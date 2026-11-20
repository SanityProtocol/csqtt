//! M-86: PROBE, a ping that measures the tunnel itself. The app's UID is excluded from its own VPN, so a test from the
//! app measures the direct path (MRTT's first ping did exactly that). The core sends a TCP SYN from the tunnel address
//! straight to the workers, catches the SYN-ACK or RST before the TUN, closes a SYN-ACK with an RST, and emits one
//! `PROBE` event. Nothing on the server.
//!
//! stdin: `PROBE|<tunnel ip>|<target ip>|<port>` → `PROBE {"target","port","ms","result"}`, `result` one of `synack`,
//! `rst` (the path works; the port is closed), `timeout`, `no_workers`, `busy`, `invalid`.
use crate::{dispatcher::Dispatcher, events::Events, packet::PacketBuf};
use std::{
    net::{IpAddr, SocketAddr},
    sync::{
        Arc, Mutex,
        atomic::{AtomicUsize, Ordering},
    },
    time::{Duration, Instant},
};
use tokio::sync::oneshot;

pub const PROBE_TIMEOUT: Duration = Duration::from_secs(5);
const MAX_PROBES: usize = 8;
const TCP_SYN: u8 = 0x02;
const TCP_RST: u8 = 0x04;
const TCP_ACK: u8 = 0x10;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Answer {
    SynAck { seq: u32 },
    Rst,
}

struct Pending {
    local: SocketAddr,
    target: SocketAddr,
    isn: u32,
    done: Option<oneshot::Sender<Answer>>,
}

#[derive(Default)]
pub struct Probes {
    /// The table's length; zero while no probe is pending, so the downlink pays one relaxed load.
    pending: AtomicUsize,
    table: Mutex<Vec<Pending>>,
}

impl Probes {
    /// True when `packet` answers a pending probe: it is consumed and must not reach the TUN.
    #[inline(always)]
    pub fn intercept(&self, packet: &[u8]) -> bool {
        self.pending.load(Ordering::Relaxed) != 0 && self.intercept_pending(packet)
    }

    #[cold]
    fn intercept_pending(&self, packet: &[u8]) -> bool {
        let Some(segment) = parse_tcp(packet) else {
            return false;
        };
        let answer = if segment.flags & TCP_RST != 0 {
            Answer::Rst
        } else if segment.flags & (TCP_SYN | TCP_ACK) == TCP_SYN | TCP_ACK {
            Answer::SynAck { seq: segment.seq }
        } else {
            return false;
        };
        let mut table = self.lock();
        let Some(entry) = table.iter_mut().find(|pending| {
            pending.target == segment.src
                && pending.local == segment.dst
                && segment.ack == pending.isn.wrapping_add(1)
        }) else {
            return false;
        };
        if let Some(done) = entry.done.take() {
            let _ = done.send(answer);
        }
        true
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, Vec<Pending>> {
        self.table.lock().unwrap_or_else(|e| e.into_inner())
    }

    fn insert(&self, local: SocketAddr, target: SocketAddr, isn: u32) -> Option<oneshot::Receiver<Answer>> {
        let mut table = self.lock();
        if table.len() >= MAX_PROBES || table.iter().any(|pending| pending.local == local) {
            return None;
        }
        let (done, answer) = oneshot::channel();
        table.push(Pending {
            local,
            target,
            isn,
            done: Some(done),
        });
        self.pending.store(table.len(), Ordering::Relaxed);
        Some(answer)
    }

    fn remove(&self, local: SocketAddr) {
        let mut table = self.lock();
        table.retain(|pending| pending.local != local);
        self.pending.store(table.len(), Ordering::Relaxed);
    }
}

/// Takes the probe out of the table however its task ends.
struct Registration<'a> {
    probes: &'a Probes,
    local: SocketAddr,
}

impl Drop for Registration<'_> {
    fn drop(&mut self) {
        self.probes.remove(self.local);
    }
}

struct Segment {
    src: SocketAddr,
    dst: SocketAddr,
    seq: u32,
    ack: u32,
    flags: u8,
}

