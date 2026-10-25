use compose_rust::Composable;

#[Composable]
fn Field(value: String) {
    let _ = value;
}

fn main() {
    Field!(String::new(), { let _ = 1; });
}
