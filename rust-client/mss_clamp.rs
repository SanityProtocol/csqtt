//! P1 Bt (the operator, 2026-11-22): a TCP MSS clamp at the tunnel's edge. The dispatcher sends an inner packet unsplit
//! only up to 1280 bytes and splits a longer one into two wire packets, which doubles each TCP segment's exposure to loss:
//! on a mobile path 2.5's downloads ran ~25–30 % slower than 2.0's (the Bt bench). A SYN or SYN-ACK crossing the tunnel
//! has its MSS option lowered so both ends send segments whose IP packet fits one wire packet. Any other packet costs a
//! few byte checks.

/// The largest inner IPv4 packet the dispatcher sends unsplit, less the IPv4 and TCP headers.
pub const TUNNEL_TCP_MSS: u16 = 1280 - 20 - 20;

/// Lowers an IPv4 TCP SYN's (or SYN-ACK's) MSS option to `max_mss` and recomputes the TCP checksum. A packet that is not
/// a whole IPv4 TCP SYN, carries no MSS option or already asks for no more is left as it is. Returns whether it changed.
pub fn clamp_tcp_mss(packet: &mut [u8], max_mss: u16) -> bool {
    if packet.len() < 40 || packet[0] >> 4 != 4 || packet[9] != 6 {
        return false;
    }
    let ihl = usize::from(packet[0] & 0x0f) * 4;
    let total = usize::from(u16::from_be_bytes([packet[2], packet[3]]));
    // a fragment (more to come, or an offset) is never a whole SYN
    if u16::from_be_bytes([packet[6], packet[7]]) & 0x3fff != 0 || ihl < 20 || total > packet.len() || total < ihl + 20 {
        return false;
    }
    let tcp = &packet[ihl..total];
    if tcp[13] & 0x02 == 0 {
        return false;
    }
    let data_offset = usize::from(tcp[12] >> 4) * 4;
    if data_offset < 20 || data_offset > tcp.len() {
        return false;
    }
    let Some(at) = mss_option_value(&tcp[..data_offset]) else {
        return false;
    };
    if u16::from_be_bytes([tcp[at], tcp[at + 1]]) <= max_mss {
        return false;
    }
    packet[ihl + at..ihl + at + 2].copy_from_slice(&max_mss.to_be_bytes());
    let checksum = tcp_checksum(packet, ihl, total);
    packet[ihl + 16..ihl + 18].copy_from_slice(&checksum.to_be_bytes());
    true
}

/// The offset of the MSS option's value within the TCP header, if it has a well-formed one.
fn mss_option_value(header: &[u8]) -> Option<usize> {
    let mut i = 20;
    while i < header.len() {
        match header[i] {
            0 => return None,
            1 => i += 1,
            kind => {
                let len = usize::from(*header.get(i + 1)?);
                if len < 2 || i + len > header.len() {
                    return None;
                }
                if kind == 2 {
                    return (len == 4).then_some(i + 2);
                }
                i += len;
            }
        }
    }
    None
}

