//! TextField typography and control over composer / icon button tokens.

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

const CUSTOM_COLOR: Paint = Paint::Literal(Color::rgb(0x123456));

#[Composable]
fn StyledField() {
    Column!({
        TextField!(
            "custom",
            |_| {},
            font_size = 15.0,
            font_weight = 600,
            line_height = 22.0,
            letter_spacing = 0.5,
            color = CUSTOM_COLOR
        );
    });
}

#[Composable]
fn PlainField() {
    Column!({
        TextField!("plain", |_| {});
    });
}

#[test]
fn fr75_text_field_typography_round_trips() {
    let tree = start(StyledField);
    let dump = tree.dump();
    assert!(dump.contains("TextField"), "missing TextField: {dump}");
    assert!(
        dump.contains("FontSize=Float(15.0)"),
        "missing FontSize in {dump}"
    );
    assert!(
        dump.contains("FontWeight=Integer(600)"),
        "missing FontWeight in {dump}"
    );
    assert!(
        dump.contains("LineHeight=Float(22.0)"),
        "missing LineHeight in {dump}"
    );
    assert!(
        dump.contains("LetterSpacing=Float(0.5)"),
        "missing LetterSpacing in {dump}"
    );
    assert!(dump.contains("Color=Integer("), "missing Color in {dump}");
    assert!(
        dump.contains(&format!("Color=Integer({})", CUSTOM_COLOR.to_bits() as i64)),
        "missing Color in {dump}"
    );
}

#[test]
fn fr75_text_field_plain_does_not_send_typography_properties() {
    let tree = start(PlainField);
    let dump = tree.dump();
    assert!(dump.contains("TextField"), "missing TextField: {dump}");
    for prop in [
        "FontSize",
        "FontWeight",
        "LineHeight",
        "LetterSpacing",
        "Color",
    ] {
        assert!(
            !dump.contains(prop),
            "{prop} should not be sent for plain field: {dump}"
        );
    }
}

/// What the tree holds for a 12 by 6 dp content padding: the four-sided padding, in the
/// slot kept for the room inside a control.
const CONTENT_PADDING: &str = "m36=PaddingEach { start: 12.0, top: 6.0, end: 12.0, bottom: 6.0 }";

#[test]
fn fr75_button_content_padding_is_sent_as_the_four_sided_padding() {
    let tree = start(|| {
        Button!("Commit", || {}, content_padding = (12.0, 6.0));
    });
    let dump = tree.dump();
    assert!(dump.contains("Button"), "missing Button: {dump}");
    assert!(
        dump.contains(CONTENT_PADDING),
        "missing the content padding in {dump}"
    );
}

#[test]
fn fr75_button_content_padding_leaves_the_padding_around_it() {
    let tree = start(|| {
        Button!(
            "Commit",
            || {},
            content_padding = (12.0, 6.0),
            modifier = Modifier.padding(8.0)
        );
    });
    let dump = tree.dump();
    assert!(
        dump.contains(CONTENT_PADDING),
        "missing the content padding in {dump}"
    );
    assert!(
        dump.contains("m22=Padding(8.0)"),
        "the padding around the button was displaced: {dump}"
    );
}

#[test]
fn fr75_button_without_content_padding_sends_no_padding() {
    let tree = start(|| {
        Button!("Commit", || {});
    });
    let dump = tree.dump();
    assert!(dump.contains("Button"), "missing Button: {dump}");
    assert!(
        !dump.contains("Padding"),
        "a plain button should send no padding: {dump}"
    );
}
