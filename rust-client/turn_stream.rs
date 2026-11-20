use crate::{
    dpi_split::{self, DpiSplit},
    packet::{PACKET_CAPACITY, PACKET_HEADROOM, PacketBuf, PacketPool},
    turn_endpoint::{TurnEndpoint, TurnWireTransport},
};
use anyhow::{Context, Result, bail};
use bytes::{Bytes, BytesMut};
use socket2::{SockRef, TcpKeepalive};
use std::{
    net::SocketAddr,
    sync::{Arc, OnceLock},
};
use tokio::{
    io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt, ReadHalf},
    net::TcpStream,
    sync::{mpsc, watch},
    time::{Duration, timeout},
};
use turn_rustls::{ClientConfig, RootCertStore, pki_types::ServerName};
use turn_tokio_rustls::TlsConnector;

pub const MAX_CONTROL_FRAME_BYTES: usize = 4096;
const MAX_DATA_FRAME_BYTES: usize = PACKET_CAPACITY - PACKET_HEADROOM;
const CONTROL_QUEUE_CAPACITY: usize = 32;
const DATA_QUEUE_CAPACITY: usize = 64;
const TCP_SEND_BUFFER_BYTES: usize = 32 * 1024;
const STREAM_READ_BUFFER_BYTES: usize = MAX_DATA_FRAME_BYTES;
const STREAM_CONNECT_TIMEOUT: Duration = Duration::from_secs(10);

trait AsyncTurnStream: AsyncRead + AsyncWrite + Send + Unpin {}

impl<T> AsyncTurnStream for T where T: AsyncRead + AsyncWrite + Send + Unpin {}

type BoxedTurnStream = Box<dyn AsyncTurnStream>;
pub type TurnStreamWriteFailure = watch::Receiver<Option<Arc<str>>>;

pub struct TurnStreamWriter {
    control: mpsc::Sender<Bytes>,
    data: mpsc::Sender<PacketBuf>,
}

pub struct TurnStreamReader {
    inner: ReadHalf<BoxedTurnStream>,
    buffer: BytesMut,
}

pub enum TurnStreamFrame {
    Control(Vec<u8>),
    Data(PacketBuf),
    DataDropped,
}

pub async fn connect(
    endpoint: &TurnEndpoint,
    server: SocketAddr,
    dpi: DpiSplit,
) -> Result<(
    Arc<TurnStreamWriter>,
    TurnStreamReader,
    TurnStreamWriteFailure,
)> {
    let socket = timeout(STREAM_CONNECT_TIMEOUT, TcpStream::connect(server))
        .await
        .with_context(|| {
            format!(
                "TURN {} connect to {} ({server}) timed out",
                endpoint.transport.as_str(),
                endpoint.host
            )
        })?
        .with_context(|| format!("TURN {} connect {server}", endpoint.transport.as_str()))?;
    socket
        .set_nodelay(true)
        .context("TURN TCP_NODELAY configuration failed")?;
    let _ = SockRef::from(&socket).set_send_buffer_size(TCP_SEND_BUFFER_BYTES);
    let _ = SockRef::from(&socket).set_tcp_keepalive(
        &TcpKeepalive::new()
            .with_time(Duration::from_secs(120))
            .with_interval(Duration::from_secs(30)),
    );
    // Inside TLS the Allocate is encrypted, so only plain TCP splits it.
    let mut allocate_splits = 0;
    let stream: BoxedTurnStream = match endpoint.transport {
        TurnWireTransport::Tcp => {
            if dpi.is_on() {
                allocate_splits = dpi_split::ALLOCATE_SPLITS;
            }
            Box::new(socket)
        }
        TurnWireTransport::Tls => {
            let server_name = ServerName::try_from(endpoint.tls_server_name()?.to_owned())
                .context("TURN TLS server name is invalid")?;
            let connector = TlsConnector::from(tls_config());
            let stream = timeout(STREAM_CONNECT_TIMEOUT, async {
                if !dpi.is_on() {
                    return connector.connect(server_name, socket).await.map_err(anyhow::Error::from);
                }
                // The ClientHello rustls queued at creation is taken before any I/O and written
                // re-framed on the socket; the handshake then goes on from the server's answer.
                let mut hello = Vec::new();
                let mut connect = connector.connect_with(server_name, socket, |session| {
                    while session.wants_write() {
                        if session.write_tls(&mut hello).is_err() {
                            break;
                        }
                    }
                });
                if let Some(socket) = connect.get_mut() {
                    dpi_split::send_client_hello(socket, &hello, dpi).await?;
                }
                connect.await.map_err(anyhow::Error::from)
            })
            .await
            .with_context(|| {
                format!("TURN TLS handshake to {} ({server}) timed out", endpoint.host)
            })?
            .with_context(|| format!("TURN TLS handshake to {} ({server}) failed", endpoint.host))?;
            Box::new(stream)
        }
        TurnWireTransport::Udp => bail!("UDP endpoint cannot create a TURN stream"),
    };
    let (reader, writer) = tokio::io::split(stream);
    let (control_tx, control_rx) = mpsc::channel(CONTROL_QUEUE_CAPACITY);
    let (data_tx, data_rx) = mpsc::channel(DATA_QUEUE_CAPACITY);
    let (write_failure_tx, write_failure_rx) = watch::channel(None);
    tokio::spawn(writer_loop(
        writer,
        control_rx,
        data_rx,
        write_failure_tx,
        WriterDpi {
            split: dpi,
            allocates_left: allocate_splits,
        },
    ));
    Ok((
        Arc::new(TurnStreamWriter {
            control: control_tx,
            data: data_tx,
        }),
        TurnStreamReader {
            inner: reader,
            buffer: BytesMut::with_capacity(STREAM_READ_BUFFER_BYTES),
        },
        write_failure_rx,
    ))
}

