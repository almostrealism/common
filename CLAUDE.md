# Almost Realism Common - Development Guidelines for Claude Code

---

# STOP. READ THIS FIRST.

## ABSOLUTE PREREQUISITE: USE AR-CONSULTANT BEFORE ANY ACTION

**THIS IS THE MOST IMPORTANT RULE. IT COMES BEFORE ALL OTHER RULES.**

The `ar-consultant` MCP server is a documentation-aware assistant that combines documentation search, semantic memory, and local LLM inference into a single interface. It **replaces direct use of `ar-docs` and `ar-memory`** for most tasks. Use it as your primary tool for understanding the codebase, recalling prior context, and storing new knowledge.

**BEFORE you write ANY code, make ANY assumptions, or take ANY action, you MUST consult:**

```
mcp__ar-consultant__consult question:"<your question>" keywords:["RelevantClass", "specific_method", "domain_term"]
```

**IMPORTANT: Always provide keywords.** The `keywords` parameter dramatically improves search relevance. Provide 2-5 domain-specific terms ordered by importance (most specific first). Without keywords, generic words in your question may match irrelevant documentation.

```
# GOOD: Keywords help find relevant docs
consult question:"How do I create an attention layer?" keywords:["AttentionFeatures", "attention", "LayerFeatures"]

# BAD: No keywords - may return irrelevant results
consult question:"How do I create a simple attention layer for testing?"
```

For specific documentation lookups:
```
mcp__ar-consultant__search_docs query:"<search terms>"
```

**YOU ARE NOT ALLOWED TO:**
- Assume you know how something works
- Speculate about architecture or data flow
- Make claims about what is or isn't stored/available
- Write code based on partial understanding
- Say "the problem might be X" without first looking it up

**THE AR CODEBASE IS A PRODUCTION APPLICATION** used by real people worldwide. If something seems like it "doesn't work" or "isn't stored," YOU ARE WRONG. The application works. You need to LOOK UP how it works.

**EVERY TIME you are about to:**
- Implement a feature -> CONSULT first
- Fix a bug -> CONSULT first
- **Investigate a CI/test failure** -> CONSULT first
- **Debug any issue** -> CONSULT first
- **Run git commands to understand changes** -> CONSULT first
- Answer a question about architecture -> CONSULT first
- Modify existing code -> CONSULT first
- Make ANY claim about the codebase -> CONSULT first

**"Investigation" and "debugging" ARE actions.** Running `git log`, `git diff`, reading test files, or exploring code changes are NOT exempt from this rule. You must understand the component architecture BEFORE looking at what changed.

**Example of WRONG behavior:**
```
User: "The prototype discovery doesn't show file paths"
Claude: "The protobuf schema only stores MD5 hash, not file path..."
```
This is WRONG because Claude did NOT consult the documentation to understand how the actual application handles this.

**Example of CORRECT behavior:**
```
User: "The prototype discovery doesn't show file paths"
Claude: [Calls mcp__ar-consultant__consult question:"How does AudioLibrary handle file path identifiers in prototype discovery?" keywords:["AudioLibrary", "PrototypeDiscovery", "identifier", "filepath"]]
Claude: [Now has a documentation-grounded answer with sources cited before responding]
```

**If the Consultant doesn't have enough information:**
1. READ the actual source code thoroughly
2. TRACE the data flow from end to end
3. NEVER guess or speculate

### Specific Scenarios Requiring Consultation

**Infrastructure changes (tests, build, framework classes):**
```
WRONG: See TestDepthRule in source, assume how it works, add @Rule manually
RIGHT: Consult first → Learn TestDepthRule is INTERNAL to TestSuiteBase
```

**API discovery (finding operations, interfaces, utilities):**
```
WRONG: Grep source for "sin" → Don't find it → Conclude "doesn't exist"
RIGHT: mcp__ar-consultant__search_docs query:"trigonometry sin cos" → Find sin/cos in GeometryFeatures
```

**Understanding data flow (how library handles file paths, identifiers, etc.):**
```
WRONG: Read one class → Make assumptions → Write incorrect code
RIGHT: Consult → Get synthesized answer with source references → Understand
```

**Complex topics requiring back-and-forth:**
```
mcp__ar-consultant__start_consultation topic:"How does process isolation interact with attention layers?"
mcp__ar-consultant__continue_consultation session_id:"..." message:"What about the QK-norm case?"
mcp__ar-consultant__end_consultation session_id:"..."  # Summary stored as memory
```

