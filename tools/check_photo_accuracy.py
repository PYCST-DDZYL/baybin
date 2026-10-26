"""Measure first-pass photos in a disposable Android review package.

Uses the owner's local.properties Qwen key; uploads the selected public test photos.
Never replaces production, exports its key, clears global logs or changes user service settings.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time

from adbutil import ADB, ROOT, phone
from acceptlib import code_hash, expected_line1, load_rules
from focus_report import metrics


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--set", type=Path, default=Path(ROOT) / "eval/realworld")
    parser.add_argument("--limit", type=int)
    parser.add_argument("--start-index", type=int, default=0, help="Zero-based manifest offset for diagnostic subsets")
    parser.add_argument("--output", type=Path, default=Path(ROOT) / "tools/out/photo_accuracy.json")
    args = parser.parse_args()
    metadata = json.loads(args.apk.with_name("output-metadata.json").read_text(encoding="utf-8"))
    package = metadata["applicationId"]
    if not re.fullmatch(r"com\.baybin\.phone\.review[a-z0-9]*", package):
        parser.error("Only a disposable review package is allowed")
    manifest = json.loads((args.set / "manifest.json").read_text(encoding="utf-8"))
    if args.start_index < 0 or args.start_index >= len(manifest["photos"]):
        parser.error("start-index is outside the manifest")
    photos = manifest["photos"][args.start_index:]
    if args.limit is not None:
        if args.limit < 1:
            parser.error("limit must be positive")
        photos = photos[:args.limit]
    for row in photos:
        src = (args.set / row["file"]).resolve()
        if not src.is_relative_to(args.set.resolve()) or not src.is_file():
            parser.error("Missing photo or invalid manifest path")
    props = {}
    for line in (Path(ROOT) / "local.properties").read_text(encoding="utf-8").splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            k, v = line.split("=", 1)
            props[k.strip()] = v.strip()
    key = props.get("qwen.apiKey", "")
    if not key or len(key) < 20:
        parser.error("No configured local test key")
    p = phone()
    if p.shell("pm path " + package).strip():
        parser.error("Review package exists; refusing to overwrite existing data")
    rows = []
    report = {"when": datetime.now(timezone.utc).isoformat(), "code_hash": code_hash(),
              "apk_sha256": hashlib.sha256(args.apk.read_bytes()).hexdigest(),
              "version": metadata["elements"][0]["versionName"], "package": package,
              "mode": "first-pass phone pipeline on public labelled photos; no user supplementation",
              "scope": "explicit test-only San Jose single-family city service; not the owner's service eligibility",
              "requested": len(photos), "sets": {args.set.name: {"results": {"san_jose": rows}}}, "pass": False}
    report["manifest_sha256"] = hashlib.sha256((args.set / "manifest.json").read_bytes()).hexdigest()
    report["locked_at_start"] = "isKeyguardShowing=true" in p.shell("dumpsys window")
    report["start_index"] = args.start_index
    report["foreground_test_broadcasts"] = True
    installed = False

    def save():
        report["metrics"] = metrics(rows)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    def stream(data, command):
        result = subprocess.run([ADB, "-s", p.serial, "exec-in", "run-as", package, "sh", "-c", command],
                                input=data, capture_output=True, timeout=60)
        if result.returncode:
            raise RuntimeError("Private review-package write failed")

    try:
        installed = True
        p.run("install", "-t", str(args.apk.resolve()), check=True, timeout=120)
        stream(key.encode(), "mkdir -p files && cat > files/qwen.key")
        key = None
        props.clear()
        p.shell("am start -W -n " + package + "/com.baybin.phone.MainActivity", check=True)
        if p.shell("run-as " + package + " ls files/qwen.key").strip():
            raise RuntimeError("One-shot key file was not removed after encrypted import")
        pid = p.pid(package)
        if not pid:
            raise RuntimeError("Review app did not start")

        def broadcast(action, extras):
            p.shell("am broadcast --receiver-foreground -a com.baybin.phone." + action + " -p " + package + " " + extras, check=True)

        def log():
            return p.run("logcat", "-d", "--pid=" + str(pid), "-v", "brief", "BayBinPhone:I", "AndroidRuntime:E", "*:S")

        broadcast("CITY", "--es id san_jose")
        broadcast("LANG", "--es id en")
        broadcast("PROFILE", "--es housing SINGLE_FAMILY --ez city_service true")
        rules = load_rules()
        for index, photo in enumerate(photos):
            name = "photo_%03d.jpg" % index
            data = (args.set / photo["file"]).read_bytes()
            photo_hash = hashlib.sha256(data).hexdigest()
            stream(data, "mkdir -p files/eval && cat > files/eval/" + name)
            copied_hash = p.shell("run-as " + package + " sha256sum files/eval/" + name).split()
            if not copied_hash or copied_hash[0] != photo_hash:
                raise RuntimeError("Private photo transfer hash mismatch; classification not started")
            broadcast("CLASSIFY", "--es file " + name)
            rx = re.compile(r"BB_GALLERY file=" + re.escape(name) + r" city=(\S+) kind=(\d+) item=(\S+) bin=(\S+) p=(\S+) calls=(\d+) prep=(-?\d+) model=(-?\d+) total=(\d+) \| (.*?) \| (.*)")
            end = time.monotonic() + 20
            found = None
            while time.monotonic() < end:
                text = log()
                if "FATAL EXCEPTION" in text:
                    raise RuntimeError("Review app crashed")
                found = rx.search(text)
                if found:
                    break
                time.sleep(0.15)
            p.shell("run-as " + package + " rm files/eval/" + name, check=True)
            row = {"file": photo["file"], "item": photo["item"], "want": expected_line1(rules, "san_jose", photo["item"]),
                   "got": "(no answer)", "kind": None, "model_ms": -1, "evidence": "photo", "photo_sha256": photo_hash}
            if found:
                city, kind, item, bin_name, probability, calls, prep, model, total, title, guidance = found.groups()
                if city != "san_jose":
                    raise RuntimeError("Photo was evaluated under an unexpected city")
                row.update(kind=kind, got_item=item, bin=bin_name, p=probability, calls=int(calls),
                           prep_ms=int(prep), model_ms=int(model), total_ms=int(total), got=title, guidance=guidance)
                if kind == "3":
                    if "BB_GALLERY file=" + name + " unreadable:" in text:
                        row["failure_stage"] = "input"
                    elif "BB_GALLERY file=" + name + " failed:" in text:
                        row["failure_stage"] = "processing"
                    else:
                        row["failure_stage"] = "service"
            row["ok"] = row["kind"] == "0" and row["want"] == row["got"]
            rows.append(row)
            save()
            print("%d/%d %s kind=%s %s" % (index + 1, len(photos), photo["item"], row["kind"], row["got"]), flush=True)
            if row["kind"] == "4" or (row["kind"] == "3" and row.get("failure_stage") == "service" and
                    any(message in row.get("guidance", "") for message in ("Check the API key", "Check the model configuration"))):
                raise RuntimeError("Recognition service/setup failed; stopped without uploading remaining photos")
        result = metrics(rows)
        report["completed"] = True
        report["pass"] = args.start_index == 0 and len(rows) >= 50 and result["success_rate"] >= 0.9 and result["wrong_bins"] == 0
    except Exception as error:
        report["error"] = str(error)
        raise
    finally:
        if installed:
            try:
                if p.shell("pm path " + package).strip():
                    p.run("uninstall", package, check=True)
                report["removed"] = not bool(p.shell("pm path " + package).strip())
            except Exception:
                report["removed"] = False
                report["cleanup_error"] = "Review uninstall could not be verified"
            if not report.get("removed"):
                report["pass"] = False
        save()
        print(json.dumps({k: report.get(k) for k in ("completed", "pass", "removed", "metrics")}))


if __name__ == "__main__":
    main()