impl TurnStreamWriter {
    pub async fn write_control(&self, wire: &[u8]) -> Result<()> {
        self.control
            .send(Bytes::copy_from_slice(wire))
            .await
            .context("TURN stream control queue closed")
    }

    pub fn write_data(&self, packet: PacketBuf) -> Result<()> {
        match self.data.try_send(packet) {
            Ok(()) => Ok(()),
            Err(mpsc::error::TrySendError::Full(_)) => {
                bail!("TURN stream data queue full")
            }
            Err(mpsc::error::TrySendError::Closed(_)) => {
                bail!("TURN stream data queue closed")
            }
        }
    }

    pub fn can_send(&self) -> bool {
        self.data.capacity() > 0
    }
}

enum TurnStreamOutbound {
    Control(Bytes),
    Data(PacketBuf),
}

/// The Allocate split state of one stream: `allocates_left` is 0 from the start when the split is
/// off, and drops to 0 at the first frame that is not an Allocate request.
struct WriterDpi {
    split: DpiSplit,
    allocates_left: u8,
}

async fn writer_loop<W: AsyncWrite + Unpin>(
    mut writer: W,
    mut control: mpsc::Receiver<Bytes>,
    mut data: mpsc::Receiver<PacketBuf>,
    write_failure: watch::Sender<Option<Arc<str>>>,
    mut dpi: WriterDpi,
) {
    loop {
        let next = if let Ok(control) = control.try_recv() {
            Some(TurnStreamOutbound::Control(control))
        } else if control.is_closed() {
            data.recv().await.map(TurnStreamOutbound::Data)
        } else if data.is_closed() {
            control.recv().await.map(TurnStreamOutbound::Control)
        } else {
            tokio::select! {
                biased;
                control = control.recv() => control.map(TurnStreamOutbound::Control),
                data = data.recv() => data.map(TurnStreamOutbound::Data),
            }
        };
        let Some(next) = next else {
            if control.is_closed() && data.is_closed() {
                return;
            }
            continue;
        };
        let wire = match &next {
            TurnStreamOutbound::Control(wire) => wire.as_ref(),
            TurnStreamOutbound::Data(packet) => packet.as_slice(),
        };
        let mut split_allocate = false;
        if dpi.allocates_left > 0 {
            split_allocate = matches!(next, TurnStreamOutbound::Control(_))
                && dpi_split::is_allocate_request(wire);
            dpi.allocates_left = if split_allocate { dpi.allocates_left - 1 } else { 0 };
        }
        let written = if split_allocate {
            dpi_split::write_allocate_split(&mut writer, wire, dpi.split).await
        } else {
            writer.write_all(wire).await
        };
        if let Err(error) = written {
            let _ = write_failure.send(Some(Arc::from(format!("{error}"))));
            return;
        }
    }
}

