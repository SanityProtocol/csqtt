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

#[cfg(any(target_os = "linux", target_os = "android"))]
fn boot_time() -> Duration {
    clock(libc::CLOCK_BOOTTIME)
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

/// The count of sleeps seen: a task that waits on a tokio timer also waits on `changed()` and looks at its deadlines
/// again. A watch, not a notify: a sleep that ends while the task is busy is still seen at its next wait.
pub(crate) fn sleeps() -> watch::Receiver<u64> {
    sleeps_seen().subscribe()
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
    let mut asleep_before = asleep();
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
        let asleep_now = asleep();
        let gap = asleep_now.saturating_sub(asleep_before);
        asleep_before = asleep_now;
        if gap >= SLEEP_SEEN {
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
