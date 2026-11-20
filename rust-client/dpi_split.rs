//! DPI split for TURN over TCP/TLS: the setup bytes of a TURN stream are written in pieces.
//!
//! The strategies are ported from Gooby, https://github.com/Endlad2/Gooby (revision c85b1a97,
//! Copyright 2026 Gooby contributors, Apache License 2.0; its LICENSE and NOTICE ship in
//! app/src/main/assets/licenses/). Taken: the three presets, the Allocate header split and the
//! ClientHello re-framing around the SNI, with the TTL-1 first piece. Changed: they run on the
//! client's own TcpStream, without Gooby's SOCKS5 listener, rule engine or copy loop; the TTL step
//! also covers IPv6 (IPV6_UNICAST_HOPS).
//!
//! Only setup bytes are split. Established tunnel traffic is never paced. TCP write boundaries are
//! not a wire guarantee: the bytes are the same, only how they are cut into segments changes.
use anyhow::{Result, bail};
use socket2::SockRef;
use std::{io, net::SocketAddr, ops::Range, time::Duration};
use tokio::{
    io::{AsyncWrite, AsyncWriteExt},
    net::TcpStream,
    time::sleep,
};

const STUN_HEADER_LEN: usize = 20;
const STUN_MAGIC_COOKIE: [u8; 4] = [0x21, 0x12, 0xa4, 0x42];
/// The initial Allocate and its authenticated retry after 401.
pub const ALLOCATE_SPLITS: u8 = 2;

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub enum DpiLevel {
    #[default]
    Off,
    /// Gooby's "gentle".
    Light,
    /// Gooby's "current".
    Medium,
    /// Gooby's "extended".
    Heavy,
}

/// The per-profile setting: a level, and whether the TTL-1 first piece may be used.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct DpiSplit {
    pub level: DpiLevel,
    pub low_ttl: bool,
}

impl DpiSplit {
    pub const OFF: Self = Self {
        level: DpiLevel::Off,
        low_ttl: true,
    };

    pub fn parse(level: &str, no_low_ttl: bool) -> Result<Self> {
        let level = match level.trim().to_ascii_lowercase().as_str() {
            "" | "off" => DpiLevel::Off,
            "light" => DpiLevel::Light,
            "medium" => DpiLevel::Medium,
            "heavy" => DpiLevel::Heavy,
            other => bail!("unsupported --dpi-split value {other:?} (off, light, medium, heavy)"),
        };
        Ok(Self {
            level,
            low_ttl: !no_low_ttl,
        })
    }

    pub fn is_on(self) -> bool {
        self.level != DpiLevel::Off
    }

    pub fn as_str(self) -> &'static str {
        match self.level {
            DpiLevel::Off => "off",
            DpiLevel::Light => "light",
            DpiLevel::Medium => "medium",
            DpiLevel::Heavy => "heavy",
        }
    }

    fn turn_parts(self) -> &'static [usize] {
        match self.level {
            DpiLevel::Off => &[STUN_HEADER_LEN],
            DpiLevel::Light => &[6, 20],
            DpiLevel::Medium => &[1, 6, 8, 20],
            DpiLevel::Heavy => &[1, 2, 5, 7, 8, 20],
        }
    }

    fn turn_delay(self) -> Duration {
        Duration::from_millis(match self.level {
            DpiLevel::Off => 0,
            DpiLevel::Light => 1,
            DpiLevel::Medium => 5,
            DpiLevel::Heavy => 10,
        })
    }

    fn tls_chunk(self) -> usize {
        match self.level {
            DpiLevel::Off => 0,
            DpiLevel::Light => 8,
            DpiLevel::Medium => 2,
            DpiLevel::Heavy => 1,
        }
    }

    fn tls_delay(self) -> Duration {
        Duration::from_millis(match self.level {
            DpiLevel::Off => 0,
            DpiLevel::Light => 1,
            DpiLevel::Medium => 10,
            DpiLevel::Heavy => 15,
        })
    }

    /// Gooby's gentle preset never uses the TTL-1 piece; the opt-out turns it off for the others.
    fn tls_low_ttl(self) -> bool {
        self.low_ttl && matches!(self.level, DpiLevel::Medium | DpiLevel::Heavy)
    }
}