**This rule exists because:** Claude repeatedly makes assumptions, writes incorrect code, and wastes the developer's time. The Consultant has access to the full documentation corpus, prior session memories, and a local LLM for synthesis. USE IT.

### Available Consultant Tools

| Tool | Purpose | When to Use |
|------|---------|-------------|
| `consult` | Ask a question, get a documentation-grounded answer | Default for any question about the codebase |
| `search_docs` | Search docs with Consultant summary | When you need raw doc results with a synthesis |
| `recall` | Search memories, contextualized with docs | Check for prior decisions, findings, or progress |
| `remember` | Store a memory with Consultant reformulation | After completing work, finding bugs, making decisions |
| `start_consultation` | Begin multi-turn session | Complex topics needing back-and-forth |
| `continue_consultation` | Follow up in a session | Refining understanding of a complex topic |
| `end_consultation` | End session, auto-store summary | Done with a multi-turn consultation |
| `consultant_status` | Check backend health | Verify the Consultant is operational |
| `list_request_history` | List recent Consultant calls | Check what was already asked in this session |
| `export_request_history` | Export full history for analysis | Quality review, dataset construction |

### Direct Tool Access (When Needed)

The `ar-docs` and `ar-memory` MCP servers are still available for direct access. Use them when:

- **ar-docs** (`mcp__ar-docs__*`): You need raw documentation without LLM synthesis (e.g., reading a full module page, checking quick reference, searching source comments)
- **ar-memory** (`mcp__ar-memory__*`): You need raw memory operations without reformulation (e.g., deleting entries, listing by tag, bulk operations)

For **all other documentation and memory needs**, prefer `ar-consultant`. It searches the same documentation and memory stores but adds synthesis, contextualization, and quality control.

---

## ⚠️ CRITICAL: USE MEMORY AGGRESSIVELY ⚠️

**Persistent memory is essential for cross-session continuity. USE IT.**

The Consultant's `remember` tool stores memories after reformulating them to be consistent with project terminology. The Consultant's `recall` tool searches memories and contextualizes them with current documentation. **You MUST use these aggressively.**

### When to STORE memories (use `remember`)

