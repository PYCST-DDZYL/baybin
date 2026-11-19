"""Gallery acceptance must stop on failed setup commands."""
import unittest
from unittest.mock import Mock, patch

import accept_7_gallery


class GallerySetupTest(unittest.TestCase):
    def test_wake_failure_stops_before_touching_gallery_data(self):
        device = Mock()
        device.shell.side_effect = RuntimeError("phone unavailable")
        with patch.object(accept_7_gallery, "phone", return_value=device), \
             patch.object(accept_7_gallery, "clear_private") as clear:
            with self.assertRaisesRegex(RuntimeError, "phone unavailable"):
                accept_7_gallery.main()
        self.assertTrue(device.shell.call_args.kwargs["check"])
        clear.assert_not_called()

    def test_picker_probe_returns_to_app_after_activity_read_failure(self):
        device = Mock()
        device.shell.side_effect = ["", RuntimeError("activity unavailable"), ""]
        with patch.object(accept_7_gallery.time, "sleep"):
            with self.assertRaisesRegex(RuntimeError, "activity unavailable"):
                accept_7_gallery.picker_opened(device, "[10,20][30,40]")
        self.assertEqual("input tap 20 30", device.shell.call_args_list[0].args[0])
        self.assertEqual("input keyevent KEYCODE_BACK", device.shell.call_args_list[-1].args[0])
        self.assertTrue(all(call.kwargs["check"] for call in device.shell.call_args_list))

    def test_picker_probe_rejects_invalid_bounds_without_tapping(self):
        device = Mock()
        self.assertFalse(accept_7_gallery.picker_opened(device, ""))
        device.shell.assert_not_called()


if __name__ == "__main__":
    unittest.main()