/// A whole STUN Allocate request (method 0x001, class request) as the TURN core emits it.
pub fn is_allocate_request(wire: &[u8]) -> bool {
    if wire.len() < STUN_HEADER_LEN || wire[..2] != [0x00, 0x03] || wire[4..8] != STUN_MAGIC_COOKIE {
        return false;
    }
    let declared = u16::from_be_bytes([wire[2], wire[3]]) as usize;
    declared.is_multiple_of(4) && wire.len() == STUN_HEADER_LEN + declared
}

/// Writes an Allocate request with its 20-byte header in the preset's pieces, a pause after each
/// piece but the last, then the body whole. Every write carries the message's own bytes in order.
pub async fn write_allocate_split<W: AsyncWrite + Unpin>(
    writer: &mut W,
    wire: &[u8],
    split: DpiSplit,
) -> io::Result<()> {
    let mut start = 0;
    for &end in split.turn_parts() {
        writer.write_all(&wire[start..end]).await?;
        writer.flush().await?;
        if end != STUN_HEADER_LEN {
            sleep(split.turn_delay()).await;
        }
        start = end;
    }
    writer.write_all(&wire[STUN_HEADER_LEN..]).await
}

/// Writes the TLS ClientHello that rustls produced, re-framed so the SNI host name is spread over
/// small TLS records. Bytes that are not one ClientHello record with an SNI go out unchanged.
pub async fn send_client_hello(
    stream: &mut TcpStream,
    hello: &[u8],
    split: DpiSplit,
) -> Result<()> {
    let (first, rest) = match first_record_len(hello) {
        Some(len) => hello.split_at(len),
        None => (hello, &[][..]),
    };
    let Some(chunks) = fragment_tls_sni_record(first, split.tls_chunk()) else {
        stream.write_all(hello).await?;
        return Ok(());
    };
    let mut next = 0;
    if split.tls_low_ttl() && chunks.len() >= 2 {
        let low: Vec<u8> = chunks[..2].concat();
        next = 2;
        let restore = set_low_hop_limit(stream);
        let sent = stream.write_all(&low).await;
        sleep(split.tls_delay()).await;
        if let Some((peer, hops)) = restore {
            restore_hop_limit(stream, peer, hops)?;
        }
        sent?;
    }
    for (index, chunk) in chunks.iter().enumerate().skip(next) {
        if index > 0 && next == 0 {
            sleep(split.tls_delay()).await;
        }
        stream.write_all(chunk).await?;
    }
    stream.write_all(rest).await?;
    Ok(())
}

/// Sets the socket's TTL (IPv4) or unicast hop limit (IPv6) to 1 and returns what to restore.
/// None when it could not be set: the piece then goes out at the normal TTL.
fn set_low_hop_limit(stream: &TcpStream) -> Option<(SocketAddr, u32)> {
    let peer = stream.peer_addr().ok()?;
    let socket = SockRef::from(stream);
    let previous = if peer.is_ipv6() {
        let hops = socket.unicast_hops_v6().ok()?;
        socket.set_unicast_hops_v6(1).ok()?;
        hops
    } else {
        let ttl = socket.ttl_v4().ok()?;
        socket.set_ttl_v4(1).ok()?;
        ttl
    };
    Some((peer, previous))
}

/// A connection left at TTL 1 would lose every later segment, so a failure here fails the stream.
fn restore_hop_limit(stream: &TcpStream, peer: SocketAddr, previous: u32) -> Result<()> {
    let socket = SockRef::from(stream);
    let restored = if peer.is_ipv6() {
        socket.set_unicast_hops_v6(previous)
    } else {
        socket.set_ttl_v4(previous)
    };
    if let Err(error) = restored {
        bail!("DPI split could not restore the socket TTL: {error}");
    }
    Ok(())
}

