//! Deterministic PNG writing (with `tEXt` metadata chunks) and pixel comparison.

use std::fs::{self, File};
use std::io::BufWriter;
use std::path::Path;

/// Result of comparing two same-sized RGBA images pixel by pixel.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct CompareResult {
    pub width: u32,
    pub height: u32,
    pub diff_pixels: u64,
    pub total_pixels: u64,
}

impl CompareResult {
    /// Fraction of pixels that differ in `[0.0, 1.0]`.
    pub fn diff_ratio(&self) -> f64 {
        if self.total_pixels == 0 {
            0.0
        } else {
            (self.diff_pixels as f64) / (self.total_pixels as f64)
        }
    }
}

/// Decoded RGBA 8888 image.
#[derive(Debug, Clone)]
pub struct RgbaImage {
    pub width: u32,
    pub height: u32,
    pub pixels: Vec<u8>,
}

/// Write an RGBA 8888 PNG deterministically with sorted `tEXt` metadata chunks (no timestamp).
pub fn write_png(
    path: &Path,
    width: u32,
    height: u32,
    rgba: &[u8],
    text_chunks: &[(&str, &str)],
) -> Result<(), String> {
    if rgba.len() != (width as usize) * (height as usize) * 4 {
        return Err(format!(
            "RGBA buffer size mismatch: expected {}, got {}",
            (width as usize) * (height as usize) * 4,
            rgba.len()
        ));
    }
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)
            .map_err(|e| format!("failed to create {}: {e}", parent.display()))?;
    }
    let file =
        File::create(path).map_err(|e| format!("failed to create {}: {e}", path.display()))?;
    let mut encoder = png::Encoder::new(BufWriter::new(file), width, height);
    encoder.set_color(png::ColorType::Rgba);
    encoder.set_depth(png::BitDepth::Eight);
    encoder.set_compression(png::Compression::Default);
    encoder.set_filter(png::FilterType::Sub);
    for (k, v) in text_chunks {
        encoder
            .add_text_chunk((*k).to_string(), (*v).to_string())
            .map_err(|e| format!("failed to add PNG text chunk {k}: {e}"))?;
    }
    let mut writer = encoder
        .write_header()
        .map_err(|e| format!("failed to write PNG header: {e}"))?;
    writer
        .write_image_data(rgba)
        .map_err(|e| format!("failed to write PNG data: {e}"))?;
    writer
        .finish()
        .map_err(|e| format!("failed to finish PNG: {e}"))?;
    Ok(())
}

/// Read a PNG file into RGBA 8888 bytes.
pub fn read_png_rgba(path: &Path) -> Result<RgbaImage, String> {
    let file = File::open(path).map_err(|e| format!("failed to open {}: {e}", path.display()))?;
    let mut decoder = png::Decoder::new(file);
    decoder.set_transformations(png::Transformations::ALPHA | png::Transformations::EXPAND);
    let mut reader = decoder
        .read_info()
        .map_err(|e| format!("failed to decode {}: {e}", path.display()))?;
    let mut buf = vec![0u8; reader.output_buffer_size()];
    let info = reader
        .next_frame(&mut buf)
        .map_err(|e| format!("failed to read frame {}: {e}", path.display()))?;
    buf.truncate(info.buffer_size());

    let pixels = match info.color_type {
        png::ColorType::Rgba => buf,
        png::ColorType::Rgb => {
            let mut rgba = Vec::with_capacity((info.width * info.height * 4) as usize);
            for chunk in buf.chunks_exact(3) {
                rgba.extend_from_slice(&[chunk[0], chunk[1], chunk[2], 255]);
            }
            rgba
        }
        other => {
            return Err(format!(
                "unsupported PNG colour type {other:?} in {}",
                path.display()
            ));
        }
    };
    Ok(RgbaImage {
        width: info.width,
        height: info.height,
        pixels,
    })
}

/// Compare two PNGs; write `<side_path>` (left: actual, right: expected) and `<diff_path>`.
///
/// Fails with both dimensions if sizes differ (never scales).
pub fn compare_pngs(
    actual_path: &Path,
    expected_path: &Path,
    side_path: Option<&Path>,
    diff_path: Option<&Path>,
) -> Result<CompareResult, String> {
    let actual = read_png_rgba(actual_path)?;
    let expected = read_png_rgba(expected_path)?;
    if actual.width != expected.width || actual.height != expected.height {
        return Err(format!(
            "image dimensions differ: actual is {}x{}, expected is {}x{}",
            actual.width, actual.height, expected.width, expected.height
        ));
    }

    let w = actual.width;
    let h = actual.height;
    let total_pixels = u64::from(w) * u64::from(h);
    let mut diff_pixels = 0u64;
    let mut diff_rgba = vec![0u8; (total_pixels * 4) as usize];

    for i in 0..(total_pixels as usize) {
        let base = i * 4;
        let a = &actual.pixels[base..base + 4];
        let e = &expected.pixels[base..base + 4];
        if a != e {
            diff_pixels += 1;
            // Highlight differing pixel in bright magenta.
            diff_rgba[base] = 255;
            diff_rgba[base + 1] = 0;
            diff_rgba[base + 2] = 170;
            diff_rgba[base + 3] = 255;
        } else {
            // Dim matching pixel to 35% so differences pop clearly.
            diff_rgba[base] = (u16::from(a[0]) * 35 / 100) as u8;
            diff_rgba[base + 1] = (u16::from(a[1]) * 35 / 100) as u8;
            diff_rgba[base + 2] = (u16::from(a[2]) * 35 / 100) as u8;
            diff_rgba[base + 3] = 255;
        }
    }

    if let Some(sp) = side_path {
        let side_w = w * 2;
        let mut side_rgba = vec![0u8; (side_w as usize) * (h as usize) * 4];
        let row_bytes = (w as usize) * 4;
        let side_row_bytes = (side_w as usize) * 4;
        for y in 0..(h as usize) {
            let src_off = y * row_bytes;
            let dst_off = y * side_row_bytes;
            side_rgba[dst_off..dst_off + row_bytes]
                .copy_from_slice(&actual.pixels[src_off..src_off + row_bytes]);
            side_rgba[dst_off + row_bytes..dst_off + side_row_bytes]
                .copy_from_slice(&expected.pixels[src_off..src_off + row_bytes]);
        }
        write_png(sp, side_w, h, &side_rgba, &[])?;
    }

    if let Some(dp) = diff_path {
        write_png(dp, w, h, &diff_rgba, &[])?;
    }

    Ok(CompareResult {
        width: w,
        height: h,
        diff_pixels,
        total_pixels,
    })
}
