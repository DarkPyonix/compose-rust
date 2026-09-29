//! An LLM chat interface with no network, clone coded from Google Gemini.
//!
//! Conversations down the side, a scrollback of messages, a composer where Enter sends and
//! Shift+Enter starts a new line, and an assistant whose reply arrives a few characters at
//! a time from a worker thread while the user keeps scrolling and typing.
//!
//! The conversations are a destination set rather than a list this code lays out, so the
//! Renderer draws them as a bar along the bottom of a phone, a rail beside a tablet and the
//! reference's sidebar on a desktop, from one declaration.
//!
//! The scrollback is a `LazyColumn` so a long conversation costs widgets in proportion to
//! what is on screen. Each message carries an id that never changes and that id is its key,
//! so the message growing at the bottom does not disturb the ones above it.

mod assistant;

use assistant::{Length, Settings, Turns};
use dioxus_compose::prelude::*;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Message {
    pub id: u64,
    /// The turn this message belongs to. A stale worker compares it before appending.
    pub token: u64,
    /// Which conversation said it. One list holds every conversation's messages so the
    /// worker can keep appending to the last one it was given without knowing that the
    /// person reading has moved to a different conversation meanwhile.
    pub conversation: u64,
    pub from_user: bool,
    pub text: String,
    pub streaming: bool,
}

/// One conversation in the sidebar.
#[derive(Clone, Debug, PartialEq, Eq)]
struct Conversation {
    /// Never reused, so it is the destination's key and what a message belongs to.
    id: u64,
    /// What the sidebar calls it. Empty until the first thing is said in it, because
    /// the reference names a conversation after its opening line.
    title: String,
}

impl Conversation {
    /// What a conversation with nothing in it is called.
    const UNTITLED: &'static str = "New chat";

    fn label(&self) -> String {
        if self.title.is_empty() {
            Self::UNTITLED.to_owned()
        } else {
            self.title.clone()
        }
    }
}

/// How many characters of the opening line become the conversation's name.
///
/// Long enough to tell two conversations apart and short enough to sit in a rail. The
/// reference does the same thing with about the same amount of room.
const TITLE_CHARS: usize = 24;

fn title_from(text: &str) -> String {
    let mut title: String = text.chars().take(TITLE_CHARS).collect();
    if text.chars().nth(TITLE_CHARS).is_some() {
        title.push('\u{2026}');
    }
    title
}

/// How many conversations stand in the destination set.
///
/// The reference's sidebar shows recent chats and keeps the rest behind a "show more",
/// and there is a harder reason than taste: the same declaration is a bar along the
/// bottom of a phone, and a bar that holds every conversation ever started is a bar with
/// nothing legible in it. Five is the published ceiling for a bottom bar in the design
/// system this project takes its size classes from.
const RECENT_CONVERSATIONS: usize = 5;

/// How many places in the application come before the conversations in the strip.
///
/// The conversations are destinations in the same set, so a conversation's position in the
/// list is not its position among the destinations. Without this the conversation you were
/// in marked the wrong row: starting one lit "Search", which is a row you are never on.
const DESTINATIONS_ABOVE_THE_CHATS: usize = 4;

/// A new conversation has nothing in it.
///
/// It used to open with one message from the assistant explaining what the screen was,
/// which put an introduction in the transcript: something the assistant never said, under
/// its name, that you could scroll back to a week later and read as part of the
/// conversation. The reference does not do that. An empty conversation is empty, and what
/// the screen is gets said by the screen, in the middle, until there is something to read.
fn opening_messages() -> Vec<Message> {
    Vec::new()
}

/// How big the mark is where it opens an empty screen, and how far the greeting sits under
/// it. Both read off the reference.
///
/// The gap is measured to the top of the letters and set to the room between them, which is
/// a smaller number: a line of type carries its own leading above the letters, so a gap
/// asked for at the reference's twenty came out at thirty five on screen and the mark
/// floated away from the words it belongs to.
const SPARK_ON_THE_EMPTY_SCREEN: f32 = 36.0;
const SPARK_TO_GREETING: f32 = 6.0;

/// And how big it is beside the application's name in the strip.
const SPARK_IN_THE_STRIP: f32 = 20.0;

/// The room above, below and either end of what the composer holds.
const ROOM_INSIDE_THE_COMPOSER: f32 = 8.0;

/// How big the key that sends the message is, across and down.
const THE_SEND_KEY: f32 = 36.0;

/// How far the page's own content stands off the window it is drawn in.
///
/// The same eight the sidebar stands off it, so the composer at the foot of the page and
/// the panel beside it stop the same distance from the window's edge. At the space ladder's
/// own step the composer floated eighteen off the floor against the reference's eight, and
/// two things held off one edge by two different amounts read as neither being held.
const PAGE_INSET: f32 = 8.0;

/// The application's mark.
///
/// An SVG rather than a draw list, because the shape has four colours meeting at a point
/// and the draw vocabulary has no filled path to say that with. It is registered once and
/// referred to by id wherever it is drawn, so the bytes cross the boundary a single time.
fn spark(size: f32) -> Element {
    rsx! {
        Image {
            asset_id: dioxus_compose::asset::asset(
                dioxus_compose::schema::AssetKind::Svg,
                include_bytes!("../assets/spark.svg"),
            ),
            width: size,
            height: size,
        }
    }
}

/// What stands in the middle of a conversation that has not started.
///
/// Not a widget in the scrollback: the list is genuinely empty, and this sits over it. It
/// leaves as soon as there is a first message, which is why it can say the thing that is
/// only true before then.
fn opening_greeting() -> Element {
    rsx! {
        Column {
            padding_role: SpaceRole::Lg,
            space_role: SpaceRole::Xs,
            alignment: Alignment::Center,
            {spark(SPARK_ON_THE_EMPTY_SCREEN)}
            Spacer { height: SPARK_TO_GREETING }
            // Two lines, not a headline over a paragraph. The reference greets you and
            // asks one question. How to use the composer is not written anywhere on it,
            // because a composer that has to explain itself is the thing to fix instead.
            Text {
                text: "Hello.",
                type_role: TypeRole::Display,
                text_align: TextAlign::Center,
            }
            Text {
                text: "What are you thinking about?",
                type_role: TypeRole::Display,
                text_align: TextAlign::Center,
            }
        }
    }
}

/// The widest a thread is allowed to be, per class.
///
/// A line of text stops being readable somewhere around sixty to eighty characters, and a
/// window twice that wide does not make it more readable, it makes it worse. So the thread
/// stops growing and centres itself instead. The two bounds are the class boundaries
/// themselves: a medium window reads at the width a medium window starts at, and an
/// expanded one at the width an expanded one starts at.
fn thread_width(window: &WindowSize) -> Option<f32> {
    match window.class {
        WindowSizeClass::Compact => None,
        WindowSizeClass::Medium => Some(WindowSizeClass::MEDIUM_MIN_WIDTH_DP),
        // Not the class's own boundary. The column in the reference is narrower than the
        // width at which its class begins, and it has to be: a line of text stops being
        // readable somewhere around seventy characters and the boundary is about the
        // window rather than about the line.
        WindowSizeClass::Expanded => Some(THREAD_COLUMN),
    }
}

/// How wide the thread and the composer under it are on a window with room to choose.
///
/// Measured off the reference, whose composer is 751 across with the page showing either
/// side of it. At the size class's own boundary the column came out fifty wider.
const THREAD_COLUMN: f32 = 787.0;

