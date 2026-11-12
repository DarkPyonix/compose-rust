use compose_rust::Composable;

#[Composable]
fn Field(value: String, #[default(false)] single_line: bool) {
    let _ = (value, single_line);
}

fn main() {
    Field!(single_line = true);
}
