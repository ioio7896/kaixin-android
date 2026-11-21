use std::cell::Cell;

const POLL_INTERVAL: usize = 512;

#[derive(Debug)]
pub(crate) struct QueryCancelled;

pub(crate) struct QueryCancellation<'a> {
    poll: Option<&'a dyn Fn() -> bool>,
    remaining: Cell<usize>,
}

impl<'a> QueryCancellation<'a> {
    pub(crate) fn new(poll: Option<&'a dyn Fn() -> bool>) -> Self {
        Self { poll, remaining: Cell::new(POLL_INTERVAL) }
    }

    pub(crate) fn check(&self) -> Result<(), QueryCancelled> {
        if self.poll.is_some_and(|poll| poll()) {
            return Err(QueryCancelled);
        }
        Ok(())
    }

    // Amortize the cross-language poll across a batch of inner-loop operations.
    pub(crate) fn step(&self) -> Result<(), QueryCancelled> {
        if self.poll.is_some() {
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