**Store EVERY TIME you:**
- Make a design decision or learn why something was done a certain way
- Discover a non-obvious behavior, gotcha, or quirk in the codebase
- Complete a task (summarize what was done, what files changed, and why)
- Encounter and resolve a bug (record the root cause and fix)
- Learn something about the architecture that isn't in the docs
- Receive explicit instructions or preferences from the user
- Identify a pattern, convention, or anti-pattern in the codebase
- Find that something does NOT work (so future sessions don't repeat the mistake)
- Start a multi-session task (record progress, next steps, and open questions)

**Example of WRONG behavior:**
```
[Spends 30 minutes debugging a FAISS index issue, finds the fix]
[Does NOT store the finding]
[Next session: re-discovers the same issue from scratch]
```

**Example of CORRECT behavior:**
```
[Fixes the issue]
[Calls mcp__ar-consultant__remember content:"FAISS index rebuild required after..." namespace:"bugs" tags:["memory","faiss"]]
[Next session: recall finds the prior work immediately, contextualized with current docs]
```

### When to SEARCH memories (use `recall`)

**Search EVERY TIME you:**
- Start a new session or task (check for prior context)
- Work on a module or feature area (check for prior decisions/findings)
- Encounter an error or unexpected behavior (check if it was seen before)
- Are about to make a design decision (check if it was already decided)
- Resume work that may have started in a prior session

### Best practices for memory

- Use **namespaces** to organize: `"decisions"` for design choices, `"bugs"` for issues found, `"context"` for codebase knowledge, `"progress"` for multi-session task tracking
- Use **tags** liberally -- they enable filtered searches later
- Write **detailed content** -- include file paths, class names, method names, and the "why" not just the "what"
- **Search before you start working** -- prior sessions may have left you exactly the context you need
- When completing a multi-step task, store a **progress summary** with next steps so the next session can pick up seamlessly
- The Consultant will reformulate your notes for terminology consistency -- write naturally and let the reformulation handle the polish

**This rule exists because:** Claude loses all context between sessions. Without aggressive memory use, every session starts from zero. The Consultant's memory system makes cross-session continuity possible. USE IT.

See [docs/internals/ar-docs-examples.md](docs/internals/ar-docs-examples.md) for detailed wrong/right examples.

---

## MECHANICAL GATE: AR-CONSULTANT IN FIRST RESPONSE

**Your first response to any task MUST include an ar-consultant call before any other tool calls.**

This is a mechanical requirement, not a judgment call. If your first tool call is `git log`, `git diff`, `Read`, `Grep`, or any tool other than an ar-consultant MCP tool, you are violating this rule.

**Correct first response pattern:**
```
1. mcp__ar-consultant__consult question:"<question about component/test/feature>"
2. THEN git commands, file reads, etc.
```

**Why this is mechanical:** Judgment-based rules ("consult when relevant") fail because Claude always thinks the current task is an exception. Making it mechanical removes ambiguity.

---

## DEBUGGING PROTOCOL: CONSULT FIRST, THEN INVESTIGATE

**When a user reports a CI failure, test failure, or bug:**

1. **FIRST**: Extract component/test names from the error message
2. **SECOND**: Consult about those components: `mcp__ar-consultant__consult question:"How does <component> work? What are its dependencies and expected behavior?"`
3. **THIRD**: NOW you may run git commands, read files, investigate changes

**Example - CI failure in OobleckComponentTests:**
```
WRONG ORDER:
1. git log to see what changed  <- VIOLATION
2. git diff to see the changes  <- VIOLATION
3. Read the test file           <- VIOLATION

CORRECT ORDER:
1. mcp__ar-consultant__consult question:"How does the Oobleck decoder block work in the ml module?" keywords:["Oobleck", "decoder", "OobleckDecoder", "ml"]
2. NOW: git log, git diff, Read test file
```

**The reason for this order:** You cannot effectively investigate changes if you don't understand what the component is supposed to do. Understanding architecture first prevents wasted effort chasing red herrings.

---

## CRITICAL: DO NOT COMMIT CODE

**THIS IS AN ABSOLUTE RULE WITH NO EXCEPTIONS.**

- **NEVER** use `git commit` commands
- Claude does not have the ability to create valid commits
- You can only **stage changes** using `git add`
- The human developer must review and commit all changes themselves

**Why this matters:** Claude cannot properly sign commits or verify the full context of changes. The human developer needs to review staged changes, write appropriate commit messages, and take responsibility for what goes into the repository history.

**What you CAN do:** `git add <file>`, `git status`, `git diff`, `git reset <file>`

**What you MUST NOT do:** `git commit` (any form), `git commit -m "..."`, `git commit --amend`

---

## CRITICAL: DO NOT MODIFY POM.XML FILES

**THIS IS AN ABSOLUTE RULE WITH NO EXCEPTIONS.**

- **NEVER** add dependencies to pom.xml files unless 100% certain the dependency is not already available transitively
- **NEVER** assume you understand the module dependency graph - it is complex and you WILL get it wrong
- **IF IN DOUBT, DO NOT TOUCH THE POM FILE** - just use the classes and let compilation fail if the dependency is truly missing
- The human developer will fix any missing dependencies - this is NOT your job

**Why this matters:** The AR project has a complex transitive dependency structure. Adding a dependency that already exists transitively creates redundant declarations, can cause version conflicts, and wastes time.

**What to do instead:**
1. Write your code assuming the dependency exists
2. Run `mvn compile` to verify
3. If compilation succeeds, the dependency was already available transitively
4. If compilation fails with "package does not exist", inform the user - DO NOT modify the pom.xml yourself

**The ONLY exception:** User explicitly instructs you to add a specific dependency.

---

## CRITICAL: NEVER REFERENCE VERSION NUMBERS

**THIS IS AN ABSOLUTE RULE WITH NO EXCEPTIONS.**

- **NEVER** include specific version numbers anywhere in CLAUDE.md files
- **NEVER** mention library versions (e.g., "JavaFX 21", "gRPC 1.53.0")
- **NEVER** mention project versions (e.g., "version 0.72")
- Version numbers change constantly and become stale immediately
- Always refer to pom.xml files as the single source of truth for versions

---

## CRITICAL: USE MCP TEST RUNNER FOR ALL TESTS

**THIS IS AN ABSOLUTE RULE WITH NO EXCEPTIONS.**

- **NEVER** use `Bash` tool with `mvn test` commands to run tests
- **ALWAYS** use the `mcp__ar-test-runner__start_test_run` MCP tool for running tests
- The MCP test runner automatically handles environment variables, async execution, and structured failure reporting

**Example usage:**
```
mcp__ar-test-runner__start_test_run
  module: "ml"
  profile: "pipeline"
  timeout_minutes: 10
```

**Why this matters:** The MCP test runner is purpose-built for this codebase. Using Bash for tests bypasses proper environment setup, loses structured output, and ignores specialized tooling.

See [docs/internals/test-examples.md](docs/internals/test-examples.md) for full tool reference and parameters.

### JVM Memory Diagnostics with `ar-jmx`

The `ar-jmx` MCP server provides real-time JVM memory analysis via JDK diagnostic tools (`jcmd`, `jstat`, `jfr`). Use it for `OutOfMemoryError`, `HardwareException: Memory max reached`, and suspected memory leaks.

**Two ways to connect:**
- **Test JVMs**: Pass `jmx_monitoring: true` to `start_test_run`. If the fork fails due to JFR/NMT args, the test runner retries automatically (metadata shows `jmx_monitoring_degraded: true`).
- **Standalone JVMs**: Use `attach_to_pid` with a PID to create a synthetic `run_id` for any running JVM.

**See [tools/mcp/jmx/README.md](tools/mcp/jmx/README.md)** for the full tool reference, parameter tables, and workflow examples (test JVM, standalone JVM, and cross-run regression detection).

---

## CRITICAL: TEST CLASS REQUIREMENTS

**THIS IS AN ABSOLUTE RULE WITH NO EXCEPTIONS.**

All test classes **MUST** extend `TestSuiteBase`:

```java
public class MyTest extends TestSuiteBase {
    @Test
    public void testSomething() {
        // Test automatically participates in grouping and depth filtering
    }

    @Test
    @TestDepth(2)
    public void expensiveTest() {
        // Automatically skipped if AR_TEST_DEPTH < 2
    }
}
```

**What TestSuiteBase provides automatically:**
- Test grouping (hash-based distribution across CI runners)
- `@TestDepth` annotation support
- All TestFeatures utilities (assertions, kernel testing, etc.)

**For long-running tests (30+ minutes):**
Use `skipLongTests` guard in addition to extending TestSuiteBase:
```java
if (skipLongTests) return;  // Respects AR_LONG_TESTS env var
```

See [docs/internals/test-examples.md](docs/internals/test-examples.md) for wrong patterns to avoid.

---

## Quick Links

- **[Quick Reference](docs/QUICK_REFERENCE.md)** - Condensed API cheatsheet
- **[llms.txt](llms.txt)** - Documentation index for AI agents
- **[Documentation Portal](docs/index.html)** - Full HTML documentation

---

## Setup Instructions

**Required environment variables** before running Java code:
```bash
export AR_HARDWARE_LIBS=/tmp/ar_libs/
export AR_HARDWARE_DRIVER=native
```

The directory will be created automatically if it doesn't exist.

**For Maven tests**, always prefix test commands with the environment variables:
```bash
export AR_HARDWARE_LIBS=/tmp/ar_libs/ && \
export AR_HARDWARE_DRIVER=native && \
mvn test -pl <module>
```

### What These Variables Do

- **`AR_HARDWARE_LIBS`**: Directory where hardware acceleration libraries (JNI .so files, OpenCL kernels, etc.) will be generated and loaded from
- **`AR_HARDWARE_DRIVER`**: Hardware backend to use:
  - `native`: Standard JNI operations with runtime-generated native code (default)
  - `opencl`: OpenCL acceleration (CPU/GPU)
  - `metal`: Metal GPU acceleration (Apple Silicon)
  - `external`: Generated executable approach

### Common Issues

Forgetting to set these variables will result in:
- `NoClassDefFoundError: Could not initialize class org.almostrealism.collect.PackedCollection`
- Runtime errors when trying to compile operations
- Missing library errors
- Failures during model inference

**Always verify** these are set before running:
```bash
echo $AR_HARDWARE_LIBS
echo $AR_HARDWARE_DRIVER
```

### Memory Configuration

For large models or tests that require more memory than the default 8GB limit:

```bash
export AR_HARDWARE_MEMORY_SCALE=7   # 8GB (default)
export AR_HARDWARE_MEMORY_SCALE=8   # 16GB
export AR_HARDWARE_MEMORY_SCALE=9   # 32GB
```

**When to increase memory:**
- Running large ML models (e.g., full Oobleck decoder with 5 blocks)
- Tests that produce `HardwareException: Memory max reached`
- Working with large audio/image data

**Example with increased memory:**
```bash
export AR_HARDWARE_MEMORY_SCALE=8 && \
export AR_HARDWARE_LIBS=/tmp/ar_libs/ && \
export AR_HARDWARE_DRIVER=native && \
mvn test -pl ml -Dtest=OobleckValidationTest
```

See [hardware/README.md](hardware/README.md) for complete memory and performance configuration options.

---

## Code Organization Principles

1. Never use @SuppressWarnings
2. Always include javadoc documentation for newly introduced code
3. Do not include excessive comments within method implementations
4. Never use `var` for variable declarations - always use explicit types

---

## Architectural Principles

These are sacred principles. Violating them will result in wasted effort and broken code. Each links to a detailed reference - **CONSULT the linked document before writing related code.**

### Training Loops: `ModelOptimizer` Owns the Loop

**`ModelOptimizer` is the ONLY class that should contain a training loop.** If you are writing `for (int epoch = 0; ...)` outside of `ModelOptimizer`, you are wrong. Delete it. Create a `Dataset`, create a `ModelOptimizer`, call `optimizer.optimize(epochs)` ONCE.

See [docs/internals/training-loop-examples.md](docs/internals/training-loop-examples.md) for correct and wrong patterns.

### Sampling Loops: `DiffusionSampler` Owns the Loop

**`DiffusionSampler` is the ONLY class that should contain a diffusion sampling loop.** If you are writing `for (int step = 0; ...)` outside of `DiffusionSampler`, you are wrong. Delete it. Create a `SamplingStrategy`, create a `DiffusionSampler`, call `sampler.sample(...)` ONCE.

See [docs/internals/sampling-loop-examples.md](docs/internals/sampling-loop-examples.md) for correct and wrong patterns.

### PackedCollection is NOT a Java Array

**`PackedCollection` is a HANDLE to potentially GPU-resident memory.** Never use `System.arraycopy`, `Arrays.copyOf`, or tight `setMem` loops. Use the **Producer pattern**: `cp(source).multiply(2.0).evaluate()`. Consult before writing code that creates, copies, or transforms `PackedCollection` objects.

See [docs/internals/packed-collection-examples.md](docs/internals/packed-collection-examples.md) for correct and wrong patterns.

### Process Isolation: Only `IsolatedProcess` Breaks Expression Embedding

**Never** return null from `getValueAt()` to force isolation. Call `Process.optimize()` before `Process.get()` and let `IsolatedProcess` handle it through the proper `isIsolationTarget()` / `isolate()` chain.

### StateDictionary for Model Weights

All model implementations should use `StateDictionary` for weight management. Avoid separate weight container classes unless transformations or caching are needed.

---

## ABSOLUTELY NO CODE DUPLICATION - THIS IS NON-NEGOTIABLE

If you find yourself copying and pasting code, or writing nearly-identical logic multiple times, **STOP IMMEDIATELY**. This is unacceptable and will never be tolerated.

**The rule**: If you have written more than 3-5 lines that are structurally similar to other code, you MUST refactor to eliminate the duplication BEFORE proceeding. Use helper methods, generics, factory functions, lambdas, or any appropriate abstraction.

**No exceptions. No excuses. Refactor first, then proceed.**

**Principle**: Extend and generalize existing code rather than creating model-specific copies.

```java
// GOOD: Generalize existing method with optional parameters
default Block attention(int heads, int kvHeads, int headSize,
                       PackedCollection<?> wq, PackedCollection<?> wk,
                       PackedCollection<?> wv, PackedCollection<?> wo,
                       PackedCollection<?> qkNormQ, PackedCollection<?> qkNormK,  // Optional
                       ...) {
    // Single implementation that handles all cases
    if (qkNormQ != null && qkNormK != null) {
        // Apply QK-Norm
    }
}
```

```java
// AVOID: Creating model-specific duplicate methods
default Block llamaAttention(...) { /* ... */ }
default Block qwenAttention(...) { /* ... */ }  // Copy-paste with minor changes
default Block mistralAttention(...) { /* ... */ }
```

**Benefits of generalization**:
- Single source of truth for attention logic
- Bugs fixed once, not per model
- Easier to add new features
- Better testing coverage

**When duplication is acceptable**:
- Fundamentally different architectures (encoder-decoder vs decoder-only)
- Performance-critical paths requiring specialization
- Temporary experimentation (mark with TODO to generalize)

---

## Development Workflow

### Before Starting a Task

1. **Check for existing implementations**: Don't reinvent the wheel
2. **Identify generalization opportunities**: Can existing code be extended?
3. **Review StateDictionary**: Can it handle your use case?
4. **Set environment variables**: Especially for testing
5. **Check design documents**: If implementing a planned feature, read the design doc in `ringsdesktop/docs/planning/`
6. **State your reuse plan**: Before writing code, explicitly state: "I will reuse [X] rather than reimplementing it"

### Design Document Verification Gate

**For any feature with a design document:**

1. Read the relevant design document section BEFORE writing code
2. Explicitly state: "According to the design document, I should use [X] for [Y]"
3. If deviating from design, document why and update the design document FIRST
4. Search for existing implementations: `Grep pattern:"class.*implements|extends.*<RelevantInterface>"`

### During Development

1. **Prefer composition over duplication**
2. **Add optional parameters rather than creating new methods**
3. **Use StateDictionary as the standard weight storage**
4. **Test frequently with environment variables set**

### Before Committing

1. **Remove TODO markers for completed work**
2. **Mark deprecated code with @deprecated tags**
3. **Ensure all tests pass with hardware acceleration enabled**
4. **Document any new patterns or breaking changes**

### Build Verification Requirements

**CRITICAL**: Before declaring any task complete, you MUST verify the full build succeeds:

```bash
export AR_HARDWARE_LIBS=/tmp/ar_libs/ && \
export AR_HARDWARE_DRIVER=native && \
mvn clean install -DskipTests
```

**This command must complete with BUILD SUCCESS.** Do not rely on:
- `mvn compile` alone (misses test compilation and packaging)
- `mvn compile -q` (suppresses errors that may appear later)
- Building individual modules (misses cross-module dependencies)

**Only after this command succeeds** should you report that the build is working.

### Considering Something Completed

Sometimes you may encounter a summary of earlier work. That summary may indicate that there are other things to do.
If this happens, that means YOU ARE EXPECTED TO DO THOSE THINGS.

Working on something for a while, writing some commentary about completing more of it later, and then reading the
commentary back to yourself is NOT a condition for suspending your work.

Before you start working, REPEAT this principle to yourself. If summarization of earlier tasks is ever required,
REPEAT THIS PRINCIPLE IN THE SUMMARY

### Context Preservation Protocol

**When continuing a session or after context summarization:**

1. **Re-read the design document** for the feature being implemented
2. **Search for existing implementations** before writing new code:
   - `Grep pattern:"ModelOptimizer"` for training-related code
   - `Grep pattern:"extends CellularLayer"` for layer implementations
   - `mcp__ar-consultant__consult question:"How does <feature> work?"` for framework patterns
3. **Explicitly state your reuse plan**: "I will reuse [X] rather than reimplementing it"
4. **Check the summary for incomplete tasks** - if the summary mentions "remaining work" or "TODO", that work is YOUR responsibility

**This protocol exists because:** Design decisions made early in a session are often lost during context summarization. The design document is the authoritative source - always consult it, especially after a session break.

---

## Debugging: No Speculation, Only Evidence

When debugging, follow a systematic bottom-up approach: inventory all components in the failing path, run tests from smallest to largest scope, record results at each level, and only draw conclusions supported by test evidence. **Never say "the problem might be X" without a test proving it.**

For memory-related failures (`OutOfMemoryError`, `HardwareException: Memory max reached`), use `ar-jmx` diagnostics instead of guessing. See [tools/mcp/jmx/README.md](tools/mcp/jmx/README.md).

---

## Further Reference

- **Module-specific guidelines**: [ML Module](./ml/claude.md), [Graph Module](./graph/README.md), [Collect Module](./collect/README.md)
- **Module overview and key classes**: See [docs/QUICK_REFERENCE.md](docs/QUICK_REFERENCE.md) or `docs/modules/<module>.html`
- **Test output logging**: Use `Console` and `OutputFeatures` from `ar-io`. See module documentation for details.
