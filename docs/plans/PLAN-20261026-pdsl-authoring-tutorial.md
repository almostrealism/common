# Teach PDSL model authoring with a worked tutorial

**Category:** Documentation
**Branch:** `project/plan-20261026-165814`
**Estimated Complexity:** Medium

## Title

Add a progressive, worked PDSL tutorial to the `docs/tutorials/` series — teaching a
reader to *read* and then *write* a complete PDSL language-model definition — backed by
a drift-guard test that parses every PDSL block the page shows.

## Category

Documentation. The reference corpus is excellent, but the *teaching* path has a concrete,
long-standing gap: the tutorial series (`docs/tutorials/01`–`09`) covers vectors,
`PackedCollection`, hardware setup, computation graphs, ML inference, signal processing,
testing, process optimization, FlowTree setup and workspace secrets — but **nothing teaches
PDSL**, the declarative language in which the platform states *what its models compute*.
A learner who wants to author or modify a model has a complete normative reference
(`docs/internals/pdsl-language.md`) and nineteen shipped `.pdsl` assets to read, but no
guided, progressive path from "I have never seen PDSL" to "I wrote a small model that
parses and runs."

## Motivation

PDSL is the single most important authoring surface on the platform. The 2026-10-23 cycle
that landed the language reference stated the reason plainly: a PDSL page is "the most
generative page we can add to the corpus the self-hosted LM trains on: a model that can
read the language can read the model files, and later propose edits to them — a concrete
step toward software that understands its own architecture." The reference is the normative
half of that; a *tutorial* is the pedagogical half, and it is the half a human (or a model
learning from prose and worked examples) reaches for first.

This connects to the deepest aim in the vision — software that understands and can grow its
own architecture. The models the platform trains are written in PDSL. Teaching PDSL well,
in the same narrative register as the rest of the tutorial series, is therefore both a
human-onboarding asset and training-corpus material for the self-hosted LM. It is the kind
of documentation whose value compounds: every future reader (and every future training run
that ingests the docs) benefits.

The task is also **reachable on any backend right now.** It depends on nothing that is
unbuilt. The ambitious research/education programme in
`docs/plans/PDSL_FOR_RESEARCH_AND_EDUCATION.md` is blocked on unimplemented features
(a token-embedding primitive, a standalone runner, weight-key resolution, a real Qwen
checkpoint the agent cannot download). This tutorial deliberately stays inside what already
works and ships: the parser, `PdslLoader`, and the already-shipped, already-tested `.pdsl`
assets. It is the teaching asset that programme will eventually want, written against
today's language rather than tomorrow's.

## Scope

Concrete, actionable deliverables:

1. **A new tutorial page** `docs/tutorials/10-pdsl-model-definitions.html`, matching the
   structure, CSS (`../css/style.css`), header/nav and progressive "Step N" format of the
   existing tutorials (see `05-ml-inference.html` as the closest sibling). Target a depth
   comparable to tutorials 04/05 (roughly 350–500 lines of HTML). It teaches, in order:
   - **What PDSL is and why it exists** — "Java is orchestration"; PDSL is where *what the
     model computes* is written, so the text can look like the picture of the data flow.
     One short paragraph, linking to `docs/internals/pdsl-language.md` as the full reference
     so the tutorial never tries to be a second reference.
   - **Reading a layer** — walk through a real shipped asset
     (`engine/ml/src/main/resources/pdsl/feed_forward.pdsl` is the smallest self-contained
     one), line by line, naming each construct (`dense`, `rmsnorm`, `product` as a gate,
     `accum { }` as a residual, `branch { }` as a side path) and pointing at the shape
     annotations that make the file readable. Keep every construct's description to one
     sentence and defer the formal semantics to the reference.
   - **Reading attention** — a guided read of `attention.pdsl` / `sdpa.pdsl`: the QKV
     projections, RoPE, the per-head structure, and the KV-cache parameters, with shapes.
   - **Composing a layer** — how `transformer.pdsl` assembles norm + attention + feed-forward
     into one `transformer` definition, and how a model file loops over layers.
   - **Writing your own small model** — build up, step by step, a minimal **complete**
     decoder definition that *parses* (and, where it needs no unbuilt primitive, loads via
     `PdslLoader`), ending in a single on-page `.pdsl` program the reader has watched grow.
     This mirrors the `tiny_decoder` worked example at the end of the reference but arrives
     at it progressively rather than presenting it whole.
   - **Running it from Java** — the minimal `PdslLoader` snippet that loads a `.pdsl` file
     and the one-line reminder that the embedding lookup, the generation loop and sampling
     stay in Java (the `AutoregressiveModel` family), exactly as the real models do.
   - **Where to go next** — links to the reference, to `pdsl-audio-dsp.md`, to the shipped
     assets, and a short "common first mistakes" list (shape mismatches, forgetting a norm),
     framed as learning aids.

