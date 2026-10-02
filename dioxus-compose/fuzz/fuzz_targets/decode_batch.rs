//! `decode_batch` must return a `ProtocolError` for any byte string the
//! Renderer could hand it, never panic, abort, or read out of bounds.
#![no_main]

use dioxus_compose::protocol::{BatchEncoder, Mutation, decode_batch};
use libfuzzer_sys::fuzz_target;

fn reencode(mutations: &[Mutation<'_>]) -> Vec<u8> {
    let mut encoder = BatchEncoder::default();
    for mutation in mutations {
        encoder.encode(mutation).expect("re-encoding a decoded batch");
    }
    encoder
        .finish()
        .expect("finishing a re-encoded batch")
        .to_vec()
}

fuzz_target!(|data: &[u8]| {
    let Ok(mutations) = decode_batch(data) else {
        return;
    };
    // Whatever the decoder accepts, the encoder must be able to reproduce,
    // and decoding that must yield the same batch again. Compared as bytes rather
    // than as values so that a NaN float payload is not a spurious mismatch.
    let once = reencode(&mutations);
    let again = decode_batch(&once).expect("re-decoding a re-encoded batch");
    let twice = reencode(&again);
    assert_eq!(once, twice, "re-encode round trip diverged");
});
