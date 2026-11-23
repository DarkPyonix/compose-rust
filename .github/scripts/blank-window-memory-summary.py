"""Turns the samples of a blank-window memory run into the table the job summary shows.

Usage: python -I blank-window-memory-summary.py <samples.json> [title]

<samples.json> is what blank-window-memory.sh (Linux) or blank-window-memory.ps1 (Windows)
wrote: the system, the metric the report is about, the columns to show, and every reading,
each with the launch it came from and how many seconds after that start it was taken.

Prints a markdown table on standard output: one row per reading time and column, with the
median over the launches, the lowest and highest, and how many launches gave a value. The
median is the number to quote; one start lands anywhere, and three is the least that has a
middle. Values are in MB (1 MB = 1024 kB).

Exits 1 when no sample has a value for the metric, because a report of nothing must not read
as a pass. A number, however high, fails nothing.
"""

import json
import sys


def median(values):
    ordered = sorted(values)
    n = len(ordered)
    if n == 0:
        return None
    mid = n // 2
    if n % 2:
        return ordered[mid]
    return (ordered[mid - 1] + ordered[mid]) / 2


def mb(kb):
    return "-" if kb is None else f"{kb / 1024:.1f}"


def summarize(report, title):
    samples = report.get("samples", [])
    metric = report["metric"]
    # Each column is [key, label] or {"key": ..., "label": ...}: the second is what
    # PowerShell's ConvertTo-Json writes without surprises.
    columns = [(c["key"], c["label"]) if isinstance(c, dict) else (c[0], c[1])
               for c in (report.get("columns") or [[metric, metric]])]
    readings = report.get("readings_s") or sorted({s["at_s"] for s in samples})
    launches = report.get("launches", len({s["launch"] for s in samples}))

    lines = [f"### {title}", ""]
    lines.append(f"- Application: `{report.get('application', 'unknown')}`, "
                 f"window {report.get('window', 'unknown')}")
    lines.append(f"- {launches} launches, read at "
                 + " and ".join(f"+{at} s" for at in readings)
                 + " after each start, then closed by its process id")
    methods = sorted({s["method"] for s in samples if s.get("method")})
    lines.append(f"- Method: {report.get('method', 'unknown')}"
                 + (f"; used: {', '.join(methods)}" if methods else ""))
    if len(methods) > 1:
        lines.append("- **The readings mix methods**, so the median mixes them too: "
                     "samples.json says which reading used which")
    for note in report.get("notes", []):
        lines.append(f"- {note}")
    lines += ["", "| Reading | Value | Median (MB) | Min (MB) | Max (MB) | Launches |",
              "|---|---|---|---|---|---|"]
    for at in readings:
        for key, label in columns:
            values = [s[key] for s in samples if s.get("at_s") == at and s.get(key) is not None]
            name = f"**{label}**" if key == metric else label
            med = median(values)
            if values:
                lines.append(f"| +{at} s | {name} | {'**' + mb(med) + '**' if key == metric else mb(med)} "
                             f"| {mb(min(values))} | {mb(max(values))} | {len(values)} of {launches} |")
            else:
                lines.append(f"| +{at} s | {name} | - | - | - | 0 of {launches} |")
    lines.append("")
    has_metric = any(s.get(metric) is not None for s in samples)
    return "\n".join(lines) + "\n", has_metric


def main(argv):
    if len(argv) not in (2, 3):
        print("usage: blank-window-memory-summary.py <samples.json> [title]", file=sys.stderr)
        return 2
    with open(argv[1], encoding="utf-8-sig") as f:
        report = json.load(f)
    title = argv[2] if len(argv) == 3 else f"Blank window memory ({report.get('os', 'unknown')})"
    text, has_metric = summarize(report, title)
    sys.stdout.write(text)
    if not has_metric:
        print(f"error: no reading of {report['metric']} in {argv[1]}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
