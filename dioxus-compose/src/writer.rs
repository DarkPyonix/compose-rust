//! The node table and the attribute translation that every authoring front end writes
//! through.
//!
//! Two front ends produce the same wire records: the `rsx!` path, where `dioxus-core`
//! compares templates and calls back with what changed, and the slot table runtime in
//! [`crate::runtime`], which re-runs the groups that read a changed state and knows what it
//! changed without comparing a tree. Both end here, so a widget attribute means exactly one
//! thing on the wire whichever of them said it. That is what lets the Renderer stay
//! unchanged, and it is why nothing in this file knows about either front end.

use crate::protocol::{BatchEncoder, Mutation, PropertyValue, ProtocolError};
use crate::schema::{PropertyKind, WidgetKind};
use std::collections::HashMap;

/// The inclusive range of design-property tags. Extension properties follow this range.
const FIRST_OPTIONAL_PROPERTY: u16 = PropertyKind::TypeRole as u16;
const LAST_OPTIONAL_PROPERTY: u16 = PropertyKind::Variant as u16;

/// The bit that tracks whether a node has ever carried a list of text runs.
///
/// Outside the range above and kept separately, because widening that range swallowed
/// values where zero means zero: a picker whose minimum really is 0 stopped sending it
/// and got the Renderer's default instead. A list of runs has no such value. Empty and
/// absent are the same state, so the first empty one is worth no record.
const SPANS_BIT: u32 = 63;

/// How many Modifier slots a node has. The slot numbers are assigned in `modifier_for`,
/// and this is one past the last of them.
const MODIFIER_SLOTS: usize = 14;

/// Node id 0 is the "no node" sentinel: a placeholder, which draws nothing and takes no
/// slot in the Compose tree. It is also the root every top-level node is inserted under.
pub(crate) const PLACEHOLDER_NODE: u32 = 0;

/// One attribute value, as either front end hands it over.
///
/// The `rsx!` path converts `dioxus_core::AttributeValue` into this, and the slot table
/// runtime builds it directly, so the translation below never sees which one it came from.
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum AttrValue<'a> {
    /// Unset, or set and then taken away.
    None,
    Text(&'a str),
    Float(f64),
    Int(i64),
    Bool(bool),
    /// An opaque run of bytes: a drawing command list or a list of text runs.
    Bytes(&'a [u8]),
    /// An event listener, carrying the handler id the front end allocated for it.
    Listener(u64),
    /// A value of a type no widget attribute accepts. It is never written.
    Unsupported,
}

/// One position among a parent's children.
///
/// The Renderer counts only the nodes it drew, so the index an Insert carries is the
/// number of drawn siblings that come first. A placeholder and a template's unfilled
/// dynamic child are both positions nothing is drawn for, and keeping them here is what
/// lets the content that arrives later take the position it was promised rather than the
/// end of the list.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Slot {
    /// A node the Renderer drew.
    Node(u32),
    /// A placeholder, named by the front end's own identity for it. Placeholders all carry
    /// node id 0, so this is the only thing that tells two of them apart.
    Hole(usize),
    /// A template's dynamic child, from the moment the template is built until the
    /// content of that child arrives.
    Pending(u32),
}

