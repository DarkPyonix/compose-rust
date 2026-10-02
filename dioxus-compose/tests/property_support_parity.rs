//! What each widget may carry, as the Host declares it and as the Renderer enforces it.
//!
//! Two copies of the same fact. The Host's `element!` calls say which attributes a widget
//! accepts, and `NodeTable.supportsProperty` in the Renderer decides which ones it will
//! keep. Nothing compared them, so adding an attribute on one side and forgetting the
//! other produced a property that encoded, crossed the boundary, and was thrown away on
//! arrival with a protocol error nobody was reading.
//!
//! That is not hypothetical. `NavigationItem` was given a colour, the Host sent it, and
//! the table refused it because the colour rule named Text, Button, TextField and Icon.
//! The sample drew in the design system's accent and looked exactly like a sample that
//! had never been changed, which is the worst way for a mistake to present itself.
//!
//! This reads both files as text, which is blunt, and it is the only thing that can see
//! both sides at once.

use std::collections::BTreeSet;

const HOST: &str = include_str!(concat!(env!("CARGO_MANIFEST_DIR"), "/src/lib.rs"));
const RENDERER: &str = include_str!(concat!(
    env!("CARGO_MANIFEST_DIR"),
    "/../dioxus-compose-renderer/desktop/src/renderer/NodeTable.kt"
));

/// The widget name and attribute list of every `element!` call.
///
/// Panics on a shape it cannot read rather than returning nothing: an `element!` written
/// some other way would silently drop that widget out of the comparison, and a comparison
/// of nothing passes.
fn declared() -> Vec<(String, BTreeSet<String>)> {
    let mut found = Vec::new();
    let mut rest = HOST;
    while let Some(start) = rest.find("element!(") {
        rest = &rest[start + "element!(".len()..];
        let end = rest
            .find(");")
            .expect("an element! call that is never closed");
        let body = &rest[..end];
        rest = &rest[end..];

        let name = body
            .split('"')
            .nth(1)
            .unwrap_or_else(|| panic!("element! with no widget name: {body}"))
            .to_string();
        let open = body.find('[').unwrap_or_else(|| {
            panic!("element! for {name} has no attribute list, not even an empty one")
        });
        let close = body[open..]
            .find(']')
            .unwrap_or_else(|| panic!("element! for {name} never closes its attribute list"))
            + open;
        let attributes = body[open + 1..close]
            .split(',')
            .map(str::trim)
            .filter(|attribute| !attribute.is_empty())
            .map(str::to_string)
            .collect();
        found.push((name, attributes));
    }
    assert!(
        found.len() > 20,
        "only {} element! calls were read, so the shape this comparison looks for has \
         changed and most of the vocabulary is no longer being checked",
        found.len()
    );
    found
}

/// `attribute_name` as the Renderer spells the same property.
fn property_of(attribute: &str) -> Option<&'static str> {
    Some(match attribute {
        "text" => "Text",
        "placeholder" => "Placeholder",
        "enabled" => "Enabled",
        "multiline" => "Multiline",
        "item_count" => "ItemCount",
        "item_key" => "ItemKey",
        "type_role" => "TypeRole",
        "font_size" => "FontSize",
        "font_weight" => "FontWeight",
        "line_height" => "LineHeight",
        "letter_spacing" => "LetterSpacing",
        "color" => "Color",
        "text_align" => "TextAlign",
        "max_lines" => "MaxLines",
        "overflow" => "Overflow",
        "arrangement" => "Arrangement",
        "spacing" => "Spacing",
        "space_role" => "SpaceRole",
        "alignment" => "Alignment",
        "variant" => "Variant",
        "asset" => "Asset",
        "checked" => "Checked",
        "steps" => "Steps",
        "determinate" => "Determinate",
        "circular" => "Circular",
        "icon" => "Icon",
        // Everything else is either a Modifier rather than a property, or a property this
        // comparison has not been taught. Returning None skips it rather than failing,
        // because a Modifier is not subject to the rule being checked here.
        _ => return None,
    })
}

/// What an arm of `supportsProperty` says, in the shape it says it.
///
/// An arm either lists the widgets that may carry a property or the ones that may not,
/// and the two read the same to a search for `WidgetKind.`.
enum Arm {
    Allowed(BTreeSet<String>),
    Denied(BTreeSet<String>),
}

