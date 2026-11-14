//! A notification posted from a worker thread: the worker only queues it, the Host asks the
//! Renderer for a frame, and the batch of that frame carries the notification out.
//!
//! Each `tests/*.rs` file is its own binary, so the process-global notification queue and
//! frame request counter these tests read belong to this file alone.

use compose_rust::boundary::STATUS_OK;
use compose_rust::protocol::{Mutation, decode_batch};
use compose_rust::runtime::*;
use compose_rust::ui::*;
use compose_rust::{
    ComposeHost, Notification, NotificationSender, RendererApi, install_renderer_api,
};
use std::ffi::c_int;
use std::sync::atomic::{AtomicUsize, Ordering};

static FRAME_REQUESTS: AtomicUsize = AtomicUsize::new(0);

extern "C" fn count_request() {
    FRAME_REQUESTS.fetch_add(1, Ordering::AcqRel);
}

extern "C" fn never_run() -> c_int {
    STATUS_OK as c_int
}

#[Composable]
fn Screen() {
    Text("session running");
}

/// The key, title and body of every notification `batch` posts.
fn posted(batch: &[u8]) -> Vec<(String, String, String)> {
    decode_batch(batch)
        .expect("the batch decodes")
        .iter()
        .filter_map(|mutation| match mutation {
            Mutation::PostNotification {
                key, title, body, ..
            } => Some(((*key).to_owned(), (*title).to_owned(), (*body).to_owned())),
            _ => None,
        })
        .collect()
}

/// The worker's whole part is one call on the sender. Asking for the frame and writing the
/// record are the Host's, and the frame after that one has nothing left to say.
#[test]
fn fr36_a_worker_post_rides_the_next_batch() {
    let _ = install_renderer_api(RendererApi {
        run: never_run,
        request_frame: count_request,
        measure: compose_rust::measure::fake_measure,
    });
    let mut host = ComposeHost::with_content(Screen);
    let first = host.rebuild().expect("the first batch encodes").to_vec();
    assert!(posted(&first).is_empty(), "nothing was posted yet");
    FRAME_REQUESTS.store(0, Ordering::Release);

    let notifications = NotificationSender::new();
    std::thread::spawn(move || {
        notifications.post(
            Notification::new("Session finished")
                .key("session/7")
                .body("214 tests passed"),
        );
    })
    .join()
    .expect("the worker did not panic");

    assert_eq!(
        FRAME_REQUESTS.load(Ordering::Acquire),
        1,
        "the post asked the Renderer for one frame"
    );
    let batch = host.render_frame(0).expect("the frame encodes").to_vec();
    assert_eq!(
        posted(&batch),
        vec![(
            "session/7".to_owned(),
            "Session finished".to_owned(),
            "214 tests passed".to_owned(),
        )],
        "the frame the post asked for carries exactly that notification"
    );
    let after = host.render_frame(0).expect("the frame encodes").to_vec();
    assert!(posted(&after).is_empty(), "a notification goes out once");
}
