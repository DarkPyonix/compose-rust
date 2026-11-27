//! Font features on single runs of a `Text`: the runs that name features are listed in a
//! table of their own beside the run list, sent as the `SpanFontFeatures` property. A text
//! none of whose runs names a feature sends no such record, and a frame that only changes
//! the text of one that does costs no more than one without.

use compose_rust::ComposeHost;
use compose_rust::codegen::{generate_mutation_vector, span_font_feature_vector};
use compose_rust::font_features::{FONT_FEATURE_LEN, FontFeature, MAX_FONT_FEATURES};
use compose_rust::protocol::{BatchEncoder, Mutation, PropertyValue, decode_batch};
use compose_rust::runtime::*;
use compose_rust::schema::{PropertyKind, TypeRole, WidgetKind};
use compose_rust::spans::{
    SPAN_FEATURE_COUNT_AT, SPAN_FEATURE_ENTRY_LEN, SPAN_FEATURE_OFFSET_AT, SPAN_FEATURE_SPAN_AT,
    TextSpan, TextSpans, encode_feature_table,
};
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

/// The `Text` nodes `mutations` creates, in order.
fn text_nodes(mutations: &[Mutation<'_>]) -> Vec<u32> {
    mutations
        .iter()
        .filter_map(|mutation| match mutation {
            Mutation::Create {
                node_id,
                widget: WidgetKind::Text,
            } => Some(*node_id),
            _ => None,
        })
        .collect()
}

/// Every `SpanFontFeatures` value written to `node`: the table, or `None` for a record
/// that clears it.
fn writes_to(mutations: &[Mutation<'_>], node: u32) -> Vec<Option<Vec<u8>>> {
    mutations
        .iter()
        .filter_map(|mutation| match mutation {
            Mutation::SetProp {
                node_id,
                property: PropertyKind::SpanFontFeatures,
                value,
            } if *node_id == node => match value {
                PropertyValue::Bytes(bytes) => Some(Some(bytes.to_vec())),
                PropertyValue::None => Some(None),
                other => panic!("SpanFontFeatures is a blob, not {other:?}"),
            },
            _ => None,
        })
        .collect()
}

fn word(table: &[u8], at: usize) -> u32 {
    u32::from_le_bytes(table[at..at + 4].try_into().unwrap())
}

/// Three runs, the first with no features, the second with one and the third with two.
fn three_runs() -> TextSpans {
    TextSpans::new_with_features([
        (TextSpan::new(0, 5), vec![]),
        (TextSpan::new(5, 4), vec![FontFeature::TABULAR_NUMS]),
        (
            TextSpan::new(9, 6),
            vec![FontFeature::NO_LIGATURES, FontFeature::new(b"ss01", 2)],
        ),
    ])
}

/// The table byte for byte: the entry count, a 12-byte entry for each run that names
/// features, then 8-byte feature records, every offset counted from the start of the blob.
#[test]
fn fr88_span_font_feature_table_is_a_count_entries_and_records() {
    assert_eq!(SPAN_FEATURE_ENTRY_LEN, 12);
    assert_eq!(
        (
            SPAN_FEATURE_SPAN_AT,
            SPAN_FEATURE_OFFSET_AT,
            SPAN_FEATURE_COUNT_AT
        ),
        (0, 4, 8)
    );
    assert_eq!(FONT_FEATURE_LEN, 8);

    let spans = three_runs();
    let mut expected: Vec<u8> = Vec::new();
    // Two runs name features; the run without any has no entry.
    expected.extend_from_slice(&2_u32.to_le_bytes());
    // Run 1: one record, straight after the two entries (4 + 2 * 12 = 28).
    for value in [1_u32, 28, 1] {
        expected.extend_from_slice(&value.to_le_bytes());
    }
    // Run 2: two records, after run 1's one (28 + 8 = 36).
    for value in [2_u32, 36, 2] {
        expected.extend_from_slice(&value.to_le_bytes());
    }
    expected.extend_from_slice(b"tnum\x01\0\0\0");
    expected.extend_from_slice(b"liga\0\0\0\0");
    expected.extend_from_slice(b"ss01\x02\0\0\0");
    assert_eq!(spans.feature_table(), expected.as_slice());
    assert_eq!(spans.feature_table().len() % 4, 0);
    // The runs themselves are the 36-byte records they always were.
    assert_eq!(spans.as_bytes().len(), 3 * 36);
    assert_eq!(
        spans.as_bytes(),
        TextSpans::new([
            TextSpan::new(0, 5),
            TextSpan::new(5, 4),
            TextSpan::new(9, 6)
        ])
        .as_bytes()
    );
}

#[test]
fn fr88_span_font_features_encode_decode_round_trip() {
    let spans = three_runs();
    assert_eq!(spans.feature_entry_count(), 2);
    assert_eq!(
        spans.span_features(),
        vec![
            (1, vec![FontFeature::TABULAR_NUMS]),
            (
                2,
                vec![FontFeature::NO_LIGATURES, FontFeature::new(b"ss01", 2)]
            ),
        ]
    );

    // An adopted table reads back the same, beside runs made without one.
    let adopted = TextSpans::new([
        TextSpan::new(0, 5),
        TextSpan::new(5, 4),
        TextSpan::new(9, 6),
    ])
    .with_feature_table(spans.feature_table().to_vec());
    assert_eq!(adopted.span_features(), spans.span_features());
    assert_eq!(adopted, spans);

    // Through the batch encoder and back.
    let table = spans.feature_table().to_vec();
    let mutation = Mutation::SetProp {
        node_id: 24,
        property: PropertyKind::SpanFontFeatures,
        value: PropertyValue::Bytes(&table),
    };
    let mut encoder = BatchEncoder::default();
    encoder.encode(&mutation).unwrap();
    let encoded = encoder.finish().unwrap().to_vec();
    assert_eq!(decode_batch(&encoded).unwrap(), vec![mutation]);

    // The checked-in vector carries the vector's table on text node 24, so the Kotlin
    // decoder is held to the same bytes.
    let vector_table = encode_feature_table(&span_font_feature_vector()).unwrap();
    assert_eq!(
        TextSpans::default()
            .with_feature_table(vector_table.to_vec())
            .span_features(),
        span_font_feature_vector()
    );
    let vector = generate_mutation_vector().unwrap();
    let mutations = decode_batch(&vector).unwrap();
    assert!(mutations.contains(&Mutation::Create {
        node_id: 24,
        widget: WidgetKind::Text,
    }));
    assert_eq!(writes_to(&mutations, 24), vec![Some(vector_table.to_vec())]);
    let checked_in = decode_batch(include_bytes!("vectors/mutations.bin")).unwrap();
    assert_eq!(
        writes_to(&checked_in, 24),
        vec![Some(vector_table.to_vec())],
        "the checked-in vector is the generated one; regenerate it with codegen"
    );
}

#[test]
fn fr88_runs_without_features_make_no_table() {
    let plain = TextSpans::new_with_features([
        (TextSpan::new(0, 4), Vec::<FontFeature>::new()),
        (TextSpan::new(4, 4), Vec::new()),
    ]);
    assert!(plain.feature_table().is_empty());
    assert_eq!(plain.feature_entry_count(), 0);
    assert!(plain.span_features().is_empty());
    assert_eq!(plain.as_bytes().len(), 2 * 36);

    assert_eq!(encode_feature_table(&[]), None);
    assert_eq!(encode_feature_table(&[(0, vec![]), (3, vec![])]), None);
    // A run with an empty list among ones that name features gets no entry.
    let table = encode_feature_table(&[
        (0, vec![]),
        (3, vec![FontFeature::SLASHED_ZERO]),
        (5, vec![]),
    ])
    .unwrap();
    assert_eq!(word(&table, 0), 1);
    assert_eq!(table.len(), 4 + SPAN_FEATURE_ENTRY_LEN + FONT_FEATURE_LEN);
    assert_eq!(
        TextSpans::default()
            .with_feature_table(table.to_vec())
            .span_features(),
        vec![(3, vec![FontFeature::SLASHED_ZERO])]
    );
    // An empty adopted table is no table.
    assert!(
        three_runs()
            .with_feature_table(Vec::<u8>::new())
            .feature_table()
            .is_empty()
    );
}

/// A table that breaks its layout reads back as nothing, whole, and reading it neither
/// panics nor walks a count that is only a large number.
#[test]
fn fr88_a_malformed_table_reads_back_as_no_features() {
    let good = three_runs().feature_table().to_vec();
    let read = |table: Vec<u8>| {
        TextSpans::default()
            .with_feature_table(table)
            .span_features()
    };
    assert_eq!(read(good.clone()).len(), 2, "the table these are cut from");
    let patched = |at: usize, value: u32| {
        let mut table = good.clone();
        table[at..at + 4].copy_from_slice(&value.to_le_bytes());
        table
    };
    let first_entry = 4;
    let second_entry = 4 + SPAN_FEATURE_ENTRY_LEN;

    // Shorter than its own count, and a count that could not fit in any table.
    assert!(read(good[..3].to_vec()).is_empty());
    assert!(read(good[..4 + SPAN_FEATURE_ENTRY_LEN].to_vec()).is_empty());
    assert!(read(patched(0, 3)).is_empty());
    assert!(read(patched(0, u32::MAX)).is_empty());
    // A feature list that ends past the blob, by its offset and by its count.
    assert!(
        read(patched(
            second_entry + SPAN_FEATURE_OFFSET_AT,
            good.len() as u32 - 8
        ))
        .is_empty()
    );
    assert!(read(patched(second_entry + SPAN_FEATURE_OFFSET_AT, u32::MAX - 3)).is_empty());
    assert!(read(patched(second_entry + SPAN_FEATURE_COUNT_AT, 3)).is_empty());
    // An offset that is not a multiple of four, and one that points into the entries.
    assert!(read(patched(first_entry + SPAN_FEATURE_OFFSET_AT, 30)).is_empty());
    assert!(read(patched(first_entry + SPAN_FEATURE_OFFSET_AT, 16)).is_empty());
    // The same run named twice.
    assert!(read(patched(second_entry + SPAN_FEATURE_SPAN_AT, 1)).is_empty());

    // Sixteen features for one run are taken, seventeen are not. The longer list is still
    // encoded, so the Renderer can say what is wrong with it.
    let most = vec![FontFeature::TABULAR_NUMS; MAX_FONT_FEATURES];
    let table = encode_feature_table(&[(0, most.clone())]).unwrap();
    assert_eq!(read(table.to_vec()), vec![(0, most)]);
    let over = encode_feature_table(&[(0, vec![FontFeature::TABULAR_NUMS; MAX_FONT_FEATURES + 1])])
        .unwrap();
    assert_eq!(word(&over, 4 + SPAN_FEATURE_COUNT_AT), 17);
    assert_eq!(
        over.len(),
        4 + SPAN_FEATURE_ENTRY_LEN + 17 * FONT_FEATURE_LEN
    );
    assert!(read(over.to_vec()).is_empty());
}

#[Composable]
fn Texts() {
    Column!({
        Text!(
            "12345678",
            spans = TextSpans::new_with_features([
                (TextSpan::new(0, 4), vec![]),
                (TextSpan::new(4, 4), vec![FontFeature::TABULAR_NUMS]),
            ])
        );
        Text!("87654321", spans = TextSpans::new([TextSpan::new(0, 8)]));
        Text!("no runs at all");
        Text!(
            "runs with empty lists",
            spans =
                TextSpans::new_with_features([(TextSpan::new(0, 4), Vec::<FontFeature>::new(),)])
        );
        Text!(
            "12:04 pm",
            type_role = TypeRole::Body,
            spans = TextSpans::new_with_features([(
                TextSpan::new(0, 5),
                vec![FontFeature::TABULAR_NUMS],
            )])
        );
    });
}

#[test]
fn fr88_span_font_features_writes_one_property_only_when_set() {
    assert_eq!(PropertyKind::SpanFontFeatures as u16, 209);
    assert_eq!(
        PropertyKind::from_name("span_font_features"),
        Ok(PropertyKind::SpanFontFeatures)
    );

    let mut host = ComposeHost::with_content(Texts);
    let batch = host.rebuild().expect("the first batch encodes").to_vec();
    let mutations = decode_batch(&batch).expect("the batch round-trips through the decoder");
    let texts = text_nodes(&mutations);
    assert_eq!(texts.len(), 5, "five texts are created");

    let expected = encode_feature_table(&[(1, vec![FontFeature::TABULAR_NUMS])]).unwrap();
    assert_eq!(
        writes_to(&mutations, texts[0]),
        vec![Some(expected.to_vec())],
        "one property, once, naming the second run"
    );
    assert!(
        writes_to(&mutations, texts[1]).is_empty(),
        "runs that name no feature send no record, not even one that clears"
    );
    assert!(
        writes_to(&mutations, texts[2]).is_empty(),
        "a text with no runs sends no record"
    );
    assert!(
        writes_to(&mutations, texts[3]).is_empty(),
        "runs given empty lists send no record"
    );
    let role = encode_feature_table(&[(0, vec![FontFeature::TABULAR_NUMS])]).unwrap();
    assert_eq!(
        writes_to(&mutations, texts[4]),
        vec![Some(role.to_vec())],
        "text with a role sends its runs' features too"
    );
}

#[Composable]
fn Toggled(on: MutableState<bool>, count: MutableState<i32>) {
    let features = if on.get() {
        vec![FontFeature::TABULAR_NUMS]
    } else {
        Vec::new()
    };
    let spans = TextSpans::new_with_features([(TextSpan::new(0, 4), features)]);
    Column!({
        Text!(format!("{:04}", count.get()), spans = spans.clone());
    });
}

/// A table that goes away clears the property, and an unchanged table is not sent again.
#[test]
fn fr88_span_font_features_clear_and_are_not_resent() {
    let on = mutable_state_of(true);
    let count = mutable_state_of(0);
    let (on_state, count_state) = (on, count);
    let mut host = ComposeHost::with_content(move || Toggled(on_state, count_state));
    let first = host.rebuild().unwrap().to_vec();
    let first = decode_batch(&first).unwrap();
    let node = text_nodes(&first)[0];
    let table = encode_feature_table(&[(0, vec![FontFeature::TABULAR_NUMS])]).unwrap();
    assert_eq!(writes_to(&first, node), vec![Some(table.to_vec())]);

    count.set(1);
    let changed = host.render_frame(1).unwrap().to_vec();
    assert!(
        writes_to(&decode_batch(&changed).unwrap(), node).is_empty(),
        "the same table is not written again when only the text changes"
    );

    on.set(false);
    let cleared = host.render_frame(2).unwrap().to_vec();
    assert_eq!(
        writes_to(&decode_batch(&cleared).unwrap(), node),
        vec![None],
        "a table that goes away removes the property"
    );

    on.set(true);
    let back = host.render_frame(3).unwrap().to_vec();
    assert_eq!(
        writes_to(&decode_batch(&back).unwrap(), node),
        vec![Some(table.to_vec())],
        "and one that comes back is written again"
    );
}

thread_local! {
    /// Run lists kept between frames, as an application that draws the same runs every
    /// frame keeps them: made once, so a frame's cost is the widget's and not the list's.
    static FEATURED_RUNS: TextSpans = TextSpans::new_with_features([
        (TextSpan::new(0, 2), vec![FontFeature::TABULAR_NUMS, FontFeature::SLASHED_ZERO]),
        (TextSpan::new(2, 2), vec![]),
    ]);
    static PLAIN_RUNS: TextSpans = TextSpans::new([TextSpan::new(0, 2), TextSpan::new(2, 2)]);
}

#[Composable]
fn Counter(count: MutableState<i32>, featured: MutableState<bool>) {
    let spans = if featured.get() {
        FEATURED_RUNS.with(TextSpans::clone)
    } else {
        PLAIN_RUNS.with(TextSpans::clone)
    };
    Column!({
        Text!(format!("{:04}", count.get()), spans = spans.clone());
    });
}

/// The frames of a counter whose text changes every frame, its runs with or without features.
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
fn fr88_span_font_features_do_not_allocate_in_steady_state() {
    // One unmeasured pass first: whatever the process sets up once (lazy statics, the first
    // growth of a shared buffer) lands in it, not in whichever measurement happens to run
    // first, so the two counts compare the runs and nothing else.
    frame_allocations(false);
    let plain = frame_allocations(false);
    let featured = frame_allocations(true);
    assert_eq!(
        featured, plain,
        "a text whose runs name features allocated {featured} times over the frames one whose runs name none took {plain}"
    );
}

#[test]
fn fr88_measure_record_carries_span_font_features() {
    use compose_rust::measure::{
        MEASURE_RECORD_LEN, MeasureRequests, TEXT_SPAN_FEATURES_LENGTH_AT,
        TEXT_SPAN_FEATURES_OFFSET_AT, TextConstraint, TextStyle,
    };

    assert_eq!(MEASURE_RECORD_LEN, 88);
    let featured = TextSpans::new_with_features([
        (TextSpan::new(0, 6), vec![]),
        (TextSpan::new(6, 4), vec![FontFeature::TABULAR_NUMS]),
    ]);
    let table = featured.feature_table().to_vec();
    assert!(!table.is_empty());

    let mut requests = MeasureRequests::new();
    let style = TextStyle::default();
    requests.text("Total 1111", &featured, &style, TextConstraint::MaxContent);
    requests.text(
        "Total 1111",
        &TextSpans::new([TextSpan::new(0, 6), TextSpan::new(6, 4)]),
        &style,
        TextConstraint::MaxContent,
    );
    let buffer = requests.encoded();
    let word = |at: usize| u32::from_le_bytes(buffer[at..at + 4].try_into().unwrap()) as usize;

    let at = word(TEXT_SPAN_FEATURES_OFFSET_AT);
    let length = word(TEXT_SPAN_FEATURES_LENGTH_AT);
    assert!(
        at >= 2 * MEASURE_RECORD_LEN,
        "the table sits after the records"
    );
    assert_eq!(length, table.len());
    assert_eq!(
        &buffer[at..at + length],
        &table[..],
        "the measure request carries the same bytes as the SpanFontFeatures property"
    );

    assert_eq!(
        word(MEASURE_RECORD_LEN + TEXT_SPAN_FEATURES_LENGTH_AT),
        0,
        "a text whose runs name no feature sends no table"
    );
}
