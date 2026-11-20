//! ProgressIndicator accepts color and track_color.

mod support;

use compose_rust::ComposeHost;
use compose_rust::ui::*;
use support::Tree;

fn start(content: impl Fn() + Send + Sync + 'static) -> Tree {
    let mut host = ComposeHost::with_content(content);
    let mut tree = Tree::default();
    tree.apply(host.rebuild().expect("the first batch encodes"));
    tree
}

const INK: Paint = Paint::Literal(Color::rgb(0x1c1b19));
const SURFACE: Paint = Paint::Literal(Color::rgb(0xffffff));

fn line<'a>(dump: &'a str, widget: &str) -> &'a str {
    dump.lines()
        .find(|line| line.trim_start().starts_with(widget))
        .unwrap_or_else(|| panic!("no {widget} in\n{dump}"))
}

#[test]
fn fr15_progress_indicator_without_colors_sends_neither() {
    let tree = start(|| {
        ProgressIndicator!(0.5);
        IndeterminateProgressIndicator!();
    });
    let dump = tree.dump();
    assert!(!dump.contains("Color="), "{dump}");
    assert!(!dump.contains("TrackColor="), "{dump}");
}

#[test]
fn fr15_progress_indicator_sends_color_and_track_color() {
    let tree = start(|| {
        ProgressIndicator!(0.5, color = INK, track_color = SURFACE);
    });
    let dump = tree.dump();
    let indicator = line(&dump, "ProgressIndicator");
    assert!(indicator.contains("Color="), "{indicator}");
    assert!(indicator.contains("TrackColor="), "{indicator}");
}

#[test]
fn fr15_indeterminate_progress_indicator_sends_color_and_track_color() {
    let tree = start(|| {
        IndeterminateProgressIndicator!(color = INK, track_color = SURFACE);
    });
    let dump = tree.dump();
    let indicator = line(&dump, "ProgressIndicator");
    assert!(indicator.contains("Color="), "{indicator}");
    assert!(indicator.contains("TrackColor="), "{indicator}");
}
