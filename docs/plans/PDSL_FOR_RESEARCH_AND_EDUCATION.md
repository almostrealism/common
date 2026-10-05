# PDSL for Research and Education

**Status:** Direction agreed (see "Decisions"). Nothing here has been implemented.

## Decisions

These were settled with the project owner on 2026-10-04.

1. **Start with Qwen2.5-0.5B-Instruct**, the only checkpoint validated end to end. Qwen3
   comes in with Phase 4.
2. **Lean toward putting things in PDSL, but not everything belongs there.**
   - Runtime settings stay command-line arguments of the runner: device, output directory,
     sampling temperature, number of tokens.
   - Bulk inputs such as prompts live in their own files.
   - Everything about *what the model computes*, including the data an intervention uses,
     is in PDSL or brought in by `import`.
3. **Data is imported the same way definitions are.** A steering vector, a probe direction or
   an adapter is pulled into a PDSL file with an `import` statement that reads and behaves
   like importing another `.pdsl` file. The imported names are then usable as weights. See
   "Data imports".
4. **No library shorthands until one is clearly needed.** The base language stays as small as
   possible.
   - A file may open with a short "helpers this model needs" section before the model
     definition, as long as the helpers stay compact enough that a non-programmer can still
     read them.
   - `steer(...)` or `lora_dense(...)` become shared library layers only when repetition
     across real files demands it.
5. **Protobuf is the project's data format; safetensors is only read, by a pure Java reader.**
   - `StateDictionary`'s protobuf format (`engine/ml/src/main/proto/collections.proto`,
     `CollectionLibraryData`) is the one the project writes and imports. The reasons:
     - many languages and platforms can read it;
     - it works over the wire, for an inference or training service;
     - other people's protobuf specs can include ours. Someone defining their own system that
       happens to contain steering vectors can import `collections.proto` and reuse the
       message types.
   - Safetensors is read only, by a pure Java reader, so getting started does not need Python.
     A checkpoint is read directly, or converted once to protobuf.
   - Protobuf stays out of the core modules. Today it is a dependency of `engine/ml`, the
     module that also holds PDSL, and of `studio/compose`. The plan keeps every protobuf use
     inside `engine/ml` and adds no new third-party dependencies anywhere.

## Goal

Make PDSL a language in which a person with a basic understanding of linear algebra, and
almost no programming experience, can:

1. **Read** a complete language model on one page and follow where information goes.
2. **Point** at the line where an intervention belongs: "this is where I would read the
   residual stream", "this is where I would add a steering vector", "this is where a
   low-rank adapter sits".
3. **Run** an experiment by changing a few lines, without writing Java or Python, and get
   results in a form they can analyze with ordinary tools.

The audience is a talented high-school student, a researcher from another field, or a policy
or ethics researcher who understands what a matrix multiply and a softmax are, but who would
otherwise need a research engineer to run anything.

The concrete endpoint is a single `qwen.pdsl` file for a real Qwen checkpoint, published
alongside a short sequence of variants with the changed lines highlighted:

| Page | What the reader sees |
|------|----------------------|
| The model | The whole forward pass: embedding, the layer loop, final norm, vocabulary projection |
| Reading the residual stream | One added line that records layer 18's residual stream |
| A linear probe | The recorded vector, projected onto a direction to give one number per token |
| The logit lens | A side branch that applies the final norm and vocabulary projection to an intermediate layer |
| Steering | One added line that adds `strength × direction` to the residual stream |
| Ablating a head | One changed line that zeroes the output of one attention head |
| A low-rank adapter | One `dense` replaced by `dense + B·A`, written out as two more `dense` calls |

**Every snippet shown publicly must be a file that a test loads and runs.** The published
pages and the tests read the same files, so a page cannot drift away from code that works.

## Where PDSL is today

The current state was surveyed on 2026-10-04. Citations are against that tree.

### Already expressed in PDSL

- **One transformer layer for Llama-style models.**
  - `engine/ml/src/main/resources/pdsl/transformer.pdsl` defines `transformer`, `transformer_qk_norm` (Qwen3) and `transformer_mra`.
  - The attention stage is in `attention.pdsl`. It has a KV cache declared as `state attention_cache`, grouped-query attention via `repeat_each`, RoPE and per-head QK-norm.
  - The SwiGLU MLP is in `feed_forward.pdsl`.
