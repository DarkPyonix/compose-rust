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
use std::cell::{Cell, RefCell};

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
    /// Ids the application let go of, waiting for the batch that tells the Renderer.
    static RELEASES: RefCell<Vec<u32>> = const { RefCell::new(Vec::new()) };
    /// Fonts set aside by [`lazy_asset`]: an id handed out, the bytes kept here until the
    /// Renderer asks for them. An entry stays after its bytes have been sent, so a font
    /// let go of and needed again goes out again under the same id.
    static LAZY: RefCell<Vec<(&'static [u8], u32, AssetKind)>> = const { RefCell::new(Vec::new()) };
    /// The last id handed out. Counted here and not read off the registry's length,
    /// because a released id leaves a hole and the next registration must not reuse it:
    /// a stale `Paint` would then silently name a different brush.
    static LAST_ID: Cell<u32> = const { Cell::new(0) };
}

/// The id [`asset`] returns for a registered picture, and what an `Image` or an icon
/// names it by.
pub type AssetId = u32;

/// Registers one picture and returns the id an [`Image`](fn@crate::ui::Image) draws it by.
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
    // Bytes set aside by `lazy_asset` already have their id, and font lists name them by
    // it, so registering them now sends them under that id rather than a new one.
    let set_aside = LAZY.with_borrow(|lazy| registered_id(lazy, bytes));
    // Ids start at one. Zero is what a node with no asset property reads as, so handing it
    // out would make "no picture" and "the first picture" the same value on the wire.
    let asset_id = REGISTERED.with_borrow_mut(|registered| {
        let asset_id = set_aside.unwrap_or_else(|| {
            let asset_id = LAST_ID.get() + 1;
            LAST_ID.set(asset_id);
            asset_id
        });
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

/// Sets a font aside: returns the id [`asset`] would give the same bytes, and sends
/// nothing until the Renderer asks for it.
///
/// A Korean face is over a megabyte and an application's screens use a small part of it.
/// Named as `FontRef::LazyAsset(id)` in a font list, or as a family of a
/// [`FontStack::lazy_family`](crate::FontStack::lazy_family), the face is looked for only
/// where a letter is missing from every font before it. The Renderer then asks for the
/// bytes, and they go out on the batch that answers. Until they arrive, such a letter is
/// drawn as a missing glyph, never in a face of the machine's own.
///
/// ```ignore
/// static SUBSET: &[u8] = include_bytes!("../assets/fonts/Pretendard-KSX1001.otf");
/// static FULL: &[u8] = include_bytes!("../assets/fonts/Pretendard-Regular.otf");
///
/// let fonts = FontRefs::new([
///     FontRef::Asset(asset(AssetKind::Font, SUBSET)),
///     FontRef::LazyAsset(lazy_asset(AssetKind::Font, FULL)),
/// ]);
/// ```
///
/// Bytes already registered with [`asset`] give that registration's id, and the candidate
/// is a font the Renderer already holds. Calling [`asset`] with these bytes later sends
/// them under the id this returned. Like [`asset`], the same bytes give the same id every
/// time, so this can run in a component body.
pub fn lazy_asset(kind: AssetKind, bytes: &'static [u8]) -> u32 {
    if let Some(id) = REGISTERED.with_borrow(|registered| registered_id(registered, bytes)) {
        return id;
    }
    if let Some(id) = LAZY.with_borrow(|lazy| registered_id(lazy, bytes)) {
        return id;
    }
    let asset_id = LAST_ID.get() + 1;
    LAST_ID.set(asset_id);
    LAZY.with_borrow_mut(|lazy| lazy.push((bytes, asset_id, kind)));
    asset_id
}

/// The Renderer needs the bytes of `asset_id`: queues its registration for the next batch.
///
/// Returns false for an id the Host has nothing for, which is the Renderer's mistake and
/// queues nothing. An id whose registration is already waiting for its batch queues
/// nothing more. An id the Host has sent before is sent again: the Renderer asks only for
/// what it does not hold, as after a release.
pub(crate) fn want(asset_id: u32) -> bool {
    let registered = REGISTERED.with_borrow(|registered| {
        registered
            .iter()
            .find(|(_, id, _)| *id == asset_id)
            .map(|(bytes, _, kind)| (*bytes, *kind))
    });
    let (bytes, kind) = match registered {
        Some(found) => found,
        None => {
            let Some(set_aside) = LAZY.with_borrow(|lazy| {
                lazy.iter()
                    .find(|(_, id, _)| *id == asset_id)
                    .map(|(bytes, _, kind)| (*bytes, *kind))
            }) else {
                return false;
            };
            REGISTERED.with_borrow_mut(|registered| {
                registered.push((set_aside.0, asset_id, set_aside.1));
            });
            set_aside
        }
    };
    QUEUE.with_borrow_mut(|queue| {
        if !queue.iter().any(|pending| pending.asset_id == asset_id) {
            queue.push(PendingAsset {
                asset_id,
                kind,
                bytes,
            });
        }
    });
    // A release of the same id still waiting would follow the registration in the batch
    // and take the font straight back out.
    RELEASES.with_borrow_mut(|releases| releases.retain(|id| *id != asset_id));
    true
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

/// Lets go of bytes registered with [`register_owned`] (or [`asset`]), so the Renderer
/// drops its copy.
///
/// A screen that builds its pictures at run time, a picture per colour an icon is drawn
/// in for example, would otherwise grow the Renderer's cache for the life of the app.
/// The bytes leave the registry, so registering the same bytes again gives a new id and
/// sends them again. The release goes out on the next batch, as [`crate::release_brush`]
/// sends one. Returns false, and sends nothing, for an id that is not registered as
/// `kind` or was already released. Releasing a picture a node still draws is the caller's
/// mistake, reported by the Renderer as a protocol error rather than a crash.
pub fn release_owned(asset_id: u32, kind: AssetKind) -> bool {
    release(asset_id, kind)
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

/// Lets go of one registration and queues the release for the Renderer.
///
/// Returns whether anything was released: false for an id that was never registered or
/// was already released. A registration still waiting for its batch is simply dropped,
/// because the Renderer never heard of it and a release for it would be a protocol error.
/// The id is not handed out again, so a copy of the old `Paint` stays a dangling name
/// (reported as a protocol error) and never silently names a different asset.
pub(crate) fn release(asset_id: u32, kind: AssetKind) -> bool {
    let removed = REGISTERED.with_borrow_mut(|registered| {
        let at = registered
            .iter()
            .position(|(_, id, known)| *id == asset_id && *known == kind)?;
        registered.remove(at);
        Some(())
    });
    if removed.is_none() {
        return false;
    }
    let was_waiting = QUEUE.with_borrow_mut(|queue| {
        let before = queue.len();
        queue.retain(|pending| pending.asset_id != asset_id);
        queue.len() != before
    });
    if !was_waiting {
        RELEASES.with_borrow_mut(|releases| releases.push(asset_id));
    }
    true
}

/// Lets go of a registered font or picture, so the Renderer drops its copy.
///
/// ```ignore
/// let codicon = asset(AssetKind::Font, CODICON);
/// // ... the editor closes and nothing draws with it any more:
/// release_asset(codicon);
/// ```
///
/// The release rides out on the next batch as `ReleaseAsset`. Returns false for an id that
/// was never registered or was already released. A registration still waiting for its batch
/// is dropped instead, because the Renderer never heard of it. The id is not handed out
/// again: text that still names it falls back to the next font in its list, and the
/// Renderer reports a protocol error once. Registering the same bytes again gives a new id.
///
/// A font set aside with [`lazy_asset`] keeps its id and its bytes on the Host. Released
/// once it was sent, it goes out again under the same id when the Renderer next asks for
/// it. Released before it was ever asked for, it has nothing to let go of and returns false.
pub fn release_asset(asset_id: AssetId) -> bool {
    let kind = REGISTERED.with_borrow(|registered| {
        registered
            .iter()
            .find(|(_, id, _)| *id == asset_id)
            .map(|(_, _, kind)| *kind)
    });
    match kind {
        Some(kind) => release(asset_id, kind),
        None => false,
    }
}

/// Hands every queued release to `emit` and empties the queue.
pub(crate) fn drain_releases(mut emit: impl FnMut(u32)) {
    let mut taken = RELEASES.with_borrow_mut(std::mem::take);
    if taken.is_empty() {
        return;
    }
    for asset_id in &taken {
        emit(*asset_id);
    }
    taken.clear();
    RELEASES.with_borrow_mut(|releases| {
        if releases.is_empty() {
            *releases = taken;
        }
    });
}

/// What one thread has registered, for the thread the UI runs on to take over.
///
/// Bytes that live for the whole program, ids and kinds, so it can go to another thread.
pub(crate) struct Registrations {
    registered: Vec<(&'static [u8], u32, AssetKind)>,
    lazy: Vec<(&'static [u8], u32, AssetKind)>,
    last_id: u32,
}

/// What this thread has registered so far.
///
/// An application registers its window's icon and its theme's fonts while it builds what it
/// launches with, which is before there is a UI, and the theme and the window then name
/// them by id. Where the Host makes the UI thread, that was a different thread from the
/// one the UI is about to run on, and a registry kept per thread would leave those ids
/// naming nothing there, or naming whatever the UI registered first.
pub(crate) fn registrations() -> Registrations {
    Registrations {
        registered: REGISTERED.with_borrow(Clone::clone),
        lazy: LAZY.with_borrow(Clone::clone),
        last_id: LAST_ID.get(),
    }
}

/// Makes another thread's registrations this thread's, ids unchanged, if this thread has
/// registered nothing of its own. The Host that starts here then tells the Renderer all of
/// them (`requeue_all`), and the next id handed out here follows the last one handed out
/// there.
pub(crate) fn adopt(registrations: Registrations) {
    if LAST_ID.get() != 0 {
        return;
    }
    LAST_ID.set(registrations.last_id);
    REGISTERED.with_borrow_mut(|registered| *registered = registrations.registered);
    LAZY.with_borrow_mut(|lazy| *lazy = registrations.lazy);
    requeue_all();
}

/// Forgets every registration and every id. Used between tests.
#[doc(hidden)]
pub fn reset_assets() {
    REGISTERED.with_borrow_mut(Vec::clear);
    QUEUE.with_borrow_mut(Vec::clear);
    RELEASES.with_borrow_mut(Vec::clear);
    LAZY.with_borrow_mut(Vec::clear);
    LAST_ID.set(0);
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
    // A new Renderer never held what was released, so telling it would be an error.
    RELEASES.with_borrow_mut(Vec::clear);
    if all.is_empty() {
        return;
    }
    QUEUE.with_borrow_mut(|queue| {
        // Anything already waiting is in `all` as well, because everything queued was
        // registered. Replacing rather than appending is what keeps it sent once.
        *queue = all;
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Releasing a brush: a release reaches the Renderer once, a registration
    /// that never left is dropped silently, and ids are never reused.
    #[test]
    fn fr23_released_brush_ids_are_sent_once_and_never_reused() {
        reset_assets();
        let kept = register_owned(AssetKind::Brush, vec![1, 2, 3]);
        let sent = register_owned(AssetKind::Brush, vec![4, 5, 6]);
        drain(|_| {});
        let waiting = register_owned(AssetKind::Brush, vec![7, 8, 9]);

        assert!(release(sent, AssetKind::Brush));
        assert!(release(waiting, AssetKind::Brush));
        assert!(
            !release(sent, AssetKind::Brush),
            "second release is a no-op"
        );
        assert!(!release(kept, AssetKind::Png), "wrong kind is not released");

        let mut released = Vec::new();
        drain_releases(|id| released.push(id));
        assert_eq!(released, [sent], "only the registration the Renderer saw");
        drain(|pending| panic!("nothing should be waiting, found {}", pending.asset_id));

        let again = register_owned(AssetKind::Brush, vec![4, 5, 6]);
        assert!(again > waiting, "a released id is not handed out again");
        reset_assets();
    }
}
