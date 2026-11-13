//! The larger event buffer the Host lends a browser Renderer.
//!
//! The block `compose_rust_host_web_start` reports carries a 4 KiB event buffer, which is
//! room for every event that is not carrying a document. A paste, a large edit or a long
//! answer from the browser's storage does not fit, so the Renderer asks the Host for a
//! larger buffer, in the Host's region, and keeps it. The Renderer cannot make one itself:
//! its allocator only hands out addresses inside the scope that asked for them.
//!
//! The policy lives here, apart from the generated wasm export that calls it, so that it
//! is the same code on every target and can be tested on the machine that builds it.

use crate::schema::{WEB_EVENT_BUFFER_MAX_BYTES, WEB_RUST_REGION_BASE};

/// The buffer lent last, kept until the Renderer asks again.
///
/// It only grows. A request it already satisfies answers with the same address and
/// allocates nothing, so once an application has seen its largest event, sending another
/// like it costs nothing. A refused request leaves the buffer lent before it where it was,
/// because the Renderer goes on encoding into that one.
#[derive(Debug, Default)]
pub struct LentEventBuffer {
    bytes: Vec<u8>,
}

impl LentEventBuffer {
    /// Nothing lent yet.
    pub const fn new() -> Self {
        Self { bytes: Vec::new() }
    }

    /// How many bytes the buffer lent last holds. Zero before the first request.
    pub fn len(&self) -> usize {
        self.bytes.len()
    }

    /// Whether anything has been lent yet.
    pub fn is_empty(&self) -> bool {
        self.bytes.is_empty()
    }

    /// The start of a buffer of at least `min_len` bytes, or `None` when the request is
    /// refused.
    ///
    /// Refused when `min_len` is zero, which asks for nothing, when it is past
    /// [`WEB_EVENT_BUFFER_MAX_BYTES`], and when the memory cannot be had. The larger buffer
    /// is allocated before the smaller one is let go of, so a failed allocation never
    /// leaves the Renderer holding the address of memory that was freed.
    pub fn lend(&mut self, min_len: u32) -> Option<*mut u8> {
        if min_len == 0 || min_len > WEB_EVENT_BUFFER_MAX_BYTES {
            return None;
        }
        let wanted = min_len as usize;
        if self.bytes.len() < wanted {
            let mut grown = Vec::new();
            grown.try_reserve_exact(wanted).ok()?;
            grown.resize(wanted, 0);
            self.bytes = grown;
        }
        Some(self.bytes.as_mut_ptr())
    }
}

/// The address a browser Renderer is told for `pointer`, or zero when it may not be told.
///
/// Every address that crosses to the Renderer is in the Host's region, at or above
/// [`WEB_RUST_REGION_BASE`] and inside the 32-bit memory. One below the region would be
/// memory the Renderer's own allocator hands out, and the two would write over each other.
pub fn region_address(pointer: *const u8) -> u32 {
    let address = pointer as usize;
    match u32::try_from(address) {
        Ok(address) if address >= WEB_RUST_REGION_BASE => address,
        _ => 0,
    }
}
