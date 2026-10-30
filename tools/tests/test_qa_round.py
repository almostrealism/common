"""Guard: the reusable qa-round.yaml renders a round's prompt correctly, for a
consumer that is not a Maven/Java repository as much as for this one.

``qa-round.yaml`` is the reusable workflow every QA round calls. Its prompt-build
step runs one generic builder, ``tools/ci/prompts/build-qa-round-prompt.sh``,
which renders either a framework built-in template chosen by a short selector or
a template the caller supplies by path, read as data from the caller's checkout.
``test_reusable_workflows.py`` pins the workflow's trust rule; this runs the
builder for real — the way ``TaskPlanningReleaseStepTests`` runs the release
step — and proves:

  - the built-in defect-hunt template renders with no surviving ``${...}``
    placeholder, and, with the generic test-runner fragment a non-Maven consumer
    selects, carries none of this framework's Maven/Java tooling instructions;
  - the same template with the default fragment does carry them, so the
    fragment is doing real work rather than being inert;
  - a caller-supplied template is rendered from the caller's checkout with its
    template-vars substituted, and a path that climbs out of the checkout, or a
    fragment name with a ``/``, is refused.
"""

import json
import os
import subprocess
import tempfile
import unittest

import yaml

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_WORKFLOW = os.path.join(_REPO_ROOT, ".github", "workflows", "qa-round.yaml")
_BUILDER = os.path.join(_REPO_ROOT, "tools", "ci", "prompts", "build-qa-round-prompt.sh")

# Tooling this framework's templates name that a non-Maven consumer must not be
# instructed to use.
_MAVEN_ONLY = ("mvn ", "pom.xml", "Maven", "ar-profile-analyzer", "MCP test runner")


def _build_step():
    """The reusable workflow's prompt-build step, whose ``run`` invokes the
    generic builder exactly as the job does."""
    with open(_WORKFLOW) as f:
        job = next(iter(yaml.safe_load(f)["jobs"].values()))
    return next(s for s in job["steps"] if s["name"] == "Build the round's prompt")


