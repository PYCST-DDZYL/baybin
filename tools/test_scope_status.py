import unittest
from acceptlib import parse_phone_status, require_residential_scope


class ScopeStatusTest(unittest.TestCase):
    base = "BB_STATUS link=DISCONNECTED mac=null glasses=null city=san_jose online=true auto=false lang=en app=0.6.1"

    def test_current_status_retains_service_evidence(self):
        status = parse_phone_status(self.base + " housing=SINGLE_FAMILY city_service=true")
        require_residential_scope(status)
        self.assertEqual("SINGLE_FAMILY", status["housing"])
        self.assertEqual("true", status["city_service"])

    def test_missing_and_unsupported_profiles_fail_closed(self):
        for status in (None, parse_phone_status(self.base), {},
                       parse_phone_status(self.base + " housing=APARTMENT city_service=true"),
                       parse_phone_status(self.base + " housing=SINGLE_FAMILY city_service=false")):
            with self.subTest(status=status), self.assertRaises(RuntimeError):
                require_residential_scope(status)

    def test_old_status_still_restores_preferences_but_cannot_authorize_photos(self):
        status = parse_phone_status(self.base)
        self.assertEqual("san_jose", status["city"])
        self.assertEqual("en", status["lang"])
        self.assertIsNone(status["housing"])
        self.assertIsNone(parse_phone_status("unrelated log"))


if __name__ == "__main__":
    unittest.main()
