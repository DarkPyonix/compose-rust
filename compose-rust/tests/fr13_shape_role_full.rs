//! `ShapeRole::Full`: the corner role that makes a pill at any height.

mod support;

use compose_rust::ComposeHost;
use compose_rust::protocol::{BatchEncoder, Mutation, decode_batch};
use compose_rust::schema::{MODIFIER_SCHEMA, ROLE_ENUM_SCHEMA, SHAPE_ROLE_SCHEMA};
use compose_rust::tokens::DESIGN_TOKENS;
use compose_rust::ui::*;
use support::Tree;

fn start(content: impl Fn() + Send + Sync + 'static) -> (ComposeHost, Tree) {
    let mut host = ComposeHost::with_content(content);
    let mut tree = Tree::default();
    tree.apply(host.rebuild().expect("the first batch encodes"));
    (host, tree)
}

#[test]
fn fr13_shape_role_full_round_trips_at_its_tag() {
    assert_eq!(u16::from(ShapeRole::Full), 6);
    assert_eq!(ShapeRole::try_from(6), Ok(ShapeRole::Full));
    // The tag after the last one is no role, so a seventh cannot be read as `Full`.
    assert_eq!(ShapeRole::try_from(7), Err(()));

    let carrier = MODIFIER_SCHEMA
        .iter()
        .find(|variant| variant.name == "ShapeRole")
        .expect("the schema names the ShapeRole modifier");
    assert_eq!(carrier.tag, 13);

    let expected = [Mutation::SetModifier {
        node_id: 3,
        index: 5,
        modifier: compose_rust::Modifier::ShapeRole(ShapeRole::Full),
    }];
    let mut encoder = BatchEncoder::default();
    encoder.encode(&expected[0]).unwrap();
    let bytes = encoder.finish().unwrap().to_vec();
    assert_eq!(decode_batch(&bytes).unwrap(), expected);
}

#[test]
fn fr13_shape_role_schema_lists_full_last() {
    let roles = ROLE_ENUM_SCHEMA
        .iter()
        .find(|role| role.name == "ShapeRole")
        .expect("the schema names the ShapeRole enum");
    let listed: Vec<(&str, u16)> = roles
        .variants
        .iter()
        .map(|variant| (variant.name, variant.tag))
        .collect();
    assert_eq!(
        listed,
        [
            ("None", 1),
            ("ExtraSmall", 2),
            ("Small", 3),
            ("Medium", 4),
            ("Large", 5),
            ("Full", 6),
        ],
    );
    assert_eq!(
        SHAPE_ROLE_SCHEMA.last().map(|variant| variant.name),
        Some("Full"),
    );
}

#[test]
fn fr13_modifier_shape_role_builder_sends_full() {
    let (_host, tree) = start(|| {
        Box!(
            modifier = Modifier.height(40.0).shape_role(ShapeRole::Full),
            {
                Text!("pill");
            }
        );
        Box!({
            Text!("square");
        });
    });
    let dump = tree.dump();
    let boxes: Vec<&str> = dump
        .lines()
        .filter(|line| line.trim_start().starts_with("Box"))
        .collect();
    assert_eq!(boxes.len(), 2, "{dump}");
    assert!(boxes[0].contains("ShapeRole(Full)"), "{dump}");
    assert!(!boxes[1].contains("ShapeRole"), "{dump}");
}

/// The Renderer draws a pill for a radius of 1000 or more, so that is what every design
/// system's table has to say for `Full`: a smaller figure would be drawn as that many dp
/// and stop being a pill on a node taller than twice it.
#[test]
fn fr13_full_is_the_pill_radius_in_every_design_system() {
    for table in DESIGN_TOKENS {
        let full = table.shapes.last().expect("a design system has shape rows");
        assert_eq!(full.role, ShapeRole::Full, "{:?}", table.system);
        assert!(
            full.radius >= 1000.0,
            "{:?} gives Full a radius of {} dp, which the Renderer draws as a fixed corner",
            table.system,
            full.radius,
        );
    }
}
