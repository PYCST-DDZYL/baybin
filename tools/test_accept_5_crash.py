"""Crash acceptance cannot pass when scans or process IDs are missing."""
import unittest
from unittest.mock import patch

import accept_5_crash


class CrashCompletenessTest(unittest.TestCase):
    def test_missing_scan_or_pid_cannot_pass(self):
        base = {"rows": [{"outcome": "result_0"}], "pids_before": {"glasses": 1, "phone": 2},
                "pids_after": {"glasses": 1, "phone": 2}, "crash_glasses": [], "crash_phone": [], "when": "today"}
        for run in ({**base, "rows": []}, {**base, "pids_after": {"glasses": 1, "phone": None}}):
            with self.subTest(run=run), patch.object(accept_5_crash, "long_run", return_value=run), \
                 patch.object(accept_5_crash, "save") as save, patch.object(accept_5_crash, "verdict"):
                self.assertFalse(accept_5_crash.main(["1"]))
                self.assertIn("Incomplete", save.call_args.args[1]["error"])


if __name__ == "__main__":
    unittest.main()
