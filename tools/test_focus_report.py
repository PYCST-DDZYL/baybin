import unittest

from focus_report import metrics, summarize


class FocusMetricsTest(unittest.TestCase):
    def test_retakes_and_timeouts_remain_misses_and_offline_is_separate(self):
        rows = [{"kind": "0", "want": "Garbage", "got": "Garbage"},
                {"kind": 0, "want": "Garbage", "got": "Recycling"},
                {"kind": 1}, {"kind": 2, "model_ms": 7001},
                {"kind": 2, "model_ms": -1}, {"kind": 3}, {}]
        result = metrics(rows)
        self.assertEqual(1, result["wrong_bins"])
        self.assertEqual(1, result["retakes"])
        self.assertEqual(1, result["timeouts"])
        self.assertEqual(1, result["connection_errors"])
        self.assertEqual(1 / 7, result["success_rate"])
        self.assertEqual(0.5, result["definite_precision"])

    def test_no_measurements_do_not_claim_success(self):
        self.assertIsNone(metrics([])["success_rate"])
        self.assertIsNone(metrics([])["definite_precision"])

    def test_old_measurements_and_manual_answers_are_not_new_first_pass_successes(self):
        photo = dict(item="paper_cup", kind=0, want="Garbage", got="Garbage")
        user = dict(photo, evidence="user")
        report = {"code_hash": "old", "sets": {"sample": {"results": {"san_jose": [photo, user]}}}}
        result = summarize(report, "current", {"city": "san_jose", "residence": "single-family", "items": ["paper_cup"]})
        self.assertEqual("STALE", result["status"])
        self.assertEqual(1, result["first_pass"]["total"])
        self.assertEqual(1, result["user_supplemented"]["total"])
