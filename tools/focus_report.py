"""Summarize measured San Jose daily-item results without treating retakes as correct.

python tools/focus_report.py tools/out/accept/2_accuracy.json [--output tools/out/focus.json]
The input is a real accept_2_accuracy report. Old source hashes are explicitly STALE.
This command does not run recognition or demonstrate an accuracy improvement.
"""
import argparse
import json
from pathlib import Path

from acceptlib import code_hash
from adbutil import ROOT


def metrics(rows):
    counts = dict(total=len(rows), correct=0, wrong_bins=0, retakes=0, timeouts=0,
                  connection_errors=0, service_errors=0, outside_scope=0, not_configured=0, missing=0)
    for row in rows:
        kind = str(row.get("kind"))
        if row.get("got") == "(no answer)":
            counts["timeouts"] += 1
        elif kind == "0":
            if not row.get("want") or not row.get("got"):
                counts["missing"] += 1
            else:
                counts["correct" if row["got"] == row["want"] else "wrong_bins"] += 1
        elif kind == "1":
            if str(row.get("guidance", "")).startswith("Confirm housing and city collection service"):
                counts["outside_scope"] += 1
            else:
                counts["retakes"] += 1
        elif kind == "2":
            counts["timeouts" if int(row.get("model_ms", -1)) >= 7000 else "connection_errors"] += 1
        elif kind == "3":
            counts["service_errors"] += 1
        elif kind == "4":
            counts["not_configured"] += 1
        else:
            counts["missing"] += 1
    n = counts["total"]
    counts["success_rate"] = counts["correct"] / n if n else None
    definite = counts["correct"] + counts["wrong_bins"]
    counts["definite_precision"] = counts["correct"] / definite if definite else None
    return counts


def summarize(report, expected_hash, focus):
    rows = [row for sample in report.get("sets", {}).values()
            for row in sample.get("results", {}).get(focus["city"], []) if row.get("item") in focus["items"]]
    first = [row for row in rows if row.get("evidence", "photo") == "photo"]
    supplemented = [row for row in rows if row.get("evidence") == "user"]
    return {"city": focus["city"], "residence": focus["residence"],
            "source_hash": report.get("code_hash"), "current_hash": expected_hash,
            "status": "CURRENT" if report.get("code_hash") == expected_hash else "STALE",
            "measured_at": report.get("when"), "first_pass": metrics(first),
            "user_supplemented": metrics(supplemented),
            "per_item": {item: metrics([r for r in first if r["item"] == item]) for item in focus["items"]},
            "note": "A report of supplied measurements; no new cloud/device evaluation was run by this command."}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if args.output and args.output.resolve() == args.report.resolve():
        parser.error("Output must not overwrite the input measurements")
    focus = json.loads((Path(ROOT) / "eval/focus_san_jose.json").read_text(encoding="utf-8"))
    result = summarize(json.loads(args.report.read_text(encoding="utf-8")), code_hash(), focus)
    text = json.dumps(result, ensure_ascii=False, indent=2)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text + "\n", encoding="utf-8")
    print(text)


if __name__ == "__main__":
    main()
