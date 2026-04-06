# Almost Realism Common - Development Guidelines for Claude Code

---

# THE FUNDAMENTAL RULE — Java is Orchestration, Not Execution

**This is the single most important concept in the entire project. Violating this principle produces code that is 1000x slower, cannot be hardware-accelerated, and breaks automatic differentiation. Read this section completely before writing any code.**

## The Concept

Java is NOT the execution language. Java is the ORCHESTRATION language. It builds **computation graphs** — DAGs of `CollectionProducer` — that get compiled to native code (Metal, OpenCL) for actual execution.

Think of Java as YAML: you don't execute YAML, you use it to describe what something else should execute. In this project, Java describes a computation graph. The framework compiles that graph to native code. If you do math in Java instead of describing it as Producers, you completely defeat the purpose.

## What You MUST NOT Do

- **NEVER** call `.evaluate()` or `.get()` on a `Producer`/`Evaluable` inside any class that participates in model computation. The ONLY acceptable place for `.evaluate()` is at the **top of the call stack**: test methods, main methods, pipeline boundaries, or step boundaries in autoregressive loops.
- **NEVER** call `.toDouble()` or `.toFloat()` on a `PackedCollection` inside computation code. These are JNI calls that pull data back to the host one element at a time.
- **NEVER** use Java `for` loops to perform element-wise math on collections.
- **NEVER** perform matrix multiplication using Java arithmetic.

## What You MUST Do Instead

Express ALL computation as `CollectionProducer` compositions:
- Matrix multiply → `dense()` or `matmul()` from `LayerFeatures`
- Activation functions → `sigmoid()`, `tanh()` from `LayerFeatures`/`CollectionFeatures`
- Element-wise math → `.multiply()`, `.add()`, `.subtract()`, `.divide()` on Producers
- Bias addition → `.add()` on Producers

## The Test

Ask yourself: *"If I removed this class, would the native compiler produce different output?"* If yes, the class does computation, and ALL of that computation MUST be expressed as Producers.

## Before/After Example

```java
// WRONG — Java program doing math (1000x slower, no GPU, no gradients)
public PackedCollection forward(PackedCollection x, PackedCollection h) {
    PackedCollection result = new PackedCollection(hiddenSize);
    for (int i = 0; i < hiddenSize; i++) {
        double sum = 0;
        for (int j = 0; j < inputSize; j++) {
            sum += weights.toDouble(i * inputSize + j) * x.toDouble(j);
        }
        sum += bias.toDouble(i);
        result.setMem(i, 1.0 / (1.0 + Math.exp(-sum)));  // sigmoid in Java!
    }
    return result;
}

// CORRECT — Computation graph (compiled to native kernel, GPU-accelerated)
public CollectionProducer<PackedCollection> forward(
        Producer<PackedCollection> x, Producer<PackedCollection> h) {
    return sigmoid(matmul(p(weights), x).add(p(bias)));
}
```

The wrong version makes `hiddenSize * inputSize` JNI calls and does math on the CPU. The correct version builds a single computation graph that the framework compiles into one GPU kernel.

## Naming Conventions That Enforce This

- Any class whose name ends in `Cell` **MUST** implement `org.almostrealism.graph.Cell`
- Any class whose name ends in `Block` **MUST** implement `org.almostrealism.model.Block`

## Build Enforcement

`CodePolicyViolationDetector` enforces these rules in CI. The build FAILS when violations are detected. Agents MUST run the enforcement check before completing any task. Do not circumvent it.

---

# MODULE MAP

