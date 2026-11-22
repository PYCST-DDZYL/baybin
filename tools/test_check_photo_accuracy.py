"""A rejected review install must never uninstall a package it did not install."""
import json
import io
from contextlib import redirect_stderr
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import Mock, patch

import check_photo_accuracy


class ReviewInstallSafetyTest(unittest.TestCase):
    def test_unknown_item_label_is_rejected_before_phone_access(self):
        with TemporaryDirectory(dir=Path(check_photo_accuracy.ROOT)) as folder:
            root = Path(folder)
            apk = root / "review.apk"
            apk.write_bytes(b"test apk")
            apk.with_name("output-metadata.json").write_text(json.dumps({
                "applicationId": "com.baybin.phone.reviewtest", "elements": [{"versionName": "test"}]
            }), encoding="utf-8")
            (root / "rules").mkdir()
            (root / "rules/items.json").write_text(json.dumps({"items": [{"id": "plastic_bag"}]}), encoding="utf-8")
            sample = root / "sample"
            sample.mkdir()
            (sample / "one.jpg").write_bytes(b"photo")
            (sample / "manifest.json").write_text(json.dumps({
                "photos": [{"file": "one.jpg", "item": "made_up_item"}]
            }), encoding="utf-8")
            errors = io.StringIO()
            with patch.object(check_photo_accuracy, "ROOT", str(root)), \
                 patch.object(check_photo_accuracy, "phone") as phone, \
                 patch("sys.argv", ["check_photo_accuracy", "--apk", str(apk), "--set", str(sample)]), \
                 redirect_stderr(errors):
                with self.assertRaises(SystemExit):
                    check_photo_accuracy.main()
            self.assertIn("Unknown catalog item", errors.getvalue())
            phone.assert_not_called()

    def test_empty_photo_is_rejected_before_phone_access(self):
        with TemporaryDirectory(dir=Path(check_photo_accuracy.ROOT)) as folder:
            root = Path(folder)
            apk = root / "review.apk"
            apk.write_bytes(b"test apk")
            apk.with_name("output-metadata.json").write_text(json.dumps({
                "applicationId": "com.baybin.phone.reviewtest",
                "elements": [{"versionName": "test"}],
            }), encoding="utf-8")
            sample = root / "sample"
            sample.mkdir()
            (root / "rules").mkdir()
            (root / "rules/items.json").write_text(json.dumps({"items": [{"id": "plastic_bag"}]}), encoding="utf-8")
            (sample / "empty.jpg").write_bytes(b"")
            (sample / "manifest.json").write_text(json.dumps({
                "photos": [{"file": "empty.jpg", "item": "plastic_bag"}],
            }), encoding="utf-8")
            errors = io.StringIO()
            with patch.object(check_photo_accuracy, "ROOT", str(root)), \
                 patch.object(check_photo_accuracy, "phone") as phone, \
                 patch("sys.argv", ["check_photo_accuracy", "--apk", str(apk), "--set", str(sample)]), \
                 redirect_stderr(errors):
                with self.assertRaises(SystemExit):
                    check_photo_accuracy.main()
            self.assertIn("empty", errors.getvalue())
            phone.assert_not_called()

    def test_one_shot_key_removal_requires_successful_device_check(self):
        device = Mock()
        check_photo_accuracy.verify_key_consumed(device, "com.baybin.phone.reviewtest")
        self.assertIn("test ! -e files/qwen.key", device.shell.call_args.args[0])
        self.assertTrue(device.shell.call_args.kwargs["check"])

    def test_review_log_read_failure_is_not_treated_as_no_reply(self):
        device = Mock()
        device.run.side_effect = RuntimeError("logcat unavailable")
        with self.assertRaisesRegex(RuntimeError, "logcat unavailable"):
            check_photo_accuracy.review_log(device, 123)
        self.assertTrue(device.run.call_args.kwargs["check"])

    def test_report_target_cannot_overwrite_apk_or_photo_set(self):
        with TemporaryDirectory(dir=Path(check_photo_accuracy.ROOT)) as folder:
            root = Path(folder)
            sample = root / "eval"
            sample.mkdir()
            apk = root / "review.apk"
            apk.write_bytes(b"apk")
            for target in (apk, sample / "manifest.json", root / "tools/check_photo_accuracy.py"):
                with self.subTest(target=target), self.assertRaisesRegex(ValueError, "overwrite"):
                    check_photo_accuracy.verify_report_target(target, apk, sample, root)
            check_photo_accuracy.verify_report_target(root / "tools/out/report.json", apk, sample, root)

    def test_install_failure_does_not_uninstall_review_package(self):
        with TemporaryDirectory(dir=Path(check_photo_accuracy.ROOT)) as folder:
            root = Path(folder)
            apk = root / "review.apk"
            apk.write_bytes(b"test apk")
            apk.with_name("output-metadata.json").write_text(json.dumps({
                "applicationId": "com.baybin.phone.reviewtest",
                "elements": [{"versionName": "test"}],
            }), encoding="utf-8")
            sample = root / "sample"
            sample.mkdir()
            (root / "rules").mkdir()
            (root / "rules/items.json").write_text(json.dumps({"items": [{"id": "plastic_bag"}]}), encoding="utf-8")
            (sample / "one.jpg").write_bytes(b"test photo")
            (sample / "manifest.json").write_text(json.dumps({
                "photos": [{"file": "one.jpg", "item": "plastic_bag"}],
            }), encoding="utf-8")
            (root / "local.properties").write_text("qwen.apiKey=sk-test-key-placeholder-long-enough\n", encoding="utf-8")
            device = Mock()
            device.shell.return_value = ""
            device.run.side_effect = RuntimeError("install rejected")
            with patch.object(check_photo_accuracy, "ROOT", str(root)), \
                 patch.object(check_photo_accuracy, "phone", return_value=device), \
                 patch("sys.argv", ["check_photo_accuracy", "--apk", str(apk), "--set", str(sample)]):
                with self.assertRaisesRegex(RuntimeError, "install rejected"):
                    check_photo_accuracy.main()
            self.assertEqual("install", device.run.call_args.args[0])
            self.assertFalse(any(call.args and call.args[0] == "uninstall" for call in device.run.call_args_list))


if __name__ == "__main__":
    unittest.main()
