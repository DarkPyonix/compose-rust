//! What `#[non_exhaustive]` on the event types asks of code outside compose-rust, compiled
//! the way an application compiles it. A `match` on `EventPayload` needs a `_` arm, a
//! `HostEvent` is built with `HostEvent::new` and destructured with `..`.
//!
//! The fixtures live in this crate because it already builds compose-rust as a dependency
//! with the features every other member uses, which a second trybuild harness in
//! compose-rust itself would not.

#[test]
fn fr41_event_enums_are_non_exhaustive_outside_the_crate() {
    let cases = trybuild::TestCases::new();
    cases.pass("tests/event_enums/pass_*.rs");
    cases.compile_fail("tests/event_enums/fail_*.rs");
}
