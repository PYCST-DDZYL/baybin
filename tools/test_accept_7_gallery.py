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


if __name__ == "__main__":
    unittest.main()
