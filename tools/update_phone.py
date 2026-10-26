"""Update the production phone APK without uninstalling, clearing data or granting permissions.

Verifies version, application identity, first-install time and installed APK hash.
An install rejection never falls back to uninstalling the existing app.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re

from adbutil import ROOT, phone
from acceptlib import code_hash


def package_info(text):
    version = re.search(r"\bversionName=([^\s]+)", text)
    identity = re.search(r"\b(?:userId|appId)=(\d+)", text)
    first = re.search(r"(?m)^\s*firstInstallTime=([^\r\n]+)", text)
    if not all((version, identity, first)):
        raise RuntimeError("Cannot verify installed application identity")
    return {"version": version.group(1), "identity": identity.group(1), "first_install": first.group(1).strip()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, default=Path(ROOT) / "phone-app/build/outputs/apk/release/phone-app-release.apk")
    args = parser.parse_args()
    metadata = json.loads(args.apk.with_name("output-metadata.json").read_text(encoding="utf-8"))
    package = metadata["applicationId"]
    if package != "com.baybin.phone":
        parser.error("Expected the production phone package")
    expected_version = metadata["elements"][0]["versionName"]
    expected_hash = hashlib.sha256(args.apk.read_bytes()).hexdigest()
    p = phone()
    before = package_info(p.shell("dumpsys package " + package))
    report = {"before": before, "expected_version": expected_version, "apk_sha256": expected_hash,
              "source_hash": code_hash(), "state": "pending", "permissions_auto_granted": False}
    output = Path(ROOT) / "tools/out/phone_update.json"

    def save():
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")

    save()
    try:
        print("Updating %s to %s; retaining existing app data" % (before["version"], expected_version), flush=True)
        p.run("install", "-r", str(args.apk.resolve()), check=True, timeout=120)
        after = package_info(p.shell("dumpsys package " + package))
        report["after"] = after
        if after["version"] != expected_version or any(before[k] != after[k] for k in ("identity", "first_install")):
            raise RuntimeError("Version or retained application identity verification failed")
        remote = p.shell("pm path " + package).strip().removeprefix("package:")
        if not re.fullmatch(r"/data/app/[A-Za-z0-9_+/.=~\-]+\.apk", remote):
            raise RuntimeError("Unexpected installed APK path")
        installed_hash = p.shell("sha256sum " + remote).split()
        if not installed_hash or installed_hash[0] != expected_hash:
            raise RuntimeError("Installed APK does not match the requested build")
        report["data_identity_preserved"] = True
        report["installed_hash_verified"] = True
        p.shell("am start -W -n " + package + "/.MainActivity", check=True)
        pid = p.pid(package)
        report["process_started"] = bool(pid)
        if pid:
            log = p.run("logcat", "-d", "--pid=" + str(pid), "-v", "brief", "BayBinPhone:I", "AndroidRuntime:E", "*:S")
            report["saved_key_seen_in_startup_log"] = "Qwen: key on this phone" in log
            report["startup_fatal_exception_seen"] = "FATAL EXCEPTION" in log
            if report["startup_fatal_exception_seen"]:
                raise RuntimeError("A fatal exception was observed after updating")
        report["locked"] = "isKeyguardShowing=true" in p.shell("dumpsys window")
        report["state"] = "installed" if report["process_started"] else "startup_unverified"
        print(json.dumps({k: report[k] for k in ("state", "data_identity_preserved", "installed_hash_verified", "locked")}))
    except Exception as error:
        report["state"] = "failed"
        report["error"] = type(error).__name__
        raise
    finally:
        save()


if __name__ == "__main__":
    main()
