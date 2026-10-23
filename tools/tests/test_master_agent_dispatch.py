"""Guard: every recurring QA job in Master Agent Dispatch is wired completely.

The QA-style jobs in ``.github/workflows/master-agent-dispatch.yaml`` all have
the same shape — a cadence gate, an archive of the previous rounds, a branch, a
workstream, a prompt, a submission — and every one of them was written by
copying the one before it. That is exactly the situation where a step gets
dropped or a name gets left behind unchanged, and the result does not fail: a
job missing its cadence gate submits a round on every merge to master, and a
job that inherits another's ``BRANCH_PREFIX`` reads that job's branches as its
own history and silently follows its schedule instead.

None of that is visible in a passing workflow run, so it is checked here.

A job that calls a reusable workflow in this repository (the task-planning
round calls ``task-planning.yaml``) has no steps of its own; its checks are
made against the called workflow's job, with the caller's inputs in place of
the called workflow's.
"""

import json
import os
import re
import subprocess
import tempfile
import unittest

import yaml

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_WORKFLOW = os.path.join(_REPO_ROOT, ".github", "workflows", "master-agent-dispatch.yaml")

# YAML 1.1 reads the unquoted key `on` as the boolean True; PyYAML follows it.
_ON = True

# The scripts a QA round runs, in the order it must run them.
_QA_SCRIPTS = [
    "qa-cadence.sh",
    "archive-stale-workstreams.sh",
    "register-workstream.sh",
    "submit-agent-job.sh",
]

_SELECTOR = re.compile(r"github\.event\.inputs\.agent == '([a-z-]+)'")


def _workflow():
    with open(_WORKFLOW) as f:
        return yaml.safe_load(f)


def called_job(job):
    """Returns the job that runs for ``job``: the only job of the reusable
    workflow it calls from this repository, or ``job`` itself."""
    uses = job.get("uses", "")
    if not uses.startswith("./.github/workflows/"):
        return job
    with open(os.path.join(_REPO_ROOT, uses[2:])) as f:
        jobs = yaml.safe_load(f)["jobs"]
    if len(jobs) != 1:
        raise AssertionError("%s should hold exactly one job" % uses)
    return next(iter(jobs.values()))


def controller_url(caller, env):
    """Returns the controller URL a step runs with. In a called workflow the
    step reads its ``controller-url`` input, so the caller's value is the one
    that reaches it."""
    url = env.get("CONTROLLER_URL", "")
    if "inputs.controller-url" in url:
        return str(caller.get("with", {}).get("controller-url", ""))
    return url


def _run_steps(job):
    """Returns the shell of every step that runs one."""
    return [step["run"] for step in job["steps"] if "run" in step]


def _qa_jobs(workflow):
    """Returns the jobs gated by the shared cadence script, by name."""
    return {name: job for name, job in workflow["jobs"].items()
            if "BRANCH_PREFIX" in job.get("env", {})}


