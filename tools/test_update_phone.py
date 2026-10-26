import unittest
from update_phone import package_info


class UpdateMetadataTest(unittest.TestCase):
    def test_android_user_id_and_new_app_id_preserve_the_same_identity(self):
        base = "  versionName=0.6.1\n  firstInstallTime=2026-10-02 04:05:15\n"
        self.assertEqual(package_info(base + "  userId=10400\n"), package_info(base + "  appId=10400\n"))

    def test_missing_identity_or_install_time_blocks_an_update(self):
        for text in ("", "versionName=0.6.1\nappId=10400", "versionName=0.6.1\nfirstInstallTime=today"):
            with self.subTest(text=text), self.assertRaises(RuntimeError):
                package_info(text)


if __name__ == "__main__":
    unittest.main()