/// The Host's half of the node table, and the batch the current call is writing.
pub struct NodeWriter {
    encoder: BatchEncoder,
    next_node_id: u32,
    next_handler_id: u64,
    /// Which parent each drawn node hangs under.
    parents: HashMap<u32, u32>,
    /// Which parent each placeholder stands under, keyed by its own identity rather than
    /// by a node id.
    ///
    /// Placeholders all share node id 0, so keying them by node id made every one of them
    /// overwrite the last: a branch that filled in was then attached to whichever other
    /// placeholder had been seen most recently, which put a subtree under a stranger and,
    /// when that stranger was its own descendant, left the parent chain with no root.
    placeholders: HashMap<usize, u32>,
    /// What stands under each parent, in order.
    ///
    /// An index is read off this list rather than remembered from the Insert that put a
    /// node there. Removing a sibling moves everything after it, so a remembered index is
    /// only right until the next removal: a menu whose entries came and went handed the
    /// next set of entries the position the old last entry had held, and they piled up
    /// past the end of the list instead of taking its place.
    children: HashMap<u32, Vec<Slot>>,
    /// The next marker to stand in a template's dynamic child.
    next_marker: u32,
    /// Which design properties a node has actually been given, one bit per tag.
    optional_props: HashMap<u32, u64>,
    /// A border arrives as a width and a colour in separate attributes; this holds
    /// whichever came first until the pair can be written as one modifier.
    pending_borders: HashMap<u32, (Option<f32>, Option<crate::Paint>)>,
    /// Which attribute last wrote each of a node's Modifier slots, or `""` for a slot
    /// nothing has written.
    ///
    /// Two things need the owner rather than a "written" bit. Clearing a slot that was
    /// never written would cost a mutation on the first frame of every widget, for a
    /// Modifier nobody asked for. And some slots have two attributes that can fill them,
    /// `shape_role` and `corner_radius` for the shape, `padding_role` and `padding` for
    /// the padding: the one left unset arrives as an empty attribute, and without the
    /// owner it would clear the modifier its partner had just written, which is how a
    /// rounded container came out square.
    modifier_slots: HashMap<u32, [&'static str; MODIFIER_SLOTS]>,
    /// Which application token each observed node was given, for reading a report back.
    size_tokens: HashMap<u32, u32>,
    error: Option<ProtocolError>,
}

impl Default for NodeWriter {
    fn default() -> Self {
        Self::new()
    }
}

impl NodeWriter {
    pub fn new() -> Self {
        Self {
            encoder: BatchEncoder::with_capacity(16 * 1024, 4 * 1024, 256),
            next_node_id: 1,
            next_handler_id: 1,
            parents: HashMap::with_capacity(256),
            placeholders: HashMap::with_capacity(16),
            children: HashMap::with_capacity(256),
            next_marker: 1,
            optional_props: HashMap::with_capacity(64),
            pending_borders: HashMap::with_capacity(16),
            modifier_slots: HashMap::with_capacity(64),
            // Empty until a screen asks, which is the point: a tree that observes nothing
            // allocates nothing here.
            size_tokens: HashMap::new(),
            error: None,
        }
    }

