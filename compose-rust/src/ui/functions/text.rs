//! Text and text input.

use super::{set, set_some};
use crate::font_features::FontFeature;
use crate::fonts::FontRefs;
use crate::rich_text::{RichDocument, TextFormat};
use crate::schema::{OverflowWrap, Paint, TextAlign, TextOverflow, TypeRole, WordBreak};
use crate::spans::TextSpans;
use crate::ui::{FocusHandle, Modifier, RichTextState, TextFieldHandle};
use crate::{KeyEvent, MotionFinished, PointerEvent, PointerKinds};
use compose_rust_macros::Composable;

/// Text. Compose's `Text`.
///
/// `type_role` alone takes the design system's size, weight, line height and letter
/// spacing; each other style parameter overrides one axis of it.
///
/// `spans` are runs of different treatment inside the text: a bold phrase, a link, some
/// code. `line_height` is in sp, and NaN (normal) uses the font's own metrics; left unset
/// it falls back to the type role's. `letter_spacing` is extra space between letters in
/// sp: 0 draws none, and NaN, like leaving it unset, takes the type role's.
///
/// `font` is the fonts to try, in order. Only text with `TypeRole::None` takes its own
/// fonts; text with a role is set in the theme's font for that role.
/// `font_feature_settings` are OpenType features of the font the text is set in, such as
/// `FontFeature::TABULAR_NUMS` for digits of one width. They are taken with or without a
/// type role, a tag given twice takes the later value, and at most 16 are taken.
///
/// `word_break` is where a line may break inside a word (CSS `word-break`), and
/// `overflow_wrap` whether a token too long for its line, such as a path or a URL, may
/// break inside itself (CSS `overflow-wrap`).
///
/// `on_pointer` subscribes the text to the kinds of pointer input it names. Text without
/// it has no pointer input at all, and costs nothing.
#[Composable(widget)]
pub fn Text(
    text: impl Into<String>,
    modifier: Modifier,
    #[default(::compose_rust::spans::TextSpans::default())] spans: TextSpans,
    #[default(None::<::compose_rust::schema::TypeRole>)] type_role: impl Into<Option<TypeRole>>,
    #[default(None::<f32>)] font_size: impl Into<Option<f32>>,
    #[default(None::<u16>)] font_weight: impl Into<Option<u16>>,
    #[default(None::<f32>)] line_height: impl Into<Option<f32>>,
    #[default(None::<f32>)] letter_spacing: impl Into<Option<f32>>,
    #[default(None::<::compose_rust::schema::Paint>)] color: impl Into<Option<Paint>>,
    #[default(None::<::compose_rust::schema::TextAlign>)] text_align: impl Into<Option<TextAlign>>,
    #[default(None::<u32>)] max_lines: impl Into<Option<u32>>,
    #[default(None::<::compose_rust::schema::TextOverflow>)] overflow: impl Into<Option<TextOverflow>>,
    #[default(::compose_rust::fonts::FontRefs::default())] font: FontRefs,
    #[default(&[])] font_feature_settings: &[FontFeature],
    #[default(None::<::compose_rust::schema::WordBreak>)] word_break: impl Into<Option<WordBreak>>,
    #[default(None::<::compose_rust::schema::OverflowWrap>)] overflow_wrap: impl Into<
        Option<OverflowWrap>,
    >,
    #[default(None::<(::compose_rust::PointerKinds, fn(::compose_rust::PointerEvent))>)]
    on_pointer: Option<(PointerKinds, impl FnMut(PointerEvent) + 'static)>,
    #[default(None::<fn(::compose_rust::MotionFinished)>)] on_motion_finished: Option<
        impl FnMut(MotionFinished) + 'static,
    >,
) {
    let mut scope = crate::ui::widgets::Text(text)
        .modifier(modifier)
        .spans(spans)
        .font(font)
        .font_feature_settings(font_feature_settings);
    set!(
        scope;
        type_role,
        font_size,
        font_weight,
        line_height,
        letter_spacing,
        color,
        text_align,
        max_lines,
        overflow,
        word_break,
        overflow_wrap,
    );
    if let Some((kinds, handler)) = on_pointer {
        scope = scope.on_pointer(kinds, handler);
    }
    set_some!(scope; on_motion_finished);
    drop(scope);
}

/// An uncontrolled text field. Compose's `TextField`, with the value kept where the
/// platform's text input keeps it: the Renderer reports each change through
/// `on_value_change` and the Host never writes the value back while the user types.
///
/// `text` is the text the field starts with. Only the start: a later value changes
/// nothing on the field that is already there. To show other text, such as another
/// document, give the field a new identity with `key`, and the new field starts with this
/// text.
///
/// The style parameters are `Text`'s, with `placeholder_color` and `caret_color` for the
/// two things only a field draws. `on_submit` hears Enter in a single-line field, and
/// `on_composition_change` hears an input method begin or end an active composition.
/// `on_key_down` sees each key before the field acts on it; calling `consume` on the event
/// keeps the field from acting. `focus_handle` binds a handle whose `request()` puts
/// keyboard focus here.
///
/// The field is uncontrolled: `text` is the text it starts with, and a different `text` on a
/// later run does not change a field already on screen. `text_handle` binds a
/// [`TextFieldHandle`] whose `set_text` gives the live field a new text, the empty one
/// included.
///
/// `bare` draws the field without the design system's frame: no fill, line, underline,
/// corner clip, inner padding, minimum height or focus line. It is for a field inside a
/// box that already draws its own frame. `read_only` keeps the field focusable and
/// selectable, copying included, while rejecting edits and announcing it as read-only.
///
/// `rich` binds a [`RichTextState`](crate::ui::RichTextState) and turns on editable
/// formatting: the Renderer keeps the character and paragraph formatting of the text,
/// `state.format(..)` formats the selection, and `on_format` and `on_rich_change` hear the
/// formatting at the selection and the document. Giving either handler turns formatting on
/// too. A field with none of the three is the plain field it always was.
#[Composable(widget)]
pub fn TextField<'a>(
    text: impl Into<String>,
    on_value_change: impl FnMut(String) + 'static,
    modifier: Modifier,
    #[default("")] placeholder: impl Into<String>,
    #[default(true)] enabled: bool,
    #[default(false)] multiline: bool,
    #[default(None::<::compose_rust::schema::TypeRole>)] type_role: impl Into<Option<TypeRole>>,
    #[default(None::<f32>)] font_size: impl Into<Option<f32>>,
    #[default(None::<u16>)] font_weight: impl Into<Option<u16>>,
    #[default(None::<f32>)] line_height: impl Into<Option<f32>>,
    #[default(None::<f32>)] letter_spacing: impl Into<Option<f32>>,
    #[default(None::<::compose_rust::schema::Paint>)] color: impl Into<Option<Paint>>,
    #[default(None::<::compose_rust::schema::Paint>)] placeholder_color: impl Into<Option<Paint>>,
    #[default(None::<::compose_rust::schema::Paint>)] caret_color: impl Into<Option<Paint>>,
    #[default(::compose_rust::fonts::FontRefs::default())] font: FontRefs,
    #[default(&[])] font_feature_settings: &'a [FontFeature],
    #[default(None::<::compose_rust::schema::WordBreak>)] word_break: impl Into<Option<WordBreak>>,
    #[default(None::<::compose_rust::schema::OverflowWrap>)] overflow_wrap: impl Into<
        Option<OverflowWrap>,
    >,
    #[default(None::<fn(String)>)] on_submit: Option<impl FnMut(String) + 'static>,
    #[default(None::<fn()>)] on_focus_gained: Option<impl FnMut() + 'static>,
    #[default(None::<fn()>)] on_focus_lost: Option<impl FnMut() + 'static>,
    #[default(None::<fn(::compose_rust::KeyEvent)>)] on_key_down: Option<
        impl FnMut(KeyEvent) + 'static,
    >,
    #[default(None::<fn(::compose_rust::KeyEvent)>)] on_key_up: Option<
        impl FnMut(KeyEvent) + 'static,
    >,
    #[default(None::<fn(bool)>)] on_composition_change: Option<impl FnMut(bool) + 'static>,
    #[default(None::<&::compose_rust::ui::FocusHandle>)] focus_handle: impl Into<
        Option<&'a FocusHandle>,
    >,
    #[default(None::<&::compose_rust::ui::TextFieldHandle>)] text_handle: impl Into<
        Option<&'a TextFieldHandle>,
    >,
    #[default(None::<fn(::compose_rust::MotionFinished)>)] on_motion_finished: Option<
        impl FnMut(MotionFinished) + 'static,
    >,
    #[default(false)] bare: bool,
    #[default(false)] read_only: bool,
    #[default(None::<&::compose_rust::ui::RichTextState>)] rich: impl Into<Option<&'a RichTextState>>,
    #[default(None::<fn(::compose_rust::TextFormat)>)] on_format: Option<
        impl FnMut(TextFormat) + 'static,
    >,
    #[default(None::<fn(::compose_rust::RichDocument)>)] on_rich_change: Option<
        impl FnMut(RichDocument) + 'static,
    >,
) {
    let mut scope = crate::ui::widgets::TextField()
        .modifier(modifier)
        .text(text)
        .placeholder(placeholder)
        .enabled(enabled)
        .multiline(multiline)
        .font(font)
        .font_feature_settings(font_feature_settings)
        .on_value_change(on_value_change)
        .read_only(read_only);
    set!(
        scope;
        type_role,
        font_size,
        font_weight,
        line_height,
        letter_spacing,
        color,
        placeholder_color,
        caret_color,
        word_break,
        overflow_wrap,
        focus_handle,
        text_handle,
    );
    set_some!(
        scope;
        on_submit,
        on_focus_gained,
        on_focus_lost,
        on_key_down,
        on_key_up,
        on_composition_change,
        on_motion_finished,
        on_format,
        on_rich_change,
    );
    if let Some(state) = rich.into() {
        scope = scope.rich(state);
    }
    if bare {
        scope = scope.bare();
    }
    drop(scope);
}
