//! The chat sample's screen as it was when the benchmark scenarios were frozen: a
//! scrollback of messages, a composer where Enter sends, conversations in a destination
//! set, and an assistant whose reply arrives a few characters at a time.
//!
//! Measurement fixture. Taken from `samples/chat/src/lib.rs` at fea50dc5, the last commit
//! before 8d963582 and 51390409 redrew the chat sample on 2026-11-13, and checked widget by
//! widget against the rsx! screen the Dioxus path measures (dioxus-compose dd1af76b,
//! `samples/native-widgets/chat/src/lib.rs`, the same screen compose-rust held at
//! 22822c13). The two built the same tree; only the assistant's canned reply text differed,
//! and there the rsx! screen decided (see `assistant.rs`). Builder chains became today's
//! function and call macro form, and nothing else changed.
//!
//! It lives in the bench, not in `samples/`, because the comparison needs the same screen
//! on both paths. The Dioxus path is pinned with this screen, and the shipped sample has
//! since been redrawn after its design reference, so it no longer builds the tree the
//! pinned path builds. What is measured here is the authoring path and the runtime under
//! this screen, not the look of today's sample. Change nothing that changes the tree it
//! builds: the comparison only counts runs where both paths build the same tree after
//! every step.
//!
//! The tests, the launch, the theme, the window and the platform entry points of the
//! sample are left out. The bench starts the root composable on a Host of its own and
//! never opens a window.

use compose_rust::runtime::*;
use compose_rust::ui::*;
use std::rc::Rc;

mod assistant;

use assistant::{Length, Settings, Turns};

/// One message in a conversation.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Message {
    pub id: u64,
    pub token: u64,
    pub conversation: u64,
    pub from_user: bool,
    pub text: String,
    pub streaming: bool,
}

#[derive(Clone, Debug, PartialEq, Eq)]
struct Conversation {
    id: u64,
    title: String,
}

impl Conversation {
    const UNTITLED: &'static str = "New chat";

    fn label(&self) -> String {
        if self.title.is_empty() {
            Self::UNTITLED.to_owned()
        } else {
            self.title.clone()
        }
    }
}

const TITLE_CHARS: usize = 24;

/// A conversation is named after the first thing said in it.
fn title_from(text: &str) -> String {
    let mut title: String = text.chars().take(TITLE_CHARS).collect();
    if text.chars().nth(TITLE_CHARS).is_some() {
        title.push('\u{2026}');
    }
    title
}

const RECENT_CONVERSATIONS: usize = 5;

const DESTINATIONS_ABOVE_THE_CHATS: usize = 4;

fn opening_messages() -> Vec<Message> {
    Vec::new()
}

const SPARK_ON_THE_EMPTY_SCREEN: f32 = 39.0;
const SPARK_TO_GREETING: f32 = 6.0;
const SPARK_IN_THE_STRIP: f32 = 22.0;
const ROOM_INSIDE_THE_COMPOSER: f32 = 8.0;
const THE_SEND_KEY: f32 = 36.0;
const BESIDE_A_COMPOSER_KEY: f32 = 4.0;
const THE_ACCOUNT_PICTURE: f32 = 20.0;
const PAGE_INSET: f32 = 8.0;

/// The mark, at a size.
fn spark(size: f32) {
    Image!(
        compose_rust::asset::asset(
            compose_rust::schema::AssetKind::Svg,
            include_bytes!("../assets/spark.svg"),
        ),
        modifier = Modifier.width(size).height(size)
    );
}

/// What an empty conversation shows.
fn opening_greeting() {
    Column!(
        modifier = Modifier.padding_role(SpaceRole::Lg),
        space_role = SpaceRole::Xs,
        alignment = Alignment::Center,
        {
            spark(SPARK_ON_THE_EMPTY_SCREEN);
            Spacer!(modifier = Modifier.height(SPARK_TO_GREETING));
            Text!(
                "Hello.",
                type_role = TypeRole::Display,
                text_align = TextAlign::Center
            );
            Text!(
                "What are you thinking about?",
                type_role = TypeRole::Display,
                text_align = TextAlign::Center
            );
        }
    );
}

