"""Optimized Python must not turn failed review checks into a passing report."""
from pathlib import Path
import subprocess
import sys
import unittest


class AssertionSafetyTest(unittest.TestCase):
    def test_review_scripts_reject_optimized_python_before_device_access(self):
        root = Path(__file__).parent
        for script in ("check_local_ui.py", "check_local_flows.py"):
            with self.subTest(script=script):
                result = subprocess.run([sys.executable, "-O", str(root / script)],
                                        capture_output=True, text=True)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("do not run with -O", result.stderr)


if __name__ == "__main__":
    unittest.main()
