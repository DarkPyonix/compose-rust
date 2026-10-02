//! The decoders take raw bytes produced by another language, so every
//! malformed or hostile shape must surface as a `ProtocolError` and never as a panic,
//! an abort, or an out-of-bounds read.
//!
//! The cases here were found by the libFuzzer targets in `fuzz/fuzz_targets/`; each one
//! is kept as an ordinary regression test so CI defends it without running the fuzzer.

use dioxus_compose::protocol::{
    BatchEncoder, HostEvent, Mutation, PropertyValue, ProtocolError, decode_batch, decode_event,
    encode_event,
};
use dioxus_compose::schema::{EventPayload, Modifier, PropertyKind, Selection, WidgetKind};
use std::alloc::{GlobalAlloc, Layout, System};
use std::cell::Cell;

/// Tracks the largest single allocation request made while a measurement is open, so a
/// decoder that sizes a buffer from an attacker-controlled length is caught as a test
/// failure rather than as an out-of-memory abort.
struct PeakAllocator;

thread_local! {
    static TRACKING: Cell<bool> = const { Cell::new(false) };
    static PEAK_BYTES: Cell<usize> = const { Cell::new(0) };
}

fn record(size: usize) {
    // `try_with` because a thread tearing down its locals must not re-enter them.
    let _ = TRACKING.try_with(|tracking| {
        if tracking.get() {
            let _ = PEAK_BYTES.try_with(|peak| peak.set(peak.get().max(size)));
        }
    });
}

// SAFETY: Every operation delegates to the process System allocator unchanged.
unsafe impl GlobalAlloc for PeakAllocator {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        record(layout.size());
        // SAFETY: Delegating the caller-provided layout to System.
        unsafe { System.alloc(layout) }
    }

    unsafe fn dealloc(&self, ptr: *mut u8, layout: Layout) {
        // SAFETY: Delegating the original pointer and layout to System.
        unsafe { System.dealloc(ptr, layout) };
    }

    unsafe fn realloc(&self, ptr: *mut u8, layout: Layout, size: usize) -> *mut u8 {
        record(size);
        // SAFETY: Delegating the original allocation and requested size to System.
        unsafe { System.realloc(ptr, layout, size) }
    }
}

#[global_allocator]
static GLOBAL: PeakAllocator = PeakAllocator;

/// Runs `body` and returns the largest allocation it requested, in bytes.
fn peak_allocation_bytes(body: impl FnOnce()) -> usize {
    PEAK_BYTES.with(|peak| peak.set(0));
    TRACKING.with(|tracking| tracking.set(true));
    body();
    TRACKING.with(|tracking| tracking.set(false));
    PEAK_BYTES.with(Cell::get)
}

/// A 12-byte envelope: tag 0, len 12, then `records_len` and `record_count`.
fn envelope(records_len: u32, record_count: u32) -> Vec<u8> {
    let mut bytes = Vec::new();
    bytes.extend_from_slice(&0_u16.to_le_bytes());
    bytes.extend_from_slice(&12_u16.to_le_bytes());
    bytes.extend_from_slice(&records_len.to_le_bytes());
    bytes.extend_from_slice(&record_count.to_le_bytes());
    bytes
}