/// The assistant's settings, as a panel that can stand on its own.
///
/// Everything here changes what the worker does with the next reply, which is the
/// difference between a settings screen and a picture of one.
fn settings_panel(
    settings: Settings,
    change: EventHandler<Settings>,
    close: EventHandler<()>,
) -> Element {
    rsx! {
        Column {
            fill_max_width: true,
            space_role: SpaceRole::Md,
            Row {
                fill_max_width: true,
                alignment: Alignment::CenterStart,
                Text { text: "Assistant", type_role: TypeRole::Subtitle, weight: 1.0 }
                Button {
                    text: "Done",
                    variant: ButtonVariant::Filled,
                    on_click: move |_| close.call(()),
                }
            }
            Separator {}

            // One of three, which is what a radio group is for. A dropdown would hide two
            // of them behind a tap for no gain at this size.
            Text {
                text: "Reply length",
                type_role: TypeRole::Label,
                color: Paint::Role(ColorRole::OnSurfaceVariant),
            }
            for length in Length::ALL {
                Row {
                    key: "{length.label()}",
                    fill_max_width: true,
                    space_role: SpaceRole::Sm,
                    alignment: Alignment::CenterStart,
                    RadioButton {
                        selected: settings.length == length,
                        on_change: move |_| change.call(Settings { length, ..settings }),
                    }
                    Text { text: length.label(), type_role: TypeRole::Body }
                }
            }

            Separator {}
            Row {
                fill_max_width: true,
                space_role: SpaceRole::Sm,
                alignment: Alignment::CenterStart,
                Column {
                    weight: 1.0,
                    Text { text: "Type the reply out", type_role: TypeRole::Body }
                    Text {
                        text: "Off, and the whole answer lands at once.",
                        type_role: TypeRole::Caption,
                        color: Paint::Role(ColorRole::OnSurfaceVariant),
                    }
                }
                Switch {
                    checked: settings.streaming,
                    on_change: move |streaming| {
                        change.call(Settings { streaming, ..settings })
                    },
                }
            }

            // The speed only means anything while the reply is being typed out, so it is
            // disabled rather than hidden: a control that vanishes takes the explanation
            // of what the switch above it does with it.
            Text {
                text: "{settings.speed as u32} characters a second",
                type_role: TypeRole::Label,
                color: Paint::Role(ColorRole::OnSurfaceVariant),
            }
            Slider {
                value: settings.speed,
                min: assistant::SLOWEST,
                max: assistant::FASTEST,
                enabled: settings.streaming,
                on_change: move |speed| change.call(Settings { speed, ..settings }),
            }
        }
    }
}

