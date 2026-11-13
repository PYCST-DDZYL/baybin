"""Deployment commands report only confirmed device changes."""
import unittest
from unittest.mock import Mock, patch

import deploy


class DeploySafetyTest(unittest.TestCase):
    def test_unknown_command_cannot_report_success(self):
        with self.assertRaisesRegex(ValueError, "Unknown deployment command"):
            deploy.main(["phon"])

    def test_explicit_phone_install_cannot_succeed_without_phone(self):
        with patch.object(deploy, "phone", return_value=None):
            with self.assertRaisesRegex(RuntimeError, "no phone is connected"):
                deploy.install_phone("debug")

    def test_all_command_keeps_phone_optional(self):
        with patch.object(deploy, "build"), patch.object(deploy, "install_glasses"), \
             patch.object(deploy, "install_phone") as install:
            deploy.main(["all"])
        install.assert_called_once_with("debug", required=False)

    def test_phone_clean_checks_directory_is_gone_before_success_message(self):
        device = Mock()
        with patch.object(deploy, "phone", return_value=device), patch("builtins.print") as output:
            deploy.main(["phone-clean"])
        command = device.shell.call_args.args[0]
        self.assertIn("test ! -e", command)
        self.assertTrue(device.shell.call_args.kwargs["check"])
        output.assert_called_once()


if __name__ == "__main__":
    unittest.main()
