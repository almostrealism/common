# Almost Realism Common - Development Guidelines for Claude Code

---

# MODULE MAP

| Directory | Module | Description |
|-----------|--------|-------------|
| `uml/` | ar-uml | Foundation layer: UML abstractions, no dependencies |
| `relation/` | ar-relation | Computation relations, process optimization |
| `code/` | ar-code | Expression trees, scope management, code generation |
| `io/` | ar-io | File I/O, console logging, output features |
| `algebra/` | ar-algebra | Vector, matrix, and algebraic operations |
| `collect/` | ar-collect | PackedCollection: hardware-accelerated tensor storage |
| `hardware/` | ar-hardware | GPU/CPU acceleration, memory management, kernel compilation |
| `graph/` | ar-graph | Neural network layers, computation graphs, backpropagation |
| `optimize/` | ar-optimize | Optimization algorithms and graph analysis |
| `ml/` | ar-ml | Large language models, transformers, attention, tokenizers |
| `ml-djl/` | ar-ml-djl | DJL framework integration for model loading |
| `ml-onnx/` | ar-ml-onnx | ONNX runtime integration for inference |
| `ml-script/` | ar-ml-script | Groovy script execution for model scripting |
| `audio/` | ar-audio | Audio synthesis, signal processing, filters, sample playback |
| `music/` | ar-music | Musical patterns, notes, scales, composition |
| `compose/` | ar-compose | Audio scene composition, arrangement, effects processing |
| `spatial/` | ar-spatial | Spatial audio, 3D sound, composition integration |
| `geometry/` | ar-geometry | Rays, vectors, transformations, ray-tracing infrastructure |
| `color/` | ar-color | RGB/RGBA colors, lighting models, shaders, textures |
| `render/` | ar-render | Ray tracing, rendering pipeline, image output |
| `space/` | ar-space | 3D scene representation, objects, spatial hierarchy |
| `physics/` | ar-physics | Atomic/molecular structures, simulation, forces |
| `chemistry/` | ar-chemistry | Periodic table and chemical element representations |
| `heredity/` | ar-heredity | Genetic algorithms, evolution, probabilistic factories |
| `stats/` | ar-stats | Statistical operations and probability distributions |
| `time/` | ar-time | Temporal operations, timing, frequency, sequences |
| `llvm/` | ar-llvm | LLVM polyglot integration for code generation |
| `utils/` | ar-utils | Cross-module utilities and core framework services |
| `utils-http/` | ar-utils-http | HTTP client utilities and REST integration |
| `graphpersist/` | ar-graphpersist | Database persistence, NFS/SSH access, graph storage |
| `flowtree/` | ar-flowtree | Workflow orchestration controller with Slack integration |
| `flowtreeapi/` | ar-flowtreeapi | FlowTree API and protocol abstractions |
| `flowtree-python/` | ar-flowtree-python | Python bindings for FlowTree |
| `tools/` | ar-tools | JavaFX development tools; also contains `mcp/` MCP servers |
| `docs/` | — | Documentation portal, internals, tutorials, API reference |
| `scripts/` | — | Build and code generation helper scripts |

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

Call `mcp__ar-consultant__remember` **immediately** when you:
- Fix a bug (root cause + fix)
- Complete a task (what changed and why)
- Discover a non-obvious behavior or gotcha
- Make or learn about a design decision
- Find something that does NOT work

Do NOT wait to be asked. Do NOT defer to end of session. Store **as it happens**.

Use namespaces (`bugs`, `decisions`, `context`, `progress`) and tags liberally. Include file paths, class names, and the "why."

**Why this matters:** You lose all context between sessions. Without memories, every session starts from zero.

## Rule 3: RECALL MEMORIES BEFORE STARTING WORK

Call `mcp__ar-consultant__recall` at the start of every new task to check for prior context, decisions, and findings. Prior sessions may have left exactly the information you need.

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

**Required environment variable** before running Java code:
```bash
export AR_HARDWARE_LIBS=/tmp/ar_libs/
```

Leave `AR_HARDWARE_DRIVER` unset — the system auto-selects the best backend.

Forgetting `AR_HARDWARE_LIBS` causes `NoClassDefFoundError: PackedCollection` and similar failures.

**Memory configuration** for large models or `HardwareException: Memory max reached`:
```bash
export AR_HARDWARE_MEMORY_SCALE=8   # 16GB (default is 7 = 8GB)
```

See [hardware/README.md](hardware/README.md).

**Build verification** — before declaring a task complete:
```bash
export AR_HARDWARE_LIBS=/tmp/ar_libs/ && mvn clean install -DskipTests
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
- **Module guidelines**: [ML](./ml/claude.md), [Graph](./graph/README.md), [Collect](./collect/README.md)
