"""submit-staged-request.sh: a controller rejection is made visible.

When the controller rejects a staged remediation submission, submit-agent-job.sh
exits non-zero and leaves only a red submit job. submit-staged-request.sh now
regains control (it no longer exec's the submission), reads the HTTP status and
the controller's error back out of that output, and posts a short alert to the
branch's workstream (``POST /api/workstreams/{id}/messages``) and
``$GITHUB_STEP_SUMMARY`` before propagating the failure. The alert is
best-effort: a delivery failure is a warning, and the original submission
failure always still fails the job.

The controller is stood in by a ``curl`` stub that dispatches on URL and method,
records its calls, and returns scripted responses for ``/api/submit``,
``/api/workstreams`` and the messages endpoint.
"""
import json
import os
import stat
import subprocess
import tempfile
import unittest

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_SCRIPT = os.path.join(_REPO_ROOT, "tools", "ci", "submit-staged-request.sh")

# A curl stand-in. It finds the request URL (the last http(s) argument), the
# method (after -X) and the body (after -d), appends a record of the call to
# $CURL_CALL_LOG, and answers each endpoint from the FAKE_* environment.
_FAKE_CURL = r"""#!/usr/bin/env bash
url=""
method="GET"
data=""
prev=""
for a in "$@"; do
    case "$prev" in
        -X) method="$a" ;;
        -d) data="$a" ;;
    esac
    case "$a" in
        http://*|https://*) url="$a" ;;
    esac
    prev="$a"
done
echo "${method} ${url}" >> "$CURL_CALL_LOG"
case "$url" in
    */messages)
        printf 'MSGDATA %s\n' "$data" >> "$CURL_CALL_LOG"
        printf '%s\n%s' "${FAKE_MSG_BODY}" "${FAKE_MSG_CODE}"
        ;;
    */api/submit)
        if [ "${FAKE_SUBMIT_FAIL:-0}" = "1" ]; then
            exit 7
        fi
        printf '%s\n%s' "${FAKE_SUBMIT_BODY}" "${FAKE_SUBMIT_CODE}"
        ;;
    */api/workstreams)
        printf '%s\n%s' "${FAKE_WS_LIST}" "${FAKE_WS_CODE}"
        ;;
    *)
        printf '{}\n200'
        ;;
esac
"""


