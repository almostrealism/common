# Document the PDSL language: grammar, semantics, primitives and the Java boundary

- **Category:** Documentation
- **Estimated complexity:** Medium
- **Runs on:** any node (documentation plus small parse-only example tests; no Metal requirement)

## Motivation

PDSL (Producer DSL, `org.almostrealism.ml.dsl`) has quietly become the language in which the
platform *describes its own models*. Every attention variant (`attention.pdsl`,
`sequence_attention.pdsl`, `differential_attention.pdsl`, `sdpa.pdsl`), the transformer layer,
the feed-forward networks, the Qwen2/Qwen3 model files, the T5Gemma encoder, the Oobleck codec,
the SkyTNT MIDI blocks, the GRU decoder and the whole `AudioScene` real-time mixdown are PDSL
assets. There are 19 shipped assets, all under `engine/ml/src/main/resources/pdsl/`. Alongside them
are 29 test fixtures: 24 in `engine/ml/src/test/resources/pdsl/` and 5 in
`studio/compose/src/test/resources/pdsl/audio/`. That makes 48 `.pdsl` files, interpreted by about
6,200 lines of Java (`PdslLexer`, `PdslParser`, `PdslNode`, `PdslInterpreter`, `PdslBuiltins`,
`PdslLoader`, `PdslPrimitive`, `PdslPrimitiveContext`, ...).

Yet there is **no language reference.** `consult` confirms it: "There is no single complete
language reference for PDSL." What exists is scattered:

- `docs/internals/pdsl-audio-dsp.md` — the audio primitives and multi-channel constructs, with a
  one-paragraph list of the ML builtins in parentheses.
- `docs/internals/ml-inference-pipeline.md` and `engine/ml/README.md` — which assets build which
  layers, without explaining the language those assets are written in.
- The `org.almostrealism.ml.dsl` `package-info.java` — four bullet points.
- `docs/plans/PDSL_FOR_RESEARCH_AND_EDUCATION.md` — a temporary plan, excluded from the consult
  corpus, which itself notes "None of the current tutorials mention PDSL."

Nothing in durable documentation answers the questions any new PDSL author — human or model —
asks first: What are the definition kinds (`layer`, `model`, `config`, `data`, `state`)? What do
the parameter types (`weight`, `scalar`, `producer`, `int`, `float`, `bool`, shape literals,
config/checkpoint types) mean, and which are compile-time versus graph operands? What do the
composition statements (`accum`, `branch`, `product`, `accum_blocks`, `concat_blocks`, `stack ...
as`, `for`, `for each channel`, `let`, `return`) build in terms of `Block` / `SequentialBlock`
/ `BranchBlock`? (`if` and `else` are reserved by the lexer, but the parser has no production for
them and there is no `PdslNode` for a conditional, so PDSL has no conditional statement today.) How do `import` resolution, diamond de-duplication and cycle detection work? In
what order are names resolved (program layers → registered primitives → builtins), and what
happens when a layer shadows a builtin? What does each of the 40 builtins compute, with what
shapes — and which of them pass gradients to which operands (the question the last three
correctness cycles turned on: `scale` reading λ as a constant, `scaled_dot_product` taking a
branch as an auxiliary operand)? How does a domain register its own primitives
(`PdslPrimitive`, `PdslLoader(Consumer<PdslInterpreter>)`, `AudioDspPrimitives::registerWith`)?

This matters to the vision directly. The self-hosted language model is trained on the platform's
own documentation; a PDSL reference is the single most *generative* page we can add to that
corpus, because PDSL is the compact, readable form in which the platform states what its models
compute. A model that can read the reference can read — and eventually write — the model files.
It also unblocks the PDSL-for-research track (whose Phase 5 calls for teaching material) and makes
every future PDSL conversion cheaper, because an author no longer reverse-engineers the
interpreter from `PdslInterpreter.java`.

## Scope

