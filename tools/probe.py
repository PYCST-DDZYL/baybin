r"""
Phase-1 measurements on real hardware. Needs a debug build of the glasses app.

    python tools\probe.py photo              # 拍一张，拉回 tools\out\last.jpg（看画面和方向）
    python tools\probe.py camera [N=10]      # 只拍不发：相机耗时、JPEG 大小、眼镜 PSS
    python tools\probe.py transfer           # 需要手机：16KB…512KB 逐级发给手机，测上限和速度
    python tools\probe.py scan [N=10]        # 需要手机：完整一轮（拍→发→手机回结果），分段耗时
    python tools\probe.py show               # 把最长的几条规则文字显示到镜片上并截屏（tools\out\show-*.png）
    python tools\probe.py record             # 录一段镜片画面：按键 → Looking → Thinking → 结果（docs\demo-lens.mp4）
    python tools\probe.py pair [MAC]         # 需要手机：手机连眼镜（给 MAC 就直连，不给就扫描），报告两边状态

Results are printed and appended to tools\out\probe-log.txt.
"""
import os
import re
import statistics
import sys
import time

from adbutil import ROOT, GLASS_PKG, glasses, wake_glasses

OUT = os.path.join(ROOT, "tools", "out")
SCAN_RX = r"BB_SCAN id=(\d+) outcome=(\S+) open=(-?\d+) shot=(-?\d+) send=(-?\d+) bytes=(-?\d+) total=(\d+)"
PROBE_RX = r"BB_PROBE id=(\d+) size=(\d+) raw=(\S+) rc=(-?\d+) call=(-?\d+) outcome=(\S+) received=(-?\d+) rtt=(\d+)"


def log(text):
    print(text)
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, "probe-log.txt"), "a", encoding="utf-8") as f:
        f.write(text + "\n")


def broadcast(g, action, *extras):
    g.shell("am broadcast -a com.baybin.glass.%s -p %s %s" % (action, GLASS_PKG, " ".join(extras)))


def one_scan(g, capture_only, save=False, timeout=15):
    g.logcat_clear()
    broadcast(g, "SCAN", "--ez captureOnly %s" % str(capture_only).lower(), "--ez save %s" % str(save).lower())
    m = g.wait_log(SCAN_RX, timeout=timeout)
    if not m:
        return None
    keys = ("id", "outcome", "open", "shot", "send", "bytes", "total")
    return {k: (v if k == "outcome" else int(v)) for k, v in zip(keys, m.groups())}


def summary(name, values):
    if not values:
        return "%-14s -" % name
    values = sorted(values)
    p95 = values[min(len(values) - 1, int(round(0.95 * (len(values) - 1))))]
    return "%-14s min %5d  median %5d  p95 %5d  max %5d" % (
        name, values[0], statistics.median(values), p95, values[-1])


def cmd_photo(g):
    wake_glasses(g)
    r = one_scan(g, capture_only=True, save=True)
    if not r or r["outcome"] != "capture_only":
        sys.exit("拍照失败：%s\n%s" % (r, g.logcat("BayBin:V", "*:S")[-2000:]))
    os.makedirs(OUT, exist_ok=True)
    dst = os.path.join(OUT, "last.jpg")
    g.run("pull", "/sdcard/Android/data/%s/files/last.jpg" % GLASS_PKG, dst, check=True)
    log("photo: %d bytes, shot %d ms -> %s" % (r["bytes"], r["shot"], dst))


def cmd_camera(g, n):
    wake_glasses(g)
    rows = []
    pss = []
    for i in range(n):
        r = one_scan(g, capture_only=True)
        kb = g.pss_kb(GLASS_PKG)
        pss.append(kb or 0)
        if not r:
            log("  #%d no BB_SCAN line (timeout)" % (i + 1))
            continue
        rows.append(r)
        log("  #%d %-13s open %4d ms  shot %4d ms  %6d B  PSS %d KB" % (
            i + 1, r["outcome"], r["open"], r["shot"], r["bytes"], kb or -1))
        time.sleep(0.5)
    ok = [r for r in rows if r["outcome"] == "capture_only"]
    log("camera x%d: %d ok" % (n, len(ok)))
    log("  " + summary("open ms", [r["open"] for r in ok]))
    log("  " + summary("press->jpeg ms", [r["shot"] for r in ok]))
    log("  " + summary("jpeg bytes", [r["bytes"] for r in ok]))
    log("  " + summary("glasses PSS KB", [p for p in pss if p]))


def cmd_transfer(g):
    wake_glasses(g)
    log("transfer (RFCOMM frames):")
    for kb in (16, 64, 128, 256, 512):
        g.logcat_clear()
        broadcast(g, "PROBE", "--ei kb %d" % kb)
        m = g.wait_log(PROBE_RX, timeout=25)
        if not m:
            log("  %4d KB: no BB_PROBE line" % kb)
            continue
        _, size, _, rc, call, outcome, received, rtt = m.groups()
        rtt = int(rtt)
        speed = int(size) / 1024 / (rtt / 1000) if outcome == "ok" and rtt else 0
        log("  %4d KB: %-9s rc=%s call=%s ms rtt=%5d ms received=%s  %s" % (
            kb, outcome, rc, call, rtt, received, ("%.0f KB/s" % speed) if speed else ""))
        time.sleep(1)


