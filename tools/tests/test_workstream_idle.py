"""workstream-idle.sh: the in-flight gate Implement Plan uses to refuse a second
implementation.

``tools/ci/workstream-idle.sh`` asks the controller for a workstream's running
and pending jobs and reports ``inflight=true`` when either list is non-empty.
It fails open: an unreachable controller or a body that is not a JSON array
counts as idle. Implement Plan's ``build-prompt`` job is gated on that verdict,
so both the script's behaviour and its wiring into the workflow are pinned here.
"""
import os
import stat
import subprocess
import tempfile
import unittest

import yaml

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_SCRIPT = os.path.join(_REPO_ROOT, "tools", "ci", "workstream-idle.sh")
_IMPLEMENT_PLAN = os.path.join(_REPO_ROOT, ".github", "workflows", "implement-plan.yaml")

# A curl stand-in: records every argument, then answers the active and pending
# views from ACTIVE_BODY / PENDING_BODY, or fails as curl -f does on an HTTP
# error when CURL_FAIL is set.
_FAKE_CURL = """#!/usr/bin/env bash
printf '%s\\n' "$@" >> "$CURL_ARGS_OUT"
[ -n "${CURL_FAIL:-}" ] && exit 22
url="${@: -1}"
case "$url" in
  */jobs/active) printf '%s' "$ACTIVE_BODY" ;;
  */jobs/pending) printf '%s' "$PENDING_BODY" ;;
  *) exit 22 ;;
esac
"""


class WorkstreamIdleScriptTest(unittest.TestCase):
    """Runs the script against the fake curl and inspects its verdict."""

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory(prefix="workstream-idle-")
        self.addCleanup(self.dir.cleanup)
        bin_curl = os.path.join(self.dir.name, "curl")
        with open(bin_curl, "w") as f:
            f.write(_FAKE_CURL)
        os.chmod(bin_curl, os.stat(bin_curl).st_mode | stat.S_IXUSR)
        self.args_out = os.path.join(self.dir.name, "curl-args.txt")
        self.github_output = os.path.join(self.dir.name, "github-output.txt")

    def run_gate(self, **knobs):
        env = {k: v for k, v in os.environ.items()
               if k not in ("WORKSTREAM_ID", "CONTROLLER_URL", "CONTROLLER_HOST",
                            "CONTROLLER_PORT", "CF_ACCESS_CLIENT_ID",
                            "CF_ACCESS_CLIENT_SECRET", "GITHUB_OUTPUT", "CURL_FAIL")}
        env.update(PATH=self.dir.name + os.pathsep + env.get("PATH", ""),
                   CURL_ARGS_OUT=self.args_out, GITHUB_OUTPUT=self.github_output,
                   WORKSTREAM_ID="ws-1", CONTROLLER_URL="http://controller.invalid/",
                   ACTIVE_BODY="[]", PENDING_BODY="[]")
        env.update(knobs)
        return subprocess.run(["bash", _SCRIPT], env=env,
                              capture_output=True, text=True, timeout=60)

    def outputs(self):
        with open(self.github_output) as f:
            return dict(line.split("=", 1) for line in f.read().splitlines())

    def curl_args(self):
        with open(self.args_out) as f:
            return f.read().splitlines()

    def test_a_running_job_makes_the_workstream_busy(self):
        result = self.run_gate(ACTIVE_BODY='[{"jobId":"a"}]')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual({"inflight": "true", "inflight_count": "1"}, self.outputs())

    def test_a_pending_job_makes_the_workstream_busy(self):
        result = self.run_gate(PENDING_BODY='[{"jobId":"p1"},{"jobId":"p2"}]')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual({"inflight": "true", "inflight_count": "2"}, self.outputs())

    def test_running_and_pending_jobs_are_summed(self):
        result = self.run_gate(ACTIVE_BODY='[{"jobId":"a"}]',
                               PENDING_BODY='[{"jobId":"p1"},{"jobId":"p2"}]')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual({"inflight": "true", "inflight_count": "3"}, self.outputs())
        self.assertIn("inflight=true", result.stdout)

    def test_empty_views_leave_the_workstream_idle(self):
        result = self.run_gate()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual({"inflight": "false", "inflight_count": "0"}, self.outputs())

    def test_an_unreachable_controller_fails_open(self):
        result = self.run_gate(CURL_FAIL="1")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual({"inflight": "false", "inflight_count": "0"}, self.outputs())

    def test_a_body_that_is_not_a_json_array_counts_as_idle(self):
        for body in ("not json", '{"jobs":[1,2]}', "", "null"):
            with self.subTest(body=body):
                if os.path.exists(self.github_output):
                    os.remove(self.github_output)
                result = self.run_gate(ACTIVE_BODY=body, PENDING_BODY=body)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual({"inflight": "false", "inflight_count": "0"},
                                 self.outputs())

    def test_a_missing_workstream_id_is_an_error(self):
        result = self.run_gate(WORKSTREAM_ID="")
        self.assertEqual(1, result.returncode)
        self.assertIn("WORKSTREAM_ID is not set", result.stderr)
        self.assertFalse(os.path.exists(self.github_output))

    def test_it_queries_both_views_of_the_named_workstream(self):
        self.run_gate()
        args = self.curl_args()
        # The trailing slash on CONTROLLER_URL is dropped, not doubled.
        self.assertIn("http://controller.invalid/api/workstreams/ws-1/jobs/active", args)
        self.assertIn("http://controller.invalid/api/workstreams/ws-1/jobs/pending", args)

    def test_controller_host_and_port_apply_without_a_url(self):
        self.run_gate(CONTROLLER_URL="", CONTROLLER_HOST="ctl", CONTROLLER_PORT="9000")
        self.assertIn("http://ctl:9000/api/workstreams/ws-1/jobs/active", self.curl_args())

    def test_access_headers_are_sent_only_with_both_credentials(self):
        self.run_gate(CF_ACCESS_CLIENT_ID="id-1", CF_ACCESS_CLIENT_SECRET="secret-1")
        args = self.curl_args()
        self.assertIn("CF-Access-Client-Id: id-1", args)
        self.assertIn("CF-Access-Client-Secret: secret-1", args)
        os.remove(self.args_out)
        self.run_gate(CF_ACCESS_CLIENT_ID="id-1")
        self.assertFalse(any(a.startswith("CF-Access-") for a in self.curl_args()))

    def test_every_query_is_time_capped(self):
        self.run_gate()
        args = self.curl_args()
        self.assertEqual(2, args.count("--max-time"))


class ImplementPlanInflightWiringTest(unittest.TestCase):
    """The gate's verdict must actually stop Implement Plan from submitting."""

    def setUp(self):
        with open(_IMPLEMENT_PLAN) as f:
            self.jobs = yaml.safe_load(f)["jobs"]

    def test_register_workstream_runs_the_gate_on_the_registered_workstream(self):
        register = self.jobs["register-workstream"]
        self.assertEqual("${{ steps.inflight.outputs.inflight }}",
                         register["outputs"]["inflight"])
        gate = next(s for s in register["steps"] if s.get("id") == "inflight")
        self.assertEqual("bash ./tools/ci/workstream-idle.sh", gate["run"].strip())
        self.assertEqual("${{ steps.register.outputs.workstream_id }}",
                         gate["env"]["WORKSTREAM_ID"])
        self.assertTrue(any(s.get("id") == "register" for s in register["steps"]))

    def test_build_prompt_refuses_while_a_job_is_in_flight(self):
        self.assertIn("needs.register-workstream.outputs.inflight != 'true'",
                      self.jobs["build-prompt"]["if"])


if __name__ == "__main__":
    unittest.main()
