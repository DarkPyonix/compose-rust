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

fn main() {
    dioxus_compose::launch(app);
}