- **The header comments are already teaching material.** Each stage is annotated with its shape (for example `[heads, seq_len]`). That style is the right one for this audience.
- **Composition is readable.** `accum { ... }` is a residual connection, `branch { ... }` is a side path whose result the main path does not consume, and `product(a, b)` is an element-wise gate.
- **An observation primitive exists.** `capture(slot)` (`PdslBuiltins.java:288-321`) copies the signal into a slot and passes it through unchanged. Only the audio mixdown uses it today.

### Still in Java

The model-level structure of every LLM is still in Java (`Qwen3.model()`, `Qwen3.java:334-415`):

- the token embedding, a lambda in `AutoregressiveModel`;
- the loop over layers, which calls `AttentionFeatures.transformer(...)` once per layer;
- binding of each layer's weights from `StateDictionary` by Hugging Face key;
- allocation of the KV cache (`AttentionFeatures.java:846`);
- the RoPE frequency table, the final RMSNorm, the vocabulary projection, and sampling.

No shipped `.pdsl` file defines a `model`.

### Gaps that block this plan

| Gap | Where | Consequence |
|-----|-------|-------------|
| `weight("key")` returns the string `"weight:key"` and nothing resolves it | `PdslInterpreter.java:984-988` | A model cannot name its own weights |
| `buildModel(..., StateDictionary, ...)` only stores the dictionary under `args["state_dict"]`, and nothing reads it | `PdslLoader.java:218-229` | No direct weight binding |
| No token-embedding primitive | — | The model cannot begin in PDSL |
| No runner: every `.pdsl` file is loaded from Java | — | Nobody can run a file without writing Java |
| Interpreter errors are mostly unpositioned, and there is no static shape check | `PdslInterpreter.java:345-354`, `906-913` | A novice's first mistake produces a stack trace |
| `-> [shape]` is parsed as a return shape but treated as the input shape | `PdslInterpreter.java:1110-1133` | The language says something it does not mean |
| No primitive adds an external vector; `capture` is the only observation hook | — | No steering; probing only through a slot nobody reads |
| PDSL `dense` has no `ProjectionFactory` hook | `PdslBuiltins.java:79` | Existing LoRA support (`LoRALinear`, `LowRankAdapterSupport`) cannot reach PDSL-built LLMs |
| `capture`'s backward pass is an empty operation list | `PdslBuiltins.java:313-316` | A recorded model may block gradients; this matters once adapters are trained |
| Only Qwen2.5-0.5B-Instruct has been validated end to end (exact top-logit match with PyTorch, `engine/ml/qwen/STATUS.md`) | — | Qwen3 support is untested, and the program's studies used Qwen3-4B |

## Design principles

1. **The page is the model.** Nothing that changes what the model computes may be hidden in
   Java. Java may allocate buffers, load files and run the generation loop. If a reader would
   have to open a Java file to learn what the model does to a vector, the plan has failed.
   This is the project's own rule ("Java is orchestration") made visible to the reader.
2. **Interventions are ordinary PDSL.** Steering is `accum { add(direction, strength) }`, and
   a LoRA projection is `accum_blocks({ dense(w) }, { dense(a); dense(b); scale(s) })`.
   Neither should be a special mode of the runner, and neither gets a shorthand until one is
   clearly needed (Decision 4). The examples write the composition out in full where it is
   used.
3. **Names match the literature and the checkpoint.** Weight names follow the Hugging Face
   keys (`model.layers.{i}.self_attn.q_proj.weight`), so a reader can cross-reference the
   model card, a paper or a PyTorch tutorial.
4. **Shapes are always visible.** Every line of the canonical files carries its shape. The
   tooling can print a shape trace of any file without running it.
5. **Errors are for people.** Every error names a file, a line, a column and the shapes
   involved, and says what was expected in words. For example: *"line 23: `dense` expected a
   weight with 896 columns to match its input [1, 896], but `mlp.down_proj.weight` is
   [896, 4864]. Did you mean `mlp.up_proj.weight`?"*
6. **Nothing is shown before it works.** Each published claim is backed by a parity test
   against a reference implementation (see "Correctness gates").
7. **The text looks like the picture.** This is the north star. A diagram of the model's data
   flow will be generated from the same file. If someone draws the model, the drawing and the
   text must have the same shape: one box per line that does something, nesting where the
   boxes nest, and repetition shown as repetition.
   - The language should make it *hard* to write a file whose function diverges from its
     form. If it is possible, people will do it.
   - Constructs that read like programming work against this: index arithmetic, string
     substitution such as `"model.layers.{i}..."`, and long positional argument lists. The
     first whole-model files use some of these; see "Form follows the picture".
   - New features must not add more of them.

