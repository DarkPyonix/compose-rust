//! The Dioxus path's runs of the slot sweep.
//!
//! The screen is the one the recomposition experiment measured: a column of texts that
//! all read the same counter, and the button that moves it. Its width is set before each
//! scenario starts, because a root component is a plain function with nowhere else to
//! take an argument from.

use compose_rust::prelude::*;
use fr39_scenarios::{App, Batch, Event, Path, SLOT_SWEEP};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::Duration;

static SLOTS: AtomicUsize = AtomicUsize::new(1);

fn sweep() -> Element {
    let mut count = use_signal(|| 0_u64);
    let slots = SLOTS.load(Ordering::Relaxed);
    rsx! {
        Column {
            for slot in 0..slots {
                Text { key: "{slot}", text: format!("{}-{slot}", count()) }
            }
            Button { text: "Increment", on_click: move |_| *count.write() += 1 }
        }
    }
}

/// Sets the width before handing over to the Host, so each scenario starts its own.
struct Sweep(fr39_baseline_driver::DioxusPath);

impl Path for Sweep {
    fn start(&mut self, app: App) -> Batch {
        if let App::Sweep(slots) = app {
            SLOTS.store(slots, Ordering::Relaxed);
        }
        self.0.start(app)
    }
    fn dispatch(&mut self, event: &Event) -> (Batch, Duration) {
        self.0.dispatch(event)
    }
    fn frame(&mut self, frame_time_nanos: u64) -> (Batch, Duration) {
        self.0.frame(frame_time_nanos)
    }
    fn frame_requested(&self) -> bool {
        self.0.frame_requested()
    }
}

fn main() {
    fr39_baseline_driver::prepare_environment();
    let mut path = Sweep(fr39_baseline_driver::DioxusPath::new(sweep));
    let apps: Vec<App> = SLOT_SWEEP.iter().map(|slots| App::Sweep(*slots)).collect();
    fr39_scenarios::main_for(&mut path, "dioxus", &apps);
}
