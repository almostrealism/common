# CI Pipeline — Guidelines for Claude Code

This file is the authoritative reference for understanding and modifying the CI
pipeline in `.github/workflows/analysis.yaml`. **Read this file before touching
any CI configuration.**

---

## Module Dependency Graph

The project has a strict layered architecture. Dependencies only flow downward
(higher layers consume lower layers; lower layers never depend on higher ones).

```
Layer 6 — Studio
  studio/compose    → music, ml
  studio/music      → audio
  studio/spatial    → compose
  studio/experiments→ compose, ml-onnx, ml-djl, ml-script, utils

Layer 5 — Extern
  extern/ml-djl     → ml
  extern/ml-onnx    → ml
  extern/ml-script  → ml

Layer 4 — Engine
  engine/utils      → space, chemistry, optimize
  engine/utils-http → utils
  engine/ml         → utils
  engine/audio      → utils
  engine/optimize   → graph
  engine/render     → space, utils

Layer 3 — Domain
  domain/graph      → geometry, heredity
  domain/space      → graph, physics
  domain/physics    → time, color
  domain/color      → geometry, stats
  domain/chemistry  → physics, heredity
  domain/heredity   → time

Layer 2 — Compute
  compute/algebra   → hardware
  compute/geometry  → algebra
  compute/stats     → algebra
  compute/time      → geometry

Layer 1 — Base
  base/hardware     → collect
  base/collect      → code
  base/code         → relation, io
  base/relation     → meta
  base/io           → meta
  base/meta         (root — no AR dependencies)
```

### Standalone Modules (above engine layer)

These modules are **not part of any named layer** and have their own test jobs or
run in the build job. They depend on engine-layer modules but nothing in the
named layers depends on them.

```
flowtree/api          → utils
flowtree/base         → io
flowtree/graphpersist → utils
flowtree/agents       → flowtree/base, meta
flowtree/python       → flowtree/api
flowtree/runtime      → flowtree/api, flowtree/base, flowtree/agents, flowtree/python, flowtree/graphpersist, utils-http
tools                 → ml
```

(Artifact IDs: `ar-flowtreeapi`, `ar-flowtree-base`, `ar-flowtree-agents`, `ar-flowtree-python`, `ar-flowtree-runtime`, `ar-graphpersist`.)

**Critical facts:**
- `flowtree/runtime` CONSUMES engine-layer modules. No named layer depends on the flowtree family.
- `tools` CONSUMES `ml` (engine layer). Tools tests run in `code-policy-check`
  and `test-timeout-check`, not in any layer-gated job.
- `flowtree/api`, `flowtree/base`, `flowtree/agents`, `flowtree/graphpersist`, and `flowtree/python` are only consumed by the flowtree family.
- `flowtree/agents` holds the `AgentRunner` abstraction + `ClaudeCodeRunner`; the runtime
  depends on it (not the reverse), and the registry's hard-wired `ClaudeCodeRunner::new`
  default is now in this module — runners that need flowtree/runtime types must
  register themselves via `AgentRunnerRegistry.register(...)` from a higher module.

---

## How Layer-Gating Works

The `changes` job detects which top-level directories changed and sets flags:

| Flag               | Directory                  | Jobs gated on it                |
|--------------------|----------------------------|----------------------------------|
| `base_changed`     | `base/`                    | `test`                           |
| `compute_changed`  | `compute/`                 | `test`                           |
| `domain_changed`   | `domain/`                  | `test`                           |
| `engine_changed`   | `engine/`                  | `test`, `test-media`             |
| `extern_changed`   | `extern/`                  | `test-media`                     |
| `studio_changed`   | `studio/`                  | `test-media`                     |
| `python_changed`   | any `*.py` + `tools/mcp/requirements.txt` | `python-tests`    |
| `agent_protection_changed` | `tools/ci/agent-protection/` | `python-tests` (the branch-check shell suites) |
| `agent_isolation_changed` | agent compose/entrypoint + isolation validator (+ `analysis.yaml`) | `agent-volume-isolation` |
| `images_changed`   | Dockerfiles, `.dockerignore`, compose, `tools/mcp/`, `tools/tracker/`, `docs/`, `CLAUDE.md` | `docker-build` |

**No flag exists for `flowtree/` or `tools/` Java code.**
Changes to those directories set `code_changed=true` (triggering the build) but
no layer flag — so all layer-gated test jobs are skipped. This is intentional:
flowtree tests always run in the `test-flowtree` job regardless of what changed
— with one gate: `test-flowtree` needs `python-tests` success-or-skipped. The
flowtree runtime drives the Python tooling (which is also why it is not gated
on Java changes), so when the Python suite ran and failed there is nothing sound
for the Java suite to prove. The gate also keeps the remediation paths apart: a
python failure goes to `auto-resolve-python`, while `test-flowtree` is a
long-running, retry-eligible job whose failures belong to `auto-resolve`. `python-tests` is
skipped only by its own path gate, never by an upstream failure, so that
`skipped` is unambiguous.
The `python_changed` flag is a path-based (not layer-based) flag that gates
`python-tests`; Python sources are not part of the layered Java module graph.
The `agent_isolation_changed` flag is likewise path-based and gates
`agent-volume-isolation` (folded in from a former standalone workflow).

### What the `agent-volume-isolation` job covers

Runs the Python validator (`tools/ci/validate_agent_volume_isolation.py`) and its
unit tests, enforcing that FlowTree agent containers cannot share a writable
volume. Path-gated on `agent_isolation_changed`; depends only on `changes` (no
Maven build). Does not upload coverage. Part of the `all-checks` gate (skipped →
treated as passing).

### Remediation jobs: `auto-review`, `auto-resolve-python`, `auto-resolve`

Three jobs decide what a coding agent is sent to do about a pipeline, and each
**attempt of a run submits at most one of them** — two agents on one branch make
conflicting edits and at least one of them fails.

| Job | When | Prompts | Submission |
|-----|------|---------|------------|
| `auto-resolve-python` | `python-tests` failed | Python test failure | At once, from this run |
| `auto-review` | attempt 1 only, `python-tests` not failed | build failure → code policy → quality gates → docs-only review → general review (first match; always submits — a gate that failed without a recorded cause gets the general review with a note not to chase it) | As soon as the gates report, from this run |
| `auto-resolve` | attempt ≥ 3, `python-tests` not failed | long-running test failures, test-job crash, incomplete execution | Staged; `auto-resolve-submit.yaml` submits it after the run |

The docs-only review (`docs-review.txt`) reviews and improves documents and
**never implements a plan**, and it keeps its edits under `docs/` so the branch
stays on that route. A plan branch's first commit is always docs-only, so this
route reaches every plan as soon as it is proposed; it once sent the
implementation prompt (`verify-completion.txt`) and so carried out every plan
before anyone had read it. Implementation starts only when the owner approves
the plan — by marking its draft pull request ready for review, which the
`Plan Ready` workflow turns into a `verify-completion.yaml` dispatch (see below),
or by dispatching that workflow by hand. The docs-only review runs on a draft
plan PR exactly as on any other PR (GitHub fires `pull_request` for drafts), and
that is wanted: it is what improves the plan before approval.
`tools/tests/test_remediation_job_exclusivity.py` pins that `auto-review`
cannot reach the implementation prompt.

The early two exist so that an agent reaches a stopping point — gates green, no
simple fixes or review comments outstanding — before anyone pays for the
long-running suites. The quality gates are deterministic, so there is nothing to
retry before acting on them; `auto-review` submits while the test jobs are still
running, and its prompts say that test results are not yet known. The
long-running jobs (`test`, `test-flowtree`, `test-media`, the mac and CL lanes)
are flaky, so their failures are only handed to an agent once
`rerun-flaky-tests.sh` has spent its retries.

Exclusivity is structural, not a handoff between jobs:

- `auto-review` runs on attempt 1 only and `auto-resolve` on `>= 3`; attempt 2
  submits nothing. 3 is `MAX_ATTEMPTS` in `auto-resolve-submit.yaml`: the retry
  gate re-runs a failed run's failed jobs until attempt 3, so attempt 3 is the
  first whose test failures reach an agent. Change one number and you must
  change the other. `auto-review` is not allowed a second attempt because a
  retry re-runs it whenever one of its inputs failed, and its first agent —
  submitted minutes into attempt 1 — is very likely still working when a test
  failure brings attempt 2 around.
- A `python-tests` failure skips both `auto-review` and `auto-resolve`, skips
  `test-flowtree` (which gates on python-tests success-or-skipped), and is never
  retried by `rerun-flaky-tests.sh`, so `auto-resolve-python` is its only
  submission.
- Retries only happen when a long-running test job (`test`, `test (N)`,
  `test-*`) failed, i.e. the pipeline got past the gates to the slow stage.

`tools/tests/test_remediation_job_exclusivity.py` pins all of this.

**No job that runs pull request code holds controller credentials.**
`auto-review` and `auto-resolve-python` run the branch's prompt builders, so they
only stage a request (`auto-review-request`, `auto-resolve-python-request`); a
separate `auto-review-submit` / `auto-resolve-python-submit` job checks out the
default branch and sends it with `tools/ci/submit-staged-request.sh`, which reads
`submit.env` through a key allowlist instead of appending it to `$GITHUB_ENV`.
`auto-resolve-submit.yaml` uses the same script. Keep any new submission path on
that pattern.

This guards against pull request *scripts*, not against a pull request's edit to
the *workflow*: a `pull_request` run uses the workflow from the PR's merge with
the base, and any job in it can read a repository secret, so a branch that edits
`analysis.yaml` can reach `FLOWTREE_CF_ACCESS_CLIENT_SECRET` (as it can through
`register-workstream`). `verify-completion.yaml` has the same exposure by a
different route: `workflow_dispatch` runs the workflow file from the dispatched
branch, so the framework checkouts in the `implement-plan.yaml` it calls protect
the secret from the branch's scripts but not from the branch's edit to that YAML. Moving the submission into
a reusable workflow on the default branch would not close this, because the
branch's caller YAML still decides which jobs receive the secret; closing it
needs the secret scoped so that a branch's workflow cannot read it at all.
This is accepted for now, since pipelines do not run for
pull requests from outside the organization and agent commits cannot change CI
files outside `ci/...` branches. The fix is tracked in the ar-manager tracker
("Keep FlowTree controller credentials out of pull_request workflow runs").