    /// The batch arena, so a runtime that reads Host memory through a mapped view can be
    /// handed one.
    pub fn arena(&self) -> (*const u8, usize) {
        self.encoder.arena()
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

    /// The application's name for an observed node, if that node is observed.
    pub(crate) fn size_token(&self, node_id: u32) -> Option<u32> {
        self.size_tokens.get(&node_id).copied()
    }

    /// Keeps the first error of the frame. It is reported when the batch is finished,
    /// because neither front end has a fallible place to report it from mid-frame.
    pub(crate) fn set_error(&mut self, error: ProtocolError) {
        self.error = Some(error);
    }

    pub(crate) fn write(&mut self, mutation: Mutation<'_>) {
        if self.error.is_none() {
            if let Err(error) = self.encoder.encode(&mutation) {
                self.error = Some(error);
            }
        }
    }

    /// The next handler id. Ids are never reused, so an event that arrives for a handler
    /// that has since gone away names nothing rather than somebody else's handler.
    pub(crate) fn allocate_handler_id(&mut self) -> u64 {
        let id = self.next_handler_id;
        self.next_handler_id += 1;
        id
    }

    /// Creates a node and says which id it was given.
    pub(crate) fn allocate_node(&mut self, widget: WidgetKind) -> u32 {
        let id = self.next_node_id;
        self.next_node_id = self.next_node_id.checked_add(1).unwrap_or(1);
        self.write(Mutation::Create {
            node_id: id,
            widget,
        });
        id
    }

    /// The root theme record. Written once per rebuild, never per frame.
    pub fn set_theme(&mut self, theme: crate::schema::Theme) {
        self.write(Mutation::SetTheme(theme));
    }

    /// The root window record. Written once per rebuild, never per frame.
    pub fn set_window(&mut self, window: crate::schema::Window) {
        self.write(Mutation::SetWindow(window));
    }

    pub fn set_text_node(&mut self, node_id: u32, text: &str, selection: Option<crate::Selection>) {
        self.write(Mutation::SetText {
            node_id,
            text,
            selection,
        });
    }

    /// A Text node's string, as a template's static text or a dynamic text node carries it.
    pub(crate) fn set_text_property(&mut self, node_id: u32, text: &str) {
        self.write(Mutation::SetProp {
            node_id,
            property: PropertyKind::Text,
            value: PropertyValue::String(text),
        });
    }

    /// Copies one asset into the batch. The bytes ride behind the records, and the
    /// Renderer takes its own copy inside the call that carries them.
    pub fn register_asset(&mut self, asset_id: u32, kind: crate::schema::AssetKind, bytes: &[u8]) {
        self.write(Mutation::RegisterAsset {
            asset_id,
            kind,
            bytes,
        });
    }

    pub fn release_asset(&mut self, asset_id: u32) {
        self.write(Mutation::ReleaseAsset { asset_id });
    }

    /// Writes one transient message into the batch.
    ///
    /// It names no node, because a message is not in the tree: it is a sentence with a
    /// lifetime, and that lifetime belongs to the Renderer.
    pub fn show_message(
        &mut self,
        handler_id: u64,
        text: &str,
        action: &str,
        duration: crate::schema::MessageDuration,
    ) {
        self.write(Mutation::ShowMessage {
            handler_id,
            text,
            action,
            duration,
        });
    }

    /// Writes one notification command into the batch.
    ///
    /// Like a message it names no node: what it asks for happens outside the window, and
    /// the Renderer is the side that owns the platform it happens on.
    pub(crate) fn notification(&mut self, command: &crate::notification::NotificationCommand) {
        use crate::notification::NotificationCommand;
        match command {
            NotificationCommand::Post(notification) => {
                self.write(Mutation::PostNotification {
                    key: &notification.key,
                    title: &notification.title,
                    body: &notification.body,
                    channel: &notification.channel,
                    action_1: &notification.actions[0],
                    action_2: &notification.actions[1],
                    importance: notification.importance,
                    presentation: notification.presentation,
                });
            }
            NotificationCommand::Withdraw(key) => {
                self.write(Mutation::WithdrawNotification { key });
            }
            NotificationCommand::RequestPermission => {
                self.write(Mutation::RequestNotificationPermission);
            }
        }
    }

    /// Append the streamed tail to a Text node without resending its whole value.
    pub fn append_text_node(&mut self, node_id: u32, text: &str) {
        self.write(Mutation::AppendText { node_id, text });
    }

    /// Writes one widget attribute: a Modifier slot, a property, or nothing.
    ///
    /// This is the whole of what an attribute means on the wire, and both front ends call
    /// it with the same names and the same values for the same widget.
    pub fn set_attribute(&mut self, node_id: u32, name: &'static str, value: &AttrValue<'_>) {
        if let Some(slot) = self.modifier_for(node_id, name, value) {
            if let Some((index, modifier)) = slot {
                self.write(Mutation::SetModifier {
                    node_id,
                    index,
                    modifier,
                });
            }
            return;
        }
        self.set_property(node_id, name, value);
    }

    /// Writes the handler id of an event listener under the property the Renderer fires
    /// it through. Events with no property of their own, such as a clickable modifier,
    /// write nothing here.
    pub(crate) fn set_listener(&mut self, node_id: u32, name: &str, handler_id: u64) {
        if let Some(property) = event_property(name) {
            self.write(Mutation::SetProp {
                node_id,
                property,
                value: PropertyValue::Integer(handler_id as i64),
            });
        }
    }

    /// Takes a listener away: the property it was fired through is cleared.
    pub(crate) fn clear_listener(&mut self, node_id: u32, name: &str) {
        if let Some(property) = event_property(name) {
            self.write(Mutation::SetProp {
                node_id,
                property,
                value: PropertyValue::None,
            });
        }
    }

    /// Turns a Modifier attribute into one slot of the node's chain.
    ///
    /// The Renderer applies the chain in list order, exactly as hand-written Compose does,
    /// so the slot an attribute occupies decides what the result looks like. Padding
    /// before a background is a margin; after it, it insets the content. Fixing the slots
    /// here means `Card { background: .., padding: 16.0 }` behaves the way the person who
    /// wrote it expects, whichever order they happened to type the attributes in.
    ///
    /// The order below reads outside in: how big the widget is, what shape it is, what
    /// fills it, what outlines it, what lifts it, what responds to a press, and finally
    /// how far its content sits inside all of that.
    ///
    /// A `false` or absent value writes `Empty` into the slot rather than dropping the
    /// mutation, because a slot that keeps its old value would leave the removed modifier
    /// applied.
    fn modifier_for(
        &mut self,
        node_id: u32,
        name: &'static str,
        value: &AttrValue<'_>,
    ) -> Option<Option<(u16, crate::Modifier)>> {
        use crate::Modifier;

        // Slot assignments. Append only: an existing slot never changes meaning, because a
        // node keeps whatever a slot held until something overwrites it.
        const WEIGHT: u16 = 0;
        const FILL_MAX_WIDTH: u16 = 1;
        const FILL_MAX_HEIGHT: u16 = 2;
        const WIDTH: u16 = 3;
        const HEIGHT: u16 = 4;
        const SHAPE: u16 = 5;
        const BACKGROUND: u16 = 6;
        const BORDER: u16 = 7;
        const ELEVATION: u16 = 8;
        const CLICKABLE: u16 = 9;
        const PADDING: u16 = 10;
        const OBSERVE_SIZE: u16 = 11;
        const MOTION: u16 = 12;
        const MATERIAL: u16 = 13;

        let float = |value: &AttrValue<'_>| match value {
            AttrValue::Float(number) => Some(*number as f32),
            AttrValue::Int(number) => Some(*number as f32),
            _ => None,
        };
        let integer = |value: &AttrValue<'_>| match value {
            AttrValue::Int(number) => Some(*number),
            AttrValue::Float(number) => Some(*number as i64),
            _ => None,
        };

