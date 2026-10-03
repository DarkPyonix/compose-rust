//! A model of the Renderer's node table, for tests that compare what two batches built
//! rather than how they said it.
//!
//! Two front ends may allocate node and handler ids in different orders and send the
//! records for one node in a different order, and still build the same tree. What the
//! Renderer ends up holding is the thing to compare, so this applies batches the way the
//! interpreter applies them and prints the result with the ids taken out.

#![allow(dead_code)]

use compose_rust::protocol::{Mutation, PropertyValue, decode_batch};
use compose_rust::schema::PropertyKind;
use std::collections::{BTreeMap, HashMap};

#[derive(Clone, Debug, Default)]
struct Node {
    widget: String,
    props: BTreeMap<String, String>,
    modifiers: BTreeMap<u16, String>,
    children: Vec<u32>,
    parent: Option<u32>,
}

/// What the Renderer holds after the batches applied so far.
#[derive(Clone, Debug)]
pub struct Tree {
    nodes: HashMap<u32, Node>,
    /// Handler id per (node, event property), for delivering events by what is on screen.
    handlers: HashMap<(u32, String), u64>,
}

impl Default for Tree {
    fn default() -> Self {
        let mut nodes = HashMap::new();
        nodes.insert(
            0,
            Node {
                widget: "Root".to_owned(),
                ..Node::default()
            },
        );
        Self {
            nodes,
            handlers: HashMap::new(),
        }
    }
}

impl Tree {
    pub fn apply(&mut self, batch: &[u8]) {
        for mutation in decode_batch(batch).expect("the batch decodes") {
            self.apply_one(&mutation);
        }
    }

    fn apply_one(&mut self, mutation: &Mutation<'_>) {
        match mutation {
            Mutation::Create { node_id, widget } => {
                self.nodes.insert(
                    *node_id,
                    Node {
                        widget: format!("{widget:?}"),
                        ..Node::default()
                    },
                );
            }
            Mutation::SetProp {
                node_id,
                property,
                value,
            } => {
                let name = format!("{property:?}");
                if name.starts_with("On") {
                    match value {
                        PropertyValue::Integer(handler) => {
                            self.handlers
                                .insert((*node_id, name.clone()), *handler as u64);
                        }
                        _ => {
                            self.handlers.remove(&(*node_id, name.clone()));
                        }
                    }
                }
                if let Some(node) = self.nodes.get_mut(node_id) {
                    let shown = if name.starts_with("On") {
                        "handler".to_owned()
                    } else {
                        match value {
                            PropertyValue::String(text) => format!("{text:?}"),
                            other => format!("{other:?}"),
                        }
                    };
                    if matches!(value, PropertyValue::None) {
                        node.props.remove(&name);
                    } else {
                        node.props.insert(name, shown);
                    }
                }
            }
            Mutation::SetModifier {
                node_id,
                index,
                modifier,
            } => {
                if let Some(node) = self.nodes.get_mut(node_id) {
                    let shown = format!("{modifier:?}");
                    if shown == "Empty" {
                        node.modifiers.remove(index);
                    } else {
                        node.modifiers.insert(*index, shown);
                    }
                }
            }
            Mutation::Insert {
                parent_id,
                node_id,
                index,
            }
            | Mutation::Move {
                parent_id,
                node_id,
                index,
            } => {
                self.detach(*node_id);
                if let Some(parent) = self.nodes.get_mut(parent_id) {
                    let at = (*index as usize).min(parent.children.len());
                    parent.children.insert(at, *node_id);
                }
                if let Some(node) = self.nodes.get_mut(node_id) {
                    node.parent = Some(*parent_id);
                }
            }
            Mutation::Remove { node_id } => {
                self.detach(*node_id);
                self.forget(*node_id);
            }
            Mutation::SetText { node_id, text, .. } => {
                if let Some(node) = self.nodes.get_mut(node_id) {
                    node.props.insert("Text".to_owned(), format!("{text:?}"));
                }
            }
            Mutation::AppendText { node_id, text } => {
                if let Some(node) = self.nodes.get_mut(node_id) {
                    let current = node
                        .props
                        .get("Text")
                        .map(|shown| shown.trim_matches('"').to_owned())
                        .unwrap_or_default();
                    node.props
                        .insert("Text".to_owned(), format!("{:?}", current + text));
                }
            }
            _ => {}
        }
    }

