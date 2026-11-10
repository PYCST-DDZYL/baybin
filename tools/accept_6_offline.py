r"""
验收 6：断网时显示 "No connection"，不崩溃不卡死。

    python tools\accept_6_offline.py

Two ways the glasses can lose the cloud, each followed by a normal scan to prove nothing
is stuck afterwards:
  A. the phone has no internet: Wi-Fi and mobile data switched off with `svc` for about a
     minute, then switched back to exactly what they were (also if the script fails);
  B. the phone app's Bluetooth link to the glasses is dropped (and reconnected after).

PASS: both scans show "No connection" on the lens within 8 s, both recovery scans are
answered normally, no crash or ANR, neither app restarted.
"""
import time

from acceptlib import (both, crash_lines, ensure_link, glass_pid, glasses_screenshot, phone_pid, phone_status, save,
                       scan, verdict)
from adbutil import GLASS_PKG, PHONE_PKG


def wait_online(p, want, timeout):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        st = phone_status(p)
        if st and st["online"] == ("true" if want else "false"):
            return True
        time.sleep(2)
    return False


def radio_enabled(value, name):
    state = value.strip()
    if state not in ("0", "1"):
        raise RuntimeError("Cannot read original %s state; refusing to change connectivity" % name)
    return state == "1"


def restore_radios(p, wifi, data):
    try:
        if wifi:
            p.shell("svc wifi enable", check=True)
    finally:
        if data:
            p.shell("svc data enable", check=True)


def scan_without_link(p, g):
    try:
        p.shell("am broadcast -a com.baybin.phone.DISCONNECT -p %s --ez stay true" % PHONE_PKG, check=True)
        time.sleep(3)
        result = scan(g, key=True)
        glasses_screenshot(g, "6_nolink_lens.png")
        return result
    finally:
        p.shell("am broadcast -a com.baybin.phone.RECONNECT -p %s" % PHONE_PKG, check=True)


def passed(steps, crashes, same_processes):
    def no_connection(step):
        result = steps[step]["scan"]
        return bool(result) and result["outcome"] in ("result_2", "no_link", "send_failed") and result["total"] <= 8000

    def answered(step):
        result = steps[step]["scan"]
        return bool(result) and result["outcome"] in ("result_0", "result_1")

    return (steps["A_offline"]["phone_saw_offline"] and steps["A_recovered"]["online_again"] and
            steps["B_recovered"]["relinked"] and no_connection("A_offline") and no_connection("B_no_link") and
            answered("A_recovered") and answered("B_recovered") and not crashes and same_processes)


def main():
    g, p = both()
    if not ensure_link(g, p):
        raise SystemExit("phone app is not connected to the glasses app")
    for d in (g, p):
        d.run("logcat", "-b", "crash", "-c")
    pids = (glass_pid(g), phone_pid(p))
    steps = {}

    # A. phone offline
    wifi = radio_enabled(p.shell("settings get global wifi_on", check=True), "Wi-Fi")
    data = radio_enabled(p.shell("settings get global mobile_data", check=True), "mobile data")
    try:
        p.shell("svc wifi disable", check=True)
        p.shell("svc data disable", check=True)
        offline = wait_online(p, False, 30)
        r = scan(g, key=True)
        glasses_screenshot(g, "6_offline_lens.png")
        steps["A_offline"] = {"phone_saw_offline": offline, "scan": r}
    finally:
        restore_radios(p, wifi, data)
    back = wait_online(p, True, 90)
    time.sleep(3)
    steps["A_recovered"] = {"online_again": back, "scan": scan(g, key=True)}

    # B. Bluetooth link to the phone dropped
    r = scan_without_link(p, g)
    steps["B_no_link"] = {"scan": r}
    relinked = ensure_link(g, p, timeout=40)
    time.sleep(2)
    steps["B_recovered"] = {"relinked": relinked, "scan": scan(g, key=True)}

    crashes = crash_lines(g, GLASS_PKG) + crash_lines(p, PHONE_PKG)
    same = pids == (glass_pid(g), phone_pid(p))
    ok = passed(steps, crashes, same)
    save("6_offline", {"pass": ok, "steps": steps, "crash_lines": crashes, "restarted": not same})
    fmt = lambda s: "%s in %s ms" % (s["outcome"], s["total"]) if s else "no BB_SCAN line"
    verdict(ok, "断网显示 No connection，不崩溃不卡死", [
        "A phone offline     -> %s (lens: tools/out/accept/6_offline_lens.png)" % fmt(steps["A_offline"]["scan"]),
        "A back online       -> %s" % fmt(steps["A_recovered"]["scan"]),
        "B Bluetooth dropped -> %s (lens: tools/out/accept/6_nolink_lens.png)" % fmt(steps["B_no_link"]["scan"]),
        "B reconnected       -> %s" % fmt(steps["B_recovered"]["scan"]),
        "crash/ANR lines %d, apps restarted: %s" % (len(crashes), "no" if same else "YES"),
    ])
    return ok


if __name__ == "__main__":
    main()
