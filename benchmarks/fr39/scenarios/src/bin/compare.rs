//! Compares the two paths' runs, scenario by scenario, and records the result.
//!
//! ```text
//! compare --baseline RUN.json... --candidate RUN.json... [--record benches/baseline.json]
//!         [--load-average N] [--machine TEXT]
//! ```
//!
//! Every ratio is printed, not only the means. A scenario whose trees differ, or whose
//! candidate sent more records or bytes, is printed as invalid and fails the verdict.
//! `--record` writes the table, the run conditions and the paths of the raw runs into the
//! `fr39` entry of the given file, leaving the rest of it as it was.
//!
//! `--trees-only` checks that the two paths built the same trees and sent no more records,
//! and ignores the timings: it is how the ported samples are checked against the `rsx!`
//! ones after the same interactions, with one pass of each scenario.

use fr39_scenarios::{Comparison, ScenarioRun, compare, quantile, verdict};
use std::collections::BTreeMap;

fn main() {
    let mut baseline_files = Vec::new();
    let mut candidate_files = Vec::new();
    let mut record = None;
    let mut load_average = String::from("not recorded");
    let mut machine = String::from("not recorded");
    let mut current = "";
    let mut trees_only = false;
    for argument in std::env::args().skip(1) {
        if argument == "--trees-only" {
            trees_only = true;
            continue;
        }
        match argument.as_str() {
            "--baseline" | "--candidate" | "--record" | "--load-average" | "--machine" => {
                current = match argument.as_str() {
                    "--baseline" => "baseline",
                    "--candidate" => "candidate",
                    "--record" => "record",
                    "--load-average" => "load",
                    _ => "machine",
                };
            }
            value => match current {
                "baseline" => baseline_files.push(value.to_owned()),
                "candidate" => candidate_files.push(value.to_owned()),
                "record" => record = Some(value.to_owned()),
                "load" => load_average = value.to_owned(),
                "machine" => machine = value.to_owned(),
                _ => panic!("{value} follows no flag"),
            },
        }
    }

    let load = |files: &[String]| -> BTreeMap<String, ScenarioRun> {
        let mut runs = BTreeMap::new();
        for file in files {
            let text = std::fs::read_to_string(file).unwrap_or_else(|error| {
                panic!("{file} could not be read: {error}");
            });
            let list: Vec<ScenarioRun> = serde_json::from_str(&text)
                .unwrap_or_else(|error| panic!("{file} is not a run file: {error}"));
            for run in list {
                runs.insert(run.name.clone(), run);
            }
        }
        runs
    };
    let baseline = load(&baseline_files);
    let candidate = load(&candidate_files);

    let mut comparisons: Vec<Comparison> = Vec::new();
    for scenario in fr39_scenarios::scenarios() {
        match (baseline.get(scenario.name), candidate.get(scenario.name)) {
            (Some(left), Some(right)) => comparisons.push(compare(left, right)),
            (left, right) => {
                println!(
                    "{:<20} missing a run: baseline {}, candidate {}",
                    scenario.name,
                    if left.is_some() { "present" } else { "absent" },
                    if right.is_some() { "present" } else { "absent" },
                );
                comparisons.push(Comparison {
                    name: scenario.name.to_owned(),
                    valid: false,
                    reason: "a path has no run for this scenario".to_owned(),
                    baseline_p50_ns: 0,
                    baseline_p99_ns: 0,
                    baseline_max_ns: 0,
                    candidate_p50_ns: 0,
                    candidate_p99_ns: 0,
                    candidate_max_ns: 0,
                    p50_ratio: 0.0,
                    p99_ratio: 0.0,
                    baseline_mutations: 0,
                    candidate_mutations: 0,
                    baseline_bytes: 0,
                    candidate_bytes: 0,
                });
            }
        }
    }

    println!(
        "{:<20} {:>10} {:>10} {:>10} {:>10} {:>8} {:>8} {:>9} {:>9}  verdict",
        "scenario", "dx p50", "dx p99", "cr p50", "cr p99", "x p50", "x p99", "records", "bytes"
    );
    for c in &comparisons {
        println!(
            "{:<20} {:>10} {:>10} {:>10} {:>10} {:>8.2} {:>8.2} {:>4}/{:<4} {:>4}/{:<4}  {}",
            c.name,
            c.baseline_p50_ns,
            c.baseline_p99_ns,
            c.candidate_p50_ns,
            c.candidate_p99_ns,
            c.p50_ratio,
            c.p99_ratio,
            c.candidate_mutations,
            c.baseline_mutations,
            c.candidate_bytes,
            c.baseline_bytes,
            if c.valid {
                "ok".to_owned()
            } else {
                format!("INVALID: {}", c.reason)
            },
        );
    }
    if trees_only {
        let invalid: Vec<&Comparison> = comparisons.iter().filter(|c| !c.valid).collect();
        for c in &invalid {
            println!("{}: {}", c.name, c.reason);
        }
        if invalid.is_empty() {
            println!("every scenario built the same trees on both paths");
            return;
        }
        std::process::exit(1);
    }
    let (passed, g50, g99) = verdict(&comparisons);
    println!(
        "geometric mean of p50 ratios {g50:.2} (target 10), of p99 ratios {g99:.2} (target 5): {}",
        if passed { "met" } else { "not met" }
    );

    if let Some(path) = record {
        let text = std::fs::read_to_string(&path).unwrap_or_else(|_| "{}".to_owned());
        let mut document: serde_json::Value =
            serde_json::from_str(&text).unwrap_or_else(|_| serde_json::json!({}));
        let runs: Vec<serde_json::Value> = baseline
            .values()
            .chain(candidate.values())
            .map(|run| {
                serde_json::json!({
                    "scenario": run.name,
                    "path": run.path,
                    "iterations": run.iterations,
                    "warmup": run.warmup,
                    "p50_ns": quantile(&run.frame_ns, 0.50),
                    "p99_ns": quantile(&run.frame_ns, 0.99),
                    "max_ns": run.frame_ns.iter().copied().max().unwrap_or(0),
                    "host_p50_ns": quantile(&run.host_ns, 0.50),
                    "apply_p50_ns": quantile(&run.apply_ns, 0.50),
                    "mutations": run.mutations,
                    "bytes": run.bytes,
                })
            })
            .collect();
        document["fr39"] = serde_json::json!({
            "machine": machine,
            "load_average": load_average,
            "baseline_commit": "ab73fb45b416d1a143b1593cd03aee8af186c12a",
            "raw_runs": baseline_files.iter().chain(candidate_files.iter()).collect::<Vec<_>>(),
            "runs": runs,
            "comparisons": comparisons,
            "geometric_mean_p50_ratio": g50,
            "geometric_mean_p99_ratio": g99,
            "target_met": passed,
        });
        std::fs::write(
            &path,
            serde_json::to_string_pretty(&document).expect("the record serializes") + "\n",
        )
        .expect("the record could not be written");
    }
    if !passed {
        std::process::exit(1);
    }
}
