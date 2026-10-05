# PDSL for Research and Education

**Status:** Draft for discussion. Nothing here has been implemented.

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
   Neither should be a special mode of the runner. Library layers such as `steer(...)` and
   `lora_dense(...)` are welcome, but they are themselves PDSL that the reader can open,
   written in an `interventions.pdsl` asset next to `transformer.pdsl`.
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
   keys select. The KV cache stays caller-allocated, as `state attention_cache` already
   declares, but it is allocated per layer by the runner from the declaration, not by hand
   in `AttentionFeatures`.
4. **A layer signature that takes `config` and a weight prefix,** so the call site inside the
   loop stays one line. The existing long-argument layers remain for Java callers.
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
5. **Weights without Python.** Today the checkpoint must be converted to protobuf by
   `extract_qwen3_weights.py`. Read safetensors directly from Java, or ship a one-command
   converter, so the first step does not need a Python environment. The setMem ingest rules
   apply here.

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

1. **Which model first?** Recommendation: Qwen2.5-0.5B-Instruct for Phases 1 to 3, because it
   is the only validated checkpoint and it is small enough to run anywhere. Qwen3 comes with
   Phase 4. The published page could show either; the structural difference is one line (QK-norm).
2. **Where do experiment settings live?** Options:
   - (a) runner flags plus a prompts file;
   - (b) an `experiment` block in PDSL listing prompts, sweeps and records;
   - (c) a separate small settings file.

   Recommendation: (a) first, then decide between (b) and (c) after seeing what the Phase 3
   examples actually need. (b) keeps everything on one page, but mixes "what the model is"
   with "what we do to it".
3. **What format for results?** CSV is readable in a spreadsheet, but cannot hold a 896-wide
   vector per token comfortably. NumPy `.npy` needs Python. Possibly both: CSV for scalar
   reads (probe scores, logit-lens top tokens), `.npy` for raw vectors.
4. **Distribution.** How does a novice get the runner? Options include a prebuilt jar with a
   wrapper script, a container image, or a Homebrew formula. A JDK on the machine is the
   minimum requirement any of these imposes.
5. **Should library layers such as `steer` and `lora_dense` exist at all,** or should the
   canonical examples always write the composition out in full? Writing it out teaches more.
   A library is shorter to read.
6. **A second architecture for contrast.** GPT-2 (LayerNorm, learned positions, GELU, no
   gating) would let a reader see what is common to transformers and what is a design
   choice. It needs `layernorm` and learned-position builtins.
