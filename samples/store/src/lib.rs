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
    BagLine, CATALOGUE, Category, FEATURED, Product, SIZES, price, price_with_cents, stars, total,
    under,
};
use compose_rust::runtime::*;
use compose_rust::schema::AssetKind;
use compose_rust::ui::*;
use std::rc::Rc;

/// How wide the page is once the window is wider than a phone. A phone design in a desktop
/// window is still a phone design.
const PAGE_MEASURE: f32 = 420.0;

/// How tall a garment's tile is on the catalogue and on the carousel.
///
/// Numbers, because these are the proportions of a picture and the space ladder answers
/// how far apart two things sit rather than how large a picture is.
const TILE_HEIGHT: f32 = 168.0;
const CAROUSEL_HEIGHT: f32 = 200.0;
/// How far the scene on the carousel's first slide stands above the card. The reference's
/// two figures rise out of the top of the grey card rather than sitting inside it.
const CAROUSEL_RISE: f32 = 40.0;
/// How tall the picture on a garment's page is: a little over half a phone's height, which
/// is the share the reference's photograph takes before the panel covers it.
const HERO_HEIGHT: f32 = 400.0;
/// How far the panel on a garment's page reaches up over the picture, and how round its two
/// top corners are. The reference's panel slides over the photograph with its top corners
/// rounded and its bottom running into the window's edge.
const PANEL_OVERLAP: f32 = 28.0;
const PANEL_CORNER: f32 = 28.0;
/// The grabber at the top of that panel.
const GRABBER: (f32, f32) = (36.0, 4.0);
/// How tall the row of category labels is, and the gap after each label.
const STRIP_HEIGHT: f32 = 56.0;
const STRIP_GAP: f32 = 24.0;
/// Which weight the chosen category is written in, and which the others are. The
/// reference sets the chosen one heavier as well as darker.
const CHOSEN_WEIGHT: u16 = 600;
const PLAIN_WEIGHT: u16 = 400;

/// The round dark button on the first screen, which sits on the line where the white page
/// meets the picture, half over each.
const START_SIDE: f32 = 68.0;
/// How far that button sits from the window's right edge.
const START_INSET: f32 = 28.0;

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

/// The size chips on a garment's page: equal circles with gaps between them, which is
/// what the reference draws, rather than the row divided five ways.
const CHIP_SIDE: f32 = 46.0;
/// The stepper's two round buttons.
const STEP_SIDE: f32 = 36.0;
/// How tall the dark pill buttons are: "Add to Bag" and "Checkout".
const PILL_HEIGHT: f32 = 54.0;

