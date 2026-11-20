//! A node's hover style, focus ring and press style reach the wire through the app-facing
//! Modifier, each as one record of its own, and only on the node that asked for it.

mod support;

use compose_rust::ComposeHost;
use compose_rust::protocol::{BatchEncoder, Mutation, decode_batch};
use compose_rust::ui::*;
use support::Tree;

fn start(content: impl Fn() + Send + Sync + 'static) -> (ComposeHost, Tree) {
    let mut host = ComposeHost::with_content(content);
    let mut tree = Tree::default();
    tree.apply(host.rebuild().expect("the first batch encodes"));
    (host, tree)
}

const RED: Paint = Paint::Literal(Color::argb(0xffff_0000));

/// Encodes one record and returns its tag, its length and what it decodes back to.
fn wire(modifier: compose_rust::Modifier) -> (u16, u16, Vec<Mutation<'static>>) {
    let expected = Mutation::SetModifier {
        node_id: 3,
        index: 0,
        modifier,
    };
    let mut encoder = BatchEncoder::default();
    encoder.encode(&expected).unwrap();
    // Leaked so the decoded records can outlive this call.
    let bytes: &'static [u8] =
        std::boxed::Box::leak(encoder.finish().unwrap().to_vec().into_boxed_slice());
    let tag = u16::from_le_bytes([bytes[22], bytes[23]]);
    let len = u16::from_le_bytes([bytes[14], bytes[15]]);
    let decoded = decode_batch(bytes).unwrap();
    assert_eq!(decoded, [expected]);
    (tag, len, decoded)
}

#[test]
fn fr55_hover_style_builder_reaches_the_wire() {
    let (_host, tree) = start(|| {
        Row!({
            Box!(
                modifier = Modifier.hover_style(
                    HoverStyle::fill(ColorRole::Primary, 0.12)
                        .border(RED)
                        .content_alpha(0.88),
                ),
                on_click = Some(|| {}),
                {}
            );
            Box!(on_click = Some(|| {}), {});
        });
    });
    let dump = tree.dump();
    assert!(
        dump.contains(
            "HoverStyle { fill: Some(Role(Primary)), fill_alpha: 0.12, \
             border: Some(Literal(Color(4294901760))), content_alpha: 0.88 }"
        ),
        "{dump}"
    );
    assert_eq!(dump.matches("HoverStyle").count(), 1, "{dump}");
}

#[test]
fn fr55_hover_style_default_changes_nothing_and_round_trips() {
    let style = HoverStyle::default();
    assert_eq!(style.fill, None);
    assert_eq!(style.border, None);
    assert_eq!(style.content_alpha, 1.0);
    let (tag, len, _) = wire(compose_rust::Modifier::HoverStyle {
        fill: None,
        fill_alpha: 0.0,
        border: None,
        content_alpha: 1.0,
    });
    assert_eq!(tag, 43);
    assert_eq!(len, 36, "two paints and two alphas take three words");
    let (_, _, _) = wire(compose_rust::Modifier::HoverStyle {
        fill: Some(Paint::Role(ColorRole::OnSurface)),
        fill_alpha: 0.2,
        border: Some(RED),
        content_alpha: 0.5,
    });
}

#[test]
fn fr55_focus_ring_builder_reaches_the_wire() {
    let (_host, tree) = start(|| {
        Box!(
            modifier = Modifier.focus_ring(RED, 3.0, 2.0),
            on_click = Some(|| {}),
            {}
        );
    });
    let dump = tree.dump();
    assert!(
        dump.contains(
            "FocusRing { paint: Literal(Color(4294901760)), width_dp: 3.0, offset_dp: 2.0 }"
        ),
        "{dump}"
    );
    let (tag, len, _) = wire(compose_rust::Modifier::FocusRing {
        paint: Paint::Role(ColorRole::Primary),
        width_dp: 2.0,
        offset_dp: 0.0,
    });
    assert_eq!(tag, 44);
    assert_eq!(len, 28);
}

#[test]
fn fr51_press_style_builder_reaches_the_wire() {
    let (_host, tree) = start(|| {
        Row!({
            Box!(
                modifier = Modifier.press_style(
                    PressStyle::tint(ColorRole::OnPrimary).press_easing(0.2, 0.0, 0.0, 1.0),
                ),
                on_click = Some(|| {}),
                {}
            );
            Box!(
                modifier = Modifier.press_style(PressStyle::default()),
                on_click = Some(|| {}),
                {}
            );
        });
    });
    let dump = tree.dump();
    assert!(
        dump.contains(
            "PressStyle { tint: Some(Role(OnPrimary)), press_easing: Some([0.2, 0.0, 0.0, 1.0]) }"
        ),
        "{dump}"
    );
    assert!(
        dump.contains("PressStyle { tint: None, press_easing: None }"),
        "{dump}"
    );
    let (tag, len, _) = wire(compose_rust::Modifier::PressStyle {
        tint: None,
        press_easing: None,
    });
    assert_eq!(tag, 45);
    assert_eq!(len, 36);
}

#[test]
fn fr55_a_node_without_the_new_modifiers_sends_none_of_them() {
    let (_host, tree) = start(|| {
        Box!(on_click = Some(|| {}), {});
    });
    let dump = tree.dump();
    for name in ["HoverStyle", "FocusRing", "PressStyle"] {
        assert!(!dump.contains(name), "{name}: {dump}");
    }
}
