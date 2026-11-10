"""ADB discovery must surface tool failures before a device test changes state."""
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import adbutil


class DeviceDiscoveryTest(unittest.TestCase):
    def test_adb_server_failure_is_reported_instead_of_no_phone(self):
        failed = subprocess.CompletedProcess([], 1, stdout="", stderr="could not start daemon")
        with patch.object(adbutil.subprocess, "run", return_value=failed):
            with self.assertRaisesRegex(RuntimeError, "adb devices failed: could not start daemon"):
                adbutil.phone_serial()

    def test_connected_glasses_do_not_count_as_a_phone(self):
        listed = subprocess.CompletedProcess([], 0, stdout=(
            "List of devices attached\n"
            "glass1 device model:RG_glasses\n"
            "phone1 device model:SM-S928U\n"), stderr="")
        with patch.object(adbutil.subprocess, "run", return_value=listed):
            self.assertEqual("phone1", adbutil.phone_serial())
            self.assertEqual("glass1", adbutil.glasses_serial())

    def test_multiple_phones_are_not_selected_arbitrarily(self):
        listed = subprocess.CompletedProcess([], 0, stdout=(
            "List of devices attached\n"
            "phone1 device model:Pixel_8\n"
            "phone2 device model:SM-S928U\n"), stderr="")
        with patch.object(adbutil.subprocess, "run", return_value=listed):
            with self.assertRaisesRegex(RuntimeError, "Multiple phones connected"):
                adbutil.phone_serial()

    def test_multiple_glasses_are_not_selected_arbitrarily(self):
        listed = subprocess.CompletedProcess([], 0, stdout=(
            "List of devices attached\n"
            "glass1 device model:RG_glasses\n"
            "glass2 device model:RG_glasses\n"), stderr="")
        with patch.object(adbutil.subprocess, "run", return_value=listed):
            with self.assertRaisesRegex(RuntimeError, "Multiple glasses connected"):
                adbutil.glasses_serial()

    def test_unknown_model_is_not_assumed_to_be_a_phone(self):
        listed = subprocess.CompletedProcess([], 0, stdout=(
            "List of devices attached\n"
            "unidentified device usb:1-1\n"), stderr="")
        with patch.object(adbutil.subprocess, "run", return_value=listed):
            with self.assertRaisesRegex(RuntimeError, "did not report every device model"):
                adbutil.phone_serial()

    def test_failed_screenshot_preserves_existing_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / "screen.png"
            target.write_bytes(b"previous screenshot")
            failed = subprocess.CompletedProcess([], 1, stdout=b"", stderr=b"device offline")
            with patch.object(adbutil.subprocess, "run", return_value=failed):
                with self.assertRaisesRegex(RuntimeError, "ADB screenshot failed"):
                    adbutil.Dev("phone1", "phone").screenshot(target)
            self.assertEqual(b"previous screenshot", target.read_bytes())

    def test_non_png_screenshot_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / "screen.png"
            invalid = subprocess.CompletedProcess([], 0, stdout=b"adb error", stderr=b"")
            with patch.object(adbutil.subprocess, "run", return_value=invalid):
                with self.assertRaisesRegex(RuntimeError, "invalid PNG"):
                    adbutil.Dev("phone1", "phone").screenshot(target)
            self.assertFalse(target.exists())

    def test_truncated_png_screenshot_does_not_replace_previous_image(self):
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / "screen.png"
            target.write_bytes(b"previous screenshot")
            truncated = subprocess.CompletedProcess([], 0, stdout=b"\x89PNG\r\n\x1a\npartial", stderr=b"")
            with patch.object(adbutil.subprocess, "run", return_value=truncated):
                with self.assertRaisesRegex(RuntimeError, "invalid PNG"):
                    adbutil.Dev("phone1", "phone").screenshot(target)
            self.assertEqual(b"previous screenshot", target.read_bytes())

    def test_log_clear_failure_cannot_leave_stale_test_evidence(self):
        failed = subprocess.CompletedProcess([], 1, stdout="", stderr="permission denied")
        with patch.object(adbutil.subprocess, "run", return_value=failed):
            with self.assertRaisesRegex(RuntimeError, "adb logcat -c failed"):
                adbutil.Dev("phone1", "phone").logcat_clear()

    def test_log_wait_uses_elapsed_time_even_if_wall_clock_changes(self):
        device = adbutil.Dev("phone1", "phone")
        with patch.object(device, "logcat", return_value="") as logcat, \
             patch.object(adbutil.time, "monotonic", side_effect=[10, 10.1, 11.1]), \
             patch.object(adbutil.time, "time", side_effect=AssertionError("wall clock used")), \
             patch.object(adbutil.time, "sleep"):
            self.assertIsNone(device.wait_log("BB_SCAN", timeout=1))
        logcat.assert_called_once()


if __name__ == "__main__":
    unittest.main()
