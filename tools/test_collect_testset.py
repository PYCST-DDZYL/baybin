"""Collected photo sets must only record confirmed capture requests."""
import unittest
import tempfile
from pathlib import Path
from unittest.mock import Mock
from unittest.mock import patch

import collect_testset


class CaptureRequestTest(unittest.TestCase):
    def test_new_glasses_photo_does_not_overwrite_unlisted_file(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(collect_testset, "SET", tmp):
            (Path(tmp) / "battery__2.jpg").write_bytes(b"existing")
            name = collect_testset.next_photo_name("battery", [{"file": "battery__1.jpg"}])
            self.assertEqual("battery__3.jpg", name)

    def test_rejected_capture_broadcast_is_reported(self):
        device = Mock()
        device.shell.side_effect = RuntimeError("capture refused")
        with self.assertRaisesRegex(RuntimeError, "capture refused"):
            collect_testset.request_capture(device)
        self.assertTrue(device.shell.call_args.kwargs["check"])

    def test_empty_pulled_photo_is_rejected_before_manifest_entry(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(collect_testset, "SET", tmp):
            device = Mock()
            device.run.side_effect = lambda *args, **kwargs: Path(args[2]).write_bytes(b"")
            with self.assertRaisesRegex(RuntimeError, "missing or empty"):
                collect_testset.pull_photo(device, "sample__1.jpg")
            self.assertTrue(device.run.call_args.kwargs["check"])


if __name__ == "__main__":
    unittest.main()
