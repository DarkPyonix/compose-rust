use compose_rust::Composable;

mod ui {
    use compose_rust::Composable;

    #[Composable]
    pub fn Field(value: String) {
        let _ = value;
    }
}

use ui::Field as Input;

fn main() {
    // The function form works under the new name. The macro form finds the function by its
    // own name, which this scope does not have.
    Input(String::new());
    Input!(String::new());
}