        let slot_of = |name: &str| match name {
            "weight" => Some(WEIGHT),
            "fill_max_width" => Some(FILL_MAX_WIDTH),
            "fill_max_height" => Some(FILL_MAX_HEIGHT),
            "width" => Some(WIDTH),
            "height" => Some(HEIGHT),
            "shape_role" | "corner_radius" => Some(SHAPE),
            "background" => Some(BACKGROUND),
            "border_width" | "border_color" => Some(BORDER),
            "elevation" => Some(ELEVATION),
            "onclickable" => Some(CLICKABLE),
            "padding" | "padding_role" => Some(PADDING),
            "observe_size" => Some(OBSERVE_SIZE),
            "motion" => Some(MOTION),
            "material" => Some(MATERIAL),
            _ => None,
        };
        let slot = slot_of(name)?;

        // An unset Modifier attribute is not a property, so it must not fall through to
        // set_property and be rejected as an unknown one. It only produces a mutation if
        // this slot already holds something, because clearing a slot that was never
        // written would cost a mutation on the first frame of every widget.
        if matches!(value, AttrValue::None) {
            let owners = self
                .modifier_slots
                .entry(node_id)
                .or_insert([""; MODIFIER_SLOTS]);
            // Only the attribute that wrote the slot may clear it. `corner_radius` being
            // unset says nothing about the `shape_role` sitting in the same slot.
            if owners[slot as usize] != name {
                return Some(None);
            }
            owners[slot as usize] = "";
            return Some(Some((slot, crate::Modifier::Empty)));
        }
        self.modifier_slots
            .entry(node_id)
            .or_insert([""; MODIFIER_SLOTS])[slot as usize] = name;

