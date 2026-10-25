use compose_rust::Composable;

#[Composable]
fn List<T: Clone + PartialEq + 'static>(items: Vec<T>) {
    let _ = items;
}

fn main() {
    List!::<i32>(vec![1]);
}
