"""Tests for the scripts that gate and set up a task-planning round.

The task-planning job in master-agent-dispatch.yaml starts a round only when
the open-PR backlog has room (``open-pr-backlog.sh``) and the release has a
claimable task (``tracker-claimable.sh``), and registers the round's
workstream with the tracker "planner" role (``register-workstream.sh``).
Each gate decides whether anything is created at all, so each is run here
for real: the scripts talk to a local stand-in for the controller, or to a
fake ``gh`` on the PATH.
"""

import json
import os
import stat
import subprocess
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_CI = os.path.join(_REPO_ROOT, "tools", "ci")


class _ControllerStub:
    """A local stand-in for the FlowTree controller.

    ``claimable`` is the (status, body) the claimable endpoint answers with;
    ``existing`` makes workstream registration report an existing workstream;
    ``update`` is the (status, body) the ``/update`` endpoint answers with;
    ``claimable_delay`` sleeps that many seconds before answering the
    claimable endpoint, to stand in for a controller that accepts the
    connection but stops responding.
    Every request is recorded as (method, path, query, headers, body).
    """

    def __init__(self, claimable=(200, {"ok": True, "count": 0}), existing=False,
                 update=(200, {"ok": True}), claimable_delay=0):
        self.claimable = claimable
        self.existing = existing
        self.update = update
        self.claimable_delay = claimable_delay
        self.seen = []
        stub = self

        class Handler(BaseHTTPRequestHandler):
            def _record(self, body):
                url = urlparse(self.path)
                stub.seen.append((self.command, url.path, parse_qs(url.query),
                                  dict(self.headers), body))
                return url.path

            def _reply(self, status, payload):
                data = payload if isinstance(payload, bytes) else json.dumps(payload).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def do_GET(self):
                self._record(None)
                if stub.claimable_delay:
                    time.sleep(stub.claimable_delay)
                self._reply(*stub.claimable)

            def do_POST(self):
                length = int(self.headers.get("Content-Length") or 0)
                body = json.loads(self.rfile.read(length) or b"{}")
                path = self._record(body)
                if path.endswith("/update"):
                    self._reply(*stub.update)
                else:
                    self._reply(200, {"workstreamId": "ws-1", "existing": stub.existing})

            def log_message(self, *args):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.url = "http://127.0.0.1:%d" % self.server.server_address[1]
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def close(self):
        self.server.shutdown()
        self.server.server_close()


def _run(script, env):
    """Runs a CI script and returns (returncode, {key: value} outputs, stdout)."""
    full = dict(os.environ)
    full.pop("GITHUB_OUTPUT", None)
    full.update(env)
    result = subprocess.run(["bash", os.path.join(_CI, script)], env=full,
                            capture_output=True, text=True, timeout=60)
    outputs = dict(line.split("=", 1) for line in result.stdout.splitlines()
                   if "=" in line and not line.startswith("::"))
    return result.returncode, outputs, result.stdout + result.stderr


