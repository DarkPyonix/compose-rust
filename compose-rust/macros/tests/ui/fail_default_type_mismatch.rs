use compose_rust::Composable;

#[Composable]
fn Field(#[default("not a bool")] single_line: bool) {
    let _ = single_line;
}

fn main() {
    Field!();
}
