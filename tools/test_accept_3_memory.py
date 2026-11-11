"""Memory acceptance needs the requested number of scans."""
import unittest
from unittest.mock import patch

import accept_3_memory


class MemoryCompletenessTest(unittest.TestCase):
    def test_nine_of_ten_scans_cannot_pass_even_with_low_pss(self):
        run = {"rows": [{"pss_kb": 1000} for _ in range(9)], "pss_before_kb": 1000, "when": "today"}
        with patch.object(accept_3_memory, "long_run", return_value=run), \
             patch.object(accept_3_memory, "save") as save, patch.object(accept_3_memory, "verdict"):
            self.assertFalse(accept_3_memory.main(["10"]))
        self.assertIn("Incomplete", save.call_args.args[1]["error"])


if __name__ == "__main__":
    unittest.main()