class TrackerClaimableTests(unittest.TestCase):
    """tracker-claimable.sh starts a round only on a positive count."""

    def _ask(self, stub, **extra):
        self.addCleanup(stub.close)
        env = {"TRACKER_PROJECT": "Common", "TRACKER_RELEASE": "Common 1.2",
               "CONTROLLER_URL": stub.url + "/"}
        env.update(extra)
        return _run("tracker-claimable.sh", env)

    def test_a_claimable_task_starts_a_round(self):
        stub = _ControllerStub(claimable=(200, {"ok": True, "count": 2}))
        code, out, _ = self._ask(stub)
        self.assertEqual(0, code)
        self.assertEqual(("true", "2"), (out["run"], out["claimable"]))
        _, path, query, _, _ = stub.seen[0]
        self.assertEqual("/api/tracker/claimable", path)
        self.assertEqual({"project": ["Common"], "release": ["Common 1.2"]}, query)

    def test_nothing_claimable_starts_nothing(self):
        code, out, _ = self._ask(_ControllerStub(claimable=(200, {"ok": True, "count": 0})))
        self.assertEqual((0, "false"), (code, out["run"]))

    def test_an_error_from_the_controller_starts_nothing(self):
        code, out, _ = self._ask(_ControllerStub(claimable=(503, {"ok": False})))
        self.assertEqual((0, "false"), (code, out["run"]))

    def test_an_unexpected_answer_starts_nothing(self):
        for answer in ({"ok": True}, {"ok": False, "count": 3}, {"ok": True, "count": "3"}):
            with self.subTest(answer=answer):
                code, out, _ = self._ask(_ControllerStub(claimable=(200, answer)))
                self.assertEqual((0, "false"), (code, out["run"]))

    def test_an_unreachable_controller_starts_nothing(self):
        stub = _ControllerStub()
        url = stub.url
        stub.close()
        code, out, _ = _run("tracker-claimable.sh", {
            "TRACKER_PROJECT": "Common", "TRACKER_RELEASE": "Common 1.2",
            "CONTROLLER_URL": url})
        self.assertEqual((0, "false"), (code, out["run"]))

    def test_a_hanging_controller_starts_nothing_within_the_budget(self):
        # The controller accepts the connection but never answers in time;
        # --max-time must make curl give up so the fail-closed fallback runs
        # instead of the request holding the concurrency slot indefinitely.
        stub = _ControllerStub(claimable=(200, {"ok": True, "count": 5}),
                               claimable_delay=30)
        started = time.monotonic()
        code, out, _ = self._ask(stub, CURL_MAX_TIME="1", CURL_CONNECT_TIMEOUT="2")
        self.assertEqual((0, "false"), (code, out["run"]))
        self.assertLess(time.monotonic() - started, 15)

    def test_the_access_token_is_sent_when_both_halves_are_set(self):
        stub = _ControllerStub(claimable=(200, {"ok": True, "count": 1}))
        self._ask(stub, CF_ACCESS_CLIENT_ID="id", CF_ACCESS_CLIENT_SECRET="secret")
        headers = stub.seen[0][3]
        self.assertEqual("id", headers.get("CF-Access-Client-Id"))
        self.assertEqual("secret", headers.get("CF-Access-Client-Secret"))

    def test_missing_names_are_refused(self):
        code, _, _ = _run("tracker-claimable.sh", {
            "TRACKER_PROJECT": "", "TRACKER_RELEASE": "Common 1.2",
            "CONTROLLER_URL": "http://127.0.0.1:1"})
        self.assertEqual(1, code)


class OpenPrBacklogTests(unittest.TestCase):
    """open-pr-backlog.sh leaves room for a round at or below the limit."""

    def _decide(self, open_prs, limit="6", force="false"):
        bin_dir = tempfile.mkdtemp(prefix="fake-gh-")
        fake = os.path.join(bin_dir, "gh")
        with open(fake, "w") as f:
            f.write("#!/usr/bin/env bash\necho %d\n" % open_prs)
        os.chmod(fake, os.stat(fake).st_mode | stat.S_IEXEC)
        return _run("open-pr-backlog.sh", {
            "PATH": bin_dir + os.pathsep + os.environ.get("PATH", ""),
            "MAX_OPEN_PRS": limit, "FORCE": force, "GITHUB_TOKEN": "t"})

    def test_under_and_at_the_limit_there_is_room(self):
        for count in (0, 6):
            with self.subTest(open_prs=count):
                code, out, _ = self._decide(count)
                self.assertEqual((0, "true"), (code, out["needs_new_branch"]))
                self.assertEqual(str(count), out["open_prs"])

    def test_over_the_limit_there_is_not(self):
        code, out, _ = self._decide(7)
        self.assertEqual((0, "false"), (code, out["needs_new_branch"]))

    def test_force_ignores_the_backlog(self):
        _, out, _ = self._decide(50, force="true")
        self.assertEqual("true", out["needs_new_branch"])

    def test_force_starts_a_round_even_when_github_cannot_be_queried(self):
        bin_dir = tempfile.mkdtemp(prefix="failing-gh-")
        fake = os.path.join(bin_dir, "gh")
        with open(fake, "w") as f:
            f.write("#!/usr/bin/env bash\necho 'gh unavailable' >&2\nexit 1\n")
        os.chmod(fake, os.stat(fake).st_mode | stat.S_IEXEC)
        code, out, _ = _run("open-pr-backlog.sh", {
            "PATH": bin_dir + os.pathsep + os.environ.get("PATH", ""),
            "MAX_OPEN_PRS": "6", "FORCE": "true", "GITHUB_TOKEN": "t"})
        self.assertEqual(0, code)
        self.assertEqual("true", out["needs_new_branch"])
        self.assertEqual("not-checked", out["open_prs"])

    def test_a_missing_limit_is_refused(self):
        code, _, _ = self._decide(0, limit="")
        self.assertEqual(1, code)


