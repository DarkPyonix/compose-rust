use crate::protocol::{BatchEncoder, Mutation, PropertyValue, ProtocolError};
use crate::schema::{PropertyKind, WidgetKind};
use dioxus_core::{
    AttributeValue, ElementId, Template, TemplateAttribute, TemplateNode, WriteMutations,
};
use std::collections::HashMap;

/// The inclusive FR-13 design-property range. Extension properties follow this range.
const FIRST_DESIGN_PROPERTY: u16 = PropertyKind::TypeRole as u16;
const LAST_DESIGN_PROPERTY: u16 = PropertyKind::Variant as u16;

#[derive(Clone, Copy, Debug)]
struct Handler {
    id: u64,
    element: ElementId,
    node_id: u32,
    name: &'static str,
}

#[derive(Clone, Copy, Debug)]
enum PathTarget {
    Node(u32),
    Slot { parent: u32, index: u32 },
}

#[derive(Debug)]
struct StackNode {
    node_id: u32,
    paths: HashMap<Vec<u8>, PathTarget>,
}

/// `dioxus-core` mutation sink that writes the Compose wire protocol directly.
pub struct ComposeRenderer {
    encoder: BatchEncoder,
    next_node_id: u32,
    next_handler_id: u64,
    nodes: Vec<Option<u32>>,
    handlers: Vec<Handler>,
    parents: HashMap<u32, (u32, u32)>,
    /// Which FR-13 design properties a node has actually been given, one bit per tag.
    design_props: HashMap<u32, u32>,
    stack: Vec<StackNode>,
    error: Option<ProtocolError>,
}

impl Default for ComposeRenderer {
    fn default() -> Self {
        Self::new()
    }
}

impl ComposeRenderer {
    pub fn new() -> Self {
        Self {
            encoder: BatchEncoder::with_capacity(16 * 1024, 4 * 1024, 256),
            next_node_id: 1,
            next_handler_id: 1,
            nodes: Vec::with_capacity(256),
            handlers: Vec::with_capacity(64),
            parents: HashMap::with_capacity(256),
            design_props: HashMap::with_capacity(64),
            stack: Vec::with_capacity(64),
            error: None,
        }
    }

    pub fn begin_frame(&mut self) {
        self.encoder.clear();
        self.error = None;
    }

    pub fn finish_frame(&mut self) -> Result<&[u8], ProtocolError> {
        if let Some(error) = self.error.take() {
            return Err(error);
        }
        self.encoder.finish()
    }

    pub fn handler(&self, handler_id: u64) -> Option<(ElementId, u32, &'static str)> {
        self.handlers
            .iter()
            .find(|handler| handler.id == handler_id)
            .map(|handler| (handler.element, handler.node_id, handler.name))
    }

    pub fn set_text(
        &mut self,
        element: ElementId,
        text: &str,
        selection: Option<crate::Selection>,
    ) {
        let Some(node_id) = self.node(element) else {
            return;
        };
        self.write(Mutation::SetText {
            node_id,
            text,
            selection,
        });
    }

    /// FR-14.5: the root theme record. Written once per rebuild, never per frame.
    pub fn set_theme(&mut self, theme: crate::schema::Theme) {
        self.write(Mutation::SetTheme(theme));
    }

    pub fn set_text_node(&mut self, node_id: u32, text: &str, selection: Option<crate::Selection>) {
        self.write(Mutation::SetText {
            node_id,
            text,
            selection,
        });
    }

    /// FR-9: append the streamed tail to a Text node without resending its whole value.
    pub fn append_text_node(&mut self, node_id: u32, text: &str) {
        self.write(Mutation::AppendText { node_id, text });
    }

    /// Puts an asset's bytes in the batch arena for the Renderer to copy into its cache.
    pub fn register_asset(&mut self, asset_id: u32, kind: crate::schema::AssetKind, bytes: &[u8]) {
        self.write(Mutation::RegisterAsset {
            asset_id,
            kind,
            bytes,
        });
    }

    /// Drops the asset from the Renderer's cache. Using the id afterwards is an error.
    pub fn release_asset(&mut self, asset_id: u32) {
        self.write(Mutation::ReleaseAsset { asset_id });
    }

    fn write(&mut self, mutation: Mutation<'_>) {
        if self.error.is_none() {
            if let Err(error) = self.encoder.encode(&mutation) {
                self.error = Some(error);
            }
        }
    }

