//! An LLM chat interface with no network, clone coded from Google Gemini.
//!
//! Conversations down the side, a scrollback of messages, a composer where Enter sends and
//! Shift+Enter starts a new line, and an assistant whose reply arrives a few characters at
//! a time from a worker thread while the user keeps scrolling and typing. The worker
//! writes a `MutableState`, which is `Send`, and the Host asks for the frame.
//!
//! The conversations are a destination set rather than a list this code lays out, so the
//! Renderer draws them as a bar along the bottom of a phone, a rail beside a tablet and the
//! reference's sidebar on a desktop, from one declaration.
//!
//! The scrollback is a `LazyColumn` so a long conversation costs widgets in proportion to
//! what is on screen. Each message carries an id that never changes and that id is its key,
//! so the message growing at the bottom does not disturb the ones above it.

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

///
/// Five: new chat, search, images, videos and the library. Search is one of them at every
/// width, because it is a destination like the others rather than a desktop extra; it used
/// to appear only on an expanded window, so a phone could reach it only through the
/// composer's menu.
const DESTINATIONS_ABOVE_THE_CHATS: usize = 5;

fn opening_messages() -> Vec<Message> {
    Vec::new()
}

const SPARK_ON_THE_EMPTY_SCREEN: f32 = 39.0;
const SPARK_TO_GREETING: f32 = 6.0;
const SPARK_IN_THE_STRIP: f32 = 22.0;
const ROOM_INSIDE_THE_COMPOSER: f32 = 8.0;
const THE_SEND_KEY: f32 = 36.0;
const BESIDE_A_COMPOSER_KEY: f32 = 4.0;
/// What the search field says while it is empty, and how a test finds it.
const SEARCH_PLACEHOLDER: &str = "Search conversations";

/// Images, videos and the library are places a full application has and this sample does
/// not; pressing one says so rather than doing nothing.
fn not_in_this_sample(place: &str) {
    compose_rust::Message::new(format!("{place} is not part of this sample")).show();
}

const THE_ACCOUNT_PICTURE: f32 = 20.0;
const PAGE_INSET: f32 = 8.0;

/// The mark, at a size.
fn spark(size: f32) {
    Image(compose_rust::asset::asset(
        compose_rust::schema::AssetKind::Svg,
        include_bytes!("../assets/spark.svg"),
    ))
    .modifier(Modifier.width(size).height(size));
}

