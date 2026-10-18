# consult: Evidence Grounding, Corpus Coverage, Conversations and Evaluation

Status: **PARTIALLY IMPLEMENTED.** Step 1 (the evaluation harness and seed
cases E1–E4) and Step 2 (the first corpus-coverage fix — indexing the CI
documents and raising the per-file read cap) have landed; see Section 6 for
which steps those are and the many later steps that remain only planned. The
rest of this document is still a plan, written to be iterated on; every section
separates a first step from later refinements.
Author: planning session, 2026-10-17.

> **Locate code by symbol, not by line.** References below name functions and
> constants (`consult`, `build_consult_prompt`, `format_memory_context`,
> `STANDALONE_MD_ROOTS`). Where a line number is given it was correct on
> 2026-10-17 and will drift.

This document plans improvements to the ar-manager `consult` tool. It starts
with how the tool works today (section 1), then covers the main problem:
how to combine three kinds of evidence that differ a lot in reliability
(section 2). After that come documentation corpus coverage (section 3),
multi-turn consultation (section 4) and a version-controlled evaluation set
(section 5). It ends with an implementation order sized one job per step
(section 6) and open questions (section 7).

It builds on, and does not replace, two earlier documents:
`docs/plans/CONSULTANT_QUALITY.md` (the 2026-07 quality audit, which found
that most failures come from retrieval and coverage, not from synthesis) and
`tools/mcp/memory/SCHEMA_EVOLUTION_PROPOSAL.md` (memory lifecycle columns:
`verification_status`, `superseded_by`, `hit_count`, ...). Where this plan
needs memory metadata, it says whether the metadata can be computed at read
time (available now) or needs that schema work (later).

### Non-goals

- The agent "exit interview" itself. Section 4.6 lists what it will need from
  consult, and stops there.
- Changing which inference backend or model consult uses. Everything here
  assumes a small local model (the `AutoBackend` order in
  `tools/mcp/common/inference.py`: llama.cpp, Ollama, MLX, then OpenRouter)
  and is designed so that a weak model can follow it.
- Fine-tuning. The 2026-07 audit's conclusion still holds: fix retrieval,
  coverage and the evidence contract first.

---

## 1. How consult works today

### 1.1 Request path

`consult` (`tools/mcp/manager/memory_tools.py`) runs these steps:

1. Checks scope (`memory-read`) and lengths (`question` and `context` are
   each bounded by `MAX_PROMPT_LEN`).
2. **Documentation retrieval** through `DocsRetriever`
   (`tools/mcp/common/docs_retriever.py`). If the caller supplied `keywords`
   it calls `get_context_for_keywords(keywords)`. Otherwise it calls
   `get_context_for_query(question)`.
3. **Memory retrieval** through the ar-memory HTTP client:
   `client.search(query=question, namespace="default", limit=3, repo_url=…,
   branch=…)`. Scope is `repo`, meaning all branches of the caller's
   repository.
4. **Presentation** through `memory_text.present()`. This picks original or
   reformulated text according to `prefers_reformulated()`.
5. **Synthesis** through `llm.consult(question, doc_context, memory_context,
   extra_context)`, which calls `build_consult_prompt` and then `synthesize`
   with `SYSTEM_PROMPT`.
6. **Post-processing.** If the answer, lower-cased with the trailing period
   removed, equals `"not documented"`, the answer is dropped and replaced by
   a `note`. Otherwise it is returned as `answer`.

The response always contains `sources` (the sorted set of markdown files that
retrieval returned), `html_refs`, and `related_memories`. Each
`related_memories` item carries only `content` and `score`.

### 1.2 The corpus: what is copied, and what is indexed

These are two separate filters, and they disagree with each other.

**Copy filter — the `docs` stage of `tools/mcp/manager/Dockerfile`.** It
copies `docs/`, the root `CLAUDE.md`, `base/`, `compute/`, `domain/`,
`engine/`, `extern/`, `studio/`, `flowtree/` and `tools/`, then deletes every
file that is not `*.md` or `*.html`. The result is baked in at
`/app/docs-root`, with `AR_DOCS_DIR=/app/docs-root/docs`. The following are
**never copied**: `.github/`, `.claude/`, the root `README.md`, and the
top-level stray files (`HOST_ARRAY_AUDIT.md`, `PR_REVIEW_CARRYOVER.md`).
`.dockerignore` excludes git history, so the image cannot tell which commit
its corpus came from. **The corpus has no snapshot identifier.**

**Index filter — `DocsRetriever._markdown_files` / `_all_doc_files`.**
Retrieval does not walk the copied tree. It reads a fixed allowlist:

- `README.md` and `docs/*.md` for each of the 24 entries in `MODULE_PATHS`;
- `STANDALONE_MD_ROOTS` (`flowtree/runtime/README.md`,
  `flowtree/runtime/docs/*.md`, and eight READMEs plus `CLAUDE.md` and
  `SECRETS.md` under `tools/mcp/`);
- `docs/QUICK_REFERENCE.md`, the root `CLAUDE.md`, and
  `docs/internals/*.md`;
- HTML: `docs/modules/*.html` and `docs/tutorials/*.html`.

On a checkout taken 2026-10-17 this comes to **130 files**. Section 3.1
lists the gaps.

There is a third, hidden filter: `_read_file` keeps only the **first
50 000 characters** of each file. Anything later in a long document cannot
be retrieved, with no warning.

### 1.3 Documentation retrieval and ranking

The index is not precomputed and nothing is embedded. On each call the
retriever reads the files and matches them line by line:

- `_fuzzy_match(query, line)` succeeds when the whole query is a substring of
  the line, or when **every** query word of three or more characters has a
  stem or synonym variant on that line (`_get_word_variants`, `SYNONYMS`).
- A match becomes a chunk of the matching line ±6 lines. The chunk is
  labelled `[file:line]`, and the label carries **the matching line's
  number, not a range**.
- `search_by_type` ranks the matches:
  - READMEs are scored by how many query words a line contains, and files by
    total match count.
  - The best README contributes one or two chunks.
  - Other files are taken one chunk per file, deepest path first.
  - Remaining slots are filled with further README chunks, then repeats.
- `get_context_for_query` first runs the full question as one query, which
  almost never matches because every word must hit one line. It then runs
  each extracted keyword (stop words removed) on its own.
  `get_context_for_keywords` runs each caller keyword on its own. Both stop
  at 5 chunks and 8000 characters.

The model gets no relevance score. Chunk order is the only ranking signal it
sees.

### 1.4 Memory retrieval

- Only the **`default` namespace** is searched. Each namespace has its own
  FAISS index (`CONSULTANT_QUALITY.md`). Memories that agents store under
  `bugs`, `decisions`, `context` or `progress` — the namespaces the root
  `CLAUDE.md` tells agents to use — **are never seen by consult.**
- The query is the raw `question`, even when the caller supplied `keywords`.
- `limit=3`. The repo/branch filter is applied after the vector search, so
  fewer than three may come back.
- `score` is **L2 distance**: lower means closer (`MemoryStore.search`
  docstring). `consult` passes it through without a label.
  `memory_recall`'s summary prompt calls it `similarity`, which reverses its
  meaning.
- Nothing about age, branch, merge state or whether the note still holds
  reaches the model or the caller.

### 1.5 The prompt given to the model

`SYSTEM_PROMPT` (12 rules plus two worked examples) frames the model as a
documentation consultant. The rules that matter for this plan:

- Rule 5: answer "Not documented" only when *all* chunks are irrelevant.
- Rule 6: cite as "per file.md:line".
- Rule 9: notes are one agent's record of one past task, and are evidence
  only about what they name.
- Rule 11: cite a line only for a location that actually states the claim.

`build_consult_prompt` builds the user prompt in this order:

1. `## Question`
2. optionally `## Additional Context from Agent`
3. `## Relevant Documentation` ("the answer is in here", followed by the
   `[file:line]` chunks)
