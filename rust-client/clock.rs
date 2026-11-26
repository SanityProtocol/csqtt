//! The relay's clock (APP-PLAN P3 §1.9). VK's TURN forgets a silent client: a permission after 300 s, an allocation after
//! 600 s, and the NAT in front of the phone sooner. `std::time::Instant` and tokio's timers count `CLOCK_MONOTONIC`,
//! which stands still while the phone sleeps: a keepalive due in 10 s was still due in 10 s after an hour of sleep, so
//! the 2.5 core held the CPU awake for the whole connection. The relay's deadlines are kept on [`Instant`], which
//! counts the sleep (`CLOCK_BOOTTIME`), and [`watch_sleep`] tells the tasks that wait on tokio's timers that a sleep
//! ended: every wake of the phone, whatever caused it, sends what fell due meanwhile.

use std::{
    ops::{Add, Sub},
    sync::OnceLock,
    time::Duration,
};
use tokio::sync::watch;

/// A sleep the 10 s keepalives missed: the tasks are told, and nobody is taken to want the tunnel any more.
pub(crate) const SLEEP_SEEN: Duration = Duration::from_secs(10);

/// A moment on the boot clock: `std::time::Instant`'s arithmetic, on a clock that counts the phone's sleep.
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) struct Instant(Duration);

impl Instant {
    pub(crate) fn now() -> Self {
        #[cfg(test)]
        let ahead = tests::AHEAD.with(std::cell::Cell::get);
        #[cfg(not(test))]
        let ahead = Duration::ZERO;
        Self(boot_time() + ahead)
    }

    pub(crate) fn saturating_duration_since(self, earlier: Self) -> Duration {
        self.0.saturating_sub(earlier.0)
    }
}

impl Add<Duration> for Instant {
    type Output = Self;

    fn add(self, duration: Duration) -> Self {
        Self(self.0.saturating_add(duration))
    }
}

impl Sub for Instant {
    type Output = Duration;

    fn sub(self, earlier: Self) -> Duration {
        self.saturating_duration_since(earlier)
    }
}

#[cfg(any(target_os = "linux", target_os = "android"))]
fn clock(id: libc::clockid_t) -> Duration {
    let mut time = libc::timespec { tv_sec: 0, tv_nsec: 0 };
    // SAFETY: `time` is a valid timespec for the call to fill; both clocks exist on every kernel the app runs on
    if unsafe { libc::clock_gettime(id, &mut time) } != 0 {
        return Duration::ZERO;
    }
    Duration::new(time.tv_sec as u64, time.tv_nsec as u32)
}

/// The clock the relay's deadlines are kept on: the one that counts the phone's sleep.
#[cfg(any(target_os = "linux", target_os = "android"))]
const RELAY_CLOCK: libc::clockid_t = libc::CLOCK_BOOTTIME;

#[cfg(any(target_os = "linux", target_os = "android"))]
fn boot_time() -> Duration {
    clock(RELAY_CLOCK)
}

/// Elsewhere (a desktop build of the core) nothing sleeps under the tunnel: the monotonic clock.
#[cfg(not(any(target_os = "linux", target_os = "android")))]
fn boot_time() -> Duration {
    static START: OnceLock<std::time::Instant> = OnceLock::new();
    START.get_or_init(std::time::Instant::now).elapsed()
}

fn sleeps_seen() -> &'static watch::Sender<u64> {
    static SLEEPS: OnceLock<watch::Sender<u64>> = OnceLock::new();
    SLEEPS.get_or_init(|| watch::channel(0).0)
}

/// The count of sleeps seen, for [`wait`]. A watch, not a notify: a sleep that ends while the task is busy is still
/// seen at its next wait.
pub(crate) fn sleeps() -> watch::Receiver<u64> {
    sleeps_seen().subscribe()
}

/// Waits `duration` on tokio's timer, or less when a sleep of the phone ended meanwhile: the timer stood still in the
/// sleep, and what the caller waits for is due on the boot clock. Every wait for a relay deadline is this one.
pub(crate) async fn wait(duration: Duration, sleeps: &mut watch::Receiver<u64>) {
    tokio::select! {
        _ = tokio::time::sleep(duration) => {}
        _ = sleeps.changed() => {}
    }
}

