use compose_rust::Composable;

#[Composable]
fn Panel(#[default(|| {})] content: impl Fn() + 'static) {
    content();
}

fn main() {
    Panel!(content = || {}, { let _ = 1; });
}
