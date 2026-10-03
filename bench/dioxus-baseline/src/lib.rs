//! The Dioxus baseline: every sample application's `rsx!` screen, under one name.
//!
//! The screens themselves stay in `samples/`, where they build, run and record frames the
//! way they always have. This crate is how a comparison reaches all of them at once,
//! through the same adapter they were written against, without reaching into each sample
//! by path.

pub use dioxus_compose_adapter as adapter;

use dioxus_compose_adapter::Element;

/// One sample's root component, under the sample's name.
pub struct Screen {
    pub name: &'static str,
    pub app: fn() -> Element,
}

/// Every sample application, in the order `samples/README.md` lists them.
pub static SCREENS: [Screen; 11] = [
    Screen {
        name: "calculator",
        app: sample_calculator::app,
    },
    Screen {
        name: "notepad",
        app: sample_notepad::app,
    },
    Screen {
        name: "todo",
        app: sample_todo::app,
    },
    Screen {
        name: "chat",
        app: sample_chat::app,
    },
    Screen {
        name: "minimal",
        app: sample_minimal::app,
    },
    Screen {
        name: "store",
        app: sample_store::app,
    },
    Screen {
        name: "statistics",
        app: sample_statistics::app,
    },
    Screen {
        name: "selfcare",
        app: sample_selfcare::app,
    },
    Screen {
        name: "podcast",
        app: sample_podcast::app,
    },
    Screen {
        name: "academic",
        app: sample_academic::app,
    },
    Screen {
        name: "social",
        app: sample_social::app,
    },
];

/// The screen with this name, if there is one.
pub fn screen(name: &str) -> Option<&'static Screen> {
    SCREENS.iter().find(|screen| screen.name == name)
}

#[cfg(test)]
mod tests {
    use super::*;
    use dioxus_compose_adapter::Host;

    /// Every baseline screen builds a first batch through the adapter it is pinned to. A
    /// screen that no longer encodes is not a baseline anything can be compared with.
    #[test]
    fn fr39_every_baseline_screen_builds_through_the_adapter() {
        for screen in &SCREENS {
            let mut host = Host::new(screen.app);
            let batch = host.rebuild();
            assert!(
                batch.is_ok_and(|bytes| !bytes.is_empty()),
                "{} did not build a first batch",
                screen.name
            );
        }
    }

    #[test]
    fn fr39_the_baseline_names_each_sample_once() {
        for screen in &SCREENS {
            assert_eq!(
                SCREENS
                    .iter()
                    .filter(|other| other.name == screen.name)
                    .count(),
                1,
                "{} appears twice",
                screen.name
            );
            assert!(std::ptr::eq(super::screen(screen.name).unwrap(), screen));
        }
    }
}
