# docs/plans/ — Planning & Investigation Documents

This directory holds **temporary** working documents: design studies, investigation
notes, and implementation plans. They capture in-progress thinking and are expected
to change, be superseded, or be deleted once the work they describe lands (or is
abandoned). They are not durable API documentation.

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
