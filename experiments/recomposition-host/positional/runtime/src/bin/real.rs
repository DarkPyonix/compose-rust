//! The breakdown's sweep, through the widget layer that writes real records.
//!
//! Same shape as the Dioxus measurement: one column, `width` texts each showing
//! `"{count}-{slot}"`, and a click that either moves `count` or leaves it. Same props, same
//! encoder, same envelope. The per-slot slope is directly comparable to the breakdown's.

use positional::composable;
use positional::widgets::{TextProps, column, compose_frame, reset_frame, text};
use std::hint::black_box;
use std::time::Instant;

#[composable]
fn list(width: usize, count: u64) {
    column(|| {
        for slot in 0..width {
            text(TextProps {
                text: format!("{count}-{slot}"),
                ..TextProps::default()
            });
        }
    });
}

fn median_ns(iterations: usize, mut operation: impl FnMut()) -> u128 {
    let mut samples = Vec::with_capacity(iterations);
    for _ in 0..iterations {
        let started = Instant::now();
        operation();
        samples.push(started.elapsed().as_nanos());
    }
    samples.sort_unstable();
    samples[iterations / 2]
}

fn slope(points: &[(usize, u128)]) -> f64 {
    let n = points.len() as f64;
    let mean_x = points.iter().map(|p| p.0 as f64).sum::<f64>() / n;
    let mean_y = points.iter().map(|p| p.1 as f64).sum::<f64>() / n;
    let cov: f64 = points.iter().map(|p| (p.0 as f64 - mean_x) * (p.1 as f64 - mean_y)).sum();
    let var: f64 = points.iter().map(|p| (p.0 as f64 - mean_x).powi(2)).sum();
    cov / var
}

fn sweep(width: usize, iterations: usize, changing: bool) -> u128 {
    positional::reset();
    reset_frame();
    let mut count = 0_u64;
    for _ in 0..200 {
        if changing {
            count += 1;
        }
        black_box(compose_frame(|| list(width, count)));
    }
    median_ns(iterations, || {
        if changing {
            count += 1;
        }
        black_box(compose_frame(|| list(width, count)));
    })
}

fn main() {
    let iterations: usize = std::env::args()
        .nth(1)
        .and_then(|a| a.parse().ok())
        .unwrap_or(20_000);
    let widths = [1_usize, 5, 17, 33, 65, 129];
    let changed: Vec<(usize, u128)> = widths.iter().map(|&w| (w, sweep(w, iterations, true))).collect();
    let unchanged: Vec<(usize, u128)> = widths.iter().map(|&w| (w, sweep(w, iterations, false))).collect();
    println!("  slots   changed p50   unchanged p50");
    for ((w, c), (_, u)) in changed.iter().zip(&unchanged) {
        println!("  {w:>5}   {c:>11}   {u:>13}");
    }
    println!("  per changed slot     {:>6.0} ns", slope(&changed));
    println!("  per unchanged slot   {:>6.0} ns", slope(&unchanged));
}
