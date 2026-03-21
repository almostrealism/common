# Almost Realism Common - Development Guidelines for Claude Code

---

# MODULE MAP

```
common/
├── base/                          # Layer 1 — Foundation
│   ├── uml/                       #   ar-uml: Annotations, lifecycle, semantic metadata
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
│   ├── chemistry/                 #   ar-chemistry: Periodic table, elements, electron configurations
│   └── llvm/                      #   ar-llvm: LLVM IR / C code integration via GraalVM polyglot
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
├── flowtree/                      # FlowTree — Workflow Orchestration
├── flowtreeapi/                   #   FlowTree API and protocol abstractions
├── flowtree-python/               #   Python bindings for FlowTree
├── graphpersist/                  #   Database persistence, NFS/SSH, graph storage
│
├── tools/                         # Standalone — Dev tools, MCP servers
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

For debugging/CI failures: extract component names from the error, consult about those components, THEN investigate.

**Why mechanical:** Judgment-based rules fail because you always think the current task is an exception.

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

# CODE RULES

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
- **[CI Pipeline](.github/workflows/analysis.yaml)** — Build and test workflow
- **Module guidelines**: [ML](./engine/ml/claude.md), [Graph](./domain/graph/README.md), [Collect](./base/collect/README.md)
