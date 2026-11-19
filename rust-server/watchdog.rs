// SPDX-FileCopyrightText: 2026 SanityProtocol
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
//
// S2 / M-2: a hung server is restarted, not only a crashed one. A supervisor restarts a process that exits; one that
// hangs (amurcanov #42: the session came up, GETCONF never answered) it leaves alone. Two loops already tick on their
// own: the main runtime's 1 s cpu_loop and the dataplane's 100 ms tick, each on its own runtime. Each bumps one counter
// here, and one std thread checks that both still move. A thread, because a stuck runtime cannot run its own timer.

use std::sync::atomic::{AtomicU64, Ordering};
use std::time::Duration;

static MAIN_BEAT: AtomicU64 = AtomicU64::new(0);
static DATAPLANE_BEAT: AtomicU64 = AtomicU64::new(0);

/// The main runtime's beat (cpu_loop, every second).
#[inline]
pub fn beat_main() {
    MAIN_BEAT.fetch_add(1, Ordering::Relaxed);
}

/// The dataplane's beat (its tick, every 100 ms).
#[inline]
pub fn beat_dataplane() {
    DATAPLANE_BEAT.fetch_add(1, Ordering::Relaxed);
}

const DEFAULT_PERIOD: Duration = Duration::from_secs(10);
const MIN_PERIOD: Duration = Duration::from_secs(1);
const STALL_AFTER: Duration = Duration::from_secs(60);
// EX_SOFTWARE: non-zero, so Restart=on-failure and Docker's --restart both start the server again
const STALL_EXIT_CODE: i32 = 70;

#[derive(Debug, PartialEq, Eq)]
enum Verdict {
    /// not armed yet, or a beat missed one check (no notify, no exit)
    Wait,
    /// both beats are moving; `first` = the first time since start (READY=1)
    Alive { first: bool },
    /// `which` beat has not moved for the whole stall window
    Stalled { which: &'static str },
}

const BEAT_NAMES: [&str; 2] = ["main runtime", "dataplane"];

/// The check, apart from the thread, so it can be tested. A stall is counted in checks, not wall time: after a frozen
/// VM or a suspended host the first check may see beats that did not move, but they move again within a tick.
struct Watch {
    last: [u64; 2],
    still: [u32; 2],
    armed: bool,
    stall_checks: u32,
}

impl Watch {
    fn new(period: Duration) -> Self {
        let stall_checks = STALL_AFTER.as_millis().div_ceil(period.as_millis().max(1)).max(6) as u32;
        Self { last: [0, 0], still: [0, 0], armed: false, stall_checks }
    }

    fn check(&mut self, beats: [u64; 2]) -> Verdict {
        if !self.armed {
            // armed once both loops have ticked since start (the dataplane starts after the runtime): a slow start is
            // never a stall
            if beats[0] == 0 || beats[1] == 0 {
                return Verdict::Wait;
            }
            self.armed = true;
            self.last = beats;
            return Verdict::Alive { first: true };
        }
        for i in 0..2 {
            if beats[i] == self.last[i] {
                self.still[i] += 1;
            } else {
                self.still[i] = 0;
                self.last[i] = beats[i];
            }
        }
        if let Some(i) = (0..2).find(|&i| self.still[i] >= self.stall_checks) {
            return Verdict::Stalled { which: BEAT_NAMES[i] };
        }
        // one missed check is jitter (the main beat is 1 s, the shortest period too); two in a row stop the notifies,
        // so systemd's own WatchdogSec acts on a stall as well
        if self.still.iter().all(|&s| s <= 1) { Verdict::Alive { first: false } } else { Verdict::Wait }
    }
}

/// systemd's notify socket, from the environment: `NOTIFY_SOCKET` (a path, or `@name` for an abstract socket), and
/// whether its watchdog is on for this process (`WATCHDOG_USEC`, with `WATCHDOG_PID` unset or ours).
struct Notify {
    socket: Option<std::ffi::OsString>,
    watchdog_usec: Option<u64>,
}

impl Notify {
    fn from_env() -> Self {
        let socket = std::env::var_os("NOTIFY_SOCKET").filter(|s| !s.is_empty());
        let ours = std::env::var("WATCHDOG_PID")
            .ok()
            .is_none_or(|pid| pid.trim().parse::<u32>().ok() == Some(std::process::id()));
        let watchdog_usec =
            std::env::var("WATCHDOG_USEC").ok().and_then(|v| v.trim().parse::<u64>().ok()).filter(|&u| u > 0 && ours);
        let watchdog_usec = if socket.is_some() { watchdog_usec } else { None };
        Self { socket, watchdog_usec }
    }

