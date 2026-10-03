//! What the shop sells, and what is in the bag.
//!
//! No prices in floating point. A price is a whole number of cents and is formatted once,
//! which is the only way a catalogue adds up to the same total twice.

/// The strip across the top of the catalogue.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Category {
    New,
    Women,
    Men,
    Kids,
    Sale,
}

impl Category {
    pub const STRIP: [Category; 5] = [
        Category::New,
        Category::Women,
        Category::Men,
        Category::Kids,
        Category::Sale,
    ];

    pub fn label(self) -> &'static str {
        match self {
            Category::New => "New Releases",
            Category::Women => "Women",
            Category::Men => "Men",
            Category::Kids => "Kids",
            Category::Sale => "Sale",
        }
    }
}

/// The garments, drawn. Vector rather than raster, because a flat garment is paths and a
/// path file is a few hundred bytes that stays sharp at any tile size.
///
/// These are original drawings made for this sample. The reference is photography, which
/// is the one thing that cannot be written into a repository: a photograph of a real
/// garment belongs to whoever took it. So the shop draws its stock instead, and what the
/// sample demonstrates, that a picture crosses the boundary once and is drawn by id
/// afterwards, is the same either way.
///
/// A picture carries its own colours, and so does everything round it: this shop is one
/// reference picture rather than a screen that follows the machine it is running on, so
/// the card behind the garment, the name under it and the price beside it are the
/// literals in `palette`.
static TEE: &[u8] = include_bytes!("../assets/tee.svg");
static TANK: &[u8] = include_bytes!("../assets/tank.svg");
static JACKET: &[u8] = include_bytes!("../assets/jacket.svg");
static SHORTS: &[u8] = include_bytes!("../assets/shorts.svg");
static JOGGER: &[u8] = include_bytes!("../assets/jogger.svg");
static HOODIE: &[u8] = include_bytes!("../assets/hoodie.svg");
static KIT: &[u8] = include_bytes!("../assets/kit.svg");

/// The banner at the top of the catalogue, which is the one picture that is a scene rather
/// than a garment.
pub static HERO: &[u8] = include_bytes!("../assets/hero.svg");

/// The carousel at the top of the catalogue, in the order it turns.
///
/// Its own list rather than whatever is on the shelf below it. The shelf changes with the
/// category and the carousel does not: the reference draws the season's scene first, the
/// garments it is about after it, and four dots under all of them whichever category is
/// being browsed.
///
/// A function rather than a table, because every entry here is the address of a `static`
/// and a `static` table of those is not something a constant can be built from.
pub fn featured() -> [&'static [u8]; FEATURED] {
    [HERO, TEE, JACKET, KIT]
}

/// How many slides the carousel turns through, which is how many dots sit under it.
pub const FEATURED: usize = 4;

/// One thing for sale.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct Product {
    pub id: u32,
    pub name: &'static str,
    pub support: &'static str,
    pub cents: u32,
    pub category: Category,
    /// Tenths of a star, so the rating adds up and rounds the same way every time.
    pub rating: u32,
    /// The drawing of the garment, as the bytes of an SVG. Registered once with `asset`
    /// and drawn by id after that, so a shelf of eight garments costs eight registrations
    /// on the first frame and nothing on any frame after it.
    pub picture: &'static [u8],
}

/// The sizes every garment here comes in.
pub const SIZES: [&str; 5] = ["XS", "S", "M", "L", "XL"];

pub const CATALOGUE: [Product; 8] = [
    Product {
        id: 1,
        name: "Swoosh T-Shirt",
        support: "Women's light support",
        cents: 9500,
        category: Category::New,
        picture: TEE,
        rating: 50,
    },
    Product {
        id: 2,
        name: "Pro Dri-Fit",
        support: "Men's tank top",
        cents: 7000,
        category: Category::New,
        picture: TANK,
        rating: 44,
    },
    Product {
        id: 3,
        name: "Windrunner",
        support: "Women's running jacket",
        cents: 12000,
        category: Category::Women,
        picture: JACKET,
        rating: 47,
    },
    Product {
        id: 4,
        name: "Tempo Short",
        support: "Women's 3 inch brief",
        cents: 4500,
        category: Category::Women,
        picture: SHORTS,
        rating: 41,
    },
    Product {
        id: 5,
        name: "Flex Jogger",
        support: "Men's tapered fit",
        cents: 8500,
        category: Category::Men,
        picture: JOGGER,
        rating: 46,
    },
    Product {
        id: 6,
        name: "Court Vision",
        support: "Men's training tee",
        cents: 5500,
        category: Category::Men,
        picture: TEE,
        rating: 39,
    },
    Product {
        id: 7,
        name: "Little Runner",
        support: "Kids' all-weather set",
        cents: 6000,
        category: Category::Kids,
        picture: KIT,
        rating: 48,
    },
    Product {
        id: 8,
        name: "Legacy Hoodie",
        support: "Last season, half price",
        cents: 4000,
        category: Category::Sale,
        picture: HOODIE,
        rating: 43,
    },
];