1. **New `docs/internals/pdsl-language.md` — the language reference.** Sections:
   - **Overview and pipeline:** source → `PdslLexer` → `PdslParser` → `PdslNode.Program` →
     `PdslInterpreter` → `Block`/`Model`; where `PdslLoader` fits; resource caching
     (`RESOURCE_CACHE`) and that cached programs are never mutated.
   - **Lexical structure:** comments (`//`, `/** */` asset headers), identifiers, literals
     (number, string, bool, `null`, shape literals `[a, b]`), the full keyword table read from
     `PdslLexer.KEYWORDS`, operators and precedence read from `PdslParser`. The table must mark
     which keywords are reserved but unused: `if` and `else` have no parser production. It must
     also note the contextual words that are *not* keywords: `each` in `for each channel` is an
     identifier, which `PdslParser` matches by its text.
   - **Grammar:** an EBNF-style grammar derived from `PdslParser`, covering every `PdslNode`
     subclass (`Import`, `ConfigDef`, `DataDef`, `StateDef`, `LayerDef`, `ModelDef`, `Parameter`,
     every `Statement` and `Expression` subclass including `Subscript`, `FieldAccess`,
     `InlineBlock`, `WeightRef`).
   - **Definitions and parameters:** what each definition kind produces; each parameter type,
     whether it is evaluated at build time or wired as a graph operand, and how Java supplies
     it (`buildLayer`/`buildModel` argument maps, `evaluateConfig`, `evaluateDataDef`,
     hierarchical weight binding through `stack weights.model.layers as block`).
   - **Statements and their block semantics:** for each composition statement, the `Block`
     structure it builds (e.g. `accum` → residual `BranchBlock` via `SequentialBlock.accum`;
     `accum_blocks`/`concat_blocks` → a `Cell` combining parts; `product`; `branch`), its shape
     rule, and how compute requirements propagate (`buildLayer` applies them to every layer block).
   - **Name resolution and scope:** the `Environment` / per-build context, program scope as the
     root of every layer environment, resolution order, self-recursion and shadowing rules
     (`layer relu { relu() }` wraps the builtin; real recursion throws `PdslParseException`).
   - **Imports:** syntax, classpath resolution, diamond de-duplication of resources versus
     duplicate-definition errors, cycle errors — each pinned to the `pdsl/imports/*.pdsl` fixtures
     and `PdslLoaderTest`. When to still use `parseResources(...)`.
   - **Builtin catalog:** a table for every builtin dispatched by `PdslBuiltins` /
     `PdslInterpreter` (the 40 names from `identity` through `zeros`), giving signature, input →
     output shape, the `LayerFeatures`/`CollectionFeatures` method it lowers to, and its
     **gradient behavior per operand** (graph operand that trains, constant factor, or no
     gradient). The audio catalog stays in `pdsl-audio-dsp.md`; this page links to it instead of
     duplicating it.
   - **Extending PDSL:** writing a `PdslPrimitive`, using `PdslPrimitiveContext` argument
     normalisation, registering through `PdslLoader(Consumer<PdslInterpreter>)`, and the
     placement rule (domain-agnostic primitives belong in `PdslBuiltins`, domain ones in the
     domain module's registrar).
   - **Errors and diagnostics:** what `PdslParseException` reports, and common errors.
     `PdslParseException` is a `RuntimeException` that carries only a message; there are no
     structured position fields. Where the position appears depends on where the error comes from:
     parser errors (`PdslParser.error`) end in "at line L, column C"; lexer errors give "L:C";
     interpreter errors such as an unknown function or a self-recursive layer give only
     "at line L"; some build-time failures carry no position at all. The page must
     describe this as it is. Positioned errors are Phase 2 work in
     `PDSL_FOR_RESEARCH_AND_EDUCATION.md`, not this task.
   - **Known limits:** recorded honestly, each with evidence (for example, which builtins treat
     an operand as a constant; anything the grammar parses but the interpreter rejects). Defects
     found are recorded, not fixed, in this task.
2. **Trim `pdsl-audio-dsp.md`** so its "Core/domain-agnostic primitives" table and the
   parenthesized ML-builtin list point to the new catalog rather than maintaining a second,
   drifting copy (no duplication).
3. **Javadoc pass on the `org.almostrealism.ml.dsl` public API.** Expand `package-info.java`
   to name all public components. Today it lists four. The public types are `PdslLexer`,
   `PdslToken`, `PdslParser`, `PdslNode`, `PdslParseException`, `PdslInterpreter`, `PdslLoader`,
   `PdslPrimitive`, `PdslPrimitiveContext`, `PdslChannelBank` and `PdslMultiChannelDispatcher`.
   The expanded page should also link the reference page by its stable path, and the pass should
   fill in Javadoc on any public class or method of those types that lacks it. `PdslBuiltins`
   (a `final` class) and `PdslFeatures` are **package-private**. The package overview may describe
   them in prose, but it must not `{@link}` them, because that link does not resolve in the
   generated public Javadoc.
4. **Executable examples.** Every grammar example in the reference that is a complete program
   must parse. Add a small `PdslLanguageReferenceExamplesTest` (extends `TestSuiteBase`, every
   `@Test` with a `timeout`) whose PDSL snippets are the exact examples shown in the doc, asserting
   they parse (and, for a few tiny layers, build to the documented output shape on CPU). This
   keeps the page from drifting from the parser.
5. **Discoverability.** Add the page to `llms.txt` and cross-link it from `engine/ml/README.md`,
   `ml-inference-pipeline.md` (its asset descriptions), `training-a-language-model.md` (its
   gradient-wiring section) and `pdsl-audio-dsp.md`.

## Approach

- **Read source, do not paraphrase memory.** Derive the keyword table from `PdslLexer`, the
  grammar from `PdslParser`'s methods, the node list from `PdslNode`, the builtin catalog by
  enumerating the dispatch in `PdslBuiltins`/`PdslInterpreter`, and the semantics from the
  interpreter code paths. Pin every non-obvious behavioral claim to an existing test
  (`PdslProgramScopeTest`, `PdslLoaderTest`, `PdslStackTest`, `PdslNestedLayerShapeTest`,
  `SdpaPrimitivesTest`, `SequenceAttentionPrimitivesTest`, `DifferentialAttentionAssetTest`, ...)
  or to the new examples test.
- **Gradient column from evidence only.** For each builtin, state whether an operand trains by
  reading how it is lowered (graph operand vs. a value captured as a constant), citing the
  gradient tests that exist (`SequenceAttentionGradientTest`,
  `SequenceCrossAttentionGradientTest`, `DifferentialAttentionAssetTest`). Where neither code nor
  a test settles it, write "not verified" — never guess.
- **`consult` first** for each subsystem touched; store memories as facts are established.
- Follow `docs/CLAUDE.md`: no source line numbers; reference classes and methods by name; no
  references from durable docs or code into `docs/plans/`.
- Run the new examples test and the build validator (`checkstyle`, `code_policy`,
  `test_timeouts`, `duplicate_code`) before completion.

## Success Criteria

- `docs/internals/pdsl-language.md` exists and covers every section in Scope item 1; every
  keyword in `PdslLexer.KEYWORDS`, every `PdslNode` subclass and every builtin name dispatched
  by `PdslBuiltins`/`PdslInterpreter` appears in it.
- The builtin catalog has a gradient-behavior entry for every operand of every builtin, each
  backed by code or a test, or marked "not verified".
- `pdsl-audio-dsp.md` no longer duplicates the core-primitive table.
- `package-info.java` names every public type in `org.almostrealism.ml.dsl` (the eleven listed in
  Scope item 3); public members of those types carry Javadoc.
- The keyword table says plainly that `if`/`else` are reserved but have no meaning.
- `PdslLanguageReferenceExamplesTest` passes and parses every complete example on the page.
- After the corpus rebuild, `consult` answers "How do I write a PDSL layer with a residual
  branch and register a new primitive?" from the new page.
- Build validator reports no violations.

## Dependencies

There are no blocking dependencies. The import mechanism, `stack`, program scope and the
attention/differential gradient fixes have all landed on `master`.

Related work that overlaps with this plan:

- `PDSL_FOR_RESEARCH_AND_EDUCATION.md` ("Resume here") queues three language changes:
  `embed(table, token)`, `transformer(block, position)` and stack slicing
  (`stack weights.model.layers[..18]`). Its Phase 2 also includes positioned errors. Each of
  these changes the surface this page documents. "Stable" means stable today, not frozen.
- That plan's Phase 5 tutorial depends on this page. Note that the existing tutorials in
  `docs/tutorials/` are numbered HTML pages (`01-...html` through `09-...html`), not Markdown.

## Open questions

- **Order relative to the language changes above.** Writing the reference first means it
  must be revised when `embed`, `transformer(block, position)` and stack slicing land. The
  examples test (Scope item 4) catches example drift only. It does not catch a catalog row or
  grammar rule that goes missing or out of date. Should those language items land first? Or
  should a check be added that compares the builtin-name list on the page with the dispatch in
  `PdslBuiltins` and fails when they differ?
- **Gradient column cost.** Most builtins have no direct gradient test. Writing "not verified"
  is honest, but if most cells end up saying it, the column may not justify its effort. Should
  the task instead add finite-difference checks for the builtins whose gradient matters to
  training (`dense`, `scale`, `rmsnorm`, `layernorm`, `softmax`, `scaled_dot_product`,
  `weighted_values`)? That would make it a larger, test-writing task.
- **`if` / `else`.** Two keywords are reserved but have no grammar. Should the reference only
  record this, or should a follow-up either implement conditionals or remove the keywords?
  This task only records it (per "Known limits").
- **Scope beyond `docs/`.** Scope items 3–5 change Java sources, a new test class,
  `llms.txt` and `engine/ml/README.md`. The work is therefore a code change, not only a
  documentation change, and it gets the corresponding review (build validator, code policy,
  test-integrity checks).

## What this sets up

- The PDSL-for-research Phase 5 tutorial (`docs/tutorials/`) can be written against a reference
  instead of the interpreter.
- A training corpus for the self-hosted LM that includes the language its models are written in
  — the precondition for a model that reads (and later proposes edits to) the platform's model
  files.
- The "known limits" section becomes the input list for the next correctness/consistency cycle on
  PDSL builtins.
