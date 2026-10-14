# Staged artifacts for the QA retrospective job

This directory holds the parts of
[`PLAN-20261014-qa-retrospective-job.md`](../PLAN-20261014-qa-retrospective-job.md)
that could **not** land on the plan's own `project/task-…` branch, because they
change files under the CI-file lock (`.github/workflows/`, `tools/ci/`) or must
change in lockstep with a locked file. They are staged here, verbatim, so that a
follow-up branch named `ci/…` (or a controller-signed `Sensitive-File-Bypass`
run — see the plan's §7) can move them into place with no re-derivation.

Everything here has been validated: see "How this was validated" at the end.

## What already landed on the plan branch (not here)

These are not under the lock and are already committed, so the `ci/` branch does
**not** recreate them:

- `docs/qa/retrospectives/README.md` — what the directory is.
- `docs/qa/retrospectives/ledger.tsv` — the ledger header row.

## 1. New files — copy verbatim into place

| Staged file | Destination |
|---|---|
| `build-retrospective-prompt.sh` | `tools/ci/prompts/build-retrospective-prompt.sh` (keep it executable: `chmod +x`) |
| `retrospective.txt` | `tools/ci/prompts/retrospective.txt` |

Nothing in them needs editing. The builder follows the recurring-round pattern
(`build-doc-qa-prompt.sh`): it validates `BRANCH BASE_BRANCH REPO_URL
REVIEW_SINCE QA_PREFIXES`, reads `retrospective.txt`, and substitutes with an
inline `sed`. It carries no `pr-feedback.txt` fragment (a round dispatched from
master starts on a fresh branch with no PR).

## 2. `.github/workflows/master-agent-dispatch.yaml`

The fully assembled result is staged as `master-agent-dispatch.yaml` in this
directory. If master's copy is unchanged since this was written, it can be moved
straight into place. If master's copy has moved on, apply these five edits
instead (each is also reproduced as a standalone snippet here):

1. **New job** `qa-retrospective` — append the contents of
   `master-agent-dispatch.qa-retrospective-job.yaml` after the `pdsl-qa` job
   (end of the `jobs:` map).
2. **New dispatch input** — insert the contents of
   `master-agent-dispatch.ignore-interval-input.yaml` into
   `on.workflow_dispatch.inputs`, immediately before `plan_title:`.
3. **New selector option** — add `          - "retrospective"` to
   `on.workflow_dispatch.inputs.agent.options`, after `          - "pdsl"`.
4. **Backlog ceiling** — change both occurrences of `MAX_OPEN_PRS: "6"` (the two
   planning jobs) to `MAX_OPEN_PRS: "7"`. Seven QA rounds can each hold a PR
   open, and `MAX_OPEN_PRS` is "the number of automated QA rounds".
5. **Header comment** — in the opening comment, change
   `performance, PDSL migration — belongs` to
   `performance, PDSL migration, retrospective — belongs`.

Why the job's distinctive settings (all pinned by the tests in §4):
`BRANCH_PREFIX: "qa/retro-"`, `MIN_INTERVAL_DAYS: "7"`,
`REVIEW_SINCE: "14 days ago"`,
`QA_PREFIXES: "qa/docs- qa/defect- qa/coverage- qa/consolidate- qa/performance- qa/pdsl- qa/retro- ci/qa-"`,
the `claude/opus` at `effort: max` pin, `PROTECT_TEST_FILES: "true"`, no
`REQUIRED_LABELS`, and its own `qa-retrospective` concurrency group.

## 3. `tools/tests/test_master_agent_dispatch.py`

The fully assembled result is staged as `test_master_agent_dispatch.py`. Move it
into place, or apply these edits to master's copy:

1. In `test_qa_intervals_match_the_documented_cadence`, add `"qa-retrospective":
   7,` to the `expected` map (an addition, not a weakening).
2. In `test_the_planning_job_keeps_one_round_open_at_a_time`, change
   `self.assertEqual("6", job["env"]["MAX_OPEN_PRS"])` to `"7"`.
3. In `test_both_planning_rounds_share_one_backlog_limit`, change
   `self.assertEqual({"6"}, limits)` to `{"7"}`.
4. Add the two new test methods (present in the staged copy):
   `test_the_retrospective_round_runs_at_max_effort_with_tests_locked` and
   `test_the_retrospective_survey_covers_every_qa_prefix`.

This file is **not** under the CI-file lock, but it must move together with the
workflow: adding `"qa-retrospective": 7` to the expected map fails until the job
exists, and the `MAX_OPEN_PRS` assertions fail until the workflow is bumped. So
it belongs on the same `ci/` branch, not on the plan branch.

## 4. `tools/ci/README.md` — two new rows in the Prompts table

Add, in the `## Prompts (prompts/)` table:

- after the `build-review-prompt.sh` row:
  `| `build-retrospective-prompt.sh` | Build prompt for the recurring QA retrospective: survey recent automated work and propose how the process should improve |`
- after the `performance.txt` row:
  `| `retrospective.txt` | Template for the QA retrospective: study recent automated work and propose, with evidence, how the agent harness and the QA jobs should improve — proposes, never implements |`

## 5. `.github/CLAUDE.md` — "What the `Master Agent Dispatch` workflow does"

This file is not under the lock, but it describes the workflow and so must move
with it. Make these edits in that section:

1. The job list (`doc-qa … pdsl-qa`) gains `qa-retrospective`.
2. "The six QA-style jobs (`doc-qa`, …, `pdsl-qa`)" becomes seven, with
   `qa-retrospective` added and `qa/retro-` added to the `BRANCH_PREFIX` list.
3. The interval sentence ("`MIN_INTERVAL_DAYS` is 2 … or 5 …") gains the
   retrospective's 7, and the `ignore_interval_<job>` list gains `retrospective`.
4. "(6, the number of automated QA rounds)" becomes "(7, …)".
5. Add a sentence that the retrospective's output is docs-only (a report under
   `docs/qa/retrospectives/` and a ledger row), that it pins `claude/opus` at
   `effort: max` for the same reason as `pdsl-qa`, and that `ci/qa-…` is the
   branch convention for a pipeline-subject QA follow-up a person opens from an
   accepted proposal.

## How this was validated

Run from the repo root (no pytest/test-runner needed — plain Python against the
staged files; the dispatch validator temporarily copies the builder+template
into `tools/ci/prompts` and removes them afterward):

```
python3 /tmp/validate_retro.py     # staged test_master_agent_dispatch (27) + live test_prompt_builders (15)
python3 /tmp/validate_intake.py    # test_automated_intake wiring (4)
```

When the files are actually in place on the `ci/` branch, confirm with the real
suites (each is a single lightweight file, well within the Python node-id limit):

```
python3 -m pytest tools/tests/test_master_agent_dispatch.py
python3 -m pytest tools/tests/test_prompt_builders.py
python3 -m pytest tools/tests/test_automated_intake.py
```

All were green against the staged artifacts at the time this was written.