class QaRoundPromptBuildStepTests(unittest.TestCase):
    """The build step of qa-round.yaml, run for real against a fixture caller."""

    def setUp(self):
        self.caller = tempfile.mkdtemp(prefix="qa-round-caller-")
        self.addCleanup(self._cleanup)

    def _cleanup(self):
        import shutil
        shutil.rmtree(self.caller, ignore_errors=True)

    def _render(self, prompt, template_vars=None, fragment="test-runner-maven.txt",
                caller_files=None):
        """Runs the build step's own shell with a concrete environment, the way
        a GitHub runner would, and returns (result, prompt-or-None)."""
        for path, text in (caller_files or {}).items():
            full = os.path.join(self.caller, path)
            os.makedirs(os.path.dirname(full), exist_ok=True)
            with open(full, "w") as f:
                f.write(text)
        run = _build_step()["run"].replace("${{ runner.temp }}", self.caller)
        env = dict(os.environ)
        env.update({
            "FRAMEWORK": _REPO_ROOT,
            "GITHUB_WORKSPACE": self.caller,
            "BRANCH": "qa/defect-20261030-000000",
            "BASE_BRANCH": "main",
            "REPO_URL": "git@github.com:example/consumer.git",
            "PROMPT": prompt,
            "TEMPLATE_VARS": json.dumps(template_vars or {}),
            "TEST_RUNNER_FRAGMENT": fragment,
        })
        result = subprocess.run(["bash", "-e", "-c", run], cwd=self.caller, env=env,
                                capture_output=True, text=True, timeout=60)
        output = os.path.join(self.caller, "agent-prompt.txt")
        text = None
        if os.path.exists(output):
            with open(output) as f:
                text = f.read()
        return result, text

    def test_the_builder_is_executable(self):
        """The workflow invokes it with ``bash``, but it should not depend on that."""
        self.assertTrue(os.access(_BUILDER, os.X_OK))

    def test_a_non_maven_consumer_gets_a_clean_defect_hunt_prompt(self):
        """The success criterion: a consumer that is not a Maven repository runs
        the defect hunt with only a caller job and inputs, and the prompt it gets
        has no leftover placeholder and none of this framework's Maven/Java
        tooling instructions."""
        result, prompt = self._render("defect-hunt", fragment="test-runner-generic.txt")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue(prompt)
        self.assertNotIn("${", prompt)
        self.assertIn("qa/defect-20261030-000000", prompt)
        for token in _MAVEN_ONLY:
            with self.subTest(token=token):
                self.assertNotIn(token, prompt,
                                 "a non-Maven consumer must not be told to use " + token)
        # The neutral fragment's own guidance is present in its place.
        self.assertIn("repository's own test runner", prompt)

    def test_the_default_fragment_carries_the_frameworks_tooling(self):
        """The fragment is load-bearing: with the Maven default the same template
        names this framework's tools, so the non-Maven result above is the
        fragment's doing, not a template that never mentioned them."""
        result, prompt = self._render("defect-hunt", fragment="test-runner-maven.txt")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertNotIn("${", prompt)
        self.assertIn("ar-profile-analyzer", prompt)
        self.assertIn("MCP test runner", prompt)

    def test_a_caller_supplied_template_is_rendered_as_data(self):
        """A consumer may pass its own template by a path in its checkout; it is
        read from there, its template-vars substituted, and the three variables
        the workflow always supplies filled in."""
        result, prompt = self._render(
            "qa/custom-round.txt",
            template_vars={"FOCUS": "the import pipeline"},
            caller_files={"qa/custom-round.txt":
                          "Round on ${BRANCH} from ${BASE_BRANCH} of ${REPO_URL}.\n"
                          "Focus on ${FOCUS}.\n"})
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertNotIn("${", prompt)
        self.assertIn("Round on qa/defect-20261030-000000 from main", prompt)
        self.assertIn("git@github.com:example/consumer.git", prompt)
        self.assertIn("Focus on the import pipeline.", prompt)

    def test_a_missing_template_variable_fails_the_step(self):
        """A caller template with a placeholder no variable supplies must fail,
        not ship a prompt with a hole in it."""
        result, prompt = self._render(
            "qa/custom-round.txt",
            caller_files={"qa/custom-round.txt": "Work on ${UNSUPPLIED}.\n"})
        self.assertNotEqual(0, result.returncode)
        self.assertIsNone(prompt)

    def test_invalid_template_vars_fail_the_step(self):
        """A template-vars value the builder rejects must fail the step, even
        when the template never names the rejected key, rather than rendering
        on without it."""
        result, prompt = self._render(
            "qa/custom-round.txt",
            template_vars={"lower": "x"},
            caller_files={"qa/custom-round.txt": "Round on ${BRANCH}.\n"})
        self.assertNotEqual(0, result.returncode)
        self.assertIsNone(prompt)

    def test_a_reserved_template_vars_key_is_refused(self):
        """A caller's template-vars fills ${NAME} placeholders; it must not be
        able to override process state the build itself depends on (PATH, the
        three workflow-supplied names, the selector, or the fragment), so a
        reserved key fails the step rather than being exported."""
        for key in ("PATH", "BRANCH", "TEST_RUNNER_FRAGMENT", "GITHUB_TOKEN"):
            with self.subTest(key=key):
                result, prompt = self._render(
                    "qa/custom-round.txt",
                    template_vars={key: "x"},
                    caller_files={"qa/custom-round.txt": "Round on ${BRANCH}.\n"})
                self.assertNotEqual(0, result.returncode)
                self.assertIsNone(prompt)

    def test_a_self_referential_include_fragment_does_not_hang(self):
        """A fragment whose resolved value still contains its own ${NAME} must
        not spin the include-variable resolver forever; it is resolved in a
        single pass and then fails because no such fragment file exists."""
        result, prompt = self._render(
            "defect-hunt", fragment="${TEST_RUNNER_FRAGMENT}")
        self.assertNotEqual(0, result.returncode)
        self.assertIsNone(prompt)

    def test_a_template_path_that_climbs_out_of_the_checkout_is_refused(self):
        """Read-as-data must stay inside the caller's checkout."""
        result, prompt = self._render("../../../etc/passwd")
        self.assertNotEqual(0, result.returncode)
        self.assertIsNone(prompt)

    def test_a_fragment_name_with_a_slash_is_refused(self):
        """A caller template may include only a published fragment by bare name,
        never a path into the framework checkout."""
        result, prompt = self._render("defect-hunt", fragment="sub/evil.txt")
        self.assertNotEqual(0, result.returncode)
        self.assertIsNone(prompt)


class QaRoundWorkflowShapeTests(unittest.TestCase):
    """The shape of qa-round.yaml the callers and the build step depend on."""

    def setUp(self):
        with open(_WORKFLOW) as f:
            self.workflow = yaml.safe_load(f)
        self.job = next(iter(self.workflow["jobs"].values()))

    def test_it_is_only_called(self):
        self.assertEqual(["workflow_call"], list(self.workflow[True]))

    def test_the_build_step_passes_the_caller_checkout_as_the_root(self):
        """The caller-root the builder reads a caller template from is the
        caller's own checkout, not the framework checkout."""
        step = _build_step()
        self.assertIn("build-qa-round-prompt.sh", step["run"])
        self.assertIn("$GITHUB_WORKSPACE", step["run"])
        self.assertIn("$FRAMEWORK/tools/ci/prompts/build-qa-round-prompt.sh", step["run"])

    def test_the_round_never_opens_a_draft_pr(self):
        """A QA round's PR is real work meant to be merged as it stands, so the
        submit step must not open it as a draft."""
        submit = next(s for s in self.job["steps"]
                      if "submit-agent-job.sh" in s.get("run", ""))
        self.assertEqual("true", submit["env"]["AUTO_CREATE_PR"])
        self.assertNotIn("PR_DRAFT", submit["env"])


if __name__ == "__main__":
    unittest.main()
