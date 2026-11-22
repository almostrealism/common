"""detect-protected-file-changes.sh: the right grant for the files a branch changes.

The automatic remediation jobs run with the default sensitive-file protections,
so a branch whose own work is in a protected file produces a review that cannot
fix it. This script reads the branch's changed-file list from the trusted
compare API and emits exactly the operator grant each protected-file kind needs:
``SENSITIVE_FILE_PROTECTION_ENABLED=false`` for a CI/workflow file on a non-ci/
branch, ``BYPASS_AGENT_PERMISSION_PROMPTS=true`` for anything under ``.claude/``.
A branch that changes no protected file is left with today's protections.
"""
import os
import stat
import subprocess
import tempfile
import unittest

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_SCRIPT = os.path.join(_REPO_ROOT, "tools", "ci", "detect-protected-file-changes.sh")

# A gh stand-in: prints the newline-separated filenames in FAKE_FILES (the
# script passes --jq '.files[].filename', so gh already yields bare names), or
# fails like an unreachable API when FAKE_GH_FAIL is set. When FAKE_HUGE is set
# it instead emits a protected CI file followed by FAKE_HUGE generated paths, a
# changed-file list far larger than a pipe's buffer — the shape that trips the
# SIGPIPE skip this script guards against. When FAKE_PAGES is set it behaves
# like a paginated compare response for a long branch: it applies the script's
# own --jq filter, with real jq, to a first page that lists the changed files
# and a later page that carries only commits, as the compare API does.
_FAKE_GH = """#!/usr/bin/env bash
if [ -n "${FAKE_GH_FAIL:-}" ]; then
  echo "gh: could not reach api" >&2
  exit 1
fi
if [ -n "${FAKE_PAGES:-}" ]; then
  filter=""
  while [ $# -gt 0 ]; do
    if [ "$1" = "--jq" ]; then filter="$2"; shift; fi
    shift
  done
  printf '%s\\n' '{"commits":[{}],"files":[{"filename":"tools/ci/foo.sh"}]}' \\
      '{"commits":[{}]}' | jq -r "$filter"
  exit $?
fi
if [ -n "${FAKE_HUGE:-}" ]; then
  printf 'tools/ci/foo.sh\\n'
  seq 1 "$FAKE_HUGE" | sed 's/^/f/'
  exit 0
fi
printf '%s\\n' "$FAKE_FILES"
"""


