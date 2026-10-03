r"""
验收 4：100 次扫描无内存泄漏。

    python tools\accept_4_leak.py [N=100] [--reuse MIN]

Same long run as accept_3. A leak shows up as PSS that keeps climbing with every scan, so
this compares the average PSS of scans 11–20 (after warm-up) with scans N-9..N, and fits a
straight line through scans 11..N.

PASS: growth ≤ 5 MB between the two windows and slope ≤ 50 KB per scan.
"""
import statistics
import sys

from acceptlib import long_run, save, verdict


def slope(ys):
    xs = range(len(ys))
    mx, my = statistics.mean(xs), statistics.mean(ys)
    den = sum((x - mx) ** 2 for x in xs)
    return sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / den if den else 0.0


def main(argv):
    n = int(argv[0]) if argv and argv[0].isdigit() else 100
    reuse = int(argv[argv.index("--reuse") + 1]) if "--reuse" in argv else 0
    run = long_run(n, reuse)
    pss = [r.get("pss_kb") for r in run["rows"]]
    if len([x for x in pss if x]) < n * 0.9:
        verdict(False, "100 次扫描无内存泄漏", ["too few PSS samples"])
        return False
    early = [x for x in pss[10:20] if x]
    late = [x for x in pss[-10:] if x]
    growth = statistics.mean(late) - statistics.mean(early)
    k = slope([x for x in pss[10:] if x])
    ok = growth <= 5 * 1024 and k <= 50
    save("4_leak", {"pass": ok, "n": n, "early_mean_kb": statistics.mean(early), "late_mean_kb": statistics.mean(late),
                    "growth_kb": growth, "slope_kb_per_scan": k, "run": run["when"]})
    verdict(ok, "%d 次扫描无内存泄漏" % n, [
        "PSS scans 11–20 avg %.0f KB, last 10 avg %.0f KB, growth %+.0f KB (limit +5120)" % (
            statistics.mean(early), statistics.mean(late), growth),
        "trend %+.1f KB per scan (limit 50)" % k,
    ])
    return ok


if __name__ == "__main__":
    main(sys.argv[1:])
