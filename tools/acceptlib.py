"""
Shared helpers for the acceptance scripts (tools\\accept_*.py).

Each script checks one acceptance criterion, prints PASS/FAIL with the numbers, and writes
tools\\out\\accept\\<n>.json; accept_all.py collects them into docs\\acceptance.md.
"""
import json
import os
import re
import statistics
import subprocess
import time

from adbutil import ADB, GLASS_PKG, PHONE_PKG, ROOT, glasses, phone, wake_glasses

OUT = os.path.join(ROOT, "tools", "out", "accept")
SCAN_RX = r"BB_SCAN id=(\d+) outcome=(\S+) open=(-?\d+) shot=(-?\d+) send=(-?\d+) bytes=(-?\d+) total=(\d+)"
PHONE_LOG = ("BayBinPhone:I", "*:S")
GLASS_LOG = ("BayBin:I", "*:S")


def save(name, data):
    os.makedirs(OUT, exist_ok=True)
    data["when"] = time.strftime("%Y-%m-%d %H:%M:%S")
    with open(os.path.join(OUT, name + ".json"), "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=1)
    return data


def load(name):
    path = os.path.join(OUT, name + ".json")
    if not os.path.exists(path):
        return None
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def verdict(ok, title, lines):
    print("\n%s  %s" % ("PASS" if ok else "FAIL", title))
    for line in lines:
        print("   " + line)
    return ok


def pct(values, q):
    v = sorted(values)
    return v[min(len(v) - 1, int(round(q * (len(v) - 1))))] if v else None


def stats(values):
    if not values:
        return {}
    return {"n": len(values), "min": min(values), "median": statistics.median(values),
            "p90": pct(values, 0.9), "p95": pct(values, 0.95), "max": max(values)}


# ---- devices ----

def both():
    g = glasses()
    p = phone()
    return g, p


def phone_status(p):
    """Asks the phone app for BB_STATUS; returns dict or None."""
    p.logcat_clear()
    p.shell("am broadcast -a com.baybin.phone.STATUS -p %s" % PHONE_PKG)
    m = p.wait_log(r"BB_STATUS link=(\S+) mac=(\S+) glasses=(\S+) city=(\S+) online=(\S+)", timeout=8, filters=PHONE_LOG)
    if not m:
        return None
    return dict(zip(("link", "mac", "glasses", "city", "online"), m.groups()))


def ensure_link(g, p, timeout=40):
    """Phone app open and connected to the glasses app. Returns True when linked."""
    wake_glasses(g)
    p.shell("input keyevent KEYCODE_WAKEUP")
    p.shell("am start -n %s/.MainActivity" % PHONE_PKG)
    end = time.time() + timeout
    asked = False
    while time.time() < end:
        st = phone_status(p)
        if st and st["link"] == "CONNECTED":
            return True
        if st and not asked and st["mac"] != "null":
            p.shell("am broadcast -a com.baybin.phone.RECONNECT -p %s" % PHONE_PKG)
            asked = True
        time.sleep(2)
    return False


def set_city(p, city):
    p.shell("am broadcast -a com.baybin.phone.CITY -p %s --es id %s" % (PHONE_PKG, city))


def scan(g, key=True, timeout=20):
    """One scan the way a user does it: the touchpad/OK key on the glasses (key=True), or the
    debug broadcast. Returns the BB_SCAN fields, or None if no line appeared."""
    g.logcat_clear()
    if key:
        g.shell("input keyevent KEYCODE_DPAD_CENTER")
    else:
        g.shell("am broadcast -a com.baybin.glass.SCAN -p %s" % GLASS_PKG)
    m = g.wait_log(SCAN_RX, timeout=timeout, filters=GLASS_LOG)
    if not m:
        return None
    keys = ("id", "outcome", "open", "shot", "send", "bytes", "total")
    return {k: (v if k == "outcome" else int(v)) for k, v in zip(keys, m.groups())}


def phone_result(p, req_id, timeout=3):
    """The BB_RESULT line the phone logged for a scan id (item, p, model ms, lines)."""
    m = p.wait_log(r"BB_RESULT id=%d kind=(\d+) item=(\S+) bin=(\S+) p=(\S+) calls=(\d+) prep=(-?\d+) model=(-?\d+) "
                   r"phone=(\d+) sent=(\S+) \| (.*) \| (.*)$" % req_id, timeout=timeout, filters=PHONE_LOG)
    if not m:
        return None
    keys = ("kind", "item", "bin", "p", "calls", "prep", "model", "phone", "sent", "line1", "line2")
    return dict(zip(keys, m.groups()))


def glass_pid(g):
    return g.pid(GLASS_PKG)


def phone_pid(p):
    return p.pid(PHONE_PKG)


def crash_lines(dev, pkg):
    out = dev.run("logcat", "-d", "-v", "time", "-b", "crash")
    bad = [l for l in out.splitlines() if pkg in l or "FATAL EXCEPTION" in l]
    main = dev.run("logcat", "-d", "-v", "time", "-b", "system")
    bad += [l for l in main.splitlines() if ("ANR in " + pkg) in l]
    return bad


def push_private(p, local_path, name):
    """Copies a file into the phone app's private files/eval folder (run-as; nothing in shared storage)."""
    with open(local_path, "rb") as f:
        data = f.read()
    cmd = [ADB, "-s", p.serial, "exec-in", "run-as", PHONE_PKG, "sh", "-c",
           "mkdir -p files/eval && cat > files/eval/%s" % name]
    r = subprocess.run(cmd, input=data, capture_output=True, timeout=60)
    if r.returncode != 0:
        raise RuntimeError("push %s failed: %s" % (name, r.stderr.decode(errors="replace")))


def clear_private(p):
    p.shell("run-as %s rm -rf files/eval" % PHONE_PKG)


def classify_private(p, name, timeout=20):
    """Runs the phone pipeline on files/eval/<name> (gallery test mode hook). Returns the parsed BB_GALLERY line."""
    p.logcat_clear()
    p.shell("am broadcast -a com.baybin.phone.CLASSIFY -p %s --es file %s" % (PHONE_PKG, name))
    m = p.wait_log(r"BB_GALLERY file=%s city=(\S+) kind=(\d+) item=(\S+) bin=(\S+) p=(\S+) calls=(\d+) prep=(-?\d+) "
                   r"model=(-?\d+) total=(\d+) \| (.*) \| (.*)$" % re.escape(name), timeout=timeout, filters=PHONE_LOG)
    if not m:
        return None
    keys = ("city", "kind", "item", "bin", "p", "calls", "prep", "model", "total", "line1", "line2")
    return dict(zip(keys, m.groups()))


def glasses_screenshot(g, name):
    os.makedirs(OUT, exist_ok=True)
    return g.screenshot(os.path.join(OUT, name))


def expected_line1(cities, city, item):
    """What the lens should say for this item in this city, from the official-source rules."""
    rule = cities[city]["rules"].get(item)
    if rule is None or rule["bin"] == "unknown":
        return "Not sure — check city guide"
    return cities[city]["bins"][rule["bin"]]["line1"]


def load_rules():
    cities = {}
    for c in ("cupertino", "san_jose"):
        with open(os.path.join(ROOT, "rules", c + ".json"), encoding="utf-8") as f:
            cities[c] = json.load(f)
    return cities


# ---- the long run shared by accept_3 (memory), accept_4 (leak) and accept_5 (crashes) ----

def long_run(n=100, reuse_minutes=0):
    """n scans by key press with the phone connected; glasses PSS after every scan.

    Saved as tools/out/accept/run_<n>.json. With reuse_minutes > 0 a saved run that recent
    is returned instead of scanning again (the three criteria are judged on one run)."""
    from adbutil import GLASS_PKG as G
    prev = load("run_%d" % n)
    if prev and reuse_minutes > 0:
        age = time.time() - time.mktime(time.strptime(prev["when"], "%Y-%m-%d %H:%M:%S"))
        if age < reuse_minutes * 60:
            print("(using the %d-scan run from %s)" % (n, prev["when"]))
            return prev
    g, p = both()
    if not ensure_link(g, p):
        raise SystemExit("phone app is not connected to the glasses app")
    for d in (g, p):
        d.run("logcat", "-b", "crash", "-c")
    pids = {"glasses": glass_pid(g), "phone": phone_pid(p)}
    pss0 = g.pss_kb(G)
    rows = []
    t_start = time.time()
    for i in range(n):
        r = scan(g, key=True) or {"outcome": "no_line", "total": -1}
        r["pss_kb"] = g.pss_kb(G)
        if i % 10 == 9:
            r["phone_pss_kb"] = p.pss_kb(PHONE_PKG)
        rows.append(r)
        print("  #%3d %-12s total %5s ms  glasses PSS %s KB" % (i + 1, r["outcome"], r["total"], r["pss_kb"]))
        time.sleep(1.0)
    data = {
        "n": n, "minutes": round((time.time() - t_start) / 60, 1),
        "pids_before": pids, "pids_after": {"glasses": glass_pid(g), "phone": phone_pid(p)},
        "pss_before_kb": pss0, "rows": rows,
        "crash_glasses": crash_lines(g, G), "crash_phone": crash_lines(p, PHONE_PKG),
    }
    return save("run_%d" % n, data)
