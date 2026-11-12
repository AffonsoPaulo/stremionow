// SPDX-License-Identifier: GPL-3.0-or-later
//! How many connections the server holds, and for whom.
//!
//! A connection starts out unauthenticated, in a small pool shared with pairing. When that
//! pool is full the oldest unauthenticated connection is closed to make room, so hosts that
//! open connections and send nothing cannot lock paired devices out: a device's connection
//! gets in, and leaves the pool with its first authenticated request, for a larger pool
//! reserved for paired devices. Every address is also limited on its own.

use std::collections::{HashMap, VecDeque};
use std::net::IpAddr;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, MutexGuard, PoisonError};

use tokio_util::sync::CancellationToken;

/// Connections one address may hold. A player opens one or two, plus status requests.
pub(crate) const MAX_PER_PEER: usize = 8;

/// Connections that have not yet carried an authenticated request: pairing, and paired
/// devices for the instant before their first request.
pub(crate) const MAX_PENDING: usize = 16;

/// Connections of paired devices.
pub(crate) const MAX_AUTHENTICATED: usize = 32;

/// The connection limits of one server.
#[derive(Default)]
pub(crate) struct Limits {
    state: Mutex<State>,
}

#[derive(Default)]
struct State {
    per_peer: HashMap<IpAddr, usize>,
    /// Unauthenticated connections, oldest first.
    pending: VecDeque<(u64, CancellationToken)>,
    authenticated: usize,
    next_id: u64,
}

impl Limits {
    /// A slot for a connection from `peer`, unless that address is at its limit. When the
    /// unauthenticated pool is full, its oldest connection is closed to make room.
    pub(crate) fn admit(self: &Arc<Self>, peer: IpAddr) -> Option<Slot> {
        let mut state = self.state();
        if state.per_peer.get(&peer).copied().unwrap_or(0) >= MAX_PER_PEER {
            return None;
        }
        if state.pending.len() >= MAX_PENDING
            && let Some((_, oldest)) = state.pending.pop_front()
        {
            oldest.cancel();
        }
        let id = state.next_id;
        state.next_id += 1;
        let closed = CancellationToken::new();
        state.pending.push_back((id, closed.clone()));
        *state.per_peer.entry(peer).or_default() += 1;
        Some(Slot {
            limits: Arc::clone(self),
            peer,
            id,
            closed,
            authenticated: AtomicBool::new(false),
        })
    }

    #[cfg(test)]
    pub(crate) fn counts(&self) -> (usize, usize, usize) {
        let state = self.state();
        (
            state.per_peer.len(),
            state.pending.len(),
            state.authenticated,
        )
    }

    fn state(&self) -> MutexGuard<'_, State> {
        // Every update leaves the counts consistent, so a panic elsewhere cannot corrupt them.
        self.state.lock().unwrap_or_else(PoisonError::into_inner)
    }
}

/// A connection's place in the limits; released when dropped.
pub(crate) struct Slot {
    limits: Arc<Limits>,
    peer: IpAddr,
    id: u64,
    /// Cancelled when the connection is evicted from the unauthenticated pool.
    closed: CancellationToken,
    authenticated: AtomicBool,
}

impl Slot {
    /// Cancelled when the server needs the slot for another connection.
    pub(crate) fn closed(&self) -> &CancellationToken {
        &self.closed
    }

    /// Moves the connection to the paired devices' pool after an authenticated request.
    /// Returns false when that pool is full.
    pub(crate) fn authenticate(&self) -> bool {
        if self.authenticated.load(Ordering::Relaxed) {
            return true;
        }
        let mut state = self.limits.state();
        if state.authenticated >= MAX_AUTHENTICATED {
            return false;
        }
        state.pending.retain(|(id, _)| *id != self.id);
        state.authenticated += 1;
        self.authenticated.store(true, Ordering::Relaxed);
        true
    }
}

impl Drop for Slot {
    fn drop(&mut self) {
        let mut state = self.limits.state();
        if let Some(count) = state.per_peer.get_mut(&self.peer) {
            *count -= 1;
            if *count == 0 {
                state.per_peer.remove(&self.peer);
            }
        }
        if self.authenticated.load(Ordering::Relaxed) {
            state.authenticated -= 1;
        } else {
            state.pending.retain(|(id, _)| *id != self.id);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn peer(last: u8) -> IpAddr {
        IpAddr::from([192, 168, 1, last])
    }

    #[test]
    fn each_address_is_limited_without_growing_the_table() {
        let limits = Arc::new(Limits::default());
        let slots: Vec<Slot> = (0..MAX_PER_PEER)
            .map(|_| limits.admit(peer(1)).expect("slot"))
            .collect();
        for _ in 0..100 {
            assert!(limits.admit(peer(1)).is_none());
        }
        assert_eq!(limits.counts().0, 1);
        drop(slots);
        assert_eq!(limits.counts(), (0, 0, 0), "nothing left behind");
    }

    #[test]
    fn idle_hosts_cannot_lock_paired_devices_out() {
        let limits = Arc::new(Limits::default());
        // Silent connections from many addresses fill the unauthenticated pool...
        let idle: Vec<Slot> = (0..MAX_PENDING as u8)
            .map(|i| limits.admit(peer(100 + i)).expect("slot"))
            .collect();
        assert!(idle.iter().all(|slot| !slot.closed().is_cancelled()));
        // ...and a paired device still gets in, closing the oldest of them.
        let device = limits.admit(peer(2)).expect("slot");
        assert!(idle[0].closed().is_cancelled());
        assert!(device.authenticate());
        assert_eq!(limits.counts().2, 1);
        // The device's connection no longer counts against the unauthenticated pool.
        let more: Vec<Slot> = (0..MAX_PENDING)
            .map(|_| limits.admit(peer(3)))
            .take(MAX_PER_PEER)
            .map(|slot| slot.expect("slot"))
            .collect();
        assert!(!device.closed().is_cancelled());
        drop((idle, more, device));
        assert_eq!(limits.counts(), (0, 0, 0));
    }

    #[test]
    fn the_paired_pool_is_bounded() {
        let limits = Arc::new(Limits::default());
        let mut held = Vec::new();
        for i in 0..MAX_AUTHENTICATED {
            let slot = limits.admit(peer(u8::try_from(i / MAX_PER_PEER).expect("small") + 10));
            let slot = slot.expect("slot");
            assert!(slot.authenticate());
            held.push(slot);
        }
        let extra = limits.admit(peer(50)).expect("slot");
        assert!(!extra.authenticate());
    }
}