impl TurnStreamReader {
    pub async fn read_frame(&mut self, pool: &Arc<PacketPool>) -> Result<TurnStreamFrame> {
        loop {
            if let Some(frame) = self.take_frame(pool)? {
                return Ok(frame);
            }
            if self.buffer.len() >= STREAM_READ_BUFFER_BYTES {
                bail!("TURN stream frame buffer reached its hard limit")
            }
            let read = self
                .inner
                .read_buf(&mut self.buffer)
                .await
                .context("TURN stream read failed")?;
            if read == 0 {
                bail!("TURN stream closed by remote peer")
            }
        }
    }

    fn take_frame(&mut self, pool: &Arc<PacketPool>) -> Result<Option<TurnStreamFrame>> {
        match self.decode_frame(pool)? {
            StreamDecode::Frame(frame) => Ok(Some(frame)),
            StreamDecode::NeedMore => Ok(None),
        }
    }

    fn decode_frame(&mut self, pool: &Arc<PacketPool>) -> Result<StreamDecode> {
        let bytes = self.buffer.as_ref();
        let kind = match frame_start(bytes)? {
            Some(kind) => kind,
            None => return Ok(StreamDecode::NeedMore),
        };
        match kind {
            FrameKind::Control(frame_bytes) => {
                if bytes.len() < frame_bytes {
                    return Ok(StreamDecode::NeedMore);
                }
                let frame = self.buffer.split_to(frame_bytes).to_vec();
                Ok(StreamDecode::Frame(TurnStreamFrame::Control(frame)))
            }
            FrameKind::ChannelData {
                frame_bytes,
                padding,
            } => {
                if bytes.len() < frame_bytes + padding {
                    return Ok(StreamDecode::NeedMore);
                }
                let wire = self.buffer.split_to(frame_bytes + padding);
                Ok(StreamDecode::Frame(copy_channel_data(wire, pool)?))
            }
        }
    }
}

enum StreamDecode {
    Frame(TurnStreamFrame),
    NeedMore,
}

enum FrameKind {
    Control(usize),
    ChannelData { frame_bytes: usize, padding: usize },
}

fn frame_start(bytes: &[u8]) -> Result<Option<FrameKind>> {
    if bytes.len() < 4 {
        return Ok(None);
    }
    let declared = u16::from_be_bytes([bytes[2], bytes[3]]) as usize;
    if bytes[0] & 0xc0 == 0 {
        if !declared.is_multiple_of(4) {
            bail!("TURN stream control frame has an unaligned length")
        }
        let Some(frame_bytes) = 20usize.checked_add(declared) else {
            bail!("TURN stream control frame length overflows")
        };
        if frame_bytes > MAX_CONTROL_FRAME_BYTES {
            bail!("TURN stream control frame exceeds the hard limit")
        }
        if bytes.len() < 8 {
            return Ok(None);
        }
        if bytes[4..8] != [0x21, 0x12, 0xa4, 0x42] {
            bail!("TURN stream control frame has an invalid magic cookie")
        }
        return Ok(Some(FrameKind::Control(frame_bytes)));
    }
    if bytes[0] & 0xc0 != 0x40 {
        bail!("TURN stream received a frame in the reserved type range")
    }
    let Some(frame_bytes) = 4usize.checked_add(declared) else {
        bail!("TURN stream ChannelData frame length overflows")
    };
    let padding = (4 - declared % 4) % 4;
    if frame_bytes + padding > MAX_DATA_FRAME_BYTES {
        bail!("TURN stream ChannelData frame exceeds packet capacity")
    }
    Ok(Some(FrameKind::ChannelData {
        frame_bytes,
        padding,
    }))
}

fn copy_channel_data(wire: BytesMut, pool: &Arc<PacketPool>) -> Result<TurnStreamFrame> {
    let Some(mut packet) = pool.try_acquire() else {
        return Ok(TurnStreamFrame::DataDropped);
    };
    packet.set_read_len(wire.len())?;
    packet.as_mut_slice().copy_from_slice(&wire);
    Ok(TurnStreamFrame::Data(packet))
}

