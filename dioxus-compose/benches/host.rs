use criterion::{Criterion, Throughput, criterion_group, criterion_main};
use dioxus_compose::Host;
use dioxus_compose::prelude::*;
use dioxus_compose::protocol::{BatchEncoder, HostEvent, Mutation, PropertyValue, decode_batch};
use dioxus_compose::schema::{EventPayload, PropertyKind, WidgetKind};
use std::time::Instant;

const HOST_INTERACTION_BUDGET_NS: u128 = 500_000;
const STREAMING_FRAME_BUDGET_NS: u128 = 1_000_000;
const STREAMING_TOKENS_PER_FRAME: usize = 2;
const LONG_CONVERSATION_MESSAGES: usize = 10_000;

fn app() -> Element {
    let mut count = use_signal(|| 0_u64);
    rsx! {
        Column {
            Text { text: count().to_string() }
            TextField { placeholder: "Message" }
            Button {
                text: "Increment",
                on_click: move |_| *count.write() += 1,
            }
        }
    }
}

fn host_and_targets() -> (Host, HostEvent<'static>, u32) {
    let mut host = Host::new(app);
    let initial = decode_batch(host.rebuild().unwrap()).unwrap();
    let (node_id, handler_id) = initial
        .iter()
        .find_map(|mutation| match mutation {
            Mutation::SetProp {
                node_id,
                property: PropertyKind::OnClick,
                value: PropertyValue::Integer(handler),
            } => Some((*node_id, *handler as u64)),
            _ => None,
        })
        .unwrap();
    let text_node_id = initial
        .iter()
        .find_map(|mutation| match mutation {
            Mutation::Create {
                node_id,
                widget: WidgetKind::Text,
            } => Some(*node_id),
            _ => None,
        })
        .unwrap();
    drop(initial);
    (
        host,
        HostEvent {
            node_id,
            handler_id,
            payload: EventPayload::Clicked,
        },
        text_node_id,
    )
}

fn encode_one_hundred(encoder: &mut BatchEncoder) {
    encoder.clear();
    for node_id in 0..100 {
        encoder
            .encode(&Mutation::SetProp {
                node_id,
                property: PropertyKind::Text,
                value: PropertyValue::String("benchmark"),
            })
            .unwrap();
    }
    std::hint::black_box(encoder.finish().unwrap());
}

/// Streaming shape: a long answer already on screen, then one frame worth of tokens
/// appended as tails and flushed by a single `render_frame`.
struct StreamingText {
    host: Host,
    text_node_id: u32,
}

impl StreamingText {
    fn new() -> Self {
        let (mut host, _, text_node_id) = host_and_targets();
        let mut conversation = String::with_capacity(LONG_CONVERSATION_MESSAGES * 64);
        for message in 0..LONG_CONVERSATION_MESSAGES {
            use std::fmt::Write as _;
            writeln!(
                conversation,
                "message {message:05}: representative long conversation content"
            )
            .unwrap();
        }
        let _ = host.set_text(text_node_id, &conversation, None).unwrap();
        let mut streaming = Self { host, text_node_id };
        streaming.frame();
        streaming
    }

    /// One 16ms frame at 100 tokens per second: the tokens that arrived, then one batch.
    fn frame(&mut self) {
        for _ in 0..STREAMING_TOKENS_PER_FRAME {
            self.host.append_text(self.text_node_id, " token");
        }
        let batch = self.host.render_frame(0).unwrap();
        std::hint::black_box(batch.len());
    }
}

fn p99_ns(iterations: usize, mut operation: impl FnMut()) -> u128 {
    let mut samples = Vec::with_capacity(iterations);
    for _ in 0..iterations {
        let started = Instant::now();
        operation();
        samples.push(started.elapsed().as_nanos());
    }
    samples.sort_unstable();
    samples[(iterations * 99).div_ceil(100) - 1]
}

fn benchmarks(criterion: &mut Criterion) {
    let (mut host, click, _) = host_and_targets();
    for _ in 0..10 {
        let _ = host.dispatch(click.clone()).unwrap();
    }
    criterion.bench_function("click_dispatch_diff_encode", |bencher| {
        bencher.iter(|| {
            let (batch, result) = host.dispatch(click.clone()).unwrap();
            std::hint::black_box((batch.len(), result));
        });
    });

    let mut encoder = BatchEncoder::with_capacity(4096, 2048, 128);
    encode_one_hundred(&mut encoder);
    let mut encode_group = criterion.benchmark_group("batch_encoding");
    encode_group.throughput(Throughput::Elements(100));
    encode_group.bench_function("encode_100_mutations", |bencher| {
        bencher.iter(|| encode_one_hundred(&mut encoder));
    });
    encode_group.finish();

    let mut streaming = StreamingText::new();
    criterion.bench_function("streaming_text_append_frame_long_conversation", |bencher| {
        bencher.iter(|| streaming.frame())
    });

    let samples = if std::env::var_os("DXC_BENCH_QUICK").is_some() {
        1_000
    } else {
        10_000
    };

    let (mut sampled_host, sampled_click, _) = host_and_targets();
    for _ in 0..10 {
        let _ = sampled_host.dispatch(sampled_click.clone()).unwrap();
    }
    let click_p99 = p99_ns(samples, || {
        let (batch, result) = sampled_host.dispatch(sampled_click.clone()).unwrap();
        std::hint::black_box((batch.len(), result));
    });

    let mut sampled_encoder = BatchEncoder::with_capacity(4096, 2048, 128);
    encode_one_hundred(&mut sampled_encoder);
    let encode_p99 = p99_ns(samples, || encode_one_hundred(&mut sampled_encoder));

    let mut sampled_streaming = StreamingText::new();
    let streaming_p99 = p99_ns(samples, || sampled_streaming.frame());

    eprintln!("nfr9_p99 click_dispatch_diff_encode_ns={click_p99}");
    eprintln!("nfr9_p99 encode_100_mutations_ns={encode_p99}");
    eprintln!("nfr9_p99 streaming_text_append_frame_long_conversation_ns={streaming_p99}");

    assert!(
        click_p99 <= HOST_INTERACTION_BUDGET_NS,
        "a click took {click_p99}ns at p99, over the {HOST_INTERACTION_BUDGET_NS}ns budget. \
         That budget is the share of a frame the Host may spend before the UI stops feeling \
         native, so treat this as a bug rather than a threshold to raise."
    );
    assert!(
        streaming_p99 <= STREAMING_FRAME_BUDGET_NS,
        "a streaming frame took {streaming_p99}ns at p99, over the {STREAMING_FRAME_BUDGET_NS}ns \
         budget. Streaming text appends every frame, so an overrun here shows up as dropped \
         frames while a response is being written out."
    );
}

criterion_group!(benches, benchmarks);
criterion_main!(benches);
