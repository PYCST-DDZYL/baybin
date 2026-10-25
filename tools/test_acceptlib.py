"""Device-free regression tests for phone cleanup and truthful acceptance reporting."""
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import Mock, patch

import acceptlib
import accept_all


class PhoneTestSafety(unittest.TestCase):
    def test_report_preserves_brief_homepage_and_updates_detailed_results(self):
        with TemporaryDirectory() as folder:
            root = Path(folder)
            (root / "docs").mkdir()
            homepage = "# BayBin\n\nShort introduction.\n"
            (root / "README.md").write_text(homepage, encoding="utf-8")
            details = root / "docs" / "TECHNICAL_DETAILS.md"
            details.write_text("# Details\n<!-- RESULTS -->\nOld table\n<!-- /RESULTS -->\n", encoding="utf-8")
            with patch.object(accept_all, "ROOT", folder), \
                 patch.object(accept_all, "load", return_value=None), patch("builtins.print"):
                accept_all.report()
            self.assertEqual(homepage, (root / "README.md").read_text(encoding="utf-8"))
            self.assertNotIn("Old table", details.read_text(encoding="utf-8"))
            self.assertIn("| 8 |", details.read_text(encoding="utf-8"))
            self.assertTrue((root / "docs" / "acceptance.md").is_file())

    def test_unsafe_private_filename_is_rejected_before_reading_or_pushing(self):
        for name in ("../photo.jpg", "x;touch.jpg", "x y.jpg", "..", "/sdcard/x.jpg"):
            with self.assertRaises(ValueError):
                acceptlib.push_private(Mock(), "nonexistent.jpg", name)

    def test_cleanup_failure_is_reported(self):
        phone = Mock()
        phone.shell.side_effect = RuntimeError("run-as refused")
        with self.assertRaisesRegex(RuntimeError, "run-as refused"):
            acceptlib.clear_private(phone)
        self.assertTrue(phone.shell.call_args.kwargs["check"])

    def test_preferences_are_restored_even_when_a_test_raises(self):
        before = {"city": "berkeley", "lang": "en", "auto": "false", "link": "DISCONNECTED"}
        phone = Mock()
        with patch.object(acceptlib, "phone_status", side_effect=[before, before]), \
             patch.object(acceptlib, "set_city") as set_city:
            with self.assertRaisesRegex(ValueError, "test failed"):
                with acceptlib.preserve_phone(phone):
                    raise ValueError("test failed")
            set_city.assert_called_once_with(phone, "berkeley")
        restore = [c.args[0] for c in phone.shell.call_args_list if "RESTORE" in c.args[0]]
        self.assertIn("--ez auto false --ez connected false", restore[0])

    def test_failed_script_gets_a_visible_failure_detail(self):
        for criterion in range(1, 9):
            self.assertEqual("Script failed", accept_all.detail(criterion, {"pass": False, "error": "Script failed"}))

    def test_expected_failure_preserves_fresh_measurements(self):
        def result(name):
            return {"pass": name != "2_accuracy", "code_hash": "current", "sets": {"sample": {"n": 51}}}

        with patch.object(accept_all, "load", side_effect=result), \
             patch.object(accept_all, "save") as save, \
             patch.object(accept_all, "code_hash", return_value="current"), \
             patch.object(accept_all, "report"), \
             patch.object(accept_all.subprocess, "call", side_effect=[0, 1, 0, 0, 0, 0, 0, 0]), \
             patch.object(accept_all.os.path, "isfile", return_value=True), \
             patch.object(accept_all.os.path, "getmtime", return_value=100), \
             patch("time.time", return_value=100):
            self.assertFalse(accept_all.main([]))
            save.assert_not_called()


if __name__ == "__main__":
    unittest.main()