`auto-review-submit` sets `DELAY_SECONDS: "300"`. `auto-review` reports as soon
as the gates do, which can be before GitHub Copilot has finished reviewing the
same push (about eight minutes), and a review agent that starts before those
comments exist cannot act on them. The controller holds the job for the delay
before dispatching it. The delay comes from the submit job's own environment,
never from the staged request.

`STARTED_AFTER` is the controller's stale-submission guard: it skips a job when
anything was submitted on the workstream after that time. The early submitters
send the pipeline's start (`needs.changes.outputs.started_at`). `auto-resolve`
stages none; `auto-resolve-submit.yaml` sends the start of the attempt that
staged the request (`workflow_run.run_started_at`, which moves forward with each
re-run). The slow path is attempt-scoped: measured from the pipeline's start,
auto-review's own attempt-1 submission counted as newer work and the controller
skipped every auto-resolve that followed one (run 36380449113). A skip is a
successful response, so `submit-agent-job.sh` reports it as a warning and in the
step summary rather than failing the step.

When a Metal lane (`test-mac`, `test-media-mac`, or any matrix entry of them)
failed in the attempt being resolved, `auto-resolve-submit.yaml` submits with
`REQUIRED_LABELS={"platform":"macos"}`, so the agent lands on a Node that can
reproduce the failure. `tools/ci/remediation-required-labels.sh` decides this
from the run's job list in the GitHub API, never from the staged request, and
fails the step if it cannot list the jobs rather than submitting unpinned.

No remediation job declares `environment:` — in a `pull_request` run that
attaches a deployment status to the PR head, and an abandoned one shows as a
spurious "had a problem deploying" red X. `auto-resolve-submit.yaml` keeps its
`worker` environment as an emergency bulkhead; approval is not a design
assumption, and the early jobs do not go through it. None of the remediation
jobs is part of `all-checks`.

Every prompt carries the same pull-request review-comment policy, patched in
from `tools/ci/prompts/pr-feedback.txt` (see `tools/ci/README.md`).

**`PROTECT_TEST_FILES` is on only for the jobs sent to make failing tests
pass.** It turns on the harness's per-job test lock (`TestMethodProtection`;
see `flowtree/runtime/docs/file-staging.md`): every test method that exists
on master stays exactly as it is for that job. An agent sent to fix a failing
test has repeatedly loosened it instead, which fails `test-integrity-check`,
which dispatches an agent to restore it, which fails the test again — the lock
breaks that loop. So the "test failures", "test job crash" and "python test
failures" requests set it to `"true"`, and every other request (build failure,
code policy, quality gates, docs-only review, general review, incomplete test
execution) sets it to `"false"` and is held to `test-integrity-check` alone,
the rule every branch meets. `tools/tests/test_analysis_yaml_protect_test_files.py`
pins that mapping. The early submit jobs set the flag themselves;
`submit-staged-request.sh` lets a staged request turn it on but never off, and
`submit-agent-job.sh` defaults it to off. The lock's protected paths are
`src/test/`, `src/it/` and the CI directories, so for the Python-failure job it
adds nothing over `test-integrity-check`'s Python step; the Python suites under
`tools/` are not in them. In `master-agent-dispatch.yaml` the
performance, consolidation, PDSL-migration and flaky-test-hunt rounds keep it
on, because their premise is that existing tests stay the reference.

**A required test job's `failure` result must never go unresolved on
`auto-resolve`'s attempt.** The `Check for incomplete test execution` step
reads every required job's raw `needs.<job>.result` and, if any is `failure`,
routes to the crash prompt regardless of whether Surefire XML was parsed.
`test-flowtree` originally had no `needs.test-flowtree.result` check here at
all — back when one job owned every prompt, a `test-flowtree` failure fell
straight through to a prompt claiming everything had passed. (The quality-gate
and general-review prompts now go out before test results exist, and say so.)

**Every required test job must upload a Surefire artifact the allowlist
keeps.** The "required" set is `analysis.needs` minus `build` — the same set
`all-checks` waits on (see "What the `analysis` job does" below), which
includes every test lane: CPU, flowtree, Metal and OpenCL.
`Filter to resolvable surefire reports` keeps only artifact names the
allowlist recognises and deletes the rest before
`parse-surefire-failures.sh` ever runs, so a job whose artifact name the
allowlist does not match has its real failures silently discarded — it still
routes correctly (via the `needs.<job>.result` check above), but through the
generic crash prompt instead of one naming the actual failing tests.
`test-flowtree` uploaded no Surefire artifact at all until this was fixed;
`test-media`'s (`surefire-media-${{ matrix.group }}`) was uploaded under a
name the allowlist's `surefire-media` entry (no wildcard) could never match.
`tools/tests/test_auto_resolve_test_job_coverage.py` (see `tools/ci/README.md`)
pins both wirings for the full required set, so adding a job to
`analysis.needs` without wiring it into the failure-detection step and the
allowlist fails that test.

**A gate is reported to an agent only when its cause is known.** The message
`auto-review` builds becomes an instruction, and an agent handed "this branch
weakened its tests" will act on it. `test-integrity-check` therefore publishes
`failure_reason` — `enforcement-tampering`, `exfil-guard`, `test-hiding`,
`python-test-hiding` (a detector ran and found something), `infrastructure`
(a detector could not run), or empty — written by the step that reached the
verdict, since only that step knows whether a non-zero exit was a finding or a
crash. `check-quality-gates.sh` lists the four findings and reports nothing for
anything else, so a missing script, a skipped job, or a detector that died
blocks the pipeline for a human instead of being reported to an agent as a
finding against an innocent branch. `auto-review` still submits the general
review for such a run, with a note that the failed gate is not the agent's to
fix. When adding a detector to that job: emit a reason for the
finding, emit `infrastructure` for every other non-zero exit, and add the arm
to `check-quality-gates.sh` — `tools/tests/test_integrity_check_wiring.py`
holds the two ends together.

**A step must not assume its own scripts are in the checkout.** On a
`pull_request` the workflow file comes from the merge with the base, while the
checkout is `github.event.pull_request.head.sha` — the PR head alone. A step
added today therefore runs against every open PR's older tree, including PRs
whose head predates the script that step invokes; an unguarded
`chmod +x ./tools/ci/...` on such a run fails with "No such file or directory"
and, before this was fixed, reported that failure as an integrity violation.
Check the file is there first, and date its absence against the merge base:
absent there too means the branch predates it (benign), present there means it
was removed (a real finding) — and only once a merge base has actually been
established, since without one nothing about the branch is known.

An enforcement check is the exception: there, "the branch predates it" is not
benign, because skipping would let any branch based before the check existed
pass unchecked. The CI file lock and the Python test-hiding detector therefore
run a trusted copy of `tools/ci/agent-protection/` extracted from
`origin/master` (with `git archive`, into a temporary directory) when the head
lacks the script, and fail if even `origin/master` does not have it.
`tools/tests/test_enforcement_script_fallback.py` pins this. A new enforcement
step follows the same pattern.

**The `ci/...` carve-out covers `.claude/hooks/` too, and covers comparisons
only.** Both the enforcement-tampering check and the exfiltration guard's
integrity comparison treat a `ci/` branch as out of scope, for the reason the
carve-out has always rested on: a change permitted to rewrite the workflow
could delete the step that runs the check, so comparing its edits obstructs
pipeline work without policing anything.

The guard's presence and registration checks (CHECK 1 and CHECK 2 in
`verify-exfiltration-guard.sh`) are **not** exempt on any branch, and the
distinction is the whole safety of the carve-out. An edit compared against the
base is a question about this branch's diff; whether the guard exists and is
registered in `.claude/settings.json` is a question about the tree an agent
session will run with. Lifting the first unblocks pipeline work. Lifting the
second would let a branch name disable the runtime guard for every session on
the branch — so a `ci/` branch may rewrite the guard, and may not remove or
unregister it. The policy detectors under `engine/utils` stay locked on every
branch.

### Branch checks: test integrity, the CI file lock, and agent-commit-validation

Three separate checks hold a branch's change set, each in the job where it
belongs:

