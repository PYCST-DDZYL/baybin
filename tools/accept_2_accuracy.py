r"""
验收 2：≥ 50 件测试集，准确率 ≥ 90%。

    python tools\accept_2_accuracy.py [--set eval\realworld ...] [--city cupertino|san_jose|both]

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

PASS: every set run has ≥ 50 photos and ≥ 90% right line 1 in every city.
"""
import json
import os
import sys

from acceptlib import clear_private, classify_private, expected_line1, load_rules, push_private, save, set_city, verdict
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
    results = {}
    clear_private(p)
    try:
        names = []
        for i, (src, item) in enumerate(photos):
            fname = "%s_%03d_%s.jpg" % (name[:4], i, item)
            push_private(p, src, fname)
            names.append((fname, item))
        for city in cities_to_test:
            set_city(p, city)
            rows = []
            for fname, item in names:
                r = classify_private(p, fname) or {"line1": "(no answer)", "item": "-", "model": "-1", "p": None}
                want = expected_line1(rules, city, item)
                rows.append({"file": fname, "item": item, "got_item": r["item"], "want": want, "got": r["line1"],
                             "ok": r["line1"] == want, "p": r.get("p"), "model_ms": int(r["model"])})
                if not rows[-1]["ok"]:
                    print("  %-9s -- %-26s %-20s -> %-20s %-28s want: %s" % (city, fname, item, r["item"], r["line1"], want))
            results[city] = rows
    finally:
        clear_private(p)
    return name, {"kind": KINDS.get(name, name), "n": len(photos), "results": results}


def main(argv):
    if "--set" in argv:
        sets = [os.path.abspath(a) for a in argv[argv.index("--set") + 1:] if not a.startswith("--")]
    else:
        sets = [os.path.join(ROOT, "eval", s) for s in ("glasses_set", "realworld", "commons_probe")]
    sets = [s for s in sets if os.path.exists(os.path.join(s, "manifest.json")) and len(load_set(s)) >= 50]
    if not sets:
        sys.exit("no test set with ≥50 photos")
    city_arg = argv[argv.index("--city") + 1] if "--city" in argv else "both"
    cities_to_test = ["cupertino", "san_jose"] if city_arg == "both" else [city_arg]

    p = phone()
    p.shell("am start -n %s/.MainActivity" % PHONE_PKG)
    rules = load_rules()
    report, lines, ok = {}, [], True
    try:
        for path in sets:
            name, data = run_set(p, rules, path, cities_to_test)
            report[name] = data
            for city, rows in data["results"].items():
                right = sum(r["ok"] for r in rows)
                timeouts = sum(r["got"] in ("No connection", "(no answer)") for r in rows)
                wrong_bin = sum((not r["ok"]) and r["got"] not in ("No connection", "(no answer)")
                                and not r["got"].startswith("Not sure") for r in rows)
                acc = right / len(rows)
                ok = ok and acc >= 0.9
                lines.append("%-13s %-9s %d/%d = %.1f%% right (wrong bin %d, cloud timeouts %d)" % (
                    name, city, right, len(rows), 100 * acc, wrong_bin, timeouts))
            lines.append("%-13s %d photos: %s" % (name, data["n"], data["kind"]))
    finally:
        set_city(p, "cupertino")
    save("2_accuracy", {"pass": ok, "sets": report})
    verdict(ok, "测试集准确率 ≥90%", lines)
    return ok


if __name__ == "__main__":
    main(sys.argv[1:])
