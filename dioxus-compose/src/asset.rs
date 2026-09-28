//! Pictures: registering the bytes once and drawing them by id.
//!
//! An asset is not part of the tree. It is bytes that have to reach the Renderer before
//! anything can draw them and have to outlive the frame that does, which is the opposite
//! of a node: a node is re-sent whenever it changes and points at nothing after the call
//! that carried it.
//!
//! So an asset is registered and forgotten, the way a message is posted and forgotten. It
//! rides out on the batch the current call produces, and everything after that is the id.

use crate::schema::AssetKind;
use std::cell::RefCell;

/// One registration, waiting for the batch that carries it.
pub(crate) struct PendingAsset {
    pub(crate) asset_id: u32,
    pub(crate) kind: AssetKind,
    pub(crate) bytes: &'static [u8],
}

thread_local! {
    /// What has been registered, so the same picture asked for twice is one registration.
    ///
    /// The kind is kept beside the bytes because a new Renderer has to be told all of this
    /// again, and a registration is bytes, an id and what the bytes are.
    static REGISTERED: RefCell<Vec<(&'static [u8], u32, AssetKind)>> =
        const { RefCell::new(Vec::new()) };
    static QUEUE: RefCell<Vec<PendingAsset>> = const { RefCell::new(Vec::new()) };
}

/// Registers one picture and returns the id an [`crate::Image`] draws it by.
///
/// ```ignore
/// static HERO: &[u8] = include_bytes!("../assets/hero.svg");
///
/// rsx! { Image { asset_id: asset(AssetKind::Svg, HERO), height: 240.0 } }
/// ```
///
/// Call it wherever the picture is drawn, including in a component body that runs every
/// render. The second call with the same bytes returns the first call's id and queues
/// nothing, so a screen that draws the same picture every frame registers it once.
///
/// The bytes are `'static` because a registration is carried by whichever batch the
/// current call produces, which is not always the call this was made in, and because
/// `include_bytes!` is what an application almost always has.
pub fn asset(kind: AssetKind, bytes: &'static [u8]) -> u32 {
    if let Some(id) = REGISTERED.with_borrow(|registered| registered_id(registered, bytes)) {
        return id;
    }
    // Ids start at one. Zero is what a node with no asset property reads as, so handing it
    // out would make "no picture" and "the first picture" the same value on the wire.
    let asset_id = REGISTERED.with_borrow_mut(|registered| {
        let asset_id = registered.len() as u32 + 1;
        registered.push((bytes, asset_id, kind));
        asset_id
    });
    QUEUE.with_borrow_mut(|queue| {
        queue.push(PendingAsset {
            asset_id,
            kind,
            bytes,
        });
    });
    asset_id
}

/// Registers bytes this process built rather than bytes it was compiled with.
///
/// A gradient is worked out at run time, so there is no `include_bytes!` to point at. The
/// bytes are handed to the registry to keep for the life of the process, which is what
/// `'static` means here and what a registration is anyway: an asset has to outlive every
/// frame that draws it, and nothing ever un-registers by dropping.
///
/// The content comparison runs first, so building the same gradient on every render keeps
/// one copy. Only a brush nobody has registered before is kept.
pub fn register_owned(kind: AssetKind, bytes: Vec<u8>) -> u32 {
    if let Some(id) = REGISTERED.with_borrow(|registered| {
        registered
            .iter()
            .find(|(known, _, _)| *known == bytes.as_slice())
            .map(|(_, id, _)| *id)
    }) {
        return id;
    }
    asset(kind, Box::leak(bytes.into_boxed_slice()))
}

/// The id these bytes were registered under, by address first and by content after.
///
/// Address first because that is the answer almost every time and it costs a comparison.
/// Content after because the same picture really can arrive at two addresses: a `const`
/// holding a reference is inlined at each use, and each use can get an allocation of its
/// own, so a catalogue written as a `const` array hands out two pointers to one file and
/// the shop registers the same cover twice under two ids. The full comparison only runs
/// when the address misses, which is once per new picture and never in the steady state.
fn registered_id(
    registered: &[(&'static [u8], u32, AssetKind)],
    bytes: &'static [u8],
) -> Option<u32> {
    registered
        .iter()
        .find(|(known, _, _)| std::ptr::eq(*known, bytes))
        .or_else(|| registered.iter().find(|(known, _, _)| *known == bytes))
        .map(|(_, id, _)| *id)
}

/// Hands every queued registration to `emit` and empties the queue.
///
/// The buffer is moved out and put back, so the steady state, where nothing new has been
/// registered, touches an empty `Vec` and allocates nothing.
pub(crate) fn drain(mut emit: impl FnMut(&PendingAsset)) {
    let mut taken = QUEUE.with_borrow_mut(std::mem::take);
    if taken.is_empty() {
        return;
    }
    for pending in &taken {
        emit(pending);
    }
    taken.clear();
    QUEUE.with_borrow_mut(|queue| {
        if queue.is_empty() {
            *queue = taken;
        }
    });
}

/// Forgets every registration and every id. Used between tests.
#[doc(hidden)]
pub fn reset_assets() {
    REGISTERED.with_borrow_mut(Vec::clear);
    QUEUE.with_borrow_mut(Vec::clear);
}

/// Queues every registration again, for a Renderer that has not been told any of them.
///
/// What a new `Host` does instead of forgetting them. A new Host means a Renderer whose
/// cache is empty, so an id handed out before it names nothing over there, and the cure
/// for that is to say all of them again rather than to drop them.
///
/// Dropping them was wrong in a way that only shows on the screen. A theme names its fonts
/// by id and a window names its icon by id, and both are built by the caller before the
/// Host is made out of them: the ids travelled and the bytes they named had been thrown
/// away, so the window wore no icon and the screen was set in the machine's own face while
/// every batch looked correct.
///
/// Ids do not move. The same bytes keep the id they were given, which is what lets a theme
/// built before this still name the right thing after it.
pub(crate) fn requeue_all() {
    let all: Vec<PendingAsset> = REGISTERED.with_borrow(|registered| {
        registered
            .iter()
            .map(|(bytes, asset_id, kind)| PendingAsset {
                asset_id: *asset_id,
                kind: *kind,
                bytes,
            })
            .collect()
    });
    if all.is_empty() {
        return;
    }
    QUEUE.with_borrow_mut(|queue| {
        // Anything already waiting is in `all` as well, because everything queued was
        // registered. Replacing rather than appending is what keeps it sent once.
        *queue = all;
    });
}
