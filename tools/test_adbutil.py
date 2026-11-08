"""ADB discovery must surface tool failures before a device test changes state."""
import subprocess
import unittest
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


if __name__ == "__main__":
    unittest.main()