        match name {
            // The token is the application's name for this node, and it stays here: what
            // the Renderer reports back is its own node id, so the pairing has to be
            // remembered at the moment the two are both in hand.
            "observe_size" => {
                let token = u32::try_from(integer(value)?).ok()?;
                self.size_tokens.insert(node_id, token);
                Some(Some((slot, Modifier::ObserveSize { token })))
            }
            "fill_max_width" | "fill_max_height" => {
                let AttrValue::Bool(enabled) = value else {
                    return Some(None);
                };
                Some(Some((
                    slot,
                    if !enabled {
                        Modifier::Empty
                    } else if slot == FILL_MAX_WIDTH {
                        Modifier::FillMaxWidth
                    } else {
                        Modifier::FillMaxHeight
                    },
                )))
            }
            "weight" => Some(Some((WEIGHT, Modifier::Weight(float(value)?)))),
            "width" => Some(Some((WIDTH, Modifier::Width(float(value)?)))),
            "height" => Some(Some((HEIGHT, Modifier::Height(float(value)?)))),
            "padding" => Some(Some((PADDING, Modifier::Padding(float(value)?)))),
            "padding_role" => Some(Some((
                PADDING,
                Modifier::PaddingRole(
                    crate::SpaceRole::try_from(u16::try_from(integer(value)?).ok()?).ok()?,
                ),
            ))),
            "elevation" => Some(Some((ELEVATION, Modifier::Elevation(float(value)?)))),
            "corner_radius" => {
                let radius = float(value)?;
                Some(Some((
                    SHAPE,
                    Modifier::Shape {
                        top_start: radius,
                        top_end: radius,
                        bottom_end: radius,
                        bottom_start: radius,
                    },
                )))
            }
            // The node says how important its changes are. Which curve and how many
            // milliseconds that means is the design system's answer, and it is never
            // asked here.
            "motion" => Some(Some((
                MOTION,
                Modifier::Motion(
                    crate::MotionRole::try_from(u16::try_from(integer(value)?).ok()?).ok()?,
                ),
            ))),
            // What the surface is made of. Blur, tone or a flat fill is the running
            // design system's answer, and the node does not get to ask for one of them.
            "material" => Some(Some((
                MATERIAL,
                Modifier::Material(
                    crate::MaterialRole::try_from(u16::try_from(integer(value)?).ok()?).ok()?,
                ),
            ))),
            "shape_role" => Some(Some((
                SHAPE,
                Modifier::ShapeRole(
                    crate::ShapeRole::try_from(u16::try_from(integer(value)?).ok()?).ok()?,
                ),
            ))),
            // Colour crosses as the bits of a Paint, so a role and a literal colour take
            // the same path and there is one wire representation of colour.
            "background" => Some(Some((
                BACKGROUND,
                Modifier::Background(crate::Paint::from_bits(integer(value)? as u64)?),
            ))),
            // A border needs a width and a colour, which arrive as two attributes. The
            // half that arrives first is remembered so the pair can be written as one
            // modifier, whichever order the front end hands them over in.
            "border_width" | "border_color" => {
                let pending = self.pending_borders.entry(node_id).or_default();
                if name == "border_width" {
                    pending.0 = Some(float(value)?);
                } else {
                    pending.1 = Some(crate::Paint::from_bits(integer(value)? as u64)?);
                }
                let (Some(width), Some(paint)) = (pending.0, pending.1) else {
                    return Some(None);
                };
                Some(Some((BORDER, Modifier::Border { width, paint })))
            }
            "onclickable" => {
                let AttrValue::Listener(handler_id) = value else {
                    return Some(None);
                };
                Some(Some((
                    CLICKABLE,
                    Modifier::Clickable {
                        handler_id: *handler_id,
                    },
                )))
            }
            _ => None,
        }
    }

    /// Role tag 0 means "not sent", so a design property at its neutral value
    /// produces no record at all. A property that was set and then cleared still sends
    /// its zero once, which is what tells the Renderer to drop the override.
    fn should_write_optional_property(
        &mut self,
        node_id: u32,
        property: PropertyKind,
        neutral: bool,
    ) -> bool {
        let tag = property as u16;
        let index = if (FIRST_OPTIONAL_PROPERTY..=LAST_OPTIONAL_PROPERTY).contains(&tag) {
            u32::from(tag - FIRST_OPTIONAL_PROPERTY)
        } else if property == PropertyKind::Spans {
            SPANS_BIT
        } else {
            return true;
        };
        let bit = 1_u64 << index;
        let seen = self.optional_props.get(&node_id).copied().unwrap_or(0);
        if neutral {
            if seen & bit == 0 {
                return false;
            }
            self.optional_props.insert(node_id, seen & !bit);
        } else if seen & bit == 0 {
            self.optional_props.insert(node_id, seen | bit);
        }
        true
    }

