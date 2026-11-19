"""Probe commands reuse checked device operations."""
import tempfile
import unittest
from unittest.mock import Mock, patch

import probe


class ProbeSafetyTest(unittest.TestCase):
    def test_pair_rejects_unsafe_bluetooth_address_before_device_work(self):
        device = Mock()
        with self.assertRaisesRegex(ValueError, "Bluetooth address"):
            probe.cmd_pair(device, "AA:BB:CC:DD:EE:FF;id")
        device.shell.assert_not_called()

    def test_rejected_probe_broadcast_is_reported(self):
        device = Mock()
        device.shell.side_effect = RuntimeError("broadcast failed")
        with self.assertRaisesRegex(RuntimeError, "broadcast failed"):
            probe.broadcast(device, "SCAN")
        self.assertTrue(device.shell.call_args.kwargs["check"])

    def test_lens_show_uses_validated_screenshot_capture(self):
        device = Mock()
        with tempfile.TemporaryDirectory() as folder, \
             patch.object(probe, "OUT", folder), patch.object(probe, "wake_glasses"), \
             patch.object(probe, "broadcast"), patch.object(probe.time, "sleep"), \
             patch.object(probe, "log"):
            probe.cmd_show(device, count=0)
        device.screenshot.assert_called_once()
        self.assertTrue(device.screenshot.call_args.args[0].endswith("show-1.png"))

    def test_recording_removes_remote_video_after_pull_failure(self):
        device = Mock(serial="glass1")
        device.run.side_effect = RuntimeError("pull failed")
        recording = Mock()
        recording.wait.return_value = 0
        recording.poll.return_value = 0
        with patch.object(probe, "wake_glasses"), patch.object(probe.time, "sleep"), \
             patch.object(probe, "log"), patch("subprocess.Popen", return_value=recording):
            with self.assertRaisesRegex(RuntimeError, "pull failed"):
                probe.cmd_record(device, seconds=1)
        self.assertIn("rm -f", device.shell.call_args.args[0])
        self.assertTrue(device.shell.call_args.kwargs["check"])


if __name__ == "__main__":
    unittest.main()
