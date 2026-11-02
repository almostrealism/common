"""Guard: the consult evals run in their own job, on their own inputs.

The consult evaluation set (``tools/mcp/manager/eval``) retrieves over the
documentation corpus and a snapshot index of the source trees, both staged
from the checkout. A documentation edit can therefore break it, while the
``python-tests`` job is gated on Python sources only. The evals once lived
inside ``python-tests``: a docs-only change broke them, nothing ran them on
that pull request, and the breakage surfaced only after merge, in coverage-qa.

These tests pin the split: ``python-tests`` cannot reach the evals, the evals
run in ``python-evals``, which is gated on every file type the evals read (and
not on ``code_changed``, which a docs-only change leaves false), and an eval
failure is gated into ``all-checks`` like any other required job.
"""

import os
import re
import sys
import unittest

import yaml

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_ANALYSIS = os.path.join(_REPO_ROOT, ".github", "workflows", "analysis.yaml")
_EVAL_DIR = os.path.join(_REPO_ROOT, "tools", "mcp", "manager", "eval")
_COMMON_DIR = os.path.join(_REPO_ROOT, "tools", "mcp", "common")

if _COMMON_DIR not in sys.path:
    sys.path.insert(0, _COMMON_DIR)

import docs_retriever  # noqa: E402
import snapshot_index  # noqa: E402


def _jobs():
    with open(_ANALYSIS) as f:
        return yaml.safe_load(f)["jobs"]


def _filter_script():
    steps = _jobs()["changes"]["steps"]
    return next(step["run"] for step in steps if step.get("id") == "filter")


def _eval_pathspec():
    """The pathspecs the ``EVAL_FILES`` detection passes to ``git diff``."""
    match = re.search(r"EVAL_FILES=\$\(git diff --name-only \"\$BASE\" HEAD -- (.*?)\|\| true\)",
                      _filter_script(), re.S)
    if not match:
        raise AssertionError("no EVAL_FILES detection in the changes job's filter step")
    return re.findall(r"'([^']+)'", match.group(1))


def _run_lines(job):
    return [step["run"] for step in job["steps"] if "run" in step]


class EvalsChangedFlagTests(unittest.TestCase):

    def test_the_flag_is_an_output_of_the_changes_job(self):
        outputs = _jobs()["changes"]["outputs"]
        self.assertEqual("${{ steps.filter.outputs.evals_changed }}", outputs["evals_changed"])

    def test_every_full_pipeline_run_sets_the_flag(self):
        body = re.search(r"set_all_flags_true\(\) \{\n(.*?)\n\s*\}\n", _filter_script(), re.S).group(1)
        self.assertIn('echo "evals_changed=true"', body)

    def test_the_pull_request_detection_sets_both_values(self):
        script = _filter_script()
        self.assertIn('echo "evals_changed=true"', script.split("EVAL_FILES=", 1)[1])
        self.assertIn('echo "evals_changed=false"', script.split("EVAL_FILES=", 1)[1])

    def test_the_flag_covers_every_file_type_the_evals_read(self):
        """The corpus is every doc suffix; the snapshot index reads Java and scripts."""
        pathspec = _eval_pathspec()
        suffixes = set(docs_retriever._DOC_SUFFIXES) | set(snapshot_index._SCRIPT_SUFFIXES) | {".java"}
        for suffix in sorted(suffixes):
            with self.subTest(suffix=suffix):
                self.assertIn("*" + suffix, pathspec)

    def test_the_flag_covers_the_eval_data_and_the_corpus_definition(self):
        pathspec = _eval_pathspec()
        self.assertIn("tools/mcp/manager/eval/", pathspec)
        # The Dockerfile's COPY directives define the staged corpus.
        self.assertIn("tools/mcp/manager/Dockerfile", pathspec)


class PythonEvalsJobTests(unittest.TestCase):

    def test_the_job_runs_on_its_own_flag_alone(self):
        """A docs-only change leaves code_changed false, and is what breaks the evals."""
        condition = " ".join(str(_jobs()["python-evals"]["if"]).split())
        self.assertEqual("needs.changes.outputs.evals_changed == 'true'", condition)

    def test_the_job_runs_the_eval_suite(self):
        runs = " ".join(_run_lines(_jobs()["python-evals"]))
        self.assertIn('unittest discover -v -s tools/mcp/manager/eval -p "test_*.py"', runs)

    def test_the_job_uploads_its_output_for_auto_resolve_python(self):
        uploads = [step for step in _jobs()["python-evals"]["steps"]
                   if str(step.get("uses", "")).startswith("actions/upload-artifact")]
        self.assertEqual(["python-evals-output"], [step["with"]["name"] for step in uploads])
        downloads = [step["with"]["name"] for step in _jobs()["auto-resolve-python"]["steps"]
                     if str(step.get("uses", "")).startswith("actions/download-artifact")]
        self.assertIn("python-evals-output", downloads)

    def test_python_tests_cannot_reach_the_evals(self):
        """``unittest discover`` recurses only into packages, so eval/ must not be one."""
        self.assertFalse(os.path.exists(os.path.join(_EVAL_DIR, "__init__.py")))
        runs = " ".join(_run_lines(_jobs()["python-tests"]))
        self.assertNotIn("tools/mcp/manager/eval", runs)

    def test_all_checks_requires_the_job(self):
        job = _jobs()["all-checks"]
        self.assertIn("python-evals", job["needs"])
        runs = " ".join(_run_lines(job))
        self.assertIn('check_job "python-evals"', runs)


if __name__ == "__main__":
    unittest.main()
