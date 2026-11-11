"""Latency checks require an actual scan before they can report timing."""
import unittest
from unittest.mock import patch

import accept_1_latency


class LatencyInputTest(unittest.TestCase):
    def test_zero_scans_rejected_before_device_work(self):
        with patch.object(accept_1_latency, "both") as both:
            with self.assertRaisesRegex(ValueError, "positive scan count"):
                accept_1_latency.main(0)
            both.assert_not_called()


if __name__ == "__main__":
    unittest.main()
