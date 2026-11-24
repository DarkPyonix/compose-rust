//! The packaging templates this packager fills: the Compose fork's, copied with a pin to
//! `scripts/packaging-templates/` (see its README).
//!
//! A template is the part of the file every application has. It is filled by putting each
//! value where its `{{placeholder}}` is, the value already escaped for the format by the
//! caller. The packager then only adds to what the template rendered: lines, keys or
//! elements the template does not have, or children inside an element it leaves empty.

use crate::flatpak::Json;

pub const DESKTOP: &str = include_str!("../../packaging-templates/templates/app.desktop.in");
pub const METAINFO: &str =
    include_str!("../../packaging-templates/templates/app.metainfo.xml.in");
pub const FLATPAK: &str =
    include_str!("../../packaging-templates/templates/flatpak-manifest.json.in");

/// The placeholders `template` names, in order of first appearance.
pub fn placeholders(template: &str) -> Vec<String> {
    let mut found = Vec::new();
    let mut rest = template;
    while let Some(start) = rest.find("{{") {
        let Some(end) = rest[start + 2..].find("}}") else {
            break;
        };
        let key = &rest[start + 2..start + 2 + end];
        if !found.iter().any(|k| k == key) {
            found.push(key.to_owned());
        }
        rest = &rest[start + 2 + end + 2..];
    }
    found
}

/// `template` with every placeholder replaced by its value from `values`.
///
/// The templates are compiled into this program, so a placeholder without a value is this
/// program being out of step with its pinned templates, and it stops here, naming it.
pub fn render(template: &str, values: &[(&str, &str)]) -> String {
    let missing: Vec<String> = placeholders(template)
        .into_iter()
        .filter(|key| !values.iter().any(|(k, _)| k == key))
        .collect();
    assert!(
        missing.is_empty(),
        "the packaging template needs a value for {}",
        missing
            .iter()
            .map(|k| format!("{{{{{k}}}}}"))
            .collect::<Vec<_>>()
            .join(", ")
    );
    // One pass over the template, so a value that happens to hold `{{...}}` stays as it is.
    let mut out = String::with_capacity(template.len());
    let mut rest = template;
    while let Some(start) = rest.find("{{") {
        let Some(end) = rest[start + 2..].find("}}") else {
            break;
        };
        let key = &rest[start + 2..start + 2 + end];
        out.push_str(&rest[..start]);
        let (_, value) = values.iter().find(|(k, _)| *k == key).expect("checked above");
        out.push_str(value);
        rest = &rest[start + 2 + end + 2..];
    }
    out.push_str(rest);
    out
}

/// A string as it goes inside a JSON string, without the quotes.
pub fn json_escape(value: &str) -> String {
    let quoted = Json::str(value).render(0);
    quoted[1..quoted.len() - 1].to_owned()
}

/// Parses the JSON a template holds: objects, arrays and strings (in key order). Numbers,
/// booleans and null are not in any template, so they are not read.
pub fn parse_json(text: &str) -> Result<Json, String> {
    let mut parser = Parser {
        chars: text.chars().collect(),
        at: 0,
    };
    let value = parser.value()?;
    parser.space();
    if parser.at != parser.chars.len() {
        return Err(format!("text after the JSON value at character {}", parser.at));
    }
    Ok(value)
}

struct Parser {
    chars: Vec<char>,
    at: usize,
}

impl Parser {
    fn space(&mut self) {
        while self.chars.get(self.at).is_some_and(|c| c.is_whitespace()) {
            self.at += 1;
        }
    }

    fn expect(&mut self, want: char) -> Result<(), String> {
        self.space();
        match self.chars.get(self.at) {
            Some(&c) if c == want => {
                self.at += 1;
                Ok(())
            }
            other => Err(format!("expected {want:?} at character {}, found {other:?}", self.at)),
        }
    }

