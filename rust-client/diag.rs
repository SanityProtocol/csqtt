//! M-32: diagnostics behind `CSQTT_DIAGNOSTICS=1`: per-worker counters summed at the STATS tick, and stage records with
//! a bounded volume. Off by default: then `Stats.diag` is `None`, nothing here exists, and the per-packet paths only test
//! an `Option` that never changes. #19 kept 28 global atomics on the per-packet path, written from every worker, and
//! about 14 stage lines per worker session; this keeps its taxonomy, not its cost.
use crate::events::Events;
use std::{
    sync::{
        Arc, Mutex,
        atomic::{AtomicU64, Ordering},
    },
    time::{Duration, Instant},
};

/// The report goes out on every 10th STATS tick (5 s).
pub const REPORT_TICKS: u32 = 10;
/// Stage records: a burst of this many, then one per `STAGE_REFILL`.
const STAGE_BURST: u32 = 32;
const STAGE_REFILL: Duration = Duration::from_secs(2);
const REASON_CHARS: usize = 256;

/// A counter with exactly one writer task. The bump is a relaxed load and store, a plain add with no `lock` prefix;
/// the 5 s report reads it from another thread.
#[derive(Default)]
pub struct Counter(AtomicU64);

impl Counter {
    #[inline(always)]
    pub fn bump(&self, n: u64) {
        self.0
            .store(self.0.load(Ordering::Relaxed).wrapping_add(n), Ordering::Relaxed);
    }

    fn get(&self) -> u64 {
        self.0.load(Ordering::Relaxed)
    }
}

/// Written only by the dispatcher's uplink task. Aligned apart from `Down`, whose writer is another task: the two never
/// share a cache line.
#[derive(Default)]
#[repr(align(128))]
pub struct Up {
    pub packets: Counter,
    pub bytes: Counter,
    pub wrap_drops: Counter,
}

/// Written only by the worker's own reader task.
#[derive(Default)]
#[repr(align(128))]
pub struct Down {
    pub packets: Counter,
    pub bytes: Counter,
    pub auth_drops: Counter,
    pub replay_drops: Counter,
    pub non_rtp_drops: Counter,
    pub non_ip_drops: Counter,
}

/// One worker session's counters.
pub struct WorkerCounters {
    pub up: Up,
    pub down: Down,
    turn: Arc<crate::turn::TurnDrops>,
}

const NAMES: [&str; 16] = [
    "up_packets",
    "up_bytes",
    "wrap_drops",
    "down_packets",
    "down_bytes",
    "auth_drops",
    "replay_drops",
    "non_rtp_drops",
    "non_ip_drops",
    "turn_ingress_queue_drops",
    "turn_ingress_pool_drops",
    "turn_outbound_send_drops",
    "no_worker_drops",
    "tun_write_drops",
    "downlink_evicted",
    "stage_records_suppressed",
];
type Totals = [u64; NAMES.len()];

impl WorkerCounters {
    fn add_to(&self, totals: &mut Totals) {
        let (queue, pool, outbound) = self.turn.counts();
        let values = [
            self.up.packets.get(),
            self.up.bytes.get(),
            self.up.wrap_drops.get(),
            self.down.packets.get(),
            self.down.bytes.get(),
            self.down.auth_drops.get(),
            self.down.replay_drops.get(),
            self.down.non_rtp_drops.get(),
            self.down.non_ip_drops.get(),
            queue,
            pool,
            outbound,
        ];
        for (total, value) in totals.iter_mut().zip(values) {
            *total = total.wrapping_add(value);
        }
    }
}

struct StageBudget {
    tokens: u32,
    refilled: Instant,
}