/// The TCP checksum of the segment `packet[ihl..total]` over the IPv4 pseudo-header, its own field taken as zero.
fn tcp_checksum(packet: &[u8], ihl: usize, total: usize) -> u16 {
    let mut sum: u32 = 0;
    let mut add = |bytes: &[u8]| {
        for pair in bytes.chunks(2) {
            sum += u32::from(u16::from_be_bytes([pair[0], *pair.get(1).unwrap_or(&0)]));
        }
    };
    add(&packet[12..20]);
    let tcp_len = (total - ihl) as u16;
    add(&[0, 6]);
    add(&tcp_len.to_be_bytes());
    add(&packet[ihl..ihl + 16]);
    add(&packet[ihl + 18..total]);
    while sum > 0xffff {
        sum = (sum & 0xffff) + (sum >> 16);
    }
    !(sum as u16)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// An IPv4 TCP packet with the given flags and options, its TCP checksum valid.
    fn segment(flags: u8, options: &[u8], payload: &[u8]) -> Vec<u8> {
        let tcp_len = 20 + options.len() + payload.len();
        let total = 20 + tcp_len;
        let mut p = vec![0u8; total];
        p[0] = 0x45;
        p[2..4].copy_from_slice(&(total as u16).to_be_bytes());
        p[6] = 0x40; // DF
        p[8] = 64;
        p[9] = 6;
        p[12..16].copy_from_slice(&[10, 66, 67, 2]);
        p[16..20].copy_from_slice(&[93, 184, 216, 34]);
        p[20..22].copy_from_slice(&40000u16.to_be_bytes());
        p[22..24].copy_from_slice(&443u16.to_be_bytes());
        p[32] = ((20 + options.len()) / 4 << 4) as u8;
        p[33] = flags;
        p[34..36].copy_from_slice(&64240u16.to_be_bytes());
        p[40..40 + options.len()].copy_from_slice(options);
        p[40 + options.len()..].copy_from_slice(payload);
        let checksum = tcp_checksum(&p, 20, total);
        p[36..38].copy_from_slice(&checksum.to_be_bytes());
        p
    }

    /// A Linux SYN's options: MSS 1460, SACK permitted, timestamps, NOP, window scale 7.
    fn linux_syn_options(mss: u16) -> Vec<u8> {
        let mut o = vec![2, 4];
        o.extend_from_slice(&mss.to_be_bytes());
        o.extend_from_slice(&[4, 2, 8, 10, 0, 0, 0, 1, 0, 0, 0, 0, 1, 3, 3, 7]);
        o
    }

    fn checksum_valid(p: &[u8]) -> bool {
        u16::from_be_bytes([p[36], p[37]]) == tcp_checksum(p, 20, p.len())
    }

    fn mss_of(p: &[u8]) -> u16 {
        u16::from_be_bytes([p[42], p[43]])
    }

    #[test]
    fn a_syn_and_a_syn_ack_get_the_tunnel_mss_with_a_valid_checksum() {
        for flags in [0x02, 0x12] {
            let mut p = segment(flags, &linux_syn_options(1460), &[]);
            assert!(clamp_tcp_mss(&mut p, TUNNEL_TCP_MSS));
            assert_eq!(mss_of(&p), 1240);
            assert!(checksum_valid(&p));
        }
    }

    #[test]
    fn the_mss_option_is_found_after_nops_and_other_options() {
        let mut options = vec![1, 1, 4, 2, 2, 4];
        options.extend_from_slice(&1400u16.to_be_bytes());
        options.extend_from_slice(&[1, 3, 3, 7]);
        let mut p = segment(0x02, &options, &[]);
        assert!(clamp_tcp_mss(&mut p, TUNNEL_TCP_MSS));
        assert_eq!(u16::from_be_bytes([p[46], p[47]]), 1240);
        assert!(checksum_valid(&p));
    }

    #[test]
    fn what_is_not_a_whole_syn_asking_for_more_is_left_alone() {
        let untouched = |mut p: Vec<u8>| {
            let before = p.clone();
            assert!(!clamp_tcp_mss(&mut p, TUNNEL_TCP_MSS));
            assert_eq!(p, before);
        };
        // already at or below the tunnel's MSS
        untouched(segment(0x02, &linux_syn_options(1240), &[]));
        untouched(segment(0x02, &linux_syn_options(536), &[]));
        // an ACK with data, no SYN
        untouched(segment(0x10, &[1, 1, 8, 10, 0, 0, 0, 1, 0, 0, 0, 2], &[7; 100]));
        // a SYN with no MSS option
        untouched(segment(0x02, &[4, 2, 1, 1], &[]));
        // UDP
        let mut udp = segment(0x02, &linux_syn_options(1460), &[]);
        udp[9] = 17;
        untouched(udp);
        // IPv6
        let mut v6 = segment(0x02, &linux_syn_options(1460), &[]);
        v6[0] = 0x60;
        untouched(v6);
        // a fragment
        let mut fragment = segment(0x02, &linux_syn_options(1460), &[]);
        fragment[6] = 0x20;
        untouched(fragment);
    }

    #[test]
    fn truncated_and_malformed_packets_never_panic() {
        let p = segment(0x02, &linux_syn_options(1460), &[]);
        for cut in 0..p.len() {
            let mut short = p[..cut].to_vec();
            clamp_tcp_mss(&mut short, TUNNEL_TCP_MSS);
        }
        // an option whose length runs past the header, a zero-length option, an MSS option of the wrong length
        for options in [vec![8, 40, 0, 0], vec![8, 0, 0, 0], vec![2, 3, 5, 0]] {
            let mut bad = segment(0x02, &options, &[]);
            let before = bad.clone();
            assert!(!clamp_tcp_mss(&mut bad, TUNNEL_TCP_MSS));
            assert_eq!(bad, before);
        }
        // a total length larger than the buffer, a header length smaller than 20
        let mut long = p.clone();
        long[2..4].copy_from_slice(&2000u16.to_be_bytes());
        assert!(!clamp_tcp_mss(&mut long, TUNNEL_TCP_MSS));
        let mut short_ihl = p.clone();
        short_ihl[0] = 0x44;
        assert!(!clamp_tcp_mss(&mut short_ihl, TUNNEL_TCP_MSS));
    }
}