use Arm::{Allowed, Denied};

/// Every `WidgetKind.Something` in a stretch of Kotlin.
fn collect_widgets(text: &str) -> BTreeSet<String> {
    let mut widgets = BTreeSet::new();
    let mut scan = text;
    while let Some(start) = scan.find("WidgetKind.") {
        scan = &scan[start + "WidgetKind.".len()..];
        widgets.insert(
            scan.chars()
                .take_while(|character| character.is_alphanumeric())
                .collect(),
        );
    }
    widgets
}

/// Every widget the Renderer will keep this property on.
///
/// Read from the `when (property)` arms of `supportsProperty`: each arm lists the
/// properties it governs and then the widgets it allows them on.
fn allowed_widgets(property: &str) -> Option<Arm> {
    let body = RENDERER
        .split("internal fun supportsProperty")
        .nth(1)
        .expect("NodeTable.kt no longer declares supportsProperty");
    // Three shapes, because Kotlin's `when` writes an arm three ways: one of several
    // properties in a list, the only property with its arrow on the next line, and the
    // only property with its arrow on the same line. Reading only the first two found
    // nothing for Text, which is written the third way, and the comparison passed while
    // TopAppBar was missing from that arm.
    let at = ["{property},", "{property}\n", "{property} ->"]
        .iter()
        .find_map(|shape| {
            let needle = shape.replace("{property}", &format!("PropertyKind.{property}"));
            body.find(&needle)
        })?;
    // The arm runs from the property to the `->` that answers it, and the answer runs to
    // the blank line that ends the arm.
    let arrow = body[at..].find("->")? + at;
    // An arm ends at the blank line after it, or at the end of its own line where the
    // next line starts another arm. Reading to the blank line regardless swallowed the
    // two arms below Placeholder and reported their widgets as its own.
    let line_end = body[arrow..]
        .find('\n')
        .map(|offset| offset + arrow)
        .unwrap_or(body.len());
    let next_arm_starts_on_the_next_line = body[line_end..]
        .lines()
        .nth(1)
        .is_some_and(|line| line.trim_start().starts_with("PropertyKind."));
    let answer_end = if next_arm_starts_on_the_next_line {
        line_end
    } else {
        body[arrow..]
            .find("\n\n")
            .map(|offset| offset + arrow)
            .unwrap_or(body.len())
    };
    let answer = &body[arrow..answer_end];
    // An arm written as a denial lists the widgets it refuses, not the ones it allows,
    // so reading it as a list of allowed widgets inverts the answer. `Enabled` is
    // written that way, and the first version of this reported that a Button may not
    // carry it while a Spacer may, which is exactly backwards.
    if answer.contains("!=") {
        return Some(Denied(collect_widgets(answer)));
    }
    Some(Allowed(collect_widgets(answer)))
}

/// A property the Host offers on a widget is a property the Renderer keeps there.
///
/// The other direction is not checked. The Renderer is allowed to accept a property no
/// widget currently offers, because the schema is shared with hosts that are not this
/// crate; what cannot happen is the Host sending something that is thrown away.
#[test]
fn fr15_every_attribute_the_host_offers_is_one_the_renderer_keeps() {
    let mut refused = Vec::new();
    for (widget, attributes) in declared() {
        for attribute in &attributes {
            let Some(property) = property_of(attribute) else {
                continue;
            };
            let Some(arm) = allowed_widgets(property) else {
                continue;
            };
            let (kept, widgets) = match &arm {
                // An arm that names no widget at all answers `true` for every widget.
                Allowed(widgets) => (widgets.is_empty() || widgets.contains(&widget), widgets),
                Denied(widgets) => (!widgets.contains(&widget), widgets),
            };
            if kept {
                continue;
            }
            let rule = match arm {
                Allowed(_) => format!("keeps {property} only on {widgets:?}"),
                Denied(_) => format!("refuses {property} on {widgets:?}"),
            };
            refused.push(format!(
                "{widget} offers `{attribute}`, and the Renderer {rule}"
            ));
        }
    }
    assert!(
        refused.is_empty(),
        "these properties encode, cross the boundary and are then discarded on arrival, \
         so the widget draws as though the application had never set them:\n  {}",
        refused.join("\n  ")
    );
}