impl StageBudget {
    fn take(&mut self, now: Instant) -> bool {
        let earned = now.saturating_duration_since(self.refilled).as_millis() / STAGE_REFILL.as_millis();
        if earned >= u128::from(STAGE_BURST - self.tokens) {
            // full again: the clock restarts here, so a long quiet spell cannot bank more than the burst
            self.tokens = STAGE_BURST;
            self.refilled = now;
        } else if earned > 0 {
            self.tokens += earned as u32;
            self.refilled += STAGE_REFILL * earned as u32;
        }
        if self.tokens == 0 {
            return false;
        }
        self.tokens -= 1;
        true
    }
}

pub struct Diagnostics {
    workers: Mutex<Vec<Arc<WorkerCounters>>>,
    budget: Mutex<StageBudget>,
    /// Dispatcher-wide drops, each on a drop path of one task.
    pub no_worker_drops: AtomicU64,
    pub tun_write_drops: AtomicU64,
    suppressed: AtomicU64,
}

impl Diagnostics {
    pub fn from_env() -> Option<Arc<Self>> {
        Self::from_value(std::env::var("CSQTT_DIAGNOSTICS").ok().as_deref())
    }

    fn from_value(value: Option<&str>) -> Option<Arc<Self>> {
        (value == Some("1")).then(|| Arc::new(Self::new()))
    }

    pub(crate) fn new() -> Self {
        Self {
            workers: Mutex::new(Vec::new()),
            budget: Mutex::new(StageBudget {
                tokens: STAGE_BURST,
                refilled: Instant::now(),
            }),
            no_worker_drops: AtomicU64::new(0),
            tun_write_drops: AtomicU64::new(0),
            suppressed: AtomicU64::new(0),
        }
    }

    /// A worker session's counters, registered until the session's last reference goes (slow path: once a session).
    pub fn register(&self, turn: Arc<crate::turn::TurnDrops>) -> Arc<WorkerCounters> {
        let counters = Arc::new(WorkerCounters {
            up: Up::default(),
            down: Down::default(),
            turn,
        });
        self.workers
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .push(counters.clone());
        counters
    }

    fn take_stage_token(&self, now: Instant) -> bool {
        let taken = self.budget.lock().unwrap_or_else(|e| e.into_inner()).take(now);
        if !taken {
            self.suppressed.fetch_add(1, Ordering::Relaxed);
        }
        taken
    }

    /// Sums every live worker into `retired`'s copy; a worker whose session ended (the registry holds its last `Arc`)
    /// is folded into `retired` and dropped. Returns the totals and the live worker count.
    fn totals(&self, retired: &mut Totals) -> (Totals, usize) {
        let mut workers = self.workers.lock().unwrap_or_else(|e| e.into_inner());
        let mut totals = *retired;
        workers.retain(|worker| {
            if Arc::strong_count(worker) == 1 {
                worker.add_to(retired);
                worker.add_to(&mut totals);
                false
            } else {
                worker.add_to(&mut totals);
                true
            }
        });
        let live = workers.len();
        drop(workers);
        let n = NAMES.len();
        totals[n - 4] = self.no_worker_drops.load(Ordering::Relaxed);
        totals[n - 3] = self.tun_write_drops.load(Ordering::Relaxed);
        totals[n - 2] = crate::dispatcher::downlink_evicted();
        totals[n - 1] = self.suppressed.load(Ordering::Relaxed);
        (totals, live)
    }
}

/// The STATS task's side: the previous totals and the retired workers' sums.
pub struct Reporter {
    previous: Totals,
    retired: Totals,
    window_started: Instant,
}

impl Default for Reporter {
    fn default() -> Self {
        Self {
            previous: [0; NAMES.len()],
            retired: [0; NAMES.len()],
            window_started: Instant::now(),
        }
    }
}