pub fn app() -> Element {
    let window = use_window_size();
    let measure = thread_width(&window);
    // Shared with the assistant thread, so it is a sync signal rather than the usual one.
    // Writing it from the worker marks this scope dirty through a channel the scheduler
    // owns, and the Host asks for the frame.
    let mut messages = use_signal_sync(opening_messages);
    let mut next_id = use_signal(|| 2_u64);
    let mut draft = use_signal(String::new);
    let turns = use_signal(Turns::default);
    let mut settings = use_signal(Settings::default);
    let mut settings_open = use_signal(|| false);
    let mut length_open = use_signal(|| false);
    let mut more_open = use_signal(|| false);
    let mut search_open = use_signal(|| false);
    let mut search_query = use_signal(String::new);
    // The conversations, newest last, and the one being read. A chat application with one
    // conversation is a chat application with the part people use missing, and the
    // reference's sidebar is mostly this list.
    let mut conversations = use_signal(|| {
        vec![Conversation {
            id: 1,
            title: String::new(),
        }]
    });
    let mut current = use_signal(|| 1_u64);
    let mut next_conversation = use_signal(|| 2_u64);

    let mut send = move |text: String| {
        let text = text.trim().to_owned();
        if text.is_empty() {
            return;
        }
        let token = turns.peek().begin();
        let id = next_id();
        next_id.set(id + 2);
        let conversation = current();
        {
            let mut list = messages.write();
            list.push(Message {
                id,
                token,
                conversation,
                from_user: true,
                text: text.clone(),
                streaming: false,
            });
            // The empty reply goes in now, on the UI thread, so the worker only ever has to
            // append to a message that already exists.
            list.push(Message {
                id: id + 1,
                token,
                conversation,
                from_user: false,
                text: String::new(),
                streaming: true,
            });
        }
        // A conversation is named after the first thing said in it, which is how the
        // sidebar tells one from another without asking anybody to name anything.
        {
            let mut list = conversations.write();
            if let Some(entry) = list.iter_mut().find(|entry| entry.id == conversation) {
                if entry.title.is_empty() {
                    entry.title = title_from(&text);
                }
            }
        }
        draft.set(String::new());
        assistant::stream_reply(text, token, turns.peek().clone(), settings(), messages);
    };

    // Nothing is thrown away: the conversation that was on screen stays in the sidebar,
    // which is what the reference does and what makes the sidebar worth having.
    let mut start_conversation = move || {
        let id = next_conversation();
        next_conversation.set(id + 1);
        conversations.write().push(Conversation {
            id,
            title: String::new(),
        });
        current.set(id);
    };

    // Deleting is the one thing here that throws a conversation away, so it says what it
    // did and offers it back.
    let mut delete_current = move || {
        let gone = current();
        let entries = conversations();
        let Some(at) = entries.iter().position(|entry| entry.id == gone) else {
            return;
        };
        let removed = entries[at].clone();
        let lines = messages();
        // The worker is already handled: beginning a turn bumps the token, and a worker
        // whose token is no longer current stops at its next chunk rather than appending
        // to a conversation that has gone.
        turns.peek().begin();
        conversations.write().remove(at);
        messages
            .write()
            .retain(|message| message.conversation != gone);
        // There is always somewhere to be. Deleting the last one leaves an empty
        // conversation rather than a screen with no conversation in it, which is a state
        // the rest of this screen cannot draw.
        if conversations.read().is_empty() {
            let id = next_conversation();
            next_conversation.set(id + 1);
            conversations.write().push(Conversation {
                id,
                title: String::new(),
            });
        }
        let next = conversations.read()[at.min(conversations.read().len() - 1)].id;
        current.set(next);
        // Spelled out, because this file already has a `Message` and it is a line of a
        // conversation. The library's is the one sentence an application says after
        // something happened.
        dioxus_compose::Message::new(format!("Deleted \u{201c}{}\u{201d}", removed.label()))
            .with_action("Undo", move |()| {
                conversations.write().insert(at, removed.clone());
                messages.set(lines.clone());
                current.set(gone);
            })
            .with_duration(MessageDuration::Long)
            .show();
    };

    // What is on screen is one conversation's messages. The list holds every
    // conversation's, so this is the window into it, the same shape the task list uses
    // for its filters.
    let thread: Vec<usize> = messages
        .read()
        .iter()
        .enumerate()
        .filter(|(_, message)| message.conversation == current())
        .map(|(index, _)| index)
        .collect();
    let count = thread.len();
    let busy = messages
        .read()
        .last()
        .is_some_and(|message| message.streaming && message.conversation == current());
    let keys: Vec<String> = thread
        .iter()
        .map(|index| messages.read()[*index].id.to_string())
        .collect();
    let rows = thread.clone();
    // Newest first in the sidebar, and only as many as a set of destinations can hold.
    let query = search_query().to_lowercase();
    let recent: Vec<Conversation> = conversations()
        .iter()
        .rev()
        .filter(|entry| query.is_empty() || entry.label().to_lowercase().contains(&query))
        .take(RECENT_CONVERSATIONS)
        .cloned()
        .collect();
    let show_search = window.is_expanded();
    let selected = recent
        .iter()
        .position(|entry| entry.id == current())
        .map_or(0, |index| {
            index + DESTINATIONS_ABOVE_THE_CHATS + usize::from(show_search)
        });

    rsx! {
        // The reference's window has three parts and no bar: a sidebar the window buttons
        // sit on, a page, and a composer floating at the foot of the page. The frame is
        // told which is which and the Renderer decides what each becomes at this width:
        // the destinations are a sidebar on a desktop, a rail on a tablet and a bar along
        // the bottom of a phone, and the two actions float in a capsule rather than
        // standing in a strip across the top.
        //
        // The window itself is chrome. Where the platform can put its own material behind
        // a window, that is what asking for it here does, and the desktop shows through
        // the sidebar and, more faintly, through the page.
        Scaffold {
            material: MaterialRole::Chrome,
            top_bar: rsx! {
                TopAppBar {
                    fill_max_width: true,
                    Spacer { weight: 1.0 }
                    // The reference's two trailing actions. One starts a conversation and
                    // the other holds what is done to the one on screen.
                    // Compose rather than Add, which is what it means and what the
                    // reference draws there. It also leaves Add to the composer's own key,
                    // and two buttons wearing one meaning is two buttons nobody can tell
                    // apart, including a test reaching for one of them.
                    // Quiet, not accent. The reference's two chips at the top of the
                    // window are dark grey on white; in the accent they were the loudest
                    // thing on the screen and what they are loud about is a corner.
                    Button {
                        text: "",
                        icon: IconRole::Compose,
                        variant: ButtonVariant::Text,
                        color: Paint::Role(ColorRole::OnSurfaceVariant),
                        on_click: move |_| start_conversation(),
                    }
                    Menu {
                        expanded: more_open(),
                        on_dismiss: move |_| more_open.set(false),
                        anchor: rsx! {
                            Button {
                                text: "",
                                icon: IconRole::More,
                                variant: ButtonVariant::Text,
                                color: Paint::Role(ColorRole::OnSurfaceVariant),
                                on_click: move |_| more_open.set(true),
                            }
                        },
                        Button {
                            text: "Assistant settings",
                            variant: ButtonVariant::Text,
                            fill_max_width: true,
                            on_click: move |_| {
                                more_open.set(false);
                                settings_open.set(true);
                            },
                        }
                        Button {
                            text: "Delete conversation",
                            variant: ButtonVariant::Text,
                            color: Paint::Role(ColorRole::Error),
                            fill_max_width: true,
                            enabled: conversations.read().len() > 1,
                            on_click: move |_| {
                                more_open.set(false);
                                delete_current();
                            },
                        }
                    }
                }
            },
            // The conversations are the destination set, which is the reference's
            // sidebar. This code never asks how wide the window is for it.
            bottom_bar: rsx! {
                Navigation {
                    selected_index: selected,
                    // The reference opens its strip with the application's mark and name.
                    head: rsx! {
                        Row {
                            fill_max_width: true,
                            padding_role: SpaceRole::Sm,
                            space_role: SpaceRole::Sm,
                            alignment: Alignment::CenterStart,
                            {spark(SPARK_IN_THE_STRIP)}
                            Text { text: "Chat", type_role: TypeRole::Subtitle }
                        }
                    },
                    // And closes it with who is signed in.
                    foot: rsx! {
                        Row {
                            fill_max_width: true,
                            padding_role: SpaceRole::Sm,
                            space_role: SpaceRole::Sm,
                            alignment: Alignment::CenterStart,
                            Text { text: "\u{25cf}", type_role: TypeRole::Body, color: Paint::Role(ColorRole::Primary) }
                            Column {
                                Text { text: "Signed in", type_role: TypeRole::Label }
                                Text {
                                    text: "Local",
                                    type_role: TypeRole::Caption,
                                    color: Paint::Role(ColorRole::OnSurfaceVariant),
                                }
                            }
                        }
                    },
                    NavigationItem {
                        text: "New chat",
                        icon: IconRole::Compose,
                        on_click: move |()| start_conversation(),
                    }
                    if show_search {
                        NavigationItem {
                            text: "Search",
                            icon: IconRole::Search,
                            on_click: move |()| search_open.set(true),
                        }
                    }
                    NavigationItem { text: "Images", icon: IconRole::Image }
                    NavigationItem { text: "Videos", icon: IconRole::Video }
                    NavigationItem { text: "Library", icon: IconRole::Library }
                    for conversation in recent.iter().cloned() {
                        NavigationItem {
                            key: "{conversation.id}",
                            text: conversation.label(),
                            // Under a heading of their own, which is what the reference
                            // does with everything that is a conversation rather than a
                            // place in the application.
                            section: "Chats",
                            on_click: {
                                let id = conversation.id;
                                move |()| current.set(id)
                            },
                        }
                    }
                }
            },

            // On a narrow window the thread is the window. On anything wider it is a
            // column of its own, centred, with the page showing either side of it. The
            // page is the frame's, so the column paints nothing: what is behind it is the
            // design system's page, and on a window made of glass, the desktop.
            dioxus_compose::Box {
                fill_max_width: true,
                fill_max_height: true,
                alignment: Alignment::TopCenter,
                Column {
                    fill_max_width: measure.is_none(),
                    width: measure,
                    fill_max_height: true,
                    padding: PAGE_INSET,
                    space_role: SpaceRole::Md,


                    // A reply arriving is work in progress, and a line is what every one
                    // of these systems uses to say so. Indeterminate, because the
                    // assistant does not know how long its answer is going to be either.
                    if busy {
                        ProgressIndicator { determinate: false }
                    }

                    // The scrollback and, while there is nothing in it, what the screen
                    // is. The list is declared either way: a conversation that begins by
                    // building a list is a conversation whose first message arrives a
                    // frame late.
                    dioxus_compose::Box {
                        fill_max_width: true,
                        weight: 1.0,
                        alignment: Alignment::Center,
                        LazyColumn {
                            fill_max_width: true,
                            fill_max_height: true,
                            item_count: count,
                            key_of: move |index: usize| keys[index].clone(),
                            item: move |position: usize| {
                                let index = rows[position];
                                let message = messages.read()[index].clone();
                                // A name over every bubble is a name repeated once per
                                // line, so it is printed once at the head of a run.
                                let starts_a_run = position == 0
                                    || messages.read()[rows[position - 1]].from_user != message.from_user;
                                // Who said it is the side the bubble sits on and the colour
                                // it is filled with, with the name left as confirmation.
                                let (fill, ink) = if message.from_user {
                                    (ColorRole::Primary, ColorRole::OnPrimary)
                                } else {
                                    (ColorRole::SurfaceVariant, ColorRole::OnSurfaceVariant)
                                };
                                rsx! {
                                    dioxus_compose::Box {
                                        fill_max_width: true,
                                        padding_role: if starts_a_run {
                                            SpaceRole::Sm
                                        } else {
                                            SpaceRole::Xs
                                        },
                                        alignment: if message.from_user {
                                            Alignment::CenterEnd
                                        } else {
                                            Alignment::CenterStart
                                        },
                                        Column {
                                            space_role: SpaceRole::Xs,
                                            alignment: if message.from_user {
                                                Alignment::CenterEnd
                                            } else {
                                                Alignment::CenterStart
                                            },
                                            if starts_a_run {
                                                Text {
                                                    text: if message.from_user { "You" } else { "Assistant" },
                                                    type_role: TypeRole::Caption,
                                                    color: Paint::Role(ColorRole::OnSurfaceVariant),
                                                }
                                            }
                                            Column {
                                                background: Paint::Role(fill),
                                                shape_role: ShapeRole::Large,
                                                padding_role: SpaceRole::Md,
                                                Text {
                                                    // A message still arriving shows a
                                                    // caret so an empty reply does not look
                                                    // like a dead one.
                                                    text: if message.streaming {
                                                        format!("{}\u{2589}", message.text)
                                                    } else {
                                                        message.text.clone()
                                                    },
                                                    type_role: TypeRole::Body,
                                                    color: Paint::Role(ink),
                                                }
                                            }
                                        }
                                    }
                                }
                            },
                        }
                        if count == 0 {
                            {opening_greeting()}
                        }
                    }

                    // The composer, as the reference has it: one capsule floating at the
                    // foot of the page, held in from both sides and the bottom, holding
                    // everything that belongs to sending a message.
                    //
                    // Made of a material rather than being a `Card`. A card is the
                    // document in a desktop window and stays opaque there; the composer is
                    // chrome at every width, and a regular material is what a design
                    // system draws chrome in: glass where it draws glass, its own raised
                    // surface where it does not.
                    Row {
                        fill_max_width: true,
                        material: MaterialRole::Regular,
                        shape_role: ShapeRole::Full,
                        // A step of room inside the pill. A stadium's edge curves in at
                        // the top and bottom, and the field is a rectangle: at the
                        // system's own padding its corners came out through the curve.
                        //
                        // Measured rather than taken from the ladder. The reference's bar
                        // is fifty two tall and its send key is thirty six, which leaves
                        // exactly this much above and below, and the same number holds the
                        // key off the trailing end: at the ladder's ten the bar came out
                        // sixty and the key sat off centre.
                        padding: ROOM_INSIDE_THE_COMPOSER,
                        space_role: SpaceRole::Sm,
                        alignment: Alignment::CenterStart,
                        // No `on_key_down` here on purpose. The Renderer already treats
                        // Enter in a multiline field that has a submit handler as "send"
                        // and Shift+Enter as "new line", and `on_submit` carries the text
                        // the field holds at that instant.
                        // What the reference puts at the leading end. Its menu attaches
                        // things; this one holds what this screen can actually do, because
                        // a key that opens a menu of nothing is worse than no key.
                        Menu {
                            expanded: more_open(),
                            on_dismiss: move |_| more_open.set(false),
                            anchor: rsx! {
                                Button {
                                    text: "",
                                    icon: IconRole::Add,
                                    variant: ButtonVariant::Text,
                                    color: Paint::Role(ColorRole::OnSurfaceVariant),
                                    on_click: move |_| more_open.set(true),
                                }
                            },
                            Button {
                                text: "Search conversations",
                                icon: IconRole::Search,
                                variant: ButtonVariant::Text,
                                fill_max_width: true,
                                on_click: move |_| {
                                    more_open.set(false);
                                    search_open.set(true);
                                },
                            }
                            Divider {}
                            Button {
                                text: "Settings",
                                icon: IconRole::Settings,
                                variant: ButtonVariant::Text,
                                fill_max_width: true,
                                on_click: move |_| {
                                    more_open.set(false);
                                    settings_open.set(true);
                                },
                            }
                        }
                        TextField {
                            weight: 1.0,
                            multiline: true,
                            placeholder: "Message",
                            on_value_change: move |value| draft.set(value),
                            on_submit: move |value: String| send(value),
                        }
                        // What the reference calls the model, which is the one thing about
                        // an answer you can choose before asking for it. A menu behind its
                        // own label rather than a `Dropdown`: a picker is a wheel in some
                        // of these systems, taller than the composer it would sit in.
                        Menu {
                            expanded: length_open(),
                            on_dismiss: move |_| length_open.set(false),
                            anchor: rsx! {
                                Button {
                                    text: settings().length.label(),
                                    variant: ButtonVariant::Text,
                                    color: Paint::Role(ColorRole::OnSurfaceVariant),
                                    on_click: move |_| length_open.set(true),
                                }
                            },
                            for length in Length::ALL {
                                Button {
                                    key: "{length.label()}",
                                    text: length.label(),
                                    variant: ButtonVariant::Text,
                                    fill_max_width: true,
                                    on_click: move |_| {
                                        length_open.set(false);
                                        settings.set(Settings { length, ..settings() });
                                    },
                                }
                            }
                        }
                        // Tonal rather than filled, and the arrow points up. The
                        // reference's send is a small tinted circle, not a saturated one:
                        // a solid accent circle at the end of the composer is the loudest
                        // thing on the screen, and what it is loud about is a button you
                        // press after you have already decided.
                        Button {
                            text: "",
                            icon: IconRole::Send,
                            variant: ButtonVariant::Tonal,
                            shape_role: ShapeRole::Full,
                            width: THE_SEND_KEY,
                            height: THE_SEND_KEY,
                            on_click: move |_| send(draft()),
                        }
                    }
                }

                // The settings arrive from an edge rather than taking the screen: what they
                // change is the conversation behind them, and covering it to change it
                // would hide the thing being changed. Which edge is the Renderer's.
                Sheet {
                    open: settings_open(),
                    on_dismiss: move |_| settings_open.set(false),
                    fill_max_width: true,
                    {settings_panel(
                        settings(),
                        EventHandler::new(move |next| settings.set(next)),
                        EventHandler::new(move |()| settings_open.set(false)),
                    )}
                }

                Sheet {
                    open: search_open(),
                    on_dismiss: move |_| search_open.set(false),
                    fill_max_width: true,
                    Column {
                        fill_max_width: true,
                        space_role: SpaceRole::Md,
                        Row {
                            fill_max_width: true,
                            alignment: Alignment::CenterStart,
                            Text { text: "Search conversations", type_role: TypeRole::Subtitle, weight: 1.0 }
                            Button {
                                text: "Done",
                                variant: ButtonVariant::Filled,
                                on_click: move |_| search_open.set(false),
                            }
                        }
                        TextField {
                            fill_max_width: true,
                            placeholder: "Search conversations",
                            on_value_change: move |value| search_query.set(value),
                        }
                        if !search_query().is_empty() {
                            Button {
                                text: "Clear search",
                                fill_max_width: true,
                                variant: ButtonVariant::Text,
                                on_click: move |_| search_query.set(String::new()),
                            }
                        }
                    }
                }
            }
        }
    }
}

