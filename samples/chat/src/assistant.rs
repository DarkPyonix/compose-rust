//! The fake assistant.
//!
//! There is no network here. What matters is the shape of the work rather than where the
//! tokens come from: a reply is worked out on a worker, then arrives a few characters at a
//! time over a stretch of time long enough that the user is still scrolling and typing
//! while it lands.
//!
//! The reply is a future on the chat's coroutine scope. Working the answer out goes
//! through `with_worker`, the way domain work leaves the UI thread, and the pause between
//! chunks is a `delay`, which waits without holding any thread. The sample starts no
//! thread of its own: a browser page cannot start one, and `with_worker` is what runs the
//! same code there. Nothing here calls a boundary function: writing the state queues it
//! for the scopes that read it, and the Host asks the Renderer for the next frame.

use compose_rust::runtime::{MutableState, delay, with_worker};
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::Duration;

use crate::Message;

/// How much text lands at once. The gap between chunks comes from the speed the user set.
const CHUNK: usize = 3;

/// The beat before the first characters, the way a real model takes a moment.
const FIRST_TOKEN_DELAY: Duration = Duration::from_millis(220);

/// How much the assistant says.
///
/// A real model takes this as an instruction in the prompt. Here it is applied to the
/// canned answer afterwards, which is a different mechanism for the same visible thing:
/// the setting changes the reply, so it is a setting rather than a switch wired to
/// nothing.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Length {
    Brief,
    Normal,
    Detailed,
}

impl Length {
    pub const ALL: [Self; 3] = [Self::Brief, Self::Normal, Self::Detailed];

    pub fn label(self) -> &'static str {
        match self {
            Self::Brief => "Brief",
            Self::Normal => "Normal",
            Self::Detailed => "Detailed",
        }
    }
}

/// The slowest and fastest the reply is allowed to arrive, in characters a second.
///
/// The default is close to what a hosted model feels like, and the range reaches far
/// enough either side of it that the difference is something you can watch rather than
/// something you have to measure.
pub const SLOWEST: f32 = 20.0;
pub const FASTEST: f32 = 600.0;
pub const DEFAULT_SPEED: f32 = 180.0;

/// How the assistant answers. Everything here changes what the worker does.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Settings {
    pub length: Length,
    /// Whether the reply is typed out or lands whole.
    pub streaming: bool,
    /// Characters a second while it is being typed out.
    pub speed: f32,
}

impl Default for Settings {
    fn default() -> Self {
        Self {
            length: Length::Normal,
            streaming: true,
            speed: DEFAULT_SPEED,
        }
    }
}

impl Settings {
    /// How long to wait between two chunks, from the speed in characters a second.
    fn gap(&self) -> Duration {
        Duration::from_secs_f32(CHUNK as f32 / self.speed.clamp(SLOWEST, FASTEST))
    }
}

/// Cancellation, shared with the UI thread.
///
/// Every reply is started with a token. Sending again bumps the counter, and a worker whose
/// token is no longer the current one stops at its next chunk instead of appending to a
/// message that is no longer the last one.
#[derive(Clone, Default)]
pub struct Turns(Arc<AtomicU64>);

impl Turns {
    pub fn begin(&self) -> u64 {
        self.0.fetch_add(1, Ordering::AcqRel) + 1
    }

    fn is_current(&self, token: u64) -> bool {
        self.0.load(Ordering::Acquire) == token
    }
}

/// Reply text for a prompt and a length.
///
/// Brief is the first sentence, which is what "be brief" gets you from a real model.
/// Detailed adds a closing paragraph. Canned either way, but picked from the prompt so the
/// answers are not all the same wall of words.
fn reply_to(prompt: &str, length: Length) -> String {
    let full = full_reply(prompt);
    match length {
        Length::Brief => first_sentence(&full),
        Length::Normal => full,
        Length::Detailed => format!(
            "{full}\n\nOne more thing, since you asked for detail. Everything above \
arrived a few characters at a time while you could still scroll and type, and the only \
reason it could is that writing the shared list is all the reply does. It never calls \
across the boundary. The scheduler notices the write, the Host asks for a frame, and the \
frame carries the characters that landed since the last one."
        ),
    }
}

/// Up to and including the first full stop, or the whole thing if there is not one.
fn first_sentence(text: &str) -> String {
    match text.find(". ") {
        Some(end) => text[..=end].to_owned(),
        None => text.to_owned(),
    }
}

