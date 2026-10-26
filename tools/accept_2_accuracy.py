r"""
验收 2：≥ 50 件测试集，准确率 ≥ 90%。

    python tools\accept_2_accuracy.py [--set eval\realworld ...] [--city all|cupertino|san_jose|both]

Runs every labelled photo through the phone app's real pipeline (gallery test mode hook:
the photo goes into the app's private folder, nothing in shared storage, removed after)
and compares what the lens would show with what the city's official-source rules say
for the labelled item ("Not sure — check city guide" is the right answer for items the
sources don't settle). Needs only the phone on adb.

Test sets (each one that exists with ≥ 50 photos is run and reported on its own):
  eval\glasses_set\    photos taken with the glasses (tools\collect_testset.py) — the real test
  eval\realworld\      openly licensed real-world photos (tools\collect_web_testset.py), labelled
                       by hand; many are food still in a take-out box, bowl or cup
  eval\commons_probe\  one stock photo per catalog item (Wikimedia Commons)

PASS: every set run has ≥ 50 photos, ≥ 90% right line 1, and zero wrong definite bins in every city.
Material/contents evidence is not annotated in these old sets: abstentions count as misses.
"""
import json
import argparse
import os
import sys

from acceptlib import (clear_private, classify_private, expected_line1, load_rules, preserve_phone,
                       phone_status, push_private, require_residential_scope, review_all, save, set_city, verdict)
from adbutil import PHONE_PKG, ROOT, phone

KINDS = {
    "glasses_set": "glasses photos",
    "realworld": "real-world web photos, hand-labelled",
    "commons_probe": "stock photos (Wikimedia Commons), one per item",
}


def load_set(path):
    with open(os.path.join(path, "manifest.json"), encoding="utf-8") as f:
        man = json.load(f)
    # glasses_set / realworld: {"photos": [{"file":..,"item":..}]};  commons_probe: {item: {"file":..}}
    if "photos" in man:
        return [(os.path.join(path, m["file"]), m["item"]) for m in man["photos"]]
    return [(os.path.join(path, m["file"]), m.get("expect", item)) for item, m in sorted(man.items())]


def run_set(p, rules, path, cities_to_test):
    name = os.path.basename(path.rstrip("\\/"))
    photos = load_set(path)
    results = {city: [] for city in cities_to_test}
    clear_private(p)
    try:
        set_city(p, cities_to_test[0])
        status = phone_status(p)
        require_residential_scope(status)
        for i, (src, item) in enumerate(photos):
            fname = "%s_%03d_%s.jpg" % (name[:4], i, item)
            push_private(p, src, fname)
            first = classify_private(p, fname)
            reviews = review_all(p, cities_to_test[1:]) if first else {}
            for city in cities_to_test:
                r = first if city == cities_to_test[0] else reviews.get(city)
                r = r or {"line1": "(no answer)", "item": "-", "model": "-1", "p": None}
                want = expected_line1(rules, city, item)
                results[city].append({"file": fname, "item": item, "got_item": r["item"], "want": want, "got": r["line1"],
                             "ok": r["line1"] == want, "kind": r.get("kind"), "p": r.get("p"),
                             "model_ms": int(r["model"]), "total_ms": int(r.get("total", -1)),
                             "guidance": r.get("line2")})
                if not results[city][-1]["ok"]:
                    print("  %-9s -- %-26s %-20s -> %-20s %-28s want: %s" % (city, fname, item, r["item"], r["line1"], want))
            print("  %s %d/%d classified and reviewed in %d cities" % (name, i + 1, len(photos), len(cities_to_test)))
            clear_private(p)
    finally:
        clear_private(p)
    return name, {"kind": KINDS.get(name, name), "n": len(photos), "results": results,
                  "scope": "Single-family city residential rules; other-city reviews reuse evidence, not user-service eligibility",
                  "first_city": cities_to_test[0], "first_city_housing": status.get("housing"),
                  "first_city_service": status.get("city_service")}


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--set", nargs="+")
    parser.add_argument("--city", default="all")
    args = parser.parse_args(argv)
    sets = [os.path.abspath(s) for s in args.set] if args.set else [
        os.path.join(ROOT, "eval", s) for s in ("glasses_set", "realworld", "commons_probe")]
    sets = [s for s in sets if os.path.exists(os.path.join(s, "manifest.json")) and len(load_set(s)) >= 50]
    if not sets:
        sys.exit("no test set with ≥50 photos")

    p = phone()
    p.shell("am start -n %s/.MainActivity" % PHONE_PKG)
    rules = load_rules()
    cities_to_test = list(rules) if args.city == "all" else ["cupertino", "san_jose"] if args.city == "both" else [args.city]
    if any(c not in rules for c in cities_to_test):
        parser.error("Unsupported city")
    report, lines, ok = {}, [], True
    save("2_accuracy", {"pass": False, "error": "Accuracy run not completed", "sets": {}})
    with preserve_phone(p) as before:
        for path in sets:
            name, data = run_set(p, rules, path, cities_to_test)
            report[name] = data
            for city, rows in data["results"].items():
                right = sum(r["ok"] for r in rows)
                timeouts = sum(r["got"] in ("No connection", "(no answer)") for r in rows)
                acc = right / len(rows)
                explicit = [r for r in rows if r["kind"] == "0" and r["got"] in
                            {b["line1"] for key, b in rules[city]["bins"].items() if key != "unknown"}]
                wrong_bin = sum(not r["ok"] for r in explicit)
                precision = sum(r["ok"] for r in explicit) / len(explicit) if explicit else None
                data.setdefault("metrics", {})[city] = {"accuracy": acc, "wrong_bins": wrong_bin,
                    "coverage": len(explicit) / len(rows), "precision": precision, "timeouts": timeouts}
                ok = ok and acc >= 0.9 and wrong_bin == 0
                lines.append("%-13s %-9s %d/%d = %.1f%% right (wrong bin %d, cloud timeouts %d)" % (
                    name, city, right, len(rows), 100 * acc, wrong_bin, timeouts))
            lines.append("%-13s %d photos: %s" % (name, data["n"], data["kind"]))
    save("2_accuracy", {"pass": ok, "sets": report, "phone_app": before["app"]})
    verdict(ok, "测试集准确率 ≥90%", lines)
    return ok


if __name__ == "__main__":
    sys.exit(0 if main(sys.argv[1:]) else 1)
