"""Guard: every agent prompt builder produces a fully substituted prompt.

The builders in ``tools/ci/prompts`` are the last step before a prompt reaches
a coding agent, and nothing downstream inspects what they wrote. A template
placeholder the builder forgot to substitute travels all the way into the
agent's instructions as the literal text ``${BRANCH}``, and the only symptom is
an agent that behaves slightly oddly for reasons nobody can reconstruct later.

These tests hold every builder to the contract its own header states: run it
with the variables it declares and it writes a prompt with no placeholder left
in it; leave one of them unset and it refuses rather than writing a prompt with
a hole in it.

They also hold the auto-resolve builders to a shared obligation. The policy for
reading and answering pull-request review comments, and the rule that every TODO
the branch adds is a finding to fix or file, live once together in
``pr-feedback.txt``, and ``prompt-render.sh``'s ``append_branch_feedback`` patches
that one fragment — plus CI's computed list of the branch's new TODOs — into
each prompt. A builder that stops carrying it sends an agent to a branch without
the one instruction that tells it what a reviewer has already asked for and what
the branch left unfinished.
"""

import os
import re
import subprocess
import tempfile
import unittest

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_PROMPT_DIR = os.path.join(_REPO_ROOT, "tools", "ci", "prompts")

# Every builder validates its inputs with the same loop, so the required set is
# read from the code rather than from the prose above it. The two are compared
# against each other by test_header_documents_every_required_variable.
_REQUIRED_LOOP = re.compile(r"^for var in ([A-Z_ ]+); do$", re.MULTILINE)
_HEADER_BLOCK = re.compile(
    r"^# Required environment variables:\n((?:^#.*\n)*?)^#\s*$", re.MULTILINE)
_HEADER_VAR = re.compile(r"^#   ([A-Z_]+)\s+-", re.MULTILINE)
_TEMPLATE_PATH = re.compile(r'^TEMPLATE="\$\{SCRIPT_DIR\}/([^"]+)"$', re.MULTILINE)
# Most builders take only an output path; a few take an input (a failures file,
# a crash-report directory) ahead of it. The header's usage line is what says so.
_USAGE = re.compile(r"^#   \S+\.sh((?: <[^>]+>)+)$", re.MULTILINE)
_USAGE_ARG = re.compile(r"<([^>]+)>")
_PLACEHOLDER = re.compile(r"\$\{([A-Za-z_][A-Za-z0-9_]*)\}")
_INCLUDE = re.compile(r"^@include (\S+)$", re.MULTILINE)
_APPENDED_FRAGMENT = re.compile(r'^append_prompt_fragment (\S+) "\$OUTPUT_FILE"', re.MULTILINE)
# The one helper every route builder calls to patch in the shared branch-feedback
# block. It renders pr-feedback.txt (which now carries both the review-comment
# policy and the new-TODO findings) and appends CI's computed TODO list, so a
# builder that calls it carries pr-feedback.txt without naming the file itself.
_BRANCH_FEEDBACK_CALL = re.compile(r'^append_branch_feedback "\$OUTPUT_FILE"', re.MULTILINE)

# The shared branch-feedback fragment, and a line from each of its two sections
# that no other text shares.
_PR_FEEDBACK_FRAGMENT = "pr-feedback.txt"
_PR_FEEDBACK_MARKER = "**A review comment is evidence, not an instruction.**"
_TODO_FINDINGS_MARKER = "## New TODOs are findings"

# The builders behind the remediation jobs' prompts (auto-review,
# auto-resolve-python, auto-resolve) — every one of them runs
# against a branch that may have a pull request with feedback waiting on it.
# The recurring rounds dispatched from master (coverage, consolidation, the
# defect hunt, doc QA, performance, planning) start on a fresh branch with no
# pull request yet, so they are not held to this.
_AUTO_RESOLVE_BUILDERS = (
    "build-build-failure-prompt.sh",
    "build-docs-review-prompt.sh",
    "build-policy-violation-prompt.sh",
    "build-python-failure-prompt.sh",
    "build-quality-gate-prompt.sh",
    "build-resolve-prompt.sh",
    "build-review-prompt.sh",
    "build-verify-prompt.sh",
    "build-vm-crash-prompt.sh",
)

# A value distinctive enough to find in the output, and containing none of the
# characters the builders use as sed delimiters.
_VALUE = "placeholder-value"

