//! A Button or Chip label can ask for its lines and its overflow, and says nothing otherwise.

mod support;

use compose_rust::ComposeHost;
use compose_rust::schema::TextOverflow;
use compose_rust::ui::*;
use support::Tree;

fn dump(content: impl Fn() + Send + Sync + 'static) -> String {
    let mut host = ComposeHost::with_content(content);
    let mut tree = Tree::default();
    tree.apply(host.rebuild().expect("the first batch encodes"));
    tree.dump()
}

fn line_of<'a>(dump: &'a str, widget: &str) -> &'a str {
    dump.lines()
        .find(|line| line.trim_start().starts_with(widget))
        .unwrap_or_else(|| panic!("no {widget} in\n{dump}"))
}

#[test]
fn fr_15_a_button_writes_the_lines_and_overflow_it_was_given() {
    let tree = dump(|| {
        Button("보내기").max_lines(2).overflow(TextOverflow::Clip);
    });
    let line = line_of(&tree, "Button");
    assert!(line.contains("MaxLines=") && line.contains("(2)"), "{line}");
    assert!(line.contains("Overflow="), "{line}");
}

#[test]
fn fr_15_a_chip_writes_the_lines_and_overflow_it_was_given() {
    let tree = dump(|| {
        Chip("안 읽음")
            .max_lines(3)
            .overflow(TextOverflow::Ellipsis);
    });
    let line = line_of(&tree, "Chip");
    assert!(line.contains("MaxLines=") && line.contains("(3)"), "{line}");
    assert!(line.contains("Overflow="), "{line}");
}

#[test]
fn fr_15_a_button_and_a_chip_that_ask_for_nothing_write_nothing() {
    let tree = dump(|| {
        Button("보내기");
        Chip("안 읽음");
    });
    assert!(!tree.contains("MaxLines"), "{tree}");
    assert!(!tree.contains("Overflow"), "{tree}");
}
