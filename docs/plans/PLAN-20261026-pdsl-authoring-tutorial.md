---
status: completed
created: 2026-10-26
closed: 2026-10-27
tracker: []
---

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
`docs/plans/PDSL_FOR_RESEARCH_AND_EDUCATION.md` is blocked on unimplemented features (its
"Resume here" list: the `embed(table, token)` token-embedding primitive, the one-line
`transformer(block, position)` call, stack slicing `stack weights.model.layers[..18]`, the
Phase 2 runner, and a real Qwen checkpoint plus tokenizer the agent cannot download). Note
that hierarchical weight paths are *not* on that list: a `checkpoint` parameter,
`weights.model.norm.weight`-style paths and `stack weights.model.layers as block { }` are
implemented and are exactly what `qwen2.pdsl`, `qwen3.pdsl` and the reference's
`tiny_decoder` use, so the tutorial can and should teach them. This tutorial deliberately
stays inside what already works and ships: the parser, `PdslLoader`, and the
already-shipped, already-tested `.pdsl` assets. It is the teaching asset that programme will eventually want, written against
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
   - **Reading a layer** — walk through the `swiglu_ffn` layer of
     `engine/ml/src/main/resources/pdsl/feed_forward.pdsl` (six lines of body), naming each
     construct it uses: typed parameters (`weight`, `float`), `rmsnorm(norm_weights, epsilon)`,
     `product({ dense(w1); silu() }, { dense(w3) })` as the SwiGLU gate (two block arguments
     reading the same input, multiplied element-wise), and the final `dense(w2)`. The file's
     doc comment already draws the data flow with shapes (`[1, dim]` → `[1, hidden_dim]` →
     `[1, dim]`), which is the "text looks like the picture" point to make. `swiglu_ffn` has
     no `accum`, no `branch` and no `-> [shape]` annotation — those are introduced in the
     next two steps, where they actually occur. The file's second layer, `glu_feed_forward`,
     takes `projection`/`activation` parameters and `slice`s; leave it out of this step or
     mention it only as a pointer. Keep every construct's description to one sentence and
     defer the formal semantics to the reference.
   - **Reading attention** — a guided read of the `attend` and `attention` layers of
     `attention.pdsl`: the `-> [1, heads * head_size]` shape annotation, the opening
     `rmsnorm`, the `branch keys { }` / `branch values { }` side paths (projection,
     `rotate_heads` for RoPE, `repeat_each(heads / kv_heads)` for grouped-query attention,
     `cache_write(key_cache, position)` into the KV cache), the query path, and `attend`
     (`attention_scores`, `scale(1 / sqrt(head_size))`, `causal_mask(position)`, `softmax`,
     `weighted_values`). `sdpa.pdsl` is a different thing — full-sequence score layers built
     on `scaled_dot_product` with a `key_mask`, with no RoPE and no KV cache — so if it
     appears at all it is as a short contrast, not as part of this read.
     The annotation must be taught as the reference defines it, which is *not* what the arrow
     suggests: `layer f(...) -> [s]` declares the **input** shape `f` is built for when another
     layer or model calls it, and it is ignored by a direct `buildLayer` from Java
     (`pdsl-language.md`, "The shape annotation" and "Known limits"). The asset bears this out:
     `attend` is annotated `-> [heads, head_size]` because it is called on the rotated query of
     that shape, and its doc comment gives its mapping as
     `[heads, head_size] -> [1, heads * head_size]`. Reading the arrow as a return shape is the
     most likely first misreading, so it belongs in the "common first mistakes" list as well.
   - **Composing a layer** — how the `transformer` layer of `transformer.pdsl` uses
     `import "/pdsl/attention.pdsl"` / `import "/pdsl/feed_forward.pdsl"` and two `accum { }`
     residual blocks to assemble attention and `swiglu_ffn` into one pre-norm decoder layer.
     `transformer.pdsl` itself does not repeat over layers; the repetition lives in the
     `model` definitions of `qwen2.pdsl` / `qwen3.pdsl`, which bind a `checkpoint` parameter
     and use `stack weights.model.layers as block { transformer...(...) }` followed by the
     final `rmsnorm` and vocabulary `dense`. Teach `stack` (index-free, the owner's preferred
     form), not `for i in 0..n` with string keys. Mention the rule the reference states: a
     `model` body cannot hold `accum`, which is why the residual composition lives in a `layer`.
   - **Writing your own small model** — build up, step by step, a minimal **complete**
     program that *parses* and *builds* via `PdslLoader.buildModel(program, name, inputShape,
     stateDict, extraArgs)` against a small synthetic `StateDictionary`, ending in a single
     on-page `.pdsl` program the reader has watched grow. The reference's `tiny_decoder`
     worked example (a `residual_projection` layer plus a `model` that `stack`s it) is the
     shape to aim at; the tutorial should arrive at something of that size progressively and
     should differ from it enough (see Open questions) not to be a copy. The final program
     must be **self-contained** — no `import` — because imports are resolved only by
     `PdslLoader.parseResource`/`parseResources`; `parse(String)` and `parse(Path)` do not
     resolve them (`pdsl-language.md`, "Imports").
   - **Running it from Java** — the minimal snippet: `PdslLoader loader = new PdslLoader();`,
     `loader.parse(path)` (or `parseResource("/pdsl/...")` for a classpath asset with
     imports), then `loader.buildModel(program, "my_model", shape(1, dim), stateDict,
     extraArgs)`. Then the one-line reminder that the embedding lookup, the generation loop
     and sampling stay in Java (`AutoregressiveModel` and its subclasses
     `KeyValueCacheAutoregressiveModel` / `SlidingWindowAutoregressiveModel`), exactly as the
     real models do.
   - **Where to go next** — links to the reference, to `pdsl-audio-dsp.md`, to the shipped
     assets, and a short "common first mistakes" list (shape mismatches, forgetting a norm,
     reading `-> [shape]` as an output shape, putting `accum` in a `model` body, expecting
     `parse(String)` to resolve an `import`), framed as learning aids. Each item should name
     the error message or symptom the reader will actually see, taken from the reference's
     "Errors and diagnostics" and "Known limits" sections, not paraphrased from memory.