/// The widest the thread may be, per class.
fn thread_width(window: &WindowSize) -> Option<f32> {
    match window.class {
        WindowSizeClass::Compact => None,
        WindowSizeClass::Medium => Some(WindowSizeClass::MEDIUM_MIN_WIDTH_DP),
        WindowSizeClass::Expanded => Some(THREAD_COLUMN),
    }
}

const THREAD_COLUMN: f32 = 787.0;

/// The assistant's settings, in a sheet.
#[Composable]
fn settings_panel(settings: Settings, change: Rc<dyn Fn(Settings)>, close: Rc<dyn Fn()>) {
    Column!(
        modifier = Modifier.fill_max_width(),
        space_role = SpaceRole::Md,
        {
            Row!(
                modifier = Modifier.fill_max_width(),
                alignment = Alignment::CenterStart,
                {
                    Text!(
                        "Assistant",
                        type_role = TypeRole::Subtitle,
                        modifier = Modifier.weight(1.0)
                    );
                    let close = Rc::clone(&close);
                    Button!("Done", move || close(), variant = ButtonVariant::Filled);
                }
            );
            Separator!(None);

            Text!(
                "Reply length",
                type_role = TypeRole::Label,
                color = Paint::Role(ColorRole::OnSurfaceVariant)
            );
            for length in Length::ALL {
                let change = Rc::clone(&change);
                key(length.label(), || {
                    Row!(
                        modifier = Modifier.fill_max_width(),
                        space_role = SpaceRole::Sm,
                        alignment = Alignment::CenterStart,
                        {
                            RadioButton!(settings.length == length, move |_| change(Settings {
                                length,
                                ..settings
                            }));
                            Text!(length.label(), type_role = TypeRole::Body);
                        }
                    );
                });
            }

            Separator!(None);
            Row!(
                modifier = Modifier.fill_max_width(),
                space_role = SpaceRole::Sm,
                alignment = Alignment::CenterStart,
                {
                    Column!(modifier = Modifier.weight(1.0), {
                        Text!("Type the reply out", type_role = TypeRole::Body);
                        Text!(
                            "Off, and the whole answer lands at once.",
                            type_role = TypeRole::Caption,
                            color = Paint::Role(ColorRole::OnSurfaceVariant)
                        );
                    });
                    let change = Rc::clone(&change);
                    Switch!(settings.streaming, move |streaming| {
                        change(Settings {
                            streaming,
                            ..settings
                        })
                    });
                }
            );

            Text!(
                format!("{} characters a second", settings.speed as u32),
                type_role = TypeRole::Label,
                color = Paint::Role(ColorRole::OnSurfaceVariant)
            );
            let change = Rc::clone(&change);
            Slider!(
                settings.speed,
                move |speed| change(Settings { speed, ..settings }),
                range = (assistant::SLOWEST, assistant::FASTEST),
                enabled = settings.streaming
            );
        }
    );
}