fn first_record_len(bytes: &[u8]) -> Option<usize> {
    if bytes.len() < 5 {
        return None;
    }
    let len = 5 + u16::from_be_bytes([bytes[3], bytes[4]]) as usize;
    (len <= bytes.len()).then_some(len)
}

/// Splits a single TLS Handshake record so the SNI host name is spread across small TLS records.
/// Returns None for input that is not one ClientHello record with an SNI.
fn fragment_tls_sni_record(record: &[u8], sni_chunk_size: usize) -> Option<Vec<Vec<u8>>> {
    if sni_chunk_size == 0 || record.len() < 5 || record[0] != 0x16 {
        return None;
    }
    let record_len = u16::from_be_bytes([record[3], record[4]]) as usize;
    if record.len() != record_len + 5 {
        return None;
    }
    let payload = &record[5..];
    let sni = find_sni_hostname(payload)?;
    let version = [record[1], record[2]];

    let mut chunks = Vec::new();
    push_record(&mut chunks, version, &payload[..sni.start]);
    for piece in payload[sni.clone()].chunks(sni_chunk_size) {
        push_record(&mut chunks, version, piece);
    }
    push_record(&mut chunks, version, &payload[sni.end..]);
    (chunks.len() > 1).then_some(chunks)
}

fn push_record(out: &mut Vec<Vec<u8>>, version: [u8; 2], payload: &[u8]) {
    if payload.is_empty() {
        return;
    }
    let mut record = Vec::with_capacity(5 + payload.len());
    record.push(0x16);
    record.extend_from_slice(&version);
    // The payload is a part of one record's payload, so it fits in u16.
    record.extend_from_slice(&(payload.len() as u16).to_be_bytes());
    record.extend_from_slice(payload);
    out.push(record);
}

fn find_sni_hostname(payload: &[u8]) -> Option<Range<usize>> {
    // Handshake header: type(1) + length(3).
    if payload.len() < 4 || payload[0] != 0x01 {
        return None;
    }
    let handshake_len =
        ((payload[1] as usize) << 16) | ((payload[2] as usize) << 8) | payload[3] as usize;
    if handshake_len + 4 > payload.len() {
        return None;
    }
    let mut p = 4usize;
    take(payload, &mut p, 2 + 32)?; // legacy_version + random
    let session_len = *payload.get(p)? as usize;
    p += 1;
    take(payload, &mut p, session_len)?;
    let cipher_len = read_u16(payload, &mut p)? as usize;
    take(payload, &mut p, cipher_len)?;
    let compression_len = *payload.get(p)? as usize;
    p += 1;
    take(payload, &mut p, compression_len)?;
    let extensions_len = read_u16(payload, &mut p)? as usize;
    let extensions_end = p.checked_add(extensions_len)?;
    if extensions_end > payload.len() {
        return None;
    }
    while p + 4 <= extensions_end {
        let ext_type = read_u16(payload, &mut p)?;
        let ext_len = read_u16(payload, &mut p)? as usize;
        let ext_end = p.checked_add(ext_len)?;
        if ext_end > extensions_end {
            return None;
        }
        if ext_type == 0x0000 {
            let mut q = p;
            let list_len = read_u16(payload, &mut q)? as usize;
            let list_end = q.checked_add(list_len)?;
            if list_end > ext_end {
                return None;
            }
            while q + 3 <= list_end {
                let name_type = *payload.get(q)?;
                q += 1;
                let name_len = read_u16(payload, &mut q)? as usize;
                let name_end = q.checked_add(name_len)?;
                if name_end > list_end {
                    return None;
                }
                if name_type == 0 && name_len > 0 {
                    return Some(q..name_end);
                }
                q = name_end;
            }
        }
        p = ext_end;
    }
    None
}

fn read_u16(data: &[u8], p: &mut usize) -> Option<u16> {
    let bytes = data.get(*p..(*p + 2))?;
    *p += 2;
    Some(u16::from_be_bytes([bytes[0], bytes[1]]))
}