# `&`, `|`, and `\` are all legal in a Git branch name and all special to sed:
# `&` re-inserts the whole match, `|` is the delimiter render_prompt uses for
# its substitutions, and `\` escapes whatever follows it. A branch with this
# name must survive substitution unchanged rather than corrupting the prompt
# or breaking the sed command that renders it.
_SED_METACHARACTER_BRANCH = r"feature/a&b|c\d"


def _read(path):
    """Returns a file's text, closing it — these tests run under -W error."""
    with open(path) as f:
        return f.read()


# build-qa-round-prompt.sh is the one builder that does not fit the fixed shape
# these tests check: it renders a template chosen at run time by a selector or a
# caller path rather than a static TEMPLATE= line, and its required PROMPT must
# be a real selector, not the placeholder value the run-tests below pass. It is
# the reusable qa-round.yaml's builder and is covered for real in test_qa_round.py.
_GENERIC_QA_BUILDER = "build-qa-round-prompt.sh"


def _builders():
    """Returns the path of every fixed-shape prompt builder, sorted by name."""
    return [os.path.join(_PROMPT_DIR, name)
            for name in sorted(os.listdir(_PROMPT_DIR))
            if name.startswith("build-") and name.endswith(".sh")
            and name != _GENERIC_QA_BUILDER]


_TODOS_POSITION = re.compile(r'^TODOS_FILE="\$\{(\d+):-\}"$', re.MULTILINE)


def _branch_feedback_builders():
    """Returns the basenames of the fixed-shape builders that patch in the
    shared branch-feedback fragment by calling ``append_branch_feedback``.

    This is the set of routes that run against a branch which may carry a pull
    request, discovered from the code rather than hard-coded, so it cannot drift
    from ``_AUTO_RESOLVE_BUILDERS`` unnoticed.
    """
    return {os.path.basename(builder)
            for builder in _builders()
            if _BRANCH_FEEDBACK_CALL.search(_read(builder))}


def _required_vars(builder):
    """Returns the variables a builder's validation loop insists on."""
    source = _read(builder)
    match = _REQUIRED_LOOP.search(source)
    return match.group(1).split() if match else []


def _todos_position(builder):
    """Returns the 1-based positional index at which a builder reads its
    optional TODO-section file, or None when it takes none.

    The TODO file is always a builder's final positional, but it is not always
    the one right after the output path: ``build-resolve-prompt.sh`` reads two
    optional classification files between them. The index is declared in the
    ``TODOS_FILE="${N:-}"`` line rather than in the usage header, so a route
    that forwards the file to the wrong slot is caught here.
    """
    match = _TODOS_POSITION.search(_read(builder))
    return int(match.group(1)) if match else None


def _usage_args(builder):
    """Returns the argument names from a builder's usage line, output last."""
    match = _USAGE.search(_read(builder))
    return _USAGE_ARG.findall(match.group(1)) if match else []


def _template_of(builder):
    """Returns the template a builder reads, or None when it inlines its prompt."""
    match = _TEMPLATE_PATH.search(_read(builder))
    return os.path.join(_PROMPT_DIR, match.group(1)) if match else None


def _fragments_of(builder):
    """Returns the fragment names a builder patches into its prompt.

    A template names them with ``@include`` lines; a heredoc builder names them
    in its ``append_prompt_fragment`` calls; and a builder that calls
    ``append_branch_feedback`` patches in ``pr-feedback.txt`` through that helper
    without naming the file, so the helper call counts as naming it.
    """
    source = _read(builder)
    names = _APPENDED_FRAGMENT.findall(source)
    if _BRANCH_FEEDBACK_CALL.search(source):
        names.append(_PR_FEEDBACK_FRAGMENT)
    template = _template_of(builder)
    if template is not None:
        names += _INCLUDE.findall(_read(template))
    return names


def _expanded(template):
    """Returns a template's text with each ``@include`` replaced by its fragment."""
    return _INCLUDE.sub(
        lambda match: _read(os.path.join(_PROMPT_DIR, match.group(1))),
        _read(template))


