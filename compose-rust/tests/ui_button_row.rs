//! A Button can align and size its label, and can hold children in place of the label.

mod support;

use compose_rust::ComposeHost;
use compose_rust::schema::TextAlign;
use compose_rust::ui::*;
use support::Tree;

fn dump(content: impl Fn() + Send + Sync + 'static) -> String {
    let mut host = ComposeHost::with_content(content);
    let mut tree = Tree::default();
    tree.apply(host.rebuild().expect("the first batch encodes"));
    tree.dump()
}

#[test]
fn fr66_a_button_that_asks_for_nothing_sends_no_alignment_or_size() {
    let tree = dump(|| {
        Button("변경 사항");
    });
    assert!(!tree.contains("TextAlign"), "{tree}");
    assert!(!tree.contains("FontSize"), "{tree}");
}

#[test]
fn fr66_a_button_writes_the_alignment_and_size_it_was_given() {
    let tree = dump(|| {
        Button("변경 사항")
            .text_align(TextAlign::Start)
            .font_size(13.0);
    });
    assert!(tree.contains("TextAlign=Integer(1)"), "{tree}");
    assert!(tree.contains("FontSize=Float(13"), "{tree}");
}

#[test]
fn fr66_a_button_emits_its_children_under_itself() {
    let tree = dump(|| {
        Button("").content(|| {
            Text("Files");
            Spacer().modifier(Modifier.weight(1.0));
            Text("12");
        });
    });
    let lines: Vec<&str> = tree.lines().collect();
    let button = lines
        .iter()
        .position(|l| l.trim_start().starts_with("Button"))
        .unwrap_or_else(|| panic!("no Button in\n{tree}"));
    let indent = |l: &str| l.len() - l.trim_start().len();
    let children: Vec<&&str> = lines[button + 1..]
        .iter()
        .take_while(|l| indent(l) > indent(lines[button]))
        .collect();
    assert_eq!(children.len(), 3, "{tree}");
    assert!(children[0].contains("Text=\"Files\""), "{tree}");
    assert!(children[2].contains("Text=\"12\""), "{tree}");
}
