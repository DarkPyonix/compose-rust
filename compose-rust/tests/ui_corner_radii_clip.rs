//! Individual corner radii and clipping reach the wire through the app-facing Modifier.

mod support;

use compose_rust::ComposeHost;
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
fn fr_60_corner_radii_sends_one_shape_modifier_with_four_corners() {
    let (_host, tree) = start(|| {
        Box()
            .modifier(Modifier.corner_radii([8.0, 8.0, 0.0, 0.0]))
            .content(|| {});
    });
    let dump = tree.dump();
    assert!(
        dump.contains("Shape { top_start: 8.0, top_end: 8.0, bottom_end: 0.0, bottom_start: 0.0 }"),
        "{dump}"
    );
}

#[test]
fn fr_60_clip_sends_one_clip_modifier_and_clears_when_disabled() {
    let enabled = mutable_state_of(true);
    let state = enabled.clone();
    let (mut host, mut tree) = start(move || {
        Box().modifier(Modifier.clip(state.get())).content(|| {});
    });
    assert!(tree.dump().contains("Clip(true)"), "{}", tree.dump());
    enabled.set(false);
    let batch = host.render_frame(0).expect("the frame encodes").to_vec();
    tree.apply(&batch);
    assert!(!tree.dump().contains("Clip"), "{}", tree.dump());
}