    fn period(&self) -> Duration {
        match self.watchdog_usec {
            Some(usec) => Duration::from_micros(usec / 2).clamp(MIN_PERIOD, DEFAULT_PERIOD),
            None => DEFAULT_PERIOD,
        }
    }
}

/// Writes one sd_notify datagram. Returns whether a socket was named and the datagram went out; nothing is opened when
/// no socket is named.
fn notify_to(socket: Option<&std::ffi::OsStr>, message: &str) -> bool {
    use std::os::unix::net::UnixDatagram;
    let Some(socket) = socket else {
        return false;
    };
    let Ok(sender) = UnixDatagram::unbound() else {
        return false;
    };
    let bytes = std::os::unix::ffi::OsStrExt::as_bytes(socket);
    let sent = if let Some(name) = bytes.strip_prefix(b"@") {
        #[cfg(target_os = "linux")]
        {
            use std::os::linux::net::SocketAddrExt;
            std::os::unix::net::SocketAddr::from_abstract_name(name)
                .and_then(|addr| sender.send_to_addr(message.as_bytes(), &addr))
        }
        #[cfg(not(target_os = "linux"))]
        {
            let _ = name;
            return false;
        }
    } else {
        sender.send_to(message.as_bytes(), socket)
    };
    sent.is_ok()
}

/// Writes straight to fd 2: a hung thread may hold the stderr lock, and this line must still get out.
fn stall_line(message: &str) {
    unsafe {
        libc::write(2, message.as_ptr().cast(), message.len());
    }
}

/// Starts the watcher. Only the server calls it, after its dataplane started.
pub fn spawn() {
    let notify = Notify::from_env();
    let period = notify.period();
    let _ = std::thread::Builder::new().name("csqtt-watchdog".to_owned()).stack_size(64 * 1024).spawn(move || {
        let mut watch = Watch::new(period);
        loop {
            // every second until both loops ticked, so a Type=notify start is not held back by a whole period
            std::thread::sleep(if watch.armed { period } else { MIN_PERIOD });
            let beats = [MAIN_BEAT.load(Ordering::Relaxed), DATAPLANE_BEAT.load(Ordering::Relaxed)];
            match watch.check(beats) {
                Verdict::Wait => {}
                Verdict::Alive { first } => {
                    if first {
                        notify_to(notify.socket.as_deref(), "READY=1");
                    }
                    if notify.watchdog_usec.is_some() {
                        notify_to(notify.socket.as_deref(), "WATCHDOG=1");
                    }
                }
                Verdict::Stalled { which } => {
                    stall_line(&format!(
                        "[WATCHDOG] the {which} made no progress for {} s; exiting so the supervisor restarts the server\n",
                        STALL_AFTER.as_secs()
                    ));
                    unsafe { libc::_exit(STALL_EXIT_CODE) };
                }
            }
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn watchdog_exits_on_stalled_loop() {
        let mut watch = Watch::new(DEFAULT_PERIOD);
        assert_eq!(watch.stall_checks, 6);
        assert_eq!(watch.check([1, 1]), Verdict::Alive { first: true });
        // the dataplane stops, the main runtime goes on
        assert_eq!(watch.check([2, 1]), Verdict::Alive { first: false }); // one missed check is jitter
        for i in 1..5 {
            assert_eq!(watch.check([2 + i, 1]), Verdict::Wait, "check {i}");
        }
        assert_eq!(watch.check([9, 1]), Verdict::Stalled { which: "dataplane" });
        // both stop
        let mut watch = Watch::new(DEFAULT_PERIOD);
        watch.check([5, 5]);
        for _ in 0..5 {
            assert_ne!(watch.check([5, 5]), Verdict::Stalled { which: "main runtime" });
        }
        assert_eq!(watch.check([5, 5]), Verdict::Stalled { which: "main runtime" });
        // the main runtime stops
        let mut watch = Watch::new(DEFAULT_PERIOD);
        watch.check([5, 5]);
        for i in 0..5 {
            assert!(!matches!(watch.check([5, 6 + i]), Verdict::Stalled { .. }));
        }
        assert_eq!(watch.check([5, 20]), Verdict::Stalled { which: "main runtime" });
        // a short period still waits the full minute
        assert_eq!(Watch::new(Duration::from_secs(1)).stall_checks, 60);
        assert_eq!(Watch::new(Duration::from_secs(3)).stall_checks, 20);
    }

    #[test]
    fn watchdog_quiet_on_live_loop() {
        let mut watch = Watch::new(DEFAULT_PERIOD);
        // not armed before both loops ticked: a slow start is never a stall
        for _ in 0..20 {
            assert_eq!(watch.check([0, 0]), Verdict::Wait);
            assert_eq!(watch.check([3, 0]), Verdict::Wait);
        }
        assert_eq!(watch.check([3, 1]), Verdict::Alive { first: true });
        for i in 0..100u64 {
            assert_eq!(watch.check([4 + i, 2 + i * 100]), Verdict::Alive { first: false });
        }
        // a frozen VM: a few checks see no beat, then the loops run again and the count starts over
        for _ in 0..5 {
            assert!(!matches!(watch.check([103, 9902]), Verdict::Stalled { .. }));
        }
        assert_eq!(watch.check([104, 9903]), Verdict::Alive { first: false });
        for _ in 0..5 {
            assert!(!matches!(watch.check([104, 9903]), Verdict::Stalled { .. }));
        }
        assert_eq!(watch.check([105, 9904]), Verdict::Alive { first: false });
        // the 1 s main beat checked every second misses a check now and then: still alive, notifies go on
        let mut watch = Watch::new(MIN_PERIOD);
        watch.check([1, 1]);
        for i in 0..50u64 {
            let main = 2 + i - i / 3; // every third check sees no new main beat
            assert_eq!(watch.check([main, 10 + i * 10]), Verdict::Alive { first: false }, "check {i}");
        }
    }

    #[test]
    fn sd_notify_only_with_socket() {
        use std::os::unix::net::UnixDatagram;
        // no socket named: nothing is sent, nothing is opened
        assert!(!notify_to(None, "READY=1"));
        // a path socket
        let dir = std::env::temp_dir().join(format!("csqtt-notify-{}", std::process::id()));
        let _ = std::fs::create_dir_all(&dir);
        let path = dir.join("notify");
        let _ = std::fs::remove_file(&path);
        let receiver = UnixDatagram::bind(&path).unwrap();
        receiver.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
        assert!(notify_to(Some(path.as_os_str()), "WATCHDOG=1"));
        let mut buf = [0u8; 64];
        let n = receiver.recv(&mut buf).unwrap();
        assert_eq!(&buf[..n], b"WATCHDOG=1");
        // an abstract socket
        #[cfg(target_os = "linux")]
        {
            use std::os::linux::net::SocketAddrExt;
            let name = format!("csqtt-notify-test-{}", std::process::id());
            let addr = std::os::unix::net::SocketAddr::from_abstract_name(name.as_bytes()).unwrap();
            let abstract_rx = UnixDatagram::bind_addr(&addr).unwrap();
            abstract_rx.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
            assert!(notify_to(Some(std::ffi::OsStr::new(&format!("@{name}"))), "READY=1"));
            let n = abstract_rx.recv(&mut buf).unwrap();
            assert_eq!(&buf[..n], b"READY=1");
        }
        // a named socket nobody listens on: the send fails, nothing else happens
        assert!(!notify_to(Some(dir.join("absent").as_os_str()), "READY=1"));
        let _ = std::fs::remove_dir_all(&dir);
        // the watchdog's period follows WATCHDOG_USEC only with a socket
        let with = Notify { socket: Some("x".into()), watchdog_usec: Some(4_000_000) };
        assert_eq!(with.period(), Duration::from_secs(2));
        let long = Notify { socket: Some("x".into()), watchdog_usec: Some(120_000_000) };
        assert_eq!(long.period(), DEFAULT_PERIOD);
        let short = Notify { socket: Some("x".into()), watchdog_usec: Some(500_000) };
        assert_eq!(short.period(), MIN_PERIOD);
        let none = Notify { socket: None, watchdog_usec: None };
        assert_eq!(none.period(), DEFAULT_PERIOD);
    }
}
