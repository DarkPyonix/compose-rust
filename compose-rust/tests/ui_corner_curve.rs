//! Continuous corner curves reach the wire through the app-facing Modifier.

mod support;

use compose_rust::ComposeHost;
use compose_rust::protocol::{Mutation, decode_batch};
use compose_rust::runtime::*;
use compose_rust::ui::*;
use support::Tree;

fn start(content: impl Fn() + Send + Sync + 'static) -> (ComposeHost, Tree) {
    let mut host = ComposeHost::with_content(content);
    let mut tree = Tree::default();
    tree.apply(host.rebuild().expect("the first batch encodes"));
    (host, tree)
}

#[test]
fn fr50_corner_curve_clamps_out_of_range() {
    assert_eq!(compose_rust::schema::corner_smoothing(-0.5), 0.0);
    assert_eq!(
        compose_rust::schema::corner_smoothing(f32::NEG_INFINITY),
        0.0
    );
    assert_eq!(compose_rust::schema::corner_smoothing(1.5), 1.0);
    assert_eq!(compose_rust::schema::corner_smoothing(f32::INFINITY), 1.0);
    assert_eq!(compose_rust::schema::corner_smoothing(f32::NAN), 0.0);
    assert_eq!(compose_rust::schema::corner_smoothing(0.6), 0.6);

    for (given, sent) in [(-3.0, "0.0"), (4.0, "1.0"), (f32::NAN, "0.0")] {
        let (_host, tree) = start(move || {
            Box()
                .modifier(Modifier.corner_radius(16.0).corner_curve(given))
                .content(|| {});
        });
        let dump = tree.dump();
        assert!(
            dump.contains(&format!("CornerCurve {{ smoothing: {sent} }}")),
            "{given} should be sent as {sent}: {dump}"
        );
    }
}

#[test]
fn fr50_corner_curve_combines_with_corner_each() {
    let (_host, tree) = start(|| {
        Box()
            .modifier(
                Modifier
                    .corner_radii([4.0, 8.0, 12.0, 16.0])
                    .corner_curve(0.6),
            )
            .content(|| {});
    });
    let dump = tree.dump();
    assert!(dump.contains("CornerCurve { smoothing: 0.6 }"), "{dump}");
    assert!(
        dump.contains(
            "Shape { top_start: 4.0, top_end: 8.0, bottom_end: 12.0, bottom_start: 16.0 }"
        ),
        "the radii are sent unchanged next to the curve: {dump}"
    );
}

#[test]
fn fr50_corner_curve_change_sends_one_modifier() {
    let smoothing = mutable_state_of(0.6_f32);
    let state = smoothing.clone();
    let (mut host, mut tree) = start(move || {
        Box()
            .modifier(Modifier.corner_radius(16.0).corner_curve(state.get()))
            .content(|| {
                Text("child");
            });
    });
    smoothing.set(0.2);
    let batch = host.render_frame(0).expect("the frame encodes").to_vec();
    let mutations = decode_batch(&batch).expect("the batch decodes");
    tree.apply(&batch);
    assert_eq!(mutations.len(), 1, "{mutations:?}");
    assert!(
        matches!(
            mutations[0],
            Mutation::SetModifier {
                modifier: compose_rust::Modifier::CornerCurve { smoothing },
                ..
            } if smoothing == 0.2
        ),
        "{mutations:?}"
    );
    assert_eq!(tree.texts(), vec!["child".to_owned()]);
}