def cmd_scan(g, n):
    wake_glasses(g)
    rows = []
    for i in range(n):
        r = one_scan(g, capture_only=False, timeout=20)
        if not r:
            log("  #%d no BB_SCAN line" % (i + 1))
            continue
        rows.append(r)
        log("  #%d %-12s shot %4d  send %4d  total %5d ms  %6d B" % (
            i + 1, r["outcome"], r["shot"], r["send"], r["total"], r["bytes"]))
        time.sleep(1)
    ok = [r for r in rows if r["outcome"].startswith("result_")]
    log("scan x%d: %d answered" % (n, len(ok)))
    log("  " + summary("press->jpeg ms", [r["shot"] for r in ok]))
    log("  " + summary("send call ms", [r["send"] for r in ok]))
    log("  " + summary("total ms", [r["total"] for r in ok]))


def cmd_show(g, count=4):
    """Lens layout check: the longest rule texts, plus the not-sure text, as the glasses draw them."""
    import json
    import shlex
    import subprocess
    from adbutil import ADB
    rows = []
    for city in ("cupertino", "san_jose"):
        with open(os.path.join(ROOT, "rules", city + ".json"), encoding="utf-8") as f:
            c = json.load(f)
        for r in c["rules"].values():
            if r["bin"] != "unknown":
                rows.append((c["bins"][r["bin"]]["line1"], r["reason"]))
    rows = sorted(set(rows), key=lambda r: -len(r[1]))[:count] + [("Not sure — check city guide", "")]
    wake_glasses(g)
    os.makedirs(OUT, exist_ok=True)
    for n, (l1, l2) in enumerate(rows, 1):
        broadcast(g, "SHOW", "--es l1 %s" % shlex.quote(l1), "--es l2 %s" % shlex.quote(l2))
        time.sleep(0.6)
        png = subprocess.run([ADB, "-s", g.serial, "exec-out", "screencap", "-p"], capture_output=True).stdout
        path = os.path.join(OUT, "show-%d.png" % n)
        with open(path, "wb") as f:
            f.write(png)
        log("show-%d: %d chars  %s | %s" % (n, len(l2), l1, l2))


def cmd_pair(g, mac=None):
    """Phone connects to the glasses app: straight to MAC if given, else BLE scan + address lookup."""
    from adbutil import PHONE_PKG, phone
    p = phone()
    if p.shell("settings get global bluetooth_on").strip() != "1":
        sys.exit("手机蓝牙没开。")
    wake_glasses(g)
    p.shell("am start -n %s/.MainActivity" % PHONE_PKG)
    time.sleep(1.5)
    from acceptlib import phone_status
    st = phone_status(p)
    if st and st["link"] == "CONNECTED":  # the app's service reconnects to saved glasses by itself
        log("phone: already connected to %s, glasses app %s" % (st["mac"], st["glasses"]))
        return
    p.logcat_clear()
    g.logcat_clear()
    p.shell("am broadcast -a com.baybin.phone.FIND -p %s %s" % (PHONE_PKG, ("--es mac " + mac) if mac else ""))
    m = p.wait_log(r"link: (CONNECTED|FAILED) \((.*)\)", timeout=60, filters=("BayBinPhone:I", "*:S"))
    log("phone: %s" % (m.group(0) if m else "no connection result within 60 s"))
    for line in p.logcat("BayBinPhone:I", "*:S").splitlines()[-10:]:
        log("   " + line.split("): ", 1)[-1])
    if m and m.group(1) == "CONNECTED":
        up = g.wait_log(r"link up: (\S+)", timeout=10, filters=("BayBin:V", "*:S"))
        hello = p.wait_log(r"glasses app (\S+) connected", timeout=10, filters=("BayBinPhone:I", "*:S"))
        log("glasses side: %s" % (up.group(0) if up else "no link-up seen"))
        log("app-to-app hello: %s" % (hello.group(0) if hello else "no hello from the glasses app"))


def cmd_record(g, seconds=10):
    """Screen-records the lens during one key-press scan. The file goes to docs/ on the PC and is
    deleted from the glasses right after (it only exists there while recording)."""
    import subprocess
    from adbutil import ADB
    tmp = "/sdcard/Download/baybin-lens.mp4"
    wake_glasses(g)
    time.sleep(1)
    rec = subprocess.Popen([ADB, "-s", g.serial, "shell", "screenrecord", "--time-limit", str(seconds), tmp])
    time.sleep(1.5)
    g.logcat_clear()
    g.shell("input keyevent KEYCODE_DPAD_CENTER")
    m = g.wait_log(SCAN_RX, timeout=seconds)
    rec.wait(timeout=seconds + 10)
    os.makedirs(os.path.join(ROOT, "docs"), exist_ok=True)
    dst = os.path.join(ROOT, "docs", "demo-lens.mp4")
    g.run("pull", tmp, dst, check=True)
    g.shell("rm -f %s" % tmp)
    log("record: %s -> %s (%d KB)" % (m.group(0) if m else "no BB_SCAN line", dst, os.path.getsize(dst) // 1024))


def main(argv):
    if not argv:
        print(__doc__)
        return
    g = glasses()
    log("== %s  %s" % (time.strftime("%Y-%m-%d %H:%M:%S"), " ".join(argv)))
    cmd = argv[0]
    n = int(argv[1]) if len(argv) > 1 and argv[1].isdigit() else 10
    if cmd == "photo":
        cmd_photo(g)
    elif cmd == "camera":
        cmd_camera(g, n)
    elif cmd == "transfer":
        cmd_transfer(g)
    elif cmd == "scan":
        cmd_scan(g, n)
    elif cmd == "show":
        cmd_show(g)
    elif cmd == "record":
        cmd_record(g)
    elif cmd == "pair":
        cmd_pair(g, argv[1] if len(argv) > 1 and ":" in argv[1] else None)
    else:
        print(__doc__)


if __name__ == "__main__":
    main(sys.argv[1:])
