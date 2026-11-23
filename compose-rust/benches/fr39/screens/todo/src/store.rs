//! The task list's data: a task, and the three filters over the list.
//!
//! Measurement fixture. Taken from `samples/todo/src/store.rs` at fea50dc5, the last
//! commit before e92b33a6 redrew the todo sample on 2026-11-13, and checked against the
//! rsx! screen the Dioxus path measures (dioxus-compose dd1af76b,
//! `samples/native-widgets/todo/src/store.rs`). The task model and the filters are the
//! same on both paths; no place here was decided by the rsx! screen.
//!
//! It lives in the bench because both paths must draw the same screen. The shipped sample
//! was redrawn and no longer builds the tree the pinned path builds, and what is measured
//! is the authoring path and the runtime, not today's sample. Change nothing that changes
//! the tree.
//!
//! The file the sample saves to, its loading and its writer are left out. The scenarios
//! start from an empty list and the Dioxus path never saves in the bench, so the list
//! lives in memory only.

use compose_rust::ui::IconRole;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Task {
    pub id: u64,
    pub title: String,
    pub done: bool,
}

/// `all`, `active` and `done`, in the order the filter strip shows them.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Filter {
    All,
    Active,
    Done,
}

impl Filter {
    pub const STRIP: [Filter; 3] = [Filter::All, Filter::Active, Filter::Done];

    pub fn label(self) -> &'static str {
        match self {
            Filter::All => "All",
            Filter::Active => "Active",
            Filter::Done => "Done",
        }
    }

    /// Where this filter sits in the destination set, which is also where `STRIP` has it.
    pub fn index(self) -> usize {
        match self {
            Filter::All => 0,
            Filter::Active => 1,
            Filter::Done => 2,
        }
    }

    /// What the destination for this filter means, so the Renderer can draw its own icon.
    pub fn icon(self) -> IconRole {
        match self {
            Filter::All => IconRole::List,
            Filter::Active => IconRole::Inbox,
            Filter::Done => IconRole::Check,
        }
    }

    pub fn accepts(self, task: &Task) -> bool {
        match self {
            Filter::All => true,
            Filter::Active => !task.done,
            Filter::Done => task.done,
        }
    }
}