class RegisterWorkstreamRoleTests(unittest.TestCase):
    """register-workstream.sh grants tracker roles on new and existing workstreams."""

    def _register(self, stub, capabilities):
        self.addCleanup(stub.close)
        return _run("register-workstream.sh", {
            "BRANCH": "project/task-1", "BASE_BRANCH": "master",
            "CONTROLLER_URL": stub.url, "TRACKER_CAPABILITIES": capabilities,
            "PLAN_FILE": ""})

    def test_roles_are_sent_with_the_registration(self):
        stub = _ControllerStub()
        code, _, log = self._register(stub, " planner , steward,")
        self.assertEqual(0, code, log)
        self.assertEqual(1, len(stub.seen))
        self.assertEqual(["planner", "steward"], stub.seen[0][4]["trackerCapabilities"])

    def test_no_roles_sends_no_roles(self):
        stub = _ControllerStub()
        self._register(stub, "")
        self.assertNotIn("trackerCapabilities", stub.seen[0][4])

    def test_an_existing_workstream_is_updated_with_the_roles(self):
        stub = _ControllerStub(existing=True)
        code, _, log = self._register(stub, "planner")
        self.assertEqual(0, code, log)
        self.assertEqual(2, len(stub.seen))
        method, path, _, _, body = stub.seen[1]
        self.assertEqual(("POST", "/api/workstreams/ws-1/update"), (method, path))
        self.assertEqual({"trackerCapabilities": ["planner"]}, body)

    def test_an_existing_workstream_without_roles_or_plan_is_left_alone(self):
        stub = _ControllerStub(existing=True)
        self._register(stub, "")
        self.assertEqual(1, len(stub.seen))

    def test_a_failed_capability_update_fails_the_registration(self):
        # A task-planning agent submitted without its "planner" role cannot
        # claim a task, so a dropped capability update must fail the step
        # rather than warn and exit 0.
        stub = _ControllerStub(existing=True, update=(503, {"ok": False}))
        code, _, log = self._register(stub, "planner")
        self.assertEqual(1, code, log)
        self.assertEqual(2, len(stub.seen))

    def test_a_failed_plan_only_update_stays_best_effort(self):
        # No capabilities requested: a failed planning-document update warns
        # but still exits 0, preserving the pre-existing behavior.
        stub = _ControllerStub(existing=True, update=(503, {"ok": False}))
        self.addCleanup(stub.close)
        code, out, log = _run("register-workstream.sh", {
            "BRANCH": "project/task-1", "BASE_BRANCH": "master",
            "CONTROLLER_URL": stub.url, "TRACKER_CAPABILITIES": "",
            "PLAN_FILE": "docs/plans/PLAN.md"})
        self.assertEqual(0, code, log)
        self.assertEqual("ws-1", out["workstream_id"])
        self.assertEqual(2, len(stub.seen))


if __name__ == "__main__":
    unittest.main()