## Target shape of the model file

This is a sketch to anchor the discussion. The syntax for weight access and per-layer keys
is proposed here, and the language does not support it yet.

```pdsl
import "/pdsl/transformer.pdsl"

/** Qwen2.5 / Qwen3 decoder: one token in, one row of vocabulary logits out. */
model qwen(config: qwen_config, weights: checkpoint, token: scalar, position: scalar) {
    // The token's row of the embedding table: the start of the residual stream.  [1, 896]
    embed(weights["model.embed_tokens.weight"], token)

    // 24 identical layers, each reading and writing the residual stream.          [1, 896]
    for i in 0..config.layers {
        let layer = weights.prefix("model.layers.{i}.")
        transformer(config, layer, position)
    }

    rmsnorm(weights["model.norm.weight"], config.epsilon)                       // [1, 896]
    dense(weights["lm_head.weight"])                                            // [1, 151936]
}
```

The same file with a recorded residual stream and a steering vector. The changed lines are
the ones a published page would highlight:

```pdsl
    for i in 0..18 {
        transformer(config, weights.prefix("model.layers.{i}."), position)
    }
    record("residual_18")                                // read the stream here
    accum { add(direction, strength) }                   // steer it here
    for i in 18..config.layers {
        transformer(config, weights.prefix("model.layers.{i}."), position)
    }
```

Splitting the loop at the layer of interest avoids adding conditionals to the language. The
split is also the most honest picture of where the intervention sits.

## Form follows the picture

The first whole-model files (`qwen2.pdsl`, `qwen3.pdsl`) work, but they read more like a
program than a diagram. Each layer is reached through `for i in 0..settings.layers`, its
weights are named by substituting `{i}` into a string, and a 20-argument call hides which
weight feeds which stage. These are accepted for now. The following ideas are candidates for
moving back toward the picture; none is decided.

1. **Repetition without an index.** A `stack` construct over a collection the checkpoint
   already has: `stack weights.model.layers as layer { transformer(layer) }`.
   - The count comes from the data, and there is no loop variable to compute with.
   - It draws as one box marked "× 24".
   - An intervention between layers splits the stack:
     `stack layers[..18]`, then `record`, then `stack layers[18..]`. That is exactly the
     picture of where the intervention sits.
2. **Hierarchical weights instead of string keys.**
   - Bind the checkpoint as a tree that follows its own dotted names, so that
     `layer.self_attn.q_proj.weight` is a path into the data, not a string being assembled.
   - This removes `{name}` interpolation entirely.
3. **Layers that take a block of weights, not twenty arguments.**
   - A layer declares the weight names it reads relative to the block it is given:
     `transformer(layer)` reads `layer.self_attn.q_proj.weight` itself.
   - The call site becomes one line, and the diagram's box for the layer shows the same
     single input.
4. **Layers as nested boxes.** A called layer is a box that expands into its own body. This
   is the one kind of indirection a diagram can show faithfully (collapse and expand), so it
   is the abstraction the language should prefer over every other.
5. **Make the diagram the check.**
   - Generate the diagram from the parsed program.
   - Reject, or warn about, any construct the diagram cannot show faithfully. Candidates:
     arithmetic on loop indices, computed names, and a call whose arguments do not map to
     visible inputs.
   - If the renderer cannot draw it honestly, the language should not accept it.
6. **Keep settings out of the flow.** Sizes and constants (dim, heads, epsilon) are not
   boxes. They belong in a header the diagram shows as a legend, not threaded through every
   call as arguments.

Once 1–3 exist, the `{i}` interpolation and the per-layer `for` in the Qwen files should be
removed, not kept beside the new forms.

## Data imports

A steering vector arrives the same way a definition does:

```pdsl
import "/pdsl/transformer.pdsl"
import "distress_direction.pb" as distress      // data: a CollectionLibraryData file

model qwen_steered(...) {
    ...
    accum { add(distress["direction"], strength) }
    ...
}
```

How this should work:

- **A `.pb` import is data, not structure.** Each entry of its `CollectionLibraryData` becomes
  a named, read-only weight, reached as `alias["key"]`. A library with a single entry may
  also be used by its alias alone (`add(distress, strength)`). The `.pb` extension, or the
  file's first bytes, tells the loader which kind of import it is.
- **The same rules apply as for `.pdsl` imports.** Imports come before definitions, the cache
  keys on the normalized path, and cycles and duplicate names are errors. The data is loaded
  once, however many files import it.