// Samples are demonstrations, so they let you see any of the design systems rather than
// only the one this machine happens to select. Unset, the app adapts to the host platform,
// which is what a real application wants.
/// Runs the sample as a program of its own. The desktop binary is one line of this.
pub fn launch() {
    launch_builder().launch(app);
}

/// How this sample is configured, in one place because three entry points need it.
///
/// The theme above all: a sample that names one and then reaches a platform through an
/// entry point that makes its own builder is a sample that draws the same screens in a
/// different design system depending on where it runs.
/// The typeface the application this is clone coded from is set in.
///
/// Roboto rather than the machine's own UI face. Comparing this screen with the reference
/// is the point of the sample, and two screens in two different typefaces differ in a way
/// that hides every other way they differ: the letters are the first thing the eye reads
/// and the last thing it stops reading.
///
/// Two weights, because a role is one asset and a static face has one weight. Asking a
/// regular face for a semibold gets a synthesised one, which is the outline smeared
/// sideways and looks like nothing anybody drew. The rungs a design system sets in a
/// heavier weight get the medium face and the rest get the regular one.
///
/// Apache 2.0, the same licence as this project. `assets/Roboto-LICENSE.txt` is its copy.
fn with_the_references_typeface(theme: Theme) -> Theme {
    let regular = dioxus_compose::asset::asset(
        dioxus_compose::schema::AssetKind::Font,
        include_bytes!("../assets/Roboto-Regular.ttf"),
    );
    let medium = dioxus_compose::asset::asset(
        dioxus_compose::schema::AssetKind::Font,
        include_bytes!("../assets/Roboto-Medium.ttf"),
    );
    theme
        .with_font(TypeRole::Display, medium)
        .with_font(TypeRole::Headline, medium)
        .with_font(TypeRole::Title, medium)
        .with_font(TypeRole::Subtitle, medium)
        .with_font(TypeRole::BodyStrong, medium)
        .with_font(TypeRole::Label, medium)
        .with_font(TypeRole::Body, regular)
        .with_font(TypeRole::Caption, regular)
}

fn launch_builder() -> dioxus_compose::LaunchBuilder {
    // The name the window carries. A desktop lists windows by it, so a window that said
    // nothing was listed under whatever the renderer happened to be called, and every
    // sample here was listed as DioxusCompose until this line existed.
    dioxus_compose::LaunchBuilder::new()
        .with_theme(with_the_references_typeface(dioxus_compose::demo_theme()))
        .with_window(
            dioxus_compose::schema::Window::new()
                .with_title("Chat")
                // The window this is clone coded from, which on macOS has its buttons a
                // step in from the corner and a larger radius than a plain window. Said
                // out loud even though it is the default, because it is a fact about this
                // application's window rather than something to be inherited quietly.
                .with_title_bar(dioxus_compose::schema::TitleBar::Normal)
                // Without one the window wears the toolkit's picture, which on
                // Windows is the Java coffee cup, wherever the system lists
                // windows. The bytes travel as an asset and the renderer refers
                // to them by id: a path would be a fact about the machine this
                // was built on, and a name would ask the toolkit to find
                // something it may not have.
                .with_icon(dioxus_compose::asset::asset(
                    dioxus_compose::schema::AssetKind::Png,
                    include_bytes!("../assets/icon.png"),
                )),
        )
}

// The platforms where the sample is not a program. Android's Activity and the browser's
// page both own the loop, so neither has a `main` to call: each names an entry point that
// registers the root component, and these macros define it.
//
// Both are declared unconditionally. Each macro compiles into nothing that runs off its
// own platform, and gating them here instead would mean a desktop build never checks that
// this sample can still be built for the other two.
dioxus_compose::android_main!({ launch_builder() }, app);
dioxus_compose::web_main!({ launch_builder() }, app);
dioxus_compose::ios_main!(launch);

#[cfg(test)]
mod tests {
    use super::*;
    use dioxus_compose::Host;
    use dioxus_compose::protocol::{
        HostEvent, Mutation, PropertyValue, decode_batch, encode_event,
    };
    use dioxus_compose::schema::{EventPayload, PropertyKind, WidgetKind};
    use std::alloc::{GlobalAlloc, Layout, System};
    use std::cell::Cell;
    use std::collections::{HashMap, HashSet};
    use std::time::Duration;

    /// A click or a keystroke in a steady state screen changes one node. Anything above
    /// this means something is allocating per event rather than reusing a buffer.
    const HOST_ALLOCATION_CEILING: usize = 200;
    const REPEATED_INTERACTIONS: usize = 100;

    struct CountingAllocator;

    // Counting is per thread, not per process: the harness runs tests concurrently and the
    // assistant runs on a worker, so a global counter would attribute other threads'
    // allocations to whichever measurement happens to be open.
    thread_local! {
        static TRACKING: Cell<bool> = const { Cell::new(false) };
        static ALLOCATIONS: Cell<usize> = const { Cell::new(0) };
    }

    fn record_allocation() {
        // `try_with` because a thread tearing down its locals must not re-enter them.
        let _ = TRACKING.try_with(|tracking| {
            if tracking.get() {
                let _ = ALLOCATIONS.try_with(|count| count.set(count.get() + 1));
            }
        });
    }