/// What the watcher makes of the time the phone has spent asleep, read at each tick of its timer. A sleep of
/// [`SLEEP_SEEN`] is said at once. Shorter naps add up over the ticks they fall in and are said when they reach it: a
/// phone that sleeps 8 s of every 10 lets tokio's timers count 2 s in 10, and a keepalive "every 10 s" would go out
/// every 50. A tick with no sleep in it starts the count over: the timers ran on time.
struct Naps {
    said: Duration,
    last: Duration,
}

impl Naps {
    /// The two clocks are read one after the other: less than this between two ticks is no sleep.
    const NONE: Duration = Duration::from_millis(100);

    fn new(asleep: Duration) -> Self {
        Self { said: asleep, last: asleep }
    }

    /// `asleep`: all the time the phone has slept so far. Returns the sleep to say, if there is one.
    fn tick(&mut self, asleep: Duration) -> Option<Duration> {
        let slept = asleep.saturating_sub(self.last) >= Self::NONE;
        self.last = asleep;
        if !slept {
            self.said = asleep;
            return None;
        }
        let unsaid = asleep.saturating_sub(self.said);
        if unsaid < SLEEP_SEEN {
            return None;
        }
        self.said = asleep;
        Some(unsaid)
    }
}

/// Sees the phone's sleeps for the process: [`sleeps`] moves and `slept` is called with each one's length.
#[cfg(any(target_os = "linux", target_os = "android"))]
pub(crate) async fn watch_sleep(slept: impl Fn(Duration)) {
    // a timer on the boot clock fires the moment the phone wakes, if it fell due in the sleep; tokio's own would first
    // count the rest of its interval, and a short wake would be over before it fired
    let timer = match boot_timer(SLEEP_SEEN) {
        Ok(timer) => timer,
        Err(error) => {
            // the deadlines are on the boot clock all the same: each task finds them when its own timer fires
            crate::log_error!("[СОН] Таймер пробуждения недоступен: {error}");
            return;
        }
    };
    let asleep = || boot_time().saturating_sub(clock(libc::CLOCK_MONOTONIC));
    let mut naps = Naps::new(asleep());
    loop {
        let Ok(mut ready) = timer.readable().await else {
            return;
        };
        let fired = ready.try_io(|timer| {
            let mut expirations = [0u8; 8];
            // SAFETY: the buffer is the 8 bytes a timerfd's read fills
            let read = unsafe {
                libc::read(
                    std::os::fd::AsRawFd::as_raw_fd(timer.get_ref()),
                    expirations.as_mut_ptr().cast(),
                    expirations.len(),
                )
            };
            if read < 0 {
                Err(std::io::Error::last_os_error())
            } else {
                Ok(())
            }
        });
        match fired {
            Ok(Ok(())) => {}
            Ok(Err(_)) => return,
            // not ready after all: the wait is armed again
            Err(_) => continue,
        }
        if let Some(gap) = naps.tick(asleep()) {
            slept(gap);
            sleeps_seen().send_modify(|count| *count += 1);
        }
    }
}

