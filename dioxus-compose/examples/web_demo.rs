//! The vertical slice as a page in a browser.
//!
//! The same Rust source as `desktop_demo`, with one difference: there is no `main`. The
//! Renderer's module owns the loop, so the root component is registered from
//! `dioxus_compose_host_web_start`, which the page calls once both wasm modules exist, and
//! the generated shims carry every call after that.
//!
//! Built as a wasm module the page fetches:
//! ```sh
//! dioxus-compose-renderer/web/scripts/build-host.sh
//! ```

use dioxus_compose::prelude::*;

fn app() -> Element {
    let mut messages = use_signal(Vec::<String>::new);
    let mut draft = use_signal(String::new);

    rsx! {
        Column {
            fill_max_width: true,
            Text { text: "dioxus-compose chat" }
            for message in messages() {
                Text { text: message }
            }
            TextField {
                placeholder: "Write a message",
                multiline: true,
                on_value_change: move |value| draft.set(value),
                on_key_down: move |event: KeyEvent| {
                    if event.key() == Key::Enter && !event.shift_key() {
                        let message = draft().trim().to_owned();
                        if !message.is_empty() {
                            messages.write().push(message);
                            draft.set(String::new());
                        }
                        event.consume();
                    }
                }
            }
            Button {
                text: "Send",
                on_click: move |_| {
                    let message = draft().trim().to_owned();
                    if !message.is_empty() {
                        messages.write().push(message);
                        draft.set(String::new());
                    }
                }
            }
        }
    }
}

dioxus_compose::web_main!(app);