fn parse_tcp(packet: &[u8]) -> Option<Segment> {
    let (src, dst, offset) = match packet.first()? >> 4 {
        4 => {
            let ihl = usize::from(packet[0] & 0x0f) * 4;
            // TCP, first fragment only (a probe's answer is never fragmented)
            if ihl < 20 || packet.len() < ihl + 20 || packet[9] != 6 || packet[6] & 0x1f != 0 || packet[7] != 0 {
                return None;
            }
            let src: [u8; 4] = packet[12..16].try_into().ok()?;
            let dst: [u8; 4] = packet[16..20].try_into().ok()?;
            (IpAddr::from(src), IpAddr::from(dst), ihl)
        }
        6 => {
            if packet.len() < 60 || packet[6] != 6 {
                return None;
            }
            let src: [u8; 16] = packet[8..24].try_into().ok()?;
            let dst: [u8; 16] = packet[24..40].try_into().ok()?;
            (IpAddr::from(src), IpAddr::from(dst), 40)
        }
        _ => return None,
    };
    let tcp = &packet[offset..];
    let word = |at: usize| u32::from_be_bytes([tcp[at], tcp[at + 1], tcp[at + 2], tcp[at + 3]]);
    Some(Segment {
        src: SocketAddr::new(src, u16::from_be_bytes([tcp[0], tcp[1]])),
        dst: SocketAddr::new(dst, u16::from_be_bytes([tcp[2], tcp[3]])),
        seq: word(4),
        ack: word(8),
        flags: tcp[13],
    })
}

fn parse_request(args: &str) -> Option<(IpAddr, SocketAddr)> {
    let mut fields = args.split('|');
    let tunnel: IpAddr = fields.next()?.trim().parse().ok()?;
    let target: IpAddr = fields.next()?.trim().parse().ok()?;
    let port: u16 = fields.next()?.trim().parse().ok()?;
    if fields.next().is_some() || port == 0 || tunnel.is_ipv4() != target.is_ipv4() {
        return None;
    }
    Some((tunnel, SocketAddr::new(target, port)))
}

fn tcp_packet(
    dispatcher: &Dispatcher,
    src: SocketAddr,
    dst: SocketAddr,
    seq: u32,
    ack: u32,
    flags: u8,
) -> Option<PacketBuf> {
    let mut packet = dispatcher.pool().try_acquire()?;
    let ip_header = if src.is_ipv4() { 20 } else { 40 };
    let tcp_header = if flags & TCP_SYN != 0 { 24 } else { 20 };
    if !crate::direct_relay::craft_tcp_packet_into(src, dst, seq, ack, flags, &[], packet.read_area()) {
        return None;
    }
    packet.set_read_len(ip_header + tcp_header).ok()?;
    Some(packet)
}

fn result(target: Option<SocketAddr>, outcome: &str, ms: Option<u64>) -> serde_json::Value {
    serde_json::json!({
        "target": target.map(|t| t.ip().to_string()),
        "port": target.map(|t| t.port()),
        "ms": ms,
        "result": outcome,
    })
}

/// The stdin command: one task per probe, one `PROBE` event at its end.
pub fn start(dispatcher: Arc<Dispatcher>, args: &str, events: Events) {
    let request = parse_request(args);
    tokio::spawn(async move {
        events.probe(run(&dispatcher, request).await);
    });
}

/// tests: one probe, awaited
#[cfg(test)]
pub(crate) async fn run_line(dispatcher: &Dispatcher, args: &str) -> serde_json::Value {
    run(dispatcher, parse_request(args)).await
}