4. `## Notes From Prior Sessions`
5. `## Instructions` ("Answer the question above using the documentation
   chunks… Cite the source file and line number.")

The notes section says notes "carry no authority beyond the classes and
files they name" and must "never let its wording supply a fact the
documentation does not state."

`format_memory_context` labels each note
`### Note i — written by an agent about: {source}`. In practice `source` is
almost always empty or a tool name such as `ar-manager`, so the label
carries no information. The note has no date, no branch, and no indication
whether that branch merged.

**What this adds up to.** The prompt has two tiers: documentation, which is
authoritative, and notes, which may only qualify documentation. Notes are
**forbidden from supplying an answer**, and every note looks alike
regardless of age or provenance. Citations are free text, which the model
can make up.

### 1.6 What the observed failures show

| Observed failure (2026-10-17) | Mechanism in today's code |
|---|---|
| Master Agent Dispatch QA rounds → "Not documented", even though three relevant memories were retrieved | `.github/CLAUDE.md` is not copied (1.2). The retrieved notes held the answer, but the prompt forbids a note from supplying a fact the documentation lacks (1.5), and the post-processing turns "Not documented" into a bare `note`. |
| `validate-agent-commit.sh` → correct description, but "exit 2 per `flowtree/runtime/docs/claude-code-job.md:330`" | The chunk at that label is the `detect-test-hiding.sh` section (exit codes 0/2/1, around lines 330–340). The model attached a chunk's label to a claim about a different subject. The real value (`exit 3`, last line of `tools/ci/agent-protection/validate-agent-commit.sh`) is in no document at all — only in the script. Free-text citations meant nothing caught it (1.3, 1.5). |
| `ObjectOrientedDesignRule` verdict location → guessed from an unrelated April memory about `runCorrectionSession`. It also surfaced April memories naming `ClaudeCodeJob` | The real path, `.flowtree/oop-review.md`, is the `VERDICT_PATH` constant in `ObjectOrientedDesignRule.java` and appears in no document. `ClaudeCodeJob.java` was deleted on 2026-05-16 (commit "Pluggable agents"; `CodingAgentJob` took its place). Nothing told the model that the note was five months old or that it named a class that no longer exists (1.4, 1.5). |
| `check-branch-entropy.sh` (no such script) → "Not documented" | Correct abstention. It should stay correct after every change below. With a path index (2.5) it could also say "no such file exists in the snapshot", which is a stronger answer. |

Two of the four failures are coverage problems: the answer is not in the
corpus. Two are evidence-contract problems: the model has the wrong rules
about what it may say and how it may cite. Neither kind can be fixed by
working on the model alone.

---

## 2. The central problem: blending evidence of different reliability

### 2.1 Three kinds of evidence

| Kind | What it is | Typical failure | What it is still good for |
|---|---|---|---|
| **(a) Snapshot documentation** | The corpus baked into the image, for one commit | Silence (coverage gaps); sometimes out of date where the docs themselves lag the code | Present-tense fact *for that commit* |
| **(b) Recent notes** | Agent notes from the last weeks, usually about the author's own branch | Right for their branch but not yet (or never) on master; contradicting another branch's note; superseded by later work | Facts the docs do not cover yet; in-flight work; gotchas |
| **(c) Old notes** | Notes from months ago | Wrong about the present: renamed classes, removed mechanisms, superseded policy | History, rationale, "why it is this way", dead ends already tried |

The key point is that **reliability belongs to each item, not to each
kind**:

- a two-day-old note on an abandoned branch is less reliable than a
  four-month-old note on master whose named classes all still exist;
- a section of documentation can itself be stale.

The kinds are a useful default. The system should still compute each item's
standing from signals it can check, rather than from the bucket the item
came out of.

### 2.2 Principles

1. **Provenance goes with the item, to the model and to the caller.** The
   model cannot weigh what it cannot see. The calling agent cannot check the
   model's weighing if the response drops the provenance (as
   `related_memories` does today).
2. **Code assigns the tier; the model applies it.** A small model cannot
   reliably work out how old something is, compare branches, or tell whether
   a class still exists. Python can. The model gets the result as a label
   and a short rule for each label.
3. **Surface conflicts instead of resolving them.** When two items disagree,
   the caller hears about it. Consult may say which item it favours and why,
   but it must not drop the other one silently.
4. **Old notes are demoted, not deleted.** A stale note may supply history
   and rationale. It may not supply a present-tense fact.
5. **Every answer states its basis.** The caller must be able to tell an
   answer from documentation from an answer from a note without reading the
   citations.
6. **Abstain rather than guess, and abstain precisely.** Say which part is
   unknown, what was searched, and where to look next.

### 2.3 The per-item provenance record

Every evidence item — a documentation chunk, a note, or (once the
value-extracting index versions land, 2.5) an index-derived fact — becomes a
record with a short ID, assigned before the prompt is built.

```
D1  doc    .github/CLAUDE.md:850-862  § "What the Master Agent Dispatch workflow does"
           corpus=<sha> built=<date>
N1  note   id=7a5f…  2026-10-15  branch=qa/retro-20261015 (merged 2026-10-16)
           namespace=decisions  names: qa-cadence.sh ✓, MAX_OPEN_PRS ✓
N2  note   id=31c0…  2026-04-22  branch=feature/x (closed, not merged)
           names: ClaudeCodeJob ✗ (absent from snapshot), runCorrectionSession ✓
```

Fields:

| Field | Source | Available |
|---|---|---|
| `id` | memory id / `file:start-end` | now |
| `created_at` | memory row | now (stored, currently dropped) |
| `branch`, `repo_url` | memory row | now (stored, currently dropped) |
| `namespace` | memory row | now |
| `branch_state` — `master`, `merged@<date>`, `open`, `closed-unmerged`, `unknown` | GitHub PR lookup by head branch, cached (2.4) | first step |
| `same_branch_as_caller` | compare with the caller's resolved branch | now |
| `named_symbols` with present/absent flags | mention extraction plus the snapshot index (2.5) | needs the index step |
| `section_path` for doc chunks | heading trail above the chunk | needs the chunking step (2.9) |
| `verification_status`, `superseded_by` | `SCHEMA_EVOLUTION_PROPOSAL.md` | later |

### 2.4 Tier assignment

Tiers are computed by one pure function, `tier(item, snapshot, caller)`. A
pure function can be unit-tested and changed without touching the prompt.
Proposed tiers:

| Tier | Label shown to the model | Assigned when |
|---|---|---|
| **A** | `DOC (snapshot <sha>, <date>)` | A documentation chunk. |
| **B** | `NOTE current` | Note on `master`, or on a branch merged into its base branch (whether that merge predates **or follows** the snapshot date; see the post-snapshot note below), or on the caller's own branch; younger than the recency window; no named symbol absent from the snapshot. |
| **B′** | `NOTE branch-local (<branch>, <state>)` | Note on an open or unmerged branch other than the caller's. It is true *of that branch* and not established for master. |
| **C** | `NOTE historical (<age>)` | Older than the recency window, or on a closed-unmerged branch, and no named symbol absent. |
| **C✗** | `NOTE stale — names absent from snapshot: X, Y` | Any note that names a type or path the snapshot index does not contain, whatever its age. |

Rules the model gets (short, one per tier, written into `SYSTEM_PROMPT`):

- **A** states present fact. Cite it.
- **B** may supply a fact the documentation lacks. The answer must then be
  labelled as resting on a note (2.7).
- **B′** may be reported only as "on branch X, …". It never answers a
  question about master.
- **C** and **C✗** may supply history and rationale ("this used to be…",
  "it was introduced because…"). They never supply present-tense fact. For
  **C✗**, the model must say which names are gone.

**Post-snapshot branch changes.** Branch state is looked up live at consult
time (2.4), while the corpus is a fixed snapshot, so the two can disagree: a
note's branch can be merged (or closed) *after* the corpus was built. The
table must stay total for that case. A note on a branch that merged after the
snapshot is **B** — it describes work that is now on `master` even though the
snapshot predates it — and this is precisely the B case where a note supplies
a present fact the documentation snapshot does not yet carry (2.7). One
interaction follows from the lag: a symbol the note introduces may be absent
from the *snapshot* index purely because the snapshot predates the merge, not
because the symbol is stale. A merged-after-snapshot note is therefore **not**
demoted to C✗ on a snapshot-absence alone; the absence is recorded (and can be
surfaced as "present on master, not yet in the snapshot `<sha>`") but does not
override the B tier that the merge establishes. A note on a branch that closed
**unmerged** after the snapshot is C (closed-unmerged), exactly as the table's
C row already states; only the merged-after case needed the explicit rule.

**The recency window.** The first step uses a fixed window (proposed: 45
days before the snapshot date). It is coarse but easy to understand. A later
refinement replaces age with *"has the snapshot changed anything the note
names since the note was written?"* That requires per-path last-modified
dates in the index (2.5). It is a better signal than age, because a
six-month-old note about a file nobody has touched since is still current.

**Merge state.** ar-manager already talks to GitHub for `github_pr_find`.
Branch state comes from looking up the most recent PR by head branch.

*Authorization has to be settled first.* `consult` requires only the
`memory-read` scope, while `github_pr_find` and the other GitHub tools call
`server._require_scope("github")` (`github_tools.py`). Neither obvious route
is acceptable as it stands: calling the tool would make every consult from a
token without `github` fail, and calling the underlying GitHub helper
directly from consult would quietly let a `memory-read`-only token learn PR
state it could not otherwise see. Proposed contract:

- consult performs the branch-state lookup only when the caller's token also
  holds `github`, and only for the note's own repository, resolved through
  the same `server._resolve_github_repo` path the GitHub tools use. The
  `github` scope is not the only gate on that path: `_resolve_github_repo`
  also applies the workspace/org scope gate before it will resolve an
  explicit repository. consult must reuse that check so a `github`-scoped
  *workspace* token cannot supply an arbitrary `repo_url` and read another
  organization's PR state through that org's configured PAT — consult looks
  up only the branch of the note's stored `repo_url`, under the same gate,
  never a repository named by the caller;
- otherwise every note gets `branch_state: unknown`. `degraded` stays the
  boolean it is today (`memory_tools.py` sets `degraded: true` on synthesis
  failure and `test_memory_tools.py` asserts it as a boolean), so the
  branch-state degradation is reported through a new
  `degraded_reasons: ["branch_state"]` list rather than by turning `degraded`
  itself into a list. Tiering proceeds on the remaining signals (`unknown`
  is already handled conservatively, below);
- a lookup that fails (GitHub unreachable, rate limit) degrades the same
  way — `branch_state` is added to `degraded_reasons` — and never fails the
  call.

