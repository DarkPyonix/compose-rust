use compose_rust::Composable;

struct Screen;

impl Screen {
    #[Composable(nonrestartable)]
    fn show(label: String) {
        let _ = label;
    }
}

fn main() {
    Screen::show(String::new());
    show!(String::new());
}
