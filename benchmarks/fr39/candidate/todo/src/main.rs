//! The slot table path's runs of the scenarios that use this sample.

fn main() {
    fr39_candidate_driver::prepare_environment();
    let mut path = fr39_candidate_driver::ComposePath::new(sample_todo_compose::app);
    fr39_scenarios::main_for(&mut path, "compose", &[fr39_scenarios::App::Todo]);
}
