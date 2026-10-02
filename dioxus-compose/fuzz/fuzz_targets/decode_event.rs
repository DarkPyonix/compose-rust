//! `decode_event` must return a `ProtocolError` for any byte string the
//! Renderer could hand it, never panic, abort, or read out of bounds.
#![no_main]

use dioxus_compose::protocol::{decode_event, encode_event};
use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| {
    let Ok(event) = decode_event(data) else {
        return;
    };
    // A decoded event must re-encode to bytes that decode and re-encode to
    // the same bytes again.
    let mut once = Vec::new();
    encode_event(&event, &mut once).expect("re-encoding a decoded event");
    let again = decode_event(&once).expect("re-decoding a re-encoded event");
    let mut twice = Vec::new();
    encode_event(&again, &mut twice).expect("re-encoding a re-decoded event");
    assert_eq!(once, twice, "re-encode round trip diverged");
});
