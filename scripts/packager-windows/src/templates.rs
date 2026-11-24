//! The packaging templates this packager fills: the Compose fork's, copied with a pin to
//! `scripts/packaging-templates/` (see its README). A template is filled by putting each
//! value, already escaped by the caller, where its `{{placeholder}}` is; the packager then
//! only adds to what the template rendered.

pub const APPX_MANIFEST: &str =
    include_str!("../../packaging-templates/templates/AppxManifest.xml.in");

/// `template` with every placeholder replaced by its value from `values`, in one pass.
///
/// The templates are compiled into this program, so a placeholder without a value is this
/// program being out of step with its pinned templates, and it stops here, naming it.
pub fn render(template: &str, values: &[(&str, &str)]) -> String {
    let mut out = String::with_capacity(template.len());
    let mut rest = template;
    while let Some(start) = rest.find("{{") {
        let Some(end) = rest[start + 2..].find("}}") else {
            break;
        };
        let key = &rest[start + 2..start + 2 + end];
        let Some((_, value)) = values.iter().find(|(k, _)| *k == key) else {
            panic!("the packaging template needs a value for {{{{{key}}}}}");
        };
        out.push_str(&rest[..start]);
        out.push_str(value);
        rest = &rest[start + 2 + end + 2..];
    }
    out.push_str(rest);
    out
}
