import unittest

from app.services.callback_service import canonical_json, generate_signature


class CallbackContractTest(unittest.TestCase):

    def test_canonical_json_and_signature_should_match_expected_hash(self):
        payload = {
            "jobId": "job-123",
            "workflowId": 7,
            "status": "COMPLETED",
            "progress": 100,
            "message": "completed",
            "metrics": {
                "mAP": 0.88,
                "precision": 0.91,
                "recall": 0.86,
            },
            "resultFile": {
                "fileName": "job-123_result.json",
                "filePath": "D:/results/job-123_result.json",
            },
            "errorMessage": None,
        }

        expected_json = (
            "{\"errorMessage\":null,\"jobId\":\"job-123\",\"message\":\"completed\","
            "\"metrics\":{\"mAP\":0.88,\"precision\":0.91,\"recall\":0.86},"
            "\"progress\":100,\"resultFile\":{\"fileName\":\"job-123_result.json\","
            "\"filePath\":\"D:/results/job-123_result.json\"},"
            "\"status\":\"COMPLETED\",\"workflowId\":7}"
        )
        expected_sign = "13d73a726b9385504f2a5024471ee2b06c023a913035d63ec7f51a702e3b6ebb"

        self.assertEqual(expected_json, canonical_json(payload))
        self.assertEqual(expected_sign, generate_signature(payload, "dev-secret"))

    def test_signature_should_change_when_payload_changes(self):
        payload = {
            "jobId": "job-123",
            "workflowId": 7,
            "status": "FAILED",
            "progress": 0,
            "message": "failed",
            "metrics": None,
            "resultFile": None,
            "errorMessage": "mock failure",
        }

        original_sign = generate_signature(payload, "dev-secret")
        changed_payload = dict(payload)
        changed_payload["errorMessage"] = "another failure"

        self.assertNotEqual(original_sign, generate_signature(changed_payload, "dev-secret"))


if __name__ == "__main__":
    unittest.main()