    fn value(&mut self) -> Result<Json, String> {
        self.space();
        match self.chars.get(self.at) {
            Some('"') => self.string().map(Json::String),
            Some('[') => {
                self.at += 1;
                let mut items = Vec::new();
                self.space();
                if self.chars.get(self.at) == Some(&']') {
                    self.at += 1;
                    return Ok(Json::Array(items));
                }
                loop {
                    items.push(self.value()?);
                    self.space();
                    match self.chars.get(self.at) {
                        Some(',') => self.at += 1,
                        Some(']') => {
                            self.at += 1;
                            return Ok(Json::Array(items));
                        }
                        other => return Err(format!("expected ',' or ']' at character {}, found {other:?}", self.at)),
                    }
                }
            }
            Some('{') => {
                self.at += 1;
                let mut fields = Vec::new();
                self.space();
                if self.chars.get(self.at) == Some(&'}') {
                    self.at += 1;
                    return Ok(Json::Object(fields));
                }
                loop {
                    self.space();
                    let key = self.string()?;
                    self.expect(':')?;
                    fields.push((key, self.value()?));
                    self.space();
                    match self.chars.get(self.at) {
                        Some(',') => self.at += 1,
                        Some('}') => {
                            self.at += 1;
                            return Ok(Json::Object(fields));
                        }
                        other => return Err(format!("expected ',' or '}}' at character {}, found {other:?}", self.at)),
                    }
                }
            }
            other => Err(format!("a template holds objects, arrays and strings, found {other:?} at character {}", self.at)),
        }
    }

    fn string(&mut self) -> Result<String, String> {
        self.expect('"')?;
        let mut out = String::new();
        loop {
            let Some(&c) = self.chars.get(self.at) else {
                return Err("a string is not closed".into());
            };
            self.at += 1;
            match c {
                '"' => return Ok(out),
                '\\' => {
                    let Some(&e) = self.chars.get(self.at) else {
                        return Err("a string ends in a backslash".into());
                    };
                    self.at += 1;
                    match e {
                        '"' | '\\' | '/' => out.push(e),
                        'n' => out.push('\n'),
                        'r' => out.push('\r'),
                        't' => out.push('\t'),
                        'u' => {
                            let hex: String = self.chars.get(self.at..self.at + 4).unwrap_or(&[]).iter().collect();
                            let code = u32::from_str_radix(&hex, 16).map_err(|_| format!("bad \\u escape {hex:?}"))?;
                            out.push(char::from_u32(code).ok_or_else(|| format!("bad \\u escape {hex:?}"))?);
                            self.at += 4;
                        }
                        other => return Err(format!("unknown escape \\{other}")),
                    }
                }
                c => out.push(c),
            }
        }
    }
}

/// Whether `output` holds everything `template` has: every key of an object with a value
/// that holds the template's, every item of an array in the template's order (an item
/// matches the first later output item that holds it), and every string as it is. The
/// packager's additions are the rest. Returns the first thing missing.
pub fn json_holds(template: &Json, output: &Json, path: &str) -> Result<(), String> {
    match (template, output) {
        (Json::String(a), Json::String(b)) if a == b => Ok(()),
        (Json::Object(want), Json::Object(have)) => {
            for (key, value) in want {
                let Some((_, found)) = have.iter().find(|(k, _)| k == key) else {
                    return Err(format!("{path}.{key} is missing"));
                };
                json_holds(value, found, &format!("{path}.{key}"))?;
            }
            Ok(())
        }
        (Json::Array(want), Json::Array(have)) => {
            let mut from = 0;
            for (index, item) in want.iter().enumerate() {
                let Some(found) = (from..have.len()).find(|&i| json_holds(item, &have[i], "").is_ok()) else {
                    return Err(format!("{path}[{index}] ({}) is missing or out of order", item.render(0)));
                };
                from = found + 1;
            }
            Ok(())
        }
        _ => Err(format!("{path} is {}, and the template has {}", output.render(0), template.render(0))),
    }
}