    fn allocate_node(&mut self, widget: WidgetKind) -> u32 {
        let id = self.next_node_id;
        self.next_node_id = self.next_node_id.checked_add(1).unwrap_or(1);
        self.write(Mutation::Create {
            node_id: id,
            widget,
        });
        id
    }

    fn map_node(&mut self, element: ElementId, node_id: u32) {
        if self.nodes.len() <= element.0 {
            self.nodes.resize(element.0 + 1, None);
        }
        self.nodes[element.0] = Some(node_id);
    }

    fn node(&self, element: ElementId) -> Option<u32> {
        self.nodes.get(element.0).copied().flatten()
    }

    fn build_template_node(
        &mut self,
        node: &TemplateNode,
        path: &mut Vec<u8>,
        paths: &mut HashMap<Vec<u8>, PathTarget>,
    ) -> Option<u32> {
        match node {
            TemplateNode::Element {
                tag,
                attrs,
                children,
                ..
            } => {
                let widget = match widget_kind(tag) {
                    Ok(widget) => widget,
                    Err(error) => {
                        self.error = Some(error);
                        return None;
                    }
                };
                let node_id = self.allocate_node(widget);
                paths.insert(path.clone(), PathTarget::Node(node_id));
                for attribute in *attrs {
                    if let TemplateAttribute::Static { name, value, .. } = attribute {
                        self.set_property(node_id, name, &AttributeValue::Text((*value).into()));
                    }
                }
                for (index, child) in children.iter().enumerate() {
                    path.push(index as u8);
                    match child {
                        TemplateNode::Dynamic { .. } => {
                            paths.insert(
                                path.clone(),
                                PathTarget::Slot {
                                    parent: node_id,
                                    index: index as u32,
                                },
                            );
                        }
                        _ => {
                            if let Some(child_id) = self.build_template_node(child, path, paths) {
                                self.insert_node(node_id, child_id, index as u32);
                            }
                        }
                    }
                    path.pop();
                }
                Some(node_id)
            }
            TemplateNode::Text { text } => {
                let node_id = self.allocate_node(WidgetKind::Text);
                self.write(Mutation::SetProp {
                    node_id,
                    property: PropertyKind::Text,
                    value: PropertyValue::String(text),
                });
                paths.insert(path.clone(), PathTarget::Node(node_id));
                Some(node_id)
            }
            TemplateNode::Dynamic { .. } => None,
        }
    }

    /// FR-13.8: role tag 0 means "not sent", so a design property at its neutral value
    /// produces no record at all. A property that was set and then cleared still sends
    /// its zero once, which is what tells the Renderer to drop the override.
    fn should_write_design_property(
        &mut self,
        node_id: u32,
        property: PropertyKind,
        neutral: bool,
    ) -> bool {
        let tag = property as u16;
        if !(FIRST_DESIGN_PROPERTY..=LAST_DESIGN_PROPERTY).contains(&tag) {
            return true;
        }
        let bit = 1_u32 << (tag - FIRST_DESIGN_PROPERTY);
        let seen = self.design_props.get(&node_id).copied().unwrap_or(0);
        if neutral {
            if seen & bit == 0 {
                return false;
            }
            self.design_props.insert(node_id, seen & !bit);
        } else if seen & bit == 0 {
            self.design_props.insert(node_id, seen | bit);
        }
        true
    }

    fn set_property(&mut self, node_id: u32, name: &str, value: &AttributeValue) {
        let Some(property) = property_kind(name) else {
            // SPEC-GAP: dioxus-core has no fallible WriteMutations methods. Preserve the
            // protocol error and surface it when the batch is finalized.
            self.error = Some(ProtocolError::InvalidProperty(0));
            return;
        };
        let value = match value {
            AttributeValue::Text(value) => PropertyValue::String(value),
            AttributeValue::Float(value) => PropertyValue::Float(*value as f32),
            AttributeValue::Int(value) => PropertyValue::Integer(*value),
            AttributeValue::Bool(value) => PropertyValue::Bool(*value),
            AttributeValue::None => PropertyValue::None,
            AttributeValue::Listener(_) | AttributeValue::Any(_) => return,
        };
        let neutral = matches!(
            value,
            PropertyValue::None | PropertyValue::Integer(0) | PropertyValue::Float(0.0)
        );
        if !self.should_write_design_property(node_id, property, neutral) {
            return;
        }
        self.write(Mutation::SetProp {
            node_id,
            property,
            value,
        });
    }

