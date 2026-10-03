r"""
验收 1：按键到镜片显示 ≤ 5 秒。

    python tools\accept_1_latency.py [N=20]

Presses the glasses' OK key (adb input keyevent DPAD_CENTER, the same key event as a
touchpad tap) N times with the phone app connected, and reads the glasses' own timing:
BB_SCAN total = key up -> the two lines are on the lens. Also splits each scan into
camera / cloud / the rest (Bluetooth both ways, phone work, display), using the phone's
BB_RESULT line for the same scan.

PASS: every scan answered, and at least 90% of them took ≤ 5 s (median reported too).
Cloud time depends on Model Studio load (it is slowest in Beijing's evening).
"""
import sys
import time

from acceptlib import both, ensure_link, phone_result, save, scan, stats, verdict


def main(n):
    g, p = both()
    if not ensure_link(g, p):
        sys.exit("phone app is not connected to the glasses app")
    rows = []
    for i in range(n):
        p.logcat_clear()
        r = scan(g, key=True)
        if r is None:
            print("  #%d no BB_SCAN line" % (i + 1))
            rows.append({"outcome": "no_line"})
            continue
        ph = phone_result(p, r["id"]) or {}
        r["model"] = int(ph.get("model", -1))
        r["calls"] = int(ph.get("calls", 0))
        r["line1"] = ph.get("line1", "")
        rows.append(r)
        print("  #%2d %-10s total %5d ms  camera %4d  cloud %5d (x%d)  rest %4d  | %s" % (
            i + 1, r["outcome"], r["total"], r["shot"], r["model"], r["calls"],
            r["total"] - r["shot"] - max(r["model"], 0), r["line1"]))
        time.sleep(1.5)

    answered = [r for r in rows if str(r.get("outcome", "")).startswith("result_")]
    totals = [r["total"] for r in answered]
    under = sum(t <= 5000 for t in totals)
    ok = len(answered) == n and n > 0 and under >= 0.9 * n
    s = stats(totals)
    data = save("1_latency", {
        "pass": ok, "n": n, "answered": len(answered), "under_5s": under,
        "total_ms": s, "camera_ms": stats([r["shot"] for r in answered]),
        "cloud_ms": stats([r["model"] for r in answered if r["model"] >= 0]),
        "hedged": sum(r["calls"] > 1 for r in answered), "rows": rows})
    verdict(ok, "按键→镜片 ≤5 s", [
        "%d/%d answered, %d/%d ≤ 5 s" % (len(answered), n, under, n),
        "total  median %(median)s  p90 %(p90)s  max %(max)s ms" % s if s else "no data",
        "camera median %s ms, cloud median %s ms, hedge fired %d times" % (
            data["camera_ms"].get("median"), data["cloud_ms"].get("median"), data["hedged"]),
    ])
    return ok


if __name__ == "__main__":
    main(int(sys.argv[1]) if len(sys.argv) > 1 else 20)
