//! What an application can say about its destinations in a compact window.
//!
//! The design system answers for every width unless the application chose otherwise, and
//! the choice covers a compact window only. The Host sends it once, as one property on the
//! `Navigation` node; which width it applies at is the Renderer's to work out, so the
//! screen still never reads the window's size.

use compose_rust::ComposeHost;
use compose_rust::protocol::{Mutation, PropertyValue, decode_batch};
use compose_rust::runtime::*;
use compose_rust::schema::{CompactNavigation, DesignSystem, PropertyKind, Theme, WidgetKind};
use compose_rust::ui::*;
use std::cell::Cell;

thread_local! {
    static CHOICE: Cell<CompactNavigation> = const { Cell::new(CompactNavigation::Default) };
}

#[Composable]
fn Destinations() {
    Navigation(0).compact(CHOICE.with(Cell::get)).content(|| {
        NavigationItem("Tasks").icon(IconRole::List);
        NavigationItem("Done").icon(IconRole::Check);
        Text("the screen");
    });
}

/// The `CompactNavigation` records the first frame wrote on its `Navigation` node.
fn compact_records(choice: CompactNavigation) -> Vec<i64> {
    CHOICE.with(|cell| cell.set(choice));
    let mut host = ComposeHost::with_theme(Destinations, Theme::unified(DesignSystem::Material3));
    let batch = host.rebuild().expect("the first frame encodes").to_vec();
    let mutations = decode_batch(&batch).expect("the first frame decodes");
    let navigation: Vec<u32> = mutations
        .iter()
        .filter_map(|mutation| match mutation {
            Mutation::Create {
                node_id,
                widget: WidgetKind::Navigation,
            } => Some(*node_id),
            _ => None,
        })
        .collect();
    assert_eq!(navigation.len(), 1, "one declaration, one Navigation node");
    let records = mutations
        .iter()
        .filter_map(|mutation| match mutation {
            Mutation::SetProp {
                node_id,
                property: PropertyKind::CompactNavigation,
                value,
            } => {
                assert_eq!(
                    *node_id, navigation[0],
                    "the choice belongs to the Navigation and nothing else"
                );
                match value {
                    PropertyValue::Integer(tag) => Some(*tag),
                    other => panic!("the choice travels as an integer tag, not {other:?}"),
                }
            }
            _ => None,
        })
        .collect();
    CHOICE.with(|cell| cell.set(CompactNavigation::Default));
    records
}

/// `Sidebar` and `None` travel as tags 1 and 2 of property 185, and a navigation that chose
/// nothing sends no record at all, so it reaches the Renderer exactly as it did before
/// there was a choice to make.
#[test]
fn fr21_2_3_compact_navigation_writes_its_tag() {
    assert_eq!(PropertyKind::CompactNavigation as u16, 185);
    assert_eq!(u16::from(CompactNavigation::Default), 0);
    assert_eq!(u16::from(CompactNavigation::Sidebar), 1);
    assert_eq!(u16::from(CompactNavigation::None), 2);

    assert_eq!(compact_records(CompactNavigation::Sidebar), vec![1]);
    assert_eq!(compact_records(CompactNavigation::None), vec![2]);
    assert_eq!(
        compact_records(CompactNavigation::Default),
        Vec::<i64>::new(),
        "no choice is the default, and the default is not sent"
    );
}

/// The value's name is the one the wire knows, and only the two real choices are in the
/// table the Renderer's enum is generated from: tag 0 is "not sent", as it is for every
/// other role.
#[test]
fn fr21_2_3_only_the_two_choices_are_in_the_generated_table() {
    assert_eq!(
        CompactNavigation::try_from(1),
        Ok(CompactNavigation::Sidebar)
    );
    assert_eq!(CompactNavigation::try_from(2), Ok(CompactNavigation::None));
    assert_eq!(CompactNavigation::try_from(0), Err(()));
    assert_eq!(
        compose_rust::schema::COMPACT_NAVIGATION_SCHEMA
            .iter()
            .map(|variant| (variant.name, variant.tag))
            .collect::<Vec<_>>(),
        vec![("Sidebar", 1), ("None", 2)],
    );
}