pub fn product(id: u32) -> Option<&'static Product> {
    CATALOGUE.iter().find(|found| found.id == id)
}

/// What the catalogue shows under one tab.
pub fn under(category: Category) -> Vec<&'static Product> {
    CATALOGUE
        .iter()
        .filter(|product| product.category == category)
        .collect()
}

/// A price, from cents. Whole amounts drop the cents, which is how a shelf label is
/// written and how the reference writes it.
pub fn price(cents: u32) -> String {
    if cents % 100 == 0 {
        format!("${}", thousands(cents / 100))
    } else {
        format!("${}.{:02}", thousands(cents / 100), cents % 100)
    }
}

/// Groups of three from the right. A bag of six jackets is a four figure total, and a four
/// figure total without a separator is a number people misread.
fn thousands(value: u32) -> String {
    let digits = value.to_string();
    let mut out = String::with_capacity(digits.len() + digits.len() / 3);
    for (position, digit) in digits.chars().enumerate() {
        if position > 0 && (digits.len() - position) % 3 == 0 {
            out.push(',');
        }
        out.push(digit);
    }
    out
}

/// Five glyphs, filled up to the rating and hollow after it.
///
/// A row of five icons is what the reference draws, and the closed set of icon meanings
/// has no star in it: a meaning is drawn by every design system, and a star rating is not
/// a meaning any of them owns. Characters are what is left, and they are sized by the type
/// ladder like any other text, so they at least follow the design system's scale.
pub fn stars(rating: u32) -> String {
    let filled = (rating + 5) / 10;
    let mut out = String::with_capacity(5 * 3);
    for step in 0..5 {
        out.push(if step < filled {
            '\u{2605}'
        } else {
            '\u{2606}'
        });
    }
    out
}

/// The rating as it is written beside the stars: one decimal, from tenths.
///
/// Tenths rather than a float, so "4.7" is a number the catalogue stores and not the
/// nearest binary fraction to it printed back.
pub fn rating_text(rating: u32) -> String {
    format!("{}.{}", rating / 10, rating % 10)
}

/// One line of the bag.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct BagLine {
    pub product: u32,
    pub size: &'static str,
    pub quantity: u32,
}

/// What the bag comes to.
pub fn total(lines: &[BagLine]) -> u32 {
    lines
        .iter()
        .filter_map(|line| product(line.product).map(|found| found.cents * line.quantity))
        .sum()
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Every slide the carousel turns to is a picture the shop actually holds, and the
    /// four of them are four different pictures. Two dots showing the same drawing reads
    /// as a carousel that is stuck.
    #[test]
    fn fr22_the_carousel_turns_through_four_different_pictures() {
        let slides = featured();
        assert_eq!(slides.len(), FEATURED);
        for (index, slide) in slides.iter().enumerate() {
            assert!(!slide.is_empty(), "slide {index} has no picture");
            for other in &slides[index + 1..] {
                assert_ne!(slide, other, "two slides are the same picture");
            }
        }
    }

    #[test]
    fn a_price_reads_the_way_a_shelf_label_does() {
        assert_eq!(price(9500), "$95");
        assert_eq!(price(9550), "$95.50");
        assert_eq!(price(119250), "$1,192.50");
        assert_eq!(price(0), "$0");
    }

    #[test]
    fn a_bag_totals_its_lines_and_their_quantities() {
        let lines = [
            BagLine {
                product: 1,
                size: "S",
                quantity: 2,
            },
            BagLine {
                product: 2,
                size: "M",
                quantity: 1,
            },
        ];
        assert_eq!(total(&lines), 9500 * 2 + 7000);
        assert_eq!(price(total(&lines)), "$260");
    }

    /// A line naming something the shop does not sell contributes nothing rather than
    /// panicking. A bag restored from an old save is exactly that case.
    #[test]
    fn a_line_for_a_product_that_is_gone_is_worth_nothing() {
        let lines = [BagLine {
            product: 999,
            size: "S",
            quantity: 3,
        }];
        assert_eq!(total(&lines), 0);
    }

    #[test]
    fn fr13_the_rating_is_written_from_tenths_rather_than_a_float() {
        assert_eq!(rating_text(50), "5.0");
        assert_eq!(rating_text(47), "4.7");
        assert_eq!(rating_text(0), "0.0");
    }

    #[test]
    fn fr13_the_rating_rounds_to_whole_stars() {
        assert_eq!(stars(50), "\u{2605}\u{2605}\u{2605}\u{2605}\u{2605}");
        assert_eq!(stars(44), "\u{2605}\u{2605}\u{2605}\u{2605}\u{2606}");
        assert_eq!(stars(0), "\u{2606}\u{2606}\u{2606}\u{2606}\u{2606}");
    }

    /// Every tab has something on its shelf. A tab that shows nothing reads as a shop
    /// that has run out rather than as a filter with no matches.
    #[test]
    fn every_category_has_something_on_its_shelf() {
        for category in Category::STRIP {
            assert!(
                !under(category).is_empty(),
                "{} is an empty shelf",
                category.label()
            );
        }
    }
}
