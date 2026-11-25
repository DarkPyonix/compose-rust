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
    // A Windows checkout can give the template CRLF line ends, and what the packager adds
    // afterwards finds its place by whole lines ending in "\n". The output has LF ends on
    // every system, as the pinned template does.
    let template = template.replace("\r\n", "\n");
    let mut out = String::with_capacity(template.len());
    let mut rest = template.as_str();
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fr22_6_11_a_template_with_crlf_line_ends_renders_as_with_lf() {
        let lf = "<a>\n  <b>{{x}}</b>\n</a>\n";
        let crlf = lf.replace('\n', "\r\n");
        assert_eq!(render(&crlf, &[("x", "1")]), render(lf, &[("x", "1")]));
        assert_eq!(render(&crlf, &[("x", "1")]), "<a>\n  <b>1</b>\n</a>\n");
    }
}
