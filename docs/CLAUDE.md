# Formal Documentation Only

**TEMPORARY PLANNING ASSETS DO NOT BELONG HERE.**

This is a formal documentation directory — it holds durable, reviewed documentation
(architecture, internals, module guides, API references). Do NOT place temporary or
work-in-progress planning documents, investigation notes, design discussions, or
"revisit later" memos loose in here.

Those belong in **`docs/plans/`** (the `plans/` subdirectory). If you are writing a plan
or a note to discuss again later, create it under `docs/plans/` — not at the docs root or
in any module `docs/` directory.

---

# Do Not Cite Source Line Numbers

**Never anchor documentation to source line numbers** — neither in prose
(`AutoregressiveModel.java:298-311`, "lines 1165-1195") nor in the header comment
of a code snippet (`// AttentionFeatures.java:1287-1302`). Line numbers drift the
moment anyone edits the referenced file, so a doc that cites them is stale as soon
as the next commit lands and becomes a recurring maintenance burden: every edit to
the source silently invalidates the citation, and nothing flags it.

Reference source by **stable identifiers** instead — the class, method, field, or
PDSL layer name — which survive edits and let a reader locate the code by search:

- Prose: "the `next()` method of `AutoregressiveModel`", "`sequenceCrossAttention`
  in `AttentionFeatures`".
- Snippet headers: `// AutoregressiveModel.next()`, `// DiffusionTransformer.prependConditioning`.

Naming a file (without a line number) is fine — e.g. the module-relative paths in a
"Related Files" table. It is the volatile `:NNN` / "lines NNN-NNN" suffix that must
be omitted.
