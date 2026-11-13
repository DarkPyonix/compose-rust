//! A border on some sides only reaches the wire through the app-facing Modifier as the
//! one `BorderEach` record the Renderer already draws.

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

const GUIDE: Paint = Paint::Literal(Color::argb(0xff33_6699));
const RULE: Paint = Paint::Role(ColorRole::Outline);

fn border_each(widths: [f32; 4], paints: [Paint; 4]) -> String {
    let [top, right, bottom, left] = widths;
    let [top_paint, right_paint, bottom_paint, left_paint] = paints;
    format!(
        "{:?}",
        compose_rust::Modifier::BorderEach {
            top,
            right,
            bottom,
            left,
            top_paint,
            right_paint,
            bottom_paint,
            left_paint,
        }
    )
}

#[test]
fn fr60_border_side_start_writes_border_each_with_zero_widths_on_the_other_sides() {
    let (_host, tree) = start(|| {
        Box()
            .modifier(Modifier.border_side(Side::Start, 1.0, GUIDE))
            .content(|| {});
    });
    let dump = tree.dump();
    let expected = border_each([0.0, 0.0, 0.0, 1.0], [GUIDE; 4]);
    assert!(dump.contains(&expected), "{dump}");
    assert!(!dump.contains("Border {"), "{dump}");
}

#[test]
fn fr60_border_side_maps_each_side_to_its_screen_side() {
    for (side, widths) in [
        (Side::Top, [2.0, 0.0, 0.0, 0.0]),
        (Side::End, [0.0, 2.0, 0.0, 0.0]),
        (Side::Bottom, [0.0, 0.0, 2.0, 0.0]),
        (Side::Start, [0.0, 0.0, 0.0, 2.0]),
    ] {
        let (_host, tree) = start(move || {
            Box()
                .modifier(Modifier.border_side(side, 2.0, GUIDE))
                .content(|| {});
        });
        let dump = tree.dump();
        assert!(
            dump.contains(&border_each(widths, [GUIDE; 4])),
            "{side:?}: {dump}"
        );
    }
}

#[test]
fn fr60_border_side_twice_draws_two_sides() {
    let (_host, tree) = start(|| {
        Box()
            .modifier(Modifier.border_side(Side::Bottom, 1.0, RULE).border_side(
                Side::Start,
                3.0,
                GUIDE,
            ))
            .content(|| {});
    });
    let dump = tree.dump();
    let expected = border_each([0.0, 0.0, 1.0, 3.0], [RULE, RULE, RULE, GUIDE]);
    assert!(dump.contains(&expected), "{dump}");
}

#[test]
fn fr60_border_each_writes_each_side_in_css_order() {
    let red = Paint::Literal(Color::argb(0xffff_0000));
    let (_host, tree) = start(move || {
        Box()
            .modifier(Modifier.border_each((1.0, red), (0.0, RULE), (2.0, GUIDE), (4.0, RULE)))
            .content(|| {});
    });
    let dump = tree.dump();
    let expected = border_each([1.0, 0.0, 2.0, 4.0], [red, RULE, GUIDE, RULE]);
    assert!(dump.contains(&expected), "{dump}");
}

#[test]
fn fr60_border_each_with_four_equal_sides_is_one_border() {
    let (_host, tree) = start(|| {
        Box()
            .modifier(Modifier.border_each((1.0, RULE), (1.0, RULE), (1.0, RULE), (1.0, RULE)))
            .content(|| {});
    });
    let dump = tree.dump();
    assert!(
        dump.contains("Border { width: 1.0, paint: Role(Outline) }"),
        "{dump}"
    );
    assert!(!dump.contains("BorderEach"), "{dump}");
}

#[test]
fn fr60_border_side_and_border_take_each_others_place() {
    let one_side = mutable_state_of(true);
    let state = one_side;
    let (mut host, mut tree) = start(move || {
        let modifier = if state.get() {
            Modifier.border_side(Side::Start, 1.0, GUIDE)
        } else {
            Modifier.border(2.0, RULE)
        };
        Box().modifier(modifier).content(|| {});
    });
    assert!(tree.dump().contains("BorderEach"), "{}", tree.dump());

    one_side.set(false);
    tree.apply(host.render_frame(0).expect("the frame encodes"));
    let dump = tree.dump();
    assert!(!dump.contains("BorderEach"), "{dump}");
    assert!(
        dump.contains("Border { width: 2.0, paint: Role(Outline) }"),
        "{dump}"
    );

    one_side.set(true);
    tree.apply(host.render_frame(0).expect("the frame encodes"));
    let dump = tree.dump();
    assert!(
        dump.contains(&border_each([0.0, 0.0, 0.0, 1.0], [GUIDE; 4])),
        "{dump}"
    );
    assert!(!dump.contains("Border {"), "{dump}");
}

#[test]
fn fr60_removing_border_side_clears_the_border() {
    let bordered = mutable_state_of(true);
    let state = bordered;
    let (mut host, mut tree) = start(move || {
        let modifier = if state.get() {
            Modifier.border_side(Side::Top, 1.0, GUIDE)
        } else {
            Modifier
        };
        Box().modifier(modifier).content(|| {});
    });
    assert!(tree.dump().contains("BorderEach"), "{}", tree.dump());
    bordered.set(false);
    tree.apply(host.render_frame(0).expect("the frame encodes"));
    assert!(!tree.dump().contains("Border"), "{}", tree.dump());
}
