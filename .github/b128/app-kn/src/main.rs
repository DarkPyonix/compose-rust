use compose_rust::protocol::{HostEvent, Mutation, PropertyValue, ProtocolError};
use compose_rust::schema::{EventPayload, PropertyKind, Window, WidgetKind};
use compose_rust::{Batch, LaunchBuilder, Runtime};

const COL: u32 = 1; const LABEL: u32 = 2; const FIELD: u32 = 3; const BTN: u32 = 4;
const HDR: u32 = 7; const ECHO: u32 = 5; const LIST: u32 = 6; const ROW0: u32 = 100;

struct App { batch: Batch, clicks: u32, dirty: bool, echo: String }

impl App {
    fn text(&mut self, id: u32, s: &str) {
        self.batch.write(Mutation::SetProp { node_id: id, property: PropertyKind::Text, value: PropertyValue::String(s) });
    }
}
impl Runtime for App {
    fn batch(&self) -> &Batch { &self.batch }
    fn batch_mut(&mut self) -> &mut Batch { &mut self.batch }
    fn rebuild(&mut self) {
        let mut w = Window::new();
        w.title = "B128 Kotlin/Native";
        w.width = 480; w.height = 640; w.min_width = 320; w.min_height = 320; w.resizable = true;
        self.batch.write(Mutation::SetWindow(w));
        for (id, k) in [(COL, WidgetKind::Column), (LABEL, WidgetKind::Text), (FIELD, WidgetKind::TextField),
                        (BTN, WidgetKind::Button), (ECHO, WidgetKind::Text), (LIST, WidgetKind::ScrollColumn), (HDR, WidgetKind::Text)] {
            self.batch.write(Mutation::Create { node_id: id, widget: k });
        }
        self.text(LABEL, "clicks: 0"); self.text(HDR, "Kotlin/Native"); self.batch.write(Mutation::SetProp { node_id: HDR, property: PropertyKind::FontSize, value: PropertyValue::Float(40.0) });
        self.text(BTN, "Click me");
        self.text(ECHO, "typed: ");
        self.batch.write(Mutation::SetProp { node_id: BTN, property: PropertyKind::OnClick, value: PropertyValue::Integer(1) });
        self.batch.write(Mutation::SetProp { node_id: FIELD, property: PropertyKind::OnValueChange, value: PropertyValue::Integer(2) });
        for i in 0..60u32 {
            self.batch.write(Mutation::Create { node_id: ROW0 + i, widget: WidgetKind::Text });
            self.text(ROW0 + i, &format!("List row {i} \u{d55c}\u{ae00}"));
            self.batch.write(Mutation::Insert { parent_id: LIST, node_id: ROW0 + i, index: i });
        }
        for (i, id) in [HDR, LABEL, FIELD, BTN, ECHO, LIST].into_iter().enumerate() {
            self.batch.write(Mutation::Insert { parent_id: COL, node_id: id, index: i as u32 });
        }
    }
    fn render(&mut self) {
        if self.dirty {
            self.dirty = false;
            let l = format!("clicks: {}", self.clicks);
            self.text(LABEL, &l);
            let e = format!("typed: {}", self.echo);
            self.text(ECHO, &e);
        }
    }
    fn handle_event(&mut self, event: &HostEvent<'_>) -> Result<i64, ProtocolError> {
        match &event.payload {
            EventPayload::Clicked => { self.clicks += 1; self.dirty = true; println!("event: click {}", self.clicks); }
            EventPayload::TextChanged(t) => { self.echo = (*t).to_owned(); self.dirty = true; println!("event: text {t}"); }
            _ => return Err(ProtocolError::InvalidValueKind(0)),
        }
        Ok(0)
    }
}
fn main() {
    LaunchBuilder::new().launch_runtime(|| Box::new(App { batch: Batch::new(), clicks: 0, dirty: false, echo: String::new() }) as Box<dyn Runtime>);
}
