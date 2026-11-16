"""Deployment typos must fail before a build or device install starts."""
import unittest
from unittest.mock import patch

import deploy


class DeployCliTest(unittest.TestCase):
    def test_misspelled_variant_does_not_install_debug_build(self):
        with patch.object(deploy, "install_glasses") as install:
            with self.assertRaisesRegex(ValueError, "variant argument"):
                deploy.main(["glasses", "relese"])
        install.assert_not_called()

    def test_phone_clean_rejects_extra_arguments_before_device_access(self):
        with patch.object(deploy, "phone") as phone:
            with self.assertRaisesRegex(ValueError, "Unexpected arguments"):
                deploy.main(["phone-clean", "release"])
        phone.assert_not_called()


if __name__ == "__main__":
    unittest.main()
