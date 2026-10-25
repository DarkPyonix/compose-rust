use compose_rust::Composable;
use compose_rust::ui::Modifier;

#[Composable]
pub fn Field(
    value: String,
    on_change: impl Fn(String) + 'static,
    modifier: Modifier,
    #[default(|| {})] label: impl Fn() + 'static,
    #[default(false)] single_line: bool,
    #[default(|| {})] content: impl Fn() + 'static,
) {
    let _ = (value, on_change, modifier, label, single_line, content);
}

#[Composable]
fn Required(first: i32, second: i32) {
    let _ = (first, second);
}

#[Composable]
fn Generic<T: Clone + PartialEq + 'static>(items: Vec<T>, #[default(Vec::new())] more: Vec<T>) {
    let _ = (items, more);
}

fn main() {
    // Function form: every argument, no defaults.
    Field(String::new(), |_| {}, Modifier::new(), || {}, true, || {});
    // Positional prefix, then names in any order; closures infer from the bounds.
    Field!(String::new(), |text| drop(text), single_line = true, label = || {});
    Field!(String::new(), on_change = |text| drop(text));
    // A trailing block is the content closure.
    Field!(String::new(), |text| drop(text), { let _ = 1; });
    Field!(String::new(), |text| drop(text), single_line = true, { let _ = 1; });
    Field!(content = || {}, on_change = |text| drop(text), value = String::new());
    // Parameters can be given by name alone, in any order.
    Required!(1, second = 2);
    Required!(second = 2, first = 1);
    // Generics are inferred, in both forms.
    Generic(vec![1, 2], Vec::new());
    Generic!(vec![1, 2]);
    Generic!(vec!["a"], more = vec!["b"]);
}