2. **A drift-guard test** in `engine/ml` — `PdslTutorialExamplesTest` (extends
   `TestSuiteBase`, `@Test(timeout = ...)`), modeled directly on the existing
   `PdslLanguageReferenceExamplesTest`. It locates `docs/tutorials/10-pdsl-model-definitions.html`
   by walking up from `user.dir` (the same mechanism the reference test uses), extracts every
   PDSL program the page presents as complete, and asserts each one **parses** with
   `PdslParser` (and, for the final complete model, loads via `PdslLoader` and has the shape
   the prose claims). This makes the tutorial's code physically unable to drift away from a
   language the parser still accepts — the project's standard for shipped doc examples
   ("every snippet shown publicly must be a file that a test loads and runs").
   - To make extraction unambiguous in HTML, each complete on-page program is wrapped in a
     stable, greppable marker (e.g. a `<pre class="pdsl-program">` class or an HTML comment
     delimiter the test keys on), decided during implementation to match whatever the
     existing reference test's fenced-block convention maps to most cleanly in HTML.

3. **Index + cross-link wiring:**
   - Add the tutorial to the `#tutorials` list in `docs/index.html`.
   - Add a "Related documentation" cross-link from `docs/internals/pdsl-language.md` to the
     new tutorial (reference → tutorial), and from the `engine/ml` README's PDSL section.
   - Mention it in `llms.txt` alongside the existing PDSL reference entry.

### Out of scope (explicitly)

- No changes to the PDSL language, parser, interpreter, or any `.pdsl` asset. If the tutorial
  surfaces a defect or a rough edge, it is **recorded** (memory + a follow-up tracker task),
  not fixed here.
- None of the unbuilt `PDSL_FOR_RESEARCH_AND_EDUCATION.md` features (token-embedding
  primitive, runner, weight resolution, steering/probe/LoRA interventions). The tutorial
  teaches today's language; the research programme is a separate track.
- No real-checkpoint parity work (needs a Qwen download the agent cannot perform).

## Approach

1. **Ground every claim in source first.** Read the chosen assets (`feed_forward.pdsl`,
   `attention.pdsl`, `sdpa.pdsl`, `transformer.pdsl`) and the reference's grammar/builtin
   catalog. Every construct the tutorial names must be verified against `PdslBuiltins` and
   the parser, not described from memory. Consult `pdsl-language.md` for the normative
   wording and *link* to it rather than restating it.
2. **Write the drift-guard test alongside the prose, not after.** Each time a complete PDSL
   program is added to the page, add its extraction+parse assertion to
   `PdslTutorialExamplesTest`. The final model program must parse and (where no unbuilt
   primitive is required) load. This keeps the page honest as it is written.
3. **Keep the register of the existing tutorials.** Short paragraphs, numbered steps,
   shapes shown, prerequisites listed (Tutorials 2 and 5), nav back to the index. The
   tutorial is narrative and progressive; the reference stays the place for exhaustive
   grammar and the full builtin table.
4. **Verify.** Run `PdslTutorialExamplesTest` (and `PdslLanguageReferenceExamplesTest` to
   confirm the shared walk-up/parse mechanism still holds) via the MCP test runner, and run
   the build validator (checkstyle, code_policy, test_timeouts, duplicate_code) before
   declaring done. The test's only runtime dependency is the repo checkout, which holds in CI.

## Success Criteria

- `docs/tutorials/10-pdsl-model-definitions.html` exists, renders in the same style as the
  series, and takes a reader from "never seen PDSL" to a complete small model they have
  watched being written — reading real shipped assets along the way.
- Every PDSL program shown as complete on the page is parsed by `PdslTutorialExamplesTest`;
  the final model program also loads via `PdslLoader`. The test passes.
- The tutorial is linked from `docs/index.html`, from `pdsl-language.md`, from the `engine/ml`
  README, and from `llms.txt`.
- The tutorial restates none of the reference's normative grammar/builtin tables; it links to
  them. (Checked in review: no duplicated reference tables.)
- Build validator clean; no language/parser/asset code changed; any defect found is recorded
  as a follow-up, not fixed here.

## Dependencies

None blocking. Builds on the already-merged PDSL language reference
(`docs/internals/pdsl-language.md`) and the shipped `.pdsl` assets. The drift-guard test
reuses the mechanism already proven by `PdslLanguageReferenceExamplesTest`. Runs on any
backend — no Metal, no workspace settings file.

## Estimated Complexity

Medium. One substantial HTML page grounded in source, one focused drift-guard test mirroring
an existing one, and index/cross-link wiring. The risk is scope discipline (staying a
tutorial, not a second reference) and extraction robustness in HTML, both addressed above.