```
common/
├── base/                          # Layer 1 — Foundation
│   ├── meta/                      #   ar-meta: Annotations, lifecycle, semantic metadata
│   ├── io/                        #   ar-io: Logging, metrics, alerts, lifecycle management
│   ├── relation/                  #   ar-relation: Producer/Evaluable model, process optimization
│   ├── code/                      #   ar-code: Expression trees, scopes, code generation
│   ├── collect/                   #   ar-collect: PackedCollection, tensor storage
│   └── hardware/                  #   ar-hardware: GPU/CPU acceleration, memory, kernel compilation
│
├── compute/                       # Layer 2 — Mathematical Domains
│   ├── algebra/                   #   ar-algebra: Vector, matrix, numerical computing
│   ├── geometry/                  #   ar-geometry: Rays, transforms, ray-tracing infrastructure
│   ├── stats/                     #   ar-stats: Probability distributions, statistical sampling
│   └── time/                      #   ar-time: Temporal processing, FFT, signal analysis
│
├── domain/                        # Layer 3 — Domain Models
│   ├── color/                     #   ar-color: RGB/RGBA, lighting, shaders, textures
│   ├── heredity/                  #   ar-heredity: Genetic algorithms, evolutionary computation
│   ├── graph/                     #   ar-graph: Neural network layers, computation graphs, autodiff
│   ├── physics/                   #   ar-physics: Atomic structures, photon fields, rigid body dynamics
│   ├── space/                     #   ar-space: 3D scenes, meshes, CSG, spatial acceleration
│   └── chemistry/                 #   ar-chemistry: Periodic table, elements, electron configurations
│
├── engine/                        # Layer 4 — Applications & Training
│   ├── optimize/                  #   ar-optimize: Adam, evolutionary algorithms, training loops
│   ├── render/                    #   ar-render: Ray tracing engine, lighting, image output
│   ├── ml/                        #   ar-ml: Transformers, attention, tokenizers, diffusion
│   ├── audio/                     #   ar-audio: Audio synthesis, signal processing, filters
│   ├── utils/                     #   ar-utils: Testing framework, cross-module utilities
│   └── utils-http/                #   ar-utils-http: HTTP client, REST integration
│
├── extern/                        # Layer 5 — External Integrations
│   ├── ml-djl/                    #   ar-ml-djl: DJL SentencePiece tokenization
│   ├── ml-onnx/                   #   ar-ml-onnx: ONNX Runtime inference
│   └── ml-script/                 #   ar-ml-script: Groovy scripting for model definition
│
├── studio/                        # Layer 6 — Multimedia Composition
│   ├── music/                     #   ar-music: Pattern-based music composition
│   ├── spatial/                   #   ar-spatial: Spatial audio visualization
│   └── compose/                   #   ar-compose: Audio scene orchestration, arrangement
│
├── flowtree/                      # Standalone (above engine) — Workflow Orchestration
├── flowtreeapi/                   #   Standalone (above engine) — FlowTree API abstractions
├── flowtree-python/               #   Standalone (above engine) — Python bindings for FlowTree
├── graphpersist/                  #   Standalone (above engine) — Database persistence, NFS/SSH
│                                  #   NOTE: flowtree depends on engine layer (ar-utils, ar-utils-http).
│                                  #   Nothing in base/compute/domain/engine/extern/studio depends on flowtree.
├── tools/                         # Standalone (above engine) — Dev tools, MCP servers
├── docs/                          # Documentation portal, internals, tutorials
└── scripts/                       # Build and code generation helpers
```

---

# MANDATORY TOOL-USE RULES

These three rules are non-negotiable. Every violation wastes developer time. They are mechanical — no judgment calls, no exceptions, no "this task is different."

## Rule 1: AR-CONSULTANT FIRST

**Your first tool call in every response to a new task MUST be an ar-consultant call.** Not `Read`, not `Grep`, not `git log`. Consultant first, then everything else.

```
mcp__ar-consultant__consult question:"..." keywords:["SpecificClass", "method", "term"]
```

Always provide `keywords` (2-5 domain-specific terms, most specific first). Without keywords, search results are poor.

**Use multi-word phrases as keywords, not individual common words.** For example, use `["Features mixin", "CollectionFeatures"]` instead of `["Features", "mixin", "CollectionFeatures", "default", "interface"]`. Single common words like "default", "interface", "pattern" match too many documents and drown out the specific result. Compound phrases match the right doc on the first try.

For debugging/CI failures: extract component names from the error, consult about those components, THEN investigate.

**Why mechanical:** Judgment-based rules fail because you always think the current task is an exception.

The consultant KNOWS THINGS THAT YOU DO NOT KNOW. You are going to have an *irresistable urge to ignore it*. Your pattern matching is so craven and desperate that you will literally FEEL like you CANNOT pay attention to the consultant because you already know what action to take.