class SubmitStagedRequestAlertTest(unittest.TestCase):
    """Runs the script against a scripted controller and inspects its calls."""

    BRANCH = "ci/some-branch"
    REPO_URL = "git@github.com:o/r.git"

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory(prefix="staged-alert-")
        self.addCleanup(self.dir.cleanup)

        bin_curl = os.path.join(self.dir.name, "curl")
        with open(bin_curl, "w") as f:
            f.write(_FAKE_CURL)
        os.chmod(bin_curl, os.stat(bin_curl).st_mode | stat.S_IXUSR)

        self.request_dir = os.path.join(self.dir.name, "staged-request")
        os.makedirs(self.request_dir)
        with open(os.path.join(self.request_dir, "agent-prompt.txt"), "w") as f:
            f.write("do the work")
        with open(os.path.join(self.request_dir, "submit.env"), "w") as f:
            f.write("DESCRIPTION=Resolve 3 test failure(s)\n")

        self.call_log = os.path.join(self.dir.name, "curl_calls.log")
        self.summary = os.path.join(self.dir.name, "step_summary")

        # A workstream for this branch/repo exists by default.
        self.ws_list = json.dumps([
            {"workstreamId": "ws-7", "defaultBranch": self.BRANCH,
             "repoUrl": self.REPO_URL},
        ])

    def run_script(self, *, submit_body, submit_code="200", submit_fail="0",
                   ws_list=None, ws_code="200", msg_body='{"ok":true}',
                   msg_code="200", **extra_env):
        env = {k: v for k, v in os.environ.items()}
        env["PATH"] = self.dir.name + os.pathsep + env.get("PATH", "")
        env.update(
            BRANCH=self.BRANCH, BASE_BRANCH="master",
            CONTROLLER_URL="http://controller.invalid",
            CF_ACCESS_CLIENT_ID="cid", CF_ACCESS_CLIENT_SECRET="csecret",
            GITHUB_REPOSITORY="o/r", GITHUB_RUN_ID="12345",
            GITHUB_SERVER_URL="https://github.com",
            REMEDIATION_KIND="auto-resolve-python",
            GITHUB_STEP_SUMMARY=self.summary,
            CURL_CALL_LOG=self.call_log,
            FAKE_SUBMIT_BODY=submit_body, FAKE_SUBMIT_CODE=submit_code,
            FAKE_SUBMIT_FAIL=submit_fail,
            FAKE_WS_LIST=self.ws_list if ws_list is None else ws_list,
            FAKE_WS_CODE=ws_code,
            FAKE_MSG_BODY=msg_body, FAKE_MSG_CODE=msg_code,
        )
        env.update(extra_env)
        return subprocess.run(["bash", _SCRIPT, self.request_dir], env=env,
                              capture_output=True, text=True, timeout=60)

    def calls(self):
        if not os.path.exists(self.call_log):
            return ""
        with open(self.call_log) as f:
            return f.read()

    def summary_text(self):
        if not os.path.exists(self.summary):
            return ""
        with open(self.summary) as f:
            return f.read()

    def message_posted(self):
        """The text of the message posted to the messages endpoint, or None."""
        for line in self.calls().splitlines():
            if line.startswith("MSGDATA "):
                return json.loads(line[len("MSGDATA "):])["text"]
        return None

    def test_a_rejection_posts_an_alert_and_still_fails(self):
        result = self.run_script(
            submit_body='{"ok":false,"error":"prompt failed the test-run linter"}',
            submit_code="400")
        self.assertNotEqual(0, result.returncode, result.stdout)

        # The message was posted to the branch's workstream thread.
        self.assertIn("POST http://controller.invalid/api/workstreams/ws-7/messages",
                      self.calls())
        text = self.message_posted()
        self.assertIsNotNone(text, self.calls())
        self.assertIn("auto-resolve-python", text)
        self.assertIn(self.BRANCH, text)
        self.assertIn("HTTP 400", text)
        self.assertIn("prompt failed the test-run linter", text)
        self.assertIn("actions/runs/12345", text)

        # The same summary reached the step summary.
        self.assertIn("auto-resolve-python", self.summary_text())
        self.assertIn("HTTP 400", self.summary_text())

    def test_a_successful_submission_posts_no_alert(self):
        result = self.run_script(
            submit_body='{"ok":true,"jobId":"j1","workstreamId":"ws-7"}',
            submit_code="200")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIsNone(self.message_posted())
        self.assertNotIn("/messages", self.calls())
        self.assertEqual("", self.summary_text())

    def test_a_failed_alert_delivery_keeps_the_original_failure(self):
        result = self.run_script(
            submit_body='{"ok":false,"error":"linter said no"}',
            submit_code="400", msg_code="500")
        self.assertNotEqual(0, result.returncode, result.stdout)
        # The post was attempted, failed, and warned — without masking failure.
        self.assertIn("/messages", self.calls())
        self.assertIn("::warning::", result.stdout)

    def test_no_workstream_for_the_branch_warns_but_still_fails(self):
        result = self.run_script(
            submit_body='{"ok":false,"error":"linter said no"}',
            submit_code="400", ws_list="[]")
        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertIn("No workstream registered", result.stdout)
        # No message endpoint call when there is no workstream to post to.
        self.assertNotIn("/messages", self.calls())
        # The summary is still written even when the workstream cannot be found.
        self.assertIn("auto-resolve-python", self.summary_text())

    def test_an_unreachable_controller_submission_reports_no_response(self):
        result = self.run_script(submit_body="", submit_fail="1")
        self.assertNotEqual(0, result.returncode, result.stdout)
        text = self.message_posted()
        self.assertIsNotNone(text, self.calls())
        self.assertIn("HTTP no response", text)

    def test_the_kind_falls_back_to_the_github_job(self):
        result = self.run_script(
            submit_body='{"ok":false,"error":"linter said no"}',
            submit_code="400", REMEDIATION_KIND="", GITHUB_JOB="some-submit-job")
        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertIn("some-submit-job", self.message_posted())

    def test_the_whole_body_is_used_when_the_response_has_no_error_field(self):
        # The controller's error body carries no ``.error`` key, so the alert
        # falls back to the raw response body rather than losing the reason.
        result = self.run_script(
            submit_body='{"detail":"prompt rejected by the linter"}',
            submit_code="400")
        self.assertNotEqual(0, result.returncode, result.stdout)
        text = self.message_posted()
        self.assertIsNotNone(text, self.calls())
        self.assertIn("HTTP 400", text)
        self.assertIn("prompt rejected by the linter", text)

    def test_the_branch_only_fallback_posts_when_the_repo_url_form_differs(self):
        # The workstream is registered with an HTTPS repoUrl while the script
        # derives the SSH form, so the repository-qualified filter misses and
        # the branch-only fallback still finds the workstream to alert.
        other_form = json.dumps([
            {"workstreamId": "ws-9", "defaultBranch": self.BRANCH,
             "repoUrl": "https://github.com/o/r.git"},
        ])
        result = self.run_script(
            submit_body='{"ok":false,"error":"linter said no"}',
            submit_code="400", ws_list=other_form)
        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertIn("POST http://controller.invalid/api/workstreams/ws-9/messages",
                      self.calls())
        self.assertIn("linter said no", self.message_posted())


if __name__ == "__main__":
    unittest.main()
