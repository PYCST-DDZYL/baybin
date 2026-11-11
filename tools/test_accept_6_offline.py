"""Offline acceptance must preserve connectivity even when a check fails."""
import unittest
from copy import deepcopy
from unittest.mock import Mock, patch

import accept_6_offline


class OfflineRecoveryTest(unittest.TestCase):
    def test_pass_requires_observed_offline_and_both_recoveries(self):
        steps = {
            "A_offline": {"phone_saw_offline": True, "scan": {"outcome": "result_2", "total": 2000}},
            "A_recovered": {"online_again": True, "scan": {"outcome": "result_0"}},
            "B_no_link": {"scan": {"outcome": "no_link", "total": 500}},
            "B_recovered": {"relinked": True, "scan": {"outcome": "result_0"}},
        }
        self.assertTrue(accept_6_offline.passed(steps, [], True))
        for step, flag in (("A_offline", "phone_saw_offline"), ("A_recovered", "online_again"),
                           ("B_recovered", "relinked")):
            incomplete = deepcopy(steps)
            incomplete[step][flag] = False
            with self.subTest(step=step):
                self.assertFalse(accept_6_offline.passed(incomplete, [], True))

    def test_missing_process_identity_cannot_prove_no_restart(self):
        self.assertFalse(accept_6_offline.processes_unchanged((None, None), (None, None)))
        self.assertFalse(accept_6_offline.processes_unchanged((123, 456), (123, None)))
        self.assertTrue(accept_6_offline.processes_unchanged((123, 456), (123, 456)))

    def test_unknown_initial_radio_state_stops_before_changes(self):
        for value in ("", "null", "permission denied"):
            with self.subTest(value=value), self.assertRaisesRegex(RuntimeError, "refusing to change connectivity"):
                accept_6_offline.radio_enabled(value, "Wi-Fi")
        self.assertFalse(accept_6_offline.radio_enabled("0\n", "Wi-Fi"))
        self.assertTrue(accept_6_offline.radio_enabled("1\n", "Wi-Fi"))

    def test_bluetooth_is_reconnected_when_scan_fails(self):
        phone = Mock()
        with patch.object(accept_6_offline, "scan", side_effect=RuntimeError("scan failed")), \
             patch.object(accept_6_offline.time, "sleep"):
            with self.assertRaisesRegex(RuntimeError, "scan failed"):
                accept_6_offline.scan_without_link(phone, Mock())
        commands = [call.args[0] for call in phone.shell.call_args_list]
        self.assertIn("DISCONNECT", commands[0])
        self.assertIn("RECONNECT", commands[-1])
        self.assertTrue(all(call.kwargs.get("check") for call in phone.shell.call_args_list))

    def test_mobile_data_restoration_is_attempted_if_wifi_restoration_fails(self):
        phone = Mock()
        phone.shell.side_effect = [RuntimeError("Wi-Fi restore failed"), ""]
        with self.assertRaisesRegex(RuntimeError, "Wi-Fi restore failed"):
            accept_6_offline.restore_radios(phone, True, True)
        commands = [call.args[0] for call in phone.shell.call_args_list]
        self.assertEqual(["svc wifi enable", "svc data enable"], commands)


if __name__ == "__main__":
    unittest.main()
