r"""
验收 5：100 次扫描 0 崩溃。

    python tools\accept_5_crash.py [N=100] [--reuse MIN]

Same long run as accept_3. Counts, for both apps: crash-buffer entries (FATAL EXCEPTION),
ANRs, process restarts (pid before vs after), and scans that never finished (no BB_SCAN
line) or failed in the camera.

PASS: all of those are zero.
"""
import sys

from acceptlib import long_run, save, verdict


def main(argv):
    n = int(argv[0]) if argv and argv[0].isdigit() else 100
    reuse = int(argv[argv.index("--reuse") + 1]) if "--reuse" in argv else 0
    run = long_run(n, reuse)
    rows = run["rows"]
    missing = sum(r["outcome"] == "no_line" for r in rows)
    camera = sum(r["outcome"] == "camera_error" for r in rows)
    restarted = [k for k in ("glasses", "phone") if run["pids_before"][k] != run["pids_after"][k]]
    crashes = len(run["crash_glasses"]) + len(run["crash_phone"])
    outcomes = {}
    for r in rows:
        outcomes[r["outcome"]] = outcomes.get(r["outcome"], 0) + 1
    ok = missing == 0 and camera == 0 and not restarted and crashes == 0
    save("5_crash", {"pass": ok, "n": n, "crash_lines": crashes, "restarted": restarted, "unfinished": missing,
                     "camera_errors": camera, "outcomes": outcomes, "run": run["when"]})
    verdict(ok, "%d 次扫描 0 崩溃" % n, [
        "crash/ANR log lines: %d   restarted: %s   unfinished scans: %d   camera errors: %d" % (
            crashes, ", ".join(restarted) or "none", missing, camera),
        "outcomes: %s" % ", ".join("%s %d" % kv for kv in sorted(outcomes.items())),
        "pids glasses %s→%s, phone %s→%s" % (run["pids_before"]["glasses"], run["pids_after"]["glasses"],
                                            run["pids_before"]["phone"], run["pids_after"]["phone"]),
    ])
    return ok


if __name__ == "__main__":
    main(sys.argv[1:])