2. **A drift-guard test** in `engine/ml` — `PdslTutorialExamplesTest` (extends
   `TestSuiteBase`, `@Test(timeout = ...)`), modeled directly on the existing
   `PdslLanguageReferenceExamplesTest`. It locates `docs/tutorials/10-pdsl-model-definitions.html`
   by walking up from `user.dir` (the same mechanism the reference test uses), extracts every
   PDSL program the page presents as complete, and asserts each one **parses** with
   `PdslLoader.parse(String)` (and, for the final complete model, builds via
   `PdslLoader.buildModel` from a synthetic `StateDictionary` and has the output shape the
   prose claims — the same check the reference test makes for `tiny_decoder`). This makes
   the tutorial's code unable to drift away from a language the parser still accepts, the
   same guarantee `PdslLanguageReferenceExamplesTest` gives the reference page.
   - To make extraction unambiguous in HTML, each complete on-page program is wrapped in a
     stable, greppable marker (e.g. a `<pre class="pdsl-program">` class or an HTML comment
     delimiter the test keys on). The existing tutorials mark code as
     `<pre><code class="language-java">` / `language-bash`, so the natural choice is
     `<code class="language-pdsl">` for a complete program and a different class (for example
     `language-text`) for a fragment, keeping the page in the series' existing markup. The reference test's convention is a Markdown fence
     (`PDSL_BLOCK` matches a fenced block tagged `pdsl`): a complete program is fenced as
     `pdsl`, a fragment as `text`. The HTML marker should carry the same meaning — marked =
     complete and must parse; unmarked = fragment.
   - The extractor must **decode HTML entities** before parsing. PDSL shape
     annotations are written `->`, which must appear as `-&gt;` in HTML, and `<`/`&` likewise
     need escaping; a test that parses the raw HTML text would fail on every annotated layer
     (or pass only because the page avoided them). Any syntax-highlighting markup inside the
     `<pre>` (spans) must also be stripped, or the page must not use any.
   - Shipped assets the page quotes (rather than programs it writes) should be asserted
     against the asset itself — e.g. that the quoted `swiglu_ffn` body appears verbatim in
     `feed_forward.pdsl` — or shown only as excerpts and not marked as complete programs;
     otherwise an edit to the asset silently leaves a stale copy on the page.

3. **Index + cross-link wiring:**
   - Add the tutorial to the `#tutorials` list in `docs/index.html`.
   - Add a "Related documentation" cross-link from `docs/internals/pdsl-language.md` to the
     new tutorial (reference → tutorial), and from the `engine/ml` README's PDSL section.
   - Mention it in `llms.txt` alongside the existing PDSL reference entry.

### Out of scope (explicitly)

- No changes to the PDSL language, parser, interpreter, or any `.pdsl` asset. If the tutorial
  surfaces a defect or a rough edge, it is **recorded** (memory + a follow-up tracker task),
  not fixed here.
- None of the unbuilt `PDSL_FOR_RESEARCH_AND_EDUCATION.md` features (`embed` token-embedding
  primitive, `transformer(block, position)`, stack slicing, the runner,
  steering/probe/LoRA interventions). The tutorial
  teaches today's language; the research programme is a separate track.
- No real-checkpoint parity work (needs a Qwen download the agent cannot perform).

## Approach

1. **Ground every claim in source first.** Read the chosen assets (`feed_forward.pdsl`,
   `attention.pdsl`, `transformer.pdsl`, `qwen3.pdsl`) and the reference's grammar/builtin
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
- Every PDSL program shown as complete on the page is parsed by `PdslTutorialExamplesTest`
  (after HTML-entity decoding); the final model program also builds via
  `PdslLoader.buildModel` from a synthetic `StateDictionary` with the output shape the page
  states. The test counts the marked programs and fails if it finds none, so a changed marker
  cannot turn it into a vacuous pass. The test passes.
- Every construct the tutorial attributes to a shipped asset actually occurs in that asset
  (e.g. `accum` is taught from `transformer.pdsl`, `branch` from `attention.pdsl`, `stack`
  from `qwen3.pdsl`).
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

