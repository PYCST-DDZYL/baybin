"""Leak verdicts need complete, separate comparison windows."""
import unittest
from unittest.mock import patch

import accept_4_leak


class LeakWindowTest(unittest.TestCase):
    def test_too_short_run_is_rejected_before_device_work(self):
        with patch.object(accept_4_leak, "long_run") as run:
            with self.assertRaisesRegex(ValueError, "at least 30 scans"):
                accept_4_leak.main(["20"])
            run.assert_not_called()

    def test_missing_early_sample_is_saved_as_failure(self):
        rows = [{"pss_kb": 1000} for _ in range(30)]
        rows[11]["pss_kb"] = None
        with patch.object(accept_4_leak, "long_run", return_value={"rows": rows, "when": "today"}), \
             patch.object(accept_4_leak, "save") as save, \
             patch.object(accept_4_leak, "verdict"):
            self.assertFalse(accept_4_leak.main(["30"]))
        self.assertFalse(save.call_args.args[1]["pass"])
        self.assertIn("Insufficient", save.call_args.args[1]["error"])


if __name__ == "__main__":
    unittest.main()
