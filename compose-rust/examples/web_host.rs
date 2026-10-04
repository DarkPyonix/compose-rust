//! The Host as a page in a browser, with a runtime written by hand.
//!
//! A message list: a title, the messages sent so far, a text field and a Send button. The
//! field reports each change, and Send appends whatever it holds as a new message and
//! clears it. There is no `main`. The Renderer's module owns the loop, so the runtime is
//! registered from `compose_rust_host_web_start`, which the page calls once both wasm
//! modules exist, and the generated shims carry every call after that.
//!
//! No authoring layer is involved, so this is what `renderer/web/scripts/build-host.sh`
//! builds by default: the module whose imports `scripts/tests/web-host-imports.test.sh`
//! checks, and the Host `renderer/web/test/WebBoundaryTest.kt` types into and clicks.
//!
//! ```sh
//! renderer/web/scripts/build-host.sh
//! ```

use compose_rust::protocol::{HostEvent, Mutation, PropertyValue, ProtocolError};
use compose_rust::schema::{EventPayload, PropertyKind, WidgetKind};
use compose_rust::{Batch, Runtime};

const COLUMN: u32 = 1;
const TITLE: u32 = 2;
const FIELD: u32 = 3;
const SEND: u32 = 4;
/// Messages take ids from here up, one each, in the order they were sent.
const FIRST_MESSAGE: u32 = 100;

const ON_VALUE_CHANGE: u64 = 1;
const ON_CLICK: u64 = 2;

struct Chat {
    batch: Batch,
    draft: String,
    messages: Vec<String>,
    /// How many of `messages` are already in the tree.
    shown: usize,
}

impl Chat {
    fn new() -> Self {
        Self {
            batch: Batch::new(),
            draft: String::new(),
            messages: Vec::new(),
            shown: 0,
        }
    }

    fn text(&mut self, node_id: u32, property: PropertyKind, text: &str) {
        self.batch.write(Mutation::SetProp {
            node_id,
            property,
            value: PropertyValue::String(text),
        });
    }

    fn handler(&mut self, node_id: u32, property: PropertyKind, handler_id: u64) {
        self.batch.write(Mutation::SetProp {
            node_id,
            property,
            value: PropertyValue::Integer(handler_id as i64),
        });
    }

    /// Writes the messages the tree does not have yet, each one above the field.
    fn show_new_messages(&mut self) {
        while self.shown < self.messages.len() {
            let node_id = FIRST_MESSAGE + self.shown as u32;
            self.batch.write(Mutation::Create {
                node_id,
                widget: WidgetKind::Text,
            });
            self.batch.write(Mutation::SetProp {
                node_id,
                property: PropertyKind::Text,
                value: PropertyValue::String(&self.messages[self.shown]),
            });
            // After the title and every message before this one.
            self.batch.write(Mutation::Insert {
                parent_id: COLUMN,
                node_id,
                index: 1 + self.shown as u32,
            });
            self.shown += 1;
        }
    }
}

impl Runtime for Chat {
    fn batch(&self) -> &Batch {
        &self.batch
    }

    fn batch_mut(&mut self) -> &mut Batch {
        &mut self.batch
    }

    fn rebuild(&mut self) {
        for (node_id, widget) in [
            (COLUMN, WidgetKind::Column),
            (TITLE, WidgetKind::Text),
            (FIELD, WidgetKind::TextField),
            (SEND, WidgetKind::Button),
        ] {
            self.batch.write(Mutation::Create { node_id, widget });
        }
        self.text(TITLE, PropertyKind::Text, "compose-rust chat");
        self.text(FIELD, PropertyKind::Placeholder, "Write a message");
        self.handler(FIELD, PropertyKind::OnValueChange, ON_VALUE_CHANGE);
        self.text(SEND, PropertyKind::Text, "Send");
        self.handler(SEND, PropertyKind::OnClick, ON_CLICK);
        for (index, node_id) in [TITLE, FIELD, SEND].into_iter().enumerate() {
            self.batch.write(Mutation::Insert {
                parent_id: COLUMN,
                node_id,
                index: index as u32,
            });
        }
        self.shown = 0;
        self.show_new_messages();
    }

    fn render(&mut self) {
        self.show_new_messages();
    }

    fn handle_event(&mut self, event: &HostEvent<'_>) -> Result<i64, ProtocolError> {
        match (event.node_id, event.handler_id, &event.payload) {
            (FIELD, ON_VALUE_CHANGE, EventPayload::TextChanged(value)) => {
                self.draft.clear();
                self.draft.push_str(value);
                Ok(0)
            }
            (SEND, ON_CLICK, EventPayload::Clicked) => {
                let message = self.draft.trim().to_owned();
                if !message.is_empty() {
                    self.messages.push(message);
                    self.draft.clear();
                    // The field is uncontrolled, so clearing it is a record the Renderer
                    // applies to its own state rather than a value this runtime holds.
                    self.batch.set_text_node(FIELD, "", None);
                }
                Ok(0)
            }
            _ => Err(ProtocolError::InvalidValueKind(0)),
        }
    }
}

/// The entry point the page calls. Exported only from the wasm module: off the web there
/// is no page to call it, and the same name in two binaries linked together would collide.
#[cfg(target_family = "wasm")]
#[unsafe(no_mangle)]
pub extern "C" fn compose_rust_host_web_start() -> u32 {
    compose_rust::__web_start(compose_rust::LaunchBuilder::new(), || {
        Box::new(Chat::new()) as Box<dyn Runtime>
    })
}

/// Off the web the runtime is still type checked, so a build on the machine you are
/// working on catches what a wasm build would.
#[cfg(not(target_family = "wasm"))]
#[allow(dead_code)]
fn unused_off_the_web() -> Box<dyn Runtime> {
    Box::new(Chat::new())
}
