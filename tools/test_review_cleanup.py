import unittest
from unittest.mock import Mock

from review_cleanup import remove_review


class ReviewCleanupTest(unittest.TestCase):
    def test_success_requires_verified_absence(self):
        device = Mock()
        device.shell.return_value = ""
        report = {"pass": True}
        self.assertIsNone(remove_review(device, "com.baybin.phone.reviewtest", report))
        self.assertTrue(report["removed"])
        device.shell.assert_called_once_with("pm path com.baybin.phone.reviewtest", check=True)

    def test_failed_uninstall_cannot_leave_a_passed_report(self):
        device = Mock()
        device.run.side_effect = RuntimeError("uninstall rejected")
        report = {"pass": True}
        self.assertEqual("uninstall rejected", remove_review(device, "com.baybin.phone.reviewtest", report))
        self.assertFalse(report["removed"])
        self.assertFalse(report["pass"])

    def test_remaining_package_cannot_be_reported_as_removed(self):
        device = Mock()
        device.shell.return_value = "package:/data/app/review/base.apk"
        report = {"pass": True}
        self.assertEqual("Review package is still installed", remove_review(device, "com.baybin.phone.reviewtest", report))
        self.assertFalse(report["removed"])
        self.assertFalse(report["pass"])


if __name__ == "__main__":
    unittest.main()