fn encode_all(mutations: &[Mutation<'_>]) -> Vec<u8> {
    let mut encoder = BatchEncoder::default();
    for mutation in mutations {
        encoder.encode(mutation).unwrap();
    }
    encoder.finish().unwrap().to_vec()
}

// --- Envelope and record-count disagreement -------------------------------------------

/// Found by `fuzz/fuzz_targets/decode_batch.rs` after 3,816 executions: the envelope's
/// `record_count` was used directly as `Vec::with_capacity`, so a 12-byte message could
/// ask the Host to reserve over 100 GB and die in `handle_alloc_error`: an abort, which
/// must never happen: a protocol error has to stay a reportable error.
#[test]
fn nfr7_huge_record_count_does_not_reserve_unbounded_memory() {
    let bytes = envelope(12, u32::MAX);
    let mut result = None;
    let peak = peak_allocation_bytes(|| result = Some(decode_batch(&bytes).is_err()));
    assert_eq!(result, Some(true), "the batch must be rejected");
    assert!(
        peak <= 4096,
        "decoding a {}-byte batch reserved {peak} bytes; capacity must be bounded by the \
         input, not by the declared record count",
        bytes.len(),
    );
}

#[test]
fn nfr7_record_count_above_declared_records_is_a_protocol_error() {
    let mut bytes = envelope(20, 9);
    bytes.extend_from_slice(&6_u16.to_le_bytes()); // TAG_REMOVE
    bytes.extend_from_slice(&8_u16.to_le_bytes());
    bytes.extend_from_slice(&1_u32.to_le_bytes());
    assert_eq!(decode_batch(&bytes), Err(ProtocolError::InvalidEnvelope));
}

#[test]
fn nfr7_record_count_below_declared_records_is_a_protocol_error() {
    let mut bytes = envelope(20, 0);
    bytes.extend_from_slice(&6_u16.to_le_bytes());
    bytes.extend_from_slice(&8_u16.to_le_bytes());
    bytes.extend_from_slice(&1_u32.to_le_bytes());
    assert_eq!(decode_batch(&bytes), Err(ProtocolError::InvalidEnvelope));
}

#[test]
fn nfr7_records_len_past_the_buffer_is_a_protocol_error() {
    let bytes = envelope(u32::MAX, 1);
    assert_eq!(decode_batch(&bytes), Err(ProtocolError::InvalidEnvelope));
}

#[test]
fn nfr7_empty_and_tiny_inputs_are_protocol_errors() {
    for len in 0..12 {
        let bytes = vec![0_u8; len];
        assert!(
            decode_batch(&bytes).is_err(),
            "a {len}-byte batch must not decode"
        );
    }
}

// --- Truncation at every byte offset --------------------------------------------------

/// Cutting a well-formed batch at any offset is a clean `ProtocolError`.
#[test]
fn nfr7_truncated_batch_reports_protocol_error() {
    let bytes = encode_all(&[
        Mutation::Create {
            node_id: 1,
            widget: WidgetKind::Column,
        },
        Mutation::SetProp {
            node_id: 2,
            property: PropertyKind::Text,
            value: PropertyValue::String("가나다라"),
        },
        Mutation::SetModifier {
            node_id: 1,
            index: 0,
            modifier: Modifier::Size {
                width: 10.0,
                height: 20.0,
            },
        },
        Mutation::SetText {
            node_id: 2,
            text: "hello",
            selection: Some(Selection { start: 0, end: 5 }),
        },
    ]);
    for end in 0..bytes.len() {
        assert!(
            decode_batch(&bytes[..end]).is_err(),
            "a batch truncated to {end} of {} bytes must not decode",
            bytes.len(),
        );
    }
    assert!(decode_batch(&bytes).is_ok(), "the whole batch must decode");
}

/// The same guarantee for the Renderer-to-Host event decoder.
#[test]
fn nfr7_truncated_event_reports_protocol_error() {
    let events = [
        HostEvent {
            node_id: 7,
            handler_id: 11,
            payload: EventPayload::Clicked,
        },
        HostEvent {
            node_id: 8,
            handler_id: 12,
            payload: EventPayload::TextChanged("한글 입력"),
        },
        HostEvent {
            node_id: 9,
            handler_id: 13,
            payload: EventPayload::ProtocolError {
                code: 3,
                message: "bad",
            },
        },
    ];
    for event in &events {
        let mut bytes = Vec::new();
        encode_event(event, &mut bytes).unwrap();
        for end in 0..bytes.len() {
            assert!(
                decode_event(&bytes[..end]).is_err(),
                "an event truncated to {end} of {} bytes must not decode",
                bytes.len(),
            );
        }
        assert_eq!(decode_event(&bytes).unwrap(), *event);
    }
}

// --- String references outside the arena ----------------------------------------------

/// Strings live in the arena *after* the records. A string reference that points
/// back into the record region lets a hostile Renderer make a `SetText` whose text is
/// really the batch header, garbage decoding into a valid-looking mutation.
#[test]
fn nfr7_string_offset_inside_the_record_region_is_a_protocol_error() {
    let mut bytes = encode_all(&[Mutation::AppendText {
        node_id: 1,
        text: "abcd",
    }]);
    // The AppendText record's (offset, len) pair sits at byte 20 of the batch.
    let records_len = u32::from_le_bytes(bytes[4..8].try_into().unwrap());
    assert!(records_len > 4);
    bytes[20..24].copy_from_slice(&0_u32.to_le_bytes());
    assert_eq!(
        decode_batch(&bytes),
        Err(ProtocolError::InvalidStringRange),
        "a string may not alias the record region"
    );
}

#[test]
fn nfr7_string_offset_past_the_buffer_is_a_protocol_error() {
    let mut bytes = encode_all(&[Mutation::AppendText {
        node_id: 1,
        text: "abcd",
    }]);
    bytes[20..24].copy_from_slice(&u32::MAX.to_le_bytes());
    assert_eq!(decode_batch(&bytes), Err(ProtocolError::InvalidStringRange));
}

#[test]
fn nfr7_string_length_overflowing_the_buffer_is_a_protocol_error() {
    let mut bytes = encode_all(&[Mutation::AppendText {
        node_id: 1,
        text: "abcd",
    }]);
    bytes[24..28].copy_from_slice(&u32::MAX.to_le_bytes());
    assert_eq!(decode_batch(&bytes), Err(ProtocolError::InvalidStringRange));
}

#[test]
fn nfr7_non_utf8_string_payload_is_a_protocol_error() {
    let mut bytes = encode_all(&[Mutation::AppendText {
        node_id: 1,
        text: "abcd",
    }]);
    let last = bytes.len() - 1;
    bytes[last] = 0xFF;
    assert_eq!(decode_batch(&bytes), Err(ProtocolError::InvalidUtf8));
}

/// The event decoder carries its string after the fixed record, so the same rule applies.
#[test]
fn nfr7_event_string_offset_inside_the_record_is_a_protocol_error() {
    let mut bytes = Vec::new();
    encode_event(
        &HostEvent {
            node_id: 1,
            handler_id: 2,
            payload: EventPayload::TextChanged("abcd"),
        },
        &mut bytes,
    )
    .unwrap();
    // The (offset, len) pair sits at byte 16; point it at the record header instead.
    bytes[16..20].copy_from_slice(&0_u32.to_le_bytes());
    assert_eq!(
        decode_event(&bytes),
        Err(ProtocolError::InvalidStringRange),
        "an event string may not alias the record region"
    );
}

#[test]
fn nfr7_event_string_offset_past_the_buffer_is_a_protocol_error() {
    let mut bytes = Vec::new();
    encode_event(
        &HostEvent {
            node_id: 1,
            handler_id: 2,
            payload: EventPayload::TextChanged("abcd"),
        },
        &mut bytes,
    )
    .unwrap();
    bytes[16..20].copy_from_slice(&u32::MAX.to_le_bytes());
    assert_eq!(decode_event(&bytes), Err(ProtocolError::InvalidStringRange));
}

// --- Malformed records ----------------------------------------------------------------

#[test]
fn nfr7_record_shorter_than_its_fixed_fields_is_a_protocol_error() {
    // Every mutation tag, declared with a record length of 4 (header only).
    for tag in 1_u16..=8 {
        let mut bytes = envelope(16, 1);
        bytes.extend_from_slice(&tag.to_le_bytes());
        bytes.extend_from_slice(&4_u16.to_le_bytes());
        assert_eq!(
            decode_batch(&bytes),
            Err(ProtocolError::InvalidRecordLength),
            "tag {tag} with a 4-byte record must be rejected"
        );
    }
}

#[test]
fn nfr7_zero_length_record_does_not_loop_forever() {
    let mut bytes = envelope(16, 1);
    bytes.extend_from_slice(&6_u16.to_le_bytes());
    bytes.extend_from_slice(&0_u16.to_le_bytes());
    assert_eq!(
        decode_batch(&bytes),
        Err(ProtocolError::InvalidRecordLength)
    );
}

#[test]
fn nfr7_unaligned_record_length_is_a_protocol_error() {
    let mut bytes = envelope(20, 1);
    bytes.extend_from_slice(&6_u16.to_le_bytes());
    bytes.extend_from_slice(&7_u16.to_le_bytes());
    bytes.extend_from_slice(&1_u32.to_le_bytes());
    assert_eq!(
        decode_batch(&bytes),
        Err(ProtocolError::InvalidRecordLength)
    );
}

#[test]
fn nfr7_record_length_past_the_record_region_is_a_protocol_error() {
    let mut bytes = envelope(20, 1);
    bytes.extend_from_slice(&6_u16.to_le_bytes());
    bytes.extend_from_slice(&u16::MAX.to_le_bytes());
    bytes.extend_from_slice(&1_u32.to_le_bytes());
    assert_eq!(
        decode_batch(&bytes),
        Err(ProtocolError::InvalidRecordLength)
    );
}

#[test]
fn nfr7_nested_envelope_record_is_a_protocol_error() {
    // A second envelope record inside the stream is not a mutation.
    let mut bytes = envelope(24, 1);
    bytes.extend_from_slice(&0_u16.to_le_bytes());
    bytes.extend_from_slice(&12_u16.to_le_bytes());
    bytes.extend_from_slice(&24_u32.to_le_bytes());
    bytes.extend_from_slice(&1_u32.to_le_bytes());
    assert_eq!(decode_batch(&bytes), Err(ProtocolError::InvalidTag(0)));
}

#[test]
fn nfr7_unknown_enum_tags_are_protocol_errors() {
    // Create with a widget tag outside the schema.
    let mut bytes = envelope(24, 1);
    bytes.extend_from_slice(&1_u16.to_le_bytes());
    bytes.extend_from_slice(&12_u16.to_le_bytes());
    bytes.extend_from_slice(&1_u32.to_le_bytes());
    bytes.extend_from_slice(&999_u16.to_le_bytes());
    bytes.extend_from_slice(&0_u16.to_le_bytes());
    assert_eq!(decode_batch(&bytes), Err(ProtocolError::InvalidWidget(999)));

    // SetModifier with a modifier tag outside the schema.
    let mut bytes = envelope(40, 1);
    bytes.extend_from_slice(&3_u16.to_le_bytes());
    bytes.extend_from_slice(&28_u16.to_le_bytes());
    bytes.extend_from_slice(&1_u32.to_le_bytes());
    bytes.extend_from_slice(&0_u16.to_le_bytes());
    bytes.extend_from_slice(&999_u16.to_le_bytes());
    bytes.extend_from_slice(&0_u64.to_le_bytes());
    bytes.extend_from_slice(&0_u64.to_le_bytes());
    assert_eq!(
        decode_batch(&bytes),
        Err(ProtocolError::InvalidModifier(999))
    );
}

#[test]
fn nfr7_unknown_event_tag_is_a_protocol_error() {
    let mut bytes = Vec::new();
    bytes.extend_from_slice(&999_u16.to_le_bytes());
    bytes.extend_from_slice(&16_u16.to_le_bytes());
    bytes.extend_from_slice(&1_u32.to_le_bytes());
    bytes.extend_from_slice(&2_u64.to_le_bytes());
    assert_eq!(decode_event(&bytes), Err(ProtocolError::InvalidTag(999)));
}

#[test]
fn nfr7_event_record_length_disagreeing_with_its_tag_is_a_protocol_error() {
    // Clicked declared as a 24-byte record.
    let mut bytes = Vec::new();
    bytes.extend_from_slice(&1_u16.to_le_bytes());
    bytes.extend_from_slice(&24_u16.to_le_bytes());
    bytes.extend_from_slice(&1_u32.to_le_bytes());
    bytes.extend_from_slice(&2_u64.to_le_bytes());
    bytes.extend_from_slice(&[0_u8; 8]);
    assert_eq!(
        decode_event(&bytes),
        Err(ProtocolError::InvalidRecordLength)
    );
}

// --- Hostile but structurally valid shapes --------------------------------------------

/// These are semantically nonsense but structurally legal. The decoder's contract is to
/// hand them back without panicking; rejecting them is the tree-applier's job.
#[test]
fn nfr7_hostile_tree_shapes_decode_without_panicking() {
    let hostile = [
        // Insert a node into itself.
        Mutation::Insert {
            parent_id: 5,
            node_id: 5,
            index: 0,
        },
        // Move a node under itself at a wild index.
        Mutation::Move {
            parent_id: 5,
            node_id: 5,
            index: u32::MAX,
        },
        // Remove a node that was never created.
        Mutation::Remove { node_id: 999_999 },
        // Ids at the u32 boundary.
        Mutation::Create {
            node_id: u32::MAX,
            widget: WidgetKind::Text,
        },
        Mutation::Insert {
            parent_id: u32::MAX,
            node_id: u32::MAX - 1,
            index: u32::MAX,
        },
        // A selection far outside any real text.
        Mutation::SetText {
            node_id: u32::MAX,
            text: "",
            selection: Some(Selection {
                start: u32::MAX - 1,
                end: 0,
            }),
        },
        // An empty append, and a modifier index at its own boundary.
        Mutation::AppendText {
            node_id: 0,
            text: "",
        },
        Mutation::SetModifier {
            node_id: 0,
            index: u16::MAX,
            modifier: Modifier::Clickable {
                handler_id: u64::MAX,
            },
        },
    ];
    let bytes = encode_all(&hostile);
    assert_eq!(decode_batch(&bytes).unwrap(), hostile);
}

/// The encoder's output always decodes back identically, including for strings
/// whose bytes could be mistaken for record headers.
#[test]
fn pr4_encoder_output_round_trips_for_adversarial_strings() {
    let payloads = [
        "\u{0}\u{0}\u{c}\u{0}",
        "\u{1}\u{0}\u{8}\u{0}",
        "🧵🧶",
        "\u{feff}",
        &"a".repeat(1024),
    ];
    for payload in payloads {
        let mutations = [
            Mutation::SetText {
                node_id: 1,
                text: payload,
                selection: None,
            },
            Mutation::SetProp {
                node_id: 1,
                property: PropertyKind::Text,
                value: PropertyValue::String(payload),
            },
            Mutation::AppendText {
                node_id: 1,
                text: payload,
            },
        ];
        let bytes = encode_all(&mutations);
        assert_eq!(decode_batch(&bytes).unwrap(), mutations);
    }
}

/// Exhaustive sweep over short inputs. Nothing in this space may panic, and
/// nothing outside the handful of genuinely well-formed encodings may decode.
#[test]
fn nfr7_garbage_never_decodes_into_a_valid_looking_mutation() {
    let mut bytes = envelope(20, 1);
    bytes.extend_from_slice(&[0_u8; 8]);
    let mut accepted = 0_usize;
    // Sweep the record tag and length, plus the first payload word.
    for tag in 0_u16..=12 {
        for len in 0_u16..=32 {
            for payload in [0_u32, 1, u32::MAX] {
                bytes[12..14].copy_from_slice(&tag.to_le_bytes());
                bytes[14..16].copy_from_slice(&len.to_le_bytes());
                bytes[16..20].copy_from_slice(&payload.to_le_bytes());
                if let Ok(decoded) = decode_batch(&bytes) {
                    // `Remove` and `ReleaseAsset` are the only 8-byte records, so they
                    // are the only shapes that can legitimately fit this 20-byte envelope.
                    assert_eq!(decoded.len(), 1);
                    assert!(
                        matches!(
                            decoded[0],
                            Mutation::Remove { .. } | Mutation::ReleaseAsset { .. }
                        ),
                        "tag {tag} len {len} decoded as {:?}",
                        decoded[0]
                    );
                    accepted += 1;
                }
            }
        }
    }
    assert_eq!(
        accepted, 6,
        "only the two 8-byte records, Remove and ReleaseAsset, may be accepted here"
    );
}