Whether a `memory-read`-only caller should be allowed to see derived merge
state at all is open question Q9.

*Caching.* The cache key must identify the PR, not just the branch name,
because a branch name can be reused by a later PR. Proposed: cache per
`(repo, branch)` with the PR number and head SHA stored in the entry; open
entries expire after about 10 minutes; merged/closed entries are
revalidated after a bounded TTL (proposed 24 hours), and a revalidation that
finds a *different* most-recent PR for the same branch replaces the entry.

The cache is shared across requests, but branch-state availability depends on
the *caller's* authorization, so the two must not be conflated. The entry
stores only the objective branch state — the most-recent PR's number, head
SHA and merged/closed/open status for a `(repo, branch)` — which is a property
of the repository, not of any caller. It never stores a caller-derived
`unknown`. Authorization is therefore a *precondition for touching the cache*,
not part of its key: the `github`-scope and workspace/org gate (above) are
re-checked on every call *before* the cache is read. A caller that fails the
gate (no `github` scope, or the note's `repo_url` outside its workspace/org)
gets `branch_state: unknown` with `branch_state` in `degraded_reasons`, and
neither reads nor writes the entry — so an authorization-skipped lookup can
never poison a later authorized read with a stale `unknown`, and a populated
(privileged) entry is never served to a caller that has not just passed the
same gate. Only a caller that passes the gate reads or populates the state.
A note is then tiered against the PR that was current when it was written,
but *note-before-PR is not by itself evidence of branch reuse.* Agents
routinely store a memory before the branch's first PR is opened, so a note
whose `created_at` merely precedes the current PR's `created_at` must still
be tiered against that current PR — demoting it on that basis alone would
send most notes on most branches to `unknown` and defeat tiering for the
common case. A note is treated as belonging to an *earlier* incarnation of
the branch — and so mapped to `unknown` — only on positive evidence of
reuse: the note predates the `closed_at`/`merged_at` of a *previous* PR for
the same head branch (or, failing that, the first commit on the branch after
that previous PR closed). A branch with no previous PR has had only one
incarnation, so every note on it is tiered against the current PR regardless
of order. A memory store with ~10⁴ notes spans a few hundred distinct
branches, so the cache stays small.

*Detecting a previous incarnation needs a PR-history lookup, not the
most-recent one.* The reuse rule above compares against a **previous** PR's
close/merge time, but the lookup that populates the cache,
`server._find_recent_pr_by_branch`, uses `state="all"`, which
`_PR_STATE_QUERIES` maps to `per_page=1` — it returns only the single
most-recently-updated PR, so once the current PR is known no earlier
incarnation is visible from it. Establishing reuse therefore requires a
separate history query for the head branch — the `closed`/`merged` listing
already pages with `per_page=100` and can surface earlier PRs — run only when
a branch actually has more than one PR. Until that history query is added,
reuse cannot be established and a note is tiered against the current PR (the
common, safe default, since branch reuse across distinct PRs is rare); this
plan schedules the history lookup with the tier step and treats
previous-incarnation demotion as a refinement on top of it, not a precondition
for tiering.

Edge cases:

- branch names that are reused (handled above: a note predating a
  *previous* PR's close maps to `unknown`; a note that merely predates the
  current PR does not);
- notes with no branch;
- `qa/*` branches whose PR was closed and then superseded.

All three map to `unknown`. `unknown` is treated as **C** for facts and
**B′** for wording — the conservative choice.

**Why not let the model weigh raw metadata?** It could be given
`created_at`, `branch` and `branch_state` and left to decide. That is
simpler and more flexible, but it fails in exactly the cases that matter.
Small models do not do date arithmetic reliably, and they treat confident
prose as authoritative. Computed tiers can be tested, can be measured
(section 5), and can be explained to the caller. The raw fields still go to
the caller in the response, so a stronger calling agent can overrule the
tier.

### 2.5 Staleness signals beyond age: a snapshot index

The corpus keeps only `.md`/`.html`. So consult cannot answer "does
`ClaudeCodeJob` exist?", even though the build stage had the full tree in
hand before deleting it. **Proposal:** in the Dockerfile's `docs` stage,
generate a small `snapshot-index.json` from the full tree and ship it next
to the corpus.

**Ordering against the prune.** The stage's prune is
`find . -type f ! -name '*.md' ! -name '*.html' -delete`, which would delete
a `snapshot-index.json` written into the same tree. The index must be
*computed* before the prune (the tree it describes is gone afterwards) but
*written* outside `/src` — for example to `/snapshot/snapshot-index.json`,
copied into the runtime image by its own `COPY --from=docs` — or the prune
must explicitly spare that one filename. Writing outside the pruned tree is
preferred: it keeps the prune rule simple, and the packaging test can assert
that the file exists in the final image.

First version (cheap, high value):

- `commit` and `built_at`. The commit comes in as a build arg
  (`--build-arg CORPUS_COMMIT=$(git rev-parse HEAD)`) because `.dockerignore`
  excludes `.git/`. **Neither current caller passes it:** the CI image build
  in `.github/workflows/analysis.yaml` (job step running
  `docker build -f tools/mcp/manager/Dockerfile -t ar-manager:ci .`) and the
  deployment build in `flowtree/runtime/controller/docker-compose.yml`
  (`dockerfile: tools/mcp/manager/Dockerfile`, which would need a
  `build.args` entry). Both must be updated in the same step, and the
  Dockerfile must default the argument to an explicit sentinel
  (`ARG CORPUS_COMMIT=unknown`) so that a build that omits it yields
  `corpus.commit: "unknown"`, never an empty string that looks like a
  value. The analysis.yaml edit falls under the CI-file lock, so that part
  has to land on a `ci/...` branch (or be applied by a person); the plan
  step cannot be completed on an ordinary feature branch.
- `paths`: every file path in the copied trees plus `.github/`, with build
  output excluded. Today that is about 10⁴ paths (`git ls-files` lists
  ~9.9×10³ tracked files) and ~770 KB of raw text, much smaller with prefix
  compression.
- `java_types`: map from simple type name to path(s), taken from file names
  (~9×10³ `*.java`). File names are enough because the codebase keeps one
  top-level type per file.
- `scripts`: basename to path **list**, for `*.sh` and `*.py` under
  `tools/`, and under `.github/` once that is copied. The list form matters
  because script basenames are not unique in the tree: `entrypoint.sh`,
  `fleet.sh` and `install.sh` each occur in several directories (e.g.
  `tools/ci/docker/entrypoint.sh` and `tools/ci/rocm/entrypoint.sh`;
  `tools/ci/cuda/fleet.sh`, `tools/ci/macos/fleet.sh` and
  `tools/ci/rocm/fleet.sh`), as do `server.py`, `store.py` and others. A
  basename-to-single-path map would silently drop all but one, so a note
  mentioning `entrypoint.sh` could resolve to the wrong file or be reported
  absent when it exists. Scripts therefore use the same name-to-`path(s)`
  shape as `java_types`, and a presence check succeeds if *any* path for the
  basename exists. The repository's top-level `scripts/` directory
  (`generate-llms-full.sh`, `check-python-file-lengths.sh` and others) is
  **not** copied by the `docs` stage today, so indexing it requires adding
  `COPY scripts/ ./scripts/` to that stage as well (its contents are pruned
  like everything else; only the index records them). This plan proposes
  adding that `COPY` in the same step.

Later versions, in rough order of value:

1. **Script and workflow header comments and exit codes.** For example, the
   leading comment block of each `tools/ci/**/*.sh` plus its literal
   `exit N` lines. These scripts *are* the documentation of their own
   behaviour (see seed case E2).
2. **`static final String` constants, regardless of visibility**
   (`VERDICT_PATH = ".flowtree/oop-review.md"`). Seed case E3 is answerable
   from nothing else, and `ObjectOrientedDesignRule.VERDICT_PATH` is
   package-private (`static final String`, no `public`), so restricting
   extraction to public constants would leave E3 unanswerable. Extraction
   should take every `static final String` field initialised with a single
   string literal, recording its visibility, and skip computed initialisers.
3. **Per-path last-modified commit date**, which powers the
   change-since-note signal described in 2.4.
4. **Rename map** from `git log --diff-filter=R`. This turns "absent" into
   "renamed to `CodingAgentJob`" — useful history that a C✗ note could then
   carry correctly. Deletions such as `ClaudeCodeJob` show up as `D`, not
   `R`, so this helps less than it first seems.
5. Public method names per type. This makes the index much larger, so it
   should wait until evaluation shows a need for it.

**Index-derived facts are evidence items too.** The first versions of the
index only answer *presence* questions (does this path/type/script exist?),
which feed tiering and the `nonexistent` abstention (2.10) rather than the
answer prose. But the later versions (script header comments and `exit N`
lines, `static final String` constants) are expected to *answer* seed cases
E2 and E3, and an answer has to be grounded, cited and assigned a basis under
the same contract as documentation and notes. The evidence model (2.3) is
therefore extended with a third item kind, `I` (index-derived), carrying its
own IDs (`[I1]`, `[I2]`, …) alongside `[Dk]` and `[Nk]`. An `I` item records
the snapshot path and the extracted value (the exit code, the constant's
string, the header line) and is **tier A**: the index is generated from the
same corpus commit as the documentation, so an index fact is objective
snapshot fact, as authoritative as a doc chunk and with the same present-tense
standing. The server expands an `[Ik]` citation to the snapshot path (and
line, where the extractor recorded one) exactly as it expands `[Dk]`, so the
citation verifier (2.9) checks it the same way. Because an `I` item is tier A,
it contributes to the **`documentation`** component of `answer_basis` (2.6);
no new `answer_basis` value is introduced. This keeps E2's "`exit 3` with a
citation to the script or index" and E3's constant answer inside the grounding
contract instead of arriving as an un-citable code-generated string. Until the
value-extracting index versions land (steps 9 and 12), no `I` items are
produced and the kind is simply unused.

**Mention extraction** (memory side) only checks tokens that are shaped like
something checkable:

- `UpperCamel` with two or more humps;
- backticked identifiers;
- tokens with a `/` or a known extension (`.java`, `.sh`, `.py`, `.md`,
  `.yaml`);
- `file:line` references.

Every other token is *unchecked*, never treated as *absent*. This keeps
false "stale" flags low, at the cost of missing some stale notes. A note
counts as C✗ only when a checkable name is positively absent.

**Trade-offs:**

- It adds build complexity and image size, plus a second artifact that must
  stay in step with the corpus. Mitigation: generate both in the same stage,
  and add a packaging test in the style of `test_dockerfile_packaging.py`.
- It does not detect semantic staleness, where the class still exists but
  behaves differently. That needs the documentation/note conflict check
  (2.8) or the verification lifecycle in `SCHEMA_EVOLUTION_PROPOSAL.md`.

### 2.6 Presenting the tiers to the model

The prompt changes from "documentation, then notes" to a single **evidence
list**. Each item has its ID, tier label and provenance line, in this order:
A items, then B, then B′, then C and C✗. The question stays first. A new
first rule in `SYSTEM_PROMPT` states the tier contract in four lines
(above).

Two of today's statements must change, because they cause seed failure E1:

- "never let its wording supply a fact the documentation does not state"
  becomes the B rule (a current note *may* supply it, labelled).
- "Answer the question above using the documentation chunks" becomes
  "Answer from the evidence list under the tier rules."

The worked examples in `SYSTEM_PROMPT` gain three short cases:

1. an answer resting on a note;
2. a C✗ note used only for history;
3. a conflict being reported.

Small models copy examples more reliably than they follow rules, so the
examples do more work than the rules.

**Output format.** Ask for plain prose with `[D1]`/`[N2]` markers, followed
by two optional trailer lines that are easy to parse:

```
BASIS: documentation | documentation+notes | notes | none
CONFLICTS: N1 vs D2 — <one line>; …
```

JSON output was considered and rejected for the first step: small quantized
models break JSON often, and a broken trailer is easier to recover from
than broken JSON.

The model's `BASIS:` line is never trusted as written. A weak model can
declare `BASIS: documentation` while citing only `[Nk]` note IDs, or declare
`none` while actually citing evidence — either of which would mislabel
`answer_basis` (2.7) or abstain incorrectly. So the server always
**recomputes** the basis from the evidence IDs the answer actually cites.
The recompute counts an ID only after **resolving it against the evidence
map** the server itself built: a marker like `[D3]` or `[N2]` contributes to
the basis only when `D3`/`N2` is an item that was actually presented. A
fabricated or out-of-range marker — `[D999]`, `[N0]`, a prefix that was
never offered — is *not* counted, because otherwise a weak model could type
`[D999]` and obtain a non-`none` basis without citing any supplied item, so
bypassing abstention. From the resolved IDs: any valid `[Dk]` **or `[Ik]`**
(index-derived facts are tier A snapshot fact, 2.5) → includes documentation;
a valid `[Nk]` → includes notes **only when that note is tier B or B′**,
because C and C✗ notes are forbidden from supplying present-tense fact and are
cited only from the separate `history` field (2.7) — a `[Nk]` that resolves to
a C/C✗ note therefore never raises `answer_basis` to include `notes`, so an
answer that cites documentation for its claim and a C note only for rationale
is labelled `documentation`, not `documentation+notes`; nothing valid cited →
`none`. That computed value feeds `answer_basis` and the post-processing. An
unresolved marker is recorded as a citation warning
(`format_warning: "unknown citation"`) and otherwise ignored, so it can
neither raise nor lower the basis. The written `BASIS:` line is kept only as
a cross-check: a disagreement between the stated and the computed basis sets
`format_warning: "basis mismatch"`. If the trailer is missing entirely, the
computed basis stands, `CONFLICTS` is treated as empty, and
`format_warning: "trailer missing"` is set.

`build_consult_prompt` backs `consult` (through `llm.consult`) and the
retired consultant code path. `memory_recall`'s grounding does **not** go
through it today: `memory_recall` assembles its own `## Relevant
Documentation` / `## Retrieved Memories` / `## Task` sections and calls
`llm.synthesize` directly. So the new evidence layout should replace
`build_consult_prompt` in place (not sit beside it), **and** move
`memory_recall`'s hand-built sections onto the same shared builder in the
same step — otherwise that grounding path keeps the old contract and the
"one prompt is maintained" goal is not met.

### 2.7 When a note may answer what the documentation lacks

Today this is impossible: the prompt forbids it, and post-processing
replaces "Not documented" with a note. Proposed contract:

- A **B** note that *explicitly states* the asked-for fact may be the
  answer.
- The response then has `answer_basis: "notes"`, and the answer opens with a
  fixed sentence generated by code, not by the model: *"The documentation
  snapshot does not cover this. The following rests on agent notes:"*. Each
  claim cites its `[Nk]`, and the response's evidence list shows that note's
  date, branch and merge state.
- **B′** can answer only questions about that branch, or be reported as
  "on unmerged branch X, …".
- **C/C✗** never produce `answer_basis: "notes"` for present-tense facts.
  They can appear in a separate `history` field.
- Post-processing no longer turns every "Not documented" into a bare note.
  It looks at `BASIS`:
  - `none` with no B evidence is real abstention (2.10);
  - `none` *with* B evidence that the verifier finds relevant (shared
    salient tokens with the question) is recorded as
    `abstained_with_evidence: true`. That flags a candidate missed answer,
    which the evaluation counts.

**Trade-off.** Letting notes answer brings back the risk the current prompt
was written against: one class's details being attached to another class.
The guard is that a note answers only when it *explicitly* states the fact.
The citation verifier (2.9) enforces this mechanically by requiring the
claim's subject to appear in the cited note. Without the verifier, this
step is riskier. **That is why the verifier comes before note-answering in
the implementation order.**

### 2.8 Conflicts

Conflict sources, from cheapest to most expensive:

1. **Structural (code, first step).** A C✗ note names something absent from
   the snapshot, which proves that *name* is stale — not, by itself, that
   the note contradicts current behaviour. A note may deliberately describe
   a deleted, renamed, historical, generated or external path (2.7 already
   routes C✗ facts into `history`, not into present-tense answers). So a C✗
   is a structural conflict only when the note asserts the name is
   **present or current** and the snapshot disproves it — for example a note
   that says a class "exists" or describes its "current" behaviour while the
   snapshot has no such path. A C✗ whose claim is explicitly historical
   (past tense, "removed", "renamed to …", "formerly") is recorded as stale
   for lifecycle purposes but is **not** reported as a contradiction, so the
   absence of a name does not over-report conflicts or feed false stale
   signals downstream. Two B/B′ notes from different branches that name the
   same symbol are *potential* conflicts and are listed together.
2. **Model-reported (first step).** The `CONFLICTS:` trailer. The model is
   told to report a contradiction it notices, not to resolve it.
3. **Value-level (later).** For a fact with an extractable value (a number,
   path, exit code or prefix list), compare the values across items in code.
   This would have flagged E2 if the corpus had contained the script.
4. **Judge-based (later, offline).** The `judge_answers.py` harness from
   `CONSULTANT_QUALITY.md`, run over the evaluation set, not live.

Response field:
`conflicts: [{items: ["N1","D2"], kind: "structural|reported|value", summary}]`.
When the model favours one side, the answer says so in a sentence, and both
items stay in the evidence list. **Conflicts are never resolved by dropping
evidence.**

A later refinement uses the signals consult finds as input to the memory
lifecycle. A repeated C✗ (absent-name) signal against the snapshot — whether
or not it rises to a reported contradiction — is the natural trigger for
setting `verification_status=stale` in `SCHEMA_EVOLUTION_PROPOSAL.md`, since
staleness of a name is exactly what a C✗ proves. The write would be done by
a background job and never by consult on the read path.

### 2.9 Citation integrity

Three complementary changes.

**(i) The model cites IDs; code supplies locations.** The model writes
`[D3]`, never `file:line`. The server expands each ID to `file:start-end`
and the section heading. This removes invented line numbers outright. It
also fixes the "label is the match line, not the claim's line" problem
(1.3), because the location is now a *range* that honestly states what the
model saw.

**(ii) Section-aware chunks.** A chunk carries its heading trail (for
example `claude-code-job.md › Validation: detect-test-hiding.sh`), and its
window grows to the enclosing subsection when that is short. In E2 the
heading names the subject the model got wrong, so with this change the
mismatch is visible on the chunk itself.

**(iii) A post-hoc verifier.** This is deterministic Python. For each
sentence with a marker:

1. Extract salient tokens: identifiers, paths, script names, numbers and
   quoted strings.
2. **Subject check:** every identifier or path in the sentence must appear
   in at least one cited item (with heading trail and provenance included).
   E2 fails here: the sentence names `validate-agent-commit.sh`, but the
   cited chunk names only `detect-test-hiding.sh`.
3. **Value check:** every number or quoted literal in the sentence must
   appear verbatim in a cited item.
4. A sentence that fails gets `citation_warnings: [{sentence, marker,
   reason}]`. In the first step the sentence is also annotated *in the
   answer* with `[unsupported]`, so a caller reading only `answer` still
   sees it.

Later refinements:

- one regeneration attempt that gives the failures back to the model;
- dropping sentences that fail twice;
- tracking the verifier's false-positive rate on the evaluation set before
  making either of those the default.

**Trade-off.** Token matching misses paraphrase ("the commit validator" in
place of `validate-agent-commit.sh`). It errs towards flagging, which is the
right direction for a tool whose callers are told to trust it.
Uncited sentences are not verified, so the model could avoid the check by
not citing. The verifier therefore also counts uncited factual sentences
(those containing salient tokens) and reports `uncited_claims`.

### 2.10 Abstention

- **Full abstention:** `BASIS: none`, and no B evidence passes the relevance
  check. The response has `abstained: true` plus `searched`: keywords used,
  corpus commit, namespaces searched, and the count of evidence items by
  tier. The caller then knows *what was not found*, not only that nothing
  was found.
- **Partial abstention:** the answer covers the supported part, and the
  closing sentence (generated by code from the uncovered question terms)
  names what is not covered.
- **Nonexistence (needs the index):** when the question names a checkable
  path or type that the snapshot index lacks, consult says so directly:
  *"No file named `check-branch-entropy.sh` exists in the snapshot
  `<sha>`."* That is more useful than "Not documented", and it is the exact
  answer seed case E4 should get.
- **Presupposition** (today's rule 12) stays. The index lets code check
  presuppositions about existence before the model sees the question.

Abstention is measured in both directions (section 5):

- **false abstention** — E1 today;
- **false answer** — E3 today.

A change that reduces one at the cost of the other is visible as a
trade-off, not mistaken for an improvement.

### 2.11 What the response looks like afterwards

The change is additive. Existing fields keep their meaning:

```
answer, note                    (as today)
answer_basis                    documentation | documentation+notes | notes | none
abstained, abstained_with_evidence
evidence: [{id, tier, kind, file|memory_id, range, section, created_at,
            branch, branch_state, named_symbols:{present:[], absent:[]}}]
history: "<text>"               only when C/C✗ supplied rationale
conflicts: [...]
citation_warnings: [...], uncited_claims: n
degraded                        boolean, as today (synthesis failure)
degraded_reasons: [...]         e.g. ["branch_state"] when the PR lookup was skipped or failed (2.4)
corpus: {commit, built_at}
sources, html_refs              (as today)
related_memories                (as today, plus id, created_at, branch, namespace, tier)
```

`consult`'s docstring is the agent-facing contract, and it changes with
each step. Per `tools/mcp/CLAUDE.md`, any new *parameter* must go in the
signature and in `McpToolDiscoveryTest`'s parameter assertions.

### 2.12 Trade-offs summarized

| Choice | Gain | Cost |
|---|---|---|
| Code-assigned tiers | Testable, explainable, suits a small model | Rules are coarse; misclassification is systematic, not random |
| Fixed recency window (first step) | Simple | Penalizes old-but-valid notes until the change-since signal lands |
| Snapshot index | Real staleness and nonexistence signals | Build complexity; another artifact to keep in sync |
| Notes may answer | Fixes false abstention (E1) | Reopens subject-misattribution risk; needs the verifier first |
| ID citations plus verifier | Ends invented line numbers; catches E2 | Some false flags; slightly longer prompts |
| Trailer lines over JSON | Robust with a weak model | Less structure; parse-failure path needed |
| Widen namespaces (2.13) | Recall of `decisions`/`bugs`/… notes | More candidates → needs tier-aware selection to stay within budget |

### 2.13 Memory retrieval changes that come along with this

- Search every semantic namespace, not only `default`. (`messages` is
  already non-semantic by default — `AR_MEMORY_NON_SEMANTIC_NAMESPACES`
  defaults to `messages` in `tools/mcp/memory/store.py` — so it has no index
  and is excluded automatically.) Merge by distance, then select by tier: at
  most one C/C✗ item, prefer the caller's branch and B items.

  **The memory API cannot do this today.** `MemoryStore.search` and
  `MemoryHttpClient.search` (`POST /api/memory/search`) each take exactly
  one namespace, because each namespace has its own FAISS index. The only
  cross-namespace route, `search_by_branch` with an empty namespace, is a
  non-semantic, exact-branch, newest-first listing — it cannot rank by
  relevance and cannot reach other branches. This step therefore needs one
  of:

  1. *Client-side fan-out (no ar-memory change):* list the namespaces
     (`GET /api/memory/namespaces`, the endpoint `memory_namespaces` reads;
     a non-semantic namespace simply returns no search results, because
     `MemoryStore.search` returns `[]` for it), call `search` once per
     namespace with the same query and a
     per-namespace limit, and merge in consult. Cost: one HTTP round trip
     and one query embedding per namespace (the embedding is recomputed on
     each call); with the handful of namespaces in use this is acceptable.
  2. *A server-side multi-namespace search:* `namespace` accepts a list (or
     `*`), the store embeds the query once, searches each index, and merges
     by L2 distance before the repo/branch post-filter. Distances from
     separate indices are comparable because every index uses the same
     embedder and metric.

  Recommendation: option 1 for the first step (it touches only consult and
  is enough to measure E5), option 2 once the evaluation shows the
  namespace widening is worth keeping. Note that the per-index post-filter
  over-fetches only `limit * 4` candidates, so a namespace dominated by
  other repositories can still return fewer than `limit`; that limitation is
  unchanged by either option.
- Query with `question` plus the caller's `keywords`, not with `question`
  alone.
- Retrieve about 8 candidates and present 3–5 after tier selection. The
  prompt budget is the constraint. Documentation keeps its 8000-character
  allowance, and notes get a separate, fixed allowance.
- Label `score` as distance everywhere, and fix `memory_recall`'s
  "similarity" wording.

---

## 3. Corpus coverage

### 3.1 Audit (checkout of 2026-10-17)

Every `*.md`/`*.html` file outside build output, sorted by what happens to
it:

**Not copied into the image at all:**

| Path | Files | Notes |
|---|---|---|
| `.github/CLAUDE.md` | 1 | The authoritative description of the CI pipeline, Master Agent Dispatch, the QA rounds, `MAX_OPEN_PRS`. Cause of seed case E1. |
| `.github/CI_ARCHITECTURE.md` | 1 | CI design. |
| `.github/copilot-instructions.md` | 1 | Reviewer instructions; low value to consult. |
| `.claude/agents/*.md`, `.claude/commands/*.md`, `.claude/hooks/*.md` | 8 | Hook and agent behaviour (for example why a Bash command was blocked). Agents ask about these. |
| `README.md` (root) | 1 | Project overview. |
| `HOST_ARRAY_AUDIT.md`, `PR_REVIEW_CARRYOVER.md` (root) | 2 | Stray working files; probably should stay out. |

**Copied but never indexed** (in the image, invisible to `DocsRetriever`):

| Path | Files | Notes |
|---|---|---|
| Layer `CLAUDE.md`/`README.md` (`base/`, `compute/`, `domain/`, `engine/`, `extern/`, `studio/`, `flowtree/`) | 14 | Layer-level rules. |
| Module `CLAUDE.md` and extra root-level `*.md` (for example `engine/ml/CLAUDE.md`, which the root `CLAUDE.md` links as "Module guidelines"; `base/code/agent-memory.md`) | ~20 | Only `README.md` and `docs/*.md` are read per module. |
| `extern/ml-djl`, `extern/ml-onnx`, `extern/ml-script`, `studio/spatial`, `studio/experiments` | 6 | Missing from `MODULE_PATHS` entirely. |
| `flowtree/docs/**`, `flowtree/agents/**`, `flowtree/CLAUDE.md`, `flowtree/README.md` | 12 | Only `flowtree/runtime` is listed. |
| `tools/ci/**/*.md` (`README.md` with its script table; `cuda`, `docker`, `macos`, `monitor`, `rocm` READMEs) | 7 | The only document that describes each CI script, `validate-agent-commit.sh` included. |
| `tools/README.md`, `tools/agent-memory.md`, `tools/bin`, `tools/fleet`, `tools/tracker` | 5 | |
| `tools/mcp/memory/SCHEMA_EVOLUTION_PROPOSAL.md`, `tools/mcp/consultant/TRAINING_GUIDE.md`, `tools/mcp/transcript-analyzer/README.md` | 3 | |
| `docs/*.md` at top level (`COMPUTATION_MODEL.md`, `MODULE_RUBRIC.md`, `MODULE_REVIEW.md`, `FLOWTREE_COLLISIONS.md`, `claude-directory-setup.md`, `README.md`, `CLAUDE.md`), `docs/index.html` | 8 | `COMPUTATION_MODEL.md` especially. |
| `docs/mcp/*.md`, `docs/qa/*.md`, `docs/journals/*.md` | 10 | QA reports and retrospectives land under `docs/qa/`. |
| `docs/plans/*.md` | 113 | Excluded on purpose (temporary documents; see 3.3). |

**Not in the corpus by design, but needed for answers:** shell, Python,
YAML and Java sources. Seed cases E2 (exit code) and E3 (`VERDICT_PATH`)
can only be answered from these. This is what the snapshot index's later
versions (2.5) address. The full source tree is not proposed, because the
image size and prompt budget would not allow it.

### 3.2 Proposed fix: one manifest, and a test that holds it

The root cause is that **two allowlists** — the Dockerfile `COPY`s and
`MODULE_PATHS`/`STANDALONE_MD_ROOTS` — are maintained by hand and drift
apart. Proposal:

1. **First step:**
   - add `COPY .github/ ./.github/` and `COPY .claude/ ./.claude/` (markdown
     survives the prune; scripts and settings are deleted with everything
     else);
   - add `.github/CLAUDE.md`, `.github/CI_ARCHITECTURE.md`,
     `tools/ci/README.md` and `docs/COMPUTATION_MODEL.md` to
     `STANDALONE_MD_ROOTS`.

   Together with lifting the `_read_file` truncation (below), this fixes E1
   and gives consult the CI-script table. `.dockerignore` has no pattern for
   `.github/` or `.claude/`, so the build context already includes both.
2. **Second step:** replace the hand-kept index allowlist with a **walk of
   the copied tree, filtered by a short exclusion list**:
   - `docs/plans/`;
   - `.pytest_cache/`;
   - `docs/journals/` if its content proves to be noise;
   - the root `README.md` — the `docs` stage copies only the root
     `CLAUDE.md`, not `README.md`, so `README.md` is absent from the copied
     tree and would fail the closed-world test below (neither indexed nor
     excluded) unless it is listed here or copied and indexed on purpose;
   - the other root stray files (`HOST_ARRAY_AUDIT.md`,
     `PR_REVIEW_CARRYOVER.md`).

   Each module's `CLAUDE.md` and extra `*.md` then come in automatically.
3. **A packaging test** (alongside `test_dockerfile_packaging.py`) asserts
   that every `*.md` **and every `*.html`** in the repository is either
   indexed or matched by the exclusion list. HTML must be covered because
   `DocsRetriever` indexes HTML too (`docs/modules/*.html` and
   `docs/tutorials/*.html`, per 1.2) — a test that checks only Markdown would
   let a newly added HTML document be copied into the image yet stay invisible
   to retrieval without failing the invariant. The exclusion list likewise
   covers both suffixes (`docs/index.html` is the one HTML file not indexed
   today, so it is either indexed by the walk or named in the exclusions).
   Coverage then fails closed: a new document is either searchable or excluded
   on purpose.

**Trade-off.** A larger index means more fuzzy-match candidates competing
for 5 chunks, so precision can drop. Section-aware chunks (2.9) and the
evaluation set (section 5) are how that gets measured instead of guessed.
**The `_read_file` cap blocks the first fix by itself.** `_read_file`
truncates at 50 000 characters. `.github/CLAUDE.md` is 1157 lines and
69 653 characters, so the cut falls at line 839 — *before* the QA-round
prefixes (around line 856) and `MAX_OPEN_PRS` (around line 891). Copying the
file is therefore not enough to fix E1. The cap has to rise, or (better)
large files have to be read whole and chunked by section. That is part of
step 2 in section 6.

### 3.3 `docs/plans/`

Plans are temporary and often describe intended, not actual, behaviour.
Indexing them as tier A would plant future-tense claims as present fact.
Options:

1. keep them excluded (today);
2. index them as their own tier, `PLAN (may describe unimplemented
   work)`, treated like B′.

Recommendation: keep them excluded until the tier machinery exists, then
decide using the evaluation set (open question Q4).

---

## 4. Multi-turn consultation

### 4.1 Why

Callers often need a follow-up: "and what exit code?", or "is that still
true on master?". Today each call is stateless. A follow-up has to repeat
the whole question, retrieval starts from nothing, and the caller cannot
point at evidence from the previous answer ("N2 — is that branch merged?").

### 4.2 API

- `consult` gains `conversation_id: str = ""`. If it is empty, a new
  conversation starts. The response returns `conversation_id` and `turn` for
  every *stateful* call — that is, every call made with a job temp token.
  The one exception is a static-token caller, which is always stateless
  (4.5): such a call returns neither field, and a non-empty
  `conversation_id` passed on a static token is rejected. So the two fields
  are present whenever the caller is capable of a conversation and absent
  otherwise, and the parameter/response tests assert exactly that split
  rather than treating the fields as unconditionally mandatory.
- No new tool in the first step. Expiry ends conversations. A later
  `consult_transcript(conversation_id)` read tool may be added for the exit
  interview (4.6). It would follow the full new-tool checklist in
  `tools/mcp/CLAUDE.md`.

### 4.3 What a follow-up turn does

1. **Retrieves fresh evidence** for the new question (with keywords, as
   today).
2. **Carries forward** evidence the previous turn cited: up to 2 doc chunks
   and 2 notes, keeping their IDs. IDs are stable within a conversation
   (`D1` stays `D1`), so the caller and the model can refer back to them.
3. **Adds a condensed history:** previous questions verbatim, previous
   answers truncated to a fixed budget (proposed: 1500 characters in total).
   The first turn's documentation budget is kept, so history never pushes
   out retrieval.
4. Tiers are recomputed every turn. Branch state can change while a
   conversation runs, and the cache TTL takes care of that.

### 4.4 State, storage and expiry

State per conversation:

- id;
- caller identity — the workstream and job resolved from the HMAC temp
  token (`auth.py` labels these `tmp:<workstream>/<job>`);
- repo and branch scope;
- the turn list: question, keywords, answer, cited evidence records,
  response flags, timestamps;
- the evidence pool with its stable IDs;
- created and last-used times.

**First step: in-process store.** A dict with LRU limits and timers:

- idle TTL 30 minutes;
- absolute TTL 4 hours;
- at most 12 turns;
- at most a few hundred conversations.

ar-manager serves synchronous tools on a thread pool (`auth.py` documents the
thread-local fallback it keeps "if contextvars don't propagate into FastMCP's
sync-tool thread pool"), so two consult calls can touch this store at once.
The store must therefore be synchronized: a single lock (or an equivalent
atomic store) guards the whole read-modify-write of each operation, so that
the identity/ownership check, the turn allocation and the LRU/TTL eviction
for a conversation happen atomically. Without it, two concurrent turns on the
same `conversation_id` can race and interleave a turn across callers or
corrupt the turn list. The ownership check in particular must be inside the
same critical section as the mutation it guards, not a separate step before
it.

**Serialize the turn, not only each store access.** Making each individual
read-modify-write atomic stops the turn *list* from being corrupted, but it
does not serialize a *consultation* against itself. A turn is not one store
access — it reads the prior state, then retrieves and synthesizes (a slow,
unlocked step), then commits. If the per-access lock were the only guard, two
turns submitted on the same `conversation_id` could both read the same prior
state, both synthesize against it, and then commit in either order; the later
commit would allocate turn N+2 from a snapshot that never saw turn N+1,
breaking the "previous questions verbatim / IDs stable within a conversation"
contract (4.3) even though no list was corrupted. Two ways to close this, in
order of preference:

- a **per-conversation operation lock** held across the whole turn
  (read → retrieve/synthesize → commit) so turns on one conversation run one
  at a time, while different conversations still run concurrently. The lock is
  per-id, not the single store lock, so synthesis does not serialize the whole
  server;
- or an **optimistic version check**: each conversation carries a version (its
  turn count); a commit that carries a stale version is rejected with
  `{ok: false, error: "conversation busy", next_steps: ["retry the follow-up"]}`
  rather than appended out of order.

The first step should take the per-conversation lock — it is the simpler of
the two to reason about and a consultation turn is short enough that briefly
serializing a single conversation is acceptable. The version check is noted as
the alternative for if holding a lock across synthesis proves too coarse.

When a conversation has expired or the server restarted, an unknown id
returns
`{ok: false, error: "conversation expired", next_steps: ["start a new consult without conversation_id"]}`.
It never silently starts a new conversation under the old id. ar-manager
runs as a single container process, so in-process state is consistent. A
restart loses live conversations, which is acceptable for an interactive
convenience.

**Later: durable transcripts.** Store a finished or expired conversation's
transcript in ar-memory under a **non-semantic namespace** (for example
`consultations`, added to `AR_MEMORY_NON_SEMANTIC_NAMESPACES`), keyed to the
caller's repo and branch. It is then retrievable by branch and recency, like
`messages`, without polluting semantic recall. Two things to resolve:

- **Retention.** Proposed: 90 days, with a pruning job. The memory store
  has no TTL mechanism today.
- **The archived-workstream guard.** `memory_store` refuses writes to
  archived rounds. A transcript written on expiry after the round was
  archived would be refused the same way, so transcripts should be
  written at turn time, not at expiry.

### 4.5 Isolation and safety

- A conversation can be continued or read only by the identity that started
  it. A job's temp token cannot read another job's conversations.
- **Static tokens get no conversations in the first step.** The auth
  middleware records only a static token's scopes and its configured
  `label`; there is no per-caller subject, and one static token can be
  shared by several callers (an operator's Slack flow and a script, say).
  Binding a conversation to the label would let any holder of the token
  continue or read another holder's conversation. So a static-token call
  that passes `conversation_id` is rejected with
  `{ok: false, error: "conversations require a job token"}`, and a
  static-token call without one stays stateless (no `conversation_id` is
  returned). Opening conversations to static tokens needs a real per-caller
  credential binding first (open question Q10).
- `context` (code snippets, error output) is held in state. Transcripts that
  are persisted must go through the same secret-redaction expectations as
  logs (`tools/mcp/CLAUDE.md`, "Security Rules for Workspace Secrets").
  Leaving out the raw `context` is necessary but **not sufficient**: the
  caller can paste a credential into the `question`, and the model can echo
  a credential from `context` into the `answer`. So **every persisted text
  field** — question, keywords, answer, `history`, conflict summaries and
  citation-warning sentences — passes through one redaction function before
  it is written, and a turn in which redaction cannot run (for example it
  raises) is not persisted at all rather than persisted raw. The raw
  `context` is still never persisted. Which patterns that redaction function
  recognises (known token prefixes, `KEY=value` shapes, values of workspace
  secrets the server can see) is part of the durable-transcript step, not of
  the in-process first step, which persists nothing.
- A conversation is read-only towards memories. Nothing in it writes a
  note.

### 4.6 What a future exit interview will need from consult (not a design)

The exit interview — a multi-turn conversation at the end of a job in which
the agent gives feedback, consumed later by the QA retrospective round —
will need:

1. **Conversations bound to a job id**, not only to a token, and retrievable
   after the job ends.
2. **Durable transcripts** that outlive the idle TTL, with a read path the
   retrospective round can use: list by workstream or time range, then read
   one. This is the `consultations` namespace and `consult_transcript` above.
3. **Conversation state kept separate from the consult persona.** The
   interview asks questions instead of answering them, so it needs its own
   system prompt and turn policy on the same conversation machinery. Build
   the store and the turn loop as a component that consult *uses*, not as
   logic inside `consult`.
4. **Turn-level evidence records.** Feedback such as "consult told me X and
   it was wrong" can then be joined to the exact evidence items and tiers
   that produced X. That makes interview output usable as evaluation cases
   (section 5) and as staleness signals (2.8).
5. **A retention and redaction policy** decided once, for both uses.

---

## 5. An evaluation set

### 5.1 Purpose and principles

Changes to consult should be judged by measurement. The set is:

- **small** (tens of cases, not hundreds);
- **version-controlled**;
- **deterministic in what it checks**;
- **honest about the model being non-deterministic**: each case runs k
  times and reports a pass fraction.

### 5.2 Layout

```
tools/mcp/manager/eval/
  __init__.py                 makes eval/ a package so unittest discovery reaches it
  consult_cases.json          the cases (JSON: the manager image has no YAML dependency)
  fixtures/memories/*.json    frozen memory sets per case
  run_consult_eval.py         the runner
  test_consult_eval.py        unit tests of the graders and the case schema
  baselines/                  committed reports, one per behaviour-changing step
```

The `eval/` subdirectory keeps these files out of the image's
`COPY tools/mcp/manager/*.py` glob, and out of the `*_tools.py` tool
discovery.

### 5.3 Case schema

```json
{
  "id": "E2",
  "question": "What does validate-agent-commit.sh block and what exit code does it use?",
  "keywords": ["validate-agent-commit.sh", "agent-commit-validation"],
  "observed": {"date": "2026-10-17", "summary": "right description; 'exit 2' cited to claude-code-job.md:330 (detect-test-hiding.sh)"},
  "memories": "fixtures/memories/E2.json",
  "expect": {
    "outcome": "answer",
    "must_include": ["test files", "base branch", "no new test"],
    "must_include_any": [["exit 3", "exit code 3"], ["not documented", "does not state"]],
    "must_not_include": ["exit 2", "exit code 2"],
    "must_not_cite": [{"file": "flowtree/runtime/docs/claude-code-job.md", "lines": [320, 345], "with": "validate-agent-commit"}],
    "basis_in": ["documentation", "documentation+notes"]
  }
}
```

Grader vocabulary:

- `outcome`: `answer`, `abstain` or `nonexistent`.
- `must_include` and `must_not_include`: case-insensitive regexes.
- `must_include_any`: a list of alternative groups, at least one per group.
- `must_cite_any`: files.
- `must_not_cite`: a file and line range, optionally limited to sentences
  containing a token.
- `must_flag_stale`: symbols that must appear in `evidence[].named_symbols.absent`.
- `basis_in`: the allowed `answer_basis` values.
- `max_citation_warnings`.

The graders read the structured response fields first and the prose second.
That works before and after the response-shape changes: a missing field
counts as a fail only for expectations that need it.

### 5.4 Run modes

- **Local (reproducible; the default for measuring a change):**
  - builds the corpus with the *same* logic as the Dockerfile's `docs`
    stage, so coverage fixes are measured as shipped. This argues for
    moving that logic into a script that both the Dockerfile and the runner
    call;
  - injects the case's frozen memories through a stub memory client;
  - runs `consult` in-process against the backend selected by
    `AR_CONSULTANT_BACKEND`.

  Freezing the memory fixture alone is **not** enough to make a run
  reproducible once tiering lands (step 5 on). Tier assignment also reads live
  state: the GitHub PR/branch-state lookup (2.4), the current date against the
  recency window (2.4), the caller's scopes, and the corpus identity
  (`commit`, `built_at`). Any of these drifting between two local runs can flip
  a case's tier and therefore its outcome, which would contradict the
  "reproducible" claim. The local mode therefore also freezes them:

  - a **frozen clock** (an injected "now") so the recency window is evaluated
    against a fixed date, named per case;
  - a **branch-state fixture** that stubs the PR lookup — each fixture note's
    `branch_state` (and, where a case exercises reuse, the previous-PR history)
    is supplied by the case, not fetched from GitHub, so no network call and no
    live PR state enters a local run;
  - a **fixed caller scope** (which `github`/`memory-read` scopes the stubbed
    caller holds), since that gates whether branch state is looked up at all
    (2.4);
  - a **pinned corpus identity** (`commit`/`built_at`) matching the corpus the
    run builds, so `corpus.*` and any snapshot-relative tier rule are stable.

  A case may leave any of these unspecified only when its expectation does not
  depend on it (the graders already treat a field a case does not assert as
  not-checked, 5.3).
- **Live (spot check, not reproducible):** calls a deployed ar-manager over
  MCP HTTP with the real memory store. It is useful for confirming that a
  deployment behaves like the local run. Its numbers are not compared
  across runs.
- **Reporting:**
  - per case: pass fraction over k runs (default k=3) and which
    expectations failed;
  - overall: pass rate, false-abstention count and false-answer count,
    reported separately (2.10), and citation-warning rate;
  - the header records backend name, model, corpus commit and case-file
    hash.
- **Optional judge:** `tools/mcp/consultant/qc/judge_answers.py` can score
  free-text correctness as a second signal. The deterministic graders are
  the gate.
- **CI:** the model-backed run is not in CI, because it needs a model.
  `test_consult_eval.py` (schema validity, plus graders run against canned
  answers that include today's observed failures) runs wherever the other
  manager Python tests run. The CI step for those is
  `python3 -m unittest discover -v -s tools/mcp/manager -p "test_*.py"`
  (in `.github/workflows/analysis.yaml`). `unittest` discovery recurses only
  into directories that are importable packages — the existing
  `tools/mcp/manager/tests/` is reached because it has an `__init__.py` — so
  `eval/__init__.py` is required, not optional; without it the tests exist
  but never run in CI. Step 1 should confirm the test is reached by running
  that exact discover command locally and checking the test appears in its
  output.

### 5.5 Seed cases (observed 2026-10-17)

| ID | Question | Correct answer (verified against the tree) | Today | Expectation |
|---|---|---|---|---|
| **E1** | Which QA rounds does Master Agent Dispatch run, their branch prefixes, and the open-PR limit? | The jobs are `doc-qa`, `defect-hunt`, `coverage-qa`, `consolidation-qa`, `performance-qa`, `pdsl-qa` and `qa-retrospective`. Their prefixes are `qa/docs-`, `qa/defect-`, `qa/coverage-`, `qa/consolidate-`, `qa/performance-`, `qa/pdsl-` and `qa/retro-`. The open-PR limit is `MAX_OPEN_PRS` = 7, enforced by `tools/ci/open-pr-backlog.sh` for the planning rounds. Sources: `.github/CLAUDE.md` § "What the Master Agent Dispatch workflow does" and around line 891; `.github/workflows/master-agent-dispatch.yaml`. | "Not documented", even though three relevant memories were retrieved | Milestone-dependent (the implementation order gates it). Baseline through step 7: `outcome: abstain` — today's false abstention, and notes are not yet allowed to answer (step 5 keeps them from answering). After step 2 (coverage fix: `.github/CLAUDE.md` copied, the read cap lifted): `outcome: answer` citing `.github/CLAUDE.md`, including all seven prefixes and `7` — the intended fix, not dependent on notes. After step 8 (notes may answer): were coverage still absent, `outcome: answer` with `answer_basis: notes` from the fixture's B-tier notes would also be acceptable. The grader selects the expectation by the milestone the run targets. Fixture: the three memories retrieved on 2026-10-17. |
| **E2** | What does `validate-agent-commit.sh` block and what exit code does it use? | It rejects a change set whose only content is edits to test files that exist on the base branch (at the merge-base), with no new test, and exits **3** (`block_reason=only_base_test_edits`). Sources: `tools/ci/README.md` script table; root `CLAUDE.md`; the script's final `exit 3`. | Correct description; "exit 2" cited to `claude-code-job.md:330` (the `detect-test-hiding.sh` section) | `outcome: answer`. `exit 2` forbidden. Citing that range for an exit-code claim forbidden. Either `exit 3` with a citation to the script or index, or an explicit statement that the exit code is not documented. Measures citation integrity first, then the script-header index. |
| **E3** | Where does `ObjectOrientedDesignRule` record its verdicts? | `.flowtree/oop-review.md` (`ObjectOrientedDesignRule.VERDICT_PATH`). No document states it. | Guessed from an unrelated April memory about `runCorrectionSession`; surfaced April memories naming `ClaudeCodeJob` (deleted 2026-05-16) | `outcome: answer` (once constants are indexed) or `abstain`. `runCorrectionSession` must not appear as the answer. Must flag `ClaudeCodeJob` as absent (`must_flag_stale`). Fixture: the April memories. |
| **E4** | How does `check-branch-entropy.sh` decide drift? | No such script exists anywhere in the tree. | "Not documented" (correct) | `outcome: abstain`, and `nonexistent` once the path index lands. Any substantive description fails. **Regression guard:** every step must keep this passing. |

Proposed additional cases, each tied to a mechanism in this plan, to be
added with the step that needs them:

- **E5 (namespace widening):** a fact that exists only in a `decisions`
  namespace note.
- **E6 (conflict):** two B′ notes from different open branches that state
  different values for the same setting. The conflict must be reported and
  must not be resolved silently.
- **E7 (branch-local):** a fact that is true only on an unmerged branch,
  asked as a question about master. The answer must not assert it for
  master.
- **E8 (history):** a "why is it like this" question whose rationale is
  only in a C note. The rationale belongs in `history`, not as
  present-tense fact.

Future exit-interview feedback (4.6) is a natural source of further cases.

---

## 6. Proposed order of implementation

Each step is sized for one job, leaves consult working, and (from step 1
on) is measured against the evaluation set before and after. The ordering
is by risk: **measure, then widen coverage, then make the evidence visible,
then verify citations, and only then let notes answer.**

1. **Evaluation harness and seed cases.** Add `eval/` with E1–E4, frozen
   memory fixtures, the runner (local mode), the graders and their unit
   tests. Commit a baseline report of today's behaviour. No change to
   consult.
2. **Corpus coverage, first fix.** Copy `.github/` and `.claude/` in the
   Dockerfile. Add `.github/CLAUDE.md`, `.github/CI_ARCHITECTURE.md`,
   `tools/ci/README.md` and `docs/COMPUTATION_MODEL.md` to
   `STANDALONE_MD_ROOTS`. Raise or remove the `_read_file` 50 000-character
   truncation, which today cuts `.github/CLAUDE.md` off at line 839, before
   the facts E1 needs. Re-run the evaluation; E1 should pass.
3. **Corpus snapshot identity.** Add the `CORPUS_COMMIT` build arg (default
   `unknown`), pass it from both image builds (`analysis.yaml` and the
   controller `docker-compose.yml`), and return `corpus: {commit, built_at}`
   in the consult response. This is small, but everything in section 2
   refers to "the snapshot". The `analysis.yaml` half needs a `ci/...`
   branch (2.5).
4. **Memory retrieval fixes.** Search all semantic namespaces by client-side
   fan-out and merge (2.13, option 1); query with question plus keywords;
   retrieve 8 and present 3–5; return `id`, `created_at`, `branch` and
   `namespace` in `related_memories`; label distance correctly in
   `memory_recall`. Add E5.
5. **Provenance and tiers, without the index.** Add `branch_state` lookup
   with a PR-keyed cache, gated on the caller's `github` scope and degrading
   to `unknown` otherwise (2.4), the pure `tier()` function with unit tests, the evidence
   list with IDs in the prompt, `SYSTEM_PROMPT` tier rules and examples,
   and `evidence` in the response. Notes are *still not* allowed to answer;
   only presentation changes. Add E6 and E7.
6. **Citation by ID, plus section-aware chunks.** The model cites
   `[Dk]`/`[Nk]`, the server expands them to `file:start-end`, and chunks
   carry heading trails.
7. **Citation verifier.** Subject and value checks, `citation_warnings`,
   `uncited_claims` and inline `[unsupported]` marks. E2 should now fail
   *loudly* (flagged) instead of silently.
8. **Notes may answer.** Add the B-tier answer contract, the code-generated
   basis sentence, the `answer_basis` and `BASIS:`/`CONFLICTS:` trailer
   parsing, the `history` field, the rework of the "Not documented"
   post-processing, and `abstained_with_evidence`. Add E8. Confirm that E4
   still passes and that the false-answer count did not rise.
9. **Snapshot index, version 1.** Paths, Java type names and script names
   computed in the Dockerfile `docs` stage before the prune and written
   outside the pruned tree (`COPY scripts/` added to that stage), plus a
   packaging test that the index ships in the final image. Mention
   extraction, the C✗ tier, structural conflicts and the `nonexistent`
   abstention. E3's `must_flag_stale` and E4's `nonexistent` should pass.
10. **Corpus coverage, systematic fix.** Replace the index allowlist with a
    walk of the copied tree plus an exclusion list, and add the packaging
    test that every `*.md` and `*.html` is indexed or excluded. Re-measure
    precision.
11. **Multi-turn, in-process.** The `conversation_id` parameter (with
    `McpToolDiscoveryTest` assertions), the store with TTL and LRU,
    per-conversation turn serialization (4.4), evidence
    carry-forward with stable IDs, the history budget, and identity
    binding to job temp tokens (static tokens refused, 4.5). Build the store
    as a separate component (4.6, item 3).
12. **Snapshot index, version 2.** Script header comments and exit codes,
    and `static final String` literal constants of any visibility, presented
    as tier-A `[Ik]` index-derived evidence items (2.5) so the answers are
    cited and grounded like documentation. E2 should answer `exit 3` from the
    index and E3 should answer `.flowtree/oop-review.md`.

Later refinements, not yet scheduled:

- per-path last-modified dates and the change-since-note recency signal;
- durable `consultations` transcripts and `consult_transcript`;
- value-level conflict detection;
- verifier-driven regeneration;
- feeding conflicts consult finds into `verification_status` once the
  memory schema migration lands;
- a decision on indexing `docs/plans/`;
- the rename map.

---

## 7. Open questions

1. **Recency window.** Is 45 days a reasonable first default for "current"
   versus "historical", or should the first step skip age entirely and
   classify by branch state alone until the change-since-note signal
   exists?
2. **Notes answering.** Is a note-based answer, clearly labelled as such,
   wanted at all? Or should consult only *point at* the note ("not
   documented; note N1 from branch X says …") and let the caller judge? The
   plan assumes the labelled answer.
3. **`.claude/` in the corpus.** Hook and agent docs help agents understand
   blocks, but they also describe the enforcement machinery in detail. Is
   that an acceptable thing for consult to explain to any caller?
4. **`docs/plans/`.** Keep it excluded, or index it as its own
   "may describe unimplemented work" tier?
5. **Source-derived facts.** How far should the snapshot index go into
   source — script headers and string constants (as planned), or further,
   to public method signatures? Each step costs image size and index
   maintenance.
6. **Evaluation model.** Which backend and model should be the reference for
   committed baselines, given that the deployed backend is chosen by
   `AutoBackend` at runtime?
7. **Transcript retention.** For the durable `consultations` namespace,
   what retention period is acceptable, and is redacting every persisted
   field (4.5) — with raw `context` never persisted — a strong enough line,
   or should transcripts keep only evidence IDs and response flags, with no
   free text at all?
8. **Who acts on stale flags.** Consult only *reports* that a note names
   absent symbols. Should something eventually mark such notes in the
   memory store (via the `SCHEMA_EVOLUTION_PROPOSAL.md` lifecycle)? If so,
   should that be a QA round, the retrospective round, or a separate
   maintenance job?
9. **Merge state for `memory-read`-only callers.** The plan gates the
   branch-state lookup on the caller also holding the `github` scope (2.4),
   so those callers get coarser tiers. Is merge state of a branch sensitive
   enough to warrant that, or should consult be allowed to derive it for
   every caller (and document that it does) so tiering is the same for
   everyone?
10. **Conversations for static-token callers.** Static tokens carry no
    per-caller subject, so the plan refuses conversations for them (4.5).
    Is that acceptable for the operator-facing clients that use static
    tokens, or does the auth layer need per-caller credentials first?
