#!/usr/bin/env python3
"""Side-by-side comparison of labeled load-test sets in results/.

    ./compare.py                 # every label, oldest first
    ./compare.py P8 P32 P64      # these labels, in this order (first = reference)
    ./compare.py --md P8 P64     # Markdown tables, e.g. for the results log

Per scenario and stage: req/s (and change against the first label), p50 / p95 /
p99 latency, error %, and the average app and Redis CPU of that stage's hold
window. When a label ran the same scenario more than once, the latest run wins.
Stages with errors above 1% are marked: their req/s and latencies are not
real (e.g. connection errors after an OOM exit).
"""
import csv
import glob
import json
import os
import sys

RESULTS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "results")


def load_runs():
    runs = {}
    for path in sorted(glob.glob(os.path.join(RESULTS, "*.json"))):
        with open(path) as f:
            run = json.load(f)
        label = run.get("label")
        if not label:
            continue
        base = path[: -len(".json")]
        run["cpu"] = stage_cpu(base + "-stats.csv", run["stages"])
        run["env"] = base + "-env.txt"
        # sorted() puts later timestamps last, so the latest run wins
        runs[(label, run["scenario"])] = run
    return runs


def stage_cpu(stats_csv, stages):
    """Average app and Redis CPU % per stage, from the docker stats samples."""
    out = {}
    if not os.path.exists(stats_csv):
        return out
    with open(stats_csv) as f:
        rows = list(csv.DictReader(f))
    for s in stages:
        if s.get("startMs") is None:
            continue
        window = [r for r in rows if s["startMs"] <= int(r["epoch_ms"]) < s["endMs"]]
        app = [float(r["cpu_pct"]) for r in window]
        redis = [float(r["redis_cpu_pct"]) for r in window
                 if r.get("redis_cpu_pct") not in (None, "", "-")]
        out[s["name"]] = (sum(app) / len(app) if app else None,
                          sum(redis) / len(redis) if redis else None)
    return out


def fmt(v, digits=1):
    return "-" if v is None else f"{v:.{digits}f}"


def table(headers, rows, markdown):
    if markdown:
        lines = ["| " + " | ".join(headers) + " |", "|" + "---|" * len(headers)]
        lines += ["| " + " | ".join(str(c) for c in r) + " |" for r in rows]
        return "\n".join(lines)
    widths = [max(len(str(x)) for x in [h] + [r[i] for r in rows]) for i, h in enumerate(headers)]
    line = lambda cells: "  ".join(str(c).rjust(w) for c, w in zip(cells, widths))
    return "\n".join([line(headers), "  ".join("-" * w for w in widths)] + [line(r) for r in rows])


def main(argv):
    markdown = "--md" in argv
    labels = [a for a in argv if a != "--md"]
    runs = load_runs()
    if not labels:
        first_seen = {}
        for (label, _), run in runs.items():
            first_seen[label] = min(first_seen.get(label, run["date"]), run["date"])
        labels = sorted(first_seen, key=first_seen.get)
    missing = [l for l in labels if not any(k[0] == l for k in runs)]
    if missing:
        sys.exit(f"no runs for label(s): {', '.join(missing)}")

    scenarios = sorted({sc for (l, sc) in runs if l in labels})
    for scenario in scenarios:
        present = [l for l in labels if (l, scenario) in runs]
        ref = runs[(present[0], scenario)]
        print(f"\n### {scenario}  (reference: {present[0]})\n")
        rows = []
        for stage in ref["stages"]:
            ref_rps = stage["reqPerSec"]
            for label in present:
                run = runs[(label, scenario)]
                s = next((x for x in run["stages"] if x["name"] == stage["name"]), None)
                if s is None:
                    continue
                app_cpu, redis_cpu = run["cpu"].get(s["name"], (None, None))
                failed = s["errorPct"] > 1
                delta = "" if label == present[0] or failed or not ref_rps else \
                    f"{(s['reqPerSec'] / ref_rps - 1) * 100:+.0f}%"
                rows.append([
                    s["name"], label,
                    fmt(s["reqPerSec"]) + (" (failed)" if failed else ""), delta,
                    fmt(s["p50"]), fmt(s["p95"]), fmt(s["p99"]), fmt(s["errorPct"], 2),
                    fmt(app_cpu, 0), fmt(redis_cpu, 0),
                ])
        print(table(["stage", "label", "req/s", "vs ref", "p50", "p95", "p99", "err%",
                     "app cpu%", "redis cpu%"], rows, markdown))

    print("\nRuns:")
    for label in labels:
        for (l, sc), run in sorted(runs.items()):
            if l == label:
                print(f"  {label:<10} {sc:<18} git={run.get('gitSha')}  ips="
                      f"{'per-VU' if run.get('spreadIps') else 'single'}  "
                      f"note={run.get('note') or '-'}")


if __name__ == "__main__":
    main(sys.argv[1:])
