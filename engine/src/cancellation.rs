use std::cell::Cell;
use std::sync::atomic::{AtomicU64, Ordering};

const POLL_INTERVAL: usize = 128;

#[derive(Debug)]
pub(crate) struct QueryCancelled;

pub(crate) struct QueryCancellation<'a> {
    generation: Option<(&'a AtomicU64, u64)>,
    remaining: Cell<usize>,
}

impl<'a> QueryCancellation<'a> {
    pub(crate) fn none() -> Self {
        Self { generation: None, remaining: Cell::new(POLL_INTERVAL) }
    }

    pub(crate) fn from_atomic(counter: &'a AtomicU64, expected: u64) -> Self {
        if expected == 0 {
            Self::none()
        } else {
            Self { generation: Some((counter, expected)), remaining: Cell::new(POLL_INTERVAL) }
        }
    }

    #[inline]
    pub(crate) fn check(&self) -> Result<(), QueryCancelled> {
        if let Some((counter, expected)) = self.generation {
            if counter.load(Ordering::Relaxed) != expected {
                return Err(QueryCancelled);
            }
        }
        Ok(())
    }

    // Amortize even the relaxed atomic load across inner-loop iterations.
    #[inline]
    pub(crate) fn step(&self) -> Result<(), QueryCancelled> {
        if self.generation.is_some() {
            let remaining = self.remaining.get() - 1;
            if remaining == 0 {
                self.remaining.set(POLL_INTERVAL);
                return self.check();
            }
            self.remaining.set(remaining);
        }
        Ok(())
    }
}
