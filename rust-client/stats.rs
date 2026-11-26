use crate::events::Events;
use std::sync::{
    Arc,
    atomic::{AtomicI32, AtomicI64, Ordering},
};
use tokio_util::sync::CancellationToken;

// STATS is read by the app (the worker count its recovery watches, the traffic line, the 10 s VPN start retry); one
// that repeats the last tells it nothing. It goes out on the tick after any change, so a change still reaches the app
// within 500 ms, and otherwise every 3 s (2.1.9's rate: the retry may run up to 2.5 s later) instead of twice a second
// for the whole life of an idle tunnel, each line waking the app's reader and main thread.
const TICK: std::time::Duration = std::time::Duration::from_millis(500);
const HEARTBEAT_TICKS: u32 = 6;

type Snapshot = (i32, i64, i64, bool);

fn should_emit(last: Option<Snapshot>, now: Snapshot, ticks_since_emit: u32) -> bool {
    last != Some(now) || ticks_since_emit >= HEARTBEAT_TICKS
}

#[derive(Default)]
pub struct Stats {
    pub total_bytes_up: AtomicI64,
    pub total_bytes_down: AtomicI64,
    pub active_connections: AtomicI32,
    /// M-32: `Some` only with `CSQTT_DIAGNOSTICS=1`
    pub diag: Option<Arc<crate::diag::Diagnostics>>,
}

impl Stats {
    pub async fn run(
        self: Arc<Self>,
        events: Events,
        demand: Arc<crate::worker::DemandGate>,
        cancel: CancellationToken,
    ) {
        let mut interval = tokio::time::interval(TICK);
        interval.tick().await;
        let mut log_counter = 0u32;
        let mut last_emitted: Option<Snapshot> = None;
        let mut ticks_since_emit = 0u32;
        let mut traffic = crate::diag::Reporter::default();
        let mut traffic_ticks = 0u32;
        loop {
            let ticked = tokio::select! {
                _ = cancel.cancelled() => return,
                _ = interval.tick() => true,
                // §1.9: the app hears at once that the tunnel is at rest, before the streams that did not survive the
                // sleep are counted out, and at once that it is wanted again
                _ = demand.changed() => false,
            };
            let active = self.active_connections.load(Ordering::Relaxed);
            let up = self.total_bytes_up.load(Ordering::Relaxed);
            let down = self.total_bytes_down.load(Ordering::Relaxed);
            let now = (active, up, down, !demand.is_wanted());
            if ticked {
                log_counter += 1;
                ticks_since_emit += 1;
                if let Some(diag) = &self.diag {
                    traffic_ticks += 1;
                    if traffic_ticks >= crate::diag::REPORT_TICKS {
                        traffic_ticks = 0;
                        if let Some(payload) = traffic.report(diag, active) {
                            events.traffic(payload);
                        }
                    }
                }
            }
            if should_emit(last_emitted, now, ticks_since_emit) {
                events.stats(active, up, down, now.3);
                last_emitted = Some(now);
                ticks_since_emit = 0;
                // the log line (stderr) rides on a STATS tick: on an idle tunnel both go out together every
                // 3 s, one wakeup of the app's reader instead of two 500 ms apart
                if log_counter >= HEARTBEAT_TICKS {
                    log_counter = 0;
                    let total_mb = (up + down) as f64 / (1024.0 * 1024.0);
                    crate::log_error!("[СТАТИСТИКА] Активных: {active} | Трафик: {total_mb:.2} МБ");
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// §1.9: the app hears that the tunnel went to rest, and that it is wanted again, when it happens: a zero stream
    /// count that reached it first would start its recovery.
    #[tokio::test(start_paused = true)]
    async fn rest_and_its_end_are_said_without_waiting_for_a_tick() {
        let events = Events::new(true);
        let demand = Arc::new(crate::worker::DemandGate::new());
        let cancel = CancellationToken::new();
        let stats = Arc::new(Stats::default());
        let task = tokio::spawn(stats.run(events.clone(), demand.clone(), cancel.clone()));
        // past the first tick's STATS, in the middle of the wait for the next one
        tokio::time::sleep(TICK + TICK / 2).await;
        let dormant_said = |events: &Events| {
            let said = events.captured();
            let (kind, json) = said.last().unwrap();
            assert_eq!(kind, "STATS");
            (said.len(), json.contains("\"dormant\":true"))
        };
        let (before, resting) = dormant_said(&events);
        assert!(!resting);

        let now = tokio::time::Instant::now();
        demand.slept(std::time::Duration::from_secs(60));
        for _ in 0..8 {
            tokio::task::yield_now().await;
        }
        assert_eq!(dormant_said(&events), (before + 1, true));
        demand.want("трафик");
        for _ in 0..8 {
            tokio::task::yield_now().await;
        }
        assert_eq!(dormant_said(&events), (before + 2, false));
        assert_eq!(tokio::time::Instant::now(), now, "a tick's time passed: nothing was said at once");
        cancel.cancel();
        task.await.unwrap();
    }

    #[test]
    fn stats_sent_on_change_and_every_3s_otherwise() {
        // The first tick always goes out.
        assert!(should_emit(None, (0, 0, 0, false), 1));
        // Unchanged: held until the 6th tick (3 s), then sent.
        for ticks in 1..HEARTBEAT_TICKS {
            assert!(!should_emit(Some((10, 5, 7, false)), (10, 5, 7, false), ticks));
        }
        assert!(should_emit(Some((10, 5, 7, false)), (10, 5, 7, false), HEARTBEAT_TICKS));
        // Any change goes out on the next tick: the worker count, either byte counter.
        assert!(should_emit(Some((10, 5, 7, false)), (9, 5, 7, false), 1));
        assert!(should_emit(Some((10, 5, 7, false)), (10, 6, 7, false), 1));
        assert!(should_emit(Some((10, 5, 7, false)), (10, 5, 8, false), 1));
        // §1.9: the tunnel going to rest, or being wanted again, goes out without waiting for a tick.
        assert!(should_emit(Some((10, 5, 7, false)), (10, 5, 7, true), 0));
        assert!(should_emit(Some((0, 5, 7, true)), (0, 5, 7, false), 0));
        assert_eq!(TICK.as_millis() * HEARTBEAT_TICKS as u128, 3_000);
    }
}
