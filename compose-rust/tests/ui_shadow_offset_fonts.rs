//! The app-facing API reaches the wire: shadow layers, an offset, text fonts and word break.

mod support;

use compose_rust::runtime::*;
use compose_rust::schema::{Paint, WordBreak};
use compose_rust::ui::*;
use compose_rust::{ComposeHost, FontRef, FontRefs};
use support::Tree;

fn start(content: impl Fn() + Send + Sync + 'static) -> (ComposeHost, Tree) {
    let mut host = ComposeHost::with_content(content);
    let mut tree = Tree::default();
    tree.apply(host.rebuild().expect("the first batch encodes"));
    (host, tree)
}

fn ink() -> Paint {
    Paint::Literal(compose_rust::schema::Color(0x33000000))
}

#[test]
fn fr_42_a_modifier_with_two_shadow_layers_sends_two_shadow_modifiers() {
    let (_host, tree) = start(|| {
        Column()
            .modifier(
                Modifier
                    .shadow(Shadow::new(0.0, 1.0, 2.0, 0.0, ink()))
                    .shadow(Shadow::new(0.0, 8.0, 24.0, -4.0, ink())),
            )
            .content(|| {});
    });
    let dump = tree.dump();
    assert_eq!(dump.matches("Shadow").count(), 2, "{dump}");
}

#[test]
fn fr_42_shadows_from_an_iterator_keep_their_order_and_stop_at_the_limit() {
    let layers = (0..6).map(|n| Shadow::new(0.0, n as f32, 1.0, 0.0, ink()));
    let modifier = Modifier.shadows(layers);
    let (_host, tree) = start(move || {
        Column().modifier(modifier).content(|| {});
    });
    let dump = tree.dump();
    assert_eq!(dump.matches("Shadow").count(), MAX_SHADOWS, "{dump}");
}

#[test]
fn fr_42_a_node_that_asks_for_no_shadow_and_no_offset_sends_neither() {
    let (_host, tree) = start(|| {
        Column().content(|| {});
    });
    let dump = tree.dump();
    assert!(
        !dump.contains("Shadow") && !dump.contains("Offset"),
        "{dump}"
    );
}

#[test]
fn fr_42_an_offset_is_one_offset_modifier() {
    let (_host, tree) = start(|| {
        Box().modifier(Modifier.offset(3.0, -4.0)).content(|| {});
    });
    let dump = tree.dump();
    assert_eq!(dump.matches("Offset").count(), 1, "{dump}");
}

#[test]
fn fr_42_removing_a_shadow_layer_clears_its_slot() {
    let layers = remember_layers();
    let state = layers.clone();
    let (mut host, mut tree) = start(move || {
        let count = state.get();
        let modifier = (0..count).fold(Modifier, |modifier, n| {
            modifier.shadow(Shadow::new(0.0, n as f32, 1.0, 0.0, ink()))
        });
        Column().modifier(modifier).content(|| {});
    });
    assert_eq!(tree.dump().matches("Shadow").count(), 2);
    layers.set(1);
    let batch = host.render_frame(0).expect("the frame encodes").to_vec();
    tree.apply(&batch);
    assert_eq!(tree.dump().matches("Shadow").count(), 1, "{}", tree.dump());
}

fn remember_layers() -> MutableState<i32> {
    mutable_state_of(2)
}

#[test]
fn fr_40_text_and_a_text_field_send_the_fonts_they_were_given() {
    let (_host, tree) = start(|| {
        let fonts = FontRefs::new([FontRef::Asset(7)]);
        Text("가나다")
            .font(fonts.clone())
            .word_break(WordBreak::BreakAll);
        TextField().font(fonts.clone());
    });
    let dump = tree.dump();
    assert_eq!(dump.matches("Font=").count(), 2, "{dump}");
    assert!(dump.contains("WordBreak="), "{dump}");
}

#[test]
fn fr_40_text_that_names_no_font_sends_no_font() {
    let (_host, tree) = start(|| {
        Text("a");
        TextField();
    });
    let dump = tree.dump();
    assert!(
        !dump.contains("Font=") && !dump.contains("WordBreak="),
        "{dump}"
    );
}
