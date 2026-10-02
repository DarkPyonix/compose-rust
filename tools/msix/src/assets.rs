//! The images a package has to carry, drawn from the application's one icon.
//!
//! The manifest names four: the 44 pixel logo the taskbar and Start list use, the 150
//! pixel medium tile, the 310 by 150 wide tile, and the 50 pixel Store logo. Each is
//! written at its exact size under the unqualified name the manifest uses, so the package
//! needs no resource index to find them.
//!
//! An icon smaller than the largest image is scaled up and the packager says so: the
//! Store accepts it, but a tile drawn from 64 pixels looks like one.

use std::path::Path;

use crate::Error;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Rgba {
    pub width: u32,
    pub height: u32,
    /// Straight (not premultiplied) RGBA, row major.
    pub pixels: Vec<u8>,
}

/// One image the manifest refers to.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct AssetSpec {
    pub file: &'static str,
    pub width: u32,
    pub height: u32,
}

pub const STORE_LOGO: AssetSpec = AssetSpec {
    file: "StoreLogo.png",
    width: 50,
    height: 50,
};
pub const SQUARE_44: AssetSpec = AssetSpec {
    file: "Square44x44Logo.png",
    width: 44,
    height: 44,
};
pub const SQUARE_150: AssetSpec = AssetSpec {
    file: "Square150x150Logo.png",
    width: 150,
    height: 150,
};
pub const WIDE_310: AssetSpec = AssetSpec {
    file: "Wide310x150Logo.png",
    width: 310,
    height: 150,
};

pub const ALL: [AssetSpec; 4] = [STORE_LOGO, SQUARE_44, SQUARE_150, WIDE_310];

/// The directory inside the package the images live in.
pub const DIR: &str = "Assets";

impl Rgba {
    pub fn decode(bytes: &[u8]) -> Result<Self, Error> {
        let mut decoder = png::Decoder::new(bytes);
        decoder.set_transformations(png::Transformations::normalize_to_color8());
        let mut reader = decoder
            .read_info()
            .map_err(|e| Error::new(format!("icon is not a readable PNG: {e}")))?;
        let mut buf = vec![0; reader.output_buffer_size()];
        let info = reader
            .next_frame(&mut buf)
            .map_err(|e| Error::new(format!("icon is not a readable PNG: {e}")))?;
        let (w, h) = (info.width, info.height);
        let src = &buf[..info.buffer_size()];
        let count = (w * h) as usize;
        let mut pixels = Vec::with_capacity(count * 4);
        match info.color_type {
            png::ColorType::Rgba => pixels.extend_from_slice(src),
            png::ColorType::Rgb => {
                for p in src.chunks_exact(3) {
                    pixels.extend_from_slice(&[p[0], p[1], p[2], 255]);
                }
            }
            png::ColorType::GrayscaleAlpha => {
                for p in src.chunks_exact(2) {
                    pixels.extend_from_slice(&[p[0], p[0], p[0], p[1]]);
                }
            }
            png::ColorType::Grayscale => {
                for &g in src {
                    pixels.extend_from_slice(&[g, g, g, 255]);
                }
            }
            png::ColorType::Indexed => {
                return Err(Error::new("icon PNG kept its palette after expansion"));
            }
        }
        if pixels.len() != count * 4 {
            return Err(Error::new("icon PNG decoded to the wrong number of pixels"));
        }
        Ok(Rgba {
            width: w,
            height: h,
            pixels,
        })
    }

    pub fn encode(&self) -> Result<Vec<u8>, Error> {
        let fail = |e: png::EncodingError| Error::new(format!("cannot encode PNG: {e}"));
        let mut out = Vec::new();
        let mut encoder = png::Encoder::new(&mut out, self.width, self.height);
        encoder.set_color(png::ColorType::Rgba);
        encoder.set_depth(png::BitDepth::Eight);
        let mut writer = encoder.write_header().map_err(fail)?;
        writer.write_image_data(&self.pixels).map_err(fail)?;
        writer.finish().map_err(fail)?;
        Ok(out)
    }

    pub fn transparent(width: u32, height: u32) -> Self {
        Rgba {
            width,
            height,
            pixels: vec![0; (width * height * 4) as usize],
        }
    }

    pub fn pixel(&self, x: u32, y: u32) -> [u8; 4] {
        let i = ((y * self.width + x) * 4) as usize;
        [
            self.pixels[i],
            self.pixels[i + 1],
            self.pixels[i + 2],
            self.pixels[i + 3],
        ]
    }