/// The chat.
#[Composable]
pub fn app() {
    let window = current_window_size();
    let measure = thread_width(&window);
    // Shared with the assistant's reply, which is written a few characters at a time.
    let messages =
        remember(|| mutable_state_of_with_policy(opening_messages(), never_equal_policy()));
    let next_id = remember(|| mutable_state_of(2_u64));
    let draft = remember(|| mutable_state_of_with_policy(String::new(), never_equal_policy()));
    let turns = remember(Turns::default);
    let settings = remember(|| mutable_state_of(Settings::default()));
    let settings_open = remember(|| mutable_state_of(false));
    let length_open = remember(|| mutable_state_of(false));
    let more_open = remember(|| mutable_state_of(false));
    let search_open = remember(|| mutable_state_of(false));
    let search_query = remember(|| mutable_state_of(String::new()));
    let conversations = remember(|| {
        mutable_state_of_with_policy(
            vec![Conversation {
                id: 1,
                title: String::new(),
            }],
            never_equal_policy(),
        )
    });
    let current = remember(|| mutable_state_of(1_u64));
    let next_conversation = remember(|| mutable_state_of(2_u64));
    // Where a reply runs while it arrives. A reply still arriving when the chat leaves the
    // composition stops with it.
    let replies = remember_coroutine_scope();

    let send: Rc<dyn Fn(String)> = {
        let (messages, next_id, draft, turns, settings, conversations, current, replies) = (
            messages,
            next_id,
            draft,
            turns.clone(),
            settings,
            conversations,
            current,
            replies,
        );
        Rc::new(move |text: String| {
            let text = text.trim().to_owned();
            if text.is_empty() {
                return;
            }
            let token = turns.begin();
            let id = next_id.get_untracked();
            next_id.set(id + 2);
            let conversation = current.get_untracked();
            // The empty reply goes in now, on the UI thread, so the reply only ever has to
            // append.
            messages.update(|list| {
                list.push(Message {
                    id,
                    token,
                    conversation,
                    from_user: true,
                    text: text.clone(),
                    streaming: false,
                });
                list.push(Message {
                    id: id + 1,
                    token,
                    conversation,
                    from_user: false,
                    text: String::new(),
                    streaming: true,
                });
            });
            conversations.update(|list| {
                if let Some(entry) = list.iter_mut().find(|entry| entry.id == conversation) {
                    if entry.title.is_empty() {
                        entry.title = title_from(&text);
                    }
                }
            });
            draft.set(String::new());
            replies.launch(assistant::stream_reply(
                text,
                token,
                turns.clone(),
                settings.get_untracked(),
                messages,
            ));
        })
    };

    let start_conversation: Rc<dyn Fn()> = {
        let (next_conversation, conversations, current) =
            (next_conversation, conversations, current);
        Rc::new(move || {
            let id = next_conversation.get_untracked();
            next_conversation.set(id + 1);
            conversations.update(|list| {
                list.push(Conversation {
                    id,
                    title: String::new(),
                })
            });
            current.set(id);
        })
    };

    let delete_current: Rc<dyn Fn()> = {
        let (current, conversations, messages, turns, next_conversation) = (
            current,
            conversations,
            messages,
            turns.clone(),
            next_conversation,
        );
        Rc::new(move || {
            let gone = current.get_untracked();
            let entries = conversations.get_untracked();
            let Some(at) = entries.iter().position(|entry| entry.id == gone) else {
                return;
            };
            let removed = entries[at].clone();
            let lines = messages.get_untracked();
            turns.begin();
            conversations.update(|list| {
                list.remove(at);
            });
            messages.update(|list| list.retain(|message| message.conversation != gone));
            if conversations.with(Vec::is_empty) {
                let id = next_conversation.get_untracked();
                next_conversation.set(id + 1);
                conversations.update(|list| {
                    list.push(Conversation {
                        id,
                        title: String::new(),
                    })
                });
            }
            let next = conversations.with(|list| list[at.min(list.len() - 1)].id);
            current.set(next);
            let (conversations, messages, current) = (conversations, messages, current);
            compose_rust::Message::new(format!("Deleted \u{201c}{}\u{201d}", removed.label()))
                .with_action("Undo", move |()| {
                    conversations.update(|list| list.insert(at, removed.clone()));
                    messages.set(lines.clone());
                    current.set(gone);
                })
                .with_duration(MessageDuration::Long)
                .show();
        })
    };

    let current_id = current.get();
    let thread: Vec<usize> = messages.with(|list| {
        list.iter()
            .enumerate()
            .filter(|(_, message)| message.conversation == current_id)
            .map(|(index, _)| index)
            .collect()
    });
    let count = thread.len();
    let busy = messages.with(|list| {
        list.last()
            .is_some_and(|message| message.streaming && message.conversation == current_id)
    });
    let keys: Vec<String> = messages.with(|list| {
        thread
            .iter()
            .map(|index| list[*index].id.to_string())
            .collect()
    });
    let rows = thread.clone();
    let query = search_query.get().to_lowercase();
    let recent: Vec<Conversation> = conversations.with(|list| {
        list.iter()
            .rev()
            .filter(|entry| !entry.title.is_empty())
            .filter(|entry| query.is_empty() || entry.label().to_lowercase().contains(&query))
            .take(RECENT_CONVERSATIONS)
            .cloned()
            .collect()
    });
    let show_search = window.is_expanded();
    let selected = recent
        .iter()
        .position(|entry| entry.id == current_id)
        .map_or(0, |index| {
            index + DESTINATIONS_ABOVE_THE_CHATS + usize::from(show_search)
        });
    let current_settings = settings.get();
    let conversation_count = conversations.with(Vec::len);

    Scaffold!(
        modifier = Modifier.material(MaterialRole::Chrome),
        top_bar = Some(|| {
            TopAppBar!(modifier = Modifier.fill_max_width(), {
                Spacer!(modifier = Modifier.weight(1.0));
                let start = Rc::clone(&start_conversation);
                Button!(
                    "",
                    move || start(),
                    icon = IconRole::Compose,
                    variant = ButtonVariant::Text,
                    color = Paint::Role(ColorRole::OnSurfaceVariant)
                );
                let (dismiss, open) = (more_open, more_open);
                Menu!(
                    more_open.get(),
                    move || dismiss.set(false),
                    anchor = Some(move || {
                        Button!(
                            "",
                            move || open.set(true),
                            icon = IconRole::More,
                            variant = ButtonVariant::Text,
                            color = Paint::Role(ColorRole::OnSurfaceVariant)
                        );
                    }),
                    {
                        let (more, settings_open) = (more_open, settings_open);
                        Button!(
                            "Assistant settings",
                            move || {
                                more.set(false);
                                settings_open.set(true);
                            },
                            variant = ButtonVariant::Text,
                            modifier = Modifier.fill_max_width()
                        );
                        let (more, delete) = (more_open, Rc::clone(&delete_current));
                        Button!(
                            "Delete conversation",
                            move || {
                                more.set(false);
                                delete();
                            },
                            variant = ButtonVariant::Text,
                            color = Paint::Role(ColorRole::Error),
                            modifier = Modifier.fill_max_width(),
                            enabled = conversation_count > 1
                        );
                    }
                );
            });
        }),
        bottom_bar = Some(|| {
            Navigation!(
                selected,
                head = Some(|| {
                    Row!(
                        modifier = Modifier.fill_max_width().padding_role(SpaceRole::Sm),
                        space_role = SpaceRole::Sm,
                        alignment = Alignment::CenterStart,
                        {
                            spark(SPARK_IN_THE_STRIP);
                            Text!("Chat", type_role = TypeRole::Subtitle);
                        }
                    );
                }),
                foot = Some(|| {
                    Row!(
                        modifier = Modifier.fill_max_width().padding_role(SpaceRole::Sm),
                        space_role = SpaceRole::Sm,
                        alignment = Alignment::CenterStart,
                        {
                            Box!(
                                modifier = Modifier
                                    .width(THE_ACCOUNT_PICTURE)
                                    .height(THE_ACCOUNT_PICTURE)
                                    .shape_role(ShapeRole::Full)
                                    .background(Paint::Role(ColorRole::SecondaryContainer)),
                                alignment = Alignment::Center,
                                {
                                    Text!(
                                        "L",
                                        type_role = TypeRole::Caption,
                                        color = Paint::Role(ColorRole::OnSecondaryContainer)
                                    );
                                }
                            );
                            Column!({
                                Text!("Signed in", type_role = TypeRole::Label);
                                Text!(
                                    "Local",
                                    type_role = TypeRole::Caption,
                                    color = Paint::Role(ColorRole::OnSurfaceVariant)
                                );
                            });
                        }
                    );
                }),
                {
                    let start = Rc::clone(&start_conversation);
                    NavigationItem!("New chat", move || start(), icon = IconRole::Compose);
                    if show_search {
                        let open = search_open;
                        NavigationItem!("Search", move || open.set(true), icon = IconRole::Search);
                    }
                    NavigationItem!("Images", || {}, icon = IconRole::Image);
                    NavigationItem!("Videos", || {}, icon = IconRole::Video);
                    NavigationItem!("Library", || {}, icon = IconRole::Library);
                    for conversation in recent.iter().cloned() {
                        let pick = current;
                        let id = conversation.id;
                        key(id, || {
                            NavigationItem!(
                                conversation.label(),
                                move || pick.set(id),
                                section = "Chats"
                            );
                        });
                    }
                }
            );
        }),
        {
            Box!(
                modifier = Modifier.fill_max_width().fill_max_height(),
                alignment = Alignment::TopCenter,
                {
                    Column!(
                        modifier = Modifier
                            .fill_max_width_if(measure.is_none())
                            .width_if(measure)
                            .fill_max_height()
                            .padding(PAGE_INSET),
                        space_role = SpaceRole::Md,
                        {
                            if busy {
                                IndeterminateProgressIndicator!();
                            }

                            Box!(
                                modifier = Modifier.fill_max_width().weight(1.0),
                                alignment = Alignment::Center,
                                {
                                    let (keys, rows, messages) =
                                        (keys.clone(), rows.clone(), messages);
                                    LazyColumn!(
                                        modifier = Modifier.fill_max_width().fill_max_height(),
                                        content = move |list| {
                                            list.items_keyed(
                                                count,
                                                move |index| keys[index].clone(),
                                                move |position| {
                                                    message_row(&rows, position, &messages)
                                                },
                                            );
                                        }
                                    );
                                    if count == 0 {
                                        opening_greeting();
                                    }
                                }
                            );

                            composer_row(
                                &more_open,
                                &search_open,
                                &settings_open,
                                &length_open,
                                &draft,
                                &settings,
                                current_settings,
                                &send,
                            );
                        }
                    );

                    let close = settings_open;
                    Sheet!(
                        settings_open.get(),
                        move || close.set(false),
                        modifier = Modifier.fill_max_width(),
                        {
                            let (next, close) = (settings, settings_open);
                            settings_panel(
                                current_settings,
                                Rc::new(move |value| next.set(value)),
                                Rc::new(move || close.set(false)),
                            );
                        }
                    );

                    let close = search_open;
                    Sheet!(
                        search_open.get(),
                        move || close.set(false),
                        modifier = Modifier.fill_max_width(),
                        {
                            Column!(
                                modifier = Modifier.fill_max_width(),
                                space_role = SpaceRole::Md,
                                {
                                    Row!(
                                        modifier = Modifier.fill_max_width(),
                                        alignment = Alignment::CenterStart,
                                        {
                                            Text!(
                                                "Search conversations",
                                                type_role = TypeRole::Subtitle,
                                                modifier = Modifier.weight(1.0)
                                            );
                                            let done = search_open;
                                            Button!(
                                                "Done",
                                                move || done.set(false),
                                                variant = ButtonVariant::Filled
                                            );
                                        }
                                    );
                                    let typed = search_query;
                                    TextField!(
                                        "",
                                        move |value| typed.set(value),
                                        modifier = Modifier.fill_max_width(),
                                        placeholder = "Search conversations"
                                    );
                                    if !search_query.get().is_empty() {
                                        let clear = search_query;
                                        Button!(
                                            "Clear search",
                                            move || clear.set(String::new()),
                                            modifier = Modifier.fill_max_width(),
                                            variant = ButtonVariant::Text
                                        );
                                    }
                                }
                            );
                        }
                    );
                }
            );
        }
    );
}

