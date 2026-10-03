r"""
Write rules\REVIEW.md: every catalog item, each city's bin + reason + source link, side by side.

    python tools\rules_table.py

Regenerate after editing the rules; the JSON files are the source of truth.
"""
import json
import os

from sources import ROOT

RULES = os.path.join(ROOT, "rules")
CITIES = ["cupertino", "san_jose"]


def load(name):
    with open(os.path.join(RULES, name + ".json"), encoding="utf-8") as f:
        return json.load(f)


def cell(city, item):
    rule = city["rules"][item]
    if rule["bin"] == "unknown":
        return "❔ *unknown* — %s" % rule["note"]
    label = city["bins"][rule["bin"]]["line1"]
    src = city["sources"][rule["source"]]
    # archived copies are cited for sites that block scripts; link the live page for people
    link = "[源](%s)" % src.get("live_url", src["url"])
    if "live_url" in src:
        link += " [存档](%s)" % src["url"]
    for also in rule.get("also", []):  # official sentences behind the reason's instructions
        s = city["sources"][also["source"]]
        link += " [依据](%s)" % s.get("live_url", s["url"])
    extra = "".join("<br>📝 %s" % rule[k] for k in ("note", "exception") if rule.get(k))
    return "**%s** — %s %s%s" % (label, rule["reason"], link, extra)


def main():
    items = load("items")["items"]
    cities = [load(c) for c in CITIES]
    lines = [
        "# 规则对照表（自动生成，勿手改）",
        "",
        "由 `python tools\\rules_table.py` 从 `rules/*.json` 生成。每条规则的原文引用都由 "
        "`python tools\\verify_rules.py --live` 在官方页面上逐字核对过。",
        "",
        "⚠ = 两个城市扔法不同。",
        "",
        "| 物品 | %s |   |" % " | ".join(c["name"] for c in cities),
        "|---|" + "---|" * len(cities) + "---|",
    ]
    for it in items:
        bins = {c["rules"][it["id"]]["bin"] for c in cities}
        differs = "⚠" if len(bins) > 1 and "unknown" not in bins else ""
        lines.append("| %s | %s | %s |" % (it["name"], " | ".join(cell(c, it["id"]) for c in cities), differs))

    lines += ["", "## 统计", ""]
    for c in cities:
        counts = {}
        for r in c["rules"].values():
            label = "unknown" if r["bin"] == "unknown" else c["bins"][r["bin"]]["line1"]
            counts[label] = counts.get(label, 0) + 1
        lines.append("- **%s**（%s）：%s" % (c["name"], c["authority"],
                                            "，".join("%s %d" % kv for kv in sorted(counts.items()))))
    out = os.path.join(RULES, "REVIEW.md")
    with open(out, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    print("wrote", out)


if __name__ == "__main__":
    main()
