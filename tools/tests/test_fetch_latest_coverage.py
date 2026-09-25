"""Guard: fetch-latest-coverage.sh honours ALLOW_RECOMPUTE=false.

The coverage-qa job runs on a GitHub-hosted runner, where the Java recompute
path (``mvn install`` plus the full ``mvn test`` suite) would take hours and
produce a report that does not match the self-hosted coverage lanes. The job
sets ``ALLOW_RECOMPUTE=false`` so a missing master artifact, or ``FORCE=true``,
fails fast instead. These tests run the real script with a fake ``mvn`` first
on PATH that records every invocation, so they prove whether Maven would have
been started without ever starting it.

The reuse path is exercised the same way, with a fake ``curl`` that serves the
artifact listing from local fixture files.
"""

import json
import os
import shutil
import subprocess
import tempfile
import unittest
import zipfile

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_SCRIPT = os.path.join(_REPO_ROOT, "tools", "ci", "coverage", "fetch-latest-coverage.sh")

# Records its arguments and fails, so a permitted recompute stops right after
# the first Maven call instead of continuing into the rest of the script.
_FAKE_MVN = """#!/usr/bin/env bash
echo "mvn $*" >> "{log}"
exit 1
"""


class FetchLatestCoverageRecomputeGateTests(unittest.TestCase):
    """End-to-end runs of the Java recompute gate."""

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="fetch-latest-coverage-test-")
        self.bin_dir = os.path.join(self.tmp, "bin")
        os.makedirs(self.bin_dir)
        self.mvn_log = os.path.join(self.tmp, "mvn.log")
        mvn = os.path.join(self.bin_dir, "mvn")
        with open(mvn, "w") as f:
            f.write(_FAKE_MVN.format(log=self.mvn_log))
        os.chmod(mvn, 0o755)
        self.output_dir = os.path.join(self.tmp, "out")

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _run(self, **overrides):
        """Runs the script with no GitHub credentials, so reuse always fails."""
        env = {
            "PATH": self.bin_dir + os.pathsep + os.environ.get("PATH", ""),
            "HOME": self.tmp,
            "OUTPUT_DIR": self.output_dir,
        }
        env.update(overrides)
        return subprocess.run(["bash", _SCRIPT], cwd=self.tmp, env=env,
                              capture_output=True, text=True, timeout=60)

    def _mvn_calls(self):
        if not os.path.exists(self.mvn_log):
            return []
        with open(self.mvn_log) as f:
            return f.read().splitlines()

    def test_missing_artifact_fails_fast_when_recompute_is_disallowed(self):
        result = self._run(ALLOW_RECOMPUTE="false")
        self.assertEqual(1, result.returncode)
        self.assertEqual([], self._mvn_calls())
        self.assertIn("ALLOW_RECOMPUTE=false", result.stderr)
        self.assertIn("Build and Test", result.stderr)
        self.assertFalse(os.path.exists(os.path.join(self.output_dir, "coverage.xml")))

    def test_force_fails_fast_when_recompute_is_disallowed(self):
        result = self._run(ALLOW_RECOMPUTE="false", FORCE="true")
        self.assertEqual(1, result.returncode)
        self.assertEqual([], self._mvn_calls())
        self.assertIn("FORCE=true", result.stderr)

    def test_missing_artifact_recomputes_by_default(self):
        result = self._run()
        self.assertNotEqual(0, result.returncode)
        calls = self._mvn_calls()
        self.assertEqual(1, len(calls))
        self.assertTrue(calls[0].startswith("mvn install -DskipTests"), calls[0])
        self.assertNotIn("ALLOW_RECOMPUTE=false", result.stderr)
        self.assertIn("Falling back to a fresh recompute", result.stdout)

    def test_only_the_literal_false_disables_recompute(self):
        result = self._run(ALLOW_RECOMPUTE="true", FORCE="true")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(1, len(self._mvn_calls()))


# Serves the artifacts listing from page-<n>.json files (failing for a page
# that has no file) and the artifact download from artifact.zip, logging
# every URL it is asked for.
_FAKE_CURL = """#!/usr/bin/env bash
out=""
url=""
while [ $# -gt 0 ]; do
    case "$1" in
        -o) out="$2"; shift 2 ;;
        http*) url="$1"; shift ;;
        *) shift ;;
    esac
done
echo "$url" >> "{log}"
case "$url" in
    */zip) cp "{fixtures}/artifact.zip" "$out" ;;
    *page=*)
        page="${{url##*page=}}"
        [ -f "{fixtures}/page-$page.json" ] || exit 22
        cat "{fixtures}/page-$page.json" ;;
    *) exit 22 ;;
esac
"""


def _artifact(artifact_id, branch, created_at="2026-01-01T00:00:00Z"):
    return {"id": artifact_id, "expired": False, "created_at": created_at,
            "workflow_run": {"id": artifact_id * 10, "head_branch": branch}}


@unittest.skipUnless(shutil.which("jq") and shutil.which("unzip"),
                     "fetch-latest-coverage.sh needs jq and unzip")
