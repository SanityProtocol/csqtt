//! P1 Bt part 2: the pieces of one IPv4 datagram stay on one worker.
//!
//! A datagram longer than an MTU on its way is cut into IPv4 fragments, and only the first piece carries the ports, so
//! a flow key gives a later piece a flow of its own (port-less; a short tail is "small UDP", pinned) on another worker
//! than its head. The client meets them at its TUN (an MTU of 1280 and an app that sends a larger datagram), the server
//! on a client's downlink (a datagram the internet delivered in pieces). Each side remembers the worker a datagram's first
//! piece took and sends the later pieces there, as the core's own split keeps a packet's pieces on one worker; with it the
//! first piece's flow, so a later piece counts in that flow's burst. Pieces come back to back, so a few slots, direct-
//! mapped by the IP ID, cover the datagrams in flight. IPv6 is not covered: the flow keys read a fragment header's next
//! header (44) as the protocol and no ports, so every piece between two hosts shares one key already.

const SLOTS: usize = 16;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct DatagramId {
    pub(crate) src: [u8; 4],
    pub(crate) dst: [u8; 4],
    pub(crate) proto: u8,
    pub(crate) id: u16,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct Fragment {
    pub(crate) datagram: DatagramId,
    /// The piece at offset 0, the one with the ports.
    pub(crate) first: bool,
}

/// An IPv4 fragment (more fragments follow, or an offset): its datagram and whether it is the first piece.
pub(crate) fn ipv4_fragment(packet: &[u8]) -> Option<Fragment> {
    if packet.len() < 20 || packet[0] >> 4 != 4 {
        return None;
    }
    let flags_offset = u16::from_be_bytes([packet[6], packet[7]]);
    if flags_offset & 0x3fff == 0 {
        return None;
    }
    Some(Fragment {
        datagram: DatagramId {
            src: [packet[12], packet[13], packet[14], packet[15]],
            dst: [packet[16], packet[17], packet[18], packet[19]],
            proto: packet[9],
            id: u16::from_be_bytes([packet[4], packet[5]]),
        },
        first: flags_offset & 0x1fff == 0,
    })
}

/// The worker each recent datagram's first piece took, and that piece's flow (`F`, the caller's flow key). A worker index
/// is the caller's; clear the routes whenever the indices may change meaning.
pub(crate) struct FragmentRoutes<F> {
    slots: [Option<(DatagramId, usize, F)>; SLOTS],
}

impl<F: Copy> Default for FragmentRoutes<F> {
    fn default() -> Self {
        Self { slots: [None; SLOTS] }
    }
}

impl<F: Copy> FragmentRoutes<F> {
    fn slot(datagram: &DatagramId) -> usize {
        datagram.id as usize % SLOTS
    }

    /// Remembers a first piece's worker and flow; a later piece records nothing.
    pub(crate) fn record(&mut self, fragment: Fragment, idx: usize, flow: F) {
        if fragment.first {
            self.slots[Self::slot(&fragment.datagram)] = Some((fragment.datagram, idx, flow));
        }
    }

    /// The worker a later piece follows and the flow it counts in: its first piece's, while `usable` says that worker can
    /// still take it.
    pub(crate) fn follow(&self, fragment: Fragment, usable: impl Fn(usize) -> bool) -> Option<(usize, F)> {
        if fragment.first {
            return None;
        }
        match self.slots[Self::slot(&fragment.datagram)] {
            Some((datagram, idx, flow)) if datagram == fragment.datagram && usable(idx) => Some((idx, flow)),
            _ => None,
        }
    }

    pub(crate) fn clear(&mut self) {
        self.slots = [None; SLOTS];
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// One piece of a UDP datagram cut at a 1280 MTU: `offset` in 8-byte units, `more` = MF.
    fn piece(id: u16, offset: u16, more: bool, len: usize) -> Vec<u8> {
        let mut packet = vec![0u8; len];
        packet[0] = 0x45;
        packet[2..4].copy_from_slice(&(len as u16).to_be_bytes());
        packet[4..6].copy_from_slice(&id.to_be_bytes());
        packet[6..8].copy_from_slice(&((u16::from(more) << 13) | offset).to_be_bytes());
        packet[9] = 17;
        packet[12..16].copy_from_slice(&[10, 66, 67, 2]);
        packet[16..20].copy_from_slice(&[203, 0, 113, 7]);
        if offset == 0 {
            packet[20..22].copy_from_slice(&40000u16.to_be_bytes());
            packet[22..24].copy_from_slice(&443u16.to_be_bytes());
        }
        packet
    }

    fn fragment(id: u16, offset: u16, more: bool) -> Fragment {
        ipv4_fragment(&piece(id, offset, more, 600)).expect("a fragment")
    }

    #[test]
    fn ipv4_fragment_tells_the_first_piece_from_the_later_ones() {
        // a whole datagram, with or without DF, is not a fragment
        let mut whole = piece(7, 0, false, 1200);
        assert_eq!(ipv4_fragment(&whole), None);
        whole[6] = 0x40;
        assert_eq!(ipv4_fragment(&whole), None);
        let first = ipv4_fragment(&piece(7, 0, true, 1276)).expect("the first piece");
        let middle = ipv4_fragment(&piece(7, 157, true, 1276)).expect("a middle piece");
        let last = ipv4_fragment(&piece(7, 314, false, 172)).expect("the last piece");
        assert!(first.first && !middle.first && !last.first);
        assert_eq!(first.datagram, last.datagram);
        assert_eq!(first.datagram.id, 7);
        assert_eq!(first.datagram.proto, 17);
        // another datagram, an IPv6 packet and a runt are not this datagram's pieces
        assert_ne!(fragment(8, 157, false).datagram, first.datagram);
        let mut v6 = piece(7, 157, false, 172);
        v6[0] = 0x60;
        assert_eq!(ipv4_fragment(&v6), None);
        assert_eq!(ipv4_fragment(&piece(7, 157, false, 172)[..19]), None);
    }

    #[test]
    fn a_later_piece_follows_its_first_piece_while_that_worker_can_take_it() {
        // the flow is the caller's key: here a number per first piece
        let mut routes = FragmentRoutes::<u32>::default();
        let all = |_: usize| true;
        // the first piece is remembered with its flow and its tail follows it; a first piece takes the caller's
        // choice, a tail never records
        routes.record(fragment(7, 0, true), 3, 70);
        assert_eq!(routes.follow(fragment(7, 157, false), all), Some((3, 70)));
        assert_eq!(routes.follow(fragment(7, 0, true), all), None);
        routes.record(fragment(7, 157, false), 9, 99);
        assert_eq!(routes.follow(fragment(7, 314, false), all), Some((3, 70)));
        // a worker that can no longer take it (or is gone) leaves the tail to the caller's choice
        assert_eq!(routes.follow(fragment(7, 157, false), |idx| idx != 3), None);
        // another datagram (no entry, another source, or one that took the slot over) is not followed
        assert_eq!(routes.follow(fragment(8, 157, false), all), None);
        let mut other_src = piece(7, 157, false, 600);
        other_src[12] = 192;
        assert_eq!(routes.follow(ipv4_fragment(&other_src).unwrap(), all), None);
        let later = 7 + SLOTS as u16;
        routes.record(fragment(later, 0, true), 5, 50);
        assert_eq!(routes.follow(fragment(7, 157, false), all), None);
        assert_eq!(routes.follow(fragment(later, 157, false), all), Some((5, 50)));
        // datagrams in flight at once keep their own workers and flows
        for id in 100..100 + SLOTS as u16 {
            routes.record(fragment(id, 0, true), id as usize, u32::from(id) * 10);
        }
        for id in 100..100 + SLOTS as u16 {
            assert_eq!(routes.follow(fragment(id, 157, false), all), Some((id as usize, u32::from(id) * 10)));
        }
        routes.clear();
        assert_eq!(routes.follow(fragment(100, 157, false), all), None);
    }
}
