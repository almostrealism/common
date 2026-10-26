# QA Retrospectives

This directory is the durable record of the **QA retrospective** round of the
Master Agent Dispatch workflow. Where the other automated QA rounds each improve
the code against a problem we already know to care about, the retrospective
looks back at what those rounds — and the agent harness that runs them — have
actually been doing, and proposes, with evidence, how the process itself should
improve.

Each run of the retrospective produces one docs-only branch and, on it, one new
report plus one appended row in the survey ledger. A person reviews each report
and decides whether to act on any of its proposals.

## What a report is — and is not

A report in this directory is a **list of independent proposals**, each backed by
checkable evidence (a workstream id or branch, a job id, a PR number, a commit
SHA, or a memory id) and marked with where it would land if accepted:

- a `ci/qa-…` branch, for anything under `.github/workflows/`, `tools/ci/` or
  `.claude/hooks/`;
- an ordinary branch, for harness Java, MCP tools, or documentation;
- person-only, for secrets, the runner fleet, or controller configuration.

A report is **not a plan**. Its proposals are deliberately independent and must
never be implemented as a unit. A proposal a person accepts becomes its own unit
of work — a tracker task, or a branch dispatched for exactly that change. This is
why the reports live here under `docs/qa/` (a durable, reviewed record, the same
kind as the documentation-QA output already in `docs/qa/`) rather than under the
planning tree, where an implementation pass would try to carry the whole file out
at once.

## Files

| File | Content |
|---|---|
| `RETRO-<YYYYMMDD>.md` | One run's findings report |
| `ledger.tsv` | One row per run: window, counts, newest activity seen, proposal slugs, report path |
| `ASSESSMENT-<YYYYMMDD>-<slug>.md` | A one-off investigation of a single automation incident, written outside the retrospective round; its open questions are tracked as tracker tasks, not carried out from the file |

### The survey ledger

`ledger.tsv` is advisory input to the next run, not a gate: nothing validates it.
Each run appends one row and reads the previous rows to tell old activity from new
(`newest_job_seen`) and to track each proposal's status across runs
(`proposal_ids`). It lives here, outside `tools/ci/`, for the same reason
`tools/coverage-data/coverage-history.tsv` lives outside it: a round appends to it
on its own branch, which the CI-file lock would otherwise refuse.

The columns are, in order:

`run_utc`, `branch`, `window_since`, `workstreams_surveyed`, `jobs_surveyed`,
`prs_surveyed`, `newest_job_seen`, `proposal_ids`, `report_path`.

## Conventions

- **This repository only.** A report surveys only workstreams whose repository is
  this one, even though a job token can reach others in the same workspace.
- **Public repository.** A report names no private downstream product and quotes
  no private workstream, following the root `CLAUDE.md` rule.
- **Proposes, never implements.** A retrospective run edits nothing but its own
  report and this ledger.
