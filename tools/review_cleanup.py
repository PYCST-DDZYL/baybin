"""Verify that a disposable review app was actually removed after a device check."""


def remove_review(device, package, report):
    try:
        device.run("uninstall", package, check=True)
        removed = not bool(device.shell("pm path " + package, check=True).strip())
    except Exception as error:
        removed = False
        report["cleanup_error"] = str(error)
    report["removed"] = removed
    if not removed:
        report["pass"] = False
        report.setdefault("cleanup_error", "Review package is still installed")
    return report.get("cleanup_error")
