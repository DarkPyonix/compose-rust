//! A clothing shop: a catalogue you can browse by category, a product you can size and
//! count, and a bag that adds up.
//!
//! Unified rather than adaptive, and the stronger sense of the word: the reference is one
//! picture of one design, so the shop draws that picture everywhere rather than the
//! platform's version of it. `THEME` names the design system and the colour scheme, and
//! `palette` names the colours, because the picture has a yellow accent and flat grey
//! cards that no design system's palette would have given it.
//!
//! The garments are drawn, not photographed. A photograph of a real garment belongs to
//! whoever took it, so the shop's stock is a set of original vector drawings in
//! `assets/`, registered once with `asset` and drawn by id after that.

mod catalogue;
mod palette;

use catalogue::{
    BagLine, CATALOGUE, Category, FEATURED, Product, SIZES, price, stars, total, under,
};
use dioxus_compose::prelude::*;
use dioxus_compose::{DrawList, DrawListBuilder};

/// How wide the page is once the window is wider than a phone. A phone design in a desktop
/// window is still a phone design.
const PAGE_MEASURE: f32 = 420.0;

/// How tall a garment's tile is on the catalogue and on the carousel.
///
/// Numbers, because these are the proportions of a picture and the space ladder answers
/// how far apart two things sit rather than how large a picture is.
const TILE_HEIGHT: f32 = 168.0;
const CAROUSEL_HEIGHT: f32 = 200.0;
const HERO_HEIGHT: f32 = 300.0;
/// How tall the row of category labels is.
const STRIP_HEIGHT: f32 = 56.0;

/// The dots under the carousel: a wide pill for the slide that is showing and a small
/// circle for each of the others, which is what the reference draws.
const DOT_HEIGHT: f32 = 6.0;
const DOT_WIDTH: f32 = 6.0;
const DOT_ACTIVE_WIDTH: f32 = 22.0;
const DOT_SLOT: f32 = 26.0;

/// How large a control whose face is a drawing rather than a word is: the icons in the
/// header and along the bottom, and the mark in the top left.
const ICON_TAP: f32 = 44.0;
const LOGO: (f32, f32) = (36.0, 22.0);

/// The size chips on a garment's page, which are round in the reference and as wide as
/// the row divided five ways here.
const CHIP_HEIGHT: f32 = 46.0;

/// The icons this shop draws, as the bytes each registration carries: the meaning's wire
/// tag, little endian, and nothing else.
///
/// An icon is a meaning rather than a picture. The Renderer holds the artwork for every
/// design system, so `Home` comes out as this system's house and the shop never says what
/// a house looks like.
mod icon {
    use dioxus_compose::prelude::IconRole;

    pub static HOME: [u8; 2] = (IconRole::Home as u16).to_le_bytes();
    pub static SEARCH: [u8; 2] = (IconRole::Search as u16).to_le_bytes();
    pub static BAG: [u8; 2] = (IconRole::Inbox as u16).to_le_bytes();
    pub static ACCOUNT: [u8; 2] = (IconRole::Settings as u16).to_le_bytes();
    pub static ORDERS: [u8; 2] = (IconRole::List as u16).to_le_bytes();
}

/// The destinations along the bottom.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum Destination {
    Shop,
    Search,
    Bag,
    Account,
}

impl Destination {
    const STRIP: [Destination; 4] = [
        Destination::Shop,
        Destination::Search,
        Destination::Bag,
        Destination::Account,
    ];

    /// What the destination is called. Nothing on screen says it: the reference's bar is
    /// icons alone. It names the screen you arrive at and it is what a test asks for.
    fn label(self) -> &'static str {
        match self {
            Destination::Shop => "Shop",
            Destination::Search => "Search",
            Destination::Bag => "Bag",
            Destination::Account => "Account",
        }
    }

    /// What the destination means, so the Renderer draws its own artwork for it.
    fn icon(self) -> &'static [u8] {
        match self {
            Destination::Shop => &icon::HOME,
            Destination::Search => &icon::SEARCH,
            Destination::Bag => &icon::BAG,
            Destination::Account => &icon::ACCOUNT,
        }
    }
}

/// One icon, with the press behind it.
///
/// No label under it and no pill round it, because the reference's bar has neither: what
/// marks the destination you are on is that its icon is drawn in the accent. The button
/// carries no text and sits over the drawing, so the press lands on it whatever the
/// design system drew.
fn icon_button(picture: &'static [u8], tint: Paint, on_click: EventHandler<()>) -> Element {
    rsx! {
        dioxus_compose::Box {
            width: ICON_TAP,
            height: ICON_TAP,
            alignment: Alignment::Center,
            Icon { asset_id: asset(AssetKind::VectorIcon, picture), color: tint }
            Button {
                text: "",
                variant: ButtonVariant::Text,
                fill_max_width: true,
                fill_max_height: true,
                on_click: move |_| on_click.call(()),
            }
        }
    }
}

/// The shop's mark: three slanted bars, which is what the reference puts in the corner.
///
/// Drawn rather than written. A wordmark is a typeface somebody licensed, and the corner
/// of this screen in the reference is a mark rather than a name.
fn logo_mark() -> DrawList {
    let mut list = DrawListBuilder::with_capacity(3, 0);
    for step in 0..3 {
        let left = 3.0 + step as f32 * 11.0;
        list = list.line(
            palette::INK,
            left,
            LOGO.1 - 3.0,
            left + 7.0,
            3.0,
            if step == 2 { 3.0 } else { 5.0 },
        );
    }
    list.build()
}

/// The top of the catalogue: the mark on the left, and what you can reach from anywhere
/// on the right.
fn header(waiting: u32, on_go: EventHandler<Destination>) -> Element {
    rsx! {
        Row {
            fill_max_width: true,
            alignment: Alignment::Center,
            Canvas {
                width: LOGO.0,
                height: LOGO.1,
                commands: logo_mark(),
            }
            Spacer { weight: 1.0 }
            {icon_button(&icon::ORDERS, palette::INK, EventHandler::new(move |()| {
                on_go.call(Destination::Search);
            }))}
            // The bag, with the mark that says something is in it. The mark is the one
            // red on this screen and it only appears when it means something.
            dioxus_compose::Box {
                alignment: Alignment::TopEnd,
                {icon_button(&icon::BAG, palette::INK, EventHandler::new(move |()| {
                    on_go.call(Destination::Bag);
                }))}
                if waiting > 0 {
                    dioxus_compose::Box {
                        width: 10.0,
                        height: 10.0,
                        background: palette::ALERT,
                        shape_role: ShapeRole::Full,
                    }
                }
            }
        }
    }
}