fn tls_config() -> Arc<ClientConfig> {
    static CONFIG: OnceLock<Arc<ClientConfig>> = OnceLock::new();
    CONFIG
        .get_or_init(|| {
            let mut roots = RootCertStore::empty();
            roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
            Arc::new(
                ClientConfig::builder()
                    .with_root_certificates(roots)
                    .with_no_client_auth(),
            )
        })
        .clone()
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::net::TcpListener;

    #[tokio::test(start_paused = true)]
    async fn turn_connect_error_names_endpoint() {
        // A peer that accepts and never answers the ClientHello: the handshake times out (the paused
        // clock runs the 10 s at once), and the error names both the host and the address dialled.
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let server = listener.local_addr().unwrap();
        let _peer = tokio::spawn(async move {
            let (_socket, _) = listener.accept().await.unwrap();
            std::future::pending::<()>().await;
        });
        let endpoint = TurnEndpoint {
            host: Arc::from("turn.example"),
            port: server.port(),
            transport: TurnWireTransport::Tls,
        };
        let error = match connect(&endpoint, server, DpiSplit::OFF).await {
            Ok(_) => panic!("a silent peer completed a TLS handshake"),
            Err(error) => format!("{error:#}"),
        };
        assert!(error.contains("timed out"), "{error}");
        assert!(error.contains("turn.example"), "{error}");
        assert!(error.contains(&server.to_string()), "{error}");
    }

    #[tokio::test]
    async fn stream_data_rejects_when_queue_full() {
        let (control, _control_rx) = mpsc::channel(1);
        let (data, mut data_rx) = mpsc::channel(1);
        let writer = Arc::new(TurnStreamWriter { control, data });
        let pool = PacketPool::new(2);
        assert!(writer.can_send());
        writer.write_data(pool.acquire()).unwrap();
        assert!(!writer.can_send());
        assert!(writer.write_data(pool.acquire()).is_err());
        drop(data_rx.recv().await);
        assert!(writer.can_send());
    }

    #[test]
    fn stream_send_buffers_cover_a_two_megabit_worker_with_rtt_headroom() {
        assert_eq!(DATA_QUEUE_CAPACITY, 64);
        let bits: u128 = (TCP_SEND_BUFFER_BYTES as u128) * 8;
        let buffered_ms = bits * 1000 / 2_000_000;
        assert!(buffered_ms <= 260);
        assert!(buffered_ms >= 50);
    }

    #[tokio::test]
    async fn stream_reader_parses_control_and_padded_channel_data() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let server = tokio::spawn(async move {
            let (mut stream, _) = listener.accept().await.unwrap();
            stream
                .write_all(&[
                    0x01, 0x01, 0x00, 0x00, 0x21, 0x12, 0xa4, 0x42, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    0, 0,
                ])
                .await
                .unwrap();
            stream
                .write_all(&[0x40, 0x00, 0x00, 0x03, 0xa1, 0xb2, 0xc3, 0])
                .await
                .unwrap();
        });
        let endpoint = TurnEndpoint {
            host: Arc::from("127.0.0.1"),
            port: address.port(),
            transport: TurnWireTransport::Tcp,
        };
        let (_, mut reader, _) = connect(&endpoint, address, DpiSplit::OFF).await.unwrap();
        match reader.read_frame(&PacketPool::new(1)).await.unwrap() {
            TurnStreamFrame::Control(frame) => assert_eq!(frame.len(), 20),
            _ => panic!("expected control frame"),
        }
        match reader.read_frame(&PacketPool::new(1)).await.unwrap() {
            TurnStreamFrame::Data(packet) => {
                assert_eq!(packet.as_slice(), &[0x40, 0, 0, 3, 0xa1, 0xb2, 0xc3, 0])
            }
            _ => panic!("expected channel data frame"),
        }
        server.await.unwrap();
    }

    #[tokio::test]
    async fn stream_reader_consumes_channel_data_padding_before_control() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let server = tokio::spawn(async move {
            let (mut stream, _) = listener.accept().await.unwrap();
            let mut wire = vec![0x40, 0x00, 0x00, 0x03, 0xa1, 0xb2, 0xc3, 0];
            wire.extend_from_slice(&[
                0x01, 0x01, 0x00, 0x00, 0x21, 0x12, 0xa4, 0x42, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            ]);
            stream.write_all(&wire).await.unwrap();
        });
        let endpoint = TurnEndpoint {
            host: Arc::from("127.0.0.1"),
            port: address.port(),
            transport: TurnWireTransport::Tcp,
        };
        let (_, mut reader, _) = connect(&endpoint, address, DpiSplit::OFF).await.unwrap();
        match reader.read_frame(&PacketPool::new(1)).await.unwrap() {
            TurnStreamFrame::Data(packet) => {
                assert_eq!(packet.as_slice(), &[0x40, 0, 0, 3, 0xa1, 0xb2, 0xc3, 0])
            }
            _ => panic!("expected channel data frame"),
        }
        match reader.read_frame(&PacketPool::new(1)).await.unwrap() {
            TurnStreamFrame::Control(frame) => assert_eq!(frame.len(), 20),
            _ => panic!("expected control frame"),
        }
        server.await.unwrap();
    }

    #[tokio::test]
    async fn stream_reader_rejects_invalid_prefix() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let server = tokio::spawn(async move {
            let (mut stream, _) = listener.accept().await.unwrap();
            stream.write_all(&[0x80, 0x00, 0xff, 0xff]).await.unwrap();
        });
        let endpoint = TurnEndpoint {
            host: Arc::from("127.0.0.1"),
            port: address.port(),
            transport: TurnWireTransport::Tcp,
        };
        let (_, mut reader, _) = connect(&endpoint, address, DpiSplit::OFF).await.unwrap();
        match reader.read_frame(&PacketPool::new(1)).await {
            Err(error) => assert!(error.to_string().contains("reserved type range")),
            Ok(_) => panic!("invalid TURN stream frame was accepted"),
        }
        server.await.unwrap();
    }

    use crate::dpi_split::{
        DpiLevel,
        tests::{Recorder, allocate, join_records, split},
    };

    /// Runs the writer loop over `frames` (true = control) with a recording writer.
    async fn written_pieces(dpi: DpiSplit, allocates_left: u8, frames: &[(bool, Vec<u8>)]) -> Vec<Vec<u8>> {
        let (control_tx, control_rx) = mpsc::channel(8);
        let (data_tx, data_rx) = mpsc::channel(8);
        let (failure_tx, _failure_rx) = watch::channel(None);
        let pool = PacketPool::new(frames.len().max(1));
        let recorder = Arc::new(std::sync::Mutex::new(Recorder::default()));
        let shared = SharedRecorder(recorder.clone());
        let writer = tokio::spawn(writer_loop(
            shared,
            control_rx,
            data_rx,
            failure_tx,
            WriterDpi { split: dpi, allocates_left },
        ));
        for (is_control, bytes) in frames {
            if *is_control {
                control_tx.send(Bytes::copy_from_slice(bytes)).await.unwrap();
            } else {
                let mut packet = pool.try_acquire().unwrap();
                packet.set_read_len(bytes.len()).unwrap();
                packet.as_mut_slice().copy_from_slice(bytes);
                data_tx.send(packet).await.unwrap();
            }
            // One frame at a time, so control's priority cannot reorder the script. A sleep, not a
            // yield: the paused clock advances only while every task waits.
            while control_tx.capacity() != 8 || data_tx.capacity() != 8 {
                tokio::time::sleep(Duration::from_millis(1)).await;
            }
        }
        drop((control_tx, data_tx));
        writer.await.unwrap();
        std::mem::take(&mut recorder.lock().unwrap().0)
    }

    struct SharedRecorder(Arc<std::sync::Mutex<Recorder>>);

    impl AsyncWrite for SharedRecorder {
        fn poll_write(
            self: std::pin::Pin<&mut Self>,
            cx: &mut std::task::Context<'_>,
            bytes: &[u8],
        ) -> std::task::Poll<std::io::Result<usize>> {
            std::pin::Pin::new(&mut *self.0.lock().unwrap()).poll_write(cx, bytes)
        }
        fn poll_flush(
            self: std::pin::Pin<&mut Self>,
            _: &mut std::task::Context<'_>,
        ) -> std::task::Poll<std::io::Result<()>> {
            std::task::Poll::Ready(Ok(()))
        }
        fn poll_shutdown(
            self: std::pin::Pin<&mut Self>,
            _: &mut std::task::Context<'_>,
        ) -> std::task::Poll<std::io::Result<()>> {
            std::task::Poll::Ready(Ok(()))
        }
    }

    fn channel_data(len: usize) -> Vec<u8> {
        let mut frame = vec![0x40, 0x00, 0x00, len as u8];
        frame.resize(4 + len, 0xab);
        frame
    }

    #[tokio::test(start_paused = true)]
    async fn two_allocates_then_passthrough() {
        let first = allocate(8);
        let retry = allocate(40);
        let third = allocate(12);
        let data = channel_data(16);
        let frames = [
            (true, first.clone()),
            (true, retry.clone()),
            (true, third.clone()),
            (false, data.clone()),
        ];
        let pieces = written_pieces(
            split(DpiLevel::Medium),
            crate::dpi_split::ALLOCATE_SPLITS,
            &frames,
        )
        .await;
        let lengths: Vec<usize> = pieces.iter().map(Vec::len).collect();
        // Two split Allocates (1/5/2/12 + body), then the third and the data whole.
        assert_eq!(lengths, [1, 5, 2, 12, 8, 1, 5, 2, 12, 40, 32, 20]);
        assert_eq!(pieces.concat(), [first.clone(), retry, third, data].concat());

        // A first frame that is not an Allocate ends the splitting for good.
        let mut refresh = allocate(8);
        refresh[1] = 0x04;
        let frames = [(true, refresh.clone()), (true, first.clone())];
        let pieces = written_pieces(
            split(DpiLevel::Heavy),
            crate::dpi_split::ALLOCATE_SPLITS,
            &frames,
        )
        .await;
        assert_eq!(pieces, [refresh, first]);
    }

    #[tokio::test(start_paused = true)]
    async fn split_off_is_unchanged_path() {
        let frames = [
            (true, allocate(8)),
            (true, allocate(40)),
            (false, channel_data(16)),
        ];
        let pieces = written_pieces(DpiSplit::OFF, 0, &frames).await;
        let expected: Vec<Vec<u8>> = frames.iter().map(|(_, bytes)| bytes.clone()).collect();
        assert_eq!(pieces, expected);
    }

    #[tokio::test]
    async fn tls_connect_sends_reframed_client_hello() {
        // A raw listener records what the client sends before it gives up on the handshake: rustls's
        // real ClientHello, re-framed into more than two records that join back into one ClientHello.
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let server = listener.local_addr().unwrap();
        let peer = tokio::spawn(async move {
            let (mut socket, _) = listener.accept().await.unwrap();
            let mut received = Vec::new();
            let mut buf = [0u8; 4096];
            loop {
                let n = socket.read(&mut buf).await.unwrap();
                received.extend_from_slice(&buf[..n]);
                if n == 0 || ends_on_record(&received) {
                    break;
                }
            }
            received
        });
        let endpoint = TurnEndpoint {
            host: Arc::from("turn.example"),
            port: server.port(),
            transport: TurnWireTransport::Tls,
        };
        let client = tokio::spawn(async move {
            let _ = connect(&endpoint, server, split(DpiLevel::Medium)).await;
        });
        let received = timeout(Duration::from_secs(5), peer).await.unwrap().unwrap();
        client.abort();
        let (payload, records) = join_records(&received);
        assert!(records > 2, "{records} records");
        assert_eq!(payload[0], 0x01, "a ClientHello");
        let host = b"turn.example";
        assert!(payload.windows(host.len()).any(|w| w == host));
        let declared = u32::from_be_bytes([0, payload[1], payload[2], payload[3]]) as usize;
        assert_eq!(declared + 4, payload.len());
    }

    /// True once `bytes` holds whole TLS records whose payloads make up a whole handshake message.
    fn ends_on_record(bytes: &[u8]) -> bool {
        let (mut payload, mut p) = (Vec::new(), 0);
        while p + 5 <= bytes.len() {
            let len = u16::from_be_bytes([bytes[p + 3], bytes[p + 4]]) as usize;
            if p + 5 + len > bytes.len() {
                return false;
            }
            payload.extend_from_slice(&bytes[p + 5..p + 5 + len]);
            p += 5 + len;
        }
        p == bytes.len()
            && payload.len() >= 4
            && u32::from_be_bytes([0, payload[1], payload[2], payload[3]]) as usize + 4 == payload.len()
    }
}
