use dioxus_compose::codegen::{
    generate_event_vector, generate_kotlin, generate_mutation_vector, generate_vector_description,
};
use dioxus_compose::protocol::{HostEvent, decode_event, encode_event};
use dioxus_compose::tokens::DESIGN_TOKENS;
use dioxus_compose::{EventPayload, Key};

#[test]
fn fr7_generated_kotlin_matches_schema() {
    let generated = generate_kotlin();
    assert!(generated.contains("LinearProgressIndicator"));
    assert!(generated.contains("Progress"));
    assert!(generated.contains("100 -> WidgetKind.LinearProgressIndicator"));
    assert!(generated.contains("27 -> PropertyKind.Progress"));
    assert!(generated.contains("else -> throw ProtocolException(\"unknown widget tag $tag\""));
    assert!(generated.contains("else -> throw ProtocolException(\"unknown property tag $tag\""));
    assert!(generated.contains("enum class Key { Enter }"));
    assert!(generated.contains(
        "data class KeyDown(override val nodeId: Int, override val handlerId: Long, val key: Key, val shiftKey: Boolean, val ctrlKey: Boolean, val altKey: Boolean, val metaKey: Boolean) : HostEvent"
    ));
    assert!(generated.contains("is HostEvent.KeyDown -> 20"));
    for (field, mask) in [
        ("shiftKey", "0x01"),
        ("ctrlKey", "0x02"),
        ("altKey", "0x04"),
        ("metaKey", "0x08"),
    ] {
        assert!(generated.contains(&format!(
            "if (event.{field}) modifiers = modifiers or {mask}"
        )));
    }
    assert_eq!(
        include_str!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../dioxus-compose-renderer/desktop/src/protocol/Protocol.gen.kt"
        )),
        generated,
        "generated Kotlin is stale; run `cargo run -p dioxus-compose --bin codegen`",
    );
}

/// The role vocabulary and the token tables are generated, so a fourth
/// design system is one `DesignSystem` variant plus one Renderer rule implementation.
#[test]
fn fr14_generated_kotlin_carries_the_roles_and_token_tables() {
    let generated = generate_kotlin();
    for role in [
        "enum class ColorRole { Primary, OnPrimary, Secondary, OnSecondary, Surface, OnSurface, SurfaceVariant, OnSurfaceVariant, Background, OnBackground, Outline, OutlineVariant, Error, OnError, SurfaceContainer, Tertiary, OnTertiary, PrimaryContainer, OnPrimaryContainer, SecondaryContainer, OnSecondaryContainer, TertiaryContainer, OnTertiaryContainer }",
        "enum class TypeRole { Display, Headline, Title, Subtitle, Body, BodyStrong, Label, Caption, Mono }",
        "enum class ShapeRole { None, ExtraSmall, Small, Medium, Large, Full }",
        "enum class SpaceRole { None, Xs, Sm, Md, Lg, Xl, Xxl }",
        "enum class ButtonVariant { Filled, Tonal, Outlined, Text, Operator }",
        "enum class DesignSystem { Material3, Cupertino, Fluent, Gnome, Breeze, Deepin, LiquidGlass }",
        "enum class ColorScheme { Light, Dark, FollowSystem }",
    ] {
        assert!(generated.contains(role), "missing {role}");
    }
    assert!(generated.contains("data class SetTheme(val theme: Theme) : Mutation"));
    for table in DESIGN_TOKENS {
        assert!(generated.contains(&format!("DesignSystem.{:?},", table.system)));
        assert!(generated.contains(table.reference));
    }
    assert!(generated.contains("fun of(system: DesignSystem): DesignTokenTable"));
}

#[test]
fn fr7_generated_vectors_match_schema() {
    assert_eq!(
        include_bytes!("vectors/mutations.bin").as_slice(),
        generate_mutation_vector().unwrap(),
        "mutation vector is stale; run `cargo run -p dioxus-compose --bin codegen`",
    );
    assert_eq!(
        include_bytes!("vectors/events.bin").as_slice(),
        generate_event_vector().unwrap(),
        "event vector is stale; run `cargo run -p dioxus-compose --bin codegen`",
    );
    assert_eq!(
        include_str!("vectors/vectors.json"),
        generate_vector_description(),
        "vector description is stale; run `cargo run -p dioxus-compose --bin codegen`",
    );
}

#[test]
fn fr12_keydown_roundtrips_through_vectors() {
    let bytes = include_bytes!("vectors/events.bin");
    for (offset, length) in [(0, 16), (16, 30), (46, 28), (74, 16), (90, 35), (125, 20)] {
        decode_event(&bytes[offset..offset + length]).unwrap();
    }

    let key_down = &bytes[125..145];
    assert_eq!(&key_down[0..4], &[6, 0, 20, 0]);
    assert_eq!(key_down[18], 0x0f);

    let expected = HostEvent {
        node_id: 9,
        handler_id: 15,
        payload: EventPayload::KeyDown {
            key: Key::Enter,
            shift_key: true,
            ctrl_key: true,
            alt_key: true,
            meta_key: true,
        },
    };
    assert_eq!(decode_event(key_down).unwrap(), expected);

    let mut encoded = Vec::new();
    encode_event(&expected, &mut encoded).unwrap();
    assert_eq!(encoded, key_down);
}

#[test]
fn fr8_range_requested_roundtrips_through_vectors() {
    let bytes = include_bytes!("vectors/events.bin");
    let range_requested = &bytes[145..169];
    assert_eq!(&range_requested[0..4], &[7, 0, 24, 0]);

    let expected = HostEvent {
        node_id: 10,
        handler_id: 16,
        payload: EventPayload::RangeRequested {
            start: 100,
            count: 20,
        },
    };
    assert_eq!(decode_event(range_requested).unwrap(), expected);

    let mut encoded = Vec::new();
    encode_event(&expected, &mut encoded).unwrap();
    assert_eq!(encoded, range_requested);
}