/// A garment on its card: the flat grey picture area, and the press that opens it.
///
/// The card is the control. The reference has no button on it, and a tile cannot be
/// tapped: `Modifier::Clickable` is on the wire but no container widget exposes it, so
/// what carries the press is a `Button` with no label, filling the picture area.
fn tile(product: &Product, height: f32, on_open: EventHandler<u32>) -> Element {
    let id = product.id;
    rsx! {
        dioxus_compose::Box {
            fill_max_width: true,
            height,
            background: palette::TILE,
            shape_role: ShapeRole::Large,
            alignment: Alignment::Center,
            Image {
                fill_max_width: true,
                fill_max_height: true,
                padding_role: SpaceRole::Md,
                asset_id: asset(AssetKind::Svg, product.picture),
            }
            Button {
                text: "",
                variant: ButtonVariant::Text,
                fill_max_width: true,
                fill_max_height: true,
                on_click: move |_| on_open.call(id),
            }
        }
    }
}

/// One cell of the two-up grid: the picture area, then the name, the support line and the
/// price under it, which is the order the reference writes them in.
///
/// No weight of its own. The cell's parent in the grid is a `Column`, where weight is
/// vertical, and a vertical weight inside a column that is measuring its own height comes
/// out as a height of zero. The width share belongs to the wrapper in the row above.
fn grid_cell(product: &Product, on_open: EventHandler<u32>) -> Element {
    rsx! {
        Column {
            fill_max_width: true,
            space_role: SpaceRole::Xs,
            {tile(product, TILE_HEIGHT, on_open)}
            Text {
                text: product.name,
                type_role: TypeRole::BodyStrong,
                color: palette::INK,
                max_lines: 1,
                overflow: TextOverflow::Ellipsis,
            }
            Text {
                text: product.support,
                type_role: TypeRole::Caption,
                color: palette::MUTED,
                max_lines: 1,
                overflow: TextOverflow::Ellipsis,
            }
            Text {
                text: price(product.cents),
                type_role: TypeRole::BodyStrong,
                color: palette::INK,
            }
        }
    }
}

/// The carousel: one wide picture on the flat grey card, with the dots under it.
///
/// The slide is the Host's rather than a scroll position, because a scroll position
/// belongs to the Renderer and is never reported back: dots driven by one would be drawn
/// in the right place and never move. Pressing the card turns it, which is the nearest
/// thing to the reference's swipe that the vocabulary has.
fn carousel(slide: Signal<usize>) -> Element {
    let mut slide = slide;
    let showing = slide() % FEATURED;
    rsx! {
        dioxus_compose::Box {
            fill_max_width: true,
            height: CAROUSEL_HEIGHT,
            background: palette::TILE,
            shape_role: ShapeRole::Large,
            alignment: Alignment::Center,
            Image {
                fill_max_width: true,
                fill_max_height: true,
                padding_role: SpaceRole::Md,
                asset_id: asset(AssetKind::Svg, catalogue::featured()[showing]),
            }
            Button {
                text: "",
                variant: ButtonVariant::Text,
                fill_max_width: true,
                fill_max_height: true,
                on_click: move |_| slide.set((slide() + 1) % FEATURED),
            }
        }
        Row {
            fill_max_width: true,
            // Centred across the row, which needs the arrangement rather than the
            // alignment: alignment answers where a child sits across the row's other
            // axis, so a row of dots set to centre alignment is a row of vertically
            // centred dots still starting at the left edge.
            arrangement: Arrangement::Center,
            alignment: Alignment::Center,
            for position in 0..FEATURED {
                dioxus_compose::Box {
                    key: "{position}",
                    width: DOT_SLOT,
                    height: DOT_HEIGHT,
                    alignment: Alignment::Center,
                    dioxus_compose::Box {
                        width: if position == showing { DOT_ACTIVE_WIDTH } else { DOT_WIDTH },
                        height: DOT_HEIGHT,
                        background: if position == showing { palette::DARK } else { palette::DOT },
                        shape_role: ShapeRole::Full,
                    }
                }
            }
        }
    }
}

/// The catalogue: the header, the heading, the carousel, the category strip and a two-up
/// grid.
fn catalogue_screen(
    category: Signal<Category>,
    slide: Signal<usize>,
    bag: Signal<Vec<BagLine>>,
    on_open: EventHandler<u32>,
    on_go: EventHandler<Destination>,
) -> Element {
    let mut category = category;
    let shelf = under(category());
    let rows: Vec<Vec<&'static Product>> = shelf.chunks(2).map(<[_]>::to_vec).collect();
    let in_bag: u32 = bag().iter().map(|line| line.quantity).sum();

    rsx! {
        Column {
            fill_max_width: true,
            padding_role: SpaceRole::Md,
            space_role: SpaceRole::Md,

            {header(in_bag, on_go)}

            Text {
                text: "Let's find your sports outfit!",
                type_role: TypeRole::Headline,
                color: palette::INK,
                fill_max_width: true,
            }

            {carousel(slide)}

            // A strip that runs off the edge, not a segmented control.
            //
            // `Tabs` divides the width it is given equally between its children and does
            // not scroll, so five categories on a phone is five segments of seventy dp,
            // and a button inside one carries its own horizontal padding: every label
            // broke into a column of single letters. The reference's own strip runs past
            // the right edge with the last category half shown, which is this.
            LazyRow {
                fill_max_width: true,
                height: STRIP_HEIGHT,
                item_count: Category::STRIP.len(),
                key_of: move |position: usize| Category::STRIP[position].label().to_owned(),
                item: move |position: usize| {
                    let choice = Category::STRIP[position];
                    rsx! {
                        dioxus_compose::Box {
                            alignment: Alignment::Center,
                            // Every category is a label and one of them is the one you
                            // are looking at, which is what the reference draws: the
                            // chosen one in the reading ink and the rest in the quieter
                            // one. Filling the chosen one instead made the row read as
                            // five actions on a screen whose only real action is adding
                            // something to the bag.
                            Button {
                                text: choice.label(),
                                variant: ButtonVariant::Text,
                                color: if choice == category() {
                                    palette::INK
                                } else {
                                    palette::MUTED
                                },
                                on_click: move |_| category.set(choice),
                            }
                        }
                    }
                },
            }

            Column {
                fill_max_width: true,
                space_role: SpaceRole::Md,
                for (index, row) in rows.iter().enumerate() {
                    Row {
                        key: "{index}",
                        fill_max_width: true,
                        space_role: SpaceRole::Md,
                        alignment: Alignment::TopStart,
                        for product in row.iter().copied() {
                            Column { key: "{product.id}", weight: 1.0,
                                {grid_cell(product, on_open)}
                            }
                        }
                        // A shelf with an odd number of garments leaves a gap rather than
                        // letting the last one stretch to twice the width of its
                        // neighbours, which would read as a different kind of product.
                        if row.len() == 1 {
                            Spacer { weight: 1.0 }
                        }
                    }
                }
            }
        }
    }
}