- **Relative paths are needed.** Today's imports are absolute classpath resources
  (`PdslParser.java:96-104` rejects relative paths), which suits shipped assets. A
  researcher's own files live next to their experiment, so imports also need paths relative to
  the importing file on disk. Absolute classpath paths keep meaning shipped assets.
- **Shape is part of the contract.** An import may declare the shape it expects
  (`import "d.pb" as distress: [1, 896]`). A mismatch is reported at load time, with the file
  and line, not at compile time.
- **A safetensors import** (`import "adapter.safetensors" as lora`) goes through the same
  path, using the pure Java reader. That lets a PEFT adapter or a published direction be used
  without conversion. Converting to protobuf stays the recommended way to keep and share data.
- **The model's own checkpoint is also data.** The `checkpoint` parameter is bound by the
  runner from `--weights`, not imported. Weights are large and change from run to run, while
  a steering vector is part of the experiment's definition. That boundary is worth keeping:
  the file says what is done, and the command line says which model it is done to.

`strength` is a `producer([1])` slot, which already exists for audio automation. Changing it
therefore needs no recompile, so a sweep over strengths is a list of values and not a list of
models.

## Phases

Each phase ends at a gate. Work does not move to the next phase until its gate is met.

### Phase 1: the whole model in PDSL

1. **Weight binding.** A `checkpoint` parameter type is bound to a `StateDictionary`. It
   supports `weights["key"]`, `weights.prefix("...")` and `{i}` interpolation from the
   enclosing `for` variable. This replaces the unused `weight("key")` and `state_dict` stubs.
   It is general (any model with keyed weights) and belongs in the interpreter, not in a
   Qwen-specific registrar.
2. **`embed(table, token)`**: a general builtin that selects one row of a table by a scalar
   index.
3. **Model-level `for` over layers**, building each iteration's layer with the weights its
   keys select.
   - **Implemented:** the KV cache is now an explicit `key_cache` / `value_cache` parameter
     of every attention and transformer layer. The model allocates one pair per layer with
     `zeros([seq_len, dim])`.
   - The old `state attention_cache` block could not express this, because a called layer is
     built in the program scope: every layer of one build would have shared one cache.
4. **A layer signature that takes `config` and a weight prefix,** so the call site inside the
   loop stays one line. The existing long-argument layers remain for Java callers.
   - **Not done yet.** The shipped files pass each weight by its full Hugging Face name
     inside the loop. That is long, but every name is visible, and that may be the better
     teaching form; decide after the first published page.
5. **`qwen.pdsl`** as a shipped asset, used by `Qwen3` in place of its Java loop. The Java
   class keeps tokenization, weight loading and the generation loop.

**Gate:** the PDSL-built Qwen2.5-0.5B-Instruct produces the same logits as the current Java
path on a fixed prompt (bit-exact, or within the tolerance the existing PyTorch comparison
uses). All existing Qwen tests pass unchanged.

### Phase 2: tooling for a person, not a programmer

1. **A runner** (a `main` class in `engine/ml`; no new module):
   `pdsl run qwen.pdsl --weights <dir> --prompts prompts.txt --out results/`. It tokenizes,
   runs generation, and writes every `record(...)` as a tensor file plus a CSV summary and a
   JSON manifest naming the source file, the weights and the settings.
2. **`pdsl shapes qwen.pdsl --weights <dir>`** prints the shape at every line without
   running the model. Weight shapes can come from the checkpoint index alone.
3. **`pdsl check`** reports parse, binding and shape errors with positions. This means
   threading line and column through `PdslParseException` and the interpreter's error sites,
   and adding static shape checking for the builtins whose shapes follow from their
   arguments (`dense`, `reshape`, `rmsnorm`, `embed`).
4. **Fix the meaning of `-> [shape]`.** Either rename it to say input shape, or make it
   actually check the output.
5. **A pure Java safetensors reader** in `engine/ml`, next to `StateDictionary`.
   - Today a checkpoint must be converted to protobuf by `extract_qwen3_weights.py`.
   - The format is simple: an 8-byte little-endian header length, a JSON header naming each
     tensor's dtype, shape and byte range, then raw bytes. Parse the header with whatever JSON
     support `engine/ml` already has; add no new dependency.
   - Decode BF16 and F16 to the framework's precision. Qwen checkpoints ship as BF16.
   - The reader goes through the sanctioned host-ingest path, following the setMem ingest
     rules (`SETMEM_INGEST_LAYER.md`), and does no element-wise copying in Java.
   - It serves two uses: `StateDictionary` loads a Hugging Face directory directly, and
     `pdsl convert <hf-dir> <out>` writes the protobuf form once.

   `extract_qwen3_weights.py` can then retire, along with the other extractors as each model
   moves over.