/// What an empty conversation shows.
fn opening_greeting() {
    Column()
        .modifier(Modifier.padding_role(SpaceRole::Lg))
        .space_role(SpaceRole::Xs)
        .alignment(Alignment::Center)
        .content(|| {
            spark(SPARK_ON_THE_EMPTY_SCREEN);
            Spacer().modifier(Modifier.height(SPARK_TO_GREETING));
            Text("Hello.")
                .type_role(TypeRole::Display)
                .text_align(TextAlign::Center);
            Text("What are you thinking about?")
                .type_role(TypeRole::Display)
                .text_align(TextAlign::Center);
        });
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
#[composable]
fn settings_panel(settings: Settings, change: Rc<dyn Fn(Settings)>, close: Rc<dyn Fn()>) {
    Column()
        .modifier(Modifier.fill_max_width())
        .space_role(SpaceRole::Md)
        .content(|| {
            Row()
                .modifier(Modifier.fill_max_width())
                .alignment(Alignment::CenterStart)
                .content(|| {
                    Text("Assistant")
                        .type_role(TypeRole::Subtitle)
                        .modifier(Modifier.weight(1.0));
                    let close = Rc::clone(&close);
                    Button("Done")
                        .variant(ButtonVariant::Filled)
                        .on_click(move || close());
                });
            Separator(None);

            Text("Reply length")
                .type_role(TypeRole::Label)
                .color(Paint::Role(ColorRole::OnSurfaceVariant));
            for length in Length::ALL {
                let change = Rc::clone(&change);
                key(length.label(), || {
                    Row()
                        .modifier(Modifier.fill_max_width())
                        .space_role(SpaceRole::Sm)
                        .alignment(Alignment::CenterStart)
                        .content(|| {
                            RadioButton(settings.length == length)
                                .on_change(move |_| change(Settings { length, ..settings }));
                            Text(length.label()).type_role(TypeRole::Body);
                        });
                });
            }

            Separator(None);
            Row()
                .modifier(Modifier.fill_max_width())
                .space_role(SpaceRole::Sm)
                .alignment(Alignment::CenterStart)
                .content(|| {
                    Column().modifier(Modifier.weight(1.0)).content(|| {
                        Text("Type the reply out").type_role(TypeRole::Body);
                        Text("Off, and the whole answer lands at once.")
                            .type_role(TypeRole::Caption)
                            .color(Paint::Role(ColorRole::OnSurfaceVariant));
                    });
                    let change = Rc::clone(&change);
                    Switch(settings.streaming).on_change(move |streaming| {
                        change(Settings {
                            streaming,
                            ..settings
                        })
                    });
                });

            Text(format!("{} characters a second", settings.speed as u32))
                .type_role(TypeRole::Label)
                .color(Paint::Role(ColorRole::OnSurfaceVariant));
            let change = Rc::clone(&change);
            Slider(settings.speed)
                .range(assistant::SLOWEST, assistant::FASTEST)
                .enabled(settings.streaming)
                .on_change(move |speed| change(Settings { speed, ..settings }));
        });
}

/// The chat.
#[composable]
pub fn app() {
    let window = current_window_size();
    let measure = thread_width(&window);
    // Shared with the assistant thread, which is why it is a state that can cross threads.
    let messages =
        remember(|| mutable_state_of_with_policy(opening_messages(), never_equal_policy()));
    let next_id = remember(|| mutable_state_of(2_u64));
    let draft = remember(|| mutable_state_of_with_policy(String::new(), never_equal_policy()));
    let turns = remember(Turns::default);
    let settings = remember(|| mutable_state_of(Settings::default()));
    let settings_open = remember(|| mutable_state_of(false));
    let length_open = remember(|| mutable_state_of(false));
    let more_open = remember(|| mutable_state_of(false));
    // The composer's own menu. It shared the top bar's signal, so pressing either key
    // opened both menus at once.
    let attach_open = remember(|| mutable_state_of(false));
    // Counts the times the search was cleared. The field keeps its own text, so emptying
    // the query alone left the old words showing; a new key replaces the field.
    let search_field = remember(|| mutable_state_of(0_u64));
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

    let send: Rc<dyn Fn(String)> = {
        let (messages, next_id, draft, turns, settings, conversations, current) = (
            messages.clone(),
            next_id.clone(),
            draft.clone(),
            turns.clone(),
            settings.clone(),
            conversations.clone(),
            current.clone(),
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
            // The empty reply goes in now, on the UI thread, so the worker only ever has
            // to append.
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
            assistant::stream_reply(
                text,
                token,
                turns.clone(),
                settings.get_untracked(),
                messages.clone(),
            );
        })
    };

    let start_conversation: Rc<dyn Fn()> = {
        let (next_conversation, conversations, current) = (
            next_conversation.clone(),
            conversations.clone(),
            current.clone(),
        );
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
            current.clone(),
            conversations.clone(),
            messages.clone(),
            turns.clone(),
            next_conversation.clone(),
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
            let (conversations, messages, current) =
                (conversations.clone(), messages.clone(), current.clone());
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
    let selected = recent
        .iter()
        .position(|entry| entry.id == current_id)
        .map_or(0, |index| index + DESTINATIONS_ABOVE_THE_CHATS);
    let current_settings = settings.get();
    let conversation_count = conversations.with(Vec::len);

    Scaffold()
        .modifier(Modifier.material(MaterialRole::Chrome))
        .top_bar(|| {
            TopAppBar().modifier(Modifier.fill_max_width()).content(|| {
                Spacer().modifier(Modifier.weight(1.0));
                let start = Rc::clone(&start_conversation);
                Button("")
                    .icon(IconRole::Compose)
                    .variant(ButtonVariant::Text)
                    .color(Paint::Role(ColorRole::OnSurfaceVariant))
                    .on_click(move || start());
                let (dismiss, open) = (more_open.clone(), more_open.clone());
                Menu(more_open.get())
                    .on_dismiss(move || dismiss.set(false))
                    .anchor(move || {
                        Button("")
                            .icon(IconRole::More)
                            .variant(ButtonVariant::Text)
                            .color(Paint::Role(ColorRole::OnSurfaceVariant))
                            .on_click(move || open.set(true));
                    })
                    .content(|| {
                        let (more, settings_open) = (more_open.clone(), settings_open.clone());
                        Button("Assistant settings")
                            .variant(ButtonVariant::Text)
                            .modifier(Modifier.fill_max_width())
                            .on_click(move || {
                                more.set(false);
                                settings_open.set(true);
                            });
                        let (more, delete) = (more_open.clone(), Rc::clone(&delete_current));
                        Button("Delete conversation")
                            .variant(ButtonVariant::Text)
                            .color(Paint::Role(ColorRole::Error))
                            .modifier(Modifier.fill_max_width())
                            .enabled(conversation_count > 1)
                            .on_click(move || {
                                more.set(false);
                                delete();
                            });
                    });
            });
        })
        .bottom_bar(|| {
            Navigation(selected)
                .head(|| {
                    Row()
                        .modifier(Modifier.fill_max_width().padding_role(SpaceRole::Sm))
                        .space_role(SpaceRole::Sm)
                        .alignment(Alignment::CenterStart)
                        .content(|| {
                            spark(SPARK_IN_THE_STRIP);
                            Text("Chat").type_role(TypeRole::Subtitle);
                        });
                })
                .foot(|| {
                    Row()
                        .modifier(Modifier.fill_max_width().padding_role(SpaceRole::Sm))
                        .space_role(SpaceRole::Sm)
                        .alignment(Alignment::CenterStart)
                        .content(|| {
                            Box()
                                .modifier(
                                    Modifier
                                        .width(THE_ACCOUNT_PICTURE)
                                        .height(THE_ACCOUNT_PICTURE)
                                        .shape_role(ShapeRole::Full)
                                        .background(Paint::Role(ColorRole::SecondaryContainer)),
                                )
                                .alignment(Alignment::Center)
                                .content(|| {
                                    Text("L")
                                        .type_role(TypeRole::Caption)
                                        .color(Paint::Role(ColorRole::OnSecondaryContainer));
                                });
                            Column().content(|| {
                                Text("Signed in").type_role(TypeRole::Label);
                                Text("Local")
                                    .type_role(TypeRole::Caption)
                                    .color(Paint::Role(ColorRole::OnSurfaceVariant));
                            });
                        });
                })
                .content(|| {
                    let start = Rc::clone(&start_conversation);
                    NavigationItem("New chat")
                        .icon(IconRole::Compose)
                        .on_click(move || start());
                    let open = search_open.clone();
                    NavigationItem("Search")
                        .icon(IconRole::Search)
                        .on_click(move || open.set(true));
                    for (place, icon) in [
                        ("Images", IconRole::Image),
                        ("Videos", IconRole::Video),
                        ("Library", IconRole::Library),
                    ] {
                        NavigationItem(place)
                            .icon(icon)
                            .on_click(move || not_in_this_sample(place));
                    }
                    for conversation in recent.iter().cloned() {
                        let pick = current.clone();
                        let id = conversation.id;
                        key(id, || {
                            NavigationItem(conversation.label())
                                .section("Chats")
                                .on_click(move || pick.set(id));
                        });
                    }
                });
        })
        .content(|| {
            Box()
                .modifier(Modifier.fill_max_width().fill_max_height())
                .alignment(Alignment::TopCenter)
                .content(|| {
                    Column()
                        .modifier(
                            Modifier
                                .fill_max_width_if(measure.is_none())
                                .width_if(measure)
                                .fill_max_height()
                                .padding(PAGE_INSET),
                        )
                        .space_role(SpaceRole::Md)
                        .content(|| {
                            if busy {
                                IndeterminateProgressIndicator();
                            }

                            Box()
                                .modifier(Modifier.fill_max_width().weight(1.0))
                                .alignment(Alignment::Center)
                                .content(|| {
                                    let (keys, rows, messages) =
                                        (keys.clone(), rows.clone(), messages.clone());
                                    LazyColumn()
                                        .modifier(Modifier.fill_max_width().fill_max_height())
                                        .content(move |list| {
                                            list.items_keyed(
                                                count,
                                                move |index| keys[index].clone(),
                                                move |position| {
                                                    message_row(&rows, position, &messages)
                                                },
                                            );
                                        });
                                    if count == 0 {
                                        opening_greeting();
                                    }
                                });

                            composer_row(
                                &attach_open,
                                &search_open,
                                &settings_open,
                                &length_open,
                                &draft,
                                &settings,
                                current_settings,
                                &send,
                            );
                        });

                    let close = settings_open.clone();
                    Sheet(settings_open.get())
                        .on_dismiss(move || close.set(false))
                        .modifier(Modifier.fill_max_width())
                        .content(|| {
                            let (next, close) = (settings.clone(), settings_open.clone());
                            settings_panel(
                                current_settings,
                                Rc::new(move |value| next.set(value)),
                                Rc::new(move || close.set(false)),
                            );
                        });

                    let close = search_open.clone();
                    Sheet(search_open.get())
                        .on_dismiss(move || close.set(false))
                        .modifier(Modifier.fill_max_width())
                        .content(|| {
                            Column()
                                .modifier(Modifier.fill_max_width())
                                .space_role(SpaceRole::Md)
                                .content(|| {
                                    Row()
                                        .modifier(Modifier.fill_max_width())
                                        .alignment(Alignment::CenterStart)
                                        .content(|| {
                                            Text("Search conversations")
                                                .type_role(TypeRole::Subtitle)
                                                .modifier(Modifier.weight(1.0));
                                            let done = search_open.clone();
                                            Button("Done")
                                                .variant(ButtonVariant::Filled)
                                                .on_click(move || done.set(false));
                                        });
                                    let typed = search_query.clone();
                                    key(search_field.get(), || {
                                        TextField()
                                            .modifier(Modifier.fill_max_width())
                                            .placeholder(SEARCH_PLACEHOLDER)
                                            .on_value_change(move |value| typed.set(value));
                                    });
                                    if !search_query.get().is_empty() {
                                        let (clear, field) =
                                            (search_query.clone(), search_field.clone());
                                        Button("Clear search")
                                            .modifier(Modifier.fill_max_width())
                                            .variant(ButtonVariant::Text)
                                            .on_click(move || {
                                                clear.set(String::new());
                                                field.update(|count| *count += 1);
                                            });
                                    }
                                });
                        });
                });
        });
}

/// One message, composed when the Renderer's window reaches it.
#[composable]
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
    Box()
        .modifier(Modifier.fill_max_width().padding_role(if starts_a_run {
            SpaceRole::Sm
        } else {
            SpaceRole::Xs
        }))
        .alignment(side)
        .content(|| {
            Column()
                .space_role(SpaceRole::Xs)
                .alignment(side)
                .content(|| {
                    if starts_a_run {
                        Text(if message.from_user {
                            "You"
                        } else {
                            "Assistant"
                        })
                        .type_role(TypeRole::Caption)
                        .color(Paint::Role(ColorRole::OnSurfaceVariant));
                    }
                    Column()
                        .modifier(
                            Modifier
                                .background(Paint::Role(fill))
                                .shape_role(ShapeRole::Large)
                                .padding_role(SpaceRole::Md),
                        )
                        .content(|| {
                            Text(if message.streaming {
                                format!("{}\u{2589}", message.text)
                            } else {
                                message.text.clone()
                            })
                            .type_role(TypeRole::Body)
                            .color(Paint::Role(ink));
                        });
                });
        });
}

