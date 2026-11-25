//! Sample icon roles keep their wire tags and work on icon-only buttons.

mod support;

use compose_rust::ui::*;
use compose_rust::{ComposeHost, IconRole};
use support::Tree;

#[test]
fn fr16_sample_icon_roles_have_stable_tags_and_reach_buttons() {
    let roles = [
        IconRole::Lock,
        IconRole::Favorite,
        IconRole::Book,
        IconRole::Play,
        IconRole::Shuffle,
        IconRole::ExpandMore,
        IconRole::Person,
        IconRole::Clock,
        IconRole::Tune,
        IconRole::Chat,
        IconRole::Star,
        IconRole::Bag,
        IconRole::Bell,
        IconRole::Share,
        IconRole::Language,
        IconRole::CompactOverlay,
        IconRole::Calculator,
        IconRole::Refresh,
        IconRole::Delete,
    ];
    for (index, role) in roles.into_iter().enumerate() {
        let tag = u16::try_from(index + 22).expect("tag fits");
        assert_eq!(u16::from(role), tag);
        assert_eq!(IconRole::try_from(tag), Ok(role));
        let mut host = ComposeHost::with_content(move || {
            Button!("", || {}, icon = role, accessible_name = "sample action");
        });
        let mut tree = Tree::default();
        tree.apply(host.rebuild().expect("encode icon button"));
        let dump = tree.dump();
        assert!(dump.contains(&format!("Icon=Integer({tag})")), "{dump}");
        assert!(dump.contains("sample action"), "{dump}");
    }
    assert!(IconRole::try_from(u16::MAX).is_err());
}
