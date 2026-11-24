// SPDX-FileCopyrightText: 2026 SanityProtocol
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
//
// swg-panel integration knobs for csqtt. Every field defaults to the upstream/stock value, so a binary
// invoked WITHOUT any of the new flags behaves like stock csqtt. Set once at boot from the CLI args (each
// instance is its own process → a process-global OnceLock is the whole config).

use std::path::PathBuf;
use std::sync::OnceLock;

#[derive(Clone, Debug)]
pub struct InstanceCfg {
    pub iface: String,          // TUN interface name (stock: csqtt1)
    pub gw_addr: String,        // gateway/.1 address (stock: 10.66.67.1)
    pub subnet_prefix: [u8; 3], // first 3 octets of the /24 (stock: 10.66.67)
    pub subnet_cidr: String,    // stock: 10.66.67.0/24
    // no_nat: the node owns forwarding, NAT and the firewall. Inert from 2.1.5 to 2.1.9 (the binary touched none of
    // it); 2.5 sets host-wide sysctls and installs FORWARD/MASQUERADE rules at start (tokio_io KernelNetworking),
    // which this flag now skips. Every swg-noded unit passes it.
    pub no_nat: bool,
    pub headless: bool,         // skip the web admin panel (binds 0.0.0.0), its TLS cert generation and
                                // reload loop, and the DPI/syscalls monitor sockets — the swg-panel owns
                                // everything via --desired, and the fixed loopback 46003/46004 collide on
                                // a multi-instance node. Stock false ⇒ unset behaves exactly like upstream.
    pub desired_path: Option<PathBuf>, // declarative desired-passwords file (panel-owned)
    // instance: the name of this server on the host (stock: csqtt). A second standalone server — the app's "next to
    // 2.1.9" deploy — runs as e.g. csqtt25 and must never touch the first: it names the systemd unit the CLI and the
    // web panel's restart act on, the netfilter comments (stock deploy.sh deletes every rule whose comment CONTAINS
    // CSQTT_MANAGED / CSQTT_TPROXY, so CSQTT25_MANAGED is out of its reach), the local-proxy policy table and fwmark
    // (stock wipes table 30001 / mark 0x7531 at every start), the TPROXY status sockets and the web session cookie.
    // Its table, mark and ports come from `slot` (S4): 2.5.3 had one shared non-default row.
    pub instance: String,
    // S4: the row of SLOTS this instance's policy table, fwmark, TPROXY ports and egress table come from. 0 is the
    // default instance's; a named one takes 1 (2.5.3's side-instance values) unless --instance-slot names another.
    pub slot: usize,
    // S5: whether the server tunes the host (sysctls, tcp_bbr) at start; --no-nat implies Off
    pub host_tuning: HostTuning,
    pub managed_tag: String,    // KernelNetworking rules (stock: CSQTT_MANAGED)
    pub tproxy_tag: String,     // local-proxy TPROXY rules, ":<port>" appended (stock: CSQTT_TPROXY)
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, clap::ValueEnum)]
pub enum HostTuning {
    Auto,
    Off,
}

pub const DEFAULT_INSTANCE: &str = "csqtt";
// Every netfilter comment stock csqtt (and its deploy.sh) deletes by substring.
const STOCK_TAGS: [&str; 6] =
    ["CSQTT_MANAGED", "CSQTT_MIRRORED", "CSQTT_TPROXY", "CSQTT_LOCAL_SOCKS", "CSQTT_SOCKS", "CSQTT_CASCADE_NO_QUIC"];

impl Default for InstanceCfg {
    fn default() -> Self {
        Self {
            iface: "csqtt1".to_owned(),
            gw_addr: "10.66.67.1".to_owned(),
            subnet_prefix: [10, 66, 67],
            subnet_cidr: "10.66.67.0/24".to_owned(),
            no_nat: false,
            headless: false,
            desired_path: None,
            instance: DEFAULT_INSTANCE.to_owned(),
            slot: 0,
            host_tuning: HostTuning::Auto,
            managed_tag: "CSQTT_MANAGED".to_owned(),
            tproxy_tag: "CSQTT_TPROXY".to_owned(),
        }
    }
}

static CFG: OnceLock<InstanceCfg> = OnceLock::new();

