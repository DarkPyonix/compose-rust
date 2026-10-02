//! What an application asks of its own window, and how it gets there.
//!
//! The window belongs to the Renderer. These are the few things it cannot guess, so they
//! ride the first batch as a record of the same kind as the theme: about the window
//! rather than a node, written once per rebuild. The Renderer reads the batch before it
//! stands the window up, which is the whole reason this does not need an argument on a
//! boundary function.

use dioxus_compose::prelude::*;
use dioxus_compose::protocol::{Mutation, decode_batch};
use dioxus_compose::{Host, LaunchBuilder, LoopMode};

fn app() -> Element {
    rsx! { Text { text: "a window" } }
}

/// What the window record carries, before and after an application says anything.
///
/// One test rather than two. The choice lives in a process-wide slot, because it is
/// settled once before the first window exists and never again, so a test that sets it
/// changes what every other test in the binary sees. Splitting these left the default
/// case reading the other one's answer.
///
/// `try_launch` under `LoopMode::Platform` opens nothing: it records the choice and
/// returns, which is what a platform-owned loop does, and it is what lets this exercise
/// the real path rather than a copy of it.
#[test]
fn fr19_3_the_window_crosses_as_the_application_asked_for_it() {
    fn window_in_the_first_batch(host: &mut Host) -> Window {
        let batch = host.rebuild().expect("the first tree encodes");
        decode_batch(batch)
            .expect("the batch did not decode")
            .into_iter()
            .find_map(|mutation| match mutation {
                Mutation::SetWindow(window) => Some(window),
                _ => None,
            })
            .expect(
                "no window record in the first batch, so the Renderer has nothing to \
                 build the window from",
            )
    }

    // The default is sent rather than left out. A Renderer that saw no record would have
    // to decide whether that meant modern chrome or a platform that never sends one.
    let mut host = Host::new(app);
    assert_eq!(
        window_in_the_first_batch(&mut host),
        Window::new(),
        "an application that asked for nothing did not get the default"
    );

    let asked = Window::new()
        .with_chrome(Chrome::System)
        .with_size(900, 640)
        .with_min_size(400, 300)
        .resizable(false);
    LaunchBuilder::new()
        .with_mode(LoopMode::Platform)
        .with_window(asked)
        .try_launch(app);

    let mut host = Host::new(app);
    assert_eq!(
        window_in_the_first_batch(&mut host),
        asked,
        "the window crossed the boundary as something other than what was asked for"
    );
}
