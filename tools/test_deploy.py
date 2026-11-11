"""Deployment commands report only confirmed device changes."""
import unittest
from unittest.mock import Mock, patch

import deploy


class DeploySafetyTest(unittest.TestCase):
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