/// The icons this shop draws, as the bytes each registration carries: the meaning's wire
/// tag, little endian, and nothing else.
///
/// An icon is a meaning rather than a picture. The Renderer holds the artwork for every
/// design system, so `Home` comes out as this system's house and the shop never says what
/// a house looks like.
///
/// The reference's bar is home, a chat bubble, a location pin and a person, its header
/// has a shopping bag, and its garment page has a heart. The closed set of meanings has
/// none of those but the house yet (compose-rust issue #539), so the shop's destinations
/// are the ones the set can say, and the bag is the inbox tray until a bag exists. The
/// account's `Settings` is drawn as a crosshair at this size under Cupertino, which is the
/// toolkit's to fix (compose-rust issue #537).
mod icon {
    use compose_rust::ui::IconRole;

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
/// marks the destination you are on is that its icon is drawn in the accent. The box round
/// the icon is the control, so the press lands on it whatever the design system drew, and
/// no button of the design system's is laid over it to show its own pressed state.
#[Composable]
fn icon_button(picture: &'static [u8], tint: Paint, on_click: Rc<dyn Fn()>) {
    let press = Rc::clone(&on_click);
    Box()
        .modifier(Modifier.size(ICON_TAP, ICON_TAP))
        .alignment(Alignment::Center)
        .on_click(move || press())
        .content(|| {
            Icon(asset(AssetKind::VectorIcon, picture)).color(tint);
        });
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
#[Composable]
fn header(waiting: u32, on_go: Rc<dyn Fn(Destination)>) {
    Row()
        .modifier(Modifier.fill_max_width())
        .alignment(Alignment::Center)
        .content(|| {
            Canvas(logo_mark()).modifier(Modifier.size(LOGO.0, LOGO.1));
            Spacer().modifier(Modifier.weight(1.0));
            let go = Rc::clone(&on_go);
            icon_button(
                &icon::ORDERS,
                palette::INK,
                Rc::new(move || go(Destination::Search)),
            );
            // The bag, with the mark that says something is in it. The mark is the one
            // red on this screen and it only appears when it means something.
            Box().alignment(Alignment::TopEnd).content(|| {
                let go = Rc::clone(&on_go);
                icon_button(
                    &icon::BAG,
                    palette::INK,
                    Rc::new(move || go(Destination::Bag)),
                );
                if waiting > 0 {
                    Box().modifier(
                        Modifier
                            .size(10.0, 10.0)
                            .background(palette::ALERT)
                            .shape_role(ShapeRole::Full),
                    );
                }
            });
        });
}

/// A garment on its card: the flat grey picture area, and the press that opens it.
///
/// The card is the control. The reference has no button on it, so the card takes the
/// press itself rather than carrying a button of the design system's over the picture.
#[Composable]
fn tile(product: &'static Product, height: f32, on_open: Rc<dyn Fn(u32)>) {
    let id = product.id;
    let open = Rc::clone(&on_open);
    Box()
        .modifier(
            Modifier
                .fill_max_width()
                .height(height)
                .background(palette::TILE)
                .shape_role(ShapeRole::Large),
        )
        .alignment(Alignment::Center)
        .on_click(move || open(id))
        .content(|| {
            Image(asset(AssetKind::Svg, product.picture))
                .modifier(Modifier.fill_max_size().padding_role(SpaceRole::Md));
        });
}

/// One cell of the two-up grid: the picture area, then the name, the support line and the
/// price under it, which is the order the reference writes them in.
///
/// No weight of its own. The cell's parent in the grid is a `Column`, where weight is
/// vertical, and a vertical weight inside a column that is measuring its own height comes
/// out as a height of zero. The width share belongs to the wrapper in the row above.
#[Composable]
fn grid_cell(product: &'static Product, on_open: Rc<dyn Fn(u32)>) {
    Column()
        .modifier(Modifier.fill_max_width())
        .space_role(SpaceRole::Xs)
        .content(|| {
            tile(product, TILE_HEIGHT, Rc::clone(&on_open));
            // Name and price in the regular weight, as the reference writes them: the
            // picture is what the eye goes to, not the words under it.
            Text(product.name)
                .type_role(TypeRole::Body)
                .color(palette::INK)
                .max_lines(1)
                .overflow(TextOverflow::Ellipsis);
            Text(product.support)
                .type_role(TypeRole::Caption)
                .color(palette::MUTED)
                .max_lines(1)
                .overflow(TextOverflow::Ellipsis);
            Text(price(product.cents))
                .type_role(TypeRole::Body)
                .color(palette::INK);
        });
}

/// The carousel: one wide picture on the flat grey card, with the dots under it.
///
/// The slide is the Host's rather than a scroll position, because a scroll position
/// belongs to the Renderer and is never reported back: dots driven by one would be drawn
/// in the right place and never move. Pressing the card turns it, which is the nearest
/// thing to the reference's swipe that the vocabulary has.
///
/// The first slide is the scene, and it stands on the card with the figures' heads above
/// the card's top edge, as the reference's photograph does. So the carousel is taller than
/// its card by that rise, the card sits at the bottom of it, and the scene is drawn over
/// both. A garment slide is a product shot and stays inside the card.
#[Composable]
fn carousel(slide: MutableState<usize>) {
    let showing = slide.get() % FEATURED;
    let picture = catalogue::featured()[showing];
    let scene = picture == catalogue::HERO;
    Box()
        .modifier(
            Modifier
                .fill_max_width()
                .height(CAROUSEL_HEIGHT + CAROUSEL_RISE),
        )
        .alignment(Alignment::BottomCenter)
        .content(|| {
            let turn = slide;
            Box()
                .modifier(
                    Modifier
                        .fill_max_width()
                        .height(CAROUSEL_HEIGHT)
                        .background(palette::TILE)
                        .shape_role(ShapeRole::Large),
                )
                .alignment(Alignment::Center)
                .on_click(move || turn.set((turn.get_untracked() + 1) % FEATURED))
                .content(|| {
                    if !scene {
                        Image(asset(AssetKind::Svg, picture))
                            .modifier(Modifier.fill_max_size().padding_role(SpaceRole::Md));
                    }
                });
            if scene {
                Image(asset(AssetKind::Svg, picture)).modifier(Modifier.fill_max_size());
            }
        });
    Row()
        .modifier(Modifier.fill_max_width())
        // Centred across the row, which needs the arrangement rather than the alignment:
        // alignment answers where a child sits across the row's other axis, so a row of
        // dots set to centre alignment is a row of vertically centred dots still starting
        // at the left edge.
        .arrangement(Arrangement::Center)
        .alignment(Alignment::Center)
        .content(|| {
            for position in 0..FEATURED {
                key(position, || {
                    Box()
                        .modifier(Modifier.size(DOT_SLOT, DOT_HEIGHT))
                        .alignment(Alignment::Center)
                        .content(|| {
                            let active = position == showing;
                            Box().modifier(
                                Modifier
                                    .size(
                                        if active { DOT_ACTIVE_WIDTH } else { DOT_WIDTH },
                                        DOT_HEIGHT,
                                    )
                                    .background(if active { palette::DARK } else { palette::DOT })
                                    .shape_role(ShapeRole::Full),
                            );
                        });
                });
            }
        });
}

/// The catalogue: the header, the heading, the carousel, the category strip and a two-up
/// grid.
#[Composable]
fn catalogue_screen(
    category: MutableState<Category>,
    slide: MutableState<usize>,
    bag: MutableState<Vec<BagLine>>,
    on_open: Rc<dyn Fn(u32)>,
    on_go: Rc<dyn Fn(Destination)>,
) {
    let shelf = under(category.get());
    let rows: Vec<Vec<&'static Product>> = shelf.chunks(2).map(<[_]>::to_vec).collect();
    let in_bag: u32 = bag.with(|lines| lines.iter().map(|line| line.quantity).sum());

    Column()
        .modifier(Modifier.fill_max_width().padding_role(SpaceRole::Md))
        .space_role(SpaceRole::Md)
        .content(|| {
            header(in_bag, Rc::clone(&on_go));

            // Broken where the reference breaks it, so the heading is two lines at every
            // width rather than one line on a phone wide enough to hold it.
            Text("Let's find your\nsports outfit!")
                .type_role(TypeRole::Headline)
                .color(palette::INK)
                .modifier(Modifier.fill_max_width());

            carousel(slide);

            // A strip that runs off the edge, not a segmented control.
            //
            // `Tabs` divides the width it is given equally between its children and does
            // not scroll, so five categories on a phone is five segments of seventy dp,
            // and a button inside one carries its own horizontal padding: every label
            // broke into a column of single letters. The reference's own strip runs past
            // the right edge with the last category half shown, which is this.
            let strip = category;
            LazyRow()
                .modifier(Modifier.fill_max_width().height(STRIP_HEIGHT))
                .content(move |list| {
                    list.items_keyed(
                        Category::STRIP.len(),
                        |position| Category::STRIP[position].label().to_owned(),
                        move |position| {
                            let choice = Category::STRIP[position];
                            let chosen = strip.get() == choice;
                            let pick = strip;
                            // Every category is a label and one of them is the one you are
                            // looking at, which is what the reference draws: the chosen one
                            // heavier and in the reading ink, the rest regular and in the
                            // quieter one. Filling the chosen one instead made the row read
                            // as five actions on a screen whose only real action is adding
                            // something to the bag. A label rather than a button, because a
                            // button's words take the design system's button type and
                            // padding, and the reference's are plain words with even gaps.
                            Row().alignment(Alignment::Center).content(|| {
                                Box()
                                    .alignment(Alignment::Center)
                                    .on_click(move || pick.set(choice))
                                    .content(|| {
                                        Text(choice.label())
                                            .type_role(TypeRole::Subtitle)
                                            .font_weight(if chosen {
                                                CHOSEN_WEIGHT
                                            } else {
                                                PLAIN_WEIGHT
                                            })
                                            .color(if chosen {
                                                palette::INK
                                            } else {
                                                palette::MUTED
                                            })
                                            .max_lines(1);
                                    });
                                Spacer().modifier(Modifier.width(STRIP_GAP));
                            });
                        },
                    );
                });

            Column()
                .modifier(Modifier.fill_max_width())
                .space_role(SpaceRole::Md)
                .content(|| {
                    for (index, row) in rows.iter().enumerate() {
                        key(index, || {
                            Row()
                                .modifier(Modifier.fill_max_width())
                                .space_role(SpaceRole::Md)
                                .alignment(Alignment::TopStart)
                                .content(|| {
                                    for product in row.iter().copied() {
                                        key(product.id, || {
                                            Column().modifier(Modifier.weight(1.0)).content(|| {
                                                grid_cell(product, Rc::clone(&on_open))
                                            });
                                        });
                                    }
                                    // A shelf with an odd number of garments leaves a gap
                                    // rather than letting the last one stretch to twice the
                                    // width of its neighbours, which would read as a
                                    // different kind of product.
                                    if row.len() == 1 {
                                        Spacer().modifier(Modifier.weight(1.0));
                                    }
                                });
                        });
                    }
                });
        });
}

/// The shop's first screen: the mark, what the shop is, and the way in.
///
/// The reference opens on this before the catalogue: a white top with the mark and the
/// shop's name in large regular type, a picture filling everything under it to the
/// window's edges, and one round dark button with an arrow sitting on the line where the
/// two meet. There is no bar along the bottom, because nothing is reachable from here but
/// the shop itself.
///
/// The button straddles that line without drawing outside anything. The picture starts
/// half the button's height below the top of the area it shares with the button, so the
/// white page shows above the picture's top edge and the button, laid over both, is
/// centred on it.
#[Composable]
fn onboarding_screen(on_start: Rc<dyn Fn()>) {
    Column()
        .modifier(
            Modifier
                .fill_max_width()
                .fill_max_height()
                .background(palette::PAGE),
        )
        .content(|| {
            Column()
                .modifier(Modifier.fill_max_width().padding_role(SpaceRole::Md))
                .space_role(SpaceRole::Xl)
                .content(|| {
                    Canvas(logo_mark()).modifier(Modifier.size(LOGO.0, LOGO.1));
                    Text(WELCOME_TITLE)
                        .type_role(TypeRole::Headline)
                        .font_weight(PLAIN_WEIGHT)
                        .color(palette::INK);
                });
            Box()
                .modifier(Modifier.fill_max_width().weight(1.0))
                .alignment(Alignment::TopEnd)
                .content(|| {
                    Column().modifier(Modifier.fill_max_size()).content(|| {
                        Spacer().modifier(Modifier.height(START_SIDE / 2.0));
                        Image(asset(AssetKind::Svg, catalogue::WELCOME))
                            .modifier(Modifier.fill_max_width().weight(1.0));
                    });
                    Row().alignment(Alignment::Center).content(|| {
                        let start = Rc::clone(&on_start);
                        Button("")
                            .icon(IconRole::Forward)
                            .accessible_name("Start shopping")
                            .variant(ButtonVariant::Text)
                            .color(palette::PAGE)
                            .modifier(
                                Modifier
                                    .size(START_SIDE, START_SIDE)
                                    .background(palette::DARK)
                                    .shape_role(ShapeRole::Full),
                            )
                            .on_click(move || start());
                        Spacer().modifier(Modifier.width(START_INSET));
                    });
                });
        });
}

/// What the first screen says the shop is, broken where the reference breaks it.
const WELCOME_TITLE: &str = "Personal Fitness\nClothes App";

/// One garment: the picture, what it is, what size, how many, and what it costs.
///
/// The picture runs to the window's edges and the panel slides up over its lower edge,
/// with its two top corners rounded and a grabber on it, which is the reference's page.
/// The two are layers of one box rather than one stacked under the other: the panel's
/// column starts with a gap a little shorter than the picture, so the panel's top lies
/// over the picture and nothing is drawn outside the box. The back control is the top
/// layer, so the panel never covers it.
///
/// The reference also has a heart at the top right. The closed set of icon meanings has no
/// heart yet (compose-rust issue #539), so that corner stays empty rather than holding a
/// character pretending to be one.
#[Composable]
fn detail_screen(
    product: &'static Product,
    size: MutableState<&'static str>,
    quantity: MutableState<u32>,
    on_back: Rc<dyn Fn()>,
    on_add: Rc<dyn Fn()>,
) {
    let count = quantity.get();
    let chosen = size.get();
    let line_total = price_with_cents(product.cents * count);
    let (lit, unlit) = stars(product.rating);
    let rating = product.rating;

    Box()
        .modifier(
            Modifier
                .fill_max_width()
                .fill_max_height()
                .background(palette::PAGE),
        )
        .alignment(Alignment::TopStart)
        .content(|| {
            // The garment, full size. The reference's product page is a photograph
            // running to the window's edges with the panel covering its lower part, and a
            // page that fills that with a flat colour is the one screen in the shop that
            // shows you nothing about what you are buying.
            Box()
                .modifier(
                    Modifier
                        .fill_max_width()
                        .height(HERO_HEIGHT)
                        .background(palette::TILE),
                )
                .alignment(Alignment::Center)
                .content(|| {
                    Image(asset(AssetKind::Svg, product.picture))
                        .modifier(Modifier.fill_max_size().padding_role(SpaceRole::Xl));
                });

            Column().modifier(Modifier.fill_max_size()).content(|| {
                Spacer().modifier(Modifier.height(HERO_HEIGHT - PANEL_OVERLAP));
                Column()
                    .modifier(
                        Modifier
                            .fill_max_width()
                            .weight(1.0)
                            .background(palette::PAGE)
                            .corner_radii([PANEL_CORNER, PANEL_CORNER, 0.0, 0.0])
                            .padding_role(SpaceRole::Md),
                    )
                    .space_role(SpaceRole::Md)
                    .content(|| {
                        Box()
                            .modifier(Modifier.fill_max_width())
                            .alignment(Alignment::Center)
                            .content(|| {
                                Box().modifier(
                                    Modifier
                                        .size(GRABBER.0, GRABBER.1)
                                        .background(palette::DOT)
                                        .shape_role(ShapeRole::Full),
                                );
                            });
                        Column()
                            .modifier(Modifier.fill_max_width())
                            .space_role(SpaceRole::Xs)
                            .content(|| {
                                Text(product.name)
                                    .type_role(TypeRole::Headline)
                                    .color(palette::INK);
                                Text(product.support)
                                    .type_role(TypeRole::Body)
                                    .color(palette::MUTED);
                                Row()
                                    .space_role(SpaceRole::Sm)
                                    .alignment(Alignment::CenterStart)
                                    .content(|| {
                                        // Two runs of the same star in two inks,
                                        // so the one the rating does not reach is
                                        // grey rather than hollow.
                                        Row().alignment(Alignment::CenterStart).content(|| {
                                            if !lit.is_empty() {
                                                Text(lit.as_str())
                                                    .type_role(TypeRole::Caption)
                                                    .color(palette::INK);
                                            }
                                            if !unlit.is_empty() {
                                                Text(unlit.as_str())
                                                    .type_role(TypeRole::Caption)
                                                    .color(palette::FAINT);
                                            }
                                        });
                                        Text(format!("({})", catalogue::rating_text(rating)))
                                            .type_role(TypeRole::Label)
                                            .color(palette::MUTED);
                                    });
                            });

                        Column()
                            .modifier(Modifier.fill_max_width())
                            .space_role(SpaceRole::Sm)
                            .content(|| {
                                Text("Select size")
                                    .type_role(TypeRole::Label)
                                    .color(palette::MUTED);
                                Row().space_role(SpaceRole::Md).content(|| {
                                    for option in SIZES {
                                        key(option, || size_chip(option, chosen, size));
                                    }
                                });
                            });

                        Row()
                            .modifier(Modifier.fill_max_width())
                            .space_role(SpaceRole::Md)
                            .alignment(Alignment::CenterStart)
                            .content(|| {
                                let fewer = quantity;
                                step_button(
                                    "\u{2212}",
                                    false,
                                    Rc::new(move || {
                                        fewer.set(fewer.get_untracked().saturating_sub(1).max(1))
                                    }),
                                );
                                Text(count.to_string())
                                    .type_role(TypeRole::Subtitle)
                                    .color(palette::INK);
                                let more = quantity;
                                step_button(
                                    "+",
                                    true,
                                    Rc::new(move || more.set((more.get_untracked() + 1).min(9))),
                                );
                                Spacer().modifier(Modifier.weight(1.0));
                                Text(line_total.as_str())
                                    .type_role(TypeRole::Headline)
                                    .color(palette::INK);
                            });

                        let add = Rc::clone(&on_add);
                        Button("Add to Bag")
                            .modifier(
                                Modifier
                                    .fill_max_width()
                                    .height(PILL_HEIGHT)
                                    .background(palette::DARK)
                                    .shape_role(ShapeRole::Full),
                            )
                            .variant(ButtonVariant::Text)
                            .color(palette::PAGE)
                            .on_click(move || add());
                    });
            });

            Row()
                .modifier(Modifier.fill_max_width().padding_role(SpaceRole::Sm))
                .alignment(Alignment::CenterStart)
                .content(|| {
                    let back = Rc::clone(&on_back);
                    Button("")
                        .icon(IconRole::Back)
                        .variant(ButtonVariant::Text)
                        .color(palette::INK)
                        .modifier(Modifier.size(ICON_TAP, ICON_TAP))
                        .on_click(move || back());
                });
        });
}

/// One size on a garment's page: a circle with the size written in it.
///
/// Equal circles with gaps between them, as the reference draws them, rather than the row
/// shared five ways, which made five pills as wide as the window allowed. The chosen size
/// is the one thing on this page in the accent; the rest are the same flat grey as the
/// card the garment is drawn on.
#[Composable]
fn size_chip(option: &'static str, chosen: &'static str, size: MutableState<&'static str>) {
    Box()
        .modifier(
            Modifier
                .size(CHIP_SIDE, CHIP_SIDE)
                .background(if option == chosen {
                    palette::ACCENT
                } else {
                    palette::TILE
                })
                .shape_role(ShapeRole::Full),
        )
        .alignment(Alignment::Center)
        .on_click(move || size.set(option))
        .content(|| {
            Text(option).type_role(TypeRole::Body).color(palette::INK);
        });
}

/// One of the stepper's two round buttons: an outlined circle for fewer and a filled dark
/// one for more, which is the reference's pair.
#[Composable]
fn step_button(glyph: &'static str, filled: bool, on_step: Rc<dyn Fn()>) {
    let circle = Modifier
        .size(STEP_SIDE, STEP_SIDE)
        .shape_role(ShapeRole::Full);
    let face = if filled {
        circle.background(palette::DARK)
    } else {
        circle.background(palette::PAGE).border(1.0, palette::DARK)
    };
    let step = Rc::clone(&on_step);
    Box()
        .modifier(face)
        .alignment(Alignment::Center)
        .on_click(move || step())
        .content(|| {
            Text(glyph).type_role(TypeRole::Body).color(if filled {
                palette::PAGE
            } else {
                palette::INK
            });
        });
}

/// What is waiting to be paid for.
#[Composable]
fn bag_screen(bag: MutableState<Vec<BagLine>>) {
    let lines = bag.get();
    let sum = total(&lines);

    Column()
        .modifier(
            Modifier
                .fill_max_width()
                .fill_max_height()
                .padding_role(SpaceRole::Md),
        )
        .space_role(SpaceRole::Md)
        .content(|| {
            Text("Bag")
                .type_role(TypeRole::Headline)
                .color(palette::INK);
            if lines.is_empty() {
                Box()
                    .modifier(Modifier.fill_max_width().weight(1.0))
                    .alignment(Alignment::Center)
                    .content(|| {
                        Text("Nothing in the bag yet.")
                            .type_role(TypeRole::Body)
                            .color(palette::MUTED);
                    });
            } else {
                Column()
                    .modifier(
                        Modifier
                            .fill_max_width()
                            .background(palette::TILE)
                            .shape_role(ShapeRole::Large),
                    )
                    .content(|| {
                        for (index, line) in lines.iter().copied().enumerate() {
                            let last = index + 1 == lines.len();
                            key(index, || bag_line(bag, index, line, last));
                        }
                    });
                Spacer().modifier(Modifier.weight(1.0));
                Row()
                    .modifier(Modifier.fill_max_width())
                    .alignment(Alignment::CenterStart)
                    .content(|| {
                        Text("Total")
                            .type_role(TypeRole::Body)
                            .color(palette::MUTED)
                            .modifier(Modifier.weight(1.0));
                        Text(price(sum))
                            .type_role(TypeRole::Headline)
                            .color(palette::INK);
                    });
                Button("Checkout")
                    .modifier(
                        Modifier
                            .fill_max_width()
                            .height(PILL_HEIGHT)
                            .background(palette::DARK)
                            .shape_role(ShapeRole::Full),
                    )
                    .variant(ButtonVariant::Text)
                    .color(palette::PAGE)
                    .on_click(|| Message::new("Checkout is not part of this sample").show());
            }
        });
}

/// One line of the bag, with the hairline under it unless it is the last.
#[Composable]
fn bag_line(bag: MutableState<Vec<BagLine>>, index: usize, line: BagLine, last: bool) {
    let named = catalogue::product(line.product);
    Column().modifier(Modifier.fill_max_width()).content(|| {
        Row()
            .modifier(Modifier.fill_max_width().padding_role(SpaceRole::Md))
            .space_role(SpaceRole::Sm)
            .alignment(Alignment::CenterStart)
            .content(|| {
                Column()
                    .modifier(Modifier.weight(1.0))
                    .space_role(SpaceRole::Xs)
                    .content(|| {
                        Text(named.map_or("Unavailable", |found| found.name))
                            .type_role(TypeRole::Body)
                            .color(palette::INK)
                            .max_lines(1)
                            .overflow(TextOverflow::Ellipsis);
                        Text(format!("Size {} \u{00b7} {}", line.size, line.quantity))
                            .type_role(TypeRole::Caption)
                            .color(palette::MUTED);
                    });
                Text(price(named.map_or(0, |found| found.cents) * line.quantity))
                    .type_role(TypeRole::BodyStrong)
                    .color(palette::INK);
                let lines = bag;
                Button("Remove")
                    .variant(ButtonVariant::Text)
                    .color(palette::ALERT)
                    .on_click(move || {
                        let mut removed = None;
                        lines.update(|lines| {
                            if index < lines.len() {
                                removed = Some(lines.remove(index));
                            }
                        });
                        let Some(removed) = removed else { return };
                        let name = catalogue::product(removed.product)
                            .map_or("An item", |found| found.name);
                        let restore = lines;
                        Message::new(format!("Removed {name}"))
                            .with_action("Undo", move |()| {
                                let at = index.min(restore.with(Vec::len));
                                restore.update(|lines| lines.insert(at, removed));
                            })
                            .with_duration(MessageDuration::Long)
                            .show();
                    });
            });
        if !last {
            Separator(Some(palette::OUTLINE));
        }
    });
}

/// Search, which is a field and the shelves as a list.
#[Composable]
fn search_screen(on_open: Rc<dyn Fn(u32)>) {
    Column()
        .modifier(
            Modifier
                .fill_max_width()
                .fill_max_height()
                .padding_role(SpaceRole::Md),
        )
        .space_role(SpaceRole::Md)
        .content(|| {
            Text("Search")
                .type_role(TypeRole::Headline)
                .color(palette::INK);
            TextField()
                .modifier(Modifier.fill_max_width())
                .placeholder("T-shirts, joggers, jackets");
            Column()
                .modifier(Modifier.fill_max_width())
                .space_role(SpaceRole::Sm)
                .content(|| {
                    for product in CATALOGUE.iter() {
                        key(product.id, || {
                            Row()
                                .modifier(Modifier.fill_max_width())
                                .space_role(SpaceRole::Sm)
                                .alignment(Alignment::CenterStart)
                                .content(|| {
                                    Box()
                                        .modifier(
                                            Modifier
                                                .size(44.0, 44.0)
                                                .background(palette::TILE)
                                                .shape_role(ShapeRole::Medium),
                                        )
                                        .alignment(Alignment::Center)
                                        .content(|| {
                                            Image(asset(AssetKind::Svg, product.picture)).modifier(
                                                Modifier
                                                    .fill_max_size()
                                                    .padding_role(SpaceRole::Xs),
                                            );
                                        });
                                    Column().modifier(Modifier.weight(1.0)).content(|| {
                                        Text(product.name)
                                            .type_role(TypeRole::Body)
                                            .color(palette::INK)
                                            .max_lines(1)
                                            .overflow(TextOverflow::Ellipsis);
                                        Text(product.support)
                                            .type_role(TypeRole::Caption)
                                            .color(palette::MUTED)
                                            .max_lines(1)
                                            .overflow(TextOverflow::Ellipsis);
                                    });
                                    let (open, id) = (Rc::clone(&on_open), product.id);
                                    Button(price(product.cents))
                                        .variant(ButtonVariant::Text)
                                        .color(palette::INK)
                                        .on_click(move || open(id));
                                });
                        });
                    }
                });
        });
}

/// The account page, which is a short list of the things a shop keeps about a person.
#[Composable]
fn account_screen() {
    Column()
        .modifier(
            Modifier
                .fill_max_width()
                .fill_max_height()
                .padding_role(SpaceRole::Md),
        )
        .space_role(SpaceRole::Md)
        .content(|| {
            Text("Account")
                .type_role(TypeRole::Headline)
                .color(palette::INK);
            Column()
                .modifier(
                    Modifier
                        .fill_max_width()
                        .background(palette::TILE)
                        .shape_role(ShapeRole::Large),
                )
                .content(|| {
                    for (index, entry) in ["Orders", "Addresses", "Payment", "Notifications"]
                        .into_iter()
                        .enumerate()
                    {
                        key(entry, || {
                            Column().modifier(Modifier.fill_max_width()).content(|| {
                                Row()
                                    .modifier(Modifier.fill_max_width().padding_role(SpaceRole::Md))
                                    .alignment(Alignment::CenterStart)
                                    .content(|| {
                                        Text(entry)
                                            .type_role(TypeRole::Body)
                                            .color(palette::INK)
                                            .modifier(Modifier.weight(1.0));
                                        Text("\u{203a}")
                                            .type_role(TypeRole::Body)
                                            .color(palette::MUTED);
                                    });
                                if index < 3 {
                                    Separator(Some(palette::OUTLINE));
                                }
                            });
                        });
                    }
                });
        });
}

/// The bar along the bottom: four icons and nothing else.
///
/// Drawn here rather than declared as a `Navigation`, which is the widget for "the
/// destinations, in whatever shape this design system and this window call for": a
/// labelled bar with a selection pill under a hairline. The reference is four bare icons
/// on the page, the one you are on in the accent, and that is a shape no design system
/// would be right to give a set of destinations.
#[Composable]
fn bottom_bar(destination: Destination, on_go: Rc<dyn Fn(Destination)>) {
    Row()
        .modifier(Modifier.fill_max_width().padding_role(SpaceRole::Sm))
        .arrangement(Arrangement::SpaceAround)
        .alignment(Alignment::Center)
        .content(|| {
            for choice in Destination::STRIP {
                let tint = if choice == destination {
                    palette::ACCENT
                } else {
                    palette::INK
                };
                key(choice.label(), || {
                    Box().content(|| {
                        let go = Rc::clone(&on_go);
                        icon_button(choice.icon(), tint, Rc::new(move || go(choice)));
                    });
                });
            }
        });
}

#[Composable]
pub fn app() {
    let window = current_window_size();
    let measure = if window.is_compact() {
        None
    } else {
        Some(PAGE_MEASURE)
    };

    // The shop opens on its first screen, as the reference does, and the arrow on it is
    // the way in. Once in, there is nothing that leads back to it.
    let started = remember(|| mutable_state_of(false));
    let destination = remember(|| mutable_state_of(Destination::Shop));
    let category = remember(|| mutable_state_of(Category::New));
    let slide = remember(|| mutable_state_of(0_usize));
    let open = remember(|| mutable_state_of(Option::<u32>::None));
    let size = remember(|| mutable_state_of(SIZES[1]));
    let quantity = remember(|| mutable_state_of(1_u32));
    // The shop opens with something already in the bag, which is the state the reference
    // draws: the mark on the bag in the corner is red because something is waiting, and a
    // mark that is always drawn would say that whether or not it were true.
    let bag = remember(|| {
        mutable_state_of(vec![BagLine {
            product: 2,
            size: "M",
            quantity: 1,
        }])
    });

    let on_open: Rc<dyn Fn(u32)> = {
        let (destination, open) = (destination, open);
        Rc::new(move |id: u32| {
            destination.set(Destination::Shop);
            open.set(Some(id));
        })
    };
    let on_go: Rc<dyn Fn(Destination)> = {
        let (destination, open) = (destination, open);
        Rc::new(move |choice: Destination| {
            destination.set(choice);
            if choice != Destination::Shop {
                open.set(None);
            }
        })
    };

    let inside = started.get();
    let current = destination.get();
    let showing = open.get().and_then(catalogue::product);
    // The first screen and a garment's page fill the window, because each has a picture
    // that runs to the edge, and neither has the bar along the bottom: the reference draws
    // both without one. Everything else is a page that scrolls, above the bar.
    let full_screen = !inside || (current == Destination::Shop && showing.is_some());

    let body = || match (current, showing) {
        _ if !inside => onboarding_screen(Rc::new(move || started.set(true))),
        (Destination::Shop, Some(product)) => {
            let back: Rc<dyn Fn()> = { Rc::new(move || open.set(None)) };
            detail_screen(
                product,
                size,
                quantity,
                back,
                add_to_bag(bag, open, size, quantity),
            );
        }
        (Destination::Shop, None) => {
            catalogue_screen(category, slide, bag, Rc::clone(&on_open), Rc::clone(&on_go))
        }
        (Destination::Search, _) => search_screen(Rc::clone(&on_open)),
        (Destination::Bag, _) => bag_screen(bag),
        (Destination::Account, _) => account_screen(),
    };

    // The frame is named rather than built, and the page is laid out clear of whatever the
    // bar took. A screen that fills the window gives the frame no bar at all.
    let frame = Scaffold().modifier(Modifier.background(palette::PAGE));
    let frame = if full_screen {
        frame
    } else {
        frame.bottom_bar(|| {
            Box()
                .modifier(Modifier.fill_max_width())
                .alignment(Alignment::Center)
                .content(|| {
                    Column()
                        .modifier(
                            Modifier
                                .width_if(measure)
                                .fill_max_width_if(measure.is_none()),
                        )
                        .content(|| bottom_bar(current, Rc::clone(&on_go)));
                });
        })
    };
    frame.content(|| {
        Box()
            .modifier(Modifier.weight(1.0))
            .alignment(Alignment::TopCenter)
            .content(|| {
                if !full_screen {
                    ScrollColumn()
                        .modifier(Modifier.width_if(measure))
                        .content(body);
                } else {
                    Column().modifier(Modifier.width_if(measure)).content(body);
                }
            });
    });
}

/// Putting the open garment in the bag, at the size and count that are showing.
///
/// A line that is already there grows rather than repeating, because two lines for the
/// same garment in the same size is a bag nobody can read, and it makes the total right
/// for the wrong reason.
fn add_to_bag(
    bag: MutableState<Vec<BagLine>>,
    open: MutableState<Option<u32>>,
    size: MutableState<&'static str>,
    quantity: MutableState<u32>,
) -> Rc<dyn Fn()> {
    Rc::new(move || {
        let Some(id) = open.get_untracked() else {
            return;
        };
        let chosen = size.get_untracked();
        let count = quantity.get_untracked();
        bag.update(|lines| {
            match lines
                .iter()
                .position(|line| line.product == id && line.size == chosen)
            {
                Some(index) => lines[index].quantity += count,
                None => lines.push(BagLine {
                    product: id,
                    size: chosen,
                    quantity: count,
                }),
            }
        });
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

/// Runs the sample as a program of its own. The desktop binary is one line of this.
pub fn launch() {
    launch_builder().launch(app);
}

/// How this sample is configured, in one place because three entry points need it.
///
/// The theme above all: a sample that names one and then reaches a platform through an
/// entry point that makes its own builder is a sample that draws the same screens in a
/// different design system depending on where it runs. `demo_theme_for` rather than
/// `THEME` alone: a sample is something to look at, and one machine can only show the
/// design system and the scheme it is set to. `DXC_DESIGN` and `DXC_SCHEME` each override
/// the half they name, so `THEME` stays the answer to everything nobody asked about.
fn launch_builder() -> LaunchBuilder {
    // The name the window carries. A desktop lists windows by it, so a window that said
    // nothing was listed under whatever the renderer happened to be called.
    LaunchBuilder::new()
        // The same as [bundle] identifier in Dioxus.toml. It names the storage.
        .identifier("dev.darkpyonix.composerust.samples.store")
        .with_theme(compose_rust::demo_theme_for(THEME))
        .with_window(
            compose_rust::schema::Window::new()
                .with_title("Store")
                // Without one the window wears the toolkit's picture wherever the system
                // lists windows. The bytes travel as an asset and the renderer refers to
                // them by id: a path would be a fact about the machine this was built on.
                .with_icon(compose_rust::asset::asset(
                    AssetKind::Png,
                    include_bytes!("../assets/icon.png"),
                )),
        )
}

// The platforms where the sample is not a program. Android's Activity and the browser's
// page both own the loop, so neither has a `main` to call: each names an entry point that
// registers the root composable, and these macros define it.
//
// Both are declared unconditionally. Each macro compiles into nothing that runs off its
// own platform, and gating them here instead would mean a desktop build never checks that
// this sample can still be built for the other two.
compose_rust::android_application!({ launch_builder() }, app);
compose_rust::web_application!({ launch_builder() }, app);
compose_rust::ios_main!(launch);

#[cfg(test)]
mod tests {
    use super::*;

    use compose_rust::ComposeHost as Host;
    use compose_rust::protocol::{HostEvent, Mutation, PropertyValue, decode_batch, encode_event};
    use compose_rust::schema::{EventPayload, Modifier, PropertyKind, WidgetKind};

    /// Named for what it defends: the reference is a light design, and a machine set
    /// the other way drew this sample dark with nothing to compare against.
    #[test]
    fn fr14_the_design_names_its_colour_scheme() {
        // Through the wire rather than off the constant: what settles the question is the
        // record the Renderer reads, and a scheme that never leaves the Host is a scheme
        // nobody is drawn in.
        compose_rust::window::reset_window_size();
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
        /// The shop as it opens: the first screen, before the arrow has been pressed.
        fn at_the_door() -> Self {
            compose_rust::window::reset_window_size();
            let mut host = Host::new(app);
            let first = host.rebuild().expect("the first frame failed").to_vec();
            let mut screen = Self {
                host,
                frames: vec![first],
            };
            screen.resize(420.0);
            screen
        }

        /// The shop once the arrow on the first screen has been pressed, which is where
        /// everything but the first screen is reached from.
        fn new() -> Self {
            let mut screen = Self::at_the_door();
            assert!(
                screen.press_role(IconRole::Forward),
                "the first screen has no way into the shop"
            );
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
                    safe_area_top: 0.0,
                    safe_area_leading: 0.0,
                    safe_area_trailing: 0.0,
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

        /// What is on screen now, without the nodes later batches removed. A control is
        /// looked up here, so a press never names a handler that went with its node.
        fn live(&self) -> Vec<Mutation<'_>> {
            sample_frames::live(self.mutations())
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
            self.live()
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
            self.live()
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
            self.live()
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
            self.live()
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

        /// The node that carries an icon button of this meaning, if one is on screen.
        fn node_with_role(&self, role: IconRole) -> Option<u32> {
            self.live()
                .iter()
                .rev()
                .find_map(|mutation| match mutation {
                    Mutation::SetProp {
                        node_id,
                        property: PropertyKind::Icon,
                        value: PropertyValue::Integer(value),
                    } if *value == role as i64 => Some(*node_id),
                    _ => None,
                })
        }

        /// Presses the icon button of this meaning: a button with no words on it, named
        /// by what its glyph means.
        fn press_role(&mut self, role: IconRole) -> bool {
            let Some(node_id) = self.node_with_role(role) else {
                return false;
            };
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

        /// Presses the control a piece of text is written in, such as a size in its
        /// circle or a category in the strip.
        fn press_text(&mut self, label: &str) -> bool {
            let Some(node) = self.node_saying(label) else {
                return false;
            };
            self.press_beside(node)
        }

        /// Every modifier a node on screen now carries, in the order they were set.
        fn modifiers_of(&self, node: u32) -> Vec<Modifier> {
            self.live()
                .iter()
                .filter_map(|mutation| match mutation {
                    Mutation::SetModifier {
                        node_id, modifier, ..
                    } if *node_id == node => Some(modifier.clone()),
                    _ => None,
                })
                .collect()
        }

        /// What a node on screen now was last given as a property of this kind.
        fn integer_prop(&self, node: u32, property: PropertyKind) -> Option<i64> {
            self.live()
                .iter()
                .rev()
                .find_map(|mutation| match mutation {
                    Mutation::SetProp {
                        node_id,
                        property: found,
                        value: PropertyValue::Integer(value),
                    } if *node_id == node && *found == property => Some(*value),
                    _ => None,
                })
        }

        /// The colour a node on screen now is drawn in.
        fn colour_of(&self, node: u32) -> Option<Paint> {
            self.integer_prop(node, PropertyKind::Color)
                .and_then(|bits| Paint::from_bits(bits as u64))
        }

        /// The texts on screen now, without those of screens that have gone.
        fn live_texts(&self) -> Vec<String> {
            self.live()
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

        /// Whether an icon registered with these bytes is drawn by anything on screen now.
        fn shows_icon(&self, tag: &[u8]) -> bool {
            self.icon_asset(tag)
                .and_then(|asset_id| self.node_drawing(asset_id))
                .is_some()
        }

        /// The children a node on screen now holds.
        fn children_of(&self, node: u32) -> Vec<u32> {
            let mut children: Vec<u32> = Vec::new();
            for (child, parent) in self.parents() {
                if parent == node && !children.contains(&child) {
                    children.push(child);
                }
            }
            children
                .into_iter()
                .filter(|child| self.parent_of(*child) == Some(node))
                .collect()
        }

        /// Answers the category strip's range request, which is what a real Renderer does
        /// before the first pixel. Until it is answered the strip holds no labels.
        fn fill_strip(&mut self) {
            let lists: Vec<(u32, u64)> = {
                let live = self.live();
                live.iter()
                    .filter_map(|mutation| match mutation {
                        Mutation::Create {
                            node_id,
                            widget: WidgetKind::LazyRow,
                        } => Some(*node_id),
                        _ => None,
                    })
                    .filter_map(|node| {
                        live.iter().rev().find_map(|mutation| match mutation {
                            Mutation::SetProp {
                                node_id,
                                property: PropertyKind::OnRangeRequested,
                                value: PropertyValue::Integer(id),
                            } if *node_id == node => Some((node, *id as u64)),
                            _ => None,
                        })
                    })
                    .collect()
            };
            assert_eq!(lists.len(), 1, "the catalogue should have one strip");
            for (node_id, handler_id) in lists {
                self.send(HostEvent {
                    node_id,
                    handler_id,
                    payload: EventPayload::RangeRequested {
                        start: 0,
                        count: Category::STRIP.len() as u32,
                    },
                });
            }
        }

        /// Opens a garment from the catalogue by the name written under it.
        fn open(&mut self, name: &str) {
            let named = self
                .node_saying(name)
                .unwrap_or_else(|| panic!("the shelf does not show {name}"));
            assert!(self.press_beside(named), "{name} does not open");
            assert!(
                self.live_texts().iter().any(|text| text == "Select size"),
                "opening {name} did not bring up its page"
            );
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
        screen.fill_strip();
        // Away and back: the second shelf brings drawings of its own the first time, and
        // coming back to the first one must not send its drawings again.
        assert!(
            screen.press_text(Category::Women.label()),
            "the category strip cannot be pressed"
        );
        assert!(
            screen.press_text(Category::New.label()),
            "the category strip cannot be pressed back"
        );
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
        compose_rust::window::reset_window_size();
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
        compose_rust::window::reset_window_size();
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
            screen.press_role(IconRole::Back),
            "the garment's page has no way back"
        );
        assert!(
            screen
                .latest_texts()
                .iter()
                .any(|text| text.starts_with("Let's find")),
            "going back did not bring the catalogue with it"
        );
        compose_rust::window::reset_window_size();
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
            compose_rust::window::reset_window_size();
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
                        safe_area_top: 0.0,
                        safe_area_leading: 0.0,
                        safe_area_trailing: 0.0,
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
            compose_rust::window::reset_window_size();
            found
        }

        assert!(widths(1180.0).contains(&PAGE_MEASURE));
        assert!(!widths(420.0).contains(&PAGE_MEASURE));
    }

    /// The shop opens on the reference's first screen: the mark, the shop's name, a
    /// picture to the window's edges and a round dark arrow on the line where page and
    /// picture meet, with no bar along the bottom. The arrow is the way in.
    #[test]
    fn fr22_the_shop_opens_on_its_first_screen() {
        let mut screen = Screen::at_the_door();
        assert!(
            screen.live_texts().iter().any(|text| text == WELCOME_TITLE),
            "the first screen does not say what the shop is"
        );
        assert!(
            !screen
                .live_texts()
                .iter()
                .any(|text| text.starts_with("Let's find")),
            "the shop opened on the catalogue rather than its first screen"
        );
        assert!(
            !screen.shows_icon(&icon::HOME),
            "the first screen has the bar along the bottom, which the reference does not"
        );

        let picture = screen
            .mutations()
            .iter()
            .find_map(|mutation| match mutation {
                Mutation::RegisterAsset {
                    asset_id, bytes, ..
                } if *bytes == catalogue::WELCOME => Some(*asset_id),
                _ => None,
            })
            .expect("the first screen's picture was never registered");
        let picture = screen
            .node_drawing(picture)
            .expect("nothing draws the first screen's picture");

        let start = screen
            .node_with_role(IconRole::Forward)
            .expect("the first screen has no arrow");
        let face = screen.modifiers_of(start);
        for expected in [
            Modifier::Width(START_SIDE),
            Modifier::Height(START_SIDE),
            Modifier::Background(palette::DARK),
            Modifier::ShapeRole(ShapeRole::Full),
        ] {
            assert!(
                face.contains(&expected),
                "the arrow is not a round dark button: {face:?} lacks {expected:?}"
            );
        }
        assert_eq!(screen.colour_of(start), Some(palette::PAGE));

        // Laid over the picture rather than above it: the picture and the arrow are
        // layers of one box, and the picture starts half the arrow's height down it, so
        // the arrow sits on the picture's top edge.
        let picture_column = screen
            .parent_of(picture)
            .expect("the picture has no parent");
        let arrow_row = screen.parent_of(start).expect("the arrow has no parent");
        assert_eq!(
            screen.parent_of(picture_column),
            screen.parent_of(arrow_row),
            "the arrow and the picture are not layers of one box, so the arrow cannot sit \
             over the picture's edge"
        );
        assert!(
            screen.children_of(picture_column).iter().any(|child| screen
                .modifiers_of(*child)
                .contains(&Modifier::Height(START_SIDE / 2.0))),
            "the picture does not start half the arrow's height down, so the arrow is not \
             centred on its edge"
        );

        assert!(screen.press_role(IconRole::Forward));
        assert!(
            screen
                .live_texts()
                .iter()
                .any(|text| text.starts_with("Let's find")),
            "the arrow did not open the catalogue"
        );
        assert!(
            screen.shows_icon(&icon::HOME),
            "the catalogue has no bar along the bottom"
        );
        compose_rust::window::reset_window_size();
    }

    /// The sizes are equal circles with gaps between them, not the row shared five ways.
    #[test]
    fn fr22_the_sizes_are_equal_circles() {
        let mut screen = Screen::new();
        screen.open(under(Category::New)[0].name);
        for option in SIZES {
            let label = screen
                .node_saying(option)
                .unwrap_or_else(|| panic!("size {option} is not on the page"));
            let chip = screen
                .parent_of(label)
                .expect("a size has no chip round it");
            let face = screen.modifiers_of(chip);
            for expected in [
                Modifier::Width(CHIP_SIDE),
                Modifier::Height(CHIP_SIDE),
                Modifier::ShapeRole(ShapeRole::Full),
            ] {
                assert!(
                    face.contains(&expected),
                    "size {option} is not a circle: {face:?} lacks {expected:?}"
                );
            }
            assert!(
                !face
                    .iter()
                    .any(|modifier| matches!(modifier, Modifier::Weight(_))),
                "size {option} stretches to share the row, which makes it a pill"
            );
            let chosen = option == SIZES[1];
            assert_eq!(
                face.contains(&Modifier::Background(palette::ACCENT)),
                chosen,
                "only the chosen size is in the accent"
            );
        }

        assert!(screen.press_text("L"), "a size cannot be chosen");
        let large = screen.node_saying("L").expect("L is gone");
        let chip = screen.parent_of(large).expect("L has no chip");
        let fill =
            screen
                .modifiers_of(chip)
                .into_iter()
                .rev()
                .find_map(|modifier| match modifier {
                    Modifier::Background(paint) => Some(paint),
                    _ => None,
                });
        assert_eq!(
            fill,
            Some(palette::ACCENT),
            "choosing L did not move the accent to it"
        );
        compose_rust::window::reset_window_size();
    }

    /// A garment's page writes the amount with its cents, "$95.00", as the reference does,
    /// while the shelf it was opened from keeps "$95".
    #[test]
    fn fr22_the_garment_page_writes_its_price_with_cents() {
        let mut screen = Screen::new();
        let first = under(Category::New)[0];
        assert!(
            screen.live_texts().contains(&price(first.cents)),
            "the shelf does not write the short price"
        );
        screen.open(first.name);
        assert!(
            screen.live_texts().contains(&price_with_cents(first.cents)),
            "the garment's page does not write {} with its cents",
            first.name
        );
        compose_rust::window::reset_window_size();
    }

    /// The panel on a garment's page slides up over the picture: its two top corners are
    /// round and its bottom ones square, it carries a grabber, and it starts above the
    /// picture's bottom edge in a layer of the same box rather than stacked under it.
    #[test]
    fn fr22_the_panel_overlaps_the_picture() {
        let mut screen = Screen::new();
        screen.open(under(Category::New)[0].name);
        let live = screen.live();
        let with = |wanted: &Modifier| -> Vec<u32> {
            live.iter()
                .filter_map(|mutation| match mutation {
                    Mutation::SetModifier {
                        node_id, modifier, ..
                    } if modifier == wanted => Some(*node_id),
                    _ => None,
                })
                .collect()
        };
        let panel = *with(&Modifier::Shape {
            top_start: PANEL_CORNER,
            top_end: PANEL_CORNER,
            bottom_end: 0.0,
            bottom_start: 0.0,
        })
        .first()
        .expect("no panel with round top corners and square bottom ones");
        let hero = *with(&Modifier::Height(HERO_HEIGHT))
            .first()
            .expect("the garment's picture is not on the page");
        let layer = screen.parent_of(panel).expect("the panel has no parent");
        assert_eq!(
            screen.parent_of(layer),
            screen.parent_of(hero),
            "the panel is not a layer over the picture"
        );
        assert!(
            screen.children_of(layer).iter().any(|child| screen
                .modifiers_of(*child)
                .contains(&Modifier::Height(HERO_HEIGHT - PANEL_OVERLAP))),
            "the panel does not start above the picture's bottom edge"
        );
        assert!(PANEL_OVERLAP > 0.0 && PANEL_OVERLAP < HERO_HEIGHT);

        let grabbers = with(&Modifier::Width(GRABBER.0));
        assert!(
            grabbers.iter().any(|node| {
                let mut at = *node;
                while let Some(parent) = screen.parent_of(at) {
                    if parent == panel {
                        return true;
                    }
                    at = parent;
                }
                false
            }),
            "the panel has no grabber"
        );
        compose_rust::window::reset_window_size();
    }

    /// A garment's page has no bar along the bottom, as the reference's has none, and the
    /// bar comes back with the catalogue.
    #[test]
    fn fr22_a_garment_page_fills_the_window_without_the_bar() {
        let mut screen = Screen::new();
        assert!(screen.shows_icon(&icon::HOME));
        screen.open(under(Category::New)[0].name);
        assert!(
            !screen.shows_icon(&icon::HOME),
            "the garment's page still has the bar along the bottom"
        );
        assert!(screen.press_role(IconRole::Back));
        assert!(
            screen.shows_icon(&icon::HOME),
            "the bar did not come back with the catalogue"
        );
        compose_rust::window::reset_window_size();
    }

    /// A star the rating does not reach is the same star in a light grey, not an outline.
    #[test]
    fn fr22_a_missing_star_is_grey() {
        let mut screen = Screen::new();
        let second = under(Category::New)[1];
        let (lit, unlit) = stars(second.rating);
        assert!(
            !unlit.is_empty(),
            "{} needs a rating under five",
            second.name
        );
        screen.open(second.name);
        let faint = screen
            .node_saying(&unlit)
            .expect("the stars the rating does not reach are not drawn");
        assert_eq!(screen.colour_of(faint), Some(palette::FAINT));
        let full = screen
            .node_saying(&lit)
            .expect("the stars the rating reaches are not drawn");
        assert_eq!(screen.colour_of(full), Some(palette::INK));
        compose_rust::window::reset_window_size();
    }

    /// The chosen category is heavier as well as darker, and the others are regular, as
    /// the reference sets them. Plain words, not buttons with the design system's padding.
    #[test]
    fn fr22_the_chosen_category_is_the_heavier_word() {
        let mut screen = Screen::new();
        screen.fill_strip();
        for choice in Category::STRIP {
            let node = screen
                .node_saying(choice.label())
                .unwrap_or_else(|| panic!("{} is not in the strip", choice.label()));
            let chosen = choice == Category::New;
            assert_eq!(
                screen.integer_prop(node, PropertyKind::FontWeight),
                Some(i64::from(if chosen { CHOSEN_WEIGHT } else { PLAIN_WEIGHT })),
                "{} has the wrong weight",
                choice.label()
            );
            assert_eq!(
                screen.colour_of(node),
                Some(if chosen { palette::INK } else { palette::MUTED })
            );
        }
        compose_rust::window::reset_window_size();
    }

    /// The first slide's figures stand on the card with their heads above its top edge:
    /// the card is shorter than the carousel by the rise and sits at its bottom, and the
    /// scene is drawn over both rather than inside the card.
    #[test]
    fn fr22_the_carousel_figures_rise_above_the_card() {
        let screen = Screen::new();
        let scene = screen
            .mutations()
            .iter()
            .find_map(|mutation| match mutation {
                Mutation::RegisterAsset {
                    asset_id, bytes, ..
                } if *bytes == catalogue::HERO => Some(*asset_id),
                _ => None,
            })
            .expect("the carousel's scene was never registered");
        let scene = screen.node_drawing(scene).expect("nothing draws the scene");
        let carousel = screen.parent_of(scene).expect("the scene has no parent");
        assert!(
            screen
                .modifiers_of(carousel)
                .contains(&Modifier::Height(CAROUSEL_HEIGHT + CAROUSEL_RISE)),
            "the scene is not drawn over the rise above the card"
        );
        assert!(
            screen.children_of(carousel).iter().any(|child| {
                let face = screen.modifiers_of(*child);
                face.contains(&Modifier::Height(CAROUSEL_HEIGHT))
                    && face.contains(&Modifier::Background(palette::TILE))
            }),
            "the grey card is not under the scene"
        );
        compose_rust::window::reset_window_size();
    }

    /// The first screen, in the design system it ships.
    #[test]
    fn fr14_the_first_screen_is_recorded() {
        sample_frames::record_as(
            "StoreOnboarding",
            &sample_frames::as_designed(THEME, &sample_frames::APPLE),
            app,
            |_| {},
        );
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
                assert!(
                    screen.press_icon(IconRole::Forward),
                    "the first screen has no way into the shop"
                );
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
                assert!(
                    screen.press_icon(IconRole::Forward),
                    "the first screen has no way into the shop"
                );
                screen.fill_lists(6);
                assert!(
                    screen.press_beside(under(Category::New)[0].name),
                    "no garment on the catalogue opens"
                );
            },
        );
    }
}