/// One message, composed when the Renderer's window reaches it.
#[Composable]
fn message_row(rows: &[usize], position: usize, messages: &MutableState<Vec<Message>>) {
    let index = rows[position];
    let message = messages.with(|list| list[index].clone());
    let starts_a_run = position == 0
        || messages.with(|list| list[rows[position - 1]].from_user != message.from_user);
    let (fill, ink) = if message.from_user {
        (ColorRole::Primary, ColorRole::OnPrimary)
    } else {
        (ColorRole::SurfaceVariant, ColorRole::OnSurfaceVariant)
    };
    let side = if message.from_user {
        Alignment::CenterEnd
    } else {
        Alignment::CenterStart
    };
    Box!(
        modifier = Modifier.fill_max_width().padding_role(if starts_a_run {
            SpaceRole::Sm
        } else {
            SpaceRole::Xs
        }),
        alignment = side,
        {
            Column!(space_role = SpaceRole::Xs, alignment = side, {
                if starts_a_run {
                    Text!(
                        if message.from_user {
                            "You"
                        } else {
                            "Assistant"
                        },
                        type_role = TypeRole::Caption,
                        color = Paint::Role(ColorRole::OnSurfaceVariant)
                    );
                }
                Column!(
                    modifier = Modifier
                        .background(Paint::Role(fill))
                        .shape_role(ShapeRole::Large)
                        .padding_role(SpaceRole::Md),
                    {
                        Text!(
                            if message.streaming {
                                format!("{}\u{2589}", message.text)
                            } else {
                                message.text.clone()
                            },
                            type_role = TypeRole::Body,
                            color = Paint::Role(ink)
                        );
                    }
                );
            });
        }
    );
}