    /// Writes a widget property. A name the schema does not know is a protocol error,
    /// kept for the end of the frame.
    pub(crate) fn set_property(&mut self, node_id: u32, name: &str, value: &AttrValue<'_>) {
        let Some(property) = property_kind(name) else {
            // Neither front end has a fallible place to report this from. Keep the
            // protocol error and surface it when the batch is finalized.
            self.error = Some(ProtocolError::InvalidProperty(0));
            return;
        };
        let value = match value {
            AttrValue::Text(value) => PropertyValue::String(value),
            AttrValue::Float(value) => PropertyValue::Float(*value as f32),
            AttrValue::Int(value) => PropertyValue::Integer(*value),
            AttrValue::Bool(value) => PropertyValue::Bool(*value),
            AttrValue::None => PropertyValue::None,
            // A drawing command list or a list of text runs. The front end compares it
            // before calling here, so an unchanged list never reaches this point and costs
            // no record.
            AttrValue::Bytes(bytes) => PropertyValue::Bytes(bytes),
            AttrValue::Listener(_) | AttrValue::Unsupported => return,
        };
        let neutral = matches!(
            value,
            PropertyValue::None | PropertyValue::Integer(0) | PropertyValue::Float(0.0)
        );
        if !self.should_write_optional_property(node_id, property, neutral) {
            return;
        }
        self.write(Mutation::SetProp {
            node_id,
            property,
            value,
        });
    }

    /// One past the last position under `parent`.
    pub(crate) fn end_of(&self, parent: u32) -> usize {
        self.children.get(&parent).map_or(0, Vec::len)
    }

    /// Stands a marker in a template's dynamic child until its content arrives, and says
    /// which marker it was.
    pub(crate) fn reserve_slot(&mut self, parent: u32) -> u32 {
        let marker = self.next_marker;
        self.next_marker = self.next_marker.checked_add(1).unwrap_or(1);
        self.children
            .entry(parent)
            .or_default()
            .push(Slot::Pending(marker));
        marker
    }

    /// Takes a template's marker out of `parent` and says where it stood.
    pub(crate) fn take_pending(&mut self, parent: u32, marker: u32) -> Option<usize> {
        let list = self.children.get_mut(&parent)?;
        let position = list
            .iter()
            .position(|slot| *slot == Slot::Pending(marker))?;
        list.remove(position);
        Some(position)
    }

    /// Where the given slot stands: the parent it hangs under and its position in that
    /// parent's list.
    pub(crate) fn locate(&self, slot: Slot) -> Option<(u32, usize)> {
        let parent = match slot {
            Slot::Node(node_id) => *self.parents.get(&node_id)?,
            Slot::Hole(element) => *self.placeholders.get(&element)?,
            Slot::Pending(_) => return None,
        };
        let position = self
            .children
            .get(&parent)?
            .iter()
            .position(|standing| *standing == slot)?;
        Some((parent, position))
    }

    /// How many drawn nodes stand before `position` under `parent`, which is the index
    /// the Renderer understands.
    fn drawn_before(&self, parent: u32, position: usize) -> u32 {
        self.children.get(&parent).map_or(0, |list| {
            list.iter()
                .take(position)
                .filter(|slot| matches!(slot, Slot::Node(_)))
                .count() as u32
        })
    }

    /// Takes the slot out of wherever it stands, and says where that was.
    pub(crate) fn detach(&mut self, slot: Slot) -> Option<(u32, usize)> {
        let (parent, position) = self.locate(slot)?;
        if let Some(list) = self.children.get_mut(&parent) {
            list.remove(position);
        }
        Some((parent, position))
    }

    /// Puts the slot under `parent` at `position`, and says which position it took.
    fn attach(&mut self, parent: u32, slot: Slot, position: usize) -> usize {
        let mut position = position;
        if let Some((from, was_at)) = self.detach(slot) {
            if from == parent && was_at < position {
                position -= 1;
            }
        }
        let list = self.children.entry(parent).or_default();
        let position = position.min(list.len());
        list.insert(position, slot);
        match slot {
            Slot::Node(node_id) => {
                self.parents.insert(node_id, parent);
            }
            Slot::Hole(element) => {
                self.placeholders.insert(element, parent);
            }
            Slot::Pending(_) => {}
        }
        position
    }

    /// Forgets a node and everything under it, as the Renderer does when it is removed.
    /// The caller has already taken the node out of its parent's list.
    pub(crate) fn forget(&mut self, node_id: u32) {
        self.parents.remove(&node_id);
        for slot in self.children.remove(&node_id).unwrap_or_default() {
            match slot {
                Slot::Node(child) => self.forget(child),
                Slot::Hole(element) => {
                    self.placeholders.remove(&element);
                }
                Slot::Pending(_) => {}
            }
        }
    }