class FetchLatestCoverageArtifactPagingTests(unittest.TestCase):
    """The reuse path pages through the repo-wide artifact listing.

    Pull-request runs upload the same artifact name, so the newest master
    artifact is not necessarily on the first page. With ALLOW_RECOMPUTE=false
    a miss has no fallback, so the search has to reach later pages. Run from
    outside the checkout, the script stops at the missing requirements file
    right after the Java stage, which is all these tests need.
    """

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="fetch-latest-coverage-paging-")
        self.bin_dir = os.path.join(self.tmp, "bin")
        self.fixtures = os.path.join(self.tmp, "fixtures")
        os.makedirs(self.bin_dir)
        os.makedirs(self.fixtures)
        self.request_log = os.path.join(self.tmp, "requests.log")
        fake = os.path.join(self.bin_dir, "curl")
        with open(fake, "w") as f:
            f.write(_FAKE_CURL.format(log=self.request_log, fixtures=self.fixtures))
        os.chmod(fake, 0o755)
        with zipfile.ZipFile(os.path.join(self.fixtures, "artifact.zip"), "w") as z:
            z.writestr("coverage.xml", "<report name=\"reused\"/>")
        self.output_dir = os.path.join(self.tmp, "out")

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _page(self, number, artifacts):
        with open(os.path.join(self.fixtures, "page-%d.json" % number), "w") as f:
            json.dump({"artifacts": artifacts}, f)

    def _pr_page(self, number, count=100):
        base = number * 1000
        self._page(number, [_artifact(base + i, "feature/pr-%d" % i) for i in range(count)])

    def _run(self, **overrides):
        env = {
            "PATH": self.bin_dir + os.pathsep + os.environ.get("PATH", ""),
            "HOME": self.tmp,
            "OUTPUT_DIR": self.output_dir,
            "GITHUB_REPOSITORY": "example/repo",
            "GITHUB_TOKEN": "token",
            "ALLOW_RECOMPUTE": "false",
        }
        env.update(overrides)
        return subprocess.run(["bash", _SCRIPT], cwd=self.tmp, env=env,
                              capture_output=True, text=True, timeout=60)

    def _requested_pages(self):
        with open(self.request_log) as f:
            urls = f.read().splitlines()
        return [int(u.rsplit("page=", 1)[1]) for u in urls if "page=" in u]

    def _reused_report(self):
        path = os.path.join(self.output_dir, "coverage.xml")
        if not os.path.exists(path):
            return None
        with open(path) as f:
            return f.read()

    def test_master_artifact_on_a_later_page_is_reused(self):
        self._pr_page(1)
        self._page(2, [_artifact(7, "feature/x"), _artifact(8, "master")])
        result = self._run()
        self.assertEqual([1, 2], self._requested_pages())
        self.assertIn("Reused merged Java coverage report from artifact 8 (run 80 on master)",
                      result.stdout)
        self.assertEqual("<report name=\"reused\"/>", self._reused_report())
        self.assertNotIn("ALLOW_RECOMPUTE=false", result.stderr)

    def test_hit_on_a_full_first_page_reads_no_further(self):
        self._page(1, [_artifact(i, "feature/pr") for i in range(1, 100)]
                   + [_artifact(500, "master")])
        self._pr_page(2)
        result = self._run()
        self.assertEqual([1], self._requested_pages())
        self.assertIn("from artifact 500 (run 5000 on master)", result.stdout)

    def test_newest_matching_artifact_on_a_page_wins(self):
        self._page(1, [_artifact(3, "master", "2026-01-01T00:00:00Z"),
                       _artifact(4, "master", "2026-02-01T00:00:00Z"),
                       _artifact(6, "feature/newer", "2026-03-01T00:00:00Z")])
        result = self._run()
        self.assertIn("from artifact 4 (run 40 on master)", result.stdout)

    def test_short_page_ends_the_search_and_fails_fast(self):
        self._page(1, [_artifact(1, "feature/a"), _artifact(2, "feature/b")])
        self._pr_page(2)
        result = self._run()
        self.assertEqual(1, result.returncode)
        self.assertEqual([1], self._requested_pages())
        self.assertIn("in the 1 most recent page(s)", result.stdout)
        self.assertIn("ALLOW_RECOMPUTE=false", result.stderr)
        self.assertIsNone(self._reused_report())

    def test_page_limit_bounds_the_search(self):
        for number in (1, 2, 3):
            self._pr_page(number)
        self._page(4, [_artifact(9, "master")])
        result = self._run(ARTIFACT_PAGE_LIMIT="2")
        self.assertEqual(1, result.returncode)
        self.assertEqual([1, 2], self._requested_pages())
        self.assertIn("in the 2 most recent page(s)", result.stdout)
        self.assertIsNone(self._reused_report())

    def test_listing_failure_on_a_later_page_is_a_miss(self):
        self._pr_page(1)
        result = self._run()
        self.assertEqual(1, result.returncode)
        self.assertEqual([1, 2], self._requested_pages())
        self.assertIn("Could not list merged-coverage-report artifacts (page 2)", result.stdout)
        self.assertIsNone(self._reused_report())

    def test_no_fallback_notice_when_recompute_is_disallowed(self):
        self._page(1, [])
        disallowed = self._run()
        self.assertNotIn("Falling back to a fresh recompute", disallowed.stdout)
        self.assertIn("ALLOW_RECOMPUTE=false", disallowed.stderr)


if __name__ == "__main__":
    unittest.main()
