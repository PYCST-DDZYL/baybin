r"""
验收 3：眼镜端峰值 PSS ≤ 150 MB（adb shell dumpsys meminfo com.baybin.glass）。

    python tools\accept_3_memory.py [N=100] [--reuse MIN]

Samples the glasses app's TOTAL PSS after every scan of the shared long run (camera bound,
JPEG in memory, sent over Bluetooth, result shown) and reports the peak.
--reuse MIN: judge a long run saved in the last MIN minutes instead of scanning again.

PASS: highest sample ≤ 153600 KB (150 MB).
"""
import sys

from acceptlib import long_run, save, stats, verdict

LIMIT_KB = 150 * 1024


def main(argv):
    n = int(argv[0]) if argv and argv[0].isdigit() else 100
    reuse = int(argv[argv.index("--reuse") + 1]) if "--reuse" in argv else 0
    run = long_run(n, reuse)
    pss = [r["pss_kb"] for r in run["rows"] if r.get("pss_kb")]
    peak = max(pss) if pss else None
    ok = peak is not None and peak <= LIMIT_KB and len(pss) >= n * 0.9
    s = stats(pss)
    save("3_memory", {"pass": ok, "peak_kb": peak, "limit_kb": LIMIT_KB, "samples": len(pss), "pss_kb": s,
                      "idle_before_kb": run["pss_before_kb"], "run": run["when"]})
    verdict(ok, "眼镜峰值 PSS ≤150 MB", [
        "peak %s KB = %.1f MB over %d samples (limit 150 MB)" % (peak, (peak or 0) / 1024, len(pss)),
        "median %s KB, idle before the run %s KB" % (s.get("median"), run["pss_before_kb"]),
    ])
    return ok


if __name__ == "__main__":
    main(sys.argv[1:])