/// One garment: the picture, what it is, what size, how many, and what it costs.
fn detail_screen(
    product: &Product,
    size: Signal<&'static str>,
    quantity: Signal<u32>,
    on_back: EventHandler<()>,
    on_add: EventHandler<()>,
) -> Element {
    let mut size = size;
    let mut quantity = quantity;
    let line_total = price(product.cents * quantity());
    let rating = product.rating;

    rsx! {
        Column {
            fill_max_width: true,
            fill_max_height: true,
            background: palette::PAGE,

            dioxus_compose::Box {
                fill_max_width: true,
                height: HERO_HEIGHT,
                background: palette::TILE,
                alignment: Alignment::TopStart,
                // The garment, full size. The reference's product page is a photograph
                // running to the window's edges with the panel covering its lower part,
                // and a page that fills that with a flat colour is the one screen in the
                // shop that shows you nothing about what you are buying.
                Image {
                    fill_max_width: true,
                    fill_max_height: true,
                    padding_role: SpaceRole::Lg,
                    asset_id: asset(AssetKind::Svg, product.picture),
                }
                Row {
                    fill_max_width: true,
                    padding_role: SpaceRole::Sm,
                    alignment: Alignment::CenterStart,
                    Button {
                        text: "\u{2190}",
                        variant: ButtonVariant::Text,
                        color: palette::INK,
                        on_click: move |_| on_back.call(()),
                    }
                }
            }

            // The panel that covers the lower part of the picture, which is the shape the
            // reference draws and the shape a grouped iOS sheet has.
            Column {
                fill_max_width: true,
                weight: 1.0,
                background: palette::PAGE,
                shape_role: ShapeRole::Large,
                padding_role: SpaceRole::Md,
                space_role: SpaceRole::Md,

                Column {
                    fill_max_width: true,
                    space_role: SpaceRole::Xs,
                    Text {
                        text: product.name,
                        type_role: TypeRole::Headline,
                        color: palette::INK,
                    }
                    Text {
                        text: product.support,
                        type_role: TypeRole::Body,
                        color: palette::MUTED,
                    }
                    Row {
                        space_role: SpaceRole::Sm,
                        alignment: Alignment::CenterStart,
                        Text {
                            text: stars(rating),
                            type_role: TypeRole::Body,
                            color: palette::INK,
                        }
                        Text {
                            text: "({catalogue::rating_text(rating)})",
                            type_role: TypeRole::Label,
                            color: palette::MUTED,
                        }
                    }
                }

                Column {
                    fill_max_width: true,
                    space_role: SpaceRole::Sm,
                    Text {
                        text: "Select size",
                        type_role: TypeRole::Label,
                        color: palette::MUTED,
                    }
                    Row {
                        fill_max_width: true,
                        space_role: SpaceRole::Sm,
                        for option in SIZES {
                            // The chosen size is the one thing on this page in the
                            // accent. The rest are the same flat grey as the card the
                            // garment is drawn on.
                            Button {
                                key: "{option}",
                                text: option,
                                weight: 1.0,
                                height: CHIP_HEIGHT,
                                variant: ButtonVariant::Text,
                                color: palette::INK,
                                background: if option == size() {
                                    palette::ACCENT
                                } else {
                                    palette::TILE
                                },
                                shape_role: ShapeRole::Full,
                                on_click: move |_| size.set(option),
                            }
                        }
                    }
                }

                Row {
                    fill_max_width: true,
                    space_role: SpaceRole::Sm,
                    alignment: Alignment::CenterStart,
                    Button {
                        text: "\u{2212}",
                        variant: ButtonVariant::Text,
                        color: palette::INK,
                        background: palette::PAGE,
                        border_width: 1.0,
                        border_color: palette::OUTLINE,
                        shape_role: ShapeRole::Full,
                        enabled: quantity() > 1,
                        on_click: move |_| quantity.set(quantity().saturating_sub(1).max(1)),
                    }
                    Text {
                        text: "{quantity}",
                        type_role: TypeRole::Subtitle,
                        color: palette::INK,
                    }
                    Button {
                        text: "+",
                        variant: ButtonVariant::Text,
                        color: palette::PAGE,
                        background: palette::DARK,
                        shape_role: ShapeRole::Full,
                        enabled: quantity() < 9,
                        on_click: move |_| quantity.set((quantity() + 1).min(9)),
                    }
                    Spacer { weight: 1.0 }
                    Text {
                        text: line_total,
                        type_role: TypeRole::Headline,
                        color: palette::INK,
                    }
                }

                Button {
                    text: "Add to Bag",
                    fill_max_width: true,
                    height: CHIP_HEIGHT + 8.0,
                    variant: ButtonVariant::Text,
                    color: palette::PAGE,
                    background: palette::DARK,
                    shape_role: ShapeRole::Full,
                    on_click: move |_| on_add.call(()),
                }
            }
        }
    }
}

