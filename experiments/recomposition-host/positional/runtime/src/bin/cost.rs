//! What re-running a scope costs in this runtime, against what it costs in `dioxus-core`.
//!
//! The shape is the one the breakdown measured: one piece of state changes, a parent's
//! children all read it, and the cost is taken as the slope against the number of children
//! so that re-running the parent once is not counted as part of the per-child price.
//!
//! This is not a like-for-like comparison and cannot be. There is no wire encoding here, no
//! widget schema, no props, and no hooks beyond `remember`. What it can say is whether the
//! floor of a slot table is near `dioxus-core`'s 1904ns or an order below it, and a floor is
//! the useful thing to know before deciding whether the ceiling is worth building.

use positional::{compose, composable, emit, remember, reset};
use std::hint::black_box;
use std::time::Instant;

/// The leaf: its own group, one slot, one comparison, one possible change.
#[composable]
fn leaf(value: u32) {
    emit(value.to_string());
}

/// The leaf with a slot of its own to remember, which is the nearest thing here to a
/// component holding state.
#[composable]
fn stateful_leaf(value: u32) {
    let seen = remember(|| 0_u32);
    *seen.borrow_mut() += 1;
    emit(value.to_string());
}

#[composable]
fn parent(width: usize, tick: u32) {
    for index in 0..width {
        leaf(tick.wrapping_add(index as u32));
    }
}

#[composable]
fn stateful_parent(width: usize, tick: u32) {
    for index in 0..width {
        stateful_leaf(tick.wrapping_add(index as u32));
    }
}

fn percentiles_ns(iterations: usize, mut operation: impl FnMut()) -> (u128, u128) {
    let mut samples = Vec::with_capacity(iterations);
    for _ in 0..iterations {
        let started = Instant::now();
        operation();
        samples.push(started.elapsed().as_nanos());
    }
    samples.sort_unstable();
    (
        samples[iterations / 2],
        samples[(iterations * 99).div_ceil(100) - 1],
    )
}

fn slope(points: &[(usize, u128)]) -> f64 {
    let n = points.len() as f64;
    let mean_x = points.iter().map(|point| point.0 as f64).sum::<f64>() / n;
    let mean_y = points.iter().map(|point| point.1 as f64).sum::<f64>() / n;
    let covariance: f64 = points
        .iter()
        .map(|point| (point.0 as f64 - mean_x) * (point.1 as f64 - mean_y))
        .sum();
    let variance: f64 = points
        .iter()
        .map(|point| (point.0 as f64 - mean_x).powi(2))
        .sum();
    covariance / variance
}

/// One width, recomposed with the value moving on every pass or standing still.
fn sweep(
    component: fn(usize, u32),
    width: usize,
    iterations: usize,
    changing: bool,
) -> (u128, u128) {
    reset();
    let mut tick = 0_u32;
    for _ in 0..200 {
        compose(|| component(width, tick));
        if changing {
            tick = tick.wrapping_add(1);
        }
    }
    percentiles_ns(iterations, || {
        if changing {
            tick = tick.wrapping_add(1);
        }
        black_box(compose(|| component(width, tick)).len());
    })
}

fn main() {
    let iterations: usize = std::env::args()
        .nth(1)
        .and_then(|argument| argument.parse().ok())
        .unwrap_or(20_000);
    let widths = [1_usize, 5, 17, 33, 65, 129];

    for (label, component) in [
        ("leaf with no state of its own", parent as fn(usize, u32)),
        ("leaf remembering one slot", stateful_parent as fn(usize, u32)),
    ] {
        let changed: Vec<(usize, u128)> = widths
            .iter()
            .map(|&width| (width, sweep(component, width, iterations, true).0))
            .collect();
        let unchanged: Vec<(usize, u128)> = widths
            .iter()
            .map(|&width| (width, sweep(component, width, iterations, false).0))
            .collect();

        let changed_slope = slope(&changed);
        let unchanged_slope = slope(&unchanged);

        println!("{label}");
        println!("  slots   changed p50   unchanged p50");
        for ((width, median), (_, median_same)) in changed.iter().zip(&unchanged) {
            println!("  {width:>5}   {median:>11}   {median_same:>13}");
        }
        println!("  per changed slot     {changed_slope:>8.0} ns");
        println!("  per unchanged slot   {unchanged_slope:>8.0} ns");
        println!(
            "  emitting the change  {:>8.0} ns",
            changed_slope - unchanged_slope
        );
        println!();
    }
}
