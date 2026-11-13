//! Font features: `font_feature_settings` on `Text` and `TextField` sends one blob
//! property of 8-byte records, a node that does not ask sends nothing, and a frame that
//! only changes the text of a node with features costs no more than one without them.

use compose_rust::ComposeHost;
use compose_rust::codegen::{FONT_FEATURE_VECTOR, generate_mutation_vector};
use compose_rust::font_features::{FONT_FEATURE_LEN, MAX_FONT_FEATURES, font_feature_bytes};
use compose_rust::protocol::{BatchEncoder, Mutation, PropertyValue, decode_batch};
use compose_rust::runtime::*;
use compose_rust::schema::{PropertyKind, WidgetKind};
use compose_rust::ui::*;
use std::alloc::{GlobalAlloc, Layout, System};
use std::cell::Cell;

struct CountingAllocator;

// Counting is per thread, not per process: the test harness runs tests concurrently, so a
// global counter would attribute other tests' allocations to whichever measurement is open.
thread_local! {
    static TRACKING: Cell<bool> = const { Cell::new(false) };
    static ALLOCATIONS: Cell<usize> = const { Cell::new(0) };
}

fn record_allocation() {
    let _ = TRACKING.try_with(|tracking| {
        if tracking.get() {
            let _ = ALLOCATIONS.try_with(|count| count.set(count.get() + 1));
        }
    });
}

// SAFETY: Every operation delegates to the process System allocator unchanged.
unsafe impl GlobalAlloc for CountingAllocator {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        record_allocation();
        // SAFETY: Delegating the caller-provided layout to System.
        unsafe { System.alloc(layout) }
    }

    unsafe fn dealloc(&self, ptr: *mut u8, layout: Layout) {
        // SAFETY: Delegating the original pointer and layout to System.
        unsafe { System.dealloc(ptr, layout) };
    }

    unsafe fn realloc(&self, ptr: *mut u8, layout: Layout, size: usize) -> *mut u8 {
        record_allocation();
        // SAFETY: Delegating the original allocation and requested size to System.
        unsafe { System.realloc(ptr, layout, size) }
    }
}

#[global_allocator]
static GLOBAL: CountingAllocator = CountingAllocator;

fn count_allocations(work: impl FnOnce()) -> usize {
    ALLOCATIONS.with(|count| count.set(0));
    TRACKING.with(|tracking| tracking.set(true));
    work();
    TRACKING.with(|tracking| tracking.set(false));
    ALLOCATIONS.with(|count| count.get())
}

/// For each node `batch` creates as `widget`, the `FontFeatureSettings` values written to it.
fn feature_values(batch: &[u8], widget: WidgetKind) -> Vec<Vec<Option<Vec<u8>>>> {
    let mutations = decode_batch(batch).expect("the batch round-trips through the decoder");
    let nodes: Vec<u32> = mutations
        .iter()
        .filter_map(|mutation| match mutation {
            Mutation::Create {
                node_id,
                widget: kind,
            } if *kind == widget => Some(*node_id),
            _ => None,
        })
        .collect();
    nodes
        .iter()
        .map(|node| writes_to(&mutations, *node))
        .collect()
}

