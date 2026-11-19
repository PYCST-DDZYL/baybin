"""Web photo labels must be catalog items before files are written."""
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import collect_web_testset


class WebSetPickTest(unittest.TestCase):
    def test_search_excludes_photos_without_attribution_page(self):
        with tempfile.TemporaryDirectory(dir=collect_web_testset.ROOT) as folder, \
             patch.object(collect_web_testset, "OUT", folder), \
             patch.object(collect_web_testset, "QUERIES", [("trash", "can")]), \
             patch.object(collect_web_testset, "commons", return_value=[{"page": None}]), \
             patch.object(collect_web_testset, "openverse", return_value=[]), \
             patch.object(collect_web_testset, "sheets"), \
             patch.object(collect_web_testset.time, "sleep"):
            collect_web_testset.search()
            self.assertEqual([], json.loads((Path(folder) / "candidates.json").read_text(encoding="utf-8")))

    def test_invalid_label_cannot_escape_photo_directory(self):
        with tempfile.TemporaryDirectory(dir=collect_web_testset.ROOT) as folder:
            root = Path(folder)
            (root / "rules").mkdir()
            (root / "rules/items.json").write_text(json.dumps({"items": [{"id": "battery"}]}), encoding="utf-8")
            (root / "candidates").mkdir()
            (root / "candidates/candidates.json").write_text(json.dumps([{"id": "c1"}]), encoding="utf-8")
            with patch.object(collect_web_testset, "ROOT", folder), \
                 patch.object(collect_web_testset, "OUT", str(root / "candidates")), \
                 patch.object(collect_web_testset, "SET", str(root / "set")):
                with self.assertRaisesRegex(ValueError, "Unknown candidate or catalog item"):
                    collect_web_testset.pick(["c1=../../outside"])
            self.assertFalse((root / "set").exists())


if __name__ == "__main__":
    unittest.main()
