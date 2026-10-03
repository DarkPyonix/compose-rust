//! A window asking for the platform's own title bar.
use compose_rust::prelude::*;

fn app() -> Element {
    rsx! {
        compose_rust::Box {
            fill_max_width: true,
            fill_max_height: true,
            background: Paint::Literal(Color::rgb(0xFFDD55)),
            alignment: Alignment::Center,
            Text { text: "system chrome", type_role: TypeRole::Headline }
        }
    }
}

fn main() {
    compose_rust::LaunchBuilder::new()
        .with_window(
            Window::new()
                .with_chrome(Chrome::System)
                .with_size(420, 300),
        )
        .launch(app);
}