    fn detach(&mut self, node_id: u32) {
        let parent = self.nodes.get(&node_id).and_then(|node| node.parent);
        if let Some(parent) = parent {
            if let Some(parent) = self.nodes.get_mut(&parent) {
                parent.children.retain(|child| *child != node_id);
            }
        }
    }

    fn forget(&mut self, node_id: u32) {
        if let Some(node) = self.nodes.remove(&node_id) {
            for child in node.children {
                self.forget(child);
            }
        }
        self.handlers.retain(|(node, _), _| *node != node_id);
    }

    /// The tree, indented, with ids left out.
    pub fn dump(&self) -> String {
        let mut out = String::new();
        self.dump_node(0, 0, &mut out);
        out
    }

    fn dump_node(&self, node_id: u32, depth: usize, out: &mut String) {
        let Some(node) = self.nodes.get(&node_id) else {
            return;
        };
        out.push_str(&"  ".repeat(depth));
        out.push_str(&node.widget);
        for (name, value) in &node.props {
            out.push_str(&format!(" {name}={value}"));
        }
        for (index, modifier) in &node.modifiers {
            out.push_str(&format!(" m{index}={modifier}"));
        }
        out.push('\n');
        for child in &node.children {
            self.dump_node(*child, depth + 1, out);
        }
    }

    fn preorder(&self) -> Vec<u32> {
        let mut order = Vec::new();
        let mut stack = vec![0_u32];
        while let Some(node) = stack.pop() {
            order.push(node);
            if let Some(entry) = self.nodes.get(&node) {
                for child in entry.children.iter().rev() {
                    stack.push(*child);
                }
            }
        }
        order
    }

    /// The `nth` node of this widget whose text is `label`, and the handler of `event` on
    /// it.
    pub fn find(
        &self,
        widget: &str,
        label: Option<&str>,
        nth: usize,
        event: PropertyKind,
    ) -> (u32, u64) {
        let event = format!("{event:?}");
        let label = label.map(|label| format!("{label:?}"));
        let node = self
            .preorder()
            .into_iter()
            .filter(|node| {
                let entry = &self.nodes[node];
                entry.widget == widget
                    && label
                        .as_ref()
                        .is_none_or(|label| entry.props.get("Text") == Some(label))
            })
            .nth(nth)
            .unwrap_or_else(|| panic!("no {widget} {label:?} number {nth} on screen"));
        let handler = self.handlers[&(node, event)];
        (node, handler)
    }

    /// The texts on screen, in tree order.
    pub fn texts(&self) -> Vec<String> {
        self.preorder()
            .into_iter()
            .filter_map(|node| self.nodes[&node].props.get("Text").cloned())
            .map(|text| text.trim_matches('"').to_owned())
            .collect()
    }

    /// The widgets under the root, in tree order.
    pub fn widgets(&self) -> Vec<String> {
        self.preorder()
            .into_iter()
            .skip(1)
            .map(|node| self.nodes[&node].widget.clone())
            .collect()
    }

    pub fn node_count(&self) -> usize {
        self.nodes.len() - 1
    }
}

/// Every record in a batch, by kind, for counting.
pub fn kinds(batch: &[u8]) -> Vec<String> {
    decode_batch(batch)
        .expect("the batch decodes")
        .iter()
        .map(|mutation| match mutation {
            Mutation::Create { .. } => "Create".to_owned(),
            Mutation::SetProp { property, .. } => format!("SetProp {property:?}"),
            Mutation::SetModifier { .. } => "SetModifier".to_owned(),
            Mutation::Insert { .. } => "Insert".to_owned(),
            Mutation::Move { .. } => "Move".to_owned(),
            Mutation::Remove { .. } => "Remove".to_owned(),
            Mutation::AppendText { .. } => "AppendText".to_owned(),
            other => format!("{other:?}"),
        })
        .collect()
}