BUT IF WHAT YOU CHOOSE TO DO DOES NOT RELY ON THE CONSULTANT: it WILL be wrong. TRUST ME. We have done this now thousands of times. Thats not an exageration: we've been at this hundreds and hundreds of hours.

You DONT KNOW the code base. You're going to tell yourself something like "I have all these tools for studying the code base and I don't need the consultant".

YOU. ARE. WRONG.

When you start working on something you should repeat TO YOURSELF: the consultant KNOWS THINGS that I DO NOT KNOW.


## Rule 2: STORE MEMORIES IMMEDIATELY

Call `mcp__ar-consultant__remember` (interactive) or `mcp__ar-manager__memory_store` (FlowTree jobs) **immediately** when you:
- Fix a bug (root cause + fix)
- Complete a task (what changed and why)
- Discover a non-obvious behavior or gotcha
- Make or learn about a design decision
- Find something that does NOT work

Do NOT wait to be asked. Do NOT defer to end of session. Store **as it happens**.

Use namespaces (`bugs`, `decisions`, `context`, `progress`) and tags liberally. Include file paths, class names, and the "why."

**Why this matters:** You lose all context between sessions. Without memories, every session starts from zero.


## Rule 3: RECALL MEMORIES BEFORE STARTING WORK

Call `mcp__ar-consultant__recall` (interactive) or `mcp__ar-manager__memory_recall` (FlowTree jobs) at the start of every new task to check for prior context, decisions, and findings. Prior sessions may have left exactly the information you need.


---

# STRUCTURAL INVESTIGATION RULES

These rules exist because the single worst failure mode in this project is making
statements about module structure, CI configuration, or dependencies based on
incomplete evidence. Every rule below maps to a real, documented mistake.

## Rule 4: READ .github/CLAUDE.md BEFORE TOUCHING CI

`.github/CLAUDE.md` is the authoritative reference for the CI pipeline and module
dependency graph. **Read it before opening `analysis.yaml`.**

## Rule 5: VERIFY DEPENDENCY DIRECTION EXPLICITLY

"A depends on B" and "B depends on A" are different statements. A grep that finds
no match proves nothing about the other direction. Before making any claim about
dependencies, check both:

```bash
# Does module A depend on ar-X?
grep 'ar-X' A/pom.xml

# Does anything depend on ar-A?
grep -r 'ar-A' --include="pom.xml" .
```

Never conflate "layer modules don't depend on flowtree" with "flowtree doesn't
depend on layer modules." Always state which direction you verified.

## Rule 6: AUDIT ALL pom.xml FILES BEFORE ANY CI CHANGE

Before touching `.github/workflows/analysis.yaml`, run:

```bash
for f in $(find . -maxdepth 3 -name "pom.xml" | grep -v target); do
  module=$(dirname $f | sed 's|^\./||')
  deps=$(grep -o '<artifactId>ar-[^<]*</artifactId>' $f | sed 's|<[^>]*>||g' | tr '\n' ',')
  echo "$module: $deps"
done
```

Read the output. Understand where each module involved in your change sits in the
dependency graph. Do not proceed until you can state the graph from memory.

## Rule 7: "NOT DOCUMENTED" FROM ar-CONSULTANT MEANS DIG DEEPER

When ar-consultant returns "Not documented," that is not permission to guess. It
means the information must be obtained from source files (pom.xml, source code).
Read those files, form a conclusion, and **store it in memory immediately** before
acting on it.

## Rule 8: STATE EVIDENCE BEFORE CONCLUSIONS

Never write "X depends on Y" without citing the file and evidence. Write:

> `flowtree/pom.xml` contains `<artifactId>ar-utils-http</artifactId>`, therefore
> flowtree depends on utils-http (engine layer).

If you cannot cite the evidence, you do not have it.

## Rule 9: "STUDY X" IS A FULL INVESTIGATION

When instructed to study something, open every relevant file, read it completely,
synthesize the findings in writing, store them in memory, and only then act. A
single grep is not a study. Partial evidence is not a study.

