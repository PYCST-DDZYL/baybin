"""Device-free regression tests for phone cleanup and truthful acceptance reporting."""
import unittest
import json
import subprocess
import time
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import Mock, patch

import acceptlib
import accept_all
import accept_2_accuracy


class PhoneTestSafety(unittest.TestCase):
    def test_phone_status_fails_when_status_request_is_rejected(self):
        device = Mock()
        device.shell.side_effect = RuntimeError("broadcast failed")
        with self.assertRaisesRegex(RuntimeError, "broadcast failed"):
            acceptlib.phone_status(device)
        self.assertTrue(device.shell.call_args.kwargs["check"])

    def test_city_change_fails_when_broadcast_is_rejected(self):
        device = Mock()
        device.shell.side_effect = RuntimeError("broadcast failed")
        with self.assertRaisesRegex(RuntimeError, "broadcast failed"):
            acceptlib.set_city(device, "san_jose")
        self.assertTrue(device.shell.call_args.kwargs["check"])

    def test_scan_trigger_failure_cannot_be_treated_as_a_slow_scan(self):
        for use_key in (True, False):
            device = Mock()
            device.shell.side_effect = RuntimeError("trigger failed")
            with self.subTest(use_key=use_key), self.assertRaisesRegex(RuntimeError, "trigger failed"):
                acceptlib.scan(device, key=use_key)
            self.assertTrue(device.shell.call_args.kwargs["check"])
            device.wait_log.assert_not_called()

    def test_classification_request_failure_is_reported(self):
        device = Mock()
        device.shell.side_effect = RuntimeError("classification broadcast failed")
        with self.assertRaisesRegex(RuntimeError, "classification broadcast failed"):
            acceptlib.classify_private(device, "photo.jpg")
        self.assertTrue(device.shell.call_args.kwargs["check"])
        device.wait_log.assert_not_called()

    def test_classification_rejects_shell_unsafe_name_before_device_work(self):
        device = Mock()
        for name in ("../photo.jpg", "photo;id", "photo name.jpg", None):
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, "simple basename"):
                acceptlib.classify_private(device, name)
        device.logcat_clear.assert_not_called()

    def test_city_commands_reject_shell_unsafe_ids(self):
        device = Mock()
        for city in ("san_jose;id", "../rules", "San Jose", None):
            with self.subTest(city=city), self.assertRaisesRegex(ValueError, "Invalid city ID"):
                acceptlib.set_city(device, city)
            with self.subTest(city=city), self.assertRaisesRegex(ValueError, "Invalid city ID"):
                acceptlib.review_private(device, city)
        device.shell.assert_not_called()

    def test_private_photo_transfer_rejects_corrupted_device_copy(self):
        with TemporaryDirectory() as folder:
            photo = Path(folder) / "photo.jpg"
            photo.write_bytes(b"photo bytes")
            device = Mock(serial="phone1")
            device.shell.return_value = "deadbeef  files/eval/photo.jpg"
            copied = subprocess.CompletedProcess([], 0, stdout=b"", stderr=b"")
            with patch.object(acceptlib.subprocess, "run", return_value=copied), \
                 self.assertRaisesRegex(RuntimeError, "transfer hash mismatch"):
                acceptlib.push_private(device, photo, "photo.jpg")
            self.assertTrue(device.shell.call_args.kwargs["check"])

    def test_photo_manifests_cannot_select_files_outside_the_test_set(self):
        with TemporaryDirectory() as folder:
            root = Path(folder)
            sample = root / "sample"
            sample.mkdir()
            (sample / "photo.jpg").write_bytes(b"photo")
            outside = root / "outside.jpg"
            outside.write_bytes(b"private")
            manifest = sample / "manifest.json"
            for entry in ({"photos": [{"file": "photo.jpg", "item": "cup"}]},
                          {"cup": {"file": "photo.jpg"}}):
                manifest.write_text(json.dumps(entry), encoding="utf-8")
                self.assertEqual(str(sample / "photo.jpg"), accept_2_accuracy.load_set(str(sample))[0][0])
            for filename in ("../outside.jpg", str(outside), "missing.jpg"):
                manifest.write_text(json.dumps({"photos": [{"file": filename, "item": "cup"}]}), encoding="utf-8")
                with self.subTest(filename=filename), self.assertRaisesRegex(ValueError, "outside its test set"):
                    accept_2_accuracy.load_set(str(sample))

    def test_invalid_item_ids_are_rejected_before_device_work(self):
        with TemporaryDirectory() as folder:
            root = Path(folder)
            (root / "photo.jpg").write_bytes(b"photo")
            manifest = root / "manifest.json"
            for item in ("../private", "cup;rm", "cup paper", None):
                manifest.write_text(json.dumps({"photos": [{"file": "photo.jpg", "item": item}]}), encoding="utf-8")
                with self.subTest(item=item), self.assertRaisesRegex(ValueError, "Invalid test item ID"):
                    accept_2_accuracy.load_set(folder)

    def test_unsupported_accuracy_city_rejected_before_phone_launch(self):
        with TemporaryDirectory() as folder:
            root = Path(folder)
            (root / "photo.jpg").write_bytes(b"photo")
            (root / "manifest.json").write_text(json.dumps({"photos": [
                {"file": "photo.jpg", "item": "cup"} for _ in range(50)]}), encoding="utf-8")
            with patch.object(accept_2_accuracy, "phone") as phone, \
                 patch.object(accept_2_accuracy, "load_rules", return_value={"san_jose": {}}), \
                 self.assertRaises(SystemExit):
                accept_2_accuracy.main(["--set", folder, "--city", "invalid"])
            phone.assert_not_called()

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

    def test_report_marks_incomplete_saved_result_without_crashing(self):
        with TemporaryDirectory() as folder:
            root = Path(folder)
            incomplete = {"pass": True, "when": "today", "code_hash": "current"}
            with patch.object(accept_all, "ROOT", folder), \
                 patch.object(accept_all, "load", side_effect=lambda name: incomplete if name == "1_latency" else None), \
                 patch.object(accept_all, "code_hash", return_value="current"), patch("builtins.print"):
                accept_all.report()
            report = (root / "docs" / "acceptance.md").read_text(encoding="utf-8")
            self.assertIn("| 1 | Button to lens text ≤5 s | FAIL | Invalid saved result |", report)

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

    def test_crash_checks_require_successful_log_reads(self):
        device = Mock()
        device.run.side_effect = RuntimeError("adb logcat failed")
        with self.assertRaisesRegex(RuntimeError, "adb logcat failed"):
            acceptlib.crash_lines(device, "com.baybin.phone")
        self.assertTrue(device.run.call_args.kwargs["check"])

    def test_crash_log_clear_requires_adb_success(self):
        device = Mock()
        acceptlib.clear_crash_log(device)
        device.run.assert_called_once_with("logcat", "-b", "crash", "-c", check=True)

    def test_long_run_reuse_rejects_incomplete_or_future_results(self):
        stamp = "2026-11-11 12:00:00"
        now = time.mktime(time.strptime(stamp, "%Y-%m-%d %H:%M:%S"))
        previous = {"n": 2, "rows": [{}, {}], "code_hash": "current", "when": stamp}
        self.assertTrue(acceptlib.reusable_run(previous, 2, 10, "current", now + 30))
        self.assertFalse(acceptlib.reusable_run(previous, 2, 10, "current", now - 30))
        self.assertFalse(acceptlib.reusable_run({**previous, "rows": [{}]}, 2, 10, "current", now + 30))
        self.assertFalse(acceptlib.reusable_run({**previous, "n": 1}, 2, 10, "current", now + 30))

    def test_long_run_rejects_zero_scans_before_device_work(self):
        with patch.object(acceptlib, "both") as both:
            with self.assertRaisesRegex(ValueError, "positive scan count"):
                acceptlib.long_run(0)
            both.assert_not_called()

    def test_zero_timeout_city_review_returns_no_result(self):
        phone = Mock()
        self.assertEqual({}, acceptlib.review_all(phone, ["san_jose"], timeout=0))
        phone.logcat.assert_not_called()

    def test_link_setup_requires_successful_device_commands(self):
        phone = Mock()
        phone.shell.side_effect = RuntimeError("adb failed")
        with patch.object(acceptlib, "wake_glasses"):
            with self.assertRaisesRegex(RuntimeError, "adb failed"):
                acceptlib.ensure_link(Mock(), phone)
        self.assertTrue(phone.shell.call_args.kwargs["check"])

    def test_link_timeout_uses_elapsed_time(self):
        with patch.object(acceptlib, "wake_glasses"), patch.object(acceptlib.time, "monotonic", side_effect=[10, 10]), \
             patch.object(acceptlib.time, "time", side_effect=AssertionError("wall clock used")):
            self.assertFalse(acceptlib.ensure_link(Mock(), Mock(), timeout=0))

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

    def test_language_and_link_restore_continue_after_city_restore_failure(self):
        before = {"city": "berkeley", "lang": "en", "auto": "false", "link": "DISCONNECTED"}
        device = Mock()
        with patch.object(acceptlib, "phone_status", return_value=before), \
             patch.object(acceptlib, "set_city", side_effect=RuntimeError("city failed")):
            with self.assertRaisesRegex(RuntimeError, "city failed"):
                with acceptlib.preserve_phone(device):
                    pass
        commands = [call.args[0] for call in device.shell.call_args_list]
        self.assertTrue(any(".LANG" in command for command in commands))
        self.assertTrue(any(".RESTORE" in command for command in commands))

    def test_failed_script_gets_a_visible_failure_detail(self):
        for criterion in range(1, 9):
            self.assertEqual("Script failed", accept_all.detail(criterion, {"pass": False, "error": "Script failed"}))

    def test_unknown_acceptance_flag_cannot_start_device_runs(self):
        with patch.object(accept_all.subprocess, "call") as run, patch.object(accept_all, "report") as report:
            with self.assertRaises(SystemExit):
                accept_all.main(["--reprot"])
            run.assert_not_called()
            report.assert_not_called()

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