/// The composer: the add menu, the field, the length menu and the send key.
#[Composable]
#[allow(clippy::too_many_arguments)]
fn composer_row(
    more_open: &MutableState<bool>,
    search_open: &MutableState<bool>,
    settings_open: &MutableState<bool>,
    length_open: &MutableState<bool>,
    draft: &MutableState<String>,
    settings: &MutableState<Settings>,
    current_settings: Settings,
    send: &Rc<dyn Fn(String)>,
) {
    Row!(
        modifier = Modifier
            .fill_max_width()
            .material(MaterialRole::Regular)
            .shape_role(ShapeRole::Full)
            .padding(ROOM_INSIDE_THE_COMPOSER),
        spacing = BESIDE_A_COMPOSER_KEY,
        alignment = Alignment::CenterStart,
        {
            let (dismiss, open) = (*more_open, *more_open);
            Menu!(
                more_open.get(),
                move || dismiss.set(false),
                anchor = Some(move || {
                    Button!(
                        "",
                        move || open.set(true),
                        icon = IconRole::Add,
                        variant = ButtonVariant::Text,
                        color = Paint::Role(ColorRole::OnSurfaceVariant)
                    );
                }),
                {
                    let (more, search) = (*more_open, *search_open);
                    Button!(
                        "Search conversations",
                        move || {
                            more.set(false);
                            search.set(true);
                        },
                        icon = IconRole::Search,
                        variant = ButtonVariant::Text,
                        modifier = Modifier.fill_max_width()
                    );
                    Divider!();
                    let (more, settings_open) = (*more_open, *settings_open);
                    Button!(
                        "Settings",
                        move || {
                            more.set(false);
                            settings_open.set(true);
                        },
                        icon = IconRole::Settings,
                        variant = ButtonVariant::Text,
                        modifier = Modifier.fill_max_width()
                    );
                }
            );
            let (typed, submit) = (*draft, Rc::clone(send));
            TextField!(
                "",
                move |value| typed.set(value),
                modifier = Modifier.weight(1.0),
                multiline = true,
                placeholder = "Message",
                on_submit = Some(move |value: String| submit(value))
            );
            let (dismiss, open) = (*length_open, *length_open);
            Menu!(
                length_open.get(),
                move || dismiss.set(false),
                anchor = Some(move || {
                    Button!(
                        current_settings.length.label(),
                        move || open.set(true),
                        variant = ButtonVariant::Text,
                        color = Paint::Role(ColorRole::OnSurfaceVariant)
                    );
                }),
                {
                    for length in Length::ALL {
                        let (close, settings) = (*length_open, *settings);
                        key(length.label(), || {
                            Button!(
                                length.label(),
                                move || {
                                    close.set(false);
                                    let current = settings.get_untracked();
                                    settings.set(Settings { length, ..current });
                                },
                                variant = ButtonVariant::Text,
                                modifier = Modifier.fill_max_width()
                            );
                        });
                    }
                }
            );
            let (pressed, current) = (Rc::clone(send), *draft);
            Button!(
                "",
                move || pressed(current.get_untracked()),
                icon = IconRole::Send,
                variant = ButtonVariant::Tonal,
                modifier = Modifier
                    .shape_role(ShapeRole::Full)
                    .width(THE_SEND_KEY)
                    .height(THE_SEND_KEY),
                color = Paint::Role(ColorRole::OnSurface)
            );
        }
    );
}
