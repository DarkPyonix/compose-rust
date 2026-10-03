use compose_rust::codegen::{
    generate_event_vector, generate_kotlin, generate_mutation_vector, generate_vector_description,
};
use compose_rust::protocol::{
    BatchEncoder, HostEvent, Mutation, PropertyValue, decode_batch, decode_event, encode_event,
};
use compose_rust::schema::{PROPERTY_SCHEMA, SCHEMA_DESCRIPTOR, WIDGET_SCHEMA};
use compose_rust::tokens::DESIGN_TOKENS;
use compose_rust::{EventPayload, Key, PropertyKind, WidgetKind};

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
        "generated Kotlin is stale; run `cargo run -p compose-rust --bin codegen`",
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
        "mutation vector is stale; run `cargo run -p compose-rust --bin codegen`",
    );
    assert_eq!(
        include_bytes!("vectors/events.bin").as_slice(),
        generate_event_vector().unwrap(),
        "event vector is stale; run `cargo run -p compose-rust --bin codegen`",
    );
    assert_eq!(
        include_str!("vectors/vectors.json"),
        generate_vector_description(),
        "vector description is stale; run `cargo run -p compose-rust --bin codegen`",
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

/// The Kotlin decoder both vectors are read with, as it is checked in.
const CHECKED_IN_KOTLIN: &str = include_str!(concat!(
    env!("CARGO_MANIFEST_DIR"),
    "/../dioxus-compose-renderer/desktop/src/protocol/Protocol.gen.kt"
));

/// A batch of `mutations`, encoded the way the Host encodes every frame.
fn encode_batch(mutations: &[Mutation<'_>]) -> Vec<u8> {
    let mut encoder = BatchEncoder::default();
    for mutation in mutations {
        encoder.encode(mutation).unwrap();
    }
    encoder.finish().unwrap().to_vec()
}

/// The checked-in vectors pin a sample of the vocabulary. This walks all of it: every
/// widget kind in the schema is encoded as a `Create`, its tag is where the Kotlin decoder
/// reads it, it decodes back to itself, and the checked-in Kotlin decoder maps the same
/// tag to the same name.
#[test]
fn fr7_every_widget_kind_in_the_schema_round_trips_through_the_protocol() {
    let mut failures = Vec::new();
    for (index, widget) in WIDGET_SCHEMA.iter().enumerate() {
        let Ok(kind) = WidgetKind::try_from(widget.tag) else {
            failures.push(format!(
                "{} has tag {} in the schema table, but WidgetKind does not decode that tag",
                widget.name, widget.tag
            ));
            continue;
        };
        if format!("{kind:?}") != widget.name {
            failures.push(format!(
                "tag {} is {} in the schema table but decodes as {kind:?}",
                widget.tag, widget.name
            ));
        }
        let node_id = index as u32 + 1;
        let bytes = encode_batch(&[Mutation::Create {
            node_id,
            widget: kind,
        }]);
        // The envelope is 12 bytes; a Create record is a 4 byte header, the node id, then
        // the widget tag.
        let wire_tag = u16::from_le_bytes([bytes[20], bytes[21]]);
        if wire_tag != widget.tag {
            failures.push(format!(
                "{} is written on the wire as tag {wire_tag}, but the schema gives it {}",
                widget.name, widget.tag
            ));
        }
        match decode_batch(&bytes) {
            Ok(decoded)
                if decoded
                    == [Mutation::Create {
                        node_id,
                        widget: kind,
                    }] => {}
            other => failures.push(format!(
                "{} did not decode back to itself: {other:?}",
                widget.name
            )),
        }
        let arm = format!("{} -> WidgetKind.{}", widget.tag, widget.name);
        if !CHECKED_IN_KOTLIN.contains(&arm) {
            failures.push(format!(
                "the checked-in Kotlin decoder has no `{arm}`, so the Renderer would reject \
                 or misread a {} the Host sends; regenerate it with `cargo run -p \
                 dioxus-compose --bin codegen`",
                widget.name
            ));
        }
    }
    assert!(failures.is_empty(), "{}", failures.join("\n"));
}

/// The same walk for properties, with every value kind a property can carry, because the
/// value kind and the property tag sit in the same record and a layout change to one moves
/// the other.
#[test]
fn fr7_every_property_kind_in_the_schema_round_trips_through_the_protocol() {
    let blob = [1_u8, 2, 3, 4, 5];
    let values = [
        PropertyValue::None,
        PropertyValue::String("한글 text"),
        PropertyValue::Bool(true),
        PropertyValue::Integer(-1_234_567_890_123),
        PropertyValue::Float(0.375),
        PropertyValue::Bytes(&blob),
    ];
    let mut failures = Vec::new();
    for (index, property) in PROPERTY_SCHEMA.iter().enumerate() {
        let Ok(kind) = PropertyKind::try_from(property.tag) else {
            failures.push(format!(
                "{} has tag {} in the schema table, but PropertyKind does not decode that tag",
                property.name, property.tag
            ));
            continue;
        };
        if format!("{kind:?}") != property.name {
            failures.push(format!(
                "tag {} is {} in the schema table but decodes as {kind:?}",
                property.tag, property.name
            ));
        }
        let node_id = index as u32 + 1;
        let mutations: Vec<Mutation<'_>> = values
            .iter()
            .map(|value| Mutation::SetProp {
                node_id,
                property: kind,
                value: value.clone(),
            })
            .collect();
        let bytes = encode_batch(&mutations);
        // Each SetProp record is 24 bytes: a 4 byte header, the node id, then the tag.
        for record in 0..mutations.len() {
            let at = 12 + record * 24 + 8;
            let wire_tag = u16::from_le_bytes([bytes[at], bytes[at + 1]]);
            if wire_tag != property.tag {
                failures.push(format!(
                    "{} is written on the wire as tag {wire_tag}, but the schema gives it {}",
                    property.name, property.tag
                ));
            }
        }
        match decode_batch(&bytes) {
            Ok(decoded) if decoded == mutations => {}
            other => failures.push(format!(
                "{} did not decode back to what was encoded: {other:?}",
                property.name
            )),
        }
        let arm = format!("{} -> PropertyKind.{}", property.tag, property.name);
        if !CHECKED_IN_KOTLIN.contains(&arm) {
            failures.push(format!(
                "the checked-in Kotlin decoder has no `{arm}`, so the Renderer would reject \
                 or misread a {} the Host sends; regenerate it with `cargo run -p \
                 dioxus-compose --bin codegen`",
                property.name
            ));
        }
    }
    assert!(failures.is_empty(), "{}", failures.join("\n"));
}

/// Every widget and property the checked-in mutation vector uses is one the schema table
/// still lists, under the same tag and name. This reads the bytes the Kotlin tests read.
#[test]
fn fr7_the_checked_in_mutation_vector_only_names_schema_kinds() {
    let bytes = include_bytes!("vectors/mutations.bin");
    let mutations = decode_batch(bytes).unwrap();
    let mut widgets = 0;
    let mut properties = 0;
    for mutation in &mutations {
        match mutation {
            Mutation::Create { widget, .. } => {
                widgets += 1;
                assert!(
                    WIDGET_SCHEMA
                        .iter()
                        .any(|entry| entry.tag == *widget as u16
                            && entry.name == format!("{widget:?}")),
                    "the checked-in vector creates {widget:?}, which the schema table does not \
                     list under tag {}",
                    *widget as u16
                );
            }
            Mutation::SetProp { property, .. } => {
                properties += 1;
                assert!(
                    PROPERTY_SCHEMA
                        .iter()
                        .any(|entry| entry.tag == *property as u16
                            && entry.name == format!("{property:?}")),
                    "the checked-in vector sets {property:?}, which the schema table does not \
                     list under tag {}",
                    *property as u16
                );
            }
            _ => {}
        }
    }
    assert!(
        widgets > 0 && properties > 0,
        "the checked-in vector should create at least one widget and set at least one \
         property; it created {widgets} and set {properties}"
    );
}

/// The names in the `key=a,b,c` section of a schema descriptor.
fn descriptor_list<'a>(descriptor: &'a str, key: &str) -> Vec<&'a str> {
    let prefix = format!("{key}=");
    descriptor
        .split(';')
        .find_map(|section| section.strip_prefix(prefix.as_str()))
        .unwrap_or_else(|| panic!("the schema descriptor has no `{prefix}` section"))
        .split(',')
        .collect()
}