fn take<'a>(data: &'a [u8], p: &mut usize, len: usize) -> Option<&'a [u8]> {
    let end = p.checked_add(len)?;
    let slice = data.get(*p..end)?;
    *p = end;
    Some(slice)
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;
    use std::{
        pin::Pin,
        task::{Context, Poll},
    };

    pub(crate) const LEVELS: [DpiLevel; 3] = [DpiLevel::Light, DpiLevel::Medium, DpiLevel::Heavy];

    pub(crate) fn split(level: DpiLevel) -> DpiSplit {
        DpiSplit {
            level,
            low_ttl: true,
        }
    }

    /// Records every write as one piece.
    #[derive(Default)]
    pub(crate) struct Recorder(pub(crate) Vec<Vec<u8>>);

    impl AsyncWrite for Recorder {
        fn poll_write(
            mut self: Pin<&mut Self>,
            _: &mut Context<'_>,
            bytes: &[u8],
        ) -> Poll<io::Result<usize>> {
            self.0.push(bytes.to_vec());
            Poll::Ready(Ok(bytes.len()))
        }
        fn poll_flush(self: Pin<&mut Self>, _: &mut Context<'_>) -> Poll<io::Result<()>> {
            Poll::Ready(Ok(()))
        }
        fn poll_shutdown(self: Pin<&mut Self>, _: &mut Context<'_>) -> Poll<io::Result<()>> {
            Poll::Ready(Ok(()))
        }
    }

    pub(crate) fn allocate(body_len: usize) -> Vec<u8> {
        let mut bytes = vec![0; STUN_HEADER_LEN + body_len];
        bytes[..2].copy_from_slice(&[0, 3]);
        bytes[2..4].copy_from_slice(&(body_len as u16).to_be_bytes());
        bytes[4..8].copy_from_slice(&STUN_MAGIC_COOKIE);
        for (i, b) in bytes[8..].iter_mut().enumerate() {
            *b = i as u8;
        }
        bytes
    }

    #[tokio::test(start_paused = true)]
    async fn allocate_split_preserves_bytes() {
        let expected: [(DpiLevel, &[usize]); 3] = [
            (DpiLevel::Light, &[6, 14, 32]),
            (DpiLevel::Medium, &[1, 5, 2, 12, 32]),
            (DpiLevel::Heavy, &[1, 1, 3, 2, 1, 12, 32]),
        ];
        let wire = allocate(32);
        assert!(is_allocate_request(&wire));
        for (level, lengths) in expected {
            let mut writer = Recorder::default();
            write_allocate_split(&mut writer, &wire, split(level))
                .await
                .unwrap();
            assert_eq!(
                writer.0.iter().map(Vec::len).collect::<Vec<_>>(),
                lengths,
                "{level:?}"
            );
            assert_eq!(writer.0.concat(), wire, "{level:?}");
        }
    }

    #[test]
    fn allocate_detection_needs_a_whole_request() {
        let wire = allocate(8);
        assert!(is_allocate_request(&wire));
        assert!(!is_allocate_request(&wire[..wire.len() - 4]));
        let mut refresh = wire.clone();
        refresh[1] = 0x04;
        assert!(!is_allocate_request(&refresh));
        let mut success = wire.clone();
        success[0] = 0x01; // Allocate success response
        assert!(!is_allocate_request(&success));
        let mut no_cookie = wire;
        no_cookie[4] = 0;
        assert!(!is_allocate_request(&no_cookie));
    }

    #[test]
    fn dpi_split_parses_levels() {
        assert_eq!(DpiSplit::parse("off", false).unwrap(), DpiSplit::OFF);
        assert_eq!(DpiSplit::parse("", false).unwrap(), DpiSplit::OFF);
        assert_eq!(
            DpiSplit::parse("Medium", true).unwrap(),
            DpiSplit {
                level: DpiLevel::Medium,
                low_ttl: false
            }
        );
        assert_eq!(DpiSplit::parse("heavy", false).unwrap().as_str(), "heavy");
        assert!(DpiSplit::parse("current", false).is_err());
        assert!(!split(DpiLevel::Light).tls_low_ttl());
        assert!(split(DpiLevel::Medium).tls_low_ttl());
        assert!(!DpiSplit::parse("heavy", true).unwrap().tls_low_ttl());
    }

    #[test]
    fn client_hello_reframe_preserves_handshake() {
        let record = make_client_hello("www.youtube.com");
        for level in LEVELS {
            let chunks = fragment_tls_sni_record(&record, split(level).tls_chunk())
                .expect("SNI should be found");
            assert!(chunks.len() > 2, "{level:?}");
            assert!(chunks.iter().all(|chunk| chunk[0] == 0x16
                && chunk[1..3] == record[1..3]
                && u16::from_be_bytes([chunk[3], chunk[4]]) as usize == chunk.len() - 5));
            let rebuilt: Vec<u8> = chunks.iter().flat_map(|c| c[5..].iter().copied()).collect();
            assert_eq!(rebuilt, record[5..], "{level:?}");
        }
        assert!(fragment_tls_sni_record(b"GET / HTTP/1.1\r\n", 2).is_none());
        assert!(fragment_tls_sni_record(&make_client_hello(""), 2).is_none());
        assert!(fragment_tls_sni_record(&record[..record.len() - 1], 2).is_none());
        assert!(fragment_tls_sni_record(&record, 0).is_none());
    }

    pub(crate) fn make_client_hello(host: &str) -> Vec<u8> {
        let host = host.as_bytes();
        let mut extensions = Vec::new();
        if !host.is_empty() {
            let mut sni = Vec::new();
            sni.extend_from_slice(&((1 + 2 + host.len()) as u16).to_be_bytes());
            sni.push(0);
            sni.extend_from_slice(&(host.len() as u16).to_be_bytes());
            sni.extend_from_slice(host);
            extensions.extend_from_slice(&0u16.to_be_bytes());
            extensions.extend_from_slice(&(sni.len() as u16).to_be_bytes());
            extensions.extend_from_slice(&sni);
        }
        let mut body = vec![0x03, 0x03];
        body.extend_from_slice(&[0u8; 32]);
        body.push(0);
        body.extend_from_slice(&2u16.to_be_bytes());
        body.extend_from_slice(&[0x13, 0x01]);
        body.extend_from_slice(&[1, 0]);
        body.extend_from_slice(&(extensions.len() as u16).to_be_bytes());
        body.extend_from_slice(&extensions);
        let len = body.len();
        let mut handshake = vec![0x01, (len >> 16) as u8, (len >> 8) as u8, len as u8];
        handshake.extend_from_slice(&body);
        let mut record = vec![0x16, 0x03, 0x01];
        record.extend_from_slice(&(handshake.len() as u16).to_be_bytes());
        record.extend_from_slice(&handshake);
        record
    }

    /// Parses TLS records off the front of `bytes`; returns their payloads joined and the count.
    pub(crate) fn join_records(bytes: &[u8]) -> (Vec<u8>, usize) {
        let (mut payload, mut count, mut p) = (Vec::new(), 0, 0);
        while p + 5 <= bytes.len() {
            assert_eq!(bytes[p], 0x16, "every record is a handshake record");
            let len = u16::from_be_bytes([bytes[p + 3], bytes[p + 4]]) as usize;
            payload.extend_from_slice(&bytes[p + 5..p + 5 + len]);
            p += 5 + len;
            count += 1;
        }
        assert_eq!(p, bytes.len());
        (payload, count)
    }

    #[tokio::test]
    async fn send_client_hello_low_ttl_restores_the_ttl() {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let mut client = TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (mut server, _) = listener.accept().await.unwrap();
        let before = client.ttl().unwrap();
        let record = make_client_hello("turn.example");
        send_client_hello(&mut client, &record, split(DpiLevel::Medium))
            .await
            .unwrap();
        assert_eq!(client.ttl().unwrap(), before);
        drop(client);
        let mut received = Vec::new();
        tokio::io::AsyncReadExt::read_to_end(&mut server, &mut received)
            .await
            .unwrap();
        let (payload, count) = join_records(&received);
        assert!(count > 2);
        assert_eq!(payload, record[5..]);
    }
}