fn full_reply(prompt: &str) -> String {
    let lowered = prompt.to_lowercase();
    if lowered.contains("hello") || lowered.contains("hi ") || lowered.trim() == "hi" {
        return "Hello. I am a stand-in for a language model: there is no network behind \
me, only a worker working out an answer and a timer handing it to you a few characters at \
a time so the streaming path gets exercised for real. Ask me anything and I will answer at length, because a short \
answer would not tell you whether scrolling stays smooth."
            .to_owned();
    }
    if lowered.contains("thread") || lowered.contains("stream") {
        return "Streaming works like this. The UI thread pushes your message and an empty \
reply, then hands the prompt to a worker. The answer comes back, and a timer lets it out \
a few characters at a time, appending to the shared list. Writing the list marks the \
scope that reads it dirty, which wakes the scheduler, which asks for a frame. Nothing in \
the loop touches the boundary directly, and nothing waits on the UI thread."
            .to_owned();
    }
    if lowered.contains("code") || lowered.contains("rust") {
        return "Here is what a handler looks like:\n\n    Button {\n        text: \"Send\",\n\
        on_click: move |_| send(draft()),\n    }\n\nThat block is plain text in a Text \
widget. There is no code block widget and no monospace span inside a paragraph, so the \
indentation is doing all the work that a background and a border would normally do."
            .to_owned();
    }
    format!(
        "You said: \"{prompt}\". I do not know what that means, because I am four lines of \
string matching wearing a trench coat. What I can do is keep talking long enough for you \
to try scrolling the history, selecting some of this text, and typing your next message \
while the rest of this sentence is still arriving, which is the only part of this sample \
that was ever in doubt."
    )
}

/// The reply, as a future to launch on the chat's coroutine scope. The text arrives on
/// `messages` over the next second or so.
///
/// Every chunk whose time has come lands in one write. A frame that comes late therefore
/// carries several chunks at once instead of the reply falling behind the speed the user
/// set, and the Host sends that message's tail once for the frame.
pub async fn stream_reply(
    prompt: String,
    token: u64,
    turns: Turns,
    settings: Settings,
    messages: MutableState<Vec<Message>>,
) {
    let length = settings.length;
    let reply = with_worker(move || reply_to(prompt.trim(), length)).await;
    // The beat before the first characters, the way a real model takes a moment.
    delay(FIRST_TOKEN_DELAY).await;
    if !turns.is_current(token) {
        return;
    }
    if !settings.streaming {
        // Not typed out: the whole reply lands in one write, which is the shape a
        // non-streaming endpoint has.
        messages.update(|list| {
            if let Some(message) = list.last_mut() {
                if message.token == token {
                    message.text.push_str(&reply);
                    message.streaming = false;
                }
            }
        });
        return;
    }
    // Chunk on character boundaries, never on bytes: a reply cut through the middle of a
    // multi-byte character would not be valid UTF-8 to push.
    let characters: Vec<char> = reply.chars().collect();
    let gap = settings.gap();
    let clock = Clock::start().await;
    let mut sent = 0;
    while sent < characters.len() {
        if !turns.is_current(token) {
            return;
        }
        let elapsed = clock.elapsed().await;
        let chunks_due = (elapsed.as_nanos() / gap.as_nanos().max(1)) as usize + 1;
        let due = (chunks_due * CHUNK).min(characters.len());
        if due > sent {
            let tail: String = characters[sent..due].iter().collect();
            let mut appended = false;
            messages.update(|list| match list.last_mut() {
                // The append is the point. Replacing the message instead would make the
                // whole reply new text on every chunk.
                Some(message) if message.token == token => {
                    message.text.push_str(&tail);
                    appended = true;
                }
                _ => {}
            });
            if !appended {
                return;
            }
            sent = due;
        }
        if sent < characters.len() {
            delay(gap).await;
        }
    }
    if turns.is_current(token) {
        messages.update(|list| {
            if let Some(message) = list.last_mut() {
                message.streaming = false;
            }
        });
    }
}

/// How long the reply has been arriving.
///
/// A desktop or a phone reads the system's monotonic clock. A browser page has none that
/// Rust can read here (`std::time::Instant` panics on `wasm32-unknown-unknown`), so there
/// the time is the frame clock's, which is the clock `delay` counts on as well.
struct Clock {
    #[cfg(not(target_family = "wasm"))]
    started: std::time::Instant,
    #[cfg(target_family = "wasm")]
    started: u64,
}

impl Clock {
    #[cfg(not(target_family = "wasm"))]
    async fn start() -> Self {
        Self {
            started: std::time::Instant::now(),
        }
    }

    #[cfg(target_family = "wasm")]
    async fn start() -> Self {
        Self {
            started: compose_rust::runtime::with_frame_nanos(|now| now).await,
        }
    }

    #[cfg(not(target_family = "wasm"))]
    async fn elapsed(&self) -> Duration {
        self.started.elapsed()
    }

    #[cfg(target_family = "wasm")]
    async fn elapsed(&self) -> Duration {
        let now = compose_rust::runtime::with_frame_nanos(|now| now).await;
        Duration::from_nanos(now.saturating_sub(self.started))
    }
}