/// The composer: the add menu, the field, the length menu and the send key.
#[composable]
#[allow(clippy::too_many_arguments)]
fn composer_row(
    attach_open: &MutableState<bool>,
    search_open: &MutableState<bool>,
    settings_open: &MutableState<bool>,
    length_open: &MutableState<bool>,
    draft: &MutableState<String>,
    settings: &MutableState<Settings>,
    current_settings: Settings,
    send: &Rc<dyn Fn(String)>,
) {
    Row()
        .modifier(
            Modifier
                .fill_max_width()
                .material(MaterialRole::Regular)
                .shape_role(ShapeRole::Full)
                .padding(ROOM_INSIDE_THE_COMPOSER),
        )
        .spacing(BESIDE_A_COMPOSER_KEY)
        .alignment(Alignment::CenterStart)
        .content(|| {
            let (dismiss, open) = (attach_open.clone(), attach_open.clone());
            Menu(attach_open.get())
                .on_dismiss(move || dismiss.set(false))
                .anchor(move || {
                    Button("")
                        .icon(IconRole::Add)
                        .variant(ButtonVariant::Text)
                        .color(Paint::Role(ColorRole::OnSurfaceVariant))
                        .on_click(move || open.set(true));
                })
                .content(|| {
                    let (more, search) = (attach_open.clone(), search_open.clone());
                    Button("Search conversations")
                        .icon(IconRole::Search)
                        .variant(ButtonVariant::Text)
                        .modifier(Modifier.fill_max_width())
                        .on_click(move || {
                            more.set(false);
                            search.set(true);
                        });
                    Divider();
                    let (more, settings_open) = (attach_open.clone(), settings_open.clone());
                    Button("Settings")
                        .icon(IconRole::Settings)
                        .variant(ButtonVariant::Text)
                        .modifier(Modifier.fill_max_width())
                        .on_click(move || {
                            more.set(false);
                            settings_open.set(true);
                        });
                });
            let (typed, submit) = (draft.clone(), Rc::clone(send));
            TextField()
                .modifier(Modifier.weight(1.0))
                .multiline(true)
                .placeholder("Message")
                .on_value_change(move |value| typed.set(value))
                .on_submit(move |value: String| submit(value));
            let (dismiss, open) = (length_open.clone(), length_open.clone());
            Menu(length_open.get())
                .on_dismiss(move || dismiss.set(false))
                .anchor(move || {
                    Button(current_settings.length.label())
                        .variant(ButtonVariant::Text)
                        .color(Paint::Role(ColorRole::OnSurfaceVariant))
                        .on_click(move || open.set(true));
                })
                .content(|| {
                    for length in Length::ALL {
                        let (close, settings) = (length_open.clone(), settings.clone());
                        key(length.label(), || {
                            Button(length.label())
                                .variant(ButtonVariant::Text)
                                .modifier(Modifier.fill_max_width())
                                .on_click(move || {
                                    close.set(false);
                                    let current = settings.get_untracked();
                                    settings.set(Settings { length, ..current });
                                });
                        });
                    }
                });
            let (pressed, current) = (Rc::clone(send), draft.clone());
            Button("")
                .icon(IconRole::Send)
                .variant(ButtonVariant::Tonal)
                .modifier(
                    Modifier
                        .shape_role(ShapeRole::Full)
                        .width(THE_SEND_KEY)
                        .height(THE_SEND_KEY),
                )
                .color(Paint::Role(ColorRole::OnSurface))
                .on_click(move || pressed(current.get_untracked()));
        });
}