    /// Forgets a placeholder, wherever it stood.
    pub(crate) fn forget_hole(&mut self, element: usize) {
        self.detach(Slot::Hole(element));
        self.placeholders.remove(&element);
    }

    /// Takes a drawn node and everything under it out of the tree, and tells the Renderer.
    pub(crate) fn remove_node(&mut self, node_id: u32) {
        self.detach(Slot::Node(node_id));
        self.write(Mutation::Remove { node_id });
        self.forget(node_id);
    }

    /// Puts one entry under `parent` at `position` and tells the Renderer about it,
    /// unless nothing is drawn for it. Says which position it took.
    ///
    /// `hole` names the placeholder when `node_id` is the placeholder id.
    pub(crate) fn insert_node(
        &mut self,
        parent: u32,
        node_id: u32,
        position: usize,
        hole: Option<usize>,
    ) -> usize {
        if node_id == PLACEHOLDER_NODE {
            // Nothing is drawn for a placeholder, so nothing is sent. It still holds its
            // position here, so the branch that fills it in takes that position rather
            // than the end of the list.
            let Some(element) = hole else {
                return position;
            };
            return self.attach(parent, Slot::Hole(element), position);
        }
        let moving = self.parents.contains_key(&node_id);
        let at = self.attach(parent, Slot::Node(node_id), position);
        let index = self.drawn_before(parent, at);
        self.write(if moving {
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
        });
        at
    }

    /// Places a node immediately after `after` under `parent`, or first when `after` is
    /// `None`, and says nothing when it is already somewhere after it.
    ///
    /// This is how a front end that knows the order its children came out in keeps the
    /// Renderer's order without recounting it. A node that stands anywhere past the one
    /// placed before it is left alone: whatever stands between the two is either about to
    /// be placed after it or about to be removed, and moving it would cost a record that
    /// the removal makes unnecessary. A node that stands before it has really moved, and
    /// gets one `Move`. A node that is not in the tree yet gets its `Insert`.
    pub(crate) fn place_after(&mut self, parent: u32, node_id: u32, after: Option<u32>) {
        let target = match after {
            Some(previous) => match self.position_of(parent, previous) {
                Some(position) => position + 1,
                None => 0,
            },
            None => 0,
        };
        if self.parents.get(&node_id) == Some(&parent) {
            // The common case is a node standing exactly where it belongs, so look there
            // before scanning the whole list.
            let standing = self.children.get(&parent).and_then(|list| list.get(target));
            if standing == Some(&Slot::Node(node_id)) {
                return;
            }
            if let Some(position) = self.position_of(parent, node_id) {
                if position >= target {
                    return;
                }
            }
        }
        self.insert_node(parent, node_id, target, None);
    }

    /// Where a drawn node stands under `parent`.
    fn position_of(&self, parent: u32, node_id: u32) -> Option<usize> {
        self.children
            .get(&parent)?
            .iter()
            .position(|slot| *slot == Slot::Node(node_id))
    }
}

fn property_kind(name: &str) -> Option<PropertyKind> {
    PropertyKind::from_name(name).ok()
}

/// The property an event listener's handler id is written under.
pub(crate) fn event_property(name: &str) -> Option<PropertyKind> {
    match name {
        "click" | "onclick" => Some(PropertyKind::OnClick),
        "valuechange" | "onvaluechange" => Some(PropertyKind::OnValueChange),
        "submit" | "onsubmit" => Some(PropertyKind::OnSubmit),
        "focuslost" | "onfocuslost" => Some(PropertyKind::OnFocusLost),
        "keydown" | "onkeydown" => Some(PropertyKind::OnKeyDown),
        "rangerequest" | "onrangerequest" => Some(PropertyKind::OnRangeRequested),
        "dismiss" | "ondismiss" => Some(PropertyKind::OnDismiss),
        "filesentered" | "onfilesentered" => Some(PropertyKind::OnFilesEntered),
        "filesdropped" | "onfilesdropped" => Some(PropertyKind::OnFilesDropped),
        "change" | "onchange" => Some(PropertyKind::OnValueChange),
        _ => None,
    }
}