impl Reporter {
    /// The window's deltas as one `TRAFFIC` payload, or `None` when nothing changed.
    pub fn report(&mut self, diag: &Diagnostics, active: i32) -> Option<serde_json::Value> {
        let (totals, live) = diag.totals(&mut self.retired);
        let now = Instant::now();
        let window = now.saturating_duration_since(self.window_started);
        self.window_started = now;
        if totals == self.previous {
            return None;
        }
        let mut payload = serde_json::Map::new();
        payload.insert("window_ms".into(), (window.as_millis() as u64).into());
        payload.insert("active".into(), active.into());
        payload.insert("workers".into(), live.into());
        for (i, name) in NAMES.iter().enumerate() {
            payload.insert((*name).into(), totals[i].wrapping_sub(self.previous[i]).into());
        }
        self.previous = totals;
        Some(serde_json::Value::Object(payload))
    }
}

/// One worker session's stage record: at most two `STREAM` events (READY with the stage times, and the end).
pub struct Attempt {
    diag: Arc<Diagnostics>,
    events: Events,
    worker: usize,
    state: Mutex<AttemptState>,
}

struct AttemptState {
    turn: String,
    transport: String,
    stage: &'static str,
    stage_started: Instant,
    stages: Vec<(&'static str, u64)>,
    ready_sent: bool,
    finished: bool,
}

impl Attempt {
    pub fn new(diag: Arc<Diagnostics>, events: Events, worker: usize) -> Arc<Self> {
        Arc::new(Self {
            diag,
            events,
            worker,
            state: Mutex::new(AttemptState {
                turn: String::new(),
                transport: String::new(),
                stage: "connect",
                stage_started: Instant::now(),
                stages: Vec::with_capacity(4),
                ready_sent: false,
                finished: false,
            }),
        })
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, AttemptState> {
        self.state.lock().unwrap_or_else(|e| e.into_inner())
    }

    pub fn endpoint(&self, turn: &str, transport: String) {
        let mut state = self.lock();
        state.turn = turn.chars().take(REASON_CHARS).collect();
        state.transport = transport;
    }

    /// Closes the current stage and starts the next.
    pub fn stage(&self, next: &'static str) {
        let mut state = self.lock();
        state.close_stage();
        state.stage = next;
    }

    pub fn ready(&self) {
        let payload = {
            let mut state = self.lock();
            if state.ready_sent || state.finished {
                return;
            }
            state.close_stage();
            state.stage = "running";
            state.ready_sent = true;
            let mut stages = serde_json::Map::new();
            for (name, ms) in &state.stages {
                stages.insert((*name).into(), (*ms).into());
            }
            serde_json::json!({
                "worker": self.worker,
                "status": "ready",
                "turn": state.turn,
                "transport": state.transport,
                "stages_ms": stages,
            })
        };
        self.send(payload);
    }

    /// The session's end: `None` = closed, `Some(reason)` = an error. Only the first call counts.
    pub fn finish(&self, error: Option<&str>) {
        let payload = {
            let mut state = self.lock();
            if state.finished {
                return;
            }
            state.finished = true;
            serde_json::json!({
                "worker": self.worker,
                "status": if error.is_some() { "error" } else { "closed" },
                "stage": state.stage,
                "stage_ms": state.stage_started.elapsed().as_millis() as u64,
                "turn": state.turn,
                "transport": state.transport,
                "reason": error.map(|r| r.chars().take(REASON_CHARS).collect::<String>()),
            })
        };
        self.send(payload);
    }

    fn send(&self, payload: serde_json::Value) {
        if self.diag.take_stage_token(Instant::now()) {
            self.events.stream(payload);
        }
    }
}

impl AttemptState {
    fn close_stage(&mut self) {
        let ms = self.stage_started.elapsed().as_millis() as u64;
        if self.stage != "running" && self.stages.len() < 8 {
            self.stages.push((self.stage, ms));
        }
        self.stage_started = Instant::now();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn index(name: &str) -> usize {
        NAMES.iter().position(|n| *n == name).unwrap()
    }

    #[test]
    fn counters_sum_across_workers() {
        let diag = Diagnostics::new();
        let mut reporter = Reporter::default();
        let first = diag.register(Arc::new(crate::turn::TurnDrops::default()));
        let second = diag.register(Arc::new(crate::turn::TurnDrops::default()));
        first.up.packets.bump(3);
        first.up.bytes.bump(300);
        second.up.packets.bump(2);
        second.up.bytes.bump(200);
        second.down.replay_drops.bump(1);
        diag.no_worker_drops.fetch_add(4, Ordering::Relaxed);
        let report = reporter.report(&diag, 2).unwrap();
        assert_eq!(report["up_packets"], 5);
        assert_eq!(report["up_bytes"], 500);
        assert_eq!(report["replay_drops"], 1);
        assert_eq!(report["no_worker_drops"], 4);
        assert_eq!(report["workers"], 2);
        // An unchanged window sends nothing.
        assert!(reporter.report(&diag, 2).is_none());
        // The second worker's session ends after one more packet: it is counted once, then folded into the retired sum.
        second.up.packets.bump(1);
        drop(second);
        first.down.packets.bump(7);
        let report = reporter.report(&diag, 1).unwrap();
        assert_eq!(report["up_packets"], 1);
        assert_eq!(report["down_packets"], 7);
        assert_eq!(report["workers"], 1);
        assert!(reporter.report(&diag, 1).is_none(), "a retired worker must not count twice");
        // Every field is a window delta.
        for name in NAMES {
            assert!(report.get(name).is_some(), "{name} missing");
        }
        assert_eq!(index("stage_records_suppressed"), NAMES.len() - 1);
    }

    #[test]
    fn counters_off_by_default_cost_free() {
        assert!(Diagnostics::from_value(None).is_none());
        assert!(Diagnostics::from_value(Some("0")).is_none());
        assert!(Diagnostics::from_value(Some("1")).is_some());
        // Off, nothing exists: Stats carries no diagnostics, so no worker session registers counters.
        assert!(crate::stats::Stats::default().diag.is_none());
    }

    #[test]
    fn stage_records_bounded() {
        let diag = Arc::new(Diagnostics::new());
        let events = Events::new(true);
        // Two records per session at most, however often it is told.
        let attempt = Attempt::new(diag.clone(), events.clone(), 3);
        attempt.endpoint("turn.example:19302", "Udp".into());
        attempt.stage("channel");
        attempt.stage("srtp");
        attempt.ready();
        attempt.ready();
        attempt.finish(Some(&"я".repeat(2000)));
        attempt.finish(None);
        let records: Vec<serde_json::Value> = events
            .captured()
            .into_iter()
            .filter(|(kind, _)| kind == "STREAM")
            .map(|(_, json)| serde_json::from_str(&json).unwrap())
            .collect();
        assert_eq!(records.len(), 2);
        assert_eq!(records[0]["status"], "ready");
        assert!(records[0]["stages_ms"].get("connect").is_some());
        assert!(records[0]["stages_ms"].get("srtp").is_some());
        assert_eq!(records[1]["status"], "error");
        assert_eq!(records[1]["stage"], "running");
        assert_eq!(records[1]["reason"].as_str().unwrap().chars().count(), REASON_CHARS);
        // The process-wide budget: a burst, then one per refill; the rest are counted, not sent.
        let mut budget = StageBudget {
            tokens: STAGE_BURST,
            refilled: Instant::now(),
        };
        let now = budget.refilled;
        assert_eq!((0..100).filter(|_| budget.take(now)).count(), STAGE_BURST as usize);
        assert!(!budget.take(now + STAGE_REFILL / 2));
        assert!(budget.take(now + STAGE_REFILL));
        assert!(!budget.take(now + STAGE_REFILL));
        let flood = Arc::new(Diagnostics::new());
        for worker in 0..100 {
            Attempt::new(flood.clone(), Events::new(false), worker).finish(Some("timeout"));
        }
        assert_eq!(flood.suppressed.load(Ordering::Relaxed), 100 - STAGE_BURST as u64);
        let report = Reporter::default().report(&flood, 0).unwrap();
        assert_eq!(report["stage_records_suppressed"], 100 - STAGE_BURST as u64);
    }
}