pub fn init(cfg: InstanceCfg) {
    let _ = CFG.set(cfg);
}
pub fn get() -> &'static InstanceCfg {
    CFG.get_or_init(InstanceCfg::default)
}
pub fn iface() -> &'static str {
    &get().iface
}
pub fn gw_addr() -> &'static str {
    &get().gw_addr
}
pub fn subnet_cidr() -> &'static str {
    &get().subnet_cidr
}
pub fn subnet_prefix() -> [u8; 3] {
    get().subnet_prefix
}
// The address `last` of this instance's /24 (stock: 10.66.67.<last>).
pub fn subnet_addr(last: u8) -> String {
    let p = subnet_prefix();
    format!("{}.{}.{}.{last}", p[0], p[1], p[2])
}
pub fn no_nat() -> bool {
    get().no_nat
}
pub fn headless() -> bool {
    get().headless
}
pub fn host_tuning() -> HostTuning {
    get().host_tuning
}
// S5: whether this server writes host-wide sysctls at all — at start and when an exit comes up. --no-nat implies off.
pub fn tunes_host_for(tuning: HostTuning, no_nat: bool) -> bool {
    tuning == HostTuning::Auto && !no_nat
}
pub fn desired_path() -> Option<&'static PathBuf> {
    get().desired_path.as_ref()
}

pub fn instance() -> &'static str {
    &get().instance
}
pub fn is_default_instance() -> bool {
    get().instance == DEFAULT_INSTANCE
}
pub fn managed_tag() -> &'static str {
    &get().managed_tag
}
// P2 Bsrv: the chain a new flow from the tunnel enters (tokio_io.rs ForwardScope). The longest, a 15-character instance's
// `…_MANAGED_FWD`, is 27 characters, inside iptables' 28.
pub fn forward_chain_for(managed_tag: &str) -> String {
    format!("{managed_tag}_FWD")
}
pub fn forward_chain() -> &'static str {
    static CHAIN: OnceLock<String> = OnceLock::new();
    CHAIN.get_or_init(|| forward_chain_for(managed_tag()))
}
pub fn tproxy_tag() -> &'static str {
    &get().tproxy_tag
}
// S4 / M-3: the per-instance values, one row per slot. Stock owns table/priority 30001 and fwmark 0x7531 and flushes
// them at every start; 2.5.3's side instance took 30002 / 0x7532 / 10766. A mark is matched with its own value as the
// mask, and every mark here is 0x7530 plus one bit 0x7530 lacks, so for any two of them a & b = 0x7530, which is neither:
// no rule sees another slot's packets. TPROXY ports start at the slot's base plus the profile id; they can still meet
// another slot's range, which the listener's EADDRINUSE retry already handles. The egress block (D7 / M-179) sits after
// the local table, so the clients still reach the server itself, and before main, where their traffic would leave
// directly.
pub struct SlotValues {
    pub table: &'static str,
    pub mark: &'static str,
    pub egress_table: &'static str,
    pub tproxy_base_port: u16,
}

pub const SLOTS: [SlotValues; 8] = [
    SlotValues { table: "30001", mark: "0x7531/0x7531", egress_table: "30011", tproxy_base_port: 10666 },
    SlotValues { table: "30002", mark: "0x7532/0x7532", egress_table: "30012", tproxy_base_port: 10766 },
    SlotValues { table: "30003", mark: "0x7534/0x7534", egress_table: "30013", tproxy_base_port: 10866 },
    SlotValues { table: "30004", mark: "0x7538/0x7538", egress_table: "30014", tproxy_base_port: 10966 },
    SlotValues { table: "30005", mark: "0x7570/0x7570", egress_table: "30015", tproxy_base_port: 11066 },
    SlotValues { table: "30006", mark: "0x75b0/0x75b0", egress_table: "30016", tproxy_base_port: 11166 },
    SlotValues { table: "30007", mark: "0x7730/0x7730", egress_table: "30017", tproxy_base_port: 11266 },
    SlotValues { table: "30008", mark: "0x7d30/0x7d30", egress_table: "30018", tproxy_base_port: 11366 },
];