/// Descriptor names are snake case and table names are Rust variants; they are the same
/// name when underscores and case are ignored, which is how the Host matches them too.
fn same_name(descriptor: &str, table: &str) -> bool {
    descriptor
        .bytes()
        .filter(|byte| *byte != b'_')
        .map(|byte| byte.to_ascii_lowercase())
        .eq(table.bytes().map(|byte| byte.to_ascii_lowercase()))
}

/// Extension widgets start at this tag, and extension properties have these tags. Both
/// are described in the extension descriptor, not the core one.
const FIRST_EXTENSION_WIDGET_TAG: u16 = 100;
const EXTENSION_PROPERTY_TAGS: &[u16] = &[27];

/// The descriptor is the schema's canonical text and is where the handshake hash starts.
/// A widget or property in the tables that the descriptor leaves out is one that a
/// generator reading the descriptor would not know exists.
#[test]
fn fr7_the_schema_descriptor_names_every_widget_and_property_in_order() {
    let widgets = descriptor_list(SCHEMA_DESCRIPTOR, "widgets");
    let core_widgets: Vec<&str> = WIDGET_SCHEMA
        .iter()
        .filter(|entry| entry.tag < FIRST_EXTENSION_WIDGET_TAG)
        .map(|entry| entry.name)
        .collect();
    let widgets_agree = widgets.len() == core_widgets.len()
        && widgets
            .iter()
            .zip(&core_widgets)
            .all(|(described, table)| same_name(described, table));
    assert!(
        widgets_agree,
        "the schema descriptor lists the widgets {widgets:?}, but the widget table, in tag \
         order, is {core_widgets:?}"
    );

    let properties = descriptor_list(SCHEMA_DESCRIPTOR, "properties");
    let core_properties: Vec<&str> = PROPERTY_SCHEMA
        .iter()
        .filter(|entry| !EXTENSION_PROPERTY_TAGS.contains(&entry.tag))
        .map(|entry| entry.name)
        .collect();
    let missing: Vec<&str> = core_properties
        .iter()
        .copied()
        .filter(|table| {
            !properties
                .iter()
                .any(|described| same_name(described, table))
        })
        .collect();
    let unknown: Vec<&str> = properties
        .iter()
        .copied()
        .filter(|described| {
            !core_properties
                .iter()
                .any(|table| same_name(described, table))
        })
        .collect();
    assert!(
        missing.is_empty() && unknown.is_empty(),
        "the schema descriptor and the property table disagree: the table has {missing:?} \
         that the descriptor does not name, and the descriptor names {unknown:?} that the \
         table does not have"
    );
    let in_order = properties
        .iter()
        .zip(&core_properties)
        .all(|(described, table)| same_name(described, table));
    assert!(
        in_order,
        "the schema descriptor lists the properties as {properties:?}, but the property \
         table, in tag order, is {core_properties:?}"
    );
}