    fn insert_stack(&mut self, parent: u32, index: u32, count: usize) {
        let start = self.stack.len().saturating_sub(count);
        let nodes: Vec<_> = self.stack.drain(start..).collect();
        for (offset, node) in nodes.into_iter().enumerate() {
            self.insert_node(parent, node.node_id, index.saturating_add(offset as u32));
        }
    }

    fn insert_node(&mut self, parent: u32, node_id: u32, index: u32) {
        let mutation = if self.parents.contains_key(&node_id) {
            Mutation::Move {
                parent_id: parent,
                node_id,
                index,
            }
        } else {
            Mutation::Insert {
                parent_id: parent,
                node_id,
                index,
            }
        };
        self.write(mutation);
        self.parents.insert(node_id, (parent, index));
    }
}

impl WriteMutations for ComposeRenderer {
    fn append_children(&mut self, id: ElementId, m: usize) {
        if let Some(parent) = self.node(id) {
            self.insert_stack(parent, u32::MAX, m);
        }
    }

    fn assign_node_id(&mut self, path: &'static [u8], id: ElementId) {
        if let Some(PathTarget::Node(node_id)) = self
            .stack
            .last()
            .and_then(|loaded| loaded.paths.get(path))
            .copied()
        {
            self.map_node(id, node_id);
        }
    }

    fn create_placeholder(&mut self, id: ElementId) {
        // A Dioxus placeholder is not materialized in the Compose tree. It is
        // represented by its eventual insertion position.
        self.map_node(id, 0);
        self.stack.push(StackNode {
            node_id: 0,
            paths: HashMap::new(),
        });
    }

    fn create_text_node(&mut self, value: &str, id: ElementId) {
        let node_id = self.allocate_node(WidgetKind::Text);
        self.map_node(id, node_id);
        self.write(Mutation::SetProp {
            node_id,
            property: PropertyKind::Text,
            value: PropertyValue::String(value),
        });
        self.stack.push(StackNode {
            node_id,
            paths: HashMap::new(),
        });
    }

    fn load_template(&mut self, template: Template, index: usize, id: ElementId) {
        let mut paths = HashMap::new();
        let mut path = Vec::new();
        if let Some(root) = self.build_template_node(&template.roots[index], &mut path, &mut paths)
        {
            self.map_node(id, root);
            self.stack.push(StackNode {
                node_id: root,
                paths,
            });
        }
    }

    fn replace_node_with(&mut self, id: ElementId, m: usize) {
        if let Some(node_id) = self.node(id) {
            let target = self.parents.remove(&node_id);
            self.write(Mutation::Remove { node_id });
            if let Some((parent, index)) = target {
                self.insert_stack(parent, index, m);
                return;
            }
        }
        let keep_from = self.stack.len().saturating_sub(m);
        self.stack.drain(..keep_from);
    }

    fn replace_placeholder_with_nodes(&mut self, path: &'static [u8], m: usize) {
        let parent_position = self.stack.len().saturating_sub(m + 1);
        let target = self
            .stack
            .get(parent_position)
            .and_then(|loaded| loaded.paths.get(path))
            .copied();
        if let Some(PathTarget::Slot { parent, index }) = target {
            let children: Vec<_> = self.stack.drain(parent_position + 1..).collect();
            for (offset, child) in children.into_iter().enumerate() {
                self.insert_node(parent, child.node_id, index + offset as u32);
            }
        }
    }

    fn insert_nodes_after(&mut self, id: ElementId, m: usize) {
        if let Some(anchor) = self.node(id) {
            if let Some((parent, index)) = self.parents.get(&anchor).copied() {
                self.insert_stack(parent, index.saturating_add(1), m);
            }
        }
    }

    fn insert_nodes_before(&mut self, id: ElementId, m: usize) {
        if let Some(anchor) = self.node(id) {
            if let Some((parent, index)) = self.parents.get(&anchor).copied() {
                self.insert_stack(parent, index, m);
            }
        }
    }

