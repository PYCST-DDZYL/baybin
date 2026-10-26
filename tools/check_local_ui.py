"""Tap the no-key phone UI in a fresh disposable review package; never use production data.

Requires the APK produced by tools/review.init.gradle and an unlocked ADB phone.
No camera capture, photo upload, API key or system-setting changes are performed.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import time
import xml.etree.ElementTree as ET

from adbutil import ROOT, phone
from acceptlib import code_hash


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", required=True, type=Path)
    args = parser.parse_args()
    metadata = json.loads(args.apk.with_name("output-metadata.json").read_text(encoding="utf-8"))
    package = metadata["applicationId"]
    if not re.fullmatch(r"com\.baybin\.phone\.review[a-z0-9]*", package):
        parser.error("Only a disposable review APK is allowed")
    p = phone()
    if p.shell("pm path " + package).strip():
        parser.error("Review package already installed; refusing to replace existing data")
    report = {"checkout_hash": code_hash(), "apk_sha256": hashlib.sha256(args.apk.read_bytes()).hexdigest(),
              "package": package, "checks": [], "mode": "no-key touch UI; no photo accuracy or camera capture"}
    output = Path(ROOT) / "tools/out/local_ui.json"
    installed = False
    try:
        p.run("install", "-t", str(args.apk.resolve()), check=True)
        installed = True
        p.shell("am start -W -n " + package + "/com.baybin.phone.MainActivity", check=True)
        p.shell("am broadcast -a com.baybin.phone.LANG -p " + package + " --es id en", check=True)
        time.sleep(0.5)

        def nodes():
            xml = p.ui_xml()
            start = xml.find("<hierarchy")
            if start < 0:
                raise RuntimeError("No UI hierarchy; unlock the phone")
            root = ET.fromstring(xml[start:])
            if not any(n.get("package") == package for n in root.iter("node")):
                raise RuntimeError("Review UI not visible; unlock the phone without changing security settings")
            return list(root.iter("node"))

        def by_id(name):
            return lambda n: n.get("resource-id", "").endswith(":id/" + name)

        def by_text(text):
            return lambda n: n.get("text", "").casefold() == text.casefold()

        def swipe(down=False):
            root_bounds = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", nodes()[0].get("bounds", ""))
            if not root_bounds:
                raise RuntimeError("No app viewport bounds")
            x1, y1, x2, y2 = map(int, root_bounds.groups())
            x = (x1 + x2) // 2
            upper, lower = y1 + (y2 - y1) * 3 // 10, y1 + (y2 - y1) * 8 // 10
            start, end = (upper, lower) if down else (lower, upper)
            p.shell("input swipe %d %d %d %d 350" % (x, start, x, end), check=True)

        def find(predicate, scroll=False):
            for attempt in range(6 if scroll else 2):
                for node in nodes():
                    if predicate(node) and node.get("bounds") != "[0,0][0,0]":
                        return node
                if scroll:
                    swipe()
                else:
                    time.sleep(0.3)
            raise RuntimeError("Expected control not visible")

        def tap(predicate, scroll=False):
            node = find(predicate, scroll)
            bounds = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
            if not bounds:
                raise RuntimeError("Invalid control bounds")
            x1, y1, x2, y2 = map(int, bounds.groups())
            p.shell("input tap %d %d" % ((x1 + x2) // 2, (y1 + y2) // 2), check=True)
            time.sleep(0.3)

        def passed(name):
            report["checks"].append({"name": name, "pass": True})
            print("PASS " + name, flush=True)

        find(by_id("text_search"))
        passed("fresh launch shows free lookup without a Bluetooth permission dialog")
        assert not any(n.get("class") == "android.widget.EditText" for n in nodes())
        passed("home offers photo setup without a password entry form")
        photo = Path(ROOT) / "tools/out/local_ui_home.png"
        p.screenshot(str(photo))
        report["home_screenshot"] = str(photo.relative_to(ROOT))
        tap(by_id("settings"))
        tap(by_id("service_housing"))
        tap(by_text("Single-family home"))
        tap(by_id("service_collection"))
        tap(by_text("This city's residential collection service"))
        p.shell("input keyevent KEYCODE_BACK", check=True)
        find(by_text("Unsaved changes"))
        tap(by_text("Keep editing"))
        find(by_text("Single-family home"))
        passed("back protects an unsaved service profile")
        tap(by_text("Save service area"), scroll=True)
        find(by_text("Save service area"))
        passed("saving service scope keeps Settings open")
        tap(by_id("key_input"), scroll=True)
        p.shell("input text unsaved-test-draft", check=True)
        p.shell("input keyevent KEYCODE_BACK", check=True)  # keyboard
        p.shell("input keyevent KEYCODE_BACK", check=True)
        find(by_text("Unsaved changes"))
        tap(by_text("Keep editing"))
        assert find(by_id("key_input"), scroll=True).get("text"), "Draft input was lost"
        p.shell("input keyevent KEYCODE_BACK", check=True)
        find(by_text("Unsaved changes"))
        tap(by_text("Discard changes"))
        passed("key draft can be kept or explicitly discarded without saving a key")
        time.sleep(0.3)
        tap(by_id("text_search"))
        tap(lambda n: n.get("class") == "android.widget.EditText")
        p.shell("input text cup%spaper", check=True)
        p.shell("input keyevent KEYCODE_BACK", check=True)
        time.sleep(0.3)
        tap(by_text("Paper coffee cup"))
        find(by_text("Is there still food in the container?"))
        passed("reordered query finds paper cup and opens an evidence question")
        tap(by_text("No food (empty drinks first)"))
        title = find(by_id("line1"), scroll=True).get("text", "")
        assert title == "Black cart", title
        passed("touch answer displays the San Jose paper-cup bin")
        photo = Path(ROOT) / "tools/out/local_ui_result.png"
        # nodes() already verified this is the disposable app, not a personal screen.
        nodes()
        p.screenshot(str(photo))
        report["result_screenshot"] = str(photo.relative_to(ROOT))
        p.shell("am start -W -n " + package + "/com.baybin.phone.MainActivity", check=True)
        # Return to the top of the app's own page; do not change device settings.
        swipe(down=True)
        tap(by_id("phone_camera"))
        find(by_text("Use text lookup"))
        passed("camera without a key offers the free text path")
        tap(by_text("Use text lookup"))
        find(lambda n: "Offline lookup" in n.get("text", ""))
        passed("no-key camera dialog opens offline lookup")
        query = find(lambda n: n.get("class") == "android.widget.EditText")
        assert query.get("text") == "cup paper", query.get("text")
        passed("reopening lookup retains the previous query")
        tap(lambda n: n.get("class") == "android.widget.EditText")
        p.shell("input keyevent KEYCODE_MOVE_END", check=True)
        p.shell("input keyevent " + " ".join(["67"] * len(query.get("text", ""))), check=True)
        p.shell("input text takeout%splastic", check=True)
        p.shell("input keyevent KEYCODE_BACK", check=True)
        tap(by_text("Plastic take-out container"))
        tap(by_text("No food (empty drinks first)"))
        find(by_text("Read the number; COMPOSTABLE / PLA overrides it"))
        tap(by_text("Clearly marked #5"))
        assert find(by_id("line1"), scroll=True).get("text") == "Blue cart"
        passed("food confirmation automatically advances to the required material question")
        p.shell("am broadcast -a com.baybin.phone.LOOKUP -p " + package + " --es id battery", check=True)
        find(by_id("disposal_destination"), scroll=True)
        note = find(by_id("disposal_note"), scroll=True).get("text", "")
        assert "Link checked: 2026-10-25" in note, note
        passed("battery result exposes official booking and its checked date")
        photo = Path(ROOT) / "tools/out/local_ui_special.png"
        nodes()
        p.screenshot(str(photo))
        report["special_screenshot"] = str(photo.relative_to(ROOT))
        pid = p.pid(package)
        assert "FATAL EXCEPTION" not in p.run("logcat", "-d", "--pid=" + str(pid), "-v", "brief", "AndroidRuntime:E", "*:S")
        report["pass"] = True
    except Exception as error:
        report["pass"] = False
        report["error"] = str(error)
        if installed:
            try:
                xml = p.ui_xml()
                if package in xml:
                    (Path(ROOT) / "tools/out/local_ui_failure.xml").write_text(xml, encoding="utf-8")
                    p.screenshot(str(Path(ROOT) / "tools/out/local_ui_failure.png"))
            except Exception as evidence_error:
                report["evidence_error"] = str(evidence_error)
        raise
    finally:
        if installed:
            p.run("uninstall", package, check=True)
            report["removed"] = not bool(p.shell("pm path " + package).strip())
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps({"pass": report.get("pass"), "checks": len(report["checks"]), "removed": report.get("removed")}))


if __name__ == "__main__":
    main()
