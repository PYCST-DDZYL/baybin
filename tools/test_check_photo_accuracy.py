"""A rejected review install must never uninstall a package it did not install."""
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import Mock, patch

import check_photo_accuracy


class ReviewInstallSafetyTest(unittest.TestCase):
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
