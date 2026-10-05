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
"""

import json
import os
import re
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
            "coverage-qa": 2,
            "consolidation-qa": 2,
            "performance-qa": 2,
            "pdsl-qa": 5,
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
        for name, job in self.jobs.items():
            with self.subTest(job=name):
                self.assertNotIn("self-hosted", str(job["runs-on"]))
            for step in job["steps"]:
                if not any(s in step.get("run", "") for s in controller_scripts):
                    continue
                with self.subTest(job=name, step=step["name"]):
                    env = step.get("env", {})
                    self.assertNotIn("CONTROLLER_HOST", env)
                    self.assertIn("FLOWTREE_CONTROLLER_URL", env.get("CONTROLLER_URL", ""))
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
        self.assertEqual("6", job["env"]["MAX_OPEN_PRS"])

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
        job = self.jobs["plan-release-task"]
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

    def test_the_task_planning_workstream_holds_the_planner_role(self):
        """The agent claims its task with tracker_claim_next_task, which
        ar-manager refuses unless the workstream holds the planner role."""
        steps = self.jobs["plan-release-task"]["steps"]
        register = next(s for s in steps if "register-workstream.sh" in s.get("run", ""))
        self.assertEqual("planner", register["env"]["TRACKER_CAPABILITIES"])
        submit = next(s for s in steps if "submit-agent-job.sh" in s.get("run", ""))
        self.assertEqual("true", submit["env"]["AUTO_CREATE_PR"])
        # Workspace defaults decide the model; the round sets none.
        self.assertNotIn("PHASE_CONFIGS", submit["env"])
        prompt = next(s for s in steps if "build-task-planning-prompt.sh" in s.get("run", ""))
        self.assertIn("steps.release.outputs.release", prompt["env"]["TRACKER_RELEASE"])

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
            steps = self.jobs[name]["steps"]
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
        both rounds apply the same open-PR limit through the same script."""
        limits = set()
        for name in ("plan-next-task", "plan-release-task"):
            job = self.jobs[name]
            with self.subTest(job=name):
                self.assertIn("open-pr-backlog.sh", "\n".join(_run_steps(job)))
                limits.add(job["env"]["MAX_OPEN_PRS"])
        self.assertEqual({"6"}, limits)

    def test_the_planning_dispatch_the_mcp_tool_uses_still_exists(self):
        """project_tools.py dispatches this file by name with this selector."""
        self.assertTrue(os.path.basename(_WORKFLOW).endswith("master-agent-dispatch.yaml"))
        self.assertIn("project-manager",
                      self.workflow[_ON]["workflow_dispatch"]["inputs"]["agent"]["options"])


if __name__ == "__main__":
    unittest.main()
