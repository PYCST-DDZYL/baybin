"""Verify that a disposable review app was actually removed after a device check."""


def review_package_installed(device, package):
    """A missing package is normal; an ADB transport failure is not."""
    return bool(device.shell("pm path %s 2>/dev/null || true" % package, check=True).strip())


def remove_review(device, package, report):
    try:
        device.run("uninstall", package, check=True)
        removed = not review_package_installed(device, package)
    except Exception as error:
        removed = False
        report["cleanup_error"] = str(error)
    report["removed"] = removed
    if not removed:
        report["pass"] = False
        report.setdefault("cleanup_error", "Review package is still installed")
    return report.get("cleanup_error")
