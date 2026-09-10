# Test Coverage Automation — Outstanding Work

## Status

**Built and merged.** Every workflow, script, prompt and data file the original
plan described exists on `master` and is wired up, and all four of the plan's
open questions were settled during implementation. The design narrative that
used to fill this document has been removed: it now lives in the code and
workflow comments listed under [Where it lives](#where-it-lives), which are the
copies that stay correct when the pipeline changes.

Two things are not finished. Neither is a design question.

---

## 1. The pipeline has never completed a round in production

`coverage-qa` has failed at its **Fetch latest coverage report** step on every
run to date — most recently run 33 (`a5a558d63`, 2026-09-09), where the job died
after ten seconds while `doc-qa` and `defect-hunt` in the same workflow
succeeded.

The cause was found and fixed. `fetch-latest-coverage.sh` chose the
`merged-coverage-report` artifact by first locating an `analysis.yaml` run whose
*overall* conclusion was `success`. That conclusion is `failure` on most master
pushes, because an unrelated flaky lane fails even when the `analysis` job that
uploads the artifact succeeded — so the query kept resolving to a run old enough
that its artifact had passed the 30-day retention window, and every download
returned HTTP 410. The offline recompute fallback then failed for its own
reason: it ran `mvn -o`, which needs build extensions already cached on whichever
self-hosted host picked up the job. Both were fixed in `4a2a92cd8` (re-landed
from a `ci/` branch, having been reverted from a feature branch only because of
the branch-prefix rule on `tools/ci/`).

**What is outstanding is confirmation, not code.** The fix landed after run 33,
and the first run to exercise it was still queued when this was written. Close
this item by finding a `Master Agent Dispatch` run on `master` newer than
`4a2a92cd8`, and checking that `coverage-qa` gets past "Fetch latest coverage
report" and completes a round.

Until one has, no part of this pipeline has been observed working end to end —
target selection, dispatch, the ledger append and the give-up path have all been
unit-tested and none has ever run for real.

## 2. Artifact lookup reads a single page

`fetch-latest-coverage.sh` lists `merged-coverage-report` artifacts with
`per_page=100` and filters client-side for unexpired artifacts on the target
branch. The endpoint has no server-side branch filter, and `analysis.yaml`
uploads this same artifact name on **every** `pull_request` run, not only on
master pushes. If more than 100 such artifacts accumulate between master runs,
the master artifact scrolls off the page, the filter finds nothing, and the job
falls back to the hours-long recompute — the same symptom item 1 fixed, reached
by a different route.

100 is the API's maximum, so a larger page is not available. Closing this means
paging until a match is found or the listing is exhausted. A `TODO(review)`
comment marks the call site.

---

## Where it lives

| Piece | Location |
|---|---|
| `coverage-qa` job | `.github/workflows/master-agent-dispatch.yaml` |
| `assertion-density-report` job (report-only, deliberately absent from `all-checks`) | `.github/workflows/analysis.yaml` |
| Coverage fetch / recompute | `tools/ci/coverage/fetch-latest-coverage.sh` |
| Target selection + its tests | `tools/ci/coverage/select-target.py`, `test_select_target.py` |
| Assertion-density report | `tools/ci/coverage/assertion-density-report.sh` |
| Agent prompt | `tools/ci/prompts/coverage.txt`, `build-coverage-prompt.sh` |
| Ledger and exclusions | `tools/coverage-data/coverage-history.tsv`, `coverage-exclusions.txt` |

The data files live under `tools/coverage-data/` rather than `tools/ci/` on
purpose. The pipeline's own data has to travel through the same commit lock its
logic does, and the resolution was to move the data out of the locked path
rather than carve an exception into `validate-agent-commit.sh` — an agent job
must never be able to widen its own latitude by editing what polices it.

## Loose end

`feature/test-coverage-automation` is unmerged and has nothing left to
contribute. Its only content not on `master` is a 42-line writeup of the
artifact-reuse incident, now summarised in item 1 above; its copy of
`fetch-latest-coverage.sh` is the pre-fix version. The branch can be closed.
