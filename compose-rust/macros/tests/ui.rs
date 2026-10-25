//! What the generated call macro accepts and what it refuses, compiled the way an
//! application compiles it. The `.stderr` files next to the fixtures are the messages a
//! caller reads, so a change to one is a change to what the macro tells its users.

#[test]
fn fr39_call_macro_compile_fail_points_at_the_offending_argument() {
    let cases = trybuild::TestCases::new();
    cases.pass("tests/ui/pass_*.rs");
    cases.compile_fail("tests/ui/fail_*.rs");
}
