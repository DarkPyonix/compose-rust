//! Switch, Slider and Checkbox take colours and a size, and send them only when asked.

mod support;

use compose_rust::ComposeHost;
use compose_rust::runtime::*;
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

#[composable]
fn Plain() {
    Column().content(|| {
        Switch(true);
        Slider(0.5);
        Checkbox(true);
    });
}

#[composable]
fn Styled() {
    Column().content(|| {
        Switch(true)
            .size(36.0, 20.0)
            .track_color(INK)
            .thumb_color(SURFACE);
        Slider(0.5)
            .active_track_color(INK)
            .track_color(SURFACE)
            .thumb_color(INK);
        Checkbox(true)
            .size(18.0)
            .container_color(INK)
            .check_color(SURFACE);
    });
}

fn line<'a>(dump: &'a str, widget: &str) -> &'a str {
    dump.lines()
        .find(|line| line.trim_start().starts_with(widget))
        .unwrap_or_else(|| panic!("no {widget} in\n{dump}"))
}

#[test]
fn fr67_controls_that_set_nothing_send_none_of_the_new_properties() {
    let dump = start(Plain).dump();
    for name in [
        "TrackColor",
        "ThumbColor",
        "ActiveTrackColor",
        "ContainerColor",
        "CheckColor",
        "ControlSize",
    ] {
        assert!(!dump.contains(name), "{name} was sent in\n{dump}");
    }
}

#[test]
fn fr67_the_switch_sends_its_colours_and_its_size() {
    let dump = start(Styled).dump();
    let switch = line(&dump, "Switch");
    for name in ["TrackColor", "ThumbColor", "ControlSize"] {
        assert!(switch.contains(name), "{switch}");
    }
    for name in ["ActiveTrackColor", "ContainerColor", "CheckColor"] {
        assert!(!switch.contains(name), "{switch}");
    }
}

#[test]
fn fr67_the_slider_sends_the_active_and_remaining_track_and_the_thumb() {
    let dump = start(Styled).dump();
    let slider = line(&dump, "Slider");
    for name in ["ActiveTrackColor", "TrackColor", "ThumbColor"] {
        assert!(slider.contains(name), "{slider}");
    }
    assert!(!slider.contains("ControlSize"), "{slider}");
}

#[test]
fn fr67_the_checkbox_sends_its_container_check_and_side() {
    let dump = start(Styled).dump();
    let checkbox = line(&dump, "Checkbox");
    for name in ["ContainerColor", "CheckColor", "ControlSize"] {
        assert!(checkbox.contains(name), "{checkbox}");
    }
    assert!(!checkbox.contains("TrackColor"), "{checkbox}");
}
