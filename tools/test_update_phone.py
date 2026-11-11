import unittest
from pathlib import Path
from unittest.mock import Mock
from update_phone import package_info, release_version, startup_log


class UpdateMetadataTest(unittest.TestCase):
    def test_update_rejects_debug_or_mismatched_apk_metadata(self):
        metadata = {"applicationId": "com.baybin.phone", "variantName": "release",
                    "elements": [{"versionName": "0.6.1", "outputFile": "phone-app-release.apk"}]}
        apk = Path("phone-app-release.apk")
        self.assertEqual("0.6.1", release_version(metadata, apk))
        with self.assertRaisesRegex(ValueError, "matching production release"):
            release_version({**metadata, "variantName": "debug"}, apk)
        with self.assertRaisesRegex(ValueError, "matching production release"):
            release_version(metadata, Path("other.apk"))

    def test_startup_log_read_requires_adb_success(self):
        device = Mock()
        device.run.side_effect = RuntimeError("logcat failed")
        with self.assertRaisesRegex(RuntimeError, "logcat failed"):
            startup_log(device, 123)
        self.assertTrue(device.run.call_args.kwargs["check"])

    def test_android_user_id_and_new_app_id_preserve_the_same_identity(self):
        base = "  versionName=0.6.1\n  firstInstallTime=2026-10-02 04:05:15\n"
        self.assertEqual(package_info(base + "  userId=10400\n"), package_info(base + "  appId=10400\n"))

    def test_missing_identity_or_install_time_blocks_an_update(self):
        for text in ("", "versionName=0.6.1\nappId=10400", "versionName=0.6.1\nfirstInstallTime=today"):
            with self.subTest(text=text), self.assertRaises(RuntimeError):
                package_info(text)


if __name__ == "__main__":
    unittest.main()
