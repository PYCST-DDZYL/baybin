"""
Shared adb helpers for deploy.py / probe.py.

Two devices can be attached at once: the glasses (model RG_glasses) and the phone.
They are told apart by `adb devices -l`, so nobody has to type serials.
"""
import os
import re
import subprocess
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ADB = os.environ.get("BAYBIN_ADB", r"D:\Download\rokid adb\platform-tools\adb.exe")
GLASS_PKG = "com.baybin.glass"
PHONE_PKG = "com.baybin.phone"
GLASSES_MODEL = "RG_glasses"
# The ONLY place these tools may write on the user's phone (shows as Download › BayBin in
# the Files app, so it's easy to find and delete). Prefer writing nothing at all:
# `adb install` streams the APK and screenshots go straight to the PC (screenshot()).
PHONE_DIR = "/sdcard/Download/BayBin"

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


def devices():
    """[(serial, model)] of attached, authorised devices."""
    out = subprocess.run([ADB, "devices", "-l"], capture_output=True, text=True).stdout
    found = []
    for line in out.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            m = re.search(r"model:(\S+)", line)
            found.append((parts[0], m.group(1) if m else "?"))
    return found


def glasses_serial():
    for serial, model in devices():
        if model == GLASSES_MODEL:
            return serial
    return None


def phone_serial():
    for serial, model in devices():
        if model != GLASSES_MODEL:
            return serial
    return None


class Dev:
    """One device; every call is `adb -s <serial> ...`."""

    def __init__(self, serial, name):
        self.serial = serial
        self.name = name

    def run(self, *args, check=False, timeout=60):
        p = subprocess.run([ADB, "-s", self.serial] + list(args),
                           capture_output=True, text=True, encoding="utf-8",
                           errors="replace", timeout=timeout)
        if check and p.returncode != 0:
            raise RuntimeError("adb %s failed: %s%s" % (" ".join(args), p.stdout, p.stderr))
        return p.stdout

    def shell(self, cmd, **kw):
        return self.run("shell", cmd, **kw)

    def pid(self, pkg):
        out = self.shell("pidof %s" % pkg).strip()
        return int(out.split()[0]) if out else None

    def logcat_clear(self):
        self.run("logcat", "-c")

    def logcat(self, *filters):
        """Dump the current log buffer (no blocking)."""
        return self.run("logcat", "-d", "-v", "time", *filters)

    def wait_log(self, pattern, timeout=15.0, filters=("BayBin:I", "*:S")):
        """Poll logcat until a line matches; returns the regex match or None."""
        rx = re.compile(pattern)
        end = time.time() + timeout
        while time.time() < end:
            for line in self.logcat(*filters).splitlines():
                m = rx.search(line)
                if m:
                    return m
            time.sleep(0.3)
        return None

    def pss_kb(self, pkg):
        """TOTAL PSS of a package in KB, or None if it is not running."""
        out = self.shell("dumpsys meminfo %s" % pkg, timeout=30)
        m = re.search(r"TOTAL PSS:\s+(\d+)", out) or re.search(r"^\s*TOTAL\s+(\d+)", out, re.M)
        return int(m.group(1)) if m else None

    def screenshot(self, local_path):
        """PNG straight to the PC (exec-out); nothing is saved on the device."""
        png = subprocess.run([ADB, "-s", self.serial, "exec-out", "screencap", "-p"], capture_output=True).stdout
        with open(local_path, "wb") as f:
            f.write(png)
        return local_path

    def ui_xml(self):
        """Current screen's UI tree, printed to stdout (/dev/tty) so no window_dump.xml lands on the device."""
        out = self.run("exec-out", "uiautomator", "dump", "/dev/tty", timeout=30)
        return out[:out.rfind(">") + 1] if ">" in out else ""

    def crashes(self, pkg):
        """FATAL EXCEPTION / ANR lines mentioning pkg in the current log buffer."""
        out = self.run("logcat", "-d", "-v", "time", "-b", "crash,main,system")
        bad = []
        for line in out.splitlines():
            if pkg in line and ("FATAL EXCEPTION" in line or "ANR in" in line or "Process: " in line):
                bad.append(line)
        return bad


def glasses():
    s = glasses_serial()
    if not s:
        sys.exit("眼镜没连上 adb。先跑：\n    \"%s\" devices" % ADB)
    return Dev(s, "glasses")


def phone(required=True):
    s = phone_serial()
    if not s and required:
        sys.exit("手机没连上 adb（USB 调试或无线调试都行）。")
    return Dev(s, "phone") if s else None


def wake_glasses(g):
    """Screen on + our activity in front. Camera access is refused to background apps."""
    g.shell("input keyevent KEYCODE_WAKEUP")
    g.shell("am start -n %s/.MainActivity" % GLASS_PKG)
    time.sleep(0.8)