class DetectProtectedFileChangesTest(unittest.TestCase):
    """Runs the script against the fake gh and reads the grants it writes."""

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory(prefix="detect-protected-")
        self.addCleanup(self.dir.cleanup)
        bin_gh = os.path.join(self.dir.name, "gh")
        with open(bin_gh, "w") as f:
            f.write(_FAKE_GH)
        os.chmod(bin_gh, os.stat(bin_gh).st_mode | stat.S_IXUSR)
        self.github_env = os.path.join(self.dir.name, "github.env")

    def run_detect(self, files, branch="qa/x", base="master"):
        env = {k: v for k, v in os.environ.items()
               if k not in ("SENSITIVE_FILE_PROTECTION_ENABLED",
                            "BYPASS_AGENT_PERMISSION_PROMPTS")}
        env.update(PATH=self.dir.name + os.pathsep + env.get("PATH", ""),
                   BRANCH=branch, BASE_BRANCH=base,
                   GITHUB_REPOSITORY="almostrealism/common",
                   GITHUB_ENV=self.github_env,
                   FAKE_FILES="\n".join(files))
        return subprocess.run(["bash", _SCRIPT], env=env,
                              capture_output=True, text=True, timeout=60)

    def grants(self):
        if not os.path.exists(self.github_env):
            return {}
        out = {}
        with open(self.github_env) as f:
            for line in f:
                line = line.strip()
                if "=" in line:
                    k, v = line.split("=", 1)
                    out[k] = v
        return out

    def test_a_claude_file_grants_the_permission_prompt_bypass(self):
        result = self.run_detect([".claude/hooks/block-x.sh", "src/main/java/Foo.java"])
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual({"BYPASS_AGENT_PERMISSION_PROMPTS": "true"}, self.grants())

    def test_a_ci_file_on_a_non_ci_branch_grants_the_sensitive_file_bypass(self):
        result = self.run_detect(["tools/ci/foo.sh"], branch="qa/x")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual({"SENSITIVE_FILE_PROTECTION_ENABLED": "false"}, self.grants())

    def test_a_workflow_file_counts_as_a_ci_file(self):
        result = self.run_detect([".github/workflows/analysis.yaml"], branch="feature/y")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual({"SENSITIVE_FILE_PROTECTION_ENABLED": "false"}, self.grants())

    def test_a_ci_file_on_a_ci_branch_needs_no_grant(self):
        # A ci/ branch is already exempt from both the harness lock and
        # check-ci-file-lock.sh, so no sensitive-file grant is emitted for it.
        result = self.run_detect(["tools/ci/foo.sh", ".github/actions/x/action.yml"],
                                 branch="ci/pipeline-change")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual({}, self.grants())

    def test_a_claude_file_on_a_ci_branch_still_grants_the_bypass(self):
        # The agent runtime refuses .claude/ writes regardless of branch name.
        result = self.run_detect([".claude/settings.json"], branch="ci/pipeline-change")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual({"BYPASS_AGENT_PERMISSION_PROMPTS": "true"}, self.grants())

    def test_both_kinds_grant_both(self):
        result = self.run_detect(["tools/ci/foo.sh", ".claude/hooks/x.sh"], branch="qa/x")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(
            {"SENSITIVE_FILE_PROTECTION_ENABLED": "false",
             "BYPASS_AGENT_PERMISSION_PROMPTS": "true"},
            self.grants())

    def test_unprotected_changes_grant_nothing(self):
        result = self.run_detect(["src/main/java/Foo.java", "README.md",
                                  "engine/ml/src/test/java/FooTest.java"])
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual({}, self.grants())

    def test_a_ci_file_early_in_a_large_list_still_grants(self):
        # Regression: the match is read with a here-string, not a pipe, so
        # grep -q closing the stream on an early match cannot SIGPIPE the
        # upstream producer and, under `set -o pipefail`, make the `if` false
        # — which would silently drop the CI grant on a long file list. The
        # protected file comes first in a list far larger than a pipe's
        # buffer, the exact shape that trips the pipe form.
        env = {k: v for k, v in os.environ.items()
               if k not in ("SENSITIVE_FILE_PROTECTION_ENABLED",
                            "BYPASS_AGENT_PERMISSION_PROMPTS")}
        env.update(PATH=self.dir.name + os.pathsep + env.get("PATH", ""),
                   BRANCH="qa/x", BASE_BRANCH="master",
                   GITHUB_REPOSITORY="almostrealism/common",
                   GITHUB_ENV=self.github_env, FAKE_HUGE="40000")
        result = subprocess.run(["bash", _SCRIPT], env=env,
                                capture_output=True, text=True, timeout=120)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual({"SENSITIVE_FILE_PROTECTION_ENABLED": "false"}, self.grants())

    def test_a_list_at_the_compare_cap_warns_that_a_grant_may_be_missed(self):
        # The compare API lists at most 300 changed files. A list that reaches
        # the cap may be truncated, so a protected file past it would go
        # ungranted. The grant still fires for the files that are listed, and a
        # warning makes the possible under-grant visible in the log.
        env = {k: v for k, v in os.environ.items()
               if k not in ("SENSITIVE_FILE_PROTECTION_ENABLED",
                            "BYPASS_AGENT_PERMISSION_PROMPTS")}
        env.update(PATH=self.dir.name + os.pathsep + env.get("PATH", ""),
                   BRANCH="qa/x", BASE_BRANCH="master",
                   GITHUB_REPOSITORY="almostrealism/common",
                   GITHUB_ENV=self.github_env, FAKE_HUGE="400")
        result = subprocess.run(["bash", _SCRIPT], env=env,
                                capture_output=True, text=True, timeout=120)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual({"SENSITIVE_FILE_PROTECTION_ENABLED": "false"}, self.grants())
        self.assertIn("::warning::", result.stdout)
        self.assertIn("caps the changed-file list at 300", result.stdout)

    def test_a_short_list_does_not_warn_about_the_compare_cap(self):
        # Below the cap there is no truncation to warn about.
        result = self.run_detect(["tools/ci/foo.sh", "src/main/java/Foo.java"])
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertNotIn("::warning::", result.stdout)

    def test_a_later_page_without_a_file_list_still_grants(self):
        # Regression: a branch with more than one page of commits gets later
        # compare pages that carry no file list. The filter must read those as
        # empty rather than fail on them, or the step fails and the grant the
        # first page called for is withheld.
        env = {k: v for k, v in os.environ.items()
               if k not in ("SENSITIVE_FILE_PROTECTION_ENABLED",
                            "BYPASS_AGENT_PERMISSION_PROMPTS")}
        env.update(PATH=self.dir.name + os.pathsep + env.get("PATH", ""),
                   BRANCH="qa/x", BASE_BRANCH="master",
                   GITHUB_REPOSITORY="almostrealism/common",
                   GITHUB_ENV=self.github_env, FAKE_PAGES="1")
        result = subprocess.run(["bash", _SCRIPT], env=env,
                                capture_output=True, text=True, timeout=60)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual({"SENSITIVE_FILE_PROTECTION_ENABLED": "false"}, self.grants())

    def test_a_vendored_tools_ci_path_is_not_mistaken_for_a_ci_file(self):
        # The pattern is anchored at the start of the path.
        result = self.run_detect(["vendor/tools/ci/example.sh"])
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual({}, self.grants())

    def test_an_unlistable_diff_fails_without_granting(self):
        # No evidence is not "no protected files changed": withhold the grant
        # and fail rather than under-grant silently.
        env = {k: v for k, v in os.environ.items()
               if k not in ("SENSITIVE_FILE_PROTECTION_ENABLED",
                            "BYPASS_AGENT_PERMISSION_PROMPTS")}
        env.update(PATH=self.dir.name + os.pathsep + env.get("PATH", ""),
                   BRANCH="qa/x", BASE_BRANCH="master",
                   GITHUB_REPOSITORY="almostrealism/common",
                   GITHUB_ENV=self.github_env, FAKE_GH_FAIL="1", FAKE_FILES="")
        result = subprocess.run(["bash", _SCRIPT], env=env,
                                capture_output=True, text=True, timeout=60)
        self.assertEqual(1, result.returncode)
        self.assertEqual({}, self.grants())


if __name__ == "__main__":
    unittest.main()