    fn set_attribute(
        &mut self,
        name: &'static str,
        _namespace: Option<&'static str>,
        value: &AttributeValue,
        id: ElementId,
    ) {
        if let Some(node_id) = self.node(id) {
            if let (Some(index), AttributeValue::Bool(enabled)) = (
                match name {
                    "fill_max_width" => Some(0),
                    "fill_max_height" => Some(1),
                    _ => None,
                },
                value,
            ) {
                self.write(Mutation::SetModifier {
                    node_id,
                    index,
                    modifier: if !enabled {
                        crate::Modifier::Empty
                    } else if name == "fill_max_width" {
                        crate::Modifier::FillMaxWidth
                    } else {
                        crate::Modifier::FillMaxHeight
                    },
                });
                return;
            }
            self.set_property(node_id, name, value);
        }
    }

    fn set_node_text(&mut self, value: &str, id: ElementId) {
        if let Some(node_id) = self.node(id) {
            self.write(Mutation::SetProp {
                node_id,
                property: PropertyKind::Text,
                value: PropertyValue::String(value),
            });
        }
    }

    fn create_event_listener(&mut self, name: &'static str, id: ElementId) {
        let Some(node_id) = self.node(id) else {
            return;
        };
        let handler_id = self.next_handler_id;
        self.next_handler_id += 1;
        self.handlers.push(Handler {
            id: handler_id,
            element: id,
            node_id,
            name,
        });
        if let Some(property) = event_property(name) {
            self.write(Mutation::SetProp {
                node_id,
                property,
                value: PropertyValue::Integer(handler_id as i64),
            });
        }
    }

    fn remove_event_listener(&mut self, name: &'static str, id: ElementId) {
        let Some(position) = self
            .handlers
            .iter()
            .position(|handler| handler.element == id && handler.name == name)
        else {
            return;
        };
        self.handlers.swap_remove(position);
        if let (Some(node_id), Some(property)) = (self.node(id), event_property(name)) {
            self.write(Mutation::SetProp {
                node_id,
                property,
                value: PropertyValue::None,
            });
        }
    }

    fn remove_node(&mut self, id: ElementId) {
        if let Some(node_id) = self.node(id) {
            self.write(Mutation::Remove { node_id });
            self.parents.remove(&node_id);
        }
        if let Some(slot) = self.nodes.get_mut(id.0) {
            *slot = None;
        }
        self.handlers.retain(|handler| handler.element != id);
    }

    fn push_root(&mut self, id: ElementId) {
        if let Some(node_id) = self.node(id) {
            self.stack.push(StackNode {
                node_id,
                paths: HashMap::new(),
            });
        }
    }
}

fn widget_kind(name: &str) -> Result<WidgetKind, ProtocolError> {
    WidgetKind::from_name(name).map_err(|()| ProtocolError::InvalidWidget(0))
}

fn property_kind(name: &str) -> Option<PropertyKind> {
    PropertyKind::from_name(name).ok()
}

fn event_property(name: &str) -> Option<PropertyKind> {
    match name {
        "click" | "onclick" => Some(PropertyKind::OnClick),
        "valuechange" | "onvaluechange" => Some(PropertyKind::OnValueChange),
        "submit" | "onsubmit" => Some(PropertyKind::OnSubmit),
        "focuslost" | "onfocuslost" => Some(PropertyKind::OnFocusLost),
        "keydown" | "onkeydown" => Some(PropertyKind::OnKeyDown),
        "rangerequest" | "onrangerequest" => Some(PropertyKind::OnRangeRequested),
        // A picker reports through the same handler slot a TextField uses; the slot names
        // where the handler lives, the event record names what it carries.
        "pick" | "onpick" => Some(PropertyKind::OnValueChange),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::prelude::*;

    fn app() -> Element {
        rsx! {
            Column {
                Text { text: "zero" }
                TextField { placeholder: "type" }
                Button { text: "increment", on_click: move |_| {} }
            }
        }
    }

    #[test]
    fn builds_m0_tree_from_rsx() {
        let mut dom = VirtualDom::new(app);
        let mut renderer = ComposeRenderer::new();
        dom.rebuild(&mut renderer);
        let decoded = crate::protocol::decode_batch(renderer.finish_frame().unwrap()).unwrap();
        eprintln!("{decoded:#?}");
        assert!(decoded.iter().any(|mutation| matches!(
            mutation,
            Mutation::Create {
                widget: WidgetKind::Column,
                ..
            }
        )));
        assert!(decoded.iter().any(|mutation| matches!(
            mutation,
            Mutation::Create {
                widget: WidgetKind::TextField,
                ..
            }
        )));
        assert_eq!(
            decoded
                .iter()
                .filter(|mutation| matches!(mutation, Mutation::Insert { .. }))
                .count(),
            3
        );
    }
}