class MasterAgentDispatchTests(unittest.TestCase):
    """Structural checks over the dispatch workflow."""

    def setUp(self):
        self.workflow = _workflow()
        self.jobs = self.workflow["jobs"]
        self.qa_jobs = _qa_jobs(self.workflow)
        self.task_planning = called_job(self.jobs["plan-release-task"])

    def test_the_qa_jobs_are_found(self):
        """A discovery bug would make the per-job checks vacuously pass."""
        self.assertGreaterEqual(len(self.qa_jobs), 4)

    def test_every_qa_job_runs_the_shared_round_scripts(self):
        for name, job in self.qa_jobs.items():
            shell = "\n".join(_run_steps(job))
            for script in _QA_SCRIPTS:
                with self.subTest(job=name, script=script):
                    self.assertIn(script, shell)

    def test_every_qa_job_builds_a_prompt_that_exists(self):
        for name, job in self.qa_jobs.items():
            with self.subTest(job=name):
                builders = re.findall(r"(build-[a-z-]+-prompt\.sh)",
                                      "\n".join(_run_steps(job)))
                self.assertEqual(1, len(set(builders)), builders)
                self.assertTrue(os.path.isfile(os.path.join(
                    _REPO_ROOT, "tools", "ci", "prompts", builders[0])))

    def test_every_qa_job_has_its_own_branch_prefix(self):
        """A shared prefix would make one job read another's cadence."""
        prefixes = [job["env"]["BRANCH_PREFIX"] for job in self.qa_jobs.values()]
        self.assertEqual(len(prefixes), len(set(prefixes)))
        for prefix in prefixes:
            with self.subTest(prefix=prefix):
                # qa-cadence.sh appends "YYYYMMDD-HHMMSS" and parses it back out.
                self.assertTrue(prefix.startswith("qa/"))
                self.assertTrue(prefix.endswith("-"))

    def test_every_qa_job_bounds_its_cadence(self):
        for name, job in self.qa_jobs.items():
            with self.subTest(job=name):
                self.assertGreaterEqual(int(job["env"]["MIN_INTERVAL_DAYS"]), 1)

    def test_qa_intervals_match_the_documented_cadence(self):
        """The per-job intervals are documented in .github/CLAUDE.md and the workflow header.

        A changed interval that is not reflected there leaves the next reader
        planning around a cadence the workflow no longer has; a QA job added
        without an entry here fails, so its interval is chosen deliberately.
        """
        expected = {
            "doc-qa": 5,
            "defect-hunt": 2,
            "flaky-qa": 7,
            "coverage-qa": 2,
            "consolidation-qa": 2,
            "performance-qa": 2,
            "pdsl-qa": 5,
            "qa-retrospective": 7,
        }
        actual = {name: int(job["env"]["MIN_INTERVAL_DAYS"])
                  for name, job in self.qa_jobs.items()}
        self.assertEqual(expected, actual)

    def test_every_qa_job_has_its_own_interval_override(self):
        """Each interval-bound job needs a dispatch input that lifts only its own interval.

        A missing input leaves no way to run that job early short of force,
        which also bypasses the open-PR check; a shared input would lift the
        interval of jobs nobody asked to run early.
        """
        inputs = self.workflow[_ON]["workflow_dispatch"]["inputs"]
        seen = []
        for name, job in self.qa_jobs.items():
            gate = next(s for s in job["steps"]
                        if "qa-cadence.sh" in s.get("run", ""))
            with self.subTest(job=name):
                match = re.fullmatch(
                    r"\$\{\{ github\.event\.inputs\.(ignore_interval_[a-z_]+) \}\}",
                    gate["env"].get("IGNORE_INTERVAL", ""))
                self.assertIsNotNone(match, "IGNORE_INTERVAL is not wired to an input")
                key = match.group(1)
                self.assertIn(key, inputs)
                self.assertEqual("boolean", inputs[key]["type"])
                self.assertIs(False, inputs[key]["default"])
                seen.append(key)
        self.assertEqual(len(seen), len(set(seen)))

    def test_every_qa_job_sets_a_pr_grace_window(self):
        """The interval override must not be able to stack on an in-progress round.

        A QA round creates its branch and registers its workstream before its
        agent opens a PR, so an open-PR check alone cannot see a round that is
        still working. The interval normally covers that window, but the
        ignore_interval override lifts it; without PR_GRACE_HOURS the awaiting-PR
        check (condition 2) is off, so an override would start a duplicate round
        and the archive step would retire the live one. Each QA gate therefore
        sets a positive grace window.
        """
        for name, job in self.qa_jobs.items():
            gate = next(s for s in job["steps"]
                        if "qa-cadence.sh" in s.get("run", ""))
            with self.subTest(job=name):
                grace = gate["env"].get("PR_GRACE_HOURS")
                self.assertIsNotNone(grace, "PR_GRACE_HOURS is not set on the gate")
                self.assertGreater(int(grace), 0)

    def test_plan_next_task_opens_a_draft_pr_but_qa_rounds_do_not(self):
        """A plan PR opens as a draft — it carries only a plan awaiting the
        owner's approval, which marking it ready for review grants, starting
        implementation. A QA round's PR is instead real documentation meant to
        be merged as it stands, so it must not open as a draft, or marking it
        ready would dispatch an implementation of a branch with nothing to
        implement."""
        plan_submit = next(s for s in self.jobs["plan-next-task"]["steps"]
                           if "submit-agent-job.sh" in s.get("run", ""))
        self.assertEqual("true", plan_submit["env"]["AUTO_CREATE_PR"])
        self.assertEqual("true", plan_submit["env"]["PR_DRAFT"])

        for name, job in self.qa_jobs.items():
            submit = next(s for s in job["steps"]
                          if "submit-agent-job.sh" in s.get("run", ""))
            with self.subTest(job=name):
                self.assertNotEqual("true", submit["env"].get("PR_DRAFT", "false"),
                                    name + " must not open its PR as a draft")

    def test_every_step_after_the_gate_is_gated(self):
        """An ungated step would run on merges the cadence gate declined."""
        for name, job in self.qa_jobs.items():
            steps = job["steps"]
            gate = next(i for i, s in enumerate(steps)
                        if "qa-cadence.sh" in s.get("run", ""))
            for step in steps[gate + 1:]:
                with self.subTest(job=name, step=step["name"]):
                    condition = step.get("if", "")
                    self.assertTrue(
                        "steps.decide.outputs.run == 'true'" in condition
                        or condition == "always()",
                        "ungated: " + step["name"])

    def test_qa_rounds_do_not_archive_on_a_forced_run(self):
        """A forced QA dispatch must not retire a round that is still in progress.

        The open-PR and awaiting-PR checks run before the interval, so a
        scheduled or ignore_interval dispatch only reaches the archive step once
        no round of this prefix is in progress. A forced dispatch skips every
        check, so its run=true carries reason=forced while a previous round may
        still be live (branch and workstream registered, agent working, PR not
        yet opened). Archiving then retires that live round and a duplicate
        starts beside it. Each QA archive step therefore excludes the forced
        reason, exactly as the planning jobs do.
        """
        for name, job in self.qa_jobs.items():
            archive = next(s for s in job["steps"]
                           if "archive-stale-workstreams.sh" in s.get("run", ""))
            with self.subTest(job=name):
                condition = archive.get("if", "")
                self.assertIn("steps.decide.outputs.run == 'true'", condition)
                self.assertIn("steps.decide.outputs.reason != 'forced'", condition)

    def test_setup_python_provisioning_is_best_effort(self):
        """A setup-python failure must not abort before the fallback fetch step.

        The coverage-qa job provisions Python with actions/setup-python as its
        primary path, but fetch-latest-coverage.sh keeps a Homebrew-path
        fallback for hosts that carry their own interpreter. That fallback is
        only reachable if a failed setup-python step does not fail the job, so
        coverage-qa's setup-python step must carry continue-on-error: true.

        The contract is specific to this job: it exists because coverage-qa has
        a downstream fallback that a hard failure would skip. Other dispatch
        jobs are free to require setup-python, so the check is scoped to
        coverage-qa rather than every job in the workflow.
        """
        job = self.jobs["coverage-qa"]
        found = 0
        for step in job["steps"]:
            if "setup-python" in step.get("uses", ""):
                found += 1
                with self.subTest(step=step["name"]):
                    self.assertIs(
                        step.get("continue-on-error"), True,
                        "setup-python must be best-effort so the fallback "
                        "in fetch-latest-coverage.sh remains reachable")
        self.assertGreaterEqual(found, 1, "no setup-python step found in coverage-qa")

    def test_coverage_qa_never_recomputes_java_coverage_on_a_hosted_runner(self):
        """coverage-qa must not fall back to the full Maven suite.

        fetch-latest-coverage.sh recomputes Java coverage with a full
        ``mvn test`` when no master artifact is found. On a GitHub-hosted
        runner that is hours of work producing a report that does not match
        the self-hosted coverage lanes, so the fetch step must switch the
        recompute path off and fail fast instead.
        """
        job = self.jobs["coverage-qa"]
        fetch_steps = [step for step in job["steps"]
                       if "fetch-latest-coverage.sh" in step.get("run", "")]
        self.assertEqual(1, len(fetch_steps))
        self.assertEqual("false", fetch_steps[0].get("env", {}).get("ALLOW_RECOMPUTE"))
        self.assertNotIn("FORCE", fetch_steps[0].get("env", {}))

    def test_coverage_qa_can_read_actions_artifacts(self):
        """coverage-qa must grant actions: read so it can reuse coverage.

        fetch-latest-coverage.sh reuses master's merged-coverage-report by
        listing and downloading it through the Actions artifacts REST route
        (``/actions/artifacts``), which the default GITHUB_TOKEN cannot reach
        without actions: read — the coverage lanes in analysis.yaml grant it
        for the same reason. Without it the fetch 403s, and because this job
        runs with ALLOW_RECOMPUTE=false the fallback is disabled, so the whole
        coverage round fails instead of reusing the report.
        """
        job = self.jobs["coverage-qa"]
        self.assertEqual("read", job.get("permissions", {}).get("actions"))

    def test_every_job_reaches_the_controller_through_the_tunnel(self):
        """No dispatch job needs a host inside the private network.

        The jobs only talk to git, the GitHub API and the FlowTree
        controller, and the controller is reachable from anywhere through
        its Cloudflare Access tunnel. A step that falls back to a LAN
        hostname (CONTROLLER_HOST) would silently tie the workflow back to a
        self-hosted runner, so every controller-script step must carry the
        tunnel URL and both halves of the service token, and every job must
        run on a GitHub-hosted runner.
        """
        controller_scripts = _QA_SCRIPTS[1:]
        for name, caller in self.jobs.items():
            job = called_job(caller)
            with self.subTest(job=name):
                self.assertNotIn("self-hosted", str(job["runs-on"]))
            if job is not caller:
                # The called workflow receives the service token only as the
                # caller passes it.
                with self.subTest(job=name, caller=True):
                    self.assertIn("FLOWTREE_CF_ACCESS_CLIENT_ID",
                                  str(caller["with"].get("cf-access-client-id", "")))
                    self.assertEqual("${{ secrets.FLOWTREE_CF_ACCESS_CLIENT_SECRET }}",
                                     caller["secrets"]["FLOWTREE_CF_ACCESS_CLIENT_SECRET"])
            for step in job["steps"]:
                if not any(s in step.get("run", "") for s in controller_scripts):
                    continue
                with self.subTest(job=name, step=step["name"]):
                    env = step.get("env", {})
                    self.assertNotIn("CONTROLLER_HOST", env)
                    self.assertIn("FLOWTREE_CONTROLLER_URL", controller_url(caller, env))
                    self.assertIn("CF_ACCESS_CLIENT_ID", env)
                    self.assertIn("secrets.FLOWTREE_CF_ACCESS_CLIENT_SECRET",
                                  env.get("CF_ACCESS_CLIENT_SECRET", ""))

    def test_every_job_serializes_under_its_own_concurrency_group(self):
        """Two rounds of one job racing is what the cadence gate cannot see."""
        groups = []
        for name, job in self.jobs.items():
            with self.subTest(job=name):
                self.assertIn("concurrency", job)
                self.assertFalse(job["concurrency"]["cancel-in-progress"])
                groups.append(job["concurrency"]["group"])
        self.assertEqual(len(groups), len(set(groups)))

    def test_every_job_can_be_dispatched_on_its_own(self):
        options = self.workflow[_ON]["workflow_dispatch"]["inputs"]["agent"]["options"]
        selectors = set()
        for name, job in self.jobs.items():
            with self.subTest(job=name):
                found = _SELECTOR.findall(job["if"])
                self.assertTrue(found, "no agent selector")
                self.assertIn("github.event_name != 'workflow_dispatch'", job["if"],
                              "a push to master must run this job")
                self.assertIn("all", found)
                selectors.update(found)
        self.assertEqual(selectors, set(options))

    def test_the_performance_round_is_routed_to_a_gpu_node_at_max_effort(self):
        """The workflow's runs-on says where the *submission* happens; only
        REQUIRED_LABELS says where the *agent* runs, and a performance
        measurement taken on a Node without Metal is the wrong measurement
        presented as the right one. The effort pin is what buys the
        planning the round asks for; losing it does not fail anything
        visible either."""
        job = self.qa_jobs["performance-qa"]
        submit = next(s for s in job["steps"]
                      if "submit-agent-job.sh" in s.get("run", ""))
        env = submit["env"]
        self.assertEqual({"platform": "macos"}, json.loads(env["REQUIRED_LABELS"]))
        primary = json.loads(env["PHASE_CONFIGS"])["primary"]
        self.assertEqual("max", primary["effort"])
        self.assertEqual("opus", primary["model"])
        # Runner, model and provider must be set together; see the defect
        # hunt's note in the workflow for why one alone misroutes the phase.
        self.assertEqual({"runner", "model", "effort", "provider"}, set(primary))
        self.assertEqual("true", env["PROTECT_TEST_FILES"])

    def test_the_performance_workstream_is_routed_to_a_gpu_node(self):
        """Only the first job is submitted here; every later job on the
        workstream (a PR follow-up, a fix for a failing check) gets its Node
        from the workstream's own labels. Without them those jobs land on a
        Node without Metal and cannot reproduce what they were asked to fix."""
        job = self.qa_jobs["performance-qa"]
        register = next(s for s in job["steps"]
                        if "register-workstream.sh" in s.get("run", ""))
        submit = next(s for s in job["steps"]
                      if "submit-agent-job.sh" in s.get("run", ""))
        labels = json.loads(register["env"]["REQUIRED_LABELS_JSON"])
        self.assertEqual({"platform": "macos"}, labels)
        self.assertEqual(labels, json.loads(submit["env"]["REQUIRED_LABELS"]))

    def test_the_pdsl_migration_round_runs_at_max_effort_with_tests_locked(self):
        """The round's hardest step is telling a building block from a
        composition, and the cheap answer — a primitive that wraps the
        Java class — is always available to a model that stops thinking
        early; the effort pin is what buys the judgment the prompt asks
        for, and losing it fails nothing visible. The test lock is what
        keeps "migrate" from meaning "rewrite the test that noticed"."""
        job = self.qa_jobs["pdsl-qa"]
        submit = next(s for s in job["steps"]
                      if "submit-agent-job.sh" in s.get("run", ""))
        env = submit["env"]
        primary = json.loads(env["PHASE_CONFIGS"])["primary"]
        self.assertEqual("max", primary["effort"])
        self.assertEqual("opus", primary["model"])
        # Runner, model and provider must be set together; see the defect
        # hunt's note in the workflow for why one alone misroutes the phase.
        self.assertEqual({"runner", "model", "effort", "provider"}, set(primary))
        self.assertEqual("true", env["PROTECT_TEST_FILES"])
        self.assertNotIn("ENFORCE_CHANGES", env)

    def test_the_retrospective_round_reports_an_empty_result_without_a_rerun(self):
        """The retrospective's contract is a report on disk. A run that
        committed nothing used to exit SUCCESS; enforceChanges makes that
        DEGRADED once its re-run cap is spent, and the cap of 0 reports it at
        once rather than paying for a second full opus/max survey."""
        job = self.qa_jobs["qa-retrospective"]
        submit = next(s for s in job["steps"]
                      if "submit-agent-job.sh" in s.get("run", ""))
        env = submit["env"]
        self.assertEqual("true", env["ENFORCE_CHANGES"])
        self.assertEqual("0", env["MAX_ENFORCE_CHANGES_RETRIES"])

    def test_the_planning_job_keeps_one_round_open_at_a_time(self):
        """Every planning round rewrites the one docs/plans/MANAGER_LOG.md,
        so two open at once cannot both merge. The job used to gate on the
        total open-PR count alone, never looked for a planning branch, and
        started a second round while the first agent was still working.
        The shared gate must look at the planning prefix, cover the window
        before the round's PR exists, and apply no interval; the backlog
        limit must be checked after it, and nothing may run past a gate
        that declined."""
        job = self.jobs["plan-next-task"]
        self.assertNotIn("BRANCH_PREFIX", job.get("env", {}),
                         "a job-level prefix would enrol this job in the QA checks")
        steps = job["steps"]
        gate = next(i for i, s in enumerate(steps)
                    if "qa-cadence.sh" in s.get("run", ""))
        env = steps[gate]["env"]
        self.assertEqual("project/plan-", env["BRANCH_PREFIX"])
        self.assertEqual("0", env["MIN_INTERVAL_DAYS"])
        self.assertGreater(int(env["PR_GRACE_HOURS"]), 0)
        self.assertEqual("1", env.get("MAX_ROUNDS", "1"))
        self.assertEqual("7", self.workflow["env"]["MAX_OPEN_PRS"])
        self.assertNotIn("MAX_OPEN_PRS", job.get("env", {}),
                         "the job must apply the workflow's shared limit")

        # The backlog limit is applied by the script both planning jobs share.
        backlog = next(i for i, s in enumerate(steps)
                       if "open-pr-backlog.sh" in s.get("run", ""))
        self.assertEqual("branches", steps[backlog]["id"])
        self.assertGreater(backlog, gate)
        self.assertIn("steps.decide.outputs.run == 'true'", steps[backlog]["if"])
        for step in steps[backlog + 1:]:
            with self.subTest(step=step["name"]):
                condition = step.get("if", "")
                self.assertTrue(
                    "steps.branches.outputs.needs_new_branch == 'true'" in condition
                    or condition == "always()",
                    "ungated: " + step["name"])

    def test_the_task_planning_round_is_gated_in_order(self):
        """A task-planning round starts only when no round is in progress,
        the open-PR backlog has room, and the release has a claimable task —
        checked in that order, each gating the next, so that with nothing to
        plan no branch, workstream or agent job is created. `force` may skip
        the first two checks but never the third: without a task there is
        nothing to plan."""
        job = self.task_planning
        self.assertNotIn("BRANCH_PREFIX", job.get("env", {}),
                         "a job-level prefix would enrol this job in the QA checks")
        steps = job["steps"]

        def index(script):
            return next(i for i, s in enumerate(steps) if script in s.get("run", ""))

        gate, backlog, claimable = (index("qa-cadence.sh"), index("open-pr-backlog.sh"),
                                    index("tracker-claimable.sh"))
        self.assertLess(gate, backlog)
        self.assertLess(backlog, claimable)

        env = steps[gate]["env"]
        self.assertEqual("project/task-", env["BRANCH_PREFIX"])
        self.assertEqual("0", env["MIN_INTERVAL_DAYS"])
        self.assertGreater(int(env["PR_GRACE_HOURS"]), 0)
        self.assertIn("steps.decide.outputs.run == 'true'", steps[backlog]["if"])
        self.assertIn("steps.backlog.outputs.needs_new_branch == 'true'", steps[claimable]["if"])
        self.assertNotIn("FORCE", steps[claimable].get("env", {}))
        self.assertIn("steps.release.outputs.release", steps[claimable]["env"]["TRACKER_RELEASE"])

        for step in steps[claimable + 1:]:
            with self.subTest(step=step["name"]):
                condition = step.get("if", "")
                self.assertTrue(
                    "steps.claimable.outputs.run == 'true'" in condition
                    or condition == "always()",
                    "ungated: " + step["name"])

    def test_the_task_planning_workstream_holds_no_tracker_role(self):
        """Inverted when claiming moved into CI: the round's workstream holds
        no tracker role any more. CI claims the task for it, which links the
        task to the workstream, and that link is all the agent needs to
        edit its own task. The planner role would let a planning agent edit
        every task in the workspace, which no planning round needs."""
        steps = self.task_planning["steps"]
        register = next(s for s in steps if "register-workstream.sh" in s.get("run", ""))
        self.assertNotIn("TRACKER_CAPABILITIES", register["env"])
        submit = next(s for s in steps if "submit-agent-job.sh" in s.get("run", ""))
        self.assertEqual("true", submit["env"]["AUTO_CREATE_PR"])
        # The plan PR opens as a draft: it carries only a plan awaiting the
        # owner's approval, which marking it ready for review grants.
        self.assertEqual("true", submit["env"]["PR_DRAFT"])
        # Workspace defaults decide the model; the round sets none.
        self.assertNotIn("PHASE_CONFIGS", submit["env"])
        prompt = next(s for s in steps if "build-task-planning-prompt.sh" in s.get("run", ""))
        self.assertIn("steps.release.outputs.release", prompt["env"]["TRACKER_RELEASE"])

    def _task_planning_step(self, script):
        """Returns (index, step) of the task-planning step that runs ``script``."""
        steps = self.task_planning["steps"]
        return next((i, s) for i, s in enumerate(steps) if script in s.get("run", ""))

    def test_the_task_is_claimed_between_registration_and_the_prompt(self):
        """The claim needs the workstream's id, and the prompt needs the
        claimed task: register, then claim, then build, then submit."""
        register, register_step = self._task_planning_step("register-workstream.sh")
        claim, claim_step = self._task_planning_step("tracker-claim.sh")
        prompt, _ = self._task_planning_step("build-task-planning-prompt.sh")
        submit, _ = self._task_planning_step("submit-agent-job.sh")
        self.assertLess(register, claim)
        self.assertLess(claim, prompt)
        self.assertLess(prompt, submit)
        self.assertEqual("register", register_step["id"])
        self.assertEqual("claim", claim_step["id"])
        env = claim_step["env"]
        self.assertEqual("${{ steps.register.outputs.workstream_id }}", env["WORKSTREAM_ID"])
        self.assertIn("steps.release.outputs.release", env["TRACKER_RELEASE"])
        self.assertIn("TRACKER_PROJECT", self.task_planning["env"])

    def test_the_claim_carries_the_secret_only_through_its_environment(self):
        _, claim_step = self._task_planning_step("tracker-claim.sh")
        self.assertEqual("${{ secrets.FLOWTREE_CF_ACCESS_CLIENT_SECRET }}",
                         claim_step["env"]["CF_ACCESS_CLIENT_SECRET"])
        for step in self.task_planning["steps"]:
            with self.subTest(step=step.get("name")):
                self.assertNotIn("secrets.", step.get("run", ""))

    def test_nothing_is_submitted_unless_a_task_was_claimed(self):
        """Another round can take the last task between the claimable gate
        and the claim; then no prompt is built and no job submitted, and the
        branch created for the round is deleted so it does not read as a
        round in progress."""
        for script in ("build-task-planning-prompt.sh", "submit-agent-job.sh"):
            _, step = self._task_planning_step(script)
            with self.subTest(script=script):
                self.assertIn("steps.claim.outputs.claimed == 'true'", step["if"])
        _, cleanup = self._task_planning_step("git push origin --delete")
        self.assertIn("steps.claim.outputs.claimed != 'true'", cleanup["if"])
        self.assertIn("steps.new_branch.outputs.branch", cleanup["env"]["BRANCH"])
        self.assertNotIn("${{", cleanup["run"])

    def test_the_task_text_reaches_the_prompt_through_a_file(self):
        """The claimed task's title and description are free text; they are
        read from the claim's file inside the step, never passed as step
        outputs or interpolated into the script."""
        _, step = self._task_planning_step("build-task-planning-prompt.sh")
        env = step["env"]
        self.assertEqual("${{ steps.claim.outputs.task_file }}", env["TASK_FILE"])
        self.assertEqual("${{ steps.claim.outputs.task_id }}", env["TASK_ID"])
        self.assertNotIn("TASK_TITLE", env)
        self.assertNotIn("TASK_DESCRIPTION", env)
        self.assertNotIn("steps.claim", step["run"])
        self.assertIn('"$TASK_FILE"', step["run"])

    def test_the_prompt_step_renders_the_claimed_task(self):
        """Runs the step's own script against a claimed task's file."""
        _, step = self._task_planning_step("build-task-planning-prompt.sh")
        tmp = tempfile.mkdtemp(prefix="task-planning-step-")
        task_file = os.path.join(tmp, "task.json")
        description = "  First line & | \\ ${BRANCH}\n\nSecond line\n"
        with open(task_file, "w") as f:
            json.dump({"id": 7, "title": "  Plan a thing \n", "description": description}, f)
        script = step["run"].replace("${{ runner.temp }}", tmp)
        env = dict(os.environ)
        env.update({"BRANCH": "project/task-1", "BASE_BRANCH": "master",
                    "TRACKER_PROJECT": "Common", "TRACKER_RELEASE": "Common 1.2",
                    "TASK_ID": "7", "TASK_FILE": task_file, "FRAMEWORK": _REPO_ROOT})
        result = subprocess.run(["bash", "-e", "-c", script], cwd=_REPO_ROOT, env=env,
                                capture_output=True, text=True, timeout=60)
        self.assertEqual(0, result.returncode, result.stderr)
        with open(os.path.join(tmp, "agent-prompt.txt")) as f:
            prompt = f.read()
        self.assertIn("**Title:** Plan a thing\n", prompt)
        self.assertIn("First line & | \\ ${BRANCH}\n\nSecond line\n</task-description>",
                      prompt)
        self.assertIn("**Id:** 7", prompt)

    def test_planning_rounds_retire_their_previous_workstreams(self):
        """Each planning round registers a workstream, and a finished round's
        workstream otherwise stays live forever; for the free-form round every
        one of them also edits the Manager Log. Each job archives its own
        prefix only, only once the gate has found no round of that prefix in
        progress, never on a forced run (which skips that check and could
        archive a plan still being implemented), and before it creates the
        next round's branch."""
        for name, prefix in (("plan-next-task", "project/plan-"),
                             ("plan-release-task", "project/task-")):
            steps = called_job(self.jobs[name])["steps"]
            with self.subTest(job=name):
                gate = next(i for i, s in enumerate(steps)
                            if "qa-cadence.sh" in s.get("run", ""))
                archive = next(i for i, s in enumerate(steps)
                               if "archive-stale-workstreams.sh" in s.get("run", ""))
                branch = next(i for i, s in enumerate(steps)
                              if "git checkout -b" in s.get("run", ""))
                self.assertLess(gate, archive)
                self.assertLess(archive, branch)
                self.assertEqual(prefix, steps[gate]["env"]["BRANCH_PREFIX"])
                self.assertEqual(prefix, steps[archive]["env"]["BRANCH_PREFIX"])
                condition = steps[archive]["if"]
                self.assertIn("steps.decide.outputs.run == 'true'", condition)
                self.assertIn("steps.decide.outputs.reason != 'forced'", condition)
                self.assertIn("github.repository", steps[archive]["env"]["REPO_URL"])

    def test_both_planning_rounds_share_one_backlog_limit(self):
        """Free-form plans and task plans compete for the same reviewer, so
        both rounds apply the same open-PR limit through the same script:
        the workflow's, which a called workflow receives as an input."""
        limits = set()
        for name in ("plan-next-task", "plan-release-task"):
            caller = self.jobs[name]
            job = called_job(caller)
            with self.subTest(job=name):
                self.assertIn("open-pr-backlog.sh", "\n".join(_run_steps(job)))
                limit = job.get("env", {}).get("MAX_OPEN_PRS",
                                               self.workflow["env"]["MAX_OPEN_PRS"])
                if job is not caller:
                    self.assertEqual("${{ inputs.max-open-prs }}", limit)
                    limit = str(caller["with"]["max-open-prs"])
                limits.add(limit)
        self.assertEqual({self.workflow["env"]["MAX_OPEN_PRS"]}, limits)

    def test_every_qa_round_applies_the_shared_backlog_limit(self):
        """Every round ends in a pull request for the same reviewer, so the
        QA rounds stop at the same open-PR limit as the planning rounds. Their
        cadence gate checks it (CHECK_BACKLOG) against the workflow's
        MAX_OPEN_PRS, which no job may override with a limit of its own."""
        self.assertEqual("7", self.workflow["env"]["MAX_OPEN_PRS"])
        for name, job in self.qa_jobs.items():
            gate = next(s for s in job["steps"] if "qa-cadence.sh" in s.get("run", ""))
            with self.subTest(job=name):
                self.assertEqual("true", gate["env"].get("CHECK_BACKLOG"))
                self.assertNotIn("MAX_OPEN_PRS", job["env"])
                self.assertNotIn("MAX_OPEN_PRS", gate["env"])
                self.assertIn("GITHUB_TOKEN", gate["env"])
                summary = next(s for s in job["steps"] if s["name"] == "Summary")
                self.assertIn("backlog-full)", summary["run"])

    def test_several_task_rounds_may_be_open_at_once(self):
        """Each task round plans a different task on its own branch and
        writes no shared file, so up to max-rounds of them run at once; the
        Project Manager keeps one. With several open, "a round may start"
        no longer means "every earlier round is over": the archive step
        must keep the rounds the gate counted as in progress."""
        caller = self.jobs["plan-release-task"]
        steps = self.task_planning["steps"]
        gate = next(s for s in steps if "qa-cadence.sh" in s.get("run", ""))
        archive = next(s for s in steps if "archive-stale-workstreams.sh" in s.get("run", ""))
        self.assertEqual("${{ inputs.max-rounds }}", gate["env"]["MAX_ROUNDS"])
        self.assertEqual(4, caller["with"]["max-rounds"])
        self.assertEqual("${{ steps.decide.outputs.live }}", archive["env"]["KEEP_BRANCHES"])
        self.assertNotIn("CHECK_BACKLOG", gate["env"],
                         "the round checks the backlog in its own step")

    def test_the_planning_dispatch_the_mcp_tool_uses_still_exists(self):
        """project_tools.py dispatches this file by name with this selector."""
        self.assertTrue(os.path.basename(_WORKFLOW).endswith("master-agent-dispatch.yaml"))
        self.assertIn("project-manager",
                      self.workflow[_ON]["workflow_dispatch"]["inputs"]["agent"]["options"])

    def test_the_retrospective_round_runs_at_max_effort_with_tests_locked(self):
        """The retrospective's hardest step is synthesis across many
        workstreams, jobs and PRs — the judgment a model under-thinks — so
        it carries the same claude/opus at max effort pin as pdsl-qa and
        performance-qa, and losing it fails nothing visible. The round is
        docs-only, so the test lock costs nothing and removes a temptation.
        It reads tools and writes markdown, so it needs no Metal: a
        REQUIRED_LABELS here would strand it on a GPU node for no reason."""
        job = self.qa_jobs["qa-retrospective"]
        submit = next(s for s in job["steps"]
                      if "submit-agent-job.sh" in s.get("run", ""))
        env = submit["env"]
        primary = json.loads(env["PHASE_CONFIGS"])["primary"]
        self.assertEqual("max", primary["effort"])
        self.assertEqual("opus", primary["model"])
        # Runner, model and provider must be set together; see the defect
        # hunt's note in the workflow for why one alone misroutes the phase.
        self.assertEqual({"runner", "model", "effort", "provider"}, set(primary))
        self.assertEqual("true", env["PROTECT_TEST_FILES"])
        self.assertNotIn("REQUIRED_LABELS", env)

    def test_the_retrospective_survey_covers_every_qa_prefix(self):
        """The survey reads one QA_PREFIXES list, passed from the workflow so
        the prompt never hard-codes a stale one. It must cover every QA job's
        BRANCH_PREFIX plus ci/qa- (the pipeline-subject follow-up convention),
        so a future eighth QA job cannot silently drop out of the survey."""
        job = self.qa_jobs["qa-retrospective"]
        # The literal list lives at job level; the build step forwards it to
        # the builder as the QA_PREFIXES variable.
        build = next(s for s in job["steps"]
                     if "build-retrospective-prompt.sh" in s.get("run", ""))
        self.assertIn("env.QA_PREFIXES", build["env"]["QA_PREFIXES"])
        prefixes = set(job["env"]["QA_PREFIXES"].split())
        required = {j["env"]["BRANCH_PREFIX"] for j in self.qa_jobs.values()}
        required.add("ci/qa-")
        self.assertLessEqual(required, prefixes,
                             "QA_PREFIXES misses: " + str(required - prefixes))

    def test_the_flaky_hunt_is_a_test_locked_opus_pilot(self):
        """The flaky-test hunt is a pilot that must never weaken the test that
        revealed the flake: it fixes the production/helper cause or files a
        follow-up, so it carries the per-job test lock. Like the defect hunt it
        pins claude/opus (telling a real flake from a deterministic failure is
        judgment), and an empty-handed round is valid so it sets no
        ENFORCE_CHANGES. It reads tools and runs bounded test selections, so it
        needs no Metal and carries no REQUIRED_LABELS."""
        job = self.qa_jobs["flaky-qa"]
        submit = next(s for s in job["steps"]
                      if "submit-agent-job.sh" in s.get("run", ""))
        env = submit["env"]
        self.assertEqual("true", env["PROTECT_TEST_FILES"])
        self.assertNotIn("ENFORCE_CHANGES", env)
        self.assertNotIn("REQUIRED_LABELS", env)
        primary = json.loads(env["PHASE_CONFIGS"])["primary"]
        self.assertEqual("opus", primary["model"])
        # Runner, model and provider must be set together; see the defect
        # hunt's note in the workflow for why one alone misroutes the phase.
        self.assertEqual({"runner", "model", "provider"}, set(primary))

    def test_the_flaky_pilot_does_not_raise_the_open_pr_limit(self):
        """MAX_OPEN_PRS is sized to the full-cadence QA rounds, one open PR per
        round for the same reviewer. The flaky hunt is a pilot (RETRO-20261022
        F6: "not a full cadence job"), so it deliberately does NOT raise that
        limit until it proves its yield — the eight QA jobs share a budget of
        seven. This pins that decision: a later session that promotes the pilot
        to a full round, or adds an eighth full-cadence round, must raise
        MAX_OPEN_PRS and update this test on purpose rather than by surprise."""
        self.assertIn("flaky-qa", self.qa_jobs, "the flaky pilot must be a QA round")
        self.assertEqual("7", self.workflow["env"]["MAX_OPEN_PRS"])
        full_cadence = [name for name in self.qa_jobs if name != "flaky-qa"]
        self.assertEqual(7, len(full_cadence),
                         "MAX_OPEN_PRS tracks the full-cadence QA rounds, pilot excluded")


if __name__ == "__main__":
    unittest.main()
