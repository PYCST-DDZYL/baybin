r"""
Runs the acceptance scripts and writes docs\acceptance.md from their saved results.

    python tools\accept_all.py            # run all eight (the 100-scan run once), then write the report
    python tools\accept_all.py --report   # only rewrite docs\acceptance.md from the last saved results

Needs the glasses and the phone on adb (USB or wireless), both apps installed (deploy.py all),
and the phone app connected to the glasses.
"""
import os
import subprocess
import sys

from acceptlib import load
from adbutil import ROOT

HERE = os.path.dirname(os.path.abspath(__file__))
RUNS = [
    ["accept_1_latency.py", "20"],
    ["accept_2_accuracy.py"],
    ["accept_3_memory.py", "100"],
    ["accept_4_leak.py", "100", "--reuse", "120"],
    ["accept_5_crash.py", "100", "--reuse", "120"],
    ["accept_6_offline.py"],
    ["accept_7_gallery.py"],
]
TITLES = {
    1: "按键到镜片显示 ≤5 秒",
    2: "≥50 件测试集准确率 ≥90%",
    3: "眼镜端峰值 PSS ≤150 MB",
    4: "100 次扫描无内存泄漏",
    5: "100 次扫描 0 崩溃",
    6: "断网显示 No connection，不崩溃不卡死",
    7: "手机相册测试模式（不用眼镜）",
    8: "README 内容齐全",
}
FILES = {1: "1_latency", 2: "2_accuracy", 3: "3_memory", 4: "4_leak", 5: "5_crash", 6: "6_offline", 7: "7_gallery",
         8: "8_readme"}


def detail(i, d):
    if not d:
        return "not run"
    if i == 1:
        t = d["total_ms"]
        return "%d/%d ≤5 s; median %s ms, p90 %s, max %s (camera ~%s, cloud ~%s)" % (
            d["under_5s"], d["n"], t.get("median"), t.get("p90"), t.get("max"),
            d["camera_ms"].get("median"), d["cloud_ms"].get("median"))
    if i == 2:
        out = []
        for name, s in d.get("sets", {}).items():
            parts = ["%s %d/%d" % (city, sum(r["ok"] for r in rows), len(rows)) for city, rows in s["results"].items()]
            out.append("%s（%s，%d 张）：%s" % (name, s["kind"], s["n"], "，".join(parts)))
        return "；".join(out)
    if i == 3:
        return "peak %.1f MB (%d samples)" % (d["peak_kb"] / 1024, d["samples"])
    if i == 4:
        return "growth %+.1f MB, trend %+.1f KB/scan" % (d["growth_kb"] / 1024, d["slope_kb_per_scan"])
    if i == 5:
        return "crash lines %d, restarts %s, unfinished %d, camera errors %d" % (
            d["crash_lines"], ",".join(d["restarted"]) or "0", d["unfinished"], d["camera_errors"])
    if i == 6:
        s = d["steps"]
        f = lambda k: "%s %sms" % (s[k]["scan"]["outcome"], s[k]["scan"]["total"]) if s[k].get("scan") else "-"
        return "offline → %s; recovered → %s; BT dropped → %s; relinked → %s" % (
            f("A_offline"), f("A_recovered"), f("B_no_link"), f("B_recovered"))
    if i == 7:
        good = sum(r["ok"] for r in d["rows"])
        return "%d/%d right with glasses link off; screen shows answer: %s; button opens picker: %s" % (
            good, len(d["rows"]), d["ui_shows_answer"], d["button_opens_picker"])
    if i == 8:
        return ", ".join(k for k, v in d["checks"].items() if v) or "-"
    return ""


def report():
    lines = ["# 验收结果", "", "由 `python tools\\accept_all.py` 生成；每一项的原始数据在 `tools\\out\\accept\\`。", "",
             "| # | 验收标准 | 结果 | 数据 | 测量时间 |", "|---|---|---|---|---|"]
    for i in range(1, 9):
        d = load(FILES[i])
        res = "—" if not d else ("PASS" if d["pass"] else "FAIL")
        lines.append("| %d | %s | %s | %s | %s |" % (i, TITLES[i], res, detail(i, d), d["when"] if d else ""))
    os.makedirs(os.path.join(ROOT, "docs"), exist_ok=True)
    path = os.path.join(ROOT, "docs", "acceptance.md")
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    print("\n".join(lines))
    print("\n->", path)
    # The same table goes into README.md, between <!-- RESULTS --> and <!-- /RESULTS -->.
    readme = os.path.join(ROOT, "README.md")
    text = open(readme, encoding="utf-8").read()
    a, b = text.find("<!-- RESULTS -->"), text.find("<!-- /RESULTS -->")
    if 0 <= a < b:
        table = [l for l in lines if l.startswith("|")]
        text = text[:a] + "<!-- RESULTS -->\n" + "\n".join(table) + "\n" + text[b:]
        with open(readme, "w", encoding="utf-8") as f:
            f.write(text)
        print("-> README.md")


def main(argv):
    if "--report" not in argv:
        for cmd in RUNS:
            print("\n=== %s" % " ".join(cmd))
            subprocess.call([sys.executable, os.path.join(HERE, cmd[0])] + cmd[1:])
        subprocess.call([sys.executable, os.path.join(HERE, "accept_8_readme.py")])
    report()


if __name__ == "__main__":
    main(sys.argv[1:])
