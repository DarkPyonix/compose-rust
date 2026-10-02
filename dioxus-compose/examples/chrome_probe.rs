//! A window asking for the platform's own title bar.
use dioxus_compose::prelude::*;

fn app() -> Element {
    rsx! {
        dioxus_compose::Box {
            fill_max_width: true,
            fill_max_height: true,
            background: Paint::Literal(Color::rgb(0xFFDD55)),
            alignment: Alignment::Center,
            Text { text: "system chrome", type_role: TypeRole::Headline }
        }
    }
}

fn main() {
    dioxus_compose::LaunchBuilder::new()
        .with_window(
            Window::new()
                .with_chrome(Chrome::System)
                .with_size(420, 300),
        )
        .launch(app);
}