fn slot() -> &'static SlotValues {
    &SLOTS[get().slot]
}
pub fn tproxy_table() -> &'static str {
    slot().table
}
pub fn tproxy_mark() -> &'static str {
    slot().mark
}
pub fn egress_block_table() -> &'static str {
    slot().egress_table
}
pub fn tproxy_base_port() -> u16 {
    slot().tproxy_base_port
}
// M-6 / S4: the DPI and syscalls monitor sockets bind the fixed loopback 46003/46004, which `csqtt dpi` dials. Only the
// default instance with its web panel runs them; a second instance's would collide.
pub fn diag_sockets() -> bool {
    diag_sockets_for(get())
}
fn diag_sockets_for(cfg: &InstanceCfg) -> bool {
    cfg.instance == DEFAULT_INSTANCE && !cfg.headless
}
// Two panels on one host share its cookies (cookies are not port-scoped): a login to one logged the other out.
pub fn session_cookie() -> String {
    if is_default_instance() { "csqtt_session".to_owned() } else { format!("{}_session", get().instance) }
}

// Under --desired the node runs no IPv6 for csqtt (the TUN carries no v6 address and nothing routes it), so an IPv6
// uplink packet is dropped like a spoofed IPv4 source — stock passes any non-IPv4 packet unchecked.
pub fn ipv4_only_uplink() -> bool {
    desired_path().is_some()
}

