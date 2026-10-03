# Sample apps

compose-rust has no sample applications at the moment. This directory holds only this
README until they return.

## Where the `rsx!` samples went

The twelve `rsx!` samples (academic, calculator, chat, frames, minimal, notepad, podcast,
selfcare, social, statistics, store, todo) live in
[dioxus-compose](https://github.com/DarkPyonix/dioxus-compose), together with the Dioxus
adapter they are written against and the Dioxus baseline built from them. There they are
native-widget `rsx!` examples, under `samples/native-widgets/<name>/`.

They moved because compose-rust carries no Dioxus code: an application that uses
compose-rust without Dioxus finds no `dioxus-*` crate in its build, and
`scripts/tests/core-has-no-dioxus.test.sh` fails if one comes back.

## When samples return

compose-rust's own samples return when its authoring API lands (#64,
`feature/compose-api`) and the samples are rewritten on it. That is expected on
2026-10-05 to 2026-10-06, and #85 tracks it.

## Sample releases

Sample releases are paused until #85 closes. `.github/workflows/samples.yml` runs only
when dispatched by hand, and then fails with a pointer to #85, so a `sample-v*` tag
publishes nothing in the meantime.
