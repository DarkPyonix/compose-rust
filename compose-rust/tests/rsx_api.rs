use compose_rust::prelude::*;
use compose_rust::protocol::{Mutation, decode_batch};
use compose_rust::{Host, WidgetKind};

fn app() -> Element {
    rsx! {
        compose_rust::Box {
            fill_max_width: true,
            Text { text: "qualified Box" }
        }
    }
}

#[test]
fn compose_names_and_snake_case_attributes_compile() {
    let mut host = Host::new(app);
    let mutations = decode_batch(host.rebuild().unwrap()).unwrap();
    assert!(mutations.iter().any(|mutation| matches!(
        mutation,
        Mutation::Create {
            widget: WidgetKind::Box,
            ..
        }
    )));
    assert!(mutations.iter().any(|mutation| matches!(
        mutation,
        Mutation::SetModifier {
            modifier: Modifier::FillMaxWidth,
            ..
        }
    )));
}
