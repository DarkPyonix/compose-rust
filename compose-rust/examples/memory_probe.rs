//! A window of a stated size, with a stated amount in it, for measuring what each costs.
//!
//! The question it answers is which part of a running application's memory follows the
//! window and which part follows what is on screen. A surface scales with the window; a
//! cache that is holding a working set does not, and a cache that is holding rubbish
//! follows neither.
//!
//! `DXC_PROBE_WIDTH`, `DXC_PROBE_HEIGHT` and `DXC_PROBE_ROWS` say what to draw. No rows is
//! an empty window.

use compose_rust::prelude::*;

fn number(name: &str, fallback: u32) -> u32 {
    std::env::var(name)
        .ok()
        .and_then(|value| value.trim().parse().ok())
        .unwrap_or(fallback)
}

fn text(name: &str, fallback: &str) -> String {
    std::env::var(name).unwrap_or_else(|_| fallback.to_owned())
}

fn app() -> Element {
    let rows = number("DXC_PROBE_ROWS", 0);
    // Each is a thing the calculator has and a grid of plain buttons does not, so they can
    // be turned on one at a time until the difference shows up.
    let kind = text("DXC_PROBE_KIND", "plain");
    let icons = kind == "icons" || kind == "all";
    let elevated = kind == "elevated" || kind == "all";
    let bar = kind == "bar" || kind == "all";
    rsx! {
        Column { fill_max_width: true, fill_max_height: true,
            if bar {
                TopAppBar { title: "Probe", fill_max_width: true,
                    Button { text: "", icon: IconRole::Menu, variant: ButtonVariant::Text, on_click: move |_| {} }
                    Button { text: "", icon: IconRole::History, variant: ButtonVariant::Text, on_click: move |_| {} }
                }
            }
            for row in 0..rows {
                Row { key: "{row}", fill_max_width: true, space_role: SpaceRole::Sm,
                    for column in 0..6 {
                        if elevated {
                            Surface {
                                key: "{column}",
                                weight: 1.0,
                                elevation: 4.0,
                                shape_role: ShapeRole::Large,
                                padding_role: SpaceRole::Sm,
                                Text { text: "{row}.{column}" }
                            }
                        } else if icons {
                            Button {
                                key: "{column}",
                                text: "",
                                icon: IconRole::Add,
                                weight: 1.0,
                                on_click: move |_| {},
                            }
                        } else {
                            Button {
                                key: "{column}",
                                text: "{row}.{column}",
                                weight: 1.0,
                                on_click: move |_| {},
                            }
                        }
                    }
                }
            }
        }
    }
}

fn main() {
    let window = compose_rust::schema::Window::new().with_size(
        number("DXC_PROBE_WIDTH", 800) as u16,
        number("DXC_PROBE_HEIGHT", 600) as u16,
    );
    compose_rust::LaunchBuilder::new()
        .with_theme(compose_rust::demo_theme())
        .with_window(window)
        .launch(app);
}