- **`test-integrity-check` is the test rule.** An existing test may be edited
  but not weakened: `detect-test-hiding.sh` (Java patterns) and
  `detect-python-test-hiding.sh` (every base-branch `def test_*` survives, and
  a test file's assertion count does not fall), plus the
  enforcement-tampering check and the exfiltration guard. Instructions to
  agents cite this rule, not a stricter one.
- **The CI file lock runs in `changes`.** `check-ci-file-lock.sh` fails the
  first job when a branch not named `ci/...` changes `.github/workflows/` or
  `tools/ci/` (a controller-signed `Sensitive-File-Bypass` trailer lifts it).
  It needs only git, so a violating branch never gets as far as `build`.
  Nothing is dispatched for it: every other job needs `changes`. The harness
  applies the same lock before a commit exists (`protectCiFiles` in
  `FileStager`, on for every job but a signed-bypass or `ci/...` one), so an
  agent's workflow edit is dropped at staging rather than failing a pipeline.
  A signed bypass covers only the commits that carry it: every commit that
  changes a CI file must carry its own valid trailer.
- **`agent-commit-validation` rejects a change set that only edits master's
  tests** — every changed file a test file that exists at the merge-base, none
  of them gaining a new test. Those tests pass on master, so such a change set
  can only change what the suite reports. The job fails when it blocks, and
  the `override_integrity_checks` dispatch input lets a run through for a known
  false positive, as it does for the integrity checks. A head from before these
  checks were split carries the old validator; its retired exits (2, 4) are
  passed with a notice, and `check-quality-gates.sh` does not report their
  reasons to an agent.

All three scripts are on `test-integrity-check`'s protected list, and
`tools/ci/agent-protection/test-branch-checks.sh` covers them (run by
`python-tests` when `agent_protection_changed`).

### What the `build` job covers

The `build` job always runs when `code_changed=true`. It is the critical path
blocker: every downstream job depends on it, so it MUST stay as short as
possible. It does one thing: `mvn install -DskipTests`. It does not run tests
and does not upload coverage.

### Re-run jobs and artifacts

A retry ("Auto-Resolve Submit" re-running failed jobs) runs jobs again inside
the same workflow run, so two rules hold for every artifact:

- **Every upload sets `overwrite: true`.** `upload-artifact@v4` refuses a name
  already used in the run otherwise, so a re-run job's `if: always()` upload
  would fail even when its tests passed, and `auto-resolve` on attempt 3 would
  read the earlier attempt's report.
- **A download that can span attempts uses the REST route** (`github-token`,
  `repository`, `run-id`, with `actions: read`): the default lookup 404s on an
  artifact uploaded in an earlier attempt.

`tools/tests/test_build_artifact_sharing.py` pins both. One gap remains: a
re-run job that uploads nothing (it crashed before writing any report) leaves
the earlier attempt's report in place. The job still fails, so a remediation is
still warranted, but its prompt can name the earlier failures.

### Sharing the build

`build` is the only job that runs `mvn install`. It clears the
`org.almostrealism` tree the Maven cache restored, installs, and uploads that
tree as the `maven-installed-artifacts` artifact. Every job that used to run its
own `mvn install -DskipTests` — the three `tools` policy checks,
`test-flowtree`, every test lane, and `analysis` — downloads it instead:

- Jobs run `mvn test -pl <module>` with no `-am`, so Maven takes every other
  module from the local repository. "Download build artifacts" fetches
  `build`'s jars into `runner.temp`, and "Restore build artifacts" copies them
  into the repository Maven reads: `$MAVEN_REPO` where the macOS and CL jobs
  isolate one ("Isolate Maven repository per runner" exports it), otherwise
  `$HOME/.m2/repository`. The copy is done by the shell on purpose: those are
  environment variables, and an action input cannot rely on seeing a value an
  earlier step wrote to `GITHUB_ENV`.
- `analysis` downloads the jars to a temporary directory and hands the main
  ones to JaCoCo as `--classfiles`, rather than rebuilding for `target/classes`.

The restore is a download step and three lines of shell, not a script, on
purpose: a pull request runs the workflow from its merge with the base but
checks out its own head, which may predate any script added for this. A new
job that needs the project's artifacts restores them the same way and lists
`build` in `needs`; do not add another `mvn install`.

`checkstyle` does **not** depend on `build` and does not install anything: the
Checkstyle rules are Checkstyle's own modules and `checkstyle:check` resolves
no project dependencies, so it runs straight from the sources, in parallel
with `build`. Do not add an `mvn install` back to it.

### Every module with tests must be named by some job

A Maven module whose tests no job runs is worse than one with no tests: new
tests get added there and silently never execute, so the module reads as
covered. **Whenever you add a module to the root `pom.xml`, add it to a test job
in the same change**, even if it has no tests yet — that way the first test
committed to it runs immediately.

To audit the current state:

```bash
CI_RUN="base/hardware base/io engine/utils engine/utils-http engine/ml engine/render \
engine/audio extern/ml-onnx studio/music studio/compose studio/spatial studio/experiments \
flowtree/api flowtree/base flowtree/python flowtree/agents flowtree/graphpersist \
flowtree/runtime tools"
for f in $(find . -maxdepth 3 -name pom.xml | grep -v target | sort); do
  m=$(dirname $f | sed 's|^\./||'); [ "$m" = "." ] && continue
  n=$(find "$m/src/test" -name '*Test*.java' 2>/dev/null | wc -l); [ "$n" -eq 0 ] && continue
  case " $CI_RUN " in *" $m "*) ;; *) echo "UNCOVERED: $m ($n test files)";; esac
done
```

Keep `CI_RUN` above in sync with the `-pl` arguments in the job steps.

### What the `test-flowtree` job covers

Runs one Maven invocation over **every** flowtree module —
`flowtree/api,flowtree/base,flowtree/python,flowtree/agents,flowtree/graphpersist,flowtree/runtime`
— and uploads JaCoCo coverage as `coverage-flowtree`. `api` and `python` carry
no tests today and are listed anyway, per the rule above. Gated on the same
validation prerequisites as the `test` matrix (`code-policy-check`,
`test-timeout-check`, `duplicate-code-check`, `test-integrity-check`) and runs in
parallel with `test`. Extracted from `build` because flowtree tests are slow and
would otherwise block every other job.

Also uploads Surefire reports as `surefire-flowtree` with `if: always()`, so a
`test-flowtree` failure is auto-resolved with the specific failing tests named,
not just a generic crash prompt. See "Auto-resolve's required-test-job
coverage" above and `tools/ci/README.md`.

### What the `test` job covers

Runs the main test matrix (5 groups) for engine/domain/compute/base layers:
`base/hardware`, `engine/utils`, `engine/ml`, plus `engine/render` and
`base/io,engine/utils-http` on group 0 only. Skipped when none of those layers
change. Uploads `coverage-group-{0..4}`.

### What the `test-media` job covers

Runs `engine/audio`, `studio/music`, `studio/compose`, `studio/spatial`, and
`extern/ml-onnx,studio/experiments` on a self-hosted runner. Runs after `test`
(CPU lane, below). Skipped when none of
studio/extern/engine/domain/compute/base change. Uploads `coverage-media`.

### Tests excluded from the pipeline profile

`@TestProperties(excludeProfiles = TestUtils.PIPELINE)` makes `TestDepthRule`
skip a method via `Assume.assumeTrue()` when `AR_TEST_PROFILE=pipeline`, while
leaving it runnable locally. This is the sanctioned mechanism for a test CI
cannot host — prefer it over an early `return`, which reports the test as
**passed**. Note that `@TestDepth` and `longRunning()` will not do this: the
pipeline profile deliberately ignores both.

The table below lists the original three uses, each there because the test
needs an asset or port the runners do not have. It is **not** the full set:
about forty methods now carry the annotation (mostly heavy studio/compose
benchmarks), with no central registry, and nothing in `test-integrity-check`
flags the annotation being added to an existing test.
`knownIssue = true` and `highMemory = true` also always skip under the
pipeline profile. Run `grep -rn "excludeProfiles *=" --include='*.java' .` for
the current list, and see
[`docs/internals/test-skip-gating-prevention.md`](../docs/internals/test-skip-gating-prevention.md)
for why adding any of these to an existing test must be treated as a skip, and
for the proposed ledger that would make each one a reviewed declaration. Drop
an annotation once the test's reason no longer applies:

| Test | Reason |
|------|--------|
| `OnnxPrototypeDiscoveryTest.discoverWithOnnxFeatures` (studio/experiments) | `Assert.fail()`s without the ONNX encoder/decoder models and a real sample library |
| `OnnxAutoEncoderTests.encode` (extern/ml-onnx) | loads a model from `assets/stable-audio`; NPEs from the ONNX runtime when absent |
| `EventDeliveryTest.deliver` (engine/utils-http) | binds fixed port 8080 (`BindException` if occupied) and sleeps 57s while asserting nothing |

In all three cases the **module** stays in the test matrix, so a test added
there later runs with no further wiring. That is the point: exclude a method,
never a module.

### A `needs` chain requires `!cancelled()` to tolerate a skipped stage

GitHub applies an implicit `success()` when a job-level `if:` contains no status
check function (`always()`, `!cancelled()`, `failure()`, `success()`), and that
`success()` covers **every job upstream**, not only the ones in `needs`. A
`needs.<job>.result == 'skipped'` clause is therefore **dead code** on its own —
the dependent job is skipped before the `if` is evaluated — and a job whose
direct `needs` all succeeded is still skipped when any job further up was
skipped or failed. That is how run 35998737943 skipped `auto-review-submit`:
`auto-review` succeeded and staged a request, but `python-tests`, upstream of
it, had been skipped. `auto-review` and `auto-resolve-python` run exactly when
`python-tests` was skipped or failed, so both submit jobs start their `if:` with
`!cancelled()` and check the producer's `result` explicitly.

Every lane-chained job (`test-media`, `test-media-mac`, `test-cl`,
`test-media-cl`, `test-cuda`, `test-media-cuda`) starts its `if:` with
`!cancelled() &&` for this reason. Their explicit `result == 'success'` checks
on `build` and the four validation jobs already exclude every failure path, so
`!cancelled()` does not weaken the gate — it is what makes the gate run at all.
Never add a lane stage that depends on a layer-gated stage without it.

### Lanes: CPU (linux), GPU (macOS), CL (linux/ROCm) and CUDA (linux/NVIDIA)

The CUDA lane (`test-cuda` → `test-media-cuda`) is described in its own section
below, "What the `test-cuda` and `test-media-cuda` jobs cover". It runs in
parallel with the CL lane but is admitted later: after the macOS lane as well
as the CPU lane. The rest of this section describes the other three lanes. All
four are part of the merge gate.

The self-hosted test jobs run as three lanes, each serialised internally so
heavy suites do not contend on their fleet:

- **CPU lane (linux, `ar-ci`):** `test` → `test-media`.
- **GPU lane (macOS, `ar-ci`):** `test-mac` → `test-media-mac`.
  Only one GPU-heavy Metal suite runs on the macOS fleet at a time — this keeps
  the studio benchmark tick tails from ballooning under GPU contention (the
  mechanism behind the PDSL hot-path timeouts).
- **CL lane (linux/ROCm, `ar-ci-cl`):** `test-cl` → `test-media-cl`.
  Serialised for the same reason: the ROCm host has a single GPU.

The CPU and GPU lanes run in parallel. The CL lane starts once the **CPU lane**
has finished, and runs alongside the GPU lane: `test-cl` beside `test-mac`,
`test-media-cl` beside `test-media-mac`. `test-cl` gates on `test` and
`test-media` (each success-or-skipped), and `test-media-cl` gates on `test-cl`
**and on those same two jobs directly**. The second part is not redundant:
`test-cl` is skipped either by its own layer gate or by an upstream failure, and
its `skipped` result cannot tell the two apart — gating `test-media-cl` on
`test-cl` alone let it run after a Linux suite had failed. A downstream job must
never infer "upstream was fine" from a `skipped` that has more than one cause;
it gates on the upstream lanes itself. This is admission control, not GPU
serialisation — the fleets are different machines. The CL lane is fourteen
matrix jobs on a single host, and it is "Linux plus an accelerator": a branch
that cannot pass the Linux CPU suites will not pass their OpenCL duplicates
either, so the ROCm runners are spent only on branches that have cleared them.

The CL lane does **not** wait for the Metal suites. It once did, so that a
branch failing on Metal would not spend the ROCm fleet, but that held a healthy
pipeline's CL lane until the slower of the two other lanes had finished. Running
it beside the macOS lane lets more of the pipeline start as soon as the Linux
suites are green; the cost is that a branch that fails only on Metal still runs
its CL jobs.

The CL lane was formerly the third and fourth stages of the macOS GPU lane. It
moved to its own AMD/ROCm fleet (`tools/ci/rocm`) to give the OpenCL backend a
real, non-deprecated OpenCL implementation. The gates on `test-mac` and
`test-media-mac` were dropped at that point, restored for admission control,
and dropped again so the two accelerator lanes run side by side. The gate on the
CPU lane came after a pipeline in which the mac lane passed, the linux lane
failed, and the CL lane ran all its jobs anyway.

The `ar-ci-cl` label is deliberately distinct from `ar-ci`. If the ROCm host
also carried `ar-ci` it would start picking up general CPU test jobs, putting
the CL lane behind an unrelated queue.

Each stage gates on the four validation checks directly (it no longer inherits
them by depending on `test`). Within a lane, each stage gates on ALL earlier
stages with success-or-skipped, so a failure anywhere earlier skips the rest of
the lane; a stage skipped by its own layer gate is tolerated. Because the lanes
run in parallel, the mac jobs now run even when the linux `test` job fails.
(Follow-up idea, deferred: replace the coarse job-level layer skip — which loses
the reason a job was skipped and forced the cross-stage gating — with per-job
change-awareness that only runs the modules that changed.)

### What the `test-cl` and `test-media-cl` jobs cover

OpenCL-backend duplicates of `test-mac` and `test-media-mac`, running with
`AR_HARDWARE_DRIVER=native,cl` instead of `*` — under `*`, Metal always wins GPU
context selection, so the CL backend is otherwise never exercised by CI. They
are the two stages of the CL lane (`test-media-cl` after `test-cl`). Every step
in the CL variants uses `native,cl` where its counterpart uses `*`.

They run on the **ROCm fleet** — `[self-hosted, linux, ar-ci-cl]`, an AMD host
with a real OpenCL implementation, set up per `tools/ci/rocm/README.md`. Because
the fleet is Linux, every step uses `LD_LIBRARY_PATH` where the macOS jobs use
`DYLD_LIBRARY_PATH`; `AR_HARDWARE_LIBS` is unchanged and remains the primary
mechanism. `test-media-cl` additionally sets `AR_RINGS_LIBRARY` and
`AR_RINGS_PATTERNS` at job level, pointing at the curated sample library
bind-mounted into the runner container. Do not drop those: the studio benchmarks
either fail outright without the library (a GPU is available, so
`AudioSceneTestBase.requireCuratedLibrary()` fails rather than skips) or fall
back to synthetic samples and report timings that measure nothing real.

**Every GPU media lane declares and verifies the curated library.**
`test-media-mac`, `test-media-cl` and `test-media-cuda` all set
`AR_RINGS_LIBRARY` / `AR_RINGS_PATTERNS` at job level (the mac lane to the
fleet's `/Users/Shared/Music` copy), and each runs a `Verify curated sample
library` step right after checkout that fails the job when the library is
missing or empty or the pattern factory is missing. The tests enforce the same
rule through `TestFeatures.requireResource` (the one place the missing-resource
policy is decided): a missing resource fails on any GPU host and on any host
that declares its location, and is skipped — never passed — only on a host with
no GPU that did not declare it, i.e. the CPU `test-media` lane. A new runner
without the library therefore turns its lane red instead of letting tests that
should have failed report a pass.

The memory settings below (`AR_HARDWARE_MEMORY_SCALE=7`, the group split, the
`_JAVA_OPTIONS` direct-memory caps, `AR_HARDWARE_NATIVE_DIRECT_BUFFERS=disabled`)
were all tuned for Apple's OpenCL on the macOS fleet and moved across unchanged.
The AMD device exposes far more memory, so several are likely over-constrained —
retune them one variable at a time, as measured follow-ups, so a regression stays
attributable.

The self-hosted jobs split their suites across several groups, with group counts
tuned per lane: `test-media-cl` uses eight; `test-media-cuda` seven;
`test-cl` six; `test`, `test-media` and `test-mac` five; and `test-cuda` and
`test-media-mac` four. The CL backend hits its
memory ceiling under large per-group loads even at `AR_HARDWARE_MEMORY_SCALE=7`
(the highest scale used anywhere — the scale is exponential, so raising it
further is not an option), so spreading the tests across several JVMs keeps each
group's load small. Several groups also shrink the retry unit: re-running failed
jobs re-runs only the failed group, not the whole suite. A job's matrix and the
`AR_TEST_GROUPS` its steps pass must agree, or the groups with no matrix entry
never run; `tools/tests/test_accelerator_lane_admission.py` checks this for
`test-cl`.

The CL and CUDA jobs each run up to three groups at a time (`max-parallel: 3`).

`test-media-mac` is the exception: it uses **4 groups at `max-parallel: 2`**
(up to two groups at a time), tied with `test-cuda` for the fewest groups of any lane. As the second GPU-lane stage it
already runs alone on the fleet (no other GPU-heavy stage runs concurrently), so
running two of its four groups at once trades some GPU contention on the
studio benchmark tick tails for shorter wall-clock time.

The accelerator jobs upload surefire reports — `surefire-mac-group-*` and
`surefire-media-mac-group-*` (Metal), `surefire-cl-group-*` and
`surefire-media-cl-group-*` (OpenCL), `surefire-cuda-group-*` and
`surefire-media-cuda-group-*` (CUDA) — so a failure specific to one backend,
which a Linux CPU run would not surface, is still parsed and auto-resolved.

**Every accelerator lane is part of the `all-checks` merge gate.** The CL and
CUDA jobs were each informational at first, outside the gate and the
auto-resolve routing, while their backend was new; each was made required once
it passed reliably, because the goal is a pipeline that holds every
accelerator it tests to the same bar. They upload no coverage, but they appear
in `analysis.needs` for the same ordering reason as the mac jobs (see "What the
`analysis` job does"), and therefore in `auto-resolve`'s failure routing and
Surefire allowlist. A new accelerator lane joins the same way: `analysis.needs`,
`all-checks` (needs + env + `check_job` + summary line), `auto-resolve.needs`,
the `Check for incomplete test execution` step, and a Surefire upload the
allowlist keeps — `tools/tests/test_auto_resolve_test_job_coverage.py` fails
until all of them are in place.

### What the `test-cuda` and `test-media-cuda` jobs cover

CUDA-backend duplicates of `test-cl` and `test-media-cl`: the same modules,
memory settings and `AR_HARDWARE_PRECISION=FP32` (group counts are tuned per
lane; see above), with
`AR_HARDWARE_DRIVER=native,cuda` where the CL jobs use `native,cl`. They are the
two stages of the CUDA lane (`test-media-cuda` after `test-cuda`, because the
host has one GPU), on `[self-hosted, linux, ar-ci-cuda]`: an NVIDIA DGX Spark set
up per `tools/ci/cuda/README.md`. The fleet reuses the Linux CPU fleet's image on
the host's own Docker and NVIDIA Container Toolkit, so it installs nothing on the
host.

**Admission** is stricter than the CL lane's. Both stages gate on `test`,
`test-media`, `test-mac` and `test-media-mac` (success-or-skipped) plus `build`
and the four validation checks, and `test-media-cuda` gates on those upstream
lanes directly as well as on `test-cuda`. The CL lane no longer waits for the
macOS lane; the CUDA lane still does. The two accelerator lanes run on separate
machines; neither gates on the other.

**The CUDA lane is required**, like every other test lane: it is in
`analysis.needs`, `all-checks`, `auto-resolve.needs`, the
`Check for incomplete test execution` step, and the Surefire allowlist, and
`tools/tests/test_auto_resolve_test_job_coverage.py` pins it there. It was
informational until it had a passing baseline.

`ar-ci-cuda` is a **capability** label, distinct from `ar-ci` and `ar-ci-cl`: the
CUDA jobs require it, and only the CUDA fleet carries it, after its preflight has
proved the GPU. A Linux host without an NVIDIA GPU therefore never receives a CUDA
job. Unlike the ROCm fleet, a CUDA fleet may *additionally* carry `ar-ci`
(`RUNNER_EXTRA_LABELS` in `tools/ci/cuda/.env`), so one GPU host can serve the CPU
lane as well instead of running a separate CPU fleet beside it. That is safe
because `test` and `test-media` run every step with `AR_HARDWARE_DRIVER=native` on
the same image (the CUDA bridge tests in `base/hardware` still use the GPU
directly when one is present, so on such a host they run in `test` too, and the
fleet's preflight guarantees a complete CUDA environment for them), and because
the CUDA lane is admitted only after the CPU lane
finishes, so within one pipeline the two never compete for those runners. The
`ar-ci-cl` caveat above still applies: across concurrent pipelines, CUDA jobs can
queue behind CPU jobs on a shared host.

### What the `docker-build` job covers

Builds the controller-stack images (`ar-manager`, `ar-memory`, `ar-tracker`) so
a packaging break surfaces on the PR instead of at deploy time. Build only —
nothing is pushed or started. Path-gated on `images_changed` **and nothing
else** (see the flag-contract exception above), depends only on `changes` (no
Maven build). Uploads no coverage, so it does **not** appear in
`analysis`'s `needs`; it **is** part of `all-checks` (skipped → treated as
passing), like `agent-volume-isolation`.

The `images_changed` flag covers everything that goes *into* an image: the
Dockerfiles, `.dockerignore`, the controller compose file, `tools/mcp/`,
`tools/tracker/`, and — because ar-manager bakes the documentation corpus into
its image — `docs/` and `CLAUDE.md`. A docs-only change genuinely produces a
different image, so it is not treated as a docs-only skip here.

The job asserts more than "the build exits 0": it counts the markdown/HTML
files inside the built ar-manager image and fails if the corpus is missing or
truncated, and fails if any `.java` file survived the pruning stage.

**It does not build `flowtree-controller` or the agent image.** Those need the
Maven artifacts (`flowtree/runtime/target`) first, which would put a full
reactor build on this job. The gap is real and has already cost one failed
deploy: a root `.dockerignore` rule added for ar-manager excluded
`**/target/`, which both of those images COPY from, and nothing caught it until
the deploy ran. `tools/tests/test_dockerignore_consistency.py` now covers that
specific class of break statically — it cross-references every Dockerfile's
COPY sources against the ignore rules with no daemon required. A break that
static analysis cannot see (a bad base image, a missing file) still surfaces
only at deploy. This
matters because `_get_docs()` in `server.py` degrades **silently** when the
corpus is absent — a broken image would start cleanly and simply answer without
documentation grounding, which no startup check would catch.

### What the `Deploy Controller Stack` workflow does

Lives in its own file (`.github/workflows/deploy.yaml`) and is triggered
**only by `workflow_dispatch`** — a deploy happens only when someone starts it
by hand. It used to also run automatically, via a `workflow_run` trigger, on
every **successful master** run of "Build and Test"; that trigger was removed
after 2026-10-19, when one such automatic deploy rebuilt and restarted the
controller stack while agent jobs were in flight and dropped their ar-manager
connections. A deploy restarts the platform, so it must be a deliberate,
supervised action, not a side effect of a merge to master.

**It is a separate workflow (never a job in `analysis.yaml`) for the same
reason `auto-resolve-submit.yaml` is:** a job declaring `environment:` inside a
`pull_request` run attaches a deployment status to the PR head, and a pending
deployment later cancelled shows as a spurious "had a problem deploying" red X.
Never move a deploy job into `analysis.yaml`, and never give a
`pull_request`-reachable job an `environment:`.

It runs on `[self-hosted, macos, ar-deploy]` — a **native** macOS runner on the
host that owns the stack, with Docker available to the runner user. The label is
deliberately distinct from `ar-ci` (same reasoning as `ar-ci-cl`): a deploy must
not queue behind the macOS test lane, and the test lane must not pick up
deploys.

Restarting `ar-manager` drops the MCP connection of every in-flight coding-agent
job, so the workflow drains first: it closes job intake via
`POST /api/config/accept-automated-jobs {"accept": false}`, waits with
`tools/ci/drain-agent-jobs.sh` until the controller's `GET /api/jobs/in-flight`
reports a `total` of zero (`STARTED` plus `SUBMITTED` jobs across every
workstream; `tools/tests/test_drain_agent_jobs.py` pins the route), and
**fails rather than forcing** when the wait expires (`skip_drain: true` on a
manual run overrides this deliberately). Intake is reopened in an `always()`
step so a failed deploy cannot leave the controller permanently quiesced.

`concurrency` does **not** cancel in progress: interrupting a half-finished
container rebuild is worse than queueing behind it.

**The agent pool is part of the deploy.** `flowtree-agent-1` and
`flowtree-agent-2` run their own image, which bundles the flowtree JARs — a
change under `flowtree/agents` (the tool policy a coding-agent session runs
under, for instance) reaches a running agent only when that image is rebuilt.
The workflow therefore invokes `rebuild.sh --agents`, and then asserts the
containers were actually recreated by comparing their Docker ids across the
rebuild; a rebuild that "succeeded" while the old containers kept running fails
the job. Answer `false` to the `redeploy_agents` input when dispatching to
deploy the controller stack alone.

**Each deploy prunes what it superseded.** Because every build is
`--no-cache --pull`, a deploy leaves the previous images untagged and writes a
fresh set of BuildKit cache records; on the Docker Desktop host these share one
fixed-size VM disk with the agents' writable layers, and unpruned they filled
it until agents failed with "No space left on device". After the restart
succeeds, `rebuild.sh` removes dangling images and trims the build cache to
`BUILD_CACHE_MAX_USED_SPACE` (default `20gb`). `docker image prune` alone frees
almost nothing — the superseded layers stay referenced by the build cache —
so never drop the `builder prune`. The prune lives in `rebuild.sh`, not in this
workflow, so hand-run rebuilds get it too.

The stack includes `fleet-db` and `fleet-grafana` (runner-fleet monitoring,
`tools/fleet/README.md`). `rebuild.sh` generates their password files beside
the shared secret and binds their ports to the host's tailnet address, which it
detects (`FLEET_BIND_ADDR` overrides) and persists to the compose project's
gitignored `.env` (`flowtree/runtime/controller/.env`); the compose file
refuses to interpolate without an address, so a deploy runner that cannot
determine one fails there rather than publishing a database on every
interface. The `.env` matters beyond the rebuild: compose interpolates the
whole file on every invocation, so the workflow's post-deploy
`docker compose exec` check (and any `docker compose logs` run by hand) only
works because the address is on disk, not merely exported inside
`rebuild.sh`. A full rebuild recreates
`fleet-db` too — the data directory persists, and the collectors on every
host reconnect on their own — so nothing in the deploy needs to drain for it.

The agent `.env` is gitignored, so it is never present in the runner's checkout.
`rebuild.sh` reads `FLOWTREE_AGENT_ENV` to find the host's copy; the workflow
falls back to `/Users/Shared/flowtree/secrets/agent.env`, and the repository
variable `FLOWTREE_AGENT_ENV` overrides that. Without it the script fails with
the missing key rather than blocking on a prompt that has no terminal to answer
it.

**The native macOS agent is the second job, on a third runner.** The Docker
pool has no GPU, so `deploy-macos-agent` keeps a native agent — a JVM under
launchd on a Mac, with Metal — on the same JARs, via
`flowtree/runtime/agent/macos/install.sh`. It `needs: deploy` (two drains
racing would let one job reopen intake while the other is still rebuilding),
is gated on the same `DEPLOY_AGENTS` decision, and shares the `production`
environment (one approval covers both jobs). It asks for
`[self-hosted, macos, ar-deploy-agent]` — **not** `ar-deploy` — because the
agent runs as the `worker` account and the job writes that account's install
directory and restarts that account's process; only the owner can do either.
A runner registered as the Docker owner would install into the Docker owner's
home, so the job checks `id -un` against the expected account (`worker` by
default, overridable with the repository variable
`FLOWTREE_MACOS_AGENT_ACCOUNT`) and fails before `install.sh` runs if they do
not match. Never add `ar-deploy-agent` to the `ar-deploy` runner; register a
separate runner as `worker` (see `tools/ci/macos/README.md`, "Deploying the
native macOS agent").

The agent is a **LaunchDaemon in the system domain** (`UserName: worker`),
registered once by an administrator, and the job needs no launchd privilege:
`install.sh` swaps the JARs, signals the running JVM, and `KeepAlive` starts it
again on the new classpath. It is not a LaunchAgent in worker's own domain
because that domain is absent after a reboot (nobody logs in as `worker`) and,
on a host where `worker` is only reached through `su`, refuses every
bootstrap with `Bootstrap failed: 5: Input/output error` — from worker, from
root, and from root via `launchctl asuser`. The first version of the job
bootstrapped a LaunchAgent from inside the CI job and failed on exactly that.
`install.sh` re-renders the daemon plist on every run and fails, printing the
`sudo register-daemon.sh` command (run from a checkout the administrator owns; it
validates that the worker-written plist runs the service as worker and nothing
else, and waits for a replaced service to stop
before loading the new definition), when the daemon is not registered or the registered copy
differs from the rendered one. The job also fails unless the new agent process
— identified by process identity, pid plus start time, so it is a different
process from the one signalled even if it received the same pid number — holds a connection to
the controller port; there is no controller endpoint listing connected agents,
so that check is made from the agent's side with `lsof`.

### What the `Master Agent Dispatch` workflow does

Lives in `.github/workflows/master-agent-dispatch.yaml` and holds the agent jobs
that fire on a merge to master: `plan-next-task` (Project Manager),
`plan-release-task` (Task Planning), `doc-qa`
(Quality Assurance), `defect-hunt`, `flaky-qa`, `coverage-qa`, `consolidation-qa`,
`performance-qa`, `pdsl-qa` and `qa-retrospective`. The `plan-next-task`, `doc-qa` and `defect-hunt`
jobs were separate workflows with byte-identical triggers; merging them keeps the
Actions sidebar navigable without changing what any of them does, and each job
added since — `plan-release-task` among them — lands here for the same reason.

Every job first asks the controller whether it accepts automated jobs
(`tools/ci/automated-intake.sh`, `GET /api/config/accept-automated-jobs`), and
runs its cadence gate only when the answer is yes. A round creates its branch
and workstream before it submits its agent job, so with intake closed — by
hand, or by the deploy workflow while it drains — the refused submission used
to leave an orphaned branch and workstream behind, and the orphaned branch
then held off the next round for the 24-hour grace window. The check fails
closed, and `force` does not lift it: reopen intake instead. No step before
the gate may push a branch, register or archive a workstream, or submit a job;
`tools/tests/test_automated_intake.py` checks that, and that every job's
summary reports the intake answer so a closed intake does not read as a round
in progress. The check is a snapshot, not a reservation: intake closed in the
seconds between it and the round's submission still orphans that round. Closing
the window needs a controller-side reservation that the round takes before it
creates its branch; until one exists, this check removes the common case (a
round that starts while intake is already closed).

The eight QA-style jobs (`doc-qa`, `defect-hunt`, `flaky-qa`, `coverage-qa`,
`consolidation-qa`, `performance-qa`, `pdsl-qa`, `qa-retrospective`) share a shape:
`tools/ci/qa-cadence.sh` decides whether to run from the job's own
`BRANCH_PREFIX` (`qa/docs-`, `qa/defect-`, `qa/flaky-`, `qa/coverage-`, `qa/consolidate-`,
`qa/performance-`, `qa/pdsl-`, `qa/retro-`), `tools/ci/archive-stale-workstreams.sh` retires
the previous rounds, then a branch is created, a workstream registered, a prompt
built from `tools/ci/prompts/`, and a coding-agent job submitted with
`AUTO_CREATE_PR`. A new QA job follows that sequence; it does not need new
cadence logic.

Each QA job's `MIN_INTERVAL_DAYS` is 2 (`defect-hunt`, `coverage-qa`,
`consolidation-qa`, `performance-qa`), 5 (`doc-qa`, `pdsl-qa`) or 7
(`flaky-qa`, `qa-retrospective`). Every one of
them also has its own boolean `workflow_dispatch` input,
`ignore_interval_<job>` (`docs`, `defect_hunt`, `flaky`, `coverage`, `consolidation`,
`performance`, `pdsl`, `retrospective`), passed to `qa-cadence.sh` as `IGNORE_INTERVAL`. It lifts
only the interval: a round still in progress holds it off either way — a PR from
the job's previous round is still open, or (via `PR_GRACE_HOURS`, set to 24 on
every QA gate) its branch is younger than that window and has not opened one yet.
Those two checks run before the interval, so they also stop the `Archive previous
rounds` step from retiring a round whose agent is still working. `force` lifts
all three — so, as on the planning jobs, each QA `Archive previous rounds` step
also carries `reason != 'forced'`: a forced dispatch skips the in-progress
checks, so it archives nothing rather than retiring a live round beside the one
it starts. A new QA job gets its own `ignore_interval_*` input, sets
`PR_GRACE_HOURS`, and guards its archive step against the forced reason;
`tools/tests/test_master_agent_dispatch.py` checks all three.

**One open-PR limit for every round.** Each round, QA or planning, ends in a
pull request for the same reviewer, so none starts while more than
`MAX_OPEN_PRS` PRs of any kind are open against master. That limit is defined
once, as the workflow-level `env` of `master-agent-dispatch.yaml` (7: a round
may start with seven open, so with its own PR the repository holds at most
eight). The planning jobs check it with `tools/ci/open-pr-backlog.sh`; the QA
gates turn on `qa-cadence.sh`'s backlog condition with `CHECK_BACKLOG: "true"`,
which runs the same script after the round and interval checks and reports
`backlog-full` (or `backlog-query-failed`, read as full). `plan-release-task`
passes the same number as its `max-open-prs` input, since a called workflow
cannot read the caller's `env`. No job sets a limit of its own;
`tools/tests/test_master_agent_dispatch.py` checks that. The count covers open
PRs only: a round whose agent has not opened its PR yet is not in it.

`MAX_OPEN_PRS` is sized to the **full-cadence** QA rounds — one open PR each,
seven of them (`doc-qa`, `defect-hunt`, `coverage-qa`, `consolidation-qa`,
`performance-qa`, `pdsl-qa`, `qa-retrospective`) — so the value tracks that
count and rose from 6 to 7 when `qa-retrospective` was added. `flaky-qa` is a
**pilot** (RETRO-20261022 F6): it still participates in the backlog gate like
every round (it will not start while the backlog is full, and its own PR counts
toward the next round's check), but adding it deliberately did **not** raise
`MAX_OPEN_PRS` — the eight QA jobs share the budget of seven until the pilot
proves its yield and a person decides to promote it. Promoting the pilot to a
full round, or adding an eighth full-cadence round, is what raises the limit;
`test_the_flaky_pilot_does_not_raise_the_open_pr_limit` in
`tools/tests/test_master_agent_dispatch.py` pins this so the decision is made on
purpose rather than by surprise.

`plan-next-task` keeps **exactly one** planning round open, because every round
rewrites the single `docs/plans/MANAGER_LOG.md` and two open at once cannot both
merge. It runs `qa-cadence.sh` from a step-level env (so the QA-job checks do
not claim it) with `BRANCH_PREFIX=project/plan-`, `MIN_INTERVAL_DAYS=0` and
`PR_GRACE_HOURS=24`: an open `project/plan-*` PR, or a `project/plan-*` branch
younger than 24 hours that has never had a PR, means a round is in progress.
The grace window is load-bearing: the agent opens its PR only when it finishes,
so without it a merge landing mid-round started a second round beside the first
(this is how `project/plan-20260926-172935` and `project/plan-20260926-174202`
came to coexist). Only when no round is in progress does the job check the
backlog against the shared `MAX_OPEN_PRS`. `force` bypasses both checks.

Like the QA rounds, both planning jobs retire earlier rounds with
`tools/ci/archive-stale-workstreams.sh` (their own prefix only) once the gate
has decided a round may start. For `plan-next-task` that is exactly when every
earlier round has been merged, closed or abandoned. `plan-release-task` allows
several rounds at once, so its gate lists the rounds it counted as in progress
(`live`) and the archive step keeps them (`KEEP_BRANCHES`). A forced run
archives nothing, since it skipped the gate's checks and an approved plan may
still be being implemented on its workstream.

`plan-release-task` plans the next ready tracker task of the release master is
building (`Common <root pom.xml version>`), beside the free-form round rather
than instead of it. It has no steps of its own: it calls the reusable
`task-planning.yaml` (see below), the same workflow other repositories call for
their own tracker projects, so what is described here is that workflow's job. It is gated three ways, in order: fewer
than four `project/task-*` rounds in progress (`qa-cadence.sh`, same settings as
above plus `MAX_ROUNDS`, the workflow's `max-rounds` input; a round lasts from
its plan until its PR is merged or closed, so an approved plan being
implemented holds a slot), room in the backlog (`tools/ci/open-pr-backlog.sh`, which `plan-next-task` uses
too, with the same `MAX_OPEN_PRS`), and a claimable task
(`tools/ci/tracker-claimable.sh`, which asks the controller's
`GET /api/tracker/claimable` and fails closed). With nothing claimable no
branch, workstream or agent job is created. `force` skips the first two gates,
never the third. The round's workstream is registered with no tracker role;
CI then claims the task for it (`tools/ci/tracker-claim.sh`, the controller's
`POST /api/tracker/claim`, which links the task to the workstream and fails
closed). That link is what lets the agent update its own task; anything else
it finds it files with `tracker_create_task`, which lands in the backlog. The
claimed task's text is written to a file under `$RUNNER_TEMP` (never to
`$GITHUB_OUTPUT`) and rendered into the prompt, so the agent is told its task
rather than claiming one. When another round took the last task between the
claimable check and the claim, nothing is submitted and the new branch is
deleted again, since an unused `project/task-*` branch would read as a round in
progress for `PR_GRACE_HOURS`. The agent plans only its task and never writes
`docs/plans/MANAGER_LOG.md`.

Every job runs on `ubuntu-latest`. None of them builds the Java reactor or runs
its tests. The one job that runs any tests is `coverage-qa`: it runs the Python
suites under coverage.py (seconds) and reuses master's `merged-coverage-report`
artifact for Java. Its Maven recompute path (`fetch-latest-coverage.sh`) is
switched off with `ALLOW_RECOMPUTE=false`, so a missing artifact fails the step
and names the fix (a fresh master "Build and Test" run) instead of running the
full suite on a hosted runner. Otherwise the jobs
read git and the GitHub API, push a branch, and call the controller through its
Cloudflare Access tunnel (`vars.FLOWTREE_CONTROLLER_URL` plus the
`FLOWTREE_CF_ACCESS_CLIENT_ID` / `FLOWTREE_CF_ACCESS_CLIENT_SECRET` service
token), the same way `auto-resolve-submit.yaml` does. They formerly ran on the
self-hosted macOS `ar-ci` fleet and addressed the controller by LAN hostname,
which bought nothing and competed with the test lanes for runners. Do not add a
`CONTROLLER_HOST` fallback or a self-hosted label back;
`tools/tests/test_master_agent_dispatch.py` pins both.

`performance-qa` is the one round whose result depends on the machine the
*agent* runs on, not just the runner that submits it. Its measurements are only
meaningful with Metal available, so its workstream is registered with
`REQUIRED_LABELS_JSON='{"platform": "macos"}'` and its submission carries the
same `REQUIRED_LABELS` (both matched against the Node's auto-detected `platform`
label). The workstream's labels are the ones that matter for every job after the
first — a PR follow-up or a fix for a failing check is submitted with no labels
of its own, and without them it once landed on a Node with no Metal. The
submission also pins the primary phase to `claude/opus` at `effort: max`. The
`runs-on` label of the workflow job says nothing about where the agent executes;
the labels do. Keep all three in place when copying this job.

`pdsl-qa` is the round that moves compute-pipeline structure out of Java and
into `.pdsl` assets (`tools/ci/prompts/pdsl-migration.txt`). It carries the same
`claude/opus` at `effort: max` pin as `performance-qa`, because its central
judgment — whether a candidate is a basic building block that Java should keep
defining or a composition that belongs in the asset — is exactly the one a
model under-thinks, and the under-thought answer (register a primitive that
wraps the Java class, write a one-line asset that calls it) passes every
mechanical check while migrating nothing. It carries no `REQUIRED_LABELS`:
parity between the Java assembly and the asset is a correctness property, not a
measurement, and the merged tests run on every backend the pipeline covers.

`qa-retrospective` is the round that looks back at the others: it surveys the
repository's recent automated work and proposes, with evidence, how the agent
harness and the QA jobs should improve — it only proposes. Its output is
docs-only (a report under `docs/qa/retrospectives/` and a row in that
directory's ledger), so `PROTECT_TEST_FILES` costs nothing. That scope is the
prompt's contract, not a harness restriction — no submission setting confines a
job to a path; the CI-file lock and the test lock bound the worst edits, and
the rest is caught in review of the PR `AUTO_CREATE_PR` opens. What *is* a
harness setting is `ENFORCE_CHANGES: "true"` with `MAX_ENFORCE_CHANGES_RETRIES: "0"`.
The round's contract is to leave a report on disk. `enforceChanges` re-runs a job
that left no change, up to its re-run cap, and once the cap is spent with
nothing committed `CodingAgentJobEvent.statusFor` reports `DEGRADED` rather than
SUCCESS. A run that surveyed, recorded memories and committed nothing is
therefore visible instead of reading as a green run. The cap is 0 because a
re-run is a fresh `opus`/`max` session that re-surveys from scratch, and with
the report written first an empty tree means something a re-run of the same
prompt would not fix. The prompt's other half of the same fix (RETRO-20261019 F1) has the agent write
the skeleton report and ledger row *first*, before the expensive survey, so a
run that stalls still leaves a reviewable artifact. It pins the primary
phase to `claude/opus` at `effort: max` for the same reason as `performance-qa`
and `pdsl-qa` — the judgment is synthesis across many workstreams, jobs and
PRs, the kind a model under-thinks — and carries no `REQUIRED_LABELS`, since it
reads tools and writes markdown and needs no Metal. Its survey window
(`REVIEW_SINCE`, 14 days) is wider than its 7-day cadence so most activity is
looked at twice across runs, which is how it tells whether an earlier proposal
was acted on. `ci/qa-…` is the branch convention for a pipeline-subject QA
follow-up a person opens from an accepted proposal; it is one of the prefixes
the survey covers (`QA_PREFIXES`), and a person — never a job — creates it.

`flaky-qa` is the pilot nondeterministic-test hunt
(`tools/ci/prompts/flaky-hunt.txt`, RETRO-20261022 F6). It takes one module and
a small, named slice of its tests, runs that slice repeatedly on the current
commit, and looks for a test whose pass/fail outcome varies with no change to
the code under test; on a finding it fixes the source of the nondeterminism in
production or test-helper code, or — when the cause is out of scope — files a
follow-up with `tracker_create_task`, the same routing the general review uses.
Three things set it apart. Its `MIN_INTERVAL_DAYS` is 7, tied with `qa-retrospective`
for the longest of any round, because it is a pilot rather than a full cadence job — and for the same
reason it does **not** raise `MAX_OPEN_PRS` (see "One open-PR limit" above). Its
prompt keeps it inside the repository's test-execution limits: named, bounded
selections through the MCP test runner only, each with an explicit timeout,
repeating a small selection rather than widening it, never a module or full
suite or `AR_TEST_GROUP`/`AR_TEST_GROUPS`. And `PROTECT_TEST_FILES` stays
`"true"`: the test that reveals a flake is the specification, so the round fixes
the production/helper cause or files a follow-up rather than weakening the test
— it carries no `ENFORCE_CHANGES`, since an honestly empty-handed round is a
valid outcome, and pins the primary phase to `claude/opus` like the defect hunt,
telling a real flake from a deterministic failure being judgment, not mechanism.

Each job carries its **own** `concurrency` group (`project-manager`,
`task-planning`, `quality-assurance`, `defect-hunt`, `flaky-qa`, `coverage-qa`, `consolidation-qa`,
`performance-qa`, `pdsl-qa`, `qa-retrospective`, none cancelling in progress), so they serialize
independently rather than queueing behind one another. Workflow-level
concurrency would couple them — do not add one.

A `workflow_dispatch` selects a single job via the `agent` input
(`all` | `project-manager` | `quality-assurance` | `defect-hunt` | `flaky` | `coverage` |
`consolidation` | `performance` | `pdsl` | `retrospective`); `force` is passed through to
whichever job runs, and each `ignore_interval_*` input reaches only its own job. Each job's
`if` is written as `github.event_name != 'workflow_dispatch' || ...` so a push
to master runs all of them. Adding a job means adding its selector to that
`options` list as well — a job whose selector is missing can never be dispatched
alone.

`tools/mcp/manager/project_tools.py` dispatches this workflow by filename with
`agent: project-manager` (the `project_create_branch` MCP tool). Renaming the
file or the input breaks that tool — update it in the same change.

The policy this reflects: **one workflow per lifecycle event**, with two
deliberate exemptions. (A reusable workflow, such as `goal-decomposition.yaml` or
`task-planning.yaml`, has no lifecycle event of its own — it runs when another workflow calls it — so the
policy does not apply to it.) `workflow_dispatch`-only workflows stay separate because
dispatch inputs are per-workflow and merging them produces one form of mostly
inert fields (`Deploy Controller Stack` is triggered by hand often enough to
earn its own entry on those grounds alone). Privileged triggers stay separate
too — see `Cancel Merged PR Runs` below.

### What the `Goal Decomposition` reusable workflow does

Lives in `.github/workflows/goal-decomposition.yaml` and is triggered only by
`workflow_call`: other repositories call it from their own workflows, so a
repository that builds on this one gets the decomposition round of goal-driven
release automation without a copy of the job-submission scripts. The caller
owns the trigger (a push to its default branch that changes its goals, release
plans or version file, plus a manual dispatch); the job itself refuses to run on
any branch but the default one, because the tasks it creates are ready to plan
at once.

It resolves the caller's next release, the framework release it pins, and its
release plan (`<plans dir>/<version>/PLAN.md`) with
`tools/ci/resolve-release-scope.sh`; with no release plan it stops before asking
the controller anything. Otherwise it follows the dispatch jobs' order — intake
first, then nothing is created until intake is open — moves the standing branch
`orchestration/goal-decomposition` to the commit being reconciled, registers that
workstream with the tracker `planner` role and this repository as a dependent
repository, and submits the agent job. The agent changes no files; it creates
and updates tracker tasks with the general tracker tools and reports them on
the workstream. The planner role lets it create `ready` tasks and releases and
edit any task in the workspace; the prompt is what confines its updates to
tasks whose `source` carries its own `goals:<owner>/<repo>/` prefix and keeps
it off the stage and status of existing tasks.

**Trust.** A plain `actions/checkout` inside a called workflow fetches the
*caller's* repository. Every script therefore runs from an explicit checkout of
this repository at the `framework-ref` input (`persist-credentials: false`),
never from the caller's tree, and no input is interpolated into a `run:` script;
inputs reach scripts through `env`. The controller client ID and URL are inputs
(the caller passes its own `vars`); the client secret is a declared
`workflow_call` secret. Like every other submission it sends no model, runner or
effort settings.

**Keep this file generic.** This repository is public. Product-specific values
reach the workflow as inputs; its defaults describe this repository's own layout.
`tools/tests/test_goal_decomposition.py` pins the trigger, the default-branch
guard, the script trust rule, the intake ordering, the planner role, the
prompt's tool use and its variables.

### The other reusable workflows: `Task Planning`, `Implement Plan`, `Plan Review`

Together with `Goal Decomposition` these give another repository the whole
plan-then-implement pipeline for its own tracker project. Each is triggered only
by `workflow_call`, follows the same trust rule (every script and prompt
template from an explicit checkout of this repository at `framework-ref`,
`persist-credentials: false`; the controller URL and client ID as inputs, the
client secret as a declared secret), and describes its caller in its header.

- **`task-planning.yaml`** is the task-planning round described under `Master
  Agent Dispatch` above, parameterised by tracker project and version file (a
  `pom.xml` is read as its project version, anything else as a plain version
  string, by `resolve-release-scope.sh` with the caller's project as its own
  framework). `plan-release-task` calls it with `project: Common`, so this
  repository runs exactly what its consumers run.
- **`implement-plan.yaml`** is Verify Completion's work: detect the plan,
  resolve the plan's workstream settings in a token-free job, register the
  workstream, build the prompt in a token-free job and submit it from a job
  that runs only `submit-staged-request.sh`. Every one of its jobs checks out
  this repository, never the dispatched branch's scripts; the branch is read
  only as data (its changed file names, and a sparse checkout of its plans
  directory). `verify-completion.yaml` is this repository's caller and keeps
  its name and `plan_file` input for the `project_verify_branch` MCP tool.
  Because the prompt templates now come from `framework-ref`, a branch that
  edits `verify-completion.txt` is implemented with master's copy of it.
  Its `register-workstream` job asks the controller's in-flight view
  (`tools/ci/workstream-idle.sh`, `GET /api/workstreams/{id}/jobs/active` and
  `/pending`) whether the branch's workstream already has a job pending or
  running, and `build-prompt` gates on the answer, so no second implementation
  is dispatched beside one in flight — the guard that holds across the
  ready-for-review trigger, a manual dispatch and `project_verify_branch`, and
  across separate runs that the per-branch concurrency group does not cover. It
  fails open: an unreachable controller is read as idle.
- **`plan-review.yaml`** sends the docs-only review (`docs-review.txt`) to a
  branch whose every change is under `docs/`, for a repository that does not run
  this pipeline. This repository does not call it: here the same review is the
  docs-only route of `auto-review`, which chooses it ahead of the reviews that
  depend on the build and the quality gates. Its branch is read in a token-free
  `detect` job; the `review` job that holds the token checks out only this
  repository.

`tools/tests/test_reusable_workflows.py` pins the trust rule for all four, and
runs the release, plan-detection and docs-only steps for real. `test_master_agent_dispatch.py`,
`test_automated_intake.py` and `test_verify_completion_workflow.py` follow a
caller job into the workflow it calls, so their checks still apply to the steps
that run.

### What the `Plan Ready` workflow does

Lives in `.github/workflows/ready-plan.yaml`. It starts implementation when the
owner approves a plan by marking its pull request **ready for review**.

**The draft convention.** A plan PR is opened as a **draft** (the planning jobs
set `PR_DRAFT`/`createPrAsDraft`; see "Draft plan pull requests" below). A draft
means the branch carries only a plan awaiting approval — almost no CI ran on a
docs-only branch, so a green check on it says nothing about work that does not
exist yet. Marking the PR **ready for review** is the owner's approval, and this
workflow turns that transition into the same `verify-completion.yaml` dispatch a
manual approval makes. For a PR that is real work rather than a plan, "ready"
keeps its ordinary meaning (work under review); such a PR is not a draft and not
docs-only, so this workflow does nothing with it.

**It must stay in its own file, for the same reason `Cancel Merged PR Runs`
does.** It is triggered by `pull_request_target` (`types: [ready_for_review]`,
base `master`), which grants a writable token in the base-repository context, so
GitHub runs *master's* copy of the file — the PR's branch cannot change what it
does. It is safe only because it checks out nothing and runs nothing from the
PR: every value it uses is event metadata or comes from the GitHub API. Never
add a checkout of PR code to it, and never move it into a file beside jobs that
build PR code.

It dispatches `verify-completion.yaml` on the PR's head branch through the API
(`workflow_dispatch`, which `GITHUB_TOKEN` is allowed to trigger, needing
`actions: write`), so the implementation path is exactly the one a manual
dispatch or `project_verify_branch` takes. It dispatches only when all of:

- the PR's head is a branch in **this** repository, not a fork
  (`github.event.pull_request.head.repo.full_name == github.repository`); a fork
  cannot be given a `workflow_dispatch` here and must not be able to start one;
- the person who marked it ready (`github.event.sender.login`) is in the
  `PLAN_APPROVERS` repository variable (see below). With the variable unset
  nothing is dispatched, so an agent or bot can never approve a plan by marking
  it ready;
- the PR is **still docs-only** by the same rule `analysis.yaml` uses (every
  changed file under `docs/`, read from the PR files API). Once implementation
  commits land the PR is no longer docs-only, so re-toggling draft afterwards
  dispatches nothing — this is the main guard against a second implementation,
  alongside `implement-plan.yaml`'s own in-flight-job refusal.

`analysis.yaml` does **not** re-run on the `ready_for_review` transition: its
`pull_request` trigger uses the default types (`opened`, `synchronize`,
`reopened`), which do not include `ready_for_review`. Keep it that way — the
plan's pipeline already ran on the push that opened it.

### Draft plan pull requests

A plan PR is opened as a draft so its status reads honestly: draft = a plan
awaiting the owner's approval, ready-for-review = approved for implementation (or,
for a non-plan PR, real work under review). The flag travels as
`createPrAsDraft` from the submitter to the controller, which sends
`"draft": true` to GitHub when it opens the auto-created PR
(`GitHubProxyHandler.pullRequestPayload`); `autoCreatePr` must also be set or
there is no PR to draft. The submitters expose it as `PR_DRAFT`
(`tools/ci/submit-agent-job.sh`) and `create_pr_as_draft`
(`workstream_submit_task`). The plan-writing jobs set it: `task-planning.yaml`,
`master-agent-dispatch.yaml`'s `plan-next-task`, and the job
`workstream_register`'s `plan_instructions` submits. The docs-QA and other
docs-only QA rounds deliberately do **not** — their PRs are documentation meant
to be merged as they stand, and marking one ready must not dispatch an
implementation of a branch with nothing to implement.
`tools/tests/test_master_agent_dispatch.py` pins which jobs draft and which do
not.

### The `PLAN_APPROVERS` repository variable

A space-, comma- or newline-separated list of GitHub logins allowed to approve a
plan by marking its draft PR ready for review. `Plan Ready` reads it and
dispatches `verify-completion.yaml` only for a sender in the list. It is a
repository **variable**, not a workflow value, so the allowlist changes without a
commit and is never present on a fork. **Unset means no one can approve this
way** — the transition then dispatches nothing, which is the intended
fail-closed default, not a misconfiguration to "fix" by dropping the check.

### What the `Cancel Merged PR Runs` workflow does

Lives in `.github/workflows/cancel-merged-pr-runs.yaml`. When a PR is merged it
cancels that PR's still-running "Build and Test" runs, via
`tools/ci/cancel-merged-pr-runs.sh`. Merging is already a decision that the
checks passed, and the merge commit's own master pipeline re-runs them; the
pre-merge pipeline is holding runners for a settled question.

**It must stay in its own file.** It is triggered by `pull_request_target`,
which grants a writable token in the base-repository context. That is safe here
only because the file contains nothing that checks out or executes PR code —
`actions/checkout` with no `ref` takes the base branch, and the job's only
action is an API call driven by event metadata. Putting it in a file alongside
jobs that build PR code is how `pull_request_target` becomes a token-exfiltration
vector. Never add a `ref:` to that checkout, and never move this job into
`analysis.yaml`.

Scope is `event=pull_request` runs of `analysis.yaml` matching the PR's head
branch **and** head repository. The event filter is load-bearing: under a
fast-forward or rebase merge, master's new head can be the same SHA as the PR
head, so a SHA-based filter would cancel the master pipeline. The
head-repository match keeps a fork's branch from matching a same-named branch
here.

### What the `analysis` job does

Waits for `build`, `test`, `test-flowtree`, `test-media`, `test-mac`,
`test-media-mac`, `test-cl`, `test-media-cl`, `test-cuda` and `test-media-cuda`
(any may be skipped). The mac, CL and CUDA jobs upload no coverage; they are
in `needs` so that the input to `analysis` is not narrower than the input to
`all-checks` — `auto-resolve` depends on `analysis`, so on its attempt it does
not proceed until the same set of jobs that decide `all-checks` has reported. Downloads all
`coverage-*` artifacts, merges them with JaCoCo CLI, generates an XML report for
Qodana. The `mkdir -p all-coverage` guard ensures it tolerates missing artifacts
when test jobs are skipped.

---

## Test Execution Limits for Agent-Submitted Jobs

This section is about agents and job submitters, not about `analysis.yaml`'s own test
matrix (the `test`/`test-flowtree`/`test-media`/`test-mac`/`test-media-mac` jobs above
run their full assigned scope, unaffected). The rule is: **agents and job submitters
may never run the full test suite, a module's whole suite, or a CI shard.** A test
invocation must select tests explicitly and narrowly (Maven: `-Dtest=Class#method`, not
a bare `-Dtest=Class`, and not `test`/`verify`/`install`/`package`/`deploy` with no
selector at all; pytest: explicit node ids), and every test or build invocation needs a
timeout of at most 2400s (40 minutes). `AR_TEST_GROUP`/`AR_TEST_GROUPS` — this pipeline's
own shard-partitioning mechanism — is reserved for the CI workflow matrix and must never
appear in a command an agent or job submitter runs.

This exists because of a 2026-09-16 incident: an operator-submitted job's
`post_completion_command` ran a full `mvn install` followed by an entire `engine/utils`
CI shard, with a 3600s timeout and 2 retries. It burned over three hours on a runner, and
the retry sessions weakened pre-existing tests to force a pass. Written guidance alone
did not stop it, so the rule is enforced mechanically at job-submission time and at the
MCP test runner, with no operator bypass at either surface; the interactive-session Bash
hook below is a partial backstop, not yet a closed surface (see its own entry):

- `workstream_submit_task` (`tools/mcp/manager/execution_limits.py`) rejects a
  `post_completion_command`/`command` that runs a broad Maven phase or an unscoped
  pytest invocation, rejects a `post_completion_timeout_seconds` above 2400, and lints
  the `prompt` text itself for English instructions to run something broad.
- The controller's `/api/submit` endpoint applies the same command validation
  (`io.flowtree.jobs.PostCompletionCommandValidator`) and clamps the timeout to 2400s,
  so a direct API call cannot bypass what `workstream_submit_task` already rejects.
- The `mcp__ar-test-runner__start_test_run` tool rejects `test_group`/`test_groups`
  outright and caps `timeout_minutes` at 40, with no bypass.
- Two `PreToolUse` Bash hooks restrict what a running agent session can do directly.
  `.claude/hooks/block-mvn-test-direct.sh` blocks `mvn test`/`integration-test`,
  `verify`/`install`/`package`/`deploy` without `-DskipTests`, and any assignment of
  `AR_TEST_GROUP`/`AR_TEST_GROUPS`. `.claude/hooks/block-pytest-broad-run.sh` blocks a
  pytest run with no path, with a directory, or with more than 5 test files or 40 node
  ids. They parse command text, so they are backstops rather than a closed surface.
- `tools/ci/prompts/**`, the auto-resolve prompt builders, must never instruct an agent
  to run something broad — see [`tools/ci/README.md`](../tools/ci/README.md#test-execution-limits).

---

## Rules for Modifying the CI

### Before making any change

1. **Read this file first.**
2. **Read the actual pom.xml files** for every module you are reasoning about.
   Do not state a dependency relationship without quoting pom.xml evidence.
   Use: `grep -o '<artifactId>ar-[^<]*</artifactId>' <module>/pom.xml`
3. **Check both directions.** "Does A depend on B?" and "Does B depend on A?"
   are different questions. Check both by grepping ALL pom.xml files.

### Adding a new test job

- Identify exactly which modules the job tests.
- Trace which layer flags those modules depend on (using the graph above).
- Gate the job on the correct `*_changed` flags.
- Add the job to `analysis`'s `needs` list.
- Add coverage upload following the `coverage-*` naming pattern.

### Adding a new module

- Determine which layer it belongs to based on its dependencies.
- If it introduces a new top-level directory, add a `*_changed` flag to the
  `changes` job and gate the appropriate test job on it.
- If it depends on the `flowtree` family, add its tests to `test-flowtree`
  (this job runs on every code change) — do NOT create a separately-gated job.
- If it depends on `tools`, add its tests to the appropriate tools-using
  job (`code-policy-check` or `test-timeout-check`).

### Changing layer flags

- The `set_all_flags_true` shortcut and the detection loop must list exactly
  the same set of layer names. Keep them in sync.
- Never add a flag that no job consumes — dead flags confuse future agents.
- Non-layer path-based flags (e.g., `python_changed`) follow the same
  contract: detection must run in the `pull_request` branch, `set_all_flags_true`
  must include the flag, and any job gated on the flag must AND it with
  `code_changed == 'true'` so docs-only PRs still skip everything.

  **One sanctioned exception: `docker-build` is gated on `images_changed`
  alone.** The `code_changed` conjunction exists so a docs-only PR skips the
  *test* pipeline, and every other flag-gated job is a test job. `docker-build`
  is not: it verifies a build artifact, and documentation is one of that
  artifact's inputs, because ar-manager bakes the corpus into its image. ANDing
  it with `code_changed` would skip the corpus check on precisely the change
  that alters the corpus — `code_changed` is false for a docs-only PR, since
  the detector excludes `docs/` and `*.md`. Do not "fix" this back to the
  general rule. Any future job in the same position (verifying an artifact
  whose inputs include documentation) belongs in this exception too.

---

## Common Mistakes to Avoid

**Mistake: Assuming nothing depends on a module after one grep.**
Always grep ALL pom.xml files for `ar-<module-name>` to find every consumer.

**Mistake: Confusing dependency direction.**
`flowtree/runtime` depends on `flowtree/api` (core is the consumer).
`flowtree/api` does NOT depend on `flowtree/runtime`.

**Mistake: Adding a layer flag for a standalone module.**
`flowtree/` and `tools/` are not layers. Their tests run in
specific jobs (`test-flowtree`, `code-policy-check`) that always execute on
code changes. Do not create spurious layer flags for them.

**Mistake: Adding test steps to the `build` job.**
`build` is the critical path — every downstream job waits for it. Keep it
limited to `mvn install -DskipTests`. Add new test coverage in a dedicated
job that gates on `build` (and whatever validation jobs make sense) so the
rest of the pipeline is not blocked.

**Mistake: Forgetting to add a new test job to `analysis` needs.**
Every job that uploads `coverage-*` artifacts must appear in `analysis`'s
`needs` list, or analysis will run before coverage is available.

**Mistake: Removing `test` from `analysis` needs.**
The `test` matrix generates the bulk of coverage data. `analysis` must
always wait for it even though it may be skipped on flowtree-only branches.
