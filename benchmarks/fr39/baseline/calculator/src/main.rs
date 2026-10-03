//! The Dioxus path's runs of the scenarios that use this sample.

fn main() {
    fr39_baseline_driver::prepare_environment();
    let mut path = fr39_baseline_driver::DioxusPath::new(sample_calculator::app);
    fr39_scenarios::main_for(&mut path, "dioxus", &[fr39_scenarios::App::Calculator]);
}