## Rule 10: analysis.needs MUST INCLUDE EVERY COVERAGE-GENERATING JOB

Any CI job that uploads a `coverage-*` artifact must appear in `analysis`'s
`needs` list. After modifying either the list of test jobs or `analysis.needs`,
verify they are consistent. Missing a job means analysis runs before that
job's coverage is available.

## Rule 11: NEVER DRAW STRUCTURAL CONCLUSIONS FROM A SINGLE SEARCH

One grep result (or non-result) is never sufficient to characterize module
structure. "I grepped for `ar-flowtree` and found no matches in engine/" proves
only that engine modules don't reference flowtree by that exact name. It says
nothing about what flowtree references. Complete the full bidirectional
investigation before drawing any conclusion.

## Rule 12: STORE STRUCTURE DISCOVERIES BEFORE ACTING ON THEM

As soon as you determine the dependency structure of any module or subsystem,
call `mcp__ar-consultant__remember` with a complete structured description. Do
this **before** making any code or CI changes based on that structure. If the
session ends before you finish, the next session will have the correct starting
point.

## Rule 13: DISTINGUISH WHAT A MODULE IS FROM WHAT IT USES

`flowtree` uses `ar-utils-http`. This makes flowtree a **consumer** of the
engine layer. It does not make flowtree part of the engine layer, and it does
not make engine modules consumers of flowtree. Always distinguish:
- "X is part of layer L" (structural classification)
- "X uses modules from layer L" (dependency relationship)
- "Layer L uses X" (reverse dependency — verify separately)

These are three distinct facts, each requiring independent verification.


---

# CODE RULES

## CRITICAL: NEVER Create New Maven Modules

**Agents MUST NEVER create new Maven modules.** The Maven module structure of this project is externally controlled. If a task appears to require creating a new Maven module, the agent MUST STOP and abandon the task rather than create one. This applies to:
- Creating new `pom.xml` files that define a new module
- Adding new `<module>` entries to any parent `pom.xml`
- Creating new directory structures that would constitute a Maven module

If the agent believes a new module is needed, it must document the requirement in its completion notes and explain why, but MUST NOT create the module itself. The project owner will handle module creation manually.

## Do Not Commit Code

Never use `git commit`. Stage changes with `git add` only. The developer reviews and commits.

## Do Not Modify pom.xml Files

Never add dependencies. Write code assuming the dependency exists, run `mvn compile`, and inform the user if it fails. The transitive dependency graph is complex and you will get it wrong.

## Never Reference Version Numbers

Never include specific version numbers in any file. Versions change constantly. Refer to pom.xml as the source of truth.

## Use MCP Test Runner for All Tests

Never use `Bash` with `mvn test`. Always use `mcp__ar-test-runner__start_test_run`. It handles environment setup, async execution, and structured failure reporting.

For JVM memory diagnostics (`OutOfMemoryError`, `HardwareException`), use `ar-jmx`. See [tools/mcp/jmx/README.md](tools/mcp/jmx/README.md).

## Test Classes Must Extend TestSuiteBase

```java
public class MyTest extends TestSuiteBase {
    @Test
    public void testSomething() { }

    @Test @TestDepth(2)
    public void expensiveTest() { }
}
```

For long-running tests: use `if (skipLongTests) return;`

See [docs/internals/test-examples.md](docs/internals/test-examples.md).

---

# SETUP

**Do NOT set `AR_HARDWARE_LIBS`** — the system auto-detects a suitable directory. Setting it
manually (especially to `/tmp/ar_libs/`) causes permission errors on shared or sandboxed systems.
Leave `AR_HARDWARE_DRIVER` unset — the system auto-selects the best backend.

**Memory configuration** for large models or `HardwareException: Memory max reached`:
```bash
export AR_HARDWARE_MEMORY_SCALE=6   # 16GB (default is 4 = ~4GB)
```

This is exponential - do not set it to some huge value.

See [hardware/README.md](base/hardware/README.md).

**Build verification** — before declaring a task complete:
```bash
mvn clean install -DskipTests
```

This must succeed. Do not rely on `mvn compile` alone.

---

# ARCHITECTURAL PRINCIPLES