/// Runs the sample as a program of its own.
pub fn launch() {
    launch_builder().application(app);
}

/// The reference's typeface, on the roles it sets in it.
fn with_the_references_typeface(theme: Theme) -> Theme {
    let regular = compose_rust::asset::asset(
        compose_rust::schema::AssetKind::Font,
        include_bytes!("../assets/Roboto-Regular.ttf"),
    );
    let medium = compose_rust::asset::asset(
        compose_rust::schema::AssetKind::Font,
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

/// How this sample is configured, in one place because three entry points need it.
fn launch_builder() -> LaunchBuilder {
    LaunchBuilder::new()
        .with_theme(with_the_references_typeface(compose_rust::demo_theme()))
        .with_window(
            compose_rust::schema::Window::new()
                .with_title("Chat")
                .with_title_bar(compose_rust::schema::TitleBar::Normal)
                .with_icon(compose_rust::asset::asset(
                    compose_rust::schema::AssetKind::Png,
                    include_bytes!("../assets/icon.png"),
                )),
        )
}

compose_rust::android_application!({ launch_builder() }, app);
compose_rust::web_application!({ launch_builder() }, app);
compose_rust::ios_main!(launch);

#[cfg(test)]
mod tests {
    use super::*;
    use compose_rust::ComposeHost;
    use compose_rust::boundary::Host;
    use compose_rust::protocol::{HostEvent, Mutation, PropertyValue, decode_batch, encode_event};
    use compose_rust::schema::{EventPayload, PropertyKind, WidgetKind};
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
        host: ComposeHost,
        /// The first frame, kept so a control the screen declared once can still be found
        /// after other frames have gone by.
        first: Vec<u8>,
        scrollback: u32,
        range_handler: u64,
        composer: u32,
        submit_handler: u64,
        change_handler: u64,
        /// How many nodes the last frame opened, a menu or a sheet each.
        opened: usize,
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
            let mut host = ComposeHost::new(app);
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
                opened: 0,
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
            self.opened = 0;
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
                    Mutation::SetProp {
                        property: PropertyKind::Open,
                        value: PropertyValue::Bool(true),
                        ..
                    } => self.opened += 1,
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

        /// Resizes the window the way the Renderer reports it.
        fn resize(&mut self, class: WindowSizeClass, width_dp: f32) {
            self.encode(
                0,
                0,
                EventPayload::WindowSizeChanged {
                    width_dp,
                    height_dp: 700.0,
                    class,
                    height_class: WindowHeightClass::Medium,
                },
            );
            let batch = self
                .host
                .dispatch_event(&self.event)
                .expect("the resize failed")
                .0
                .to_vec();
            self.absorb(&batch);
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
        assert!(ComposeHost::new(app).rebuild().is_ok());
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
        compose_rust::window::reset_window_size();
        let mut host = ComposeHost::new(app);
        host.rebuild().expect("the first frame failed to encode");
        let event = HostEvent {
            node_id: 0,
            handler_id: 0,
            payload: EventPayload::WindowSizeChanged {
                width_dp,
                height_dp: 900.0,
                class: WindowSizeClass::from_width_dp(width_dp),
                height_class: WindowHeightClass::from_height_dp(900.0),
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
                    modifier: compose_rust::schema::Modifier::Width(width),
                    ..
                } => Some(*width),
                _ => None,
            })
            .collect();
        compose_rust::window::reset_window_size();
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
        assert!(widths_at(700.0).contains(&WindowSizeClass::MEDIUM_MIN_WIDTH_DP));
        // Not the class's own boundary on a wide window. The column is the reference's,
        // which is narrower than the width at which its class begins, because a line of
        // text stops being readable long before a window stops being wide.
        assert!(widths_at(1200.0).contains(&THREAD_COLUMN));
        assert!(
            !widths_at(1200.0).contains(&WindowSizeClass::EXPANDED_MIN_WIDTH_DP),
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
        assert_eq!(
            screen.conversations(),
            Vec::<String>::new(),
            "a conversation nobody has said anything in yet has no name of its own, so it \
             is not in the list: it was listed under the same words as the row that starts \
             one, which put the command and the document side by side in the same face"
        );

        screen.send("tell me about streaming");
        screen.settle();
        screen.press_icon(IconRole::Compose);
        assert_eq!(
            screen.conversations(),
            vec!["tell me about streaming".to_owned()],
            "the conversation that was on screen should be in the sidebar, named after \
             its opening line, and the empty one just started should not"
        );
    }

    /// Search is one of the destinations at every width, not a desktop extra, and pressing
    /// it opens the filter.
    #[test]
    fn fr22_search_is_a_destination_at_every_width() {
        for (class, width) in [
            (WindowSizeClass::Compact, 400.0),
            (WindowSizeClass::Medium, 700.0),
            (WindowSizeClass::Expanded, 1_000.0),
        ] {
            compose_rust::window::reset_window_size();
            let mut screen = Screen::new();
            screen.resize(class, width);
            assert_eq!(
                screen.destinations().get(1).map(String::as_str),
                Some("Search"),
                "search should be the second destination at {class:?}: {:?}",
                screen.destinations()
            );
            screen.press("Search");
            assert!(
                screen.opened >= 1,
                "pressing Search did not open its sheet at {class:?}"
            );
        }
        compose_rust::window::reset_window_size();
    }

    /// The composer's add key and the top bar's more key each open their own menu.
    #[test]
    fn fr22_the_composer_menu_opens_alone() {
        let mut screen = Screen::new();
        screen.press_icon(IconRole::Add);
        assert_eq!(screen.opened, 1, "the add key should open only the composer's menu");
        let mut screen = Screen::new();
        screen.press_icon(IconRole::More);
        assert_eq!(screen.opened, 1, "the more key should open only the top bar's menu");
    }

    /// Images, videos and the library answer when pressed.
    #[test]
    fn fr22_the_places_answer_when_pressed() {
        let mut screen = Screen::new();
        for place in ["Images", "Videos", "Library"] {
            screen.press(place);
        }
        let said: Vec<&str> = screen
            .messages
            .iter()
            .map(|(text, _)| text.as_str())
            .collect();
        assert_eq!(
            said,
            [
                "Images is not part of this sample",
                "Videos is not part of this sample",
                "Library is not part of this sample",
            ],
            "each place should say it is not part of this sample"
        );
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

        let modifiers: Vec<&compose_rust::schema::Modifier> = batch
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
                compose_rust::schema::Modifier::ShapeRole(ShapeRole::Full)
            )),
            "the composer is not the reference's pill: {modifiers:?}"
        );
        assert!(
            modifiers.iter().any(|modifier| matches!(
                modifier,
                compose_rust::schema::Modifier::Material(MaterialRole::Regular)
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
                    modifier: compose_rust::schema::Modifier::Material(MaterialRole::Chrome),
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