class PromptBuilderTests(unittest.TestCase):
    """Runs each builder for real, in a throwaway directory."""

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="prompt-builder-test-")
        self.output = os.path.join(self.tmp, "agent-prompt.txt")

    def tearDown(self):
        import shutil
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _args(self, builder):
        """Builds the positional arguments a builder's usage line calls for.

        Each input ahead of the output path gets an empty fixture: what the
        builders do with a real one is their own business, and an empty one
        exercises the substitution path on every platform (the crash builder's
        parsing of a populated report needs GNU grep).
        """
        args = []
        for name in _usage_args(builder)[:-1]:
            if "dir" in name:
                args.append(tempfile.mkdtemp(dir=self.tmp))
            else:
                path = os.path.join(self.tmp, name)
                open(path, "w").close()
                args.append(path)
        return args + [self.output]

    def _args_with_todos(self, builder, todos):
        """Builds a builder's positional arguments with ``todos`` in its TODO
        slot, padding any undocumented optional inputs between the output path
        and the TODO file with empty fixtures.

        A builder's TODO file is its final positional, but not always the one
        right after the output (``build-resolve-prompt.sh`` reads two optional
        files in between). Padding to the declared index verifies the route
        forwards the TODO file to the right slot rather than into an earlier
        one.
        """
        args = self._args(builder)
        position = _todos_position(builder)
        while len(args) < position - 1:
            path = os.path.join(self.tmp, "pad-%d" % len(args))
            open(path, "w").close()
            args.append(path)
        return args + [todos]

    def _run(self, builder, env_overrides=None, args=None):
        """Runs a builder with every required variable set, minus any removed."""
        env = dict(os.environ)
        for var in _required_vars(builder):
            env[var] = _VALUE
        for var, value in (env_overrides or {}).items():
            if value is None:
                env.pop(var, None)
            else:
                env[var] = value
        return subprocess.run(
            ["bash", builder] + (self._args(builder) if args is None else args),
            env=env, capture_output=True, text=True)

    def test_at_least_one_builder_is_discovered(self):
        """A discovery bug would make every other test here vacuously pass."""
        self.assertTrue(_builders())

    def test_every_builder_is_executable(self):
        """The workflow chmods these, but a builder should not depend on that."""
        for builder in _builders():
            with self.subTest(builder=os.path.basename(builder)):
                self.assertTrue(os.access(builder, os.X_OK))

    def test_every_builder_declares_its_required_variables(self):
        for builder in _builders():
            with self.subTest(builder=os.path.basename(builder)):
                self.assertTrue(_required_vars(builder))

    def test_header_documents_every_required_variable(self):
        """The prose above the script is the only reference a caller reads."""
        for builder in _builders():
            with self.subTest(builder=os.path.basename(builder)):
                block = _HEADER_BLOCK.search(_read(builder))
                self.assertIsNotNone(block, "no 'Required environment variables' block")
                documented = set(_HEADER_VAR.findall(block.group(1)))
                self.assertEqual(set(_required_vars(builder)), documented)

    def test_a_prompt_is_written_with_no_placeholder_left_in_it(self):
        for builder in _builders():
            with self.subTest(builder=os.path.basename(builder)):
                result = self._run(builder)
                self.assertEqual(0, result.returncode, result.stderr)
                prompt = _read(self.output)
                self.assertTrue(prompt.strip())
                self.assertNotIn("${", prompt)

    def test_every_template_placeholder_is_a_required_variable(self):
        """A placeholder outside the required set can never be substituted."""
        for builder in _builders():
            template = _template_of(builder)
            if template is None:
                continue
            with self.subTest(builder=os.path.basename(builder)):
                self.assertTrue(os.path.isfile(template))
                placeholders = set(_PLACEHOLDER.findall(_expanded(template)))
                self.assertTrue(placeholders, "template substitutes nothing")
                self.assertLessEqual(placeholders, set(_required_vars(builder)))

    def test_every_fragment_a_builder_names_exists(self):
        for builder in _builders():
            for name in _fragments_of(builder):
                with self.subTest(builder=os.path.basename(builder), fragment=name):
                    self.assertTrue(os.path.isfile(os.path.join(_PROMPT_DIR, name)))

    def test_a_builder_runs_without_complaint(self):
        """Anything on stderr from a successful run is a mistake in the builder.

        The heredoc builders write prose that quotes tool names in backticks;
        inside an unquoted heredoc an unescaped backtick is a command
        substitution, and bash reports ``command not found`` on stderr while
        silently dropping the quoted name from the prompt.
        """
        for builder in _builders():
            with self.subTest(builder=os.path.basename(builder)):
                result = self._run(builder)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual("", result.stderr)

    def test_the_auto_resolve_builders_are_all_discovered(self):
        """A renamed builder must be re-listed, not silently released."""
        names = {os.path.basename(builder) for builder in _builders()}
        self.assertLessEqual(set(_AUTO_RESOLVE_BUILDERS), names)

    def test_every_auto_resolve_prompt_carries_the_review_feedback_policy(self):
        """One copy of the policy, present verbatim in every auto-resolve prompt."""
        fragment = _read(os.path.join(_PROMPT_DIR, _PR_FEEDBACK_FRAGMENT))
        self.assertIn(_PR_FEEDBACK_MARKER, fragment)
        rendered = fragment.replace("${BRANCH}", _VALUE)
        self.assertNotIn("${", rendered)
        for name in _AUTO_RESOLVE_BUILDERS:
            builder = os.path.join(_PROMPT_DIR, name)
            with self.subTest(builder=name):
                self.assertIn(_PR_FEEDBACK_FRAGMENT, _fragments_of(builder))
                result = self._run(builder)
                self.assertEqual(0, result.returncode, result.stderr)
                prompt = _read(self.output)
                self.assertIn(rendered, prompt)
                self.assertEqual(1, prompt.count(_PR_FEEDBACK_MARKER))

    def test_every_auto_resolve_prompt_carries_the_new_todo_findings_section(self):
        """The new-TODO findings instructions ride in the same shared fragment
        as the review-feedback policy, so no route can carry one without the
        other. A quality-gate or docs-review session that lands no code change
        is still told to account for every TODO the branch introduced — fixing
        it or filing it — exactly as the auto-resolve routes already were.
        """
        fragment = _read(os.path.join(_PROMPT_DIR, _PR_FEEDBACK_FRAGMENT))
        self.assertIn(_TODO_FINDINGS_MARKER, fragment)
        for name in _AUTO_RESOLVE_BUILDERS:
            builder = os.path.join(_PROMPT_DIR, name)
            with self.subTest(builder=name):
                result = self._run(builder)
                self.assertEqual(0, result.returncode, result.stderr)
                prompt = _read(self.output)
                self.assertIn(_TODO_FINDINGS_MARKER, prompt)
                self.assertEqual(1, prompt.count(_TODO_FINDINGS_MARKER))

    def test_the_auto_resolve_tuple_matches_the_routes_carrying_branch_feedback(self):
        """`_AUTO_RESOLVE_BUILDERS` must stay in step with the builders that
        actually patch in the shared fragment.

        The tuple is hard-coded so the shared-fragment tests name the routes
        they cover, but a remediation route added later that calls
        `append_branch_feedback` without being listed here would escape every
        one of those tests. Discovering the branch-feedback routes from the code
        and comparing the two sets fails the moment they diverge — whether a new
        route is added or a listed one stops carrying the fragment.
        """
        self.assertEqual(set(_AUTO_RESOLVE_BUILDERS), _branch_feedback_builders())

    def test_every_route_prompt_appends_the_ci_computed_todo_list(self):
        """When CI passes the extracted-TODO section, every route builder
        appends it after the instructions, so the agent sees the actual TODO
        lines and not only the rule to resolve them.

        Run through each route with a non-empty computed list in its TODO slot:
        a route that forwards the file to the wrong positional, or omits the
        append, drops the list here even though the shared-fragment tests (which
        run without a TODO file) would still pass.
        """
        marker = "TODO(review): a branch-added marker line"
        todos = os.path.join(self.tmp, "branch-todos.txt")
        with open(todos, "w") as handle:
            handle.write("## TODOs this branch adds\n\n- `x/Y.java:1` " + marker + "\n")
        for name in _AUTO_RESOLVE_BUILDERS:
            builder = os.path.join(_PROMPT_DIR, name)
            with self.subTest(builder=name):
                result = self._run(builder, args=self._args_with_todos(builder, todos))
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual("", result.stderr)
                prompt = _read(self.output)
                self.assertIn(_TODO_FINDINGS_MARKER, prompt)
                self.assertIn(marker, prompt)
                self.assertLess(prompt.index(_TODO_FINDINGS_MARKER), prompt.index(marker),
                                "the computed list must follow the instructions")

    def test_a_branch_name_with_sed_metacharacters_is_substituted_literally(self):
        """A branch such as `feature/a&b|c\\d` must not corrupt the prompt.

        render_prompt builds a `sed s|${NAME}|value|g` expression for every
        variable it substitutes; a value containing the delimiter or sed's
        own replacement metacharacters must still come through unchanged.
        Scoped to the builders that render through prompt-render.sh — the
        recurring-round builders substitute BRANCH with their own inline sed
        and are not touched by this fix.
        """
        for name in _AUTO_RESOLVE_BUILDERS:
            builder = os.path.join(_PROMPT_DIR, name)
            if "BRANCH" not in _required_vars(builder):
                continue
            with self.subTest(builder=os.path.basename(builder)):
                result = self._run(builder, {"BRANCH": _SED_METACHARACTER_BRANCH})
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual("", result.stderr)
                prompt = _read(self.output)
                self.assertIn(_SED_METACHARACTER_BRANCH, prompt)

    def test_a_multi_line_value_is_substituted_whole(self):
        """A task description spans lines; a raw newline would end the sed
        expression render_prompt builds. It must come through unchanged,
        and placeholder-like text in it must stay literal, since it is
        substituted after every other value."""
        builder = os.path.join(_PROMPT_DIR, "build-task-planning-prompt.sh")
        description = "First & line | x \\ y\n\n  ${BRANCH} stays\nlast"
        result = self._run(builder, {"TASK_DESCRIPTION": description,
                                     "BRANCH": _SED_METACHARACTER_BRANCH})
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("", result.stderr)
        prompt = _read(self.output)
        self.assertIn(description, prompt)
        self.assertIn(_SED_METACHARACTER_BRANCH, prompt)

    def test_a_missing_variable_is_refused_rather_than_substituted_empty(self):
        for builder in _builders():
            for var in _required_vars(builder):
                with self.subTest(builder=os.path.basename(builder), var=var):
                    result = self._run(builder, {var: None})
                    self.assertEqual(1, result.returncode)
                    self.assertIn(var, result.stderr)
                    self.assertFalse(os.path.exists(self.output))

    def test_an_empty_variable_is_refused_too(self):
        """An unset variable and one set to "" are the same mistake."""
        for builder in _builders():
            var = _required_vars(builder)[0]
            with self.subTest(builder=os.path.basename(builder), var=var):
                result = self._run(builder, {var: ""})
                self.assertEqual(1, result.returncode)
                self.assertFalse(os.path.exists(self.output))

    def test_the_output_path_is_required(self):
        """Called with every argument but the last, a builder must refuse."""
        for builder in _builders():
            with self.subTest(builder=os.path.basename(builder)):
                result = self._run(builder, args=self._args(builder)[:-1])
                self.assertEqual(1, result.returncode)
                self.assertIn("Usage", result.stderr)

    def test_every_builder_documents_its_usage(self):
        """_args and test_the_output_path_is_required both read that line."""
        for builder in _builders():
            with self.subTest(builder=os.path.basename(builder)):
                args = _usage_args(builder)
                self.assertTrue(args)
                self.assertIn("output", args[-1])

    def test_general_review_is_scope_bounded_with_follow_up_routing(self):
        """The auto-review prompt must bound itself to the branch's scope.

        `general-review.txt` once told the reviewer to "fix
        every issue you find" over the whole branch diff, with no boundary and
        no route for out-of-scope work. It now carries the same "stay in scope,
        defer the rest" shape `coverage.txt` has, routing anything broader to
        `tracker_create_task`.
        """
        text = _read(os.path.join(_PROMPT_DIR, "general-review.txt"))
        self.assertNotIn("fix every issue you find", text)
        self.assertIn("Stay inside this branch's scope", text)
        self.assertIn("tracker_create_task", text)

    def test_retrospective_writes_its_artifact_before_surveying(self):
        """The retrospective must put its report on disk before the survey.

        A run that surveyed and then stalled left nothing to
        review because the report was written only at the end. The prompt now
        makes writing a skeleton report plus the ledger row the first action,
        enriched as the survey proceeds.
        """
        text = _read(os.path.join(_PROMPT_DIR, "retrospective.txt"))
        self.assertIn("Write your report skeleton first", text)
        self.assertIn("survey in progress", text)
        first = text.index("Write your report skeleton first")
        survey = text.index("## Survey the QA rounds")
        self.assertLess(first, survey,
                        "the skeleton instruction must precede the survey")

    def test_retrospective_surveys_open_prs_before_reporting_a_proposal_open(self):
        """The retrospective must catch in-flight implementations of prior proposals.

        A previous run reported two proposals "still open"
        while a PR was already implementing them, because a master-only status
        read cannot see an unmerged implementation. The prompt now tells the
        round to list open PRs with `github_list_open_prs` and in-window
        branches with `workstream_list`, resolve candidates with
        `github_pr_find(state="all")`, and report such a proposal "in progress
        (PR #N)" — all before the survey, in the history-reading step.
        """
        text = _read(os.path.join(_PROMPT_DIR, "retrospective.txt"))
        self.assertIn("github_list_open_prs", text)
        self.assertIn('github_pr_find(state="all")', text)
        self.assertIn("in progress (PR #N)", text)
        check = text.index("github_list_open_prs")
        survey = text.index("## Survey the QA rounds")
        self.assertLess(check, survey,
                        "the in-flight check must precede the survey")

    def test_flaky_hunt_bounds_its_runs_and_routes_follow_ups_in_scope(self):
        """The flaky-hunt pilot must stay inside the test-execution limits and
        not weaken the test that reveals a flake.

        The round runs a small, named selection repeatedly on
        one commit. The prompt must forbid a module/full-suite run and a CI
        shard selector, require an explicit timeout, tell the round to repeat
        a small selection rather than widen it, forbid editing existing test
        methods, and route an out-of-scope cause to a follow-up via
        `tracker_create_task` (the same routing `general-review.txt` uses, not
        the removed `tracker_file_follow_up_task`).
        """
        text = _read(os.path.join(_PROMPT_DIR, "flaky-hunt.txt"))
        self.assertIn("CI shard selector", text)
        self.assertIn("2400", text)
        self.assertIn("tracker_create_task", text)
        self.assertNotIn("tracker_file_follow_up_task", text)
        lowered = text.lower()
        self.assertIn("must not edit", lowered)
        self.assertIn("do not widen", lowered)

    def test_flaky_hunt_continues_from_prior_rounds_and_evidence(self):
        """The flaky round must not pick its hunting ground blind.

        The round recalls earlier
        flaky-round memories and continues from the previous round's named
        next target, consults the tracker for known flaky/timing-out tests,
        biases its module choice toward where determinism has failed
        (hardware/CUDA, studio/compose render, flowtree relay/mailbox), and
        leaves a next target for the round after it. The recall and tracker
        steps must come before the module choice. The prompt names the lanes
        by durable test-class and module names rather than by ephemeral tracker
        task ids, which it tells the round to look up at run time.
        """
        text = _read(os.path.join(_PROMPT_DIR, "flaky-hunt.txt"))
        self.assertIn("memory_recall", text)
        self.assertIn("tracker_search_tasks", text)
        self.assertIn("memory_store", text)
        for lead in ("AudioSceneMultiGenomeTest", "SimilarityOverheadTest",
                     "BatchedRealSceneRenderTest", "NodeRelaySimulationTest",
                     "WorkstreamMailboxTest", "studio/compose", "CUDA"):
            self.assertIn(lead, text)
        self.assertIn("next target", text.lower())
        self.assertLess(text.index("memory_recall"), text.index("## Scope"),
                        "recall must precede the module choice")
        self.assertLess(text.index("tracker_search_tasks"), text.index("## Scope"),
                        "the tracker search must precede the module choice")

    def test_flaky_hunt_names_the_shared_infrastructure_concurrency_leads(self):
        """The flaky round's real fixes were concurrency bugs in shared
        infrastructure, so those areas are named leads: the `base/io` metrics
        classes and the controller's `Node` worker queue, each with the test
        that exercises it. They are named by module and class, never by the PR,
        task or commit that fixed them, and they sit in the ordered target
        preference rather than after the fallback.
        """
        text = _read(os.path.join(_PROMPT_DIR, "flaky-hunt.txt"))
        for lead in ("base/io", "MetricBase", "MetricBaseConcurrencyTest",
                     "flowtree/runtime", "Node#addJob", "NodeWorkerWakeTest"):
            self.assertIn(lead, text)
        fallback = text.index("Only when none of those is a live lead")
        self.assertLess(text.index("MetricBaseConcurrencyTest"), fallback)
        self.assertLess(text.index("NodeWorkerWakeTest"), fallback)
        self.assertNotRegex(text, r"PR #\d+")


if __name__ == "__main__":
    unittest.main()