    // SAFETY: Every operation delegates to the process System allocator unchanged.
    unsafe impl GlobalAlloc for CountingAllocator {
        unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
            record_allocation();
            // SAFETY: Delegating the caller-provided layout to System.
            unsafe { System.alloc(layout) }
        }

        unsafe fn dealloc(&self, ptr: *mut u8, layout: Layout) {
            // SAFETY: Delegating the original pointer and layout to System.
            unsafe { System.dealloc(ptr, layout) };
        }

        unsafe fn realloc(&self, ptr: *mut u8, layout: Layout, size: usize) -> *mut u8 {
            record_allocation();
            // SAFETY: Delegating the original allocation and requested size to System.
            unsafe { System.realloc(ptr, layout, size) }
        }
    }

    #[global_allocator]
    static GLOBAL: CountingAllocator = CountingAllocator;

    struct AllocationMeasurement;

    impl AllocationMeasurement {
        fn start() -> Self {
            ALLOCATIONS.with(|count| count.set(0));
            TRACKING.with(|tracking| tracking.set(true));
            Self
        }

        fn finish(self) -> usize {
            TRACKING.with(|tracking| tracking.set(false));
            ALLOCATIONS.with(|count| count.get())
        }
    }

    /// The real chat screen, driven the way the Renderer drives it.
    struct Screen {
        host: Host,
        /// The first frame, kept so a control the screen declared once can still be found
        /// after other frames have gone by.
        first: Vec<u8>,
        scrollback: u32,
        range_handler: u64,
        composer: u32,
        submit_handler: u64,
        change_handler: u64,
        /// The text of every node, as the frames report it.
        texts: HashMap<u32, String>,
        /// The destination nodes that are still on screen, which is the sidebar.
        destinations: Vec<u32>,
        /// Which named group each destination said it was in, for the ones that said.
        sections: HashMap<u32, String>,
        /// Every message the screen has said, in order, with its action label.
        messages: Vec<(String, String)>,
        /// Which of the destinations the strip last said was the one being looked at.
        selected: usize,
        event: Vec<u8>,
    }

    impl Screen {
        fn new() -> Self {
            let mut host = Host::new(app);
            let bytes = host
                .rebuild()
                .expect("the first frame failed to encode")
                .to_vec();
            let first = decode_batch(&bytes).expect("the first frame did not decode");
            let node_of = |widget: WidgetKind| {
                first
                    .iter()
                    .find_map(|mutation| match mutation {
                        Mutation::Create {
                            node_id,
                            widget: found,
                        } if *found == widget => Some(*node_id),
                        _ => None,
                    })
                    .unwrap_or_else(|| panic!("the screen has no {widget:?}"))
            };
            let scrollback = node_of(WidgetKind::LazyColumn);
            let composer = node_of(WidgetKind::TextField);
            let handler_of = |node: u32, property: PropertyKind| {
                first
                    .iter()
                    .find_map(|mutation| match mutation {
                        Mutation::SetProp {
                            node_id,
                            property: found,
                            value: PropertyValue::Integer(id),
                        } if *node_id == node && *found == property => Some(*id as u64),
                        _ => None,
                    })
                    .unwrap_or_else(|| panic!("no {property:?} handler was declared"))
            };
            let range_handler = handler_of(scrollback, PropertyKind::OnRangeRequested);
            let submit_handler = handler_of(composer, PropertyKind::OnSubmit);
            let change_handler = handler_of(composer, PropertyKind::OnValueChange);
            let texts = first
                .iter()
                .filter_map(|mutation| match mutation {
                    Mutation::SetProp {
                        node_id,
                        property: PropertyKind::Text,
                        value: PropertyValue::String(text),
                    } => Some((*node_id, (*text).to_owned())),
                    _ => None,
                })
                .collect();
            let destinations = first
                .iter()
                .filter_map(|mutation| match mutation {
                    Mutation::Create {
                        node_id,
                        widget: WidgetKind::NavigationItem,
                    } => Some(*node_id),
                    _ => None,
                })
                .collect();
            let sections = first
                .iter()
                .filter_map(|mutation| match mutation {
                    Mutation::SetProp {
                        node_id,
                        property: PropertyKind::Section,
                        value: PropertyValue::String(name),
                    } => Some((*node_id, (*name).to_owned())),
                    _ => None,
                })
                .collect();
            // Read from the first frame rather than waited for, because a property that
            // never changes is never set again: a strip that opens on the row it means
            // says so once.
            let selected = first
                .iter()
                .find_map(|mutation| match mutation {
                    Mutation::SetProp {
                        property: PropertyKind::SelectedIndex,
                        value: PropertyValue::Integer(index),
                        ..
                    } => Some((*index).max(0) as usize),
                    _ => None,
                })
                .unwrap_or(0);
            drop(first);
            Self {
                host,
                first: bytes,
                scrollback,
                range_handler,
                composer,
                submit_handler,
                change_handler,
                texts,
                destinations,
                sections,
                messages: Vec::new(),
                selected,
                event: Vec::new(),
            }
        }

        /// Every node the screen created as this widget, in declaration order.
        fn nodes_of(&self, widget: WidgetKind) -> Vec<u32> {
            decode_batch(&self.first)
                .expect("the first frame did not decode")
                .iter()
                .filter_map(|mutation| match mutation {
                    Mutation::Create {
                        node_id,
                        widget: found,
                    } if *found == widget => Some(*node_id),
                    _ => None,
                })
                .collect()
        }

        fn handler_of(&self, node: u32, property: PropertyKind) -> u64 {
            decode_batch(&self.first)
                .expect("the first frame did not decode")
                .iter()
                .find_map(|mutation| match mutation {
                    Mutation::SetProp {
                        node_id,
                        property: found,
                        value: PropertyValue::Integer(id),
                    } if *node_id == node && *found == property => Some(*id as u64),
                    _ => None,
                })
                .unwrap_or_else(|| panic!("node {node} declared no {property:?} handler"))
        }

        /// Applies a batch, keeping what the screen is showing and what it has said.
        fn absorb(&mut self, batch: &[u8]) {
            for mutation in decode_batch(batch).expect("a frame did not decode") {
                match mutation {
                    Mutation::SetProp {
                        node_id,
                        property: PropertyKind::Text,
                        value: PropertyValue::String(text),
                    } => {
                        self.texts.insert(node_id, text.to_owned());
                    }
                    Mutation::Create {
                        node_id,
                        widget: WidgetKind::NavigationItem,
                    } => self.destinations.push(node_id),
                    Mutation::SetProp {
                        node_id,
                        property: PropertyKind::Section,
                        value: PropertyValue::String(name),
                    } => {
                        self.sections.insert(node_id, (*name).to_owned());
                    }
                    Mutation::Remove { node_id } => {
                        self.destinations.retain(|found| *found != node_id);
                    }
                    Mutation::SetProp {
                        property: PropertyKind::SelectedIndex,
                        value: PropertyValue::Integer(index),
                        ..
                    } => {
                        self.selected = index.max(0) as usize;
                    }
                    Mutation::ShowMessage { text, action, .. } => {
                        self.messages.push((text.to_owned(), action.to_owned()));
                    }
                    _ => {}
                }
            }
        }

        /// What the sidebar is offering, by label, in the order it was declared in.
        ///
        /// Declaration order and not sorted, because the strip says which destination is
        /// the one being looked at by its position in this list, so the order is part of
        /// what the screen means rather than the Renderer's business.
        fn destinations(&self) -> Vec<String> {
            self.destinations
                .iter()
                .filter_map(|node| self.texts.get(node).cloned())
                .collect()
        }

        /// The label of the destination the strip is marking.
        fn marked_destination(&self) -> String {
            self.destinations()
                .get(self.selected)
                .cloned()
                .unwrap_or_default()
        }

        /// The destinations in the strip's conversation group, in declaration order.
        ///
        /// The strip carries places as well as conversations now, so "every destination"
        /// and "every conversation" are no longer the same list. The group is what tells
        /// them apart, and it is the same string the screen sends.
        fn conversations(&self) -> Vec<String> {
            self.destinations
                .iter()
                .filter(|node| self.sections.get(node).map(String::as_str) == Some("Chats"))
                .filter_map(|node| self.texts.get(node).cloned())
                .collect()
        }

        /// Sends an event and keeps what came back.
        fn dispatch(&mut self, node_id: u32, handler_id: u64, payload: EventPayload<'_>) {
            self.encode(node_id, handler_id, payload);
            let batch = self
                .host
                .dispatch_event(&self.event)
                .expect("the event failed")
                .0
                .to_vec();
            self.absorb(&batch);
        }

        /// Presses what a person would read as this label.
        fn press(&mut self, label: &str) {
            let node_id = *self
                .texts
                .iter()
                .find(|(_, text)| *text == label)
                .map(|(node_id, _)| node_id)
                .unwrap_or_else(|| panic!("the screen has nothing labelled {label}"));
            let handler = self.handler_of(node_id, PropertyKind::OnClick);
            self.dispatch(node_id, handler, EventPayload::Clicked);
        }

        /// Presses the button that carries this glyph and no label, which is what the
        /// actions in the reference's toolbar are.
        fn press_icon(&mut self, icon: IconRole) {
            let batch = decode_batch(&self.first).expect("the first frame did not decode");
            let buttons: HashSet<u32> = batch
                .iter()
                .filter_map(|mutation| match mutation {
                    Mutation::Create {
                        node_id,
                        widget: WidgetKind::Button,
                    } => Some(*node_id),
                    _ => None,
                })
                .collect();
            let node_id = batch
                .iter()
                .find_map(|mutation| match mutation {
                    Mutation::SetProp {
                        node_id,
                        property: PropertyKind::Icon,
                        value: PropertyValue::Integer(value),
                    } if buttons.contains(node_id) && *value == icon as i64 => Some(*node_id),
                    _ => None,
                })
                .unwrap_or_else(|| panic!("the screen has no {icon:?} button"));
            drop(batch);
            let handler = self.handler_of(node_id, PropertyKind::OnClick);
            self.dispatch(node_id, handler, EventPayload::Clicked);
        }

        /// Polls frames until the assistant has stopped typing, the way the Renderer does
        /// after the Host asks for one.
        fn settle(&mut self) -> String {
            for _ in 0..600 {
                std::thread::sleep(Duration::from_millis(5));
                let batch = self
                    .host
                    .render_frame(0)
                    .expect("a frame failed to encode")
                    .to_vec();
                self.absorb(&batch);
                let reply = self.reply();
                // The caret is on a message that is still arriving.
                if !reply.is_empty() && !reply.ends_with('\u{2589}') {
                    return reply;
                }
            }
            panic!("the reply never finished arriving: {:?}", self.reply());
        }

        /// The last thing the assistant said, as the screen shows it.
        fn reply(&self) -> String {
            self.texts
                .iter()
                .filter(|(_, text)| {
                    text.starts_with("You said:") || text.contains("Streaming works")
                })
                .max_by_key(|(node_id, _)| **node_id)
                .map(|(_, text)| text.clone())
                .unwrap_or_default()
        }

        fn encode(&mut self, node_id: u32, handler_id: u64, payload: EventPayload<'_>) {
            encode_event(
                &HostEvent {
                    node_id,
                    handler_id,
                    payload,
                },
                &mut self.event,
            )
            .expect("the event did not encode");
        }

        /// Until the Renderer asks for a range the scrollback holds no items at all, so
        /// the window has to be opened before there is any message widget to grow.
        fn open_window(&mut self) {
            let (node, handler) = (self.scrollback, self.range_handler);
            self.encode(
                node,
                handler,
                EventPayload::RangeRequested {
                    start: 0,
                    count: 20,
                },
            );
            self.host
                .dispatch_event(&self.event)
                .expect("the range request failed");
        }

        fn send(&mut self, text: &str) {
            let (node, handler) = (self.composer, self.submit_handler);
            self.encode(node, handler, EventPayload::TextSubmitted(text));
            let batch = self
                .host
                .dispatch_event(&self.event)
                .expect("the send failed")
                .0
                .to_vec();
            self.absorb(&batch);
        }

        fn type_into_composer(&mut self, text: &str) -> usize {
            let (node, handler) = (self.composer, self.change_handler);
            self.encode(node, handler, EventPayload::TextChanged(text));
            let measurement = AllocationMeasurement::start();
            let (batch, result) = self
                .host
                .dispatch_event(&self.event)
                .expect("the keystroke failed");
            std::hint::black_box((batch.len(), result));
            measurement.finish()
        }
    }

    /// One frame's worth of what the wire carried.
    #[derive(Default)]
    struct Frame {
        created: usize,
        removed: usize,
        text_nodes: HashSet<u32>,
        longest_text: usize,
    }

    fn next_frame(host: &mut Host) -> Frame {
        let batch = host.render_frame(0).expect("a frame failed to encode");
        let mut frame = Frame::default();
        for mutation in decode_batch(batch).expect("a frame did not decode") {
            match mutation {
                Mutation::Create { .. } => frame.created += 1,
                Mutation::Remove { .. } => frame.removed += 1,
                Mutation::SetProp {
                    node_id,
                    property: PropertyKind::Text,
                    value: PropertyValue::String(text),
                } => {
                    frame.text_nodes.insert(node_id);
                    frame.longest_text = frame.longest_text.max(text.len());
                }
                _ => {}
            }
        }
        frame
    }

    /// The conversation, under every design system, in both schemes, at all three widths.
    ///
    /// A thread is the screen where a fill that is a shade away from the page behind it
    /// stops being a contrast number and becomes a bubble nobody can see. The scrollback
    /// windows its rows, so the recorder answers the range request a real Renderer would
    /// have made before the first pixel.
    #[test]
    fn fr14_the_conversation_is_recorded_under_every_design_system_and_width() {
        sample_frames::record("Chat", app, |screen| {
            assert_eq!(
                screen.fill_lists(8),
                1,
                "the screen should hold exactly one windowing list, or the picture is of \
                 something other than the scrollback"
            );
        });
    }

    /// The same screen with the assistant's settings open.
    ///
    /// A second recording rather than a flag on the first, because a sheet covers what it
    /// is over: one picture cannot be of both. This one holds a radio group, a switch and
    /// a slider, which is three of the newest widgets in the vocabulary and the place a
    /// design system that has not drawn them yet would show it.
    #[test]
    fn fr21_the_settings_sheet_is_recorded_under_every_design_system_and_width() {
        sample_frames::record("ChatSettings", app, |screen| {
            screen.fill_lists(8);
            assert!(
                screen.press("Assistant settings"),
                "the screen has no way to open the assistant's settings"
            );
        });
    }

    /// Every property this screen sets has to be one the wire can name. A property the
    /// schema does not have fails the whole batch rather than just itself, so a screen that
    /// builds in Rust can still be blank on screen.
    #[test]
    fn the_first_frame_encodes_without_a_protocol_error() {
        assert!(Host::new(app).rebuild().is_ok());
    }

    /// The reply has to arrive from the worker without anyone on the UI thread asking for
    /// it. Sending marks the scope that reads the message list dirty from another thread,
    /// and the next frame carries whatever has landed so far.
    #[test]
    fn pr3_a_reply_arrives_from_the_worker_thread() {
        let mut screen = Screen::new();
        screen.open_window();
        screen.send("tell me about threads");

        // Poll frames the way the Renderer would after the Host asked for one. The reply
        // starts after a short pause, so a second of frames is far more than it needs.
        let mut longest = 0usize;
        for _ in 0..600 {
            std::thread::sleep(Duration::from_millis(5));
            longest = longest.max(next_frame(&mut screen.host).longest_text);
            if longest > 200 {
                break;
            }
        }
        assert!(
            longest > 200,
            "the worker's reply never reached a frame; the longest text sent was {longest} bytes"
        );
    }

    /// A reply streaming in must not rebuild the scrollback around it. The message that is
    /// growing is the only one whose text changes, the conversation above it keeps the
    /// widgets it already had, and nothing is created or destroyed frame after frame.
    #[test]
    fn fr9_a_streaming_reply_leaves_the_rest_of_the_scrollback_alone() {
        let mut screen = Screen::new();
        screen.open_window();
        screen.send("tell me about streaming");

        let mut streaming_frames = 0usize;
        let mut widest = 0usize;
        for _ in 0..600 {
            std::thread::sleep(Duration::from_millis(5));
            let frame = next_frame(&mut screen.host);
            if frame.text_nodes.is_empty() {
                continue;
            }
            streaming_frames += 1;
            widest = widest.max(frame.text_nodes.len());
            assert_eq!(
                frame.created, 0,
                "a frame created {} widgets while a reply was streaming; the scrollback is \
                 being rebuilt rather than appended to",
                frame.created
            );
            assert_eq!(
                frame.removed, 0,
                "a frame removed {} widgets while a reply was streaming",
                frame.removed
            );
            if frame.longest_text > 200 {
                break;
            }
        }
        assert!(
            streaming_frames > 5,
            "only {streaming_frames} frames carried any text; the reply never streamed"
        );
        // The growing message, and at its edges the status line that says whether the
        // assistant is still replying.
        assert!(
            widest <= 2,
            "{widest} separate nodes had their text replaced in one streaming frame; only \
             the message that is growing should change"
        );
    }

    /// Tokens arriving between two frames cost one record, not one record each. The worker
    /// hands over a few characters at a time far faster than a frame goes out, so a frame
    /// taken after several chunks have landed carries the message once.
    #[test]
    fn fr9_tokens_landing_between_frames_coalesce_into_one_record() {
        let mut screen = Screen::new();
        screen.open_window();
        screen.send("tell me about streaming");

        let mut coalesced = false;
        let mut previous = 0usize;
        for _ in 0..40 {
            // Long enough that several chunks land between one frame and the next.
            std::thread::sleep(Duration::from_millis(100));
            let frame = next_frame(&mut screen.host);
            if frame.text_nodes.is_empty() {
                continue;
            }
            let grew_by = frame.longest_text.saturating_sub(previous);
            previous = frame.longest_text;
            // More than one chunk of text arrived, and the frame still named the message
            // once rather than once per chunk.
            if grew_by > 6 && frame.text_nodes.len() == 1 {
                coalesced = true;
                break;
            }
        }
        assert!(
            coalesced,
            "no frame carried more than one chunk of the reply in a single record"
        );
    }

    /// Typing into the composer is the steady state of this screen. A keystroke changes
    /// one node, so the Host must not allocate in proportion to the conversation behind it.
    #[test]
    fn nfr9_host_path_allocation_ceiling() {
        let mut screen = Screen::new();
        screen.open_window();
        for _ in 0..2 {
            screen.type_into_composer("warm");
        }

        let allocations = screen.type_into_composer("a message being typed");

        assert!(
            allocations <= HOST_ALLOCATION_CEILING,
            "the Host allocated {allocations} times while handling a keystroke; the ceiling \
             is {HOST_ALLOCATION_CEILING}. A keystroke in a steady state screen changes one \
             node, so any growth here means something is allocating per event rather than \
             reusing a buffer."
        );
    }

    /// The widths a thread takes, read back off the wire after the Renderer reports a
    /// window of the given width.
    fn widths_at(width_dp: f32) -> Vec<f32> {
        dioxus_compose::window::reset_window_size();
        let mut host = Host::new(app);
        host.rebuild().expect("the first frame failed to encode");
        let event = HostEvent {
            node_id: 0,
            handler_id: 0,
            payload: EventPayload::WindowSizeChanged {
                width_dp,
                height_dp: 900.0,
                class: dioxus_compose::WindowSizeClass::from_width_dp(width_dp),
                height_class: dioxus_compose::WindowHeightClass::from_height_dp(900.0),
            },
        };
        let mut bytes = Vec::new();
        encode_event(&event, &mut bytes).expect("the resize did not encode");
        let (batch, _) = host.dispatch_event(&bytes).expect("the resize failed");
        let widths = decode_batch(batch)
            .expect("the resize batch did not decode")
            .iter()
            .filter_map(|mutation| match mutation {
                Mutation::SetModifier {
                    modifier: dioxus_compose::Modifier::Width(width),
                    ..
                } => Some(*width),
                _ => None,
            })
            .collect();
        dioxus_compose::window::reset_window_size();
        widths
    }

    /// A wide window does not get a wide thread. The column stops at the reading measure
    /// for its class and the page shows either side of it, and a narrow window keeps the
    /// full width because there is nothing to give back.
    #[test]
    fn fr20_the_thread_stops_growing_once_the_window_is_wide() {
        assert!(
            widths_at(420.0).is_empty(),
            "a compact window should not size the thread"
        );
        assert!(widths_at(700.0).contains(&dioxus_compose::WindowSizeClass::MEDIUM_MIN_WIDTH_DP));
        // Not the class's own boundary on a wide window. The column is the reference's,
        // which is narrower than the width at which its class begins, because a line of
        // text stops being readable long before a window stops being wide.
        assert!(widths_at(1200.0).contains(&THREAD_COLUMN));
        assert!(
            !widths_at(1200.0).contains(&dioxus_compose::WindowSizeClass::EXPANDED_MIN_WIDTH_DP),
            "the thread grew to the width the class starts at, which is a window \
             measurement rather than a reading one"
        );
    }

    /// The assistant's settings reach the worker. A reply length that changed nothing
    /// about the reply would be a control wired to a signal and nothing else.
    #[test]
    fn fr21_the_reply_length_setting_changes_what_the_assistant_says() {
        let mut screen = Screen::new();
        screen.open_window();
        screen.send("tell me about streaming");
        let normal = screen.settle();

        // The radio buttons are the only ones on the screen and they are declared in the
        // order the lengths are listed, so the first of them is Brief.
        let mut screen = Screen::new();
        screen.open_window();
        let brief_button = screen.nodes_of(WidgetKind::RadioButton)[0];
        let handler = screen.handler_of(brief_button, PropertyKind::OnValueChange);
        screen.dispatch(brief_button, handler, EventPayload::ValueChanged(1.0));
        screen.send("tell me about streaming");
        let brief = screen.settle();

        assert!(
            brief.len() < normal.len(),
            "the brief reply is not shorter: {brief:?} against {normal:?}"
        );
        assert!(
            normal.starts_with(&brief),
            "the brief reply should be the opening of the full one: {brief:?}"
        );
    }

    /// Deleting throws a conversation away, so it offers it back. Starting a new one no
    /// longer destroys anything, because the old conversation stays in the sidebar.
    #[test]
    fn fr21_deleting_a_conversation_offers_it_back() {
        let mut screen = Screen::new();
        screen.open_window();
        screen.send("tell me about streaming");
        screen.settle();

        screen.press_icon(IconRole::Compose);
        screen.press("Delete conversation");
        assert_eq!(
            screen.messages,
            vec![(
                "Deleted \u{201c}New chat\u{201d}".to_owned(),
                "Undo".to_owned()
            )],
            "deleting should say what it did and offer it back"
        );
    }

    /// Which row the strip marks, which is the conversation being read.
    ///
    /// The conversations share one set of destinations with the places in the application,
    /// so a conversation's position among the conversations is not its position among the
    /// destinations. Without that offset the row marked on a fresh screen was "Search",
    /// which is a row nobody is ever on.
    #[test]
    fn fr22_the_marked_row_is_the_conversation_being_read() {
        let mut screen = Screen::new();
        screen.open_window();
        assert_eq!(
            screen.marked_destination(),
            "New chat",
            "the strip should mark the conversation on screen, and the places in the \
             application come before the conversations in the same set"
        );

        screen.send("tell me about streaming");
        screen.settle();
        let marked = screen.marked_destination();
        assert!(
            screen.conversations().contains(&marked),
            "the strip marked {marked:?}, which is not one of its conversations {:?}: the \
             row being read is always a conversation and never a place",
            screen.conversations()
        );
    }

    /// The conversations are the destination set, which is what the reference's sidebar
    /// is. One declaration, and the Renderer draws it as a bar, a rail or a sidebar from
    /// the width it measured.
    #[test]
    fn fr22_the_conversations_are_the_destination_set() {
        let mut screen = Screen::new();
        screen.open_window();
        assert_eq!(
            screen.nodes_of(WidgetKind::Navigation).len(),
            1,
            "the destinations should be one declaration the Renderer can turn into a bar, \
             a rail or a sidebar"
        );
        let before = screen.conversations();
        assert_eq!(
            before,
            vec!["New chat".to_owned()],
            "a fresh screen should offer the one conversation it has"
        );

        screen.send("tell me about streaming");
        screen.settle();
        screen.press_icon(IconRole::Compose);
        let mut after = screen.conversations();
        after.sort();
        assert_eq!(
            after,
            vec!["New chat".to_owned(), "tell me about streaming".to_owned()],
            "the conversation that was on screen should still be in the sidebar, named \
             after its opening line"
        );
    }

    /// On a desktop the sidebar opens with search, and pressing it opens the filter.
    #[test]
    fn fr22_the_desktop_search_destination_opens_the_filter_sheet() {
        dioxus_compose::window::reset_window_size();
        let mut host = Host::new(app);
        host.rebuild().expect("the first frame failed to encode");

        let resize = HostEvent {
            node_id: 0,
            handler_id: 0,
            payload: EventPayload::WindowSizeChanged {
                width_dp: 1_000.0,
                height_dp: 700.0,
                class: dioxus_compose::WindowSizeClass::Expanded,
                height_class: dioxus_compose::WindowHeightClass::Medium,
            },
        };
        let mut event = Vec::new();
        encode_event(&resize, &mut event).expect("the resize did not encode");
        let (batch, _) = host.dispatch_event(&event).expect("the resize failed");
        let mutations = decode_batch(batch).expect("the resize batch did not decode");
        let search = mutations
            .iter()
            .find_map(|mutation| match mutation {
                Mutation::SetProp {
                    node_id,
                    property: PropertyKind::Text,
                    value: PropertyValue::String("Search"),
                } => Some(*node_id),
                _ => None,
            })
            .expect("the expanded destination set has no search action");
        let handler = mutations
            .iter()
            .find_map(|mutation| match mutation {
                Mutation::SetProp {
                    node_id,
                    property: PropertyKind::OnClick,
                    value: PropertyValue::Integer(handler),
                } if *node_id == search => Some(*handler as u64),
                _ => None,
            })
            .expect("the search action cannot be pressed");

        encode_event(
            &HostEvent {
                node_id: search,
                handler_id: handler,
                payload: EventPayload::Clicked,
            },
            &mut event,
        )
        .expect("the search click did not encode");
        let (batch, _) = host
            .dispatch_event(&event)
            .expect("the search click failed");
        assert!(
            decode_batch(batch)
                .expect("the search frame did not decode")
                .iter()
                .any(|mutation| matches!(
                    mutation,
                    Mutation::SetProp {
                        property: PropertyKind::Open,
                        value: PropertyValue::Bool(true),
                        ..
                    }
                )),
            "pressing Search did not open its sheet"
        );
        dioxus_compose::window::reset_window_size();
    }

    /// The reference puts everything that belongs to sending a message inside one rounded
    /// bar. A field with a button parked next to it is two controls that happen to be
    /// adjacent, which is what this used to be.
    ///
    /// And the bar floats: it is made of a material rather than being a panel, because it
    /// is chrome over the conversation at every width, and a card is the document in a
    /// desktop window.
    #[test]
    fn fr22_the_composer_is_one_rounded_bar_holding_the_send() {
        let screen = Screen::new();
        let batch = decode_batch(&screen.first).expect("the first frame did not decode");

        let mut parents = HashMap::new();
        for mutation in &batch {
            if let Mutation::Insert {
                parent_id, node_id, ..
            } = mutation
            {
                parents.insert(*node_id, *parent_id);
            }
        }
        let bar = parents[&screen.composer];

        // Found by its icon rather than by a label. The reference's send is a filled
        // circle with an arrow in it, and a word in a pill is a form's submit button.
        let send = batch
            .iter()
            .find_map(|mutation| match mutation {
                Mutation::SetProp {
                    node_id,
                    property: PropertyKind::Icon,
                    value: PropertyValue::Integer(role),
                } if *role == i64::from(IconRole::Send as u8) => Some(*node_id),
                _ => None,
            })
            .expect("the composer holds no send");
        assert_eq!(
            parents[&send], bar,
            "the send button is outside the bar the field is in, so the composer is not \
             one control"
        );

        let modifiers: Vec<&dioxus_compose::Modifier> = batch
            .iter()
            .filter_map(|mutation| match mutation {
                Mutation::SetModifier {
                    node_id, modifier, ..
                } if *node_id == bar => Some(modifier),
                _ => None,
            })
            .collect();
        assert!(
            modifiers.iter().any(|modifier| matches!(
                modifier,
                dioxus_compose::Modifier::ShapeRole(ShapeRole::Full)
            )),
            "the composer is not the reference's pill: {modifiers:?}"
        );
        assert!(
            modifiers.iter().any(|modifier| matches!(
                modifier,
                dioxus_compose::Modifier::Material(MaterialRole::Regular)
            )),
            "the composer is not made of a material, so it cannot float as chrome: {modifiers:?}"
        );
    }

    /// The window is chrome, said on the frame at the root of the tree. That is what asks
    /// the platform to put its own material behind the window, and without it every
    /// surface in the window sits on an opaque page and the desktop never shows through.
    #[test]
    fn fr29_the_window_asks_to_be_made_of_chrome() {
        let screen = Screen::new();
        let batch = decode_batch(&screen.first).expect("the first frame did not decode");
        // The root is the one node nothing else holds.
        let held: HashSet<u32> = batch
            .iter()
            .filter_map(|mutation| match mutation {
                Mutation::Insert {
                    parent_id, node_id, ..
                } if *parent_id != 0 => Some(*node_id),
                _ => None,
            })
            .collect();
        let root = batch
            .iter()
            .find_map(|mutation| match mutation {
                Mutation::Create { node_id, .. } if !held.contains(node_id) => Some(*node_id),
                _ => None,
            })
            .expect("every node the first frame created hangs from another");
        assert!(
            batch.iter().any(|mutation| matches!(
                mutation,
                Mutation::Create { node_id, widget: WidgetKind::Scaffold } if *node_id == root
            )),
            "the root of the chat is not its frame"
        );
        assert!(
            batch.iter().any(|mutation| matches!(
                mutation,
                Mutation::SetModifier {
                    node_id,
                    modifier: dioxus_compose::Modifier::Material(MaterialRole::Chrome),
                    ..
                } if *node_id == root
            )),
            "the frame does not ask for the window's material"
        );
    }

    /// Nothing has been said yet, so the middle of the screen says what the screen is.
    /// It is not a message: an introduction under the assistant's name is something the
    /// assistant never said, sitting in the transcript for good.
    #[test]
    fn fr22_an_empty_conversation_says_what_it_is_in_the_middle() {
        let mut screen = Screen::new();
        screen.open_window();
        // Two lines, and a question rather than an instruction. The reference greets you
        // and asks what you are thinking about; it does not tell you which key sends.
        let greeting = "What are you thinking about?";
        assert!(
            screen.texts.values().any(|text| text == "Hello."),
            "an empty conversation does not greet"
        );
        assert!(
            screen.texts.values().any(|text| text == greeting),
            "an empty conversation does not say what the screen is"
        );
        assert!(
            screen
                .texts
                .values()
                .all(|text| !text.contains("Shift+Enter")),
            "the screen explains its own composer, which the reference does not"
        );
        assert!(
            screen
                .texts
                .values()
                .all(|text| !text.starts_with("You said:")),
            "an empty conversation already holds a reply"
        );

        // And it leaves as soon as there is something to read, which is what lets it say
        // the thing that is only true before then.
        let node = *screen
            .texts
            .iter()
            .find(|(_, text)| *text == greeting)
            .map(|(node_id, _)| node_id)
            .expect("the greeting has no node");
        let (composer, handler) = (screen.composer, screen.submit_handler);
        screen.encode(composer, handler, EventPayload::TextSubmitted("hello"));
        let batch = screen
            .host
            .dispatch_event(&screen.event)
            .expect("the send failed")
            .0
            .to_vec();
        // A removal takes the subtree with it, so what has to be gone is the greeting or
        // something it hangs from.
        let mut parents = HashMap::new();
        for mutation in decode_batch(&screen.first).expect("the first frame did not decode") {
            if let Mutation::Insert {
                parent_id, node_id, ..
            } = mutation
            {
                parents.insert(node_id, parent_id);
            }
        }
        let mut chain = vec![node];
        while let Some(parent) = parents.get(chain.last().expect("the chain is never empty")) {
            chain.push(*parent);
        }
        let removed = decode_batch(&batch)
            .expect("the send did not decode")
            .iter()
            .any(|mutation| {
                matches!(mutation, Mutation::Remove { node_id } if chain.contains(node_id))
            });
        assert!(
            removed,
            "the greeting stayed on screen once the conversation had started"
        );
    }

    /// The hundredth keystroke has to cost what the third one cost. A per-event buffer
    /// that is grown rather than reused shows up here as a count that climbs.
    #[test]
    fn nfr9_allocations_do_not_grow_across_repeated_interactions() {
        let mut screen = Screen::new();
        screen.open_window();
        for _ in 0..2 {
            screen.type_into_composer("warm");
        }

        let allocations: Vec<_> = (0..REPEATED_INTERACTIONS)
            .map(|_| screen.type_into_composer("a message being typed"))
            .collect();
        let expected = allocations[0];

        assert!(
            allocations.iter().all(|&count| count == expected),
            "per-interaction allocations grew or varied: {allocations:?}"
        );
    }
}
