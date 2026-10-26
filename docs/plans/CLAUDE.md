# docs/plans/ — Planning & Investigation Documents

This directory holds **temporary** working documents: design studies, investigation
notes, and implementation plans. They capture in-progress thinking and are expected
to change, be superseded, or be deleted once the work they describe lands (or is
abandoned). They are not durable API documentation.

## RULE: Every plan carries a lifecycle header

Every Markdown plan here starts with this front matter:

```
---
status: proposed
created: 2026-10-23
closed:
tracker: [9856a34e-658f-4e82-ad5b-6d671c979f2f]
---
```

- `status`: `proposed`, `approved`, `in-progress`, `completed` or `abandoned`.
- `created`: the ISO date the plan was written.
- `closed`: the ISO date it became `completed` or `abandoned`. Set exactly when the
  status is one of those two, and empty otherwise.
- `tracker`: the ids of the tracker tasks the plan carries, or `[]`.

Exempt: this file, `MANAGER_LOG.md`, everything under a release-version directory
(`docs/plans/<version>/`, managed by goal decomposition), and non-Markdown files such as
`*-workstream.yaml`.

The header is what lets finished plans be removed without studying each one:

- **Writing a plan.** The planning prompts and `project_commit_plan` write the header
  (`project_commit_plan` adds a `proposed` one when the content has none and refuses a
  malformed one). A plan written by hand needs it too:
  `tools/tests/test_sweep_plans.py` fails CI for any plan without a valid header.
- **Finishing a plan.** When the work is done, set `status: completed` and `closed` to
  today. A plan dropped without being carried out gets `status: abandoned` and `closed`.
  Do not delete it yourself.
- **The sweep.** `tools/ci/sweep-plans.py`, run by the documentation QA round, removes
  plans closed more than 14 days ago. It also lists plans with no commit in 60 days, and
  open plans with tracker tasks, so each can be checked and closed if its tasks are.

Prefer a tracker task to a plan. A plan is for work too detailed or multi-step to state in
a task description; anything smaller belongs on the tracker, where the automated rounds
pick it up.

## RULE: Never reference these documents from code you intend to keep

Because documents in `docs/plans/` are temporary, **no code, test, or durable
documentation that is meant to persist may refer to a file in `docs/plans/`** —
not in Javadoc, not in comments, not in string constants, not in error messages.

When a plan document is deleted or renamed, any such reference becomes a dangling
pointer to something that no longer exists. The reference cannot be cleaned up
together with the document (the document's deletion and the referencing file may
live on different branches, or the referencing file may be a base-branch file an
agent must not modify), so the rot is left behind silently.

If you need to record *why* a piece of code exists or how it works, put that
explanation **in the code itself** (Javadoc on the class/method) or in durable
documentation outside `docs/plans/`. A plan document may freely reference code; the
dependency must never point the other way.

### Allowed

- A plan document referencing code (`SomeClass`, `path/to/File.java`).
- One plan document referencing another plan document.
- A commit message or PR description referencing a plan document.

### Not allowed

- Javadoc/comments in `src/**` referencing `docs/plans/SOMETHING.md`.
- A retained test asserting on, or documented in terms of, a `docs/plans/` file.
- Any string constant or resource shipped in an artifact that names a
  `docs/plans/` document.
