"""Collected photo sets must only record confirmed capture requests."""
import unittest
from unittest.mock import Mock

import collect_testset


class CaptureRequestTest(unittest.TestCase):
    def test_rejected_capture_broadcast_is_reported(self):
        device = Mock()
        device.shell.side_effect = RuntimeError("capture refused")
        with self.assertRaisesRegex(RuntimeError, "capture refused"):
            collect_testset.request_capture(device)
        self.assertTrue(device.shell.call_args.kwargs["check"])


if __name__ == "__main__":
    unittest.main()
