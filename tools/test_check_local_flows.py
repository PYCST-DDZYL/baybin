"""Disposable phone flow checks require reliable log evidence."""
import unittest
from unittest.mock import Mock

import check_local_flows


class FlowLogTest(unittest.TestCase):
    def test_logcat_failure_is_reported(self):
        device = Mock()
        device.run.side_effect = RuntimeError("ADB logcat failed")
        with self.assertRaisesRegex(RuntimeError, "ADB logcat failed"):
            check_local_flows.flow_log(device, 123)
        self.assertTrue(device.run.call_args.kwargs["check"])


if __name__ == "__main__":
    unittest.main()