    /// Scales to `width` by `height` with a triangle filter whose support widens when
    /// shrinking, so a large icon is averaged rather than sampled. Colour is weighted by
    /// alpha, which keeps the transparent surround from bleeding a dark fringe into the
    /// edge of the mark.
    pub fn resize(&self, width: u32, height: u32) -> Self {
        let horizontal = weights(self.width, width);
        let vertical = weights(self.height, height);

        // Premultiplied floats for the intermediate.
        let src: Vec<[f32; 4]> = self
            .pixels
            .chunks_exact(4)
            .map(|p| {
                let a = p[3] as f32 / 255.0;
                [
                    p[0] as f32 * a,
                    p[1] as f32 * a,
                    p[2] as f32 * a,
                    p[3] as f32,
                ]
            })
            .collect();

        let mut rows = vec![[0f32; 4]; (width * self.height) as usize];
        for y in 0..self.height {
            for (x, taps) in horizontal.iter().enumerate() {
                let mut acc = [0f32; 4];
                for &(sx, w) in taps {
                    let p = src[(y * self.width + sx) as usize];
                    for (a, v) in acc.iter_mut().zip(p) {
                        *a += v * w;
                    }
                }
                rows[(y * width) as usize + x] = acc;
            }
        }

        let mut out = Rgba::transparent(width, height);
        for (y, taps) in vertical.iter().enumerate() {
            for x in 0..width {
                let mut acc = [0f32; 4];
                for &(sy, w) in taps {
                    let p = rows[(sy * width + x) as usize];
                    for (a, v) in acc.iter_mut().zip(p) {
                        *a += v * w;
                    }
                }
                let a = acc[3].clamp(0.0, 255.0);
                let i = ((y as u32 * width + x) * 4) as usize;
                if a > 0.0 {
                    let scale = 255.0 / a;
                    for (dst, v) in out.pixels[i..i + 3].iter_mut().zip(acc) {
                        *dst = (v * scale).round().clamp(0.0, 255.0) as u8;
                    }
                }
                out.pixels[i + 3] = a.round() as u8;
            }
        }
        out
    }

    /// Copies `other` onto this image with its top left corner at (`x`, `y`).
    pub fn blit(&mut self, other: &Rgba, x: u32, y: u32) {
        for oy in 0..other.height {
            for ox in 0..other.width {
                let (tx, ty) = (x + ox, y + oy);
                if tx >= self.width || ty >= self.height {
                    continue;
                }
                let s = ((oy * other.width + ox) * 4) as usize;
                let d = ((ty * self.width + tx) * 4) as usize;
                self.pixels[d..d + 4].copy_from_slice(&other.pixels[s..s + 4]);
            }
        }
    }
}

/// For each destination index, the source indices and normalised weights that make it.
fn weights(src: u32, dst: u32) -> Vec<Vec<(u32, f32)>> {
    let scale = src as f32 / dst as f32;
    let support = scale.max(1.0);
    (0..dst)
        .map(|d| {
            let centre = (d as f32 + 0.5) * scale - 0.5;
            let lo = (centre - support).floor().max(0.0) as u32;
            let hi = ((centre + support).ceil() as u32).min(src - 1);
            let mut taps: Vec<(u32, f32)> = (lo..=hi)
                .map(|s| {
                    let w = (1.0 - (s as f32 - centre).abs() / support).max(0.0);
                    (s, w)
                })
                .filter(|&(_, w)| w > 0.0)
                .collect();
            if taps.is_empty() {
                taps.push((centre.round().clamp(0.0, (src - 1) as f32) as u32, 1.0));
            }
            let total: f32 = taps.iter().map(|&(_, w)| w).sum();
            for t in &mut taps {
                t.1 /= total;
            }
            taps
        })
        .collect()
}

/// Draws one asset from the icon. Square assets are the icon at that size. The wide tile
/// is the icon at the tile's height, centred on a transparent field, which is how Windows
/// draws a tile that has no wide artwork of its own.
pub fn render(icon: &Rgba, spec: AssetSpec) -> Rgba {
    if spec.width == spec.height {
        return icon.resize(spec.width, spec.height);
    }
    let side = spec.width.min(spec.height);
    let mark = icon.resize(side, side);
    let mut canvas = Rgba::transparent(spec.width, spec.height);
    canvas.blit(&mark, (spec.width - side) / 2, (spec.height - side) / 2);
    canvas
}

/// The largest side any asset needs. An icon smaller than this is being enlarged.
pub fn largest_side() -> u32 {
    ALL.iter().map(|s| s.width.max(s.height)).max().unwrap_or(0)
}