fn writes_to(mutations: &[Mutation<'_>], node: u32) -> Vec<Option<Vec<u8>>> {
    mutations
        .iter()
        .filter_map(|mutation| match mutation {
            Mutation::SetProp {
                node_id,
                property: PropertyKind::FontFeatureSettings,
                value,
            } if *node_id == node => match value {
                PropertyValue::Bytes(bytes) => Some(Some(bytes.to_vec())),
                PropertyValue::None => Some(None),
                other => panic!("FontFeatureSettings is a blob, not {other:?}"),
            },
            _ => None,
        })
        .collect()
}

const TNUM: &[u8] = b"tnum\x01\0\0\0";

#[Composable]
fn Texts() {
    Column().content(|| {
        Text("12:04").font_feature_settings(&[FontFeature::TABULAR_NUMS]);
        Text("plain");
        Text("empty").font_feature_settings(&[]);
        Text("body")
            .type_role(TypeRole::Body)
            .font_feature_settings(&[FontFeature::TABULAR_NUMS]);
    });
}

#[Composable]
fn Fields() {
    Column().content(|| {
        TextField()
            .placeholder("0.00")
            .font_feature_settings(&[FontFeature::TABULAR_NUMS]);
        TextField().placeholder("plain");
    });
}

#[test]
fn fr83_font_feature_settings_writes_one_property_only_when_set() {
    assert_eq!(PropertyKind::FontFeatureSettings as u16, 192);
    assert_eq!(
        PropertyKind::from_name("font_feature_settings"),
        Ok(PropertyKind::FontFeatureSettings)
    );

    let mut host = ComposeHost::with_content(Texts);
    let batch = host.rebuild().expect("the first batch encodes").to_vec();
    let texts = feature_values(&batch, WidgetKind::Text);
    assert_eq!(texts.len(), 4, "four texts are created");
    assert_eq!(texts[0], vec![Some(TNUM.to_vec())], "one property, once");
    assert!(
        texts[1].is_empty(),
        "a text that does not ask sends nothing"
    );
    assert!(texts[2].is_empty(), "an empty list is no property at all");
    assert_eq!(
        texts[3],
        vec![Some(TNUM.to_vec())],
        "text with a role takes features too"
    );

    let mut host = ComposeHost::with_content(Fields);
    let batch = host.rebuild().expect("the first batch encodes").to_vec();
    let fields = feature_values(&batch, WidgetKind::TextField);
    assert_eq!(fields.len(), 2, "two fields are created");
    assert_eq!(fields[0], vec![Some(TNUM.to_vec())]);
    assert!(
        fields[1].is_empty(),
        "a field that does not ask sends nothing"
    );
}

#[Composable]
fn Toggled(on: MutableState<bool>, count: MutableState<i32>) {
    let features: &[FontFeature] = if on.get() {
        &[FontFeature::TABULAR_NUMS]
    } else {
        &[]
    };
    Column().content(|| {
        Text(format!("{}", count.get())).font_feature_settings(features);
    });
}

/// A list that goes away clears the property, and an unchanged list is not sent again.
#[test]
fn fr83_font_feature_settings_clears_and_is_not_resent() {
    let on = mutable_state_of(true);
    let count = mutable_state_of(0);
    let (on_state, count_state) = (on, count);
    let mut host = ComposeHost::with_content(move || Toggled(on_state, count_state));
    let first = host.rebuild().unwrap().to_vec();
    assert_eq!(
        feature_values(&first, WidgetKind::Text),
        vec![vec![Some(TNUM.to_vec())]]
    );
    let node = decode_batch(&first)
        .unwrap()
        .iter()
        .find_map(|mutation| match mutation {
            Mutation::Create {
                node_id,
                widget: WidgetKind::Text,
            } => Some(*node_id),
            _ => None,
        })
        .unwrap();

    count.set(1);
    let changed = host.render_frame(1).unwrap().to_vec();
    assert!(
        writes_to(&decode_batch(&changed).unwrap(), node).is_empty(),
        "the same list is not written again when only the text changes"
    );

    on.set(false);
    let cleared = host.render_frame(2).unwrap().to_vec();
    assert_eq!(
        writes_to(&decode_batch(&cleared).unwrap(), node),
        vec![None],
        "an empty list removes the property"
    );
}

#[test]
fn fr83_font_feature_settings_round_trips() {
    let bytes = font_feature_bytes(&FONT_FEATURE_VECTOR);
    assert_eq!(bytes.len(), 3 * FONT_FEATURE_LEN);
    assert_eq!(&bytes[0..8], TNUM);
    assert_eq!(&bytes[8..16], b"liga\0\0\0\0");
    assert_eq!(&bytes[16..24], b"cv01\x02\x01\0\0");
    assert!(FONT_FEATURE_VECTOR.len() <= MAX_FONT_FEATURES);

    let mutation = Mutation::SetProp {
        node_id: 22,
        property: PropertyKind::FontFeatureSettings,
        value: PropertyValue::Bytes(&bytes),
    };
    let mut encoder = BatchEncoder::default();
    encoder.encode(&mutation).unwrap();
    let encoded = encoder.finish().unwrap().to_vec();
    assert_eq!(decode_batch(&encoded).unwrap(), vec![mutation]);

    // The checked-in vector carries the same three records on text node 22, so the Kotlin
    // decoder is held to the same bytes.
    let vector = generate_mutation_vector().unwrap();
    let mutations = decode_batch(&vector).unwrap();
    assert!(mutations.contains(&Mutation::Create {
        node_id: 22,
        widget: WidgetKind::Text,
    }));
    assert_eq!(writes_to(&mutations, 22), vec![Some(bytes.clone())]);
    assert_eq!(
        include_bytes!("vectors/mutations.bin").as_slice(),
        vector.as_slice(),
        "the checked-in vector is the generated one; regenerate it with codegen"
    );
}

#[Composable]
fn Counter(count: MutableState<i32>, featured: MutableState<bool>) {
    let features: &[FontFeature] = if featured.get() {
        &[FontFeature::TABULAR_NUMS, FontFeature::SLASHED_ZERO]
    } else {
        &[]
    };
    Column().content(|| {
        Text(format!("{:04}", count.get())).font_feature_settings(features);
    });
}

/// The frames of a counter whose text changes every frame, with or without features.
fn frame_allocations(with_features: bool) -> usize {
    let count = mutable_state_of(0);
    let featured = mutable_state_of(with_features);
    let (state, flag) = (count, featured);
    let mut host = ComposeHost::with_content(move || Counter(state, flag));
    host.rebuild().unwrap();
    // Warm: the first frames size the scratch buffers the later ones reuse.
    for frame in 1..8 {
        count.set(frame as i32);
        host.render_frame(frame).unwrap();
    }
    count_allocations(|| {
        for frame in 8..40 {
            count.set(frame as i32);
            host.render_frame(frame).unwrap();
        }
    })
}

#[test]
fn fr83_font_features_do_not_allocate_in_steady_state() {
    let plain = frame_allocations(false);
    let featured = frame_allocations(true);
    assert_eq!(
        featured, plain,
        "a text with features allocated {featured} times over the frames a plain text took {plain}"
    );
}
