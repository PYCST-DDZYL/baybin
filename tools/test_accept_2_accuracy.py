"""Explicit accuracy test sets must be run or rejected."""
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import accept_2_accuracy


class AccuracySetSelectionTest(unittest.TestCase):
    def test_missing_explicit_manifest_is_rejected(self):
        with tempfile.TemporaryDirectory(dir=accept_2_accuracy.ROOT) as folder:
            with self.assertRaisesRegex(ValueError, "no manifest"):
                accept_2_accuracy.select_sets([folder])

    def test_small_explicit_set_is_rejected(self):
        with tempfile.TemporaryDirectory(dir=accept_2_accuracy.ROOT) as folder:
            (Path(folder) / "manifest.json").write_text("{}", encoding="utf-8")
            with patch.object(accept_2_accuracy, "load_set", return_value=[1] * 49):
                with self.assertRaisesRegex(ValueError, "fewer than 50"):
                    accept_2_accuracy.select_sets([folder])

    def test_duplicate_set_names_cannot_overwrite_report_results(self):
        with tempfile.TemporaryDirectory(dir=accept_2_accuracy.ROOT) as folder:
            first, second = Path(folder) / "a" / "photos", Path(folder) / "b" / "photos"
            for sample in (first, second):
                sample.mkdir(parents=True)
                (sample / "manifest.json").write_text("{}", encoding="utf-8")
            with patch.object(accept_2_accuracy, "load_set", return_value=[1] * 50):
                with self.assertRaisesRegex(ValueError, "unique folder names"):
                    accept_2_accuracy.select_sets([str(first), str(second)])


if __name__ == "__main__":
    unittest.main()