// Build a config from the optional flags. Only /24 is accepted: the datapath route table indexes a session
// by the packet's last octet, so a differently-sized mask would need a different table shape.
pub fn build(
    iface: Option<String>,
    tun_addr: Option<String>,
    no_nat: bool,
    headless: bool,
    desired: Option<PathBuf>,
    instance: Option<String>,
    slot: Option<usize>,
    host_tuning: HostTuning,
) -> anyhow::Result<InstanceCfg> {
    let mut cfg = InstanceCfg::default();
    if let Some(name) = instance
        && !name.is_empty()
        && name != DEFAULT_INSTANCE
    {
        // a unit name, a path component and a netfilter comment: lower-case letters, digits and '-' only, so the
        // tag never gains the '_' that would make it a superstring of a stock one ("CSQTT_…")
        let ok = name.len() <= 15
            && name.starts_with(|c: char| c.is_ascii_lowercase())
            && name.chars().all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '-');
        if !ok {
            anyhow::bail!("--instance must be 1-15 of a-z, 0-9 and '-', starting with a letter (got {name})");
        }
        let up = name.to_ascii_uppercase();
        cfg.managed_tag = format!("{up}_MANAGED");
        cfg.tproxy_tag = format!("{up}_TPROXY");
        // …and a name ending in "csqtt" (old-csqtt → OLD-CSQTT_MANAGED) still contains a stock tag
        for tag in [&cfg.managed_tag, &cfg.tproxy_tag] {
            if let Some(stock) = STOCK_TAGS.iter().find(|stock| tag.contains(*stock)) {
                anyhow::bail!("--instance {name}: its rule tag {tag} contains the stock {stock}, which stock csqtt deletes");
            }
        }
        cfg.instance = name;
        cfg.slot = 1;
    }
    if let Some(n) = slot {
        if cfg.instance == DEFAULT_INSTANCE {
            anyhow::bail!("--instance-slot is for a named instance (--instance <name>); the default instance is slot 0");
        }
        if !(1..SLOTS.len()).contains(&n) {
            anyhow::bail!("--instance-slot must be 1-{} (got {n})", SLOTS.len() - 1);
        }
        cfg.slot = n;
    }
    if let Some(i) = iface
        && !i.is_empty()
    {
        cfg.iface = i;
    }
    if let Some(a) = tun_addr {
        let a = a.trim();
        if !a.is_empty() {
            let (ip_part, mask) = a.split_once('/').unwrap_or((a, "24"));
            if mask != "24" {
                anyhow::bail!("--tun-addr must be a /24 (got /{mask})");
            }
            let octs: Vec<u8> = ip_part
                .split('.')
                .map(|o| o.parse::<u8>())
                .collect::<Result<_, _>>()
                .map_err(|_| anyhow::anyhow!("bad --tun-addr {a}"))?;
            if octs.len() != 4 {
                anyhow::bail!("bad --tun-addr {a}");
            }
            // .2 is the owner's address and .3–.250 the client pool (ip_in_pool, get_next_ip): a server address in
            // there is handed to a client too, whose downlink then stays on the server. Outside that range anything
            // a node already uses keeps working.
            if (2..=250).contains(&octs[3]) {
                anyhow::bail!("--tun-addr {a}: the server's address must not be in the client range .2–.250 (use .1)");
            }
            cfg.subnet_prefix = [octs[0], octs[1], octs[2]];
            cfg.gw_addr = format!("{}.{}.{}.{}", octs[0], octs[1], octs[2], octs[3]);
            cfg.subnet_cidr = format!("{}.{}.{}.0/24", octs[0], octs[1], octs[2]);
        }
    }
    cfg.no_nat = no_nat;
    cfg.headless = headless;
    cfg.desired_path = desired;
    cfg.host_tuning = host_tuning;
    Ok(cfg)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tun_addr_in_the_client_range_is_refused() {
        for bad in ["10.66.68.2/24", "10.66.68.5/24", "10.66.68.250/24"] {
            assert!(build(None, Some(bad.to_owned()), false, false, None, None, None, HostTuning::Auto).is_err(), "{bad}");
        }
        for ok in ["10.66.68.1/24", "10.66.68.0/24", "10.66.68.254/24"] {
            assert!(build(None, Some(ok.to_owned()), false, false, None, None, None, HostTuning::Auto).is_ok(), "{ok}");
        }
    }

    #[test]
    fn no_flags_is_stock() {
        let cfg = build(None, None, false, false, None, None, None, HostTuning::Auto).unwrap();
        assert_eq!(cfg.iface, "csqtt1");
        assert_eq!(cfg.gw_addr, "10.66.67.1");
        assert_eq!(cfg.subnet_prefix, [10, 66, 67]);
        assert_eq!(cfg.subnet_cidr, "10.66.67.0/24");
        assert!(!cfg.no_nat && !cfg.headless && cfg.desired_path.is_none());
        assert_eq!(cfg.instance, "csqtt");
        assert_eq!((cfg.managed_tag.as_str(), cfg.tproxy_tag.as_str()), ("CSQTT_MANAGED", "CSQTT_TPROXY"));
        // naming the default instance is the same as not naming one
        let named = build(None, None, false, false, None, Some("csqtt".into()), None, HostTuning::Auto).unwrap();
        assert_eq!((named.instance.as_str(), named.managed_tag.as_str()), ("csqtt", "CSQTT_MANAGED"));
    }

    #[test]
    fn a_side_instance_is_out_of_stock_cleanup_reach() {
        let cfg = build(Some("csqtt25".into()), Some("10.66.68.1/24".into()), false, false, None, Some("csqtt25".into()), None, HostTuning::Auto).unwrap();
        assert_eq!(cfg.instance, "csqtt25");
        assert_eq!((cfg.managed_tag.as_str(), cfg.tproxy_tag.as_str()), ("CSQTT25_MANAGED", "CSQTT25_TPROXY"));
        // stock deploy.sh deletes every rule whose comment CONTAINS one of these; ours must contain none of them
        for stock in ["CSQTT_MANAGED", "CSQTT_MIRRORED", "CSQTT_TPROXY", "CSQTT_LOCAL_SOCKS", "CSQTT_SOCKS", "CSQTT_CASCADE_NO_QUIC"] {
            assert!(!cfg.managed_tag.contains(stock) && !cfg.tproxy_tag.contains(stock), "{stock}");
        }
        for bad in ["Csqtt25", "csqtt_25", "25csqtt", "csqtt/25", "a-very-long-instance-name", "old-csqtt", "xcsqtt"] {
            assert!(build(None, None, false, false, None, Some(bad.into()), None, HostTuning::Auto).is_err(), "{bad}");
        }
    }

    #[test]
    fn tun_addr_sets_the_whole_subnet() {
        let cfg = build(Some("csqtt7".into()), Some("10.66.71.1/24".into()), true, true, None, None, None, HostTuning::Auto).unwrap();
        assert_eq!(cfg.iface, "csqtt7");
        assert_eq!(cfg.gw_addr, "10.66.71.1");
        assert_eq!(cfg.subnet_prefix, [10, 66, 71]);
        assert_eq!(cfg.subnet_cidr, "10.66.71.0/24");
        assert!(cfg.no_nat && cfg.headless);
        // a bare address means /24
        assert_eq!(build(None, Some("10.9.8.1".into()), false, false, None, None, None, HostTuning::Auto).unwrap().subnet_prefix, [10, 9, 8]);
    }

    #[test]
    fn only_a_24_is_accepted() {
        assert!(build(None, Some("10.66.71.1/16".into()), false, false, None, None, None, HostTuning::Auto).is_err());
        assert!(build(None, Some("10.66.71/24".into()), false, false, None, None, None, HostTuning::Auto).is_err());
        assert!(build(None, Some("10.66.300.1/24".into()), false, false, None, None, None, HostTuning::Auto).is_err());
    }

    #[test]
    fn default_instance_values_unchanged() {
        // the default instance: stock's values byte for byte
        assert_eq!(build(None, None, false, false, None, None, None, HostTuning::Auto).unwrap().slot, 0);
        let s = &SLOTS[0];
        assert_eq!((s.table, s.mark, s.egress_table, s.tproxy_base_port), ("30001", "0x7531/0x7531", "30011", 10666));
        // a named instance without --instance-slot: 2.5.3's side instance byte for byte
        let named = build(None, None, false, false, None, Some("csqtt25".into()), None, HostTuning::Auto).unwrap();
        assert_eq!(named.slot, 1);
        let s = &SLOTS[1];
        assert_eq!((s.table, s.mark, s.egress_table, s.tproxy_base_port), ("30002", "0x7532/0x7532", "30012", 10766));
        // the slot flag: only for a named instance, and only 1..=7
        assert_eq!(build(None, None, false, false, None, Some("side".into()), Some(5), HostTuning::Auto).unwrap().slot, 5);
        for bad in [0, 8, 100] {
            assert!(build(None, None, false, false, None, Some("side".into()), Some(bad), HostTuning::Auto).is_err(), "{bad}");
        }
        assert!(build(None, None, false, false, None, None, Some(1), HostTuning::Auto).is_err());
        assert!(build(None, None, false, false, None, Some("csqtt".into()), Some(1), HostTuning::Auto).is_err());
    }

    #[test]
    fn instance_values_distinct() {
        let marks: Vec<u32> = SLOTS
            .iter()
            .map(|s| {
                let (value, mask) = s.mark.split_once('/').unwrap();
                assert_eq!(value, mask, "a mark is matched with its own value as the mask");
                u32::from_str_radix(value.trim_start_matches("0x"), 16).unwrap()
            })
            .collect();
        for (i, a) in marks.iter().enumerate() {
            for (j, b) in marks.iter().enumerate() {
                if i != j {
                    // a packet marked a never matches the rule `fwmark b/b`, and the reverse
                    assert_ne!(a & b, *b, "slot {i}'s mark {a:#x} matches slot {j}'s rule {b:#x}");
                }
            }
        }
        let mut numbers = std::collections::HashSet::new();
        for (i, s) in SLOTS.iter().enumerate() {
            assert!(numbers.insert(s.table), "table {}", s.table);
            assert!(numbers.insert(s.egress_table), "egress table {}", s.egress_table);
            // each a rule priority too: after local (0), before main (32766) and default (32767)
            for n in [s.table, s.egress_table] {
                let n: u32 = n.parse().unwrap();
                assert!((1..32766).contains(&n));
            }
            if i > 0 {
                assert_eq!(s.tproxy_base_port, SLOTS[i - 1].tproxy_base_port + 100, "slot {i}'s port block");
            }
        }
        // the same block: slot 0's local table first, its egress block after every policy table
        assert!(SLOTS.iter().all(|s| s.table < SLOTS[0].egress_table));
    }

    #[test]
    fn diag_ports_off_for_named_instance() {
        let rule = diag_sockets_for;
        assert!(rule(&build(None, None, false, false, None, None, None, HostTuning::Auto).unwrap()));
        assert!(!rule(&build(None, None, false, true, None, None, None, HostTuning::Auto).unwrap()));
        assert!(!rule(&build(None, None, false, false, None, Some("csqtt25".into()), None, HostTuning::Auto).unwrap()));
        assert!(!rule(&build(None, None, false, false, None, Some("side".into()), Some(3), HostTuning::Auto).unwrap()));
    }
}
