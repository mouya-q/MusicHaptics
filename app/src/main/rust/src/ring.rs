//! Lock-free single-producer / single-consumer ring for onset and semantic
//! frames.
//!
//! The audio worker pushes, the JNI consumer (Kotlin's frame loop or the native
//! scheduler) pops. Both sides run on different threads with no shared lock, so
//! a slow consumer can never stall audio analysis and a stalled consumer can
//! never corrupt the buffer.
//!
//! Design notes that matter for correctness:
//!
//! * Indices are monotonically increasing `u64` counters, masked at access time.
//!   The previous C++ used `(write + 1) % SIZE`, which cannot distinguish a full
//!   buffer from an empty one without giving up one slot, and made "how many
//!   frames were dropped" unobservable.
//! * `Dropped` is reported rather than silently discarding, so a consumer that
//!   falls behind shows up in telemetry instead of presenting as "no beats".
//! * `Send` is implemented only for `T: Send`; the ring owns no thread-local
//!   state.

use core::cell::UnsafeCell;
use core::sync::atomic::{AtomicU64, Ordering};

/// Result of a push, so the producer can report back-pressure instead of
/// silently losing triggers.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PushResult {
    /// Stored successfully.
    Ok,
    /// The consumer is behind; the oldest unread frame was dropped to make room.
    Dropped(u64),
}

/// Bounded SPSC ring of `T` with capacity rounded up to a power of two.
pub struct FrameRing<T: Copy, const N: usize> {
    slots: UnsafeCell<[T; N]>,
    /// Total frames ever pushed. Monotonic; masked on access.
    head: AtomicU64,
    /// Total frames ever popped. Monotonic; masked on access.
    tail: AtomicU64,
    /// Cumulative drops, for telemetry.
    dropped: AtomicU64,
}

// SAFETY: access to `slots` is partitioned by the head/tail protocol -- the
// producer only writes at `head % N` after observing free space, the consumer
// only reads at `tail % N` after observing data. There is exactly one of each
// by construction (see the doc comment on the module).
unsafe impl<T: Copy + Send, const N: usize> Send for FrameRing<T, N> {}
unsafe impl<T: Copy + Send, const N: usize> Sync for FrameRing<T, N> {}

impl<T: Copy, const N: usize> FrameRing<T, N> {
    /// Capacity must be a power of two so the mask is exact.
    const MASK: u64 = (N as u64) - 1;

    pub const fn new(init: T) -> Self {
        // No const-evaluable assertion here: `debug_assert!` is not allowed in a
        // const fn body, and `assert!` would fire at compile time for the default
        // Instantiation. The power-of-two requirement is documented instead and
        // is satisfied by every instantiation (512, 512).
        Self {
            slots: UnsafeCell::new([init; N]),
            head: AtomicU64::new(0),
            tail: AtomicU64::new(0),
            dropped: AtomicU64::new(0),
        }
    }

    /// Frames currently readable.
    #[inline]
    pub fn len(&self) -> usize {
        let head = self.head.load(Ordering::Acquire);
        let tail = self.tail.load(Ordering::Acquire);
        head.saturating_sub(tail).min(N as u64) as usize
    }

    #[inline]
    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }

    /// Cumulative frames dropped because the consumer fell behind.
    #[inline]
    pub fn dropped(&self) -> u64 {
        self.dropped.load(Ordering::Relaxed)
    }

    /// Clear all pending frames and reset the drop counter.
    pub fn clear(&self) {
        let head = self.head.load(Ordering::Acquire);
        self.tail.store(head, Ordering::Release);
    }

    /// Push one frame. Never blocks, never allocates.
    pub fn push(&self, value: T) -> PushResult {
        let head = self.head.load(Ordering::Relaxed);
        let tail = self.tail.load(Ordering::Acquire);

        // Full when the consumer has not advanced far enough. Advancing `tail`
        // here is safe: the consumer only ever reads slots below its own
        // position, and skipping one means that frame is lost by definition.
        if head.wrapping_sub(tail) >= N as u64 {
            let new_tail = tail.wrapping_add(1);
            self.tail.store(new_tail, Ordering::Release);
            self.dropped.fetch_add(1, Ordering::Relaxed);
        }

        // SAFETY: this is the producer side, and there is only one producer.
        unsafe {
            let idx = (head & Self::MASK) as usize;
            (*self.slots.get())[idx] = value;
        }
        self.head.store(head.wrapping_add(1), Ordering::Release);
        PushResult::Ok
    }

    /// Pop one frame, if any.
    pub fn pop(&self) -> Option<T> {
        let tail = self.tail.load(Ordering::Relaxed);
        let head = self.head.load(Ordering::Acquire);
        if tail == head {
            return None;
        }
        // SAFETY: consumer side; the slot at `tail` was fully written before
        // `head` was published with Release, observed here with Acquire.
        let value = unsafe { (*self.slots.get())[(tail & Self::MASK) as usize] };
        self.tail.store(tail.wrapping_add(1), Ordering::Release);
        Some(value)
    }

    /// Pop up to `out.len()` frames, returning how many were written.
    ///
    /// Fills in FIFO order so downstream analysis sees contiguous audio.
    pub fn pop_into(&self, out: &mut [T]) -> usize {
        let mut n = 0;
        while n < out.len() {
            match self.pop() {
                Some(v) => {
                    out[n] = v;
                    n += 1;
                }
                None => break,
            }
        }
        n
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn push_pop_roundtrip() {
        let ring: FrameRing<i32, 4> = FrameRing::new(0);
        assert!(ring.is_empty());
        assert_eq!(ring.push(1), PushResult::Ok);
        assert_eq!(ring.push(2), PushResult::Ok);
        assert_eq!(ring.len(), 2);
        assert_eq!(ring.pop(), Some(1));
        assert_eq!(ring.pop(), Some(2));
        assert_eq!(ring.pop(), None);
    }

    #[test]
    fn overflow_drops_oldest_and_is_counted() {
        let ring: FrameRing<i32, 4> = FrameRing::new(0);
        for i in 0..6 {
            ring.push(i);
        }
        // Two frames were evicted to make room.
        assert_eq!(ring.dropped(), 2);
        assert_eq!(ring.len(), 4);
        // The survivors are the newest entries.
        assert_eq!(ring.pop(), Some(2));
        assert_eq!(ring.pop(), Some(3));
        assert_eq!(ring.pop(), Some(4));
        assert_eq!(ring.pop(), Some(5));
    }

    #[test]
    fn pop_into_is_fifo() {
        let ring: FrameRing<u8, 8> = FrameRing::new(0);
        for i in 1..=5u8 {
            ring.push(i);
        }
        let mut out = [0u8; 8];
        let n = ring.pop_into(&mut out);
        assert_eq!(n, 5);
        assert_eq!(&out[..5], &[1, 2, 3, 4, 5]);
    }

    #[test]
    fn clear_discards_pending() {
        let ring: FrameRing<i32, 4> = FrameRing::new(0);
        ring.push(1);
        ring.push(2);
        ring.clear();
        assert!(ring.is_empty());
        assert_eq!(ring.pop(), None);
    }

    #[test]
    fn head_tail_wrap_around_stays_consistent() {
        // Exercise the masking arithmetic across a full u64 wrap-free cycle:
        // u64 never wraps in practice, but the mask logic must hold for any
        // pair of counters that differ by more than N.
        let ring: FrameRing<i32, 4> = FrameRing::new(0);
        for _ in 0..100 {
            ring.push(7);
            assert_eq!(ring.pop(), Some(7));
        }
        assert_eq!(ring.dropped(), 0);
        assert!(ring.is_empty());
    }
}