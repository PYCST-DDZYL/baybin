"""Check no-key catalog, clarification and scope flows in a disposable Android package.

Requires an isolated debug APK built with applicationId com.baybin.phone.review*.
python tools/check_local_flows.py --apk tools/out/review-build/outputs/apk/debug/phone-app-debug.apk
Never installs over the production app. Does not test camera capture, photo accuracy or touch UI.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import time

from adbutil import ROOT, phone
from acceptlib import code_hash


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--output", type=Path, default=Path(ROOT) / "tools/out/local_flows.json")
    args = parser.parse_args()
    metadata = json.loads(args.apk.with_name("output-metadata.json").read_text(encoding="utf-8"))
    package = metadata["applicationId"]
    if not re.fullmatch(r"com\.baybin\.phone\.review[a-z0-9]*", package):
        parser.error("Only a disposable com.baybin.phone.review* APK is allowed")
    p = phone()
    if p.shell("pm path " + package).strip():
        parser.error("Disposable package already exists; refusing to replace or remove existing data")
    report = {"checkout_hash": code_hash(), "apk_sha256": hashlib.sha256(args.apk.read_bytes()).hexdigest(),
              "package": package, "version": metadata["elements"][0]["versionName"],
              "mode": "explicit user catalog choices; no API key; not photo accuracy or UI acceptance", "checks": []}
    installed = False
    try:
        p.run("install", "-t", str(args.apk.resolve()), check=True)
        installed = True
        p.shell("am start -W -n " + package + "/com.baybin.phone.MainActivity", check=True)
        pid = p.pid(package)
        if not pid:
            raise RuntimeError("Isolated app did not start")

        def log():
            return p.run("logcat", "-d", "--pid=" + str(pid), "-v", "brief", "BayBinPhone:I", "AndroidRuntime:E", "*:S")

        def broadcast(action, extras=""):
            p.shell("am broadcast -a com.baybin.phone." + action + " -p " + package + " " + extras, check=True)

        def result(action, extras, marker):
            before = len(re.findall(marker, log()))
            broadcast(action, extras)
            end = time.monotonic() + 5
            while time.monotonic() < end:
                lines = [line for line in log().splitlines() if marker in line]
                if len(lines) > before:
                    line = lines[-1]
                    found = re.search(r"kind=(\d+) item=(\S+) bin=(\S+) p=(\S+) calls=(\d+)", line)
                    if not found:
                        raise RuntimeError("Malformed local result")
                    kind, item, bin_name, probability, calls = found.groups()
                    if probability != "null" or calls != "0":
                        raise RuntimeError("Manual lookup invented confidence or used cloud calls")
                    return dict(kind=int(kind), item=item, bin=bin_name)
                time.sleep(0.1)
            raise RuntimeError("No result from " + action)

        broadcast("CITY", "--es id san_jose")
        blocked = result("LOOKUP", "--es id plastic_bag", "BB_LOOKUP")
        assert blocked["kind"] == 1 and blocked["bin"] == "null", blocked
        report["checks"].append({"name": "unset profile blocks definite answers", "pass": True})
        broadcast("PROFILE", "--es housing APARTMENT --ez city_service true")
        blocked = result("LOOKUP", "--es id plastic_bag", "BB_LOOKUP")
        assert blocked["kind"] == 1 and blocked["bin"] == "null", blocked
        report["checks"].append({"name": "apartment does not borrow single-family rules", "pass": True})
        broadcast("PROFILE", "--es housing SINGLE_FAMILY --ez city_service false")
        blocked = result("LOOKUP", "--es id plastic_bag", "BB_LOOKUP")
        assert blocked["kind"] == 1 and blocked["bin"] == "null", blocked
        report["checks"].append({"name": "unconfirmed collection service blocks definite answers", "pass": True})
        broadcast("PROFILE", "--es housing SINGLE_FAMILY --ez city_service true")
        current_city = "san_jose"

        def scenario(item, answers, want, kind=0):
            got = result("LOOKUP", "--es id " + item, "BB_LOOKUP")
            for answer in answers:
                assert got["kind"] == 1, (item, got)
                got = result("ANSWER", "--es value " + answer, "BB_CLARIFY")
            assert got["kind"] == kind and (kind != 0 or got["bin"] == want), (item, got, want)
            report["checks"].append({"name": item, "city": current_city, "answers": answers,
                                     "want_kind": kind, "want": want, "got": got, "pass": True})

        cases = [
            ("plastic_takeout", ["none", "5"], "recycling"),
            ("plastic_takeout", ["none", "6"], "landfill"),
            ("plastic_takeout", ["none", "unmarked"], "landfill"),
            ("plastic_takeout", ["none", "compostable"], "landfill"),
            ("plastic_clamshell", ["none", "1"], "recycling"),
            ("plastic_cup", ["none", "6"], "landfill"),
            ("paper_takeout", ["none"], "landfill"),
            ("paper_cup", ["none"], "landfill"),
            ("plastic_bag", [], "landfill"),
            ("cardboard_box", ["none", "soiled"], "landfill"),
            ("pizza_box_greasy", ["none", "soiled"], "landfill"),
            ("foam_food_container", ["none"], "landfill"),
            ("food_in_container", ["present"], "landfill"),
            ("compostable_plastic", ["none", "compostable"], "landfill"),
            ("frozen_food_box", ["none", "clean"], "recycling"),
            ("frozen_food_box", ["none", "soiled"], "landfill"),
            ("battery", [], "special"), ("bulb_fluorescent", [], "special"), ("phone_electronics", [], "special"),
            ("plastic_bottle", ["other"], "recycling"),
            ("plastic_jug", ["milk"], "recycling"),
            ("carton", ["milk"], "recycling"),
        ]
        for item, answers, want in cases:
            scenario(item, answers, want)
        for item in ("plastic_bottle", "plastic_jug", "carton"):
            for label in ("cleaner", "oil", "unknown"):
                scenario(item, [label], None, kind=1)

        current_city = "palo_alto"
        broadcast("CITY", "--es id " + current_city)
        blocked = result("LOOKUP", "--es id plastic_bag", "BB_LOOKUP")
        assert blocked["kind"] == 1 and blocked["bin"] == "null", blocked
        report["checks"].append({"name": "another city requires its own service confirmation", "pass": True})
        broadcast("PROFILE", "--es housing SINGLE_FAMILY --ez city_service true")
        scenario("carton", ["milk", "unknown"], None, kind=1)
        scenario("carton", ["milk", "gable"], "compost")
        scenario("carton", ["milk", "aseptic"], "garbage")
        for city, want in (("san_jose", "recycling"), ("palo_alto", "garbage")):
            got = result("REVIEW", "--es id " + city, "BB_REVIEW")
            assert got == dict(kind=0, item="aseptic_carton", bin=want), (city, got)
            report["checks"].append({"name": "city switch reapplies its own carton rule", "city": city,
                                     "want": want, "got": got, "pass": True})
        assert "FATAL EXCEPTION" not in log()
        report["pass"] = True
    except Exception as error:
        report["pass"] = False
        report["error"] = str(error)
        raise
    finally:
        if installed:
            p.run("uninstall", package, check=True)
            report["removed"] = not bool(p.shell("pm path " + package).strip())
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps({"pass": report.get("pass"), "checks": len(report["checks"]), "removed": report.get("removed")}, ensure_ascii=False))


if __name__ == "__main__":
    main()