## Existing work this builds on

Nothing in `docs/tutorials/` mentions PDSL today, and no other plan proposes a PDSL
tutorial. Closest existing material, which the tutorial should link to rather than repeat:

- `docs/internals/pdsl-language.md`, "Worked examples" — six complete programs
  (`residual_mlp`, `gated_ffn`, `two_heads`, `config tiny` + `split_weights` +
  `sum_of_halves`, `scaled`/`halved`/`gain`, `self_scores`) and the `tiny_decoder` model,
  all parsed and built by `PdslLanguageReferenceExamplesTest`. `gated_ffn` is already a
  stripped-down `swiglu_ffn`.
- `docs/internals/ml-inference-pipeline.md` — where `attention.pdsl`, `transformer.pdsl`
  and `feed_forward.pdsl` sit in the inference pipeline.
- `docs/tutorials/05-ml-inference.html` — the Java side (`AutoregressiveModel`) the
  "Running it from Java" step hands off to.

## Open questions

Raised in review for the person approving the plan; none has been decided.

1. **How the final model differs from `tiny_decoder`.** The reference already ends in a
   complete, tested `stack`-based model of exactly the size this tutorial targets. If the
   tutorial's final program is `tiny_decoder` with renamed identifiers, the page duplicates
   the reference and the drift test duplicates the reference test. Options: (a) make the
   final model a real decoder layer (norm + a small attention + `swiglu_ffn`-style gate,
   self-contained, built against a synthetic checkpoint with KV-cache weights supplied the
   way `qwen3.pdsl` does with `zeros(...)`), which is more instructive but needs
   `position`/`rope_freqs` bindings that make the Java snippet longer; or (b) keep it small
   and make the progression itself (adding one construct per step) the value. The plan
   should pick one before implementation.
2. **Teaching the 20-argument `transformer(...)` call.** `qwen2.pdsl` calls `transformer`
   with twenty positional arguments (`qwen3.pdsl` calls `transformer_qk_norm` with twenty-two),
   a form the owner has said must not
   spread and which `PDSL_FOR_RESEARCH_AND_EDUCATION.md` ("Resume here" item 3) plans to
   replace with `transformer(block, position)`. A tutorial that teaches readers to write that
   call will be out of date when item 3 lands, and the drift test will not notice (the old
   form keeps parsing). Should the tutorial show the call only as something to read, not to
   write, and should landing item 3 be listed as a trigger to revise the tutorial? The same
   applies to the `-> [shape]` annotation: `PDSL_FOR_RESEARCH_AND_EDUCATION.md` proposes to
   "fix the meaning of `-> [shape]`" (rename it to say input shape, or make it actually check
   the output).
   The tutorial teaches today's input-shape meaning; if that proposal lands, the page must be
   revised with it, and the drift test will again not notice if only the meaning changes.
3. **`for i in a..b`.** The language still accepts index loops and string-keyed weights, but
   the owner's direction is that they not spread. The plan assumes the tutorial teaches only
   `stack`; confirm that it should not mention `for` at all, or mention it only with a
   pointer to the reference.
4. **Deliverables outside `docs/`.** The test (`engine/ml`), the `engine/ml` README link
   and the `llms.txt` entry are outside `docs/`, so the implementation is a code change,
   not a documentation-only branch, and goes through code review and CI accordingly.
5. **Tutorial numbering.** `docs/tutorials/` already has two pages numbered `08`
   (`08-flowtree-stack-setup.html`, `08-process-optimization.html`); `10` is the next free
   number and this plan does not propose renumbering them.

## Estimated Complexity

Medium. One substantial HTML page grounded in source, one focused drift-guard test mirroring
an existing one, and index/cross-link wiring. The risk is scope discipline (staying a
tutorial, not a second reference) and extraction robustness in HTML, both addressed above.

## Resolution

Implemented as `docs/tutorials/10-pdsl-model-definitions.html` with the drift guard
`PdslTutorialExamplesTest`. The open questions were settled as follows:

1. The final model is option (a) without rotary embedding: a self-contained `toy_decoder`
   that stacks a pre-norm decoder layer (cached single-token attention plus a gated MLP, each
   in an `accum`) over a synthetic checkpoint, then a norm and a vocabulary projection. Its
   caches come from `zeros(...)` as in `qwen3.pdsl`; only `position` and a settings map are
   passed from Java.
2. The 22-argument `transformer_qk_norm(...)` call is shown elided, as something to read, not
   to write. Landing `transformer(block, position)` or changing the meaning of `-> [shape]`
   are triggers to revise the page.
3. `for i in a..b` is mentioned once, with a pointer to the reference; only `stack` is taught.
4. The test, README link and `llms.txt` entry are included.
5. The page is numbered `10`; the two `08` pages are untouched.

Asset excerpts on the page are marked `language-pdsl-asset` with a `data-asset` path and are
checked line by line against the asset; programs the reader writes are `language-pdsl` and
must parse; the final one must build to `[1, vocab]` with one block per stacked layer plus two.

