"""Probe arguments should fail before connecting to hardware."""
import unittest
from unittest.mock import patch

import probe


class ProbeCliTest(unittest.TestCase):
    def test_unknown_command_does_not_connect_to_glasses(self):
        with patch.object(probe, "glasses") as glasses:
            with self.assertRaisesRegex(ValueError, "Unknown probe command"):
                probe.main(["scna"])
        glasses.assert_not_called()

    def test_bad_pair_address_is_not_treated_as_scan_request(self):
        with patch.object(probe, "glasses") as glasses:
            with self.assertRaisesRegex(ValueError, "Bluetooth address"):
                probe.main(["pair", "invalid-address"])
        glasses.assert_not_called()


if __name__ == "__main__":
    unittest.main()
