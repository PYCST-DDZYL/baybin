"""Interrupted writes must preserve the previous report."""
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import atomicfile


class AtomicFileTest(unittest.TestCase):
    def test_replace_failure_preserves_previous_contents_and_cleans_temp(self):
        with tempfile.TemporaryDirectory() as folder:
            target = Path(folder) / "manifest.json"
            target.write_text("old", encoding="utf-8")
            with patch.object(atomicfile.os, "replace", side_effect=OSError("disk full")):
                with self.assertRaisesRegex(OSError, "disk full"):
                    atomicfile.write_text(target, "new")
            self.assertEqual("old", target.read_text(encoding="utf-8"))
            self.assertEqual([target], list(Path(folder).iterdir()))


if __name__ == "__main__":
    unittest.main()
