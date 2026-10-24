//! Text laid out by CSS, as rsx writes it: no rung of the type ladder, its own fonts, CSS
//! line breaking, tab stops, CSS pixels and `nowrap`.

use dioxus_compose_adapter::fonts::{FontRef, FontRefs};
use dioxus_compose_adapter::prelude::*;
use dioxus_compose_adapter::protocol::{Mutation, PropertyValue, decode_batch};
use dioxus_compose_adapter::spans::{TextSpan, TextSpans};
use dioxus_compose_adapter::{GenericFamily, Host, OverflowWrap, PropertyKind, WordBreak};

/// Every property record the first frame carries, by kind, with its value owned.
fn props_of(app: fn() -> Element) -> Vec<(PropertyKind, Vec<u8>, Option<i64>, Option<bool>)> {
    dioxus_compose_adapter::window::reset_window_size();
    let mut host = Host::new(app);
    let batch = host.rebuild().unwrap().to_vec();
    decode_batch(&batch)
        .unwrap()
        .into_iter()
        .filter_map(|mutation| match mutation {
            Mutation::SetProp {
                property, value, ..
            } => Some(match value {
                PropertyValue::Bytes(bytes) => (property, bytes.to_vec(), None, None),
                PropertyValue::Integer(value) => (property, Vec::new(), Some(value), None),
                PropertyValue::Bool(value) => (property, Vec::new(), None, Some(value)),
                _ => (property, Vec::new(), None, None),
            }),
            _ => None,
        })
        .collect()
}

fn css_text() -> Element {
    let font = FontRefs::new([
        FontRef::Asset(4),
        FontRef::System("JetBrains Mono".to_owned()),
        FontRef::Generic(GenericFamily::Monospace),
    ]);
    let spans = TextSpans::new_with_fonts([
        (
            TextSpan::new(0, 3),
            FontRefs::new([FontRef::Generic(GenericFamily::Serif)]),
        ),
        (TextSpan::new(4, 2).bold(), FontRefs::default()),
    ]);
    rsx! {
        Text {
            text: "pre\tcode",
            type_role: TypeRole::None,
            font,
            spans,
            word_break: WordBreak::KeepAll,
            overflow_wrap: OverflowWrap::Anywhere,
            tab_size: 4,
            absolute_size: true,
            soft_wrap: false,
        }
    }
}

fn plain_text() -> Element {
    rsx! {
        Text { text: "plain" }
    }
}

/// Text with no role sends its role as tag 0, which is a value rather than "not sent", and
/// every property CSS text needs travels on the Text that carries it.
#[test]
fn fr40_text_with_no_role_sends_its_own_properties() {
    let props = props_of(css_text);
    let find = |kind: PropertyKind| {
        props
            .iter()
            .find(|(property, ..)| *property == kind)
            .unwrap_or_else(|| panic!("{kind:?} did not travel: {props:?}"))
    };
    assert_eq!(find(PropertyKind::TypeRole).2, Some(0), "no role is tag 0");
    assert_eq!(
        find(PropertyKind::WordBreak).2,
        Some(WordBreak::KeepAll as i64)
    );
    assert_eq!(
        find(PropertyKind::OverflowWrap).2,
        Some(OverflowWrap::Anywhere as i64)
    );
    assert_eq!(find(PropertyKind::TabSize).2, Some(4));
    assert_eq!(find(PropertyKind::AbsoluteSize).2, Some(1));
    assert_eq!(find(PropertyKind::SoftWrap).3, Some(false));

    let font = FontRefs::from_bytes(find(PropertyKind::Font).1.clone());
    assert_eq!(
        font.refs(),
        vec![
            FontRef::Asset(4),
            FontRef::System("JetBrains Mono".to_owned()),
            FontRef::Generic(GenericFamily::Monospace),
        ]
    );
    let runs = TextSpans::from_bytes(find(PropertyKind::Spans).1.clone());
    assert_eq!(
        runs.spans().count(),
        2,
        "a run keeps its own 36 byte record"
    );
    let table = runs.with_font_table(find(PropertyKind::SpanFonts).1.clone());
    assert_eq!(
        table.span_fonts(),
        vec![(0, vec![FontRef::Generic(GenericFamily::Serif)])],
        "only the run that names a font has an entry"
    );
}

/// A Text that says none of it travels as it always did: no role, no font, nothing.
#[test]
fn fr40_text_that_names_nothing_sends_nothing_new() {
    let props = props_of(plain_text);
    let kinds: Vec<PropertyKind> = props.iter().map(|(kind, ..)| *kind).collect();
    assert_eq!(kinds, vec![PropertyKind::Text], "{props:?}");
}