async fn run(dispatcher: &Dispatcher, request: Option<(IpAddr, SocketAddr)>) -> serde_json::Value {
    let Some((tunnel, target)) = request else {
        return result(None, "invalid", None);
    };
    let workers = dispatcher.workers_snapshot();
    if workers.is_empty() {
        return result(Some(target), "no_workers", None);
    }
    let local = SocketAddr::new(tunnel, 49_152 + (rand::random::<u16>() % 16_384));
    let isn = rand::random::<u32>();
    let Some(answer) = dispatcher.probes.insert(local, target, isn) else {
        return result(Some(target), "busy", None);
    };
    let _registration = Registration {
        probes: &dispatcher.probes,
        local,
    };
    let Some(syn) = tcp_packet(dispatcher, local, target, isn, 0, TCP_SYN) else {
        return result(Some(target), "busy", None);
    };
    let started = Instant::now();
    dispatcher.send_to_workers(syn, &workers, false).await;
    let answer = tokio::time::timeout(PROBE_TIMEOUT, answer).await;
    let ms = started.elapsed().as_millis() as u64;
    match answer {
        Ok(Ok(Answer::SynAck { .. })) => {
            // Close the half-open connection: an RST whose sequence is the one the target acknowledged.
            let workers = dispatcher.workers_snapshot();
            if !workers.is_empty()
                && let Some(rst) = tcp_packet(dispatcher, local, target, isn.wrapping_add(1), 0, TCP_RST)
            {
                dispatcher.send_to_workers(rst, &workers, false).await;
            }
            result(Some(target), "synack", Some(ms))
        }
        Ok(Ok(Answer::Rst)) => result(Some(target), "rst", Some(ms)),
        _ => result(Some(target), "timeout", None),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::packet::PacketPool;

    fn answer(dispatcher: &Dispatcher, from: SocketAddr, to: SocketAddr, ack: u32, flags: u8) -> PacketBuf {
        tcp_packet(dispatcher, from, to, 777, ack, flags).unwrap()
    }

    #[tokio::test]
    async fn probe_catches_synack_before_tun() {
        let dispatcher = Dispatcher::for_test(PacketPool::new(16), 16);
        let local: SocketAddr = "10.66.67.2:50000".parse().unwrap();
        let target: SocketAddr = "1.1.1.1:443".parse().unwrap();
        // No probe pending: a SYN-ACK is ordinary traffic.
        dispatcher.return_packet(answer(&dispatcher, target, local, 1001, TCP_SYN | TCP_ACK));
        assert!(dispatcher.pop_inbound().is_some());

        let done = dispatcher.probes.insert(local, target, 1000).unwrap();
        assert_eq!(dispatcher.probes.pending.load(Ordering::Relaxed), 1);
        // A wrong acknowledgement, another port, a plain ACK: they pass to the TUN.
        dispatcher.return_packet(answer(&dispatcher, target, local, 5, TCP_SYN | TCP_ACK));
        let other: SocketAddr = "1.1.1.1:80".parse().unwrap();
        dispatcher.return_packet(answer(&dispatcher, other, local, 1001, TCP_SYN | TCP_ACK));
        dispatcher.return_packet(answer(&dispatcher, target, local, 1001, TCP_ACK));
        let mut passed = 0;
        while dispatcher.pop_inbound().is_some() {
            passed += 1;
        }
        assert_eq!(passed, 3);
        // The answer stops before the TUN and completes the probe.
        dispatcher.return_packet(answer(&dispatcher, target, local, 1001, TCP_SYN | TCP_ACK));
        assert!(dispatcher.pop_inbound().is_none(), "the probe's SYN-ACK reached the TUN");
        assert_eq!(done.await.unwrap(), Answer::SynAck { seq: 777 });
        dispatcher.probes.remove(local);
        assert_eq!(dispatcher.probes.pending.load(Ordering::Relaxed), 0);

        // An RST answers too (the path works; the port is closed), over IPv6 as well.
        let local6: SocketAddr = "[fd00::2]:50001".parse().unwrap();
        let target6: SocketAddr = "[2001:db8::1]:22".parse().unwrap();
        let done = dispatcher.probes.insert(local6, target6, u32::MAX).unwrap();
        dispatcher.return_packet(answer(&dispatcher, target6, local6, 0, TCP_RST | TCP_ACK));
        assert!(dispatcher.pop_inbound().is_none());
        assert_eq!(done.await.unwrap(), Answer::Rst);
        dispatcher.probes.remove(local6);

        assert_eq!(
            parse_request("10.66.67.2|1.1.1.1|443"),
            Some(("10.66.67.2".parse().unwrap(), target))
        );
        for bad in ["", "10.66.67.2|1.1.1.1", "10.66.67.2|1.1.1.1|0", "10.66.67.2|::1|443", "x|1.1.1.1|443"] {
            assert_eq!(parse_request(bad), None, "{bad}");
        }
        // The table is bounded.
        for port in 0..MAX_PROBES as u16 {
            assert!(dispatcher.probes.insert(SocketAddr::new(local.ip(), 60_000 + port), target, 1).is_some());
        }
        assert!(dispatcher.probes.insert(local, target, 1).is_none());
    }

    #[tokio::test(start_paused = true)]
    async fn probe_timeout() {
        let dispatcher = Dispatcher::for_test(PacketPool::new(16), 16);
        let request = parse_request("10.66.67.2|1.1.1.1|443");
        // No worker: answered at once.
        assert_eq!(run(&dispatcher, request).await["result"], "no_workers");
        assert_eq!(run(&dispatcher, None).await["result"], "invalid");
        // Pending with no answer: `timeout` after PROBE_TIMEOUT, and the table is empty after.
        let local: SocketAddr = "10.66.67.2:50000".parse().unwrap();
        let target: SocketAddr = "1.1.1.1:443".parse().unwrap();
        let answer = dispatcher.probes.insert(local, target, 1).unwrap();
        let started = tokio::time::Instant::now();
        {
            let _registration = Registration {
                probes: &dispatcher.probes,
                local,
            };
            assert!(tokio::time::timeout(PROBE_TIMEOUT, answer).await.is_err());
        }
        assert_eq!(started.elapsed(), PROBE_TIMEOUT);
        assert_eq!(dispatcher.probes.pending.load(Ordering::Relaxed), 0);
        assert!(dispatcher.probes.lock().is_empty());
    }
}