6. **Data imports** as described in "Data imports": `.pb` and `.safetensors` imports,
   relative paths, and declared shapes.

**Gate:** someone who did not write the runner (a test participant, or at minimum a fresh
agent working only from the published instructions) goes from a clean machine to logits for a
prompt by following the written steps.

### Phase 3: the intervention vocabulary

Each item is a general builtin or a PDSL library layer, with a parity test against a reference
implementation (Hugging Face `transformers` hooks or PEFT) on the same checkpoint and prompt.

| Intervention | Expressed as | Reference for parity |
|--------------|--------------|----------------------|
| Record | `record("name")`, built on `capture`; the runner writes the slot | `output_hidden_states=True` |
| Linear probe | `record` followed by an offline projection; or `branch { dense(probe); record("score") }` | NumPy on HF hidden states |
| Logit lens | `branch { rmsnorm(final_norm); dense(lm_head); record("lens_18") }` | HF hidden states through the HF norm and head |
| Steering | `accum { add(direction, strength) }` | Forward hook adding the same vector |
| Head ablation | `product` with a per-head mask before `dense(wo)` | Forward hook on `o_proj` input |
| Activation patching | `add` of a recorded difference vector | Hook-based patching |
| LoRA (inference) | `lora_dense(w, a, b, scale)` as `accum_blocks` of three `dense` | PEFT with the same adapter |

LoRA training is not in this phase. Training needs backpropagation through the KV-cached
attention path, which has never been exercised (LLMs compile with `backprop=false`). It also
needs `capture`'s backward fixed so that it passes the gradient through.

**Gate:** every row has a passing parity test, and every snippet that a published page will
show is one of the tested files.

### Phase 4: reproduce a published measurement

Re-run one measurement from the model-welfare research program in PDSL and compare it with
the published number. For example, a Study 2 projection read: frozen directions read at
layer 18 of Qwen3-4B on a fixed replay. This needs:

- Qwen3 validated as Qwen2.5 is now (QK-norm path, config inference);
- a 4B checkpoint running at a speed that makes a small replay practical (`QWEN_PERFORMANCE.md`
  reports about 735 ms per token at 0.5B).

**Gate:** the PDSL number matches the published one within its reported uncertainty.

This is the evidence that the language is a research tool, not only a teaching one.

### Phase 5: teaching material and publication

- A walk-through in `docs/tutorials/`. None of the current tutorials mention PDSL.
- The highlighted-variants page on the research program's site, built from the tested files.
- A small set of exercises with answers, each a one-line change. For example: "find where the
  model forgets which position it is at", or "make the model ignore head 3 of layer 10".

## Out of scope

- Training loops written in PDSL. `ModelOptimizer` owns training, and the runner owns
  generation; PDSL describes what the model computes, not how it is driven.
- Competing with PyTorch on speed or breadth. The claim is legibility and the cost of the
  first experiment, not throughput.
- Quantization. The research program's quantization ladder is a natural Phase 6, but it needs
  quantized-weight support in the framework that does not exist yet.

## Open questions

1. **What format for results?**
   - Following Decision 5, every `record(...)` should be written as `CollectionLibraryData`
     protobuf, so results go back in as imports. A recorded direction from one run can be the
     steering vector of the next with no conversion; that loop is a strong teaching point.
   - For people working in a spreadsheet, also write CSV for scalar reads such as probe
     scores and logit-lens top tokens.
   - Still open: whether raw vectors also need a format readable without protobuf tooling.
2. **Distribution.** How does a newcomer get the runner? Options include a prebuilt jar with a
   wrapper script, a container image, or a Homebrew formula. A JDK on the machine is the
   minimum requirement any of these imposes.
3. **A second architecture for contrast.** GPT-2 (LayerNorm, learned positions, GELU, no
   gating) would let a reader see what is common to transformers and what is a design
   choice. It needs `layernorm` and learned-position builtins.
4. **Sweeps.** A strength sweep is a list of values for a `producer([1])` slot. Should that
   list be a runner argument (`--sweep strength=0,2,4,8`), or an imported data file? Decision 2
   suggests the runner: the sweep is how the experiment is driven, not what the model computes.