/// What is waiting to be paid for.
fn bag_screen(bag: Signal<Vec<BagLine>>) -> Element {
    let mut bag = bag;
    let lines = bag();
    let sum = total(&lines);

    rsx! {
        Column {
            fill_max_width: true,
            fill_max_height: true,
            padding_role: SpaceRole::Md,
            space_role: SpaceRole::Md,
            Text { text: "Bag", type_role: TypeRole::Headline, color: palette::INK }
            if lines.is_empty() {
                dioxus_compose::Box {
                    fill_max_width: true,
                    weight: 1.0,
                    alignment: Alignment::Center,
                    Text {
                        text: "Nothing in the bag yet.",
                        type_role: TypeRole::Body,
                        color: palette::MUTED,
                    }
                }
            } else {
                Column {
                    fill_max_width: true,
                    background: palette::TILE,
                    shape_role: ShapeRole::Large,
                    for (index, line) in lines.iter().enumerate() {
                        {
                            let line = *line;
                            let named = catalogue::product(line.product);
                            let last = index + 1 == lines.len();
                            rsx! {
                                Column { key: "{index}", fill_max_width: true,
                                    Row {
                                        fill_max_width: true,
                                        padding_role: SpaceRole::Md,
                                        space_role: SpaceRole::Sm,
                                        alignment: Alignment::CenterStart,
                                        Column {
                                            weight: 1.0,
                                            space_role: SpaceRole::Xs,
                                            Text {
                                                text: named.map_or("Unavailable", |found| found.name),
                                                type_role: TypeRole::Body,
                                                color: palette::INK,
                                                max_lines: 1,
                                                overflow: TextOverflow::Ellipsis,
                                            }
                                            Text {
                                                text: "Size {line.size} \u{00b7} {line.quantity}",
                                                type_role: TypeRole::Caption,
                                                color: palette::MUTED,
                                            }
                                        }
                                        Text {
                                            text: price(
                                                named.map_or(0, |found| found.cents) * line.quantity,
                                            ),
                                            type_role: TypeRole::BodyStrong,
                                            color: palette::INK,
                                        }
                                        Button {
                                            text: "Remove",
                                            variant: ButtonVariant::Text,
                                            color: palette::ALERT,
                                            on_click: move |_| {
                                                let removed = bag.write().remove(index);
                                                let name = catalogue::product(removed.product)
                                                    .map_or("An item", |found| found.name);
                                                Message::new(format!("Removed {name}"))
                                                    .with_action("Undo", move |()| {
                                                        let at = index.min(bag.read().len());
                                                        bag.write().insert(at, removed);
                                                    })
                                                    .with_duration(MessageDuration::Long)
                                                    .show();
                                            },
                                        }
                                    }
                                    if !last {
                                        Separator { color: palette::OUTLINE }
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer { weight: 1.0 }
                Row {
                    fill_max_width: true,
                    alignment: Alignment::CenterStart,
                    Text {
                        text: "Total",
                        type_role: TypeRole::Body,
                        color: palette::MUTED,
                        weight: 1.0,
                    }
                    Text {
                        text: price(sum),
                        type_role: TypeRole::Headline,
                        color: palette::INK,
                    }
                }
                Button {
                    text: "Checkout",
                    fill_max_width: true,
                    height: CHIP_HEIGHT + 8.0,
                    variant: ButtonVariant::Text,
                    color: palette::PAGE,
                    background: palette::DARK,
                    shape_role: ShapeRole::Full,
                    on_click: move |_| {
                        Message::new("Checkout is not part of this sample").show();
                    },
                }
            }
        }
    }
}

/// Search, which is a field and the shelves as a list.
fn search_screen(on_open: EventHandler<u32>) -> Element {
    rsx! {
        Column {
            fill_max_width: true,
            fill_max_height: true,
            padding_role: SpaceRole::Md,
            space_role: SpaceRole::Md,
            Text { text: "Search", type_role: TypeRole::Headline, color: palette::INK }
            TextField { fill_max_width: true, placeholder: "T-shirts, joggers, jackets" }
            Column {
                fill_max_width: true,
                space_role: SpaceRole::Sm,
                for product in CATALOGUE {
                    Row {
                        key: "{product.id}",
                        fill_max_width: true,
                        space_role: SpaceRole::Sm,
                        alignment: Alignment::CenterStart,
                        dioxus_compose::Box {
                            width: 44.0,
                            height: 44.0,
                            background: palette::TILE,
                            shape_role: ShapeRole::Medium,
                            alignment: Alignment::Center,
                            Image {
                                fill_max_width: true,
                                fill_max_height: true,
                                padding_role: SpaceRole::Xs,
                                asset_id: asset(AssetKind::Svg, product.picture),
                            }
                        }
                        Column {
                            weight: 1.0,
                            Text {
                                text: product.name,
                                type_role: TypeRole::Body,
                                color: palette::INK,
                                max_lines: 1,
                                overflow: TextOverflow::Ellipsis,
                            }
                            Text {
                                text: product.support,
                                type_role: TypeRole::Caption,
                                color: palette::MUTED,
                                max_lines: 1,
                                overflow: TextOverflow::Ellipsis,
                            }
                        }
                        Button {
                            text: price(product.cents),
                            variant: ButtonVariant::Text,
                            color: palette::INK,
                            on_click: move |_| on_open.call(product.id),
                        }
                    }
                }
            }
        }
    }
}

/// The account page, which is a short list of the things a shop keeps about a person.
fn account_screen() -> Element {
    rsx! {
        Column {
            fill_max_width: true,
            fill_max_height: true,
            padding_role: SpaceRole::Md,
            space_role: SpaceRole::Md,
            Text { text: "Account", type_role: TypeRole::Headline, color: palette::INK }
            Column {
                fill_max_width: true,
                background: palette::TILE,
                shape_role: ShapeRole::Large,
                for (index, entry) in ["Orders", "Addresses", "Payment", "Notifications"]
                    .iter()
                    .enumerate()
                {
                    Column { key: "{entry}", fill_max_width: true,
                        Row {
                            fill_max_width: true,
                            padding_role: SpaceRole::Md,
                            alignment: Alignment::CenterStart,
                            Text {
                                text: *entry,
                                type_role: TypeRole::Body,
                                color: palette::INK,
                                weight: 1.0,
                            }
                            Text {
                                text: "\u{203a}",
                                type_role: TypeRole::Body,
                                color: palette::MUTED,
                            }
                        }
                        if index < 3 {
                            Separator { color: palette::OUTLINE }
                        }
                    }
                }
            }
        }
    }
}

/// The bar along the bottom: four icons and nothing else.
///
/// Drawn here rather than declared as a `Navigation`, which is the widget for "the
/// destinations, in whatever shape this design system and this window call for": a
/// labelled bar with a selection pill under a hairline. The reference is four bare icons
/// on the page, the one you are on in the accent, and that is a shape no design system
/// would be right to give a set of destinations.
fn bottom_bar(destination: Destination, on_go: EventHandler<Destination>) -> Element {
    rsx! {
        Row {
            fill_max_width: true,
            padding_role: SpaceRole::Sm,
            arrangement: Arrangement::SpaceAround,
            alignment: Alignment::Center,
            for choice in Destination::STRIP {
                {
                    let tint = if choice == destination {
                        palette::ACCENT
                    } else {
                        palette::INK
                    };
                    rsx! {
                        dioxus_compose::Box { key: "{choice.label()}",
                            {icon_button(choice.icon(), tint, EventHandler::new(move |()| {
                                on_go.call(choice);
                            }))}
                        }
                    }
                }
            }
        }
    }
}

pub fn app() -> Element {
    let window = use_window_size();
    let measure = if window.is_compact() {
        None
    } else {
        Some(PAGE_MEASURE)
    };

    let mut destination = use_signal(|| Destination::Shop);
    let category = use_signal(|| Category::New);
    let slide = use_signal(|| 0_usize);
    let mut open = use_signal(|| Option::<u32>::None);
    let size = use_signal(|| SIZES[1]);
    let quantity = use_signal(|| 1_u32);
    // The shop opens with something already in the bag, which is the state the reference
    // draws: the mark on the bag in the corner is red because something is waiting, and a
    // mark that is always drawn would say that whether or not it were true.
    let bag = use_signal(|| {
        vec![BagLine {
            product: 2,
            size: "M",
            quantity: 1,
        }]
    });

    let on_open = EventHandler::new(move |id: u32| {
        destination.set(Destination::Shop);
        open.set(Some(id));
    });
    let on_go = EventHandler::new(move |choice: Destination| {
        destination.set(choice);
        if choice != Destination::Shop {
            open.set(None);
        }
    });

    let showing = open().and_then(catalogue::product);
    let body = match (destination(), showing) {
        (Destination::Shop, Some(product)) => detail_screen(
            product,
            size,
            quantity,
            EventHandler::new(move |()| open.set(None)),
            add_to_bag(bag, open, size, quantity),
        ),
        (Destination::Shop, None) => catalogue_screen(category, slide, bag, on_open, on_go),
        (Destination::Search, _) => search_screen(on_open),
        (Destination::Bag, _) => bag_screen(bag),
        (Destination::Account, _) => account_screen(),
    };

    // A garment's page fills the window, because the picture runs to the edge. Everything
    // else is a page that scrolls.
    let scrolls = showing.is_none() || destination() != Destination::Shop;

    rsx! {
        // The frame is named rather than built. Whether the bar runs across the bottom,
        // stands down the leading edge or opens as a drawer is the frame's answer from
        // the width it was given, and the page is laid out clear of whatever it took.
        Scaffold {
            background: palette::PAGE,
            bottom_bar: rsx! {
                dioxus_compose::Box {
                    fill_max_width: true,
                    alignment: Alignment::Center,
                    Column {
                        width: measure,
                        fill_max_width: measure.is_none(),
                        {bottom_bar(destination(), on_go)}
                    }
                }
            },

            dioxus_compose::Box {
                weight: 1.0,
                alignment: Alignment::TopCenter,
                if scrolls {
                    ScrollColumn {
                        width: measure,
                        {body}
                    }
                } else {
                    Column {
                        width: measure,
                        {body}
                    }
                }
            }
        }
    }
}

/// Putting the open garment in the bag, at the size and count that are showing.
///
/// A line that is already there grows rather than repeating, because two lines for the
/// same garment in the same size is a bag nobody can read, and it makes the total right
/// for the wrong reason.
fn add_to_bag(
    bag: Signal<Vec<BagLine>>,
    open: Signal<Option<u32>>,
    size: Signal<&'static str>,
    quantity: Signal<u32>,
) -> EventHandler<()> {
    let mut bag = bag;
    EventHandler::new(move |()| {
        let Some(id) = open() else { return };
        let chosen = size();
        let count = quantity();
        let existing = bag
            .read()
            .iter()
            .position(|line| line.product == id && line.size == chosen);
        match existing {
            Some(index) => bag.write()[index].quantity += count,
            None => bag.write().push(BagLine {
                product: id,
                size: chosen,
                quantity: count,
            }),
        }
        let name = catalogue::product(id).map_or("Item", |found| found.name);
        Message::new(format!("{name} added to the bag"))
            .with_duration(MessageDuration::Short)
            .show();
    })
}

/// The design this sample draws, named once.
///
/// One design system everywhere, because the design is the product here rather than the
/// platform's convention, and light because the reference is a white page with black ink,
/// grey product cards and one yellow accent.
///
/// The scheme is said out loud rather than left to follow the machine. `Theme::unified`
/// settles which design system is drawn and nothing else, so without this line a reader
/// whose system is set the other way sees a screen the design was never drawn for.
const THEME: Theme = Theme::unified(DesignSystem::Cupertino).with_color_scheme(ColorScheme::Light);

/// `demo_theme_for` rather than `THEME` alone: a sample is something to look at, and one
/// machine can only show the design system and the scheme it is set to. `DXC_DESIGN` and
/// `DXC_SCHEME` each override the half they name, so the line above stays the answer to
/// everything nobody asked about.
/// Runs the sample as a program of its own. The desktop binary is one line of this.
pub fn launch() {
    launch_builder().launch(app);
}

/// How this sample is configured, in one place because three entry points need it.
///
/// The theme above all: a sample that names one and then reaches a platform through an
/// entry point that makes its own builder is a sample that draws the same screens in a
/// different design system depending on where it runs.
fn launch_builder() -> dioxus_compose::LaunchBuilder {
    // The name the window carries. A desktop lists windows by it, so a window that said
    // nothing was listed under whatever the renderer happened to be called, and every
    // sample here was listed as DioxusCompose until this line existed.
    dioxus_compose::LaunchBuilder::new()
        .with_theme(dioxus_compose::demo_theme_for(THEME))
        .with_window(
            dioxus_compose::schema::Window::new()
                .with_title("Store")
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
    use dioxus_compose::schema::{EventPayload, PropertyKind};

    /// Named for what it defends: the reference is a light design, and a machine set
    /// the other way drew this sample dark with nothing to compare against.
    #[test]
    fn fr14_the_design_names_its_colour_scheme() {
        // Through the wire rather than off the constant: what settles the question is the
        // record the Renderer reads, and a scheme that never leaves the Host is a scheme
        // nobody is drawn in.
        dioxus_compose::window::reset_window_size();
        let mut host = Host::with_theme(app, THEME);
        let batch = host.rebuild().expect("the first frame failed").to_vec();
        let first = decode_batch(&batch)
            .expect("the first batch did not decode")
            .into_iter()
            .next()
            .expect("the first batch is empty");
        let Mutation::SetTheme(theme) = first else {
            panic!("the first record is {first:?} rather than the theme");
        };
        assert_eq!(theme.color_scheme, ColorScheme::Light);
        assert!(!theme.adaptive, "the design is the product here");
    }

    /// The screen, driven the way a Renderer drives it.
    ///
    /// Every batch is kept rather than only the last one. A batch is the change since the
    /// frame before it, so a test that reads one batch is reading what moved, not what is
    /// on screen, and a screen built over three frames would look almost empty.
    struct Screen {
        host: Host,
        frames: Vec<Vec<u8>>,
    }

    impl Screen {
        fn new() -> Self {
            dioxus_compose::window::reset_window_size();
            let mut host = Host::new(app);
            let first = host.rebuild().expect("the first frame failed").to_vec();
            let mut screen = Self {
                host,
                frames: vec![first],
            };
            screen.resize(420.0);
            screen
        }

        fn resize(&mut self, width_dp: f32) {
            self.send(HostEvent {
                node_id: 0,
                handler_id: 0,
                payload: EventPayload::WindowSizeChanged {
                    width_dp,
                    height_dp: 780.0,
                    class: WindowSizeClass::from_width_dp(width_dp),
                    height_class: WindowHeightClass::from_height_dp(780.0),
                },
            });
        }

        fn send(&mut self, event: HostEvent<'_>) {
            let mut bytes = Vec::new();
            encode_event(&event, &mut bytes).expect("the event did not encode");
            let (batch, _) = self.host.dispatch_event(&bytes).expect("the event failed");
            if !batch.is_empty() {
                self.frames.push(batch.to_vec());
            }
        }

        fn mutations(&self) -> Vec<Mutation<'_>> {
            self.frames
                .iter()
                .flat_map(|frame| decode_batch(frame).expect("a batch did not decode"))
                .collect()
        }

        fn texts(&self) -> Vec<String> {
            self.mutations()
                .iter()
                .filter_map(|mutation| match mutation {
                    Mutation::SetProp {
                        property: PropertyKind::Text,
                        value: PropertyValue::String(text),
                        ..
                    } => Some((*text).to_owned()),
                    _ => None,
                })
                .collect()
        }

        /// The node that last carried this text.
        ///
        /// The most recently declared one, because a label that has appeared twice over
        /// the run belongs to whichever screen is showing now, and the older node is
        /// something the Renderer has already thrown away.
        fn node_saying(&self, label: &str) -> Option<u32> {
            self.mutations()
                .iter()
                .rev()
                .find_map(|mutation| match mutation {
                    Mutation::SetProp {
                        node_id,
                        property: PropertyKind::Text,
                        value: PropertyValue::String(text),
                    } if *text == label => Some(*node_id),
                    _ => None,
                })
        }

        /// The id an icon of this meaning was registered under.
        ///
        /// A registration carries the meaning's wire tag rather than a picture, so the
        /// bytes asked for here are the two the sample sent.
        fn icon_asset(&self, tag: &[u8]) -> Option<u32> {
            self.mutations().iter().find_map(|mutation| match mutation {
                Mutation::RegisterAsset {
                    asset_id,
                    kind: AssetKind::VectorIcon,
                    bytes,
                } if *bytes == tag => Some(*asset_id),
                _ => None,
            })
        }

        /// The node that last drew this asset.
        fn node_drawing(&self, asset_id: u32) -> Option<u32> {
            self.mutations()
                .iter()
                .rev()
                .find_map(|mutation| match mutation {
                    Mutation::SetProp {
                        node_id,
                        property: PropertyKind::Asset,
                        value: PropertyValue::Integer(id),
                    } if *id as u32 == asset_id => Some(*node_id),
                    _ => None,
                })
        }

        /// What each node was last inserted into.
        fn parents(&self) -> Vec<(u32, u32)> {
            self.mutations()
                .iter()
                .filter_map(|mutation| match mutation {
                    Mutation::Insert {
                        parent_id, node_id, ..
                    } => Some((*node_id, *parent_id)),
                    _ => None,
                })
                .collect()
        }

        fn parent_of(&self, node: u32) -> Option<u32> {
            self.parents()
                .iter()
                .rev()
                .find_map(|(child, parent)| (*child == node).then_some(*parent))
        }

        /// The handler a node declared for a press.
        fn handler_of(&self, node: u32) -> Option<u64> {
            self.mutations()
                .iter()
                .rev()
                .find_map(|mutation| match mutation {
                    Mutation::SetProp {
                        node_id,
                        property: PropertyKind::OnClick,
                        value: PropertyValue::Integer(handler),
                    } if *node_id == node => Some(*handler as u64),
                    _ => None,
                })
        }

        /// Presses the control placed beside this one.
        ///
        /// A control whose face is a drawing has no words to name it: what carries the
        /// press is a `Button` with no label sitting over the picture. So the press is
        /// asked for by what the reader can see, and found by walking one step up and
        /// back down to whatever under there answers a click.
        fn press_beside(&mut self, node: u32) -> bool {
            let Some(parent) = self.parent_of(node) else {
                return false;
            };
            let parents = self.parents();
            let mut family = vec![parent];
            let mut found = None;
            while let Some(next) = family.pop() {
                if next != node && self.handler_of(next).is_some() {
                    found = Some(next);
                    break;
                }
                family.extend(
                    parents
                        .iter()
                        .filter_map(|(child, owner)| (*owner == next).then_some(*child)),
                );
            }
            let Some(node_id) = found else { return false };
            let Some(handler_id) = self.handler_of(node_id) else {
                return false;
            };
            self.send(HostEvent {
                node_id,
                handler_id,
                payload: EventPayload::Clicked,
            });
            true
        }

        /// Presses whatever carries this label.
        fn press(&mut self, label: &str) -> bool {
            let Some(node) = self.node_saying(label) else {
                return false;
            };
            let Some(handler_id) = self.handler_of(node) else {
                return false;
            };
            self.send(HostEvent {
                node_id: node,
                handler_id,
                payload: EventPayload::Clicked,
            });
            true
        }

        /// Presses a destination along the bottom, which is an icon with nothing written
        /// under it.
        fn press_icon(&mut self, tag: &[u8]) -> bool {
            let Some(asset_id) = self.icon_asset(tag) else {
                return false;
            };
            let Some(node) = self.node_drawing(asset_id) else {
                return false;
            };
            self.press_beside(node)
        }

        /// Whatever text arrived in the last frame, which is what "this screen replaced
        /// that one" has to be read from.
        fn latest_texts(&self) -> Vec<String> {
            let Some(frame) = self.frames.last() else {
                return Vec::new();
            };
            decode_batch(frame)
                .expect("the batch did not decode")
                .iter()
                .filter_map(|mutation| match mutation {
                    Mutation::SetProp {
                        property: PropertyKind::Text,
                        value: PropertyValue::String(text),
                        ..
                    } => Some((*text).to_owned()),
                    _ => None,
                })
                .collect()
        }

        /// Every width the last frame set, which is how the dots under the carousel are
        /// read: the one showing is a wide pill and the rest are small circles.
        fn latest_widths(&self) -> Vec<f32> {
            let Some(frame) = self.frames.last() else {
                return Vec::new();
            };
            decode_batch(frame)
                .expect("the batch did not decode")
                .iter()
                .filter_map(|mutation| match mutation {
                    Mutation::SetModifier {
                        modifier: Modifier::Width(dp),
                        ..
                    } => Some(*dp),
                    _ => None,
                })
                .collect()
        }

        fn widths(&self) -> Vec<f32> {
            self.mutations()
                .iter()
                .filter_map(|mutation| match mutation {
                    Mutation::SetModifier {
                        modifier: Modifier::Width(dp),
                        ..
                    } => Some(*dp),
                    _ => None,
                })
                .collect()
        }
    }

    #[test]
    fn the_first_frame_encodes_without_a_protocol_error() {
        assert!(Host::new(app).rebuild().is_ok());
    }

    /// Every garment reaches the Renderer as a picture, and each drawing crosses once.
    ///
    /// Named for what it defends: `Image` was on the wire for a long time with nothing in
    /// the tree drawing one, and the shelves were flat rectangles the whole time. A shelf
    /// that shows two of the same garment is still one registration, because the id is
    /// what a frame carries after the first one.
    #[test]
    fn fr16_every_garment_crosses_once_as_a_picture() {
        let screen = Screen::new();
        let registered: Vec<&[u8]> = screen
            .mutations()
            .iter()
            .filter_map(|mutation| match mutation {
                Mutation::RegisterAsset { bytes, .. } => Some(*bytes),
                _ => None,
            })
            .collect();
        assert!(
            registered.contains(&catalogue::HERO),
            "the carousel never registered its first slide"
        );
        for product in under(Category::New) {
            assert!(
                registered.contains(&product.picture),
                "{} is on the shelf with no picture",
                product.name
            );
        }
        let mut seen = registered.clone();
        seen.sort_unstable_by_key(|bytes| bytes.as_ptr());
        seen.dedup_by_key(|bytes| bytes.as_ptr());
        assert_eq!(
            seen.len(),
            registered.len(),
            "a drawing was registered more than once"
        );
    }

    /// Nothing is registered again once the screen is up. A picture that crossed on every
    /// frame would put a file in the frame budget and would be the whole reason the id
    /// exists undone.
    #[test]
    fn fr16_a_later_frame_carries_no_pictures() {
        let mut screen = Screen::new();
        screen.press(Category::Women.label());
        let last = screen.frames.last().expect("nothing moved").clone();
        let registered = decode_batch(&last)
            .expect("the batch did not decode")
            .into_iter()
            .filter(|mutation| matches!(mutation, Mutation::RegisterAsset { .. }))
            .count();
        assert_eq!(
            registered, 0,
            "a drawing already on the shelf was sent again"
        );
    }

    /// The carousel shows one slide at a time, under four dots, and the dot that is
    /// showing is the wide one.
    ///
    /// Named for what it defends: a scroll position belongs to the Renderer and is never
    /// reported back, so dots driven by one would be drawn in the right place and never
    /// move. The slide is the Host's, which is what makes the dots mean anything.
    #[test]
    fn fr17_the_carousel_turns_and_the_wide_dot_follows_it() {
        let mut screen = Screen::new();
        let widths = screen.widths();
        assert_eq!(
            widths.iter().filter(|dp| **dp == DOT_WIDTH).count(),
            FEATURED - 1,
            "the carousel should have one small dot for every slide it is not showing"
        );
        assert_eq!(
            widths.iter().filter(|dp| **dp == DOT_ACTIVE_WIDTH).count(),
            1,
            "exactly one dot is the slide being shown"
        );

        let first = catalogue::featured()[0];
        let slide = screen
            .node_drawing(
                screen
                    .mutations()
                    .iter()
                    .find_map(|mutation| match mutation {
                        Mutation::RegisterAsset {
                            asset_id, bytes, ..
                        } if *bytes == first => Some(*asset_id),
                        _ => None,
                    })
                    .expect("the carousel's first slide was never registered"),
            )
            .expect("nothing is drawing the carousel's first slide");
        assert!(
            screen.press_beside(slide),
            "the carousel cannot be turned at all"
        );
        assert!(
            screen.latest_widths().contains(&DOT_ACTIVE_WIDTH),
            "turning the carousel left the wide dot where it was"
        );
        dioxus_compose::window::reset_window_size();
    }

    /// Every destination has to encode, not just the one the shop opens on. A widget only
    /// the bag reaches would otherwise fail for the first person who taps it.
    ///
    /// Pressed by its icon, because the bar has no words on it. That is the reference's
    /// bar: four drawings, the one you are on in the accent, and nothing written under
    /// any of them.
    #[test]
    fn fr15_every_destination_encodes() {
        let mut screen = Screen::new();
        for choice in Destination::STRIP {
            assert!(
                screen.press_icon(choice.icon()),
                "the bar has no icon for {}",
                choice.label()
            );
            assert!(
                !screen.texts().is_empty(),
                "{} drew nothing at all",
                choice.label()
            );
        }
        dioxus_compose::window::reset_window_size();
    }

    /// Opening a garment replaces the catalogue with its page, and going back brings the
    /// catalogue with it. A detail view that cannot be left is a dead end.
    ///
    /// The card is the control, so the press is found from the name written under it
    /// rather than from a "View" link the reference does not have.
    #[test]
    fn fr15_a_garment_opens_and_closes() {
        let mut screen = Screen::new();
        let first = under(Category::New)[0];
        let named = screen
            .node_saying(first.name)
            .expect("the shelf does not say what is on it");
        assert!(
            screen.press_beside(named),
            "no garment on the catalogue opens"
        );
        assert!(
            screen
                .latest_texts()
                .iter()
                .any(|text| text == "Select size"),
            "opening a garment did not bring up its page"
        );
        assert!(
            screen.press("\u{2190}"),
            "the garment's page has no way back"
        );
        assert!(
            screen
                .latest_texts()
                .iter()
                .any(|text| text.starts_with("Let's find")),
            "going back did not bring the catalogue with it"
        );
        dioxus_compose::window::reset_window_size();
    }

    /// Adding the same garment in the same size twice is one line that counts two, not two
    /// lines. A bag that repeats itself adds up to the right number for the wrong reason.
    #[test]
    fn fr15_the_bag_grows_a_line_rather_than_repeating_it() {
        let mut bag = vec![BagLine {
            product: 1,
            size: "S",
            quantity: 1,
        }];
        let same = BagLine {
            product: 1,
            size: "S",
            quantity: 2,
        };
        match bag
            .iter()
            .position(|line| line.product == same.product && line.size == same.size)
        {
            Some(index) => bag[index].quantity += same.quantity,
            None => bag.push(same),
        }
        assert_eq!(bag.len(), 1);
        assert_eq!(bag[0].quantity, 3);
        assert_eq!(price(total(&bag)), "$285");
    }

    /// Nothing in the shop is painted by the design system.
    ///
    /// Named for what it defends: every accent on this screen used to be a `ColorRole`,
    /// so the yellow came out as the running system's blue and the cards came out of the
    /// accent containers as pale lilac and powder blue. The reference has one yellow and
    /// flat grey cards.
    #[test]
    fn fr22_nothing_on_the_screen_is_painted_by_a_role() {
        let screen = Screen::new();
        for mutation in screen.mutations() {
            if let Mutation::SetModifier {
                modifier: Modifier::Background(paint) | Modifier::Border { paint, .. },
                node_id,
                ..
            } = mutation
            {
                assert!(
                    matches!(paint, Paint::Literal(_)),
                    "node {node_id} is filled with {paint:?}, which the design system picks"
                );
            }
        }
    }

    /// The accent appears, and it is the reference's yellow rather than a role that
    /// resolves to one.
    #[test]
    fn fr22_the_selected_destination_is_drawn_in_the_accent() {
        let screen = Screen::new();
        let tints: Vec<Paint> = screen
            .mutations()
            .iter()
            .filter_map(|mutation| match mutation {
                Mutation::SetProp {
                    property: PropertyKind::Color,
                    value: PropertyValue::Integer(bits),
                    ..
                } => Paint::from_bits(*bits as u64),
                _ => None,
            })
            .collect();
        assert_eq!(
            tints
                .iter()
                .filter(|paint| **paint == palette::ACCENT)
                .count(),
            1,
            "the accent marks the destination you are on and nothing else on this screen"
        );
    }

    /// A phone design does not become a desktop design by being put in a wider window.
    #[test]
    fn fr20_the_page_stops_widening_past_a_phone() {
        fn widths(width_dp: f32) -> Vec<f32> {
            dioxus_compose::window::reset_window_size();
            let mut host = Host::new(app);
            host.rebuild().expect("the first frame failed");
            let mut bytes = Vec::new();
            encode_event(
                &HostEvent {
                    node_id: 0,
                    handler_id: 0,
                    payload: EventPayload::WindowSizeChanged {
                        width_dp,
                        height_dp: 780.0,
                        class: WindowSizeClass::from_width_dp(width_dp),
                        height_class: WindowHeightClass::from_height_dp(780.0),
                    },
                },
                &mut bytes,
            )
            .expect("the resize did not encode");
            let (batch, _) = host.dispatch_event(&bytes).expect("the resize failed");
            let found = decode_batch(batch)
                .expect("the batch did not decode")
                .iter()
                .filter_map(|mutation| match mutation {
                    Mutation::SetModifier {
                        modifier: Modifier::Width(dp),
                        ..
                    } => Some(*dp),
                    _ => None,
                })
                .collect();
            dioxus_compose::window::reset_window_size();
            found
        }

        assert!(widths(1180.0).contains(&PAGE_MEASURE));
        assert!(!widths(420.0).contains(&PAGE_MEASURE));
    }

    /// The catalogue, in the design system it ships, in both schemes, at all three widths.
    ///
    /// The category strip windows its labels, so the recorder answers the range request a
    /// real Renderer would have made before the first pixel. Without it the strip is an
    /// empty box and the picture is of a screen with a hole in it.
    #[test]
    fn fr14_the_catalogue_is_recorded_in_the_system_it_ships() {
        sample_frames::record_as(
            "Store",
            &sample_frames::as_designed(THEME, &sample_frames::APPLE),
            app,
            |screen| {
                assert_eq!(
                    screen.fill_lists(6),
                    1,
                    "the category strip should be the catalogue's one windowing list, or \
                     the picture is of a screen with a hole in it"
                );
            },
        );
    }

    /// A garment's own page, which is a different picture: the grid is gone, the sizes and
    /// the stepper are there, and the panel covers the lower part of the tile.
    #[test]
    fn fr14_a_garment_page_is_recorded() {
        sample_frames::record_as(
            "StoreProduct",
            &sample_frames::as_designed(THEME, &sample_frames::APPLE),
            app,
            |screen| {
                screen.fill_lists(6);
                assert!(
                    screen.press_beside(under(Category::New)[0].name),
                    "no garment on the catalogue opens"
                );
            },
        );
    }
}