#[cfg(any(target_os = "linux", target_os = "android"))]
fn boot_timer(period: Duration) -> std::io::Result<tokio::io::unix::AsyncFd<std::os::fd::OwnedFd>> {
    use std::os::fd::{AsRawFd, FromRawFd, OwnedFd};
    // SAFETY: a plain syscall; its result is checked before it is owned
    let raw = unsafe { libc::timerfd_create(libc::CLOCK_BOOTTIME, libc::TFD_NONBLOCK | libc::TFD_CLOEXEC) };
    if raw < 0 {
        return Err(std::io::Error::last_os_error());
    }
    // SAFETY: `raw` is a descriptor this call just opened, owned by nothing else
    let timer = unsafe { OwnedFd::from_raw_fd(raw) };
    let period = libc::timespec {
        tv_sec: period.as_secs() as libc::time_t,
        tv_nsec: period.subsec_nanos() as libc::c_long,
    };
    let schedule = libc::itimerspec { it_interval: period, it_value: period };
    // SAFETY: `schedule` is a valid itimerspec, and no old value is asked for
    if unsafe { libc::timerfd_settime(timer.as_raw_fd(), 0, &schedule, std::ptr::null_mut()) } != 0 {
        return Err(std::io::Error::last_os_error());
    }
    tokio::io::unix::AsyncFd::with_interest(timer, tokio::io::Interest::READABLE)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub(crate) async fn watch_sleep(_slept: impl Fn(Duration)) {}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;
    use std::cell::Cell;

    thread_local! {
        /// The sleep a test has put this thread's clock through.
        pub(super) static AHEAD: Cell<Duration> = const { Cell::new(Duration::ZERO) };
    }

    /// The phone sleeps for `duration`: the boot clock of this thread moves on, tokio's and std's do not.
    pub(crate) fn sleep(duration: Duration) {
        AHEAD.with(|ahead| ahead.set(ahead.get() + duration));
    }

    /// The watcher saw a sleep end: every task that waits with [`wait`] looks at its deadlines.
    pub(crate) fn announce_sleep() {
        sleeps_seen().send_modify(|count| *count += 1);
    }

    /// Only a phone that sleeps can tell the two clocks apart, and no test machine sleeps: this pins which one the
    /// relay's deadlines are read from.
    #[cfg(any(target_os = "linux", target_os = "android"))]
    #[test]
    fn the_relays_clock_is_the_boot_clock() {
        assert_eq!(RELAY_CLOCK, libc::CLOCK_BOOTTIME);
        let read = clock(libc::CLOCK_BOOTTIME);
        assert!(boot_time().saturating_sub(read) < Duration::from_secs(1));
        assert!(read >= clock(libc::CLOCK_MONOTONIC).saturating_sub(Duration::from_secs(1)));
    }

    #[tokio::test(start_paused = true)]
    async fn a_wait_ends_with_its_time_or_with_a_sleep() {
        let (seen, mut sleeps) = watch::channel(0u64);
        let started = tokio::time::Instant::now();
        wait(Duration::from_secs(10), &mut sleeps).await;
        assert_eq!(started.elapsed(), Duration::from_secs(10));

        // the phone slept while the task waited: the wait ends with the sleep, not 10 s of awake time later
        let started = tokio::time::Instant::now();
        let waiting = tokio::spawn(async move { wait(Duration::from_secs(10), &mut sleeps).await });
        tokio::task::yield_now().await;
        seen.send_modify(|count| *count += 1);
        waiting.await.unwrap();
        assert_eq!(started.elapsed(), Duration::ZERO);
    }

    #[test]
    fn a_long_sleep_is_said_at_once_and_naps_add_up() {
        let seconds = Duration::from_secs;
        let mut naps = Naps::new(seconds(100));
        // awake: nothing to say, tick after tick (the two clocks' reads differ by a hair)
        assert_eq!(naps.tick(seconds(100)), None);
        assert_eq!(naps.tick(seconds(100) + Duration::from_millis(3)), None);
        // one sleep of five minutes
        assert_eq!(naps.tick(seconds(400)), Some(seconds(300) - Duration::from_millis(3)));
        assert_eq!(naps.tick(seconds(400)), None);
        // 8 s asleep in every 10: said every second tick, with what added up
        assert_eq!(naps.tick(seconds(408)), None);
        assert_eq!(naps.tick(seconds(416)), Some(seconds(16)));
        assert_eq!(naps.tick(seconds(424)), None);
        assert_eq!(naps.tick(seconds(432)), Some(seconds(16)));
        // a nap, then a tick awake: the timers ran on time, the nap is not carried into the next hour
        assert_eq!(naps.tick(seconds(439)), None);
        assert_eq!(naps.tick(seconds(439)), None);
        assert_eq!(naps.tick(seconds(446)), None);
    }

    #[test]
    fn the_boot_clock_counts_a_sleep_the_monotonic_clock_does_not() {
        let monotonic = std::time::Instant::now();
        let before = Instant::now();
        sleep(Duration::from_secs(3_600));
        let after = Instant::now();
        assert!(after - before >= Duration::from_secs(3_600));
        assert!(monotonic.elapsed() < Duration::from_secs(60));
        assert_eq!(before - after, Duration::ZERO, "an earlier moment minus a later one saturates");
        assert!(before + Duration::from_secs(10) < after);
    }

    #[cfg(any(target_os = "linux", target_os = "android"))]
    #[tokio::test]
    async fn the_boot_timer_fires_on_its_period() {
        let timer = boot_timer(Duration::from_millis(20)).unwrap();
        let started = std::time::Instant::now();
        let _fired = tokio::time::timeout(Duration::from_secs(5), timer.readable())
            .await
            .expect("the boot timer never fired")
            .unwrap();
        assert!(started.elapsed() >= Duration::from_millis(15));
    }
}