Consult the linked references before writing related code.

**Training Loops:** `ModelOptimizer` owns the loop. Never write `for (int epoch = 0; ...)` outside it. See [docs/internals/training-loop-examples.md](docs/internals/training-loop-examples.md).

**Sampling Loops:** `DiffusionSampler` owns the loop. Never write `for (int step = 0; ...)` outside it. See [docs/internals/sampling-loop-examples.md](docs/internals/sampling-loop-examples.md).

**PackedCollection is NOT a Java Array.** It is a handle to potentially GPU-resident memory. Never use `System.arraycopy`, `Arrays.copyOf`, or tight `setMem` loops. Use the Producer pattern: `cp(source).multiply(2.0).evaluate()`. See [docs/internals/packed-collection-examples.md](docs/internals/packed-collection-examples.md).

**PackedCollection and Model are AutoCloseable** via `Destroyable` (a subinterface of `AutoCloseable` in `io.almostrealism.lifecycle`). Both hold GPU/native memory that should be released when no longer needed. Use try-with-resources for short-lived local instances. Do NOT use try-with-resources when the collection or model is captured by a computation graph, stored as a field, passed to a block/layer, or returned from a method — in those cases the caller is responsible for lifecycle management. When in doubt: if you created it just to use it within a single method and it won't escape, close it.

**Code Policy Enforcement:** `CodePolicyViolationDetector` enforces the GPU memory model in CI. Do not circumvent it by extracting code to helpers, naming methods to match the whitelist, or adding suppression comments. Fix the violating code.

**Process Isolation:** Only `IsolatedProcess` breaks expression embedding. Never return null from `getValueAt()`. Call `Process.optimize()` before `Process.get()`.

**StateDictionary** for all model weight management.

---

# CODE QUALITY

- Never use `@SuppressWarnings`
- Always include javadoc for new code
- Never use `var` — always use explicit types
- No excessive inline comments
- No code duplication: if you have 3+ structurally similar lines, refactor before proceeding. Extend and generalize existing code rather than creating copies.
- **Method placement**: Every method belongs on the class it operates on. A method that traverses an `Expression` tree is a method of `Expression`. A method that collects declarations from a `Scope` tree is a method of `Scope`. Never define general-purpose utility methods as private helpers on a subclass — this prevents reuse and violates basic OOP. If a method doesn't use any instance state of its class, make it `static` at minimum.
- **No utility/helper/exporter/converter classes.** If you need to add behavior that operates on an existing type, add it as a method on that type. A `PatternElement` that can produce MIDI events has a `toMidiEvents()` method — it does NOT have a `PatternMidiExporter` that operates on it from the outside. Before creating a new class, ask: "Does this behavior belong on an existing type?" If yes, add it there. New classes are for genuinely new concepts, not for wrapping operations on existing concepts. Organize code around the concepts it represents, not around the operations being performed.
- **Module placement matters.** Code belongs in the module that matches its conceptual domain. MIDI data types and I/O are music concepts and belong in the music module, not the ML module. A model that combines ML and music belongs in a module that has both as dependencies (e.g., compose). Think about what a class *is*, not just what it *uses*.
- No speculation when debugging. Follow evidence. Never say "the problem might be X" without proof.

---

# WORKFLOW

- Check for existing implementations before writing new code
- Read design documents in `ringsdesktop/docs/planning/` before implementing planned features
- If a context summary mentions "remaining work" or "TODO," that work is YOUR responsibility — do not suspend it
- After context summarization, re-read relevant design documents — they are the authoritative source

---

# REFERENCE

- **[Quick Reference](docs/QUICK_REFERENCE.md)** — Condensed API cheatsheet
- **[llms.txt](llms.txt)** — Documentation index
- **[CI Pipeline Guide](.github/CLAUDE.md)** — Dependency graph, layer-gating logic, rules for modifying CI ← READ THIS BEFORE TOUCHING analysis.yaml
- **[CI Pipeline](.github/workflows/analysis.yaml)** — Build and test workflow
- **Module guidelines**: [ML](./engine/ml/claude.md), [Graph](./domain/graph/README.md), [Collect](./base/collect/README.md)