/// Writes every asset into `<package_root>/Assets`. Returns a warning when the icon had
/// to be enlarged.
pub fn write_all(icon_png: &[u8], package_root: &Path) -> Result<Option<String>, Error> {
    let icon = Rgba::decode(icon_png)?;
    let dir = package_root.join(DIR);
    std::fs::create_dir_all(&dir)
        .map_err(|e| Error::new(format!("cannot create {}: {e}", dir.display())))?;
    for spec in ALL {
        let bytes = render(&icon, spec).encode()?;
        let path = dir.join(spec.file);
        std::fs::write(&path, bytes)
            .map_err(|e| Error::new(format!("cannot write {}: {e}", path.display())))?;
    }
    let needed = 150; // the medium tile, the largest square the icon is drawn into
    let warning = (icon.width.min(icon.height) < needed).then(|| {
        format!(
            "the icon is {}x{} and is enlarged for the {needed}x{needed} tile; give the application a 256x256 icon for sharp tiles",
            icon.width, icon.height
        )
    });
    Ok(warning)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn solid(w: u32, h: u32, rgba: [u8; 4]) -> Rgba {
        Rgba {
            width: w,
            height: h,
            pixels: rgba
                .iter()
                .copied()
                .cycle()
                .take((w * h * 4) as usize)
                .collect(),
        }
    }

    #[test]
    fn fr34_every_asset_has_its_exact_size() {
        let icon = solid(64, 64, [200, 80, 40, 255]);
        for spec in ALL {
            let img = render(&icon, spec);
            assert_eq!(
                (img.width, img.height),
                (spec.width, spec.height),
                "{}",
                spec.file
            );
            let decoded = Rgba::decode(&img.encode().unwrap()).unwrap();
            assert_eq!((decoded.width, decoded.height), (spec.width, spec.height));
        }
    }

    #[test]
    fn fr34_resizing_keeps_a_solid_colour_solid() {
        let icon = solid(64, 64, [10, 120, 230, 255]);
        for (w, h) in [(44, 44), (150, 150), (50, 50), (3, 3), (256, 256)] {
            let r = icon.resize(w, h);
            for y in 0..h {
                for x in 0..w {
                    assert_eq!(r.pixel(x, y), [10, 120, 230, 255], "{w}x{h} at {x},{y}");
                }
            }
        }
    }

    #[test]
    fn fr34_transparent_surround_does_not_darken_the_edge() {
        // Left half opaque red, right half fully transparent black.
        let mut icon = solid(8, 8, [0, 0, 0, 0]);
        for y in 0..8 {
            for x in 0..4 {
                let i = ((y * 8 + x) * 4) as usize;
                icon.pixels[i..i + 4].copy_from_slice(&[255, 0, 0, 255]);
            }
        }
        let r = icon.resize(3, 3);
        let middle = r.pixel(1, 1);
        assert!(middle[3] > 0 && middle[3] < 255, "{middle:?}");
        assert_eq!(&middle[..3], &[255, 0, 0], "edge colour bled: {middle:?}");
    }

    #[test]
    fn fr34_wide_tile_centres_the_icon() {
        let icon = solid(16, 16, [0, 255, 0, 255]);
        let wide = render(&icon, WIDE_310);
        assert_eq!(wide.pixel(0, 75)[3], 0);
        assert_eq!(wide.pixel(309, 75)[3], 0);
        assert_eq!(wide.pixel(155, 75), [0, 255, 0, 255]);
    }

    #[test]
    fn fr34_small_icons_are_reported() {
        let dir =
            Path::new(env!("CARGO_MANIFEST_DIR")).join("../../samples/notepad/assets/icon.png");
        let bytes = std::fs::read(dir).unwrap();
        let icon = Rgba::decode(&bytes).unwrap();
        assert_eq!((icon.width, icon.height), (64, 64));
        assert!(largest_side() >= 150);
    }

    #[test]
    fn fr34_gray_and_rgb_pngs_decode() {
        for (color, channels) in [
            (png::ColorType::Rgb, 3usize),
            (png::ColorType::Grayscale, 1),
            (png::ColorType::GrayscaleAlpha, 2),
        ] {
            let mut out = Vec::new();
            {
                let mut e = png::Encoder::new(&mut out, 2, 2);
                e.set_color(color);
                e.set_depth(png::BitDepth::Eight);
                let mut w = e.write_header().unwrap();
                w.write_image_data(&vec![128u8; 4 * channels]).unwrap();
            }
            let img = Rgba::decode(&out).unwrap();
            assert_eq!(img.pixels.len(), 16, "{color:?}");
            assert_eq!(img.pixel(0, 0)[0], 128);
        }
    }
}
