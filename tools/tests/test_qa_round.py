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
import re
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
                caller_files=None, leads=None,
                repo_url="git@github.com:example/consumer.git",
                branch="qa/defect-20261030-000000"):
        """Runs the build step's own shell with a concrete environment, the way
        a GitHub runner would, and returns (result, prompt-or-None). When
        ``leads`` is given it is written to a LEADS_FILE the builder substitutes
        for ${LEADS}, the way the collect step's output reaches it. ``repo_url``
        and ``branch`` are the round's repository and branch — the defaults fit
        the defect-hunt cases; the retrospective cases pass the caller's own."""
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
            "BRANCH": branch,
            "BASE_BRANCH": "main",
            "REPO_URL": repo_url,
            "PROMPT": prompt,
            "TEMPLATE_VARS": json.dumps(template_vars or {}),
            "TEST_RUNNER_FRAGMENT": fragment,
        })
        if leads is not None:
            leads_file = os.path.join(self.caller, "leads.txt")
            with open(leads_file, "w") as f:
                f.write(leads)
            env["LEADS_FILE"] = leads_file
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

    def test_a_computed_leads_section_is_substituted_for_the_placeholder(self):
        """A round's workflow may compute a leads section before the prompt is
        built (the flaky round reads CI's observed flakes); it reaches the
        builder as a LEADS_FILE whose multi-line contents replace ${LEADS}."""
        leads = "1. `org.example.FooTest#a` — lane test-cuda\n   - run 100, job 1001"
        result, prompt = self._render(
            "qa/custom-round.txt",
            leads=leads,
            caller_files={"qa/custom-round.txt":
                          "Round on ${BRANCH}.\n\nLeads:\n${LEADS}\n"})
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertNotIn("${", prompt)
        self.assertIn("org.example.FooTest#a", prompt)
        self.assertIn("job 1001", prompt)

    def test_an_absent_leads_file_leaves_the_placeholder_empty(self):
        """With no LEADS_FILE the ${LEADS} placeholder renders empty rather than
        surviving into the prompt, so a round that names it but collected nothing
        still builds — and a template that never names it is unaffected."""
        result, prompt = self._render(
            "qa/custom-round.txt",
            caller_files={"qa/custom-round.txt": "Round on ${BRANCH}.\nLeads:${LEADS}\n"})
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertNotIn("${", prompt)
        self.assertIn("Leads:", prompt)

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

    def test_the_retrospective_builtin_renders_for_a_public_framework_caller(self):
        """The retrospective is a built-in selector like defect-hunt, so this
        framework's own round builds from it with only its template-vars. With
        the defaults — the public visibility fragment, the framework-owner
        finding-landing fragment, and docs/qa/retrospectives — the prompt has no
        leftover placeholder and its survey scope names only the caller's own
        repository."""
        repo = "git@github.com:almostrealism/common.git"
        result, prompt = self._render(
            "retrospective",
            template_vars={"REVIEW_SINCE": "14 days ago",
                           "QA_PREFIXES": "qa/defect- qa/retro- ci/qa-"},
            repo_url=repo,
            branch="qa/retro-20261110-000000")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue(prompt)
        self.assertNotIn("${", prompt)
        # The survey scope names only the caller's own repository: it is the one
        # and only repository URL anywhere in the prompt.
        self.assertIn(repo, prompt)
        self.assertEqual({repo},
                         set(re.findall(r"git@github\.com:[\w./-]+\.git", prompt)))
        # Public visibility and the framework-owner landing are the defaults.
        self.assertIn("This repository is public", prompt)
        self.assertIn("ci/qa-... branch", prompt)
        # The report and ledger default to this framework's location.
        self.assertIn("docs/qa/retrospectives/ledger.tsv", prompt)
        self.assertIn("qa/retro-20261110-000000", prompt)

    def test_the_retrospective_builtin_renders_for_a_private_consumer_caller(self):
        """A private repository that depends on the framework runs its own
        retrospective by overriding the visibility, finding-landing and report
        variables through template-vars. The survey scope still names only the
        caller's own repository — never the framework cloned beside it — the
        private visibility rule replaces the public one, and harness findings are
        routed to the framework's tracker project rather than to edits the
        consumer cannot make."""
        repo = "git@github.com:example/consumer.git"
        result, prompt = self._render(
            "retrospective",
            template_vars={"REVIEW_SINCE": "30 days ago",
                           "QA_PREFIXES": "qa/defect- qa/docs-",
                           "REPORT_DIR": "docs/retrospectives",
                           "VISIBILITY_FRAGMENT": "retrospective-private.txt",
                           "FINDINGS_FRAGMENT": "retrospective-findings-consumer.txt",
                           "FRAMEWORK_TRACKER_PROJECT": "Common"},
            repo_url=repo,
            branch="qa/retro-20261110-000000")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue(prompt)
        self.assertNotIn("${", prompt)
        # The survey scope names only the caller's own repository, and names no
        # other repository — not even the framework it is built on.
        self.assertIn(repo, prompt)
        self.assertEqual({repo},
                         set(re.findall(r"git@github\.com:[\w./-]+\.git", prompt)))
        self.assertNotIn("almostrealism/common", prompt)
        # Private visibility replaces the public rule.
        self.assertIn("This repository is private", prompt)
        self.assertNotIn("This repository is public", prompt)
        # Harness findings are filed as tracker tasks in the framework's project.
        self.assertIn("tracker_create_task", prompt)
        self.assertIn("Common", prompt)
        # The framework-owner landing (ci/qa- branches) is not what a consumer
        # gets; it files harness findings instead of proposing edits it cannot make.
        self.assertNotIn("ci/qa-... branch", prompt)
        # The consumer's own report location is honored.
        self.assertIn("docs/retrospectives/ledger.tsv", prompt)


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

    def test_a_leads_script_runs_before_the_build_and_feeds_it(self):
        """A round that sets the leads-script input computes a leads section
        before its prompt is built: the collect step is gated on the input,
        runs ahead of the build step, and the build step passes LEADS_FILE so
        the section reaches the builder. The input exists and defaults to empty,
        so a round that sets nothing runs no such step."""
        inputs = self.workflow[True]["workflow_call"]["inputs"]
        self.assertIn("leads-script", inputs)
        self.assertEqual("", inputs["leads-script"]["default"])

        names = [s.get("name", "") for s in self.job["steps"]]
        self.assertIn("Collect the round's leads", names)
        self.assertLess(names.index("Collect the round's leads"),
                        names.index("Build the round's prompt"))

        collect = next(s for s in self.job["steps"]
                       if s.get("name") == "Collect the round's leads")
        self.assertIn("inputs.leads-script != ''", collect["if"])

        build = next(s for s in self.job["steps"]
                     if s.get("name") == "Build the round's prompt")
        self.assertIn("LEADS_FILE", build["env"])

    def test_an_application_caller_workstream_gets_the_framework_and_its_tracker_role(self):
        """An application caller (one whose repository is not the framework) has
        its round registered with the framework as a dependent repository, so
        its agent can build against it, and with the tracker role the caller
        asks for. A framework caller — one whose repository is the framework
        itself — adds no dependent repository, which the conditional's empty
        fallback expresses."""
        register = next(s for s in self.job["steps"]
                        if "register-workstream.sh" in s.get("run", ""))
        dependent = register["env"]["DEPENDENT_REPOS"]
        self.assertIn("inputs.framework-repository != github.repository", dependent)
        self.assertIn("git@github.com:{0}.git", dependent)
        # The framework-caller branch of the conditional contributes no
        # dependent repository: the framework is cloned beside the application only.
        self.assertIn("|| ''", dependent)
        self.assertEqual("${{ inputs.tracker-capabilities }}",
                         register["env"]["TRACKER_CAPABILITIES"])
        self.assertEqual("", self.workflow[True]["workflow_call"]["inputs"]
                         ["tracker-capabilities"]["default"])


if __name__ == "__main__":
    unittest.main()
