# Consult Concept Map

This is a curated, versioned, **machine-readable** map of the core abstractions
in the codebase. `consult` parses it (`tools/mcp/common/concept_map.py`) and
surfaces the entries that match a question — or the files being touched — as its
own top evidence tier, ahead of document fragments. The upkeep check
`scripts/check-concept-map.py` fails when an entry names a type that no longer
exists in the source tree.

It exists because the most common structural mistake here is adding a new type
beside one that already represents the same idea — writing a fresh
arithmetic-progression `Set<Integer>` while `ArithmeticIndexSequence` is imported
a few lines above, for instance. The information was always in the tree; what was
missing was the framing that the codebase is organised around concepts with
designated types, and that changing those types — a signature, an interface, a
hierarchy — is a normal design move, not off limits.

This map is the per-concept, retrieval-facing counterpart of the prose
[concept-map.md](concept-map.md), which situates every module's concepts in the
six-layer stack and records type-alignment families. That document is for human
orientation; this one is the structured data consult retrieves against. Keep the
two consistent when a concept's canonical types change.

## Format

Each concept is an `##` heading. Under it, every field is a bold-labelled list
item, read verbatim by the parser:

- **Concept:** a plain statement of the idea.
- **Module:** the module (and `ar-` artifact) the concept belongs to.
- **Canonical types:** the interface and its main implementations, in backticks.
  Every backticked name here is checked for existence by the upkeep check.
- **Extension points:** how the concept is meant to grow — a new implementation,
  an interface the existing types also implement, or a changed method signature.
- **Near-duplicates and anti-patterns:** what not to add instead.
- **Keywords:** near-duplicate phrasings a question might use, separated by
  `;`. These are what a keyword-matched retrieval scores against most strongly.

---

## Producer

- **Concept:** A description of a computation that, when asked, yields an `Evaluable` which actually performs it. Producers are the orchestration layer: Java composes them into a computation graph, and the framework compiles that graph to native code. Building a `Producer` does no arithmetic.
- **Module:** `base/relation` (ar-relation).
- **Canonical types:** `Producer`, `ProducerComputation`, `Computation`, `Process`, `Factor`, `Composition`, `Countable`.
- **Extension points:** Implement `Producer` (usually through `ProducerComputation`/`Computation`) for a new kind of computation; transform one producer into another with a `Factor`; combine two with a `Composition`. Kernel boundaries are decided only by `Process.optimize`, never by a hand-written `isolate()`.
- **Near-duplicates and anti-patterns:** Do not do arithmetic in Java and wrap the result — express the computation as a `Producer` composition. Do not call `.get()`/`.evaluate()` inside graph-building code; evaluation belongs only at the top of the call stack.
- **Keywords:** producer; computation description; lazy computation; build a computation graph; deferred evaluation; orchestration not execution; two-phase execution.

## Evaluable

- **Concept:** The compiled, executable form of a computation, produced by `Producer.get()`. Calling `evaluate(...)` runs it and returns the result; it can be evaluated many times, amortising one-time compilation.
- **Module:** `base/relation` (ar-relation).
- **Canonical types:** `Evaluable`, `Computable`, `StreamingEvaluable`.
- **Extension points:** Obtain one from a `Producer`; write results in place with `into(...)`, run asynchronously with `async(...)`, or allocate a batch destination with `createDestination(int)`. For asynchronous completion handling, the operation-side analog is `Submittable`.
- **Near-duplicates and anti-patterns:** Do not call `evaluate()` inside a class that participates in model computation — the only acceptable place is a test method, a `main`, a pipeline boundary, or a step boundary in an autoregressive loop. Do not pull data back element-by-element with `toDouble()`/`toFloat()`.
- **Keywords:** evaluable; compiled computation; executable form; evaluate; run a producer; executable counterpart.

## CollectionProducer

- **Concept:** A `Producer` of `PackedCollection` values with a rich, chainable API for building collection computations — shape transforms, arithmetic, reductions, comparisons, and automatic differentiation. Every operation returns a new producer and builds graph, never mutating.
- **Module:** `compute/algebra` (ar-algebra).
- **Canonical types:** `CollectionProducer`, `CollectionProducerComputation`, `CollectionFeatures`, `CollectionExpression`.
- **Extension points:** Compose the existing chainable operations (`add`, `multiply`, `reshape`, `traverse`, `sum`, `mean`, `delta`, ...). Where a mixin of ready-made operations is wanted, implement or depend on `CollectionFeatures` (and `LayerFeatures` for layers). A genuinely new primitive is a new `CollectionProducerComputation`.
- **Near-duplicates and anti-patterns:** Do not loop over elements in Java to do element-wise math or matrix multiply — use `.multiply()`, `.add()`, `matmul()`/`dense()`. Do not add a standalone helper class that operates on a collection from the outside; add the behaviour through `CollectionFeatures` or as a method on the producing type.
- **Keywords:** collection producer; chainable collection operations; element-wise math; matmul; reductions; collection computation graph.

## PackedCollection

- **Concept:** A hardware-accelerated, multi-dimensional array backed by contiguous (possibly GPU-resident) memory. It is a handle to native memory interpreted through a `TraversalPolicy`, not a Java array.
- **Module:** `compute/algebra` (ar-algebra).
- **Canonical types:** `PackedCollection`, `MemoryData`, `MemoryBank`, `Shape`, `TraversalOrdering`.
- **Extension points:** Reinterpret the same memory with a different `TraversalPolicy`/`TraversalOrdering` (reshape, traverse); back it with a `MemoryData` delegate; batch with a `MemoryBank`. It is `AutoCloseable` (via `Destroyable`) — close short-lived locals, let the owner manage captured ones.
- **Near-duplicates and anti-patterns:** Never use `System.arraycopy`, `Arrays.copyOf`, or tight `setMem` loops; never iterate with `toDouble()`/`toFloat()`. Copy and transform with the producer pattern (`cp(source).multiply(2.0).evaluate()`).
- **Keywords:** packed collection; tensor storage; multi-dimensional array; GPU-resident memory; flat buffer; contiguous memory.

## TraversalPolicy

- **Concept:** The shape of a collection — its dimensions and the rate data is traversed along each axis — mapping a position in the output space to an index in the flat input space and back. It may be fixed-count or variable-count.
- **Module:** `base/code` (ar-code).
- **Canonical types:** `TraversalPolicy`, `TraversalOrdering`, `Shape`.
- **Extension points:** Build shapes with the dimensioning constructors and the derive/traverse operations; express a non-standard memory layout as a `TraversalOrdering` carried by the collection. A computation respects whatever ordering its arguments carry.
- **Near-duplicates and anti-patterns:** A computation must never special-case a particular `TraversalOrdering` or add a parallel variant of an operation (e.g. a second `c(shape, collection, index)`); if an existing operation reads an ordering wrongly, fix that operation. A new representation is a collection configured with an ordering, not a new shape class.
- **Keywords:** traversal policy; collection shape; dimensions; axis traversal; reshape; row-major layout; fixed vs variable count.

## Expression

- **Concept:** A node in the typed expression tree (a DAG) that code generation renders to a target language (C, OpenCL, Metal). Leaves are constants, variables and indices; internal nodes are operations. Expressions simplify, evaluate against index assignments, and transform.
- **Module:** `base/code` (ar-code).
- **Canonical types:** `Expression`, `Sum`, `Product`, `Quotient`, `Conditional`, `Constant`, `IntegerConstant`, `DoubleConstant`, `Variable`.
- **Extension points:** Add a new operation as an `Expression` subclass; merge n-ary operands through `NAryExpression`; render through `ExpressionRendering`/`LanguageOperations`. A method that walks an expression tree is a method of `Expression`.
- **Near-duplicates and anti-patterns:** When generated source is wrong, too large, or too deeply bracketed, fix how the tree is built or simplified — do not post-process the string, raise a compiler limit, or rewrite the renderer to paper over a wrong tree (redundant brackets usually mean operands that should have merged via `NAryExpression.mergeOperands` did not).
- **Keywords:** expression tree; code generation; expression DAG; simplify expression; operand merge; render to kernel source.

## Index

- **Concept:** A named, bounded index variable in a kernel computation — it produces `IndexSequence` values and carries an identifier. Concrete indices include the GPU thread id and arbitrary named indices with an optional limit.
- **Module:** `base/code` (ar-code).
- **Canonical types:** `Index`, `DefaultIndex`, `KernelIndex`, `IndexChild`, `SequenceGenerator`.
- **Extension points:** Implement `Index` (via `SequenceGenerator` + `Named`) for a new kind of iteration variable; derive a child index with `IndexChild`; the GPU thread id is `KernelIndex`.
- **Near-duplicates and anti-patterns:** Do not introduce a bespoke "named counter" type for an iteration dimension — use `DefaultIndex` or a new `Index` implementation. Do not confuse the index *variable* (`Index`) with the sequence of values it ranges over (`IndexSequence`).
- **Keywords:** index variable; kernel index; iteration dimension; named index; thread id; bounded index.

## IndexSequence

- **Concept:** A finite sequence of the integer index values an expression's index can take — the set of integers that are members of an index's range — analysable for patterns (arithmetic, constant) and convertible to an `Expression` for code generation.
- **Module:** `base/code` (ar-code).
- **Canonical types:** `IndexSequence`, `ArithmeticIndexSequence`, `ArrayIndexSequence`, `IndexValues`, `IndexRange`.
- **Extension points:** Create one with `ArrayIndexSequence.of(...)` (explicit values) or `ArithmeticIndexSequence` (offset + scale progression); transform with `map`, `multiply`, `divide`, `mod`; convert to an `Expression` via `getExpression(Index)`. A new sequence shape is a new `IndexSequence` implementation.
- **Near-duplicates and anti-patterns:** Do not wrap an `IndexSequence` in a new `Set` type, and do not write a new arithmetic-progression `Set<Integer>` to represent which integers an index may take — the concept is already `IndexSequence`/`ArithmeticIndexSequence`. If a consumer needs a membership check, change that consumer's signature or add an interface the existing sequence types implement.
- **Keywords:** integer membership; index options; set of integers; membership of an integer in index values; arithmetic progression; index value set; which integers an index can take; finite set of index values.

## Scope

- **Concept:** The primary container for generated code — a hierarchical tree of statements, methods, variables and nested scopes that compiles to native code, tracks its own arguments and dependencies, and supports conditional execution.
- **Module:** `base/code` (ar-code).
- **Canonical types:** `Scope`, `Statement`, `Method`, `Variable`, `ExpressionAssignment`, `ScopeLifecycle`.
- **Extension points:** Declare variables and assignments, add child scopes and cases (`addCase`), inline with `tryAbsorb`, and emit with `write(CodePrintWriter)`. A method that collects declarations from a scope tree is a method of `Scope`.
- **Near-duplicates and anti-patterns:** Do not add a free-standing utility that walks a `Scope` from the outside; the traversal belongs on `Scope`. Follow the verb convention: `collect…`/`get…` return and leave arguments untouched, `append…(target)` mutate the passed-in collection.
- **Keywords:** scope; code container; generated code block; statements and variables; scope tree; nested scopes; write generated code.

## Cell

- **Concept:** A node in the computation graph that both receives data (as a `Receptor`) and transmits it downstream (as a `Transmitter`), in a push-based "protein" data-flow model. Cells are the foundation of the layer system.
- **Module:** `domain/graph` (ar-graph).
- **Canonical types:** `Cell`, `Receptor`, `Transmitter`, `ReceptorCell`, `CellularPropagation`.
- **Extension points:** Build cells with the static factories (`Cell.of(...)`, `Cell.from(...)`, `Cell.branch(...)`), chain with `andThen`, or implement `Cell` for genuinely new behaviour. Any class whose name ends in `Cell` MUST implement `org.almostrealism.graph.Cell`.
- **Near-duplicates and anti-patterns:** Do not build a parallel "node that processes and forwards" abstraction — that is `Cell`. Do not do computation in a `…Cell` without expressing it as producers.
- **Keywords:** cell; computation graph node; push-based data flow; receptor and transmitter; protein data flow; graph cell.

## Block

- **Concept:** A composable neural-network unit with forward and backward propagation, input/output shape, and fluent composition — the primary abstraction for network components.
- **Module:** `domain/graph` (ar-graph).
- **Canonical types:** `Block`, `DefaultBlock`, `SequentialBlock`, `Layer`, `CellularLayer`, `LayerFeatures`, `Component`.
- **Extension points:** Compose with `andThen`, `reshape`, `scale`, `branch`; build a layer through `LayerFeatures`; a `DefaultBlock` wraps explicit forward/backward cells, a `SequentialBlock` chains blocks. Any class whose name ends in `Block` MUST implement `org.almostrealism.model.Block`.
- **Near-duplicates and anti-patterns:** Do not add a weights-holding class that hands out a block from a `buildModel()`/`buildBlock()` — a `…Block` *is* a `Block`. Do not introduce a separate "layer" concept beside `Block`/`Layer`.
- **Keywords:** block; neural network unit; forward and backward; composable layer; block composition; network component.

## Model

- **Concept:** The top-level container for a neural network — a sequential block chain plus optional auxiliary inputs and a parameter-update strategy — compiled to a `CompiledModel` for training and inference.
- **Module:** `domain/graph` (ar-graph).
- **Canonical types:** `Model`, `CompiledModel`, `AutoregressiveModel`.
- **Extension points:** Build by `add(...)`-ing blocks, compile with `compile(...)`; for a specialised model type, extend the model type you name (`Model`, `AutoregressiveModel`). Manage weights through `StateDictionary`.
- **Near-duplicates and anti-patterns:** A class named `…Model` must extend a model type — not merely hold weights and return a `Model` from `buildModel()`. A caller must not pass in a `CompiledModel` the model should compile from its own state ("own your collaborators").
- **Keywords:** model; neural network container; compiled model; training and inference; sequential block chain; autoregressive model.

## Receptor

- **Concept:** The data-receiving half of the cell communication pattern: it accepts a `Producer` of incoming data and returns a `Supplier<Runnable>` of the operations that process or forward it, composed for later optimised execution rather than run immediately.
- **Module:** `domain/graph` (ar-graph).
- **Canonical types:** `Receptor`, `Transmitter`, `Cell`, `CollectionReceptor`.
- **Extension points:** Implement `Receptor` (often a lambda) to consume pushed data; fan out with `Receptor.to(...)`; store into a collection with `CollectionReceptor`.
- **Near-duplicates and anti-patterns:** A class that routes to a `Receptor` must stay correct for every `Receptor` implementation — never cast to a concrete receptor, never return the concrete class a constructor merely adapts, and never write a path that silently does nothing unless one implementation is present (`block-interface-bypass`).
- **Keywords:** receptor; data-receiving endpoint; push to receptor; receive a producer; cell input; broadcast receptor.

## Submittable and Semaphore

- **Concept:** The asynchronous-execution contract. `Submittable` is the operation side: an operation that can be submitted to run after a prior one completes and yields its own completion `Semaphore` without necessarily blocking the host. `Semaphore` is the completion handle a provider returns for a dispatch — a caller or dependent operation waits on it or registers a callback.
- **Module:** `Submittable` in `base/code` (ar-code); `Semaphore` in `base/relation` (ar-relation); native-backed implementations in `base/hardware` (ar-hardware).
- **Canonical types:** `Submittable`, `Semaphore`, `OperationSemaphore`, `LatchSemaphore`, `DeferredSemaphore`.
- **Extension points:** Chain with `Submittable.submit(dependsOn)`, keep only the latest completion, submit host code through `Submittable.of(Runnable)`, and call `waitFor()` once at a true host boundary. A backend returns a live `Semaphore` (a `cl_event`, an `MTLSharedEvent`) or an already-completed one so chaining degrades to synchronous.
- **Near-duplicates and anti-patterns:** Never inspect a `Semaphore` (its class, its backend) to decide whether to wait, never wait before submitting, never add a caller-side "steps in flight" cap, and never add a second submit method whose meaning depends on the backend. If chaining is slow or grows threads, a receiver is broken — fix it there.
- **Keywords:** submittable; semaphore; asynchronous submission; completion handle; chain on completion; dependsOn; wait for completion; async dispatch.

## MemoryProvider

- **Concept:** The owner of allocation, deallocation and data transfer for one kind of `Memory`, backing the memory model for a particular device or compute backend (CPU heap, Metal buffer, OpenCL buffer).
- **Module:** interface in `base/code` (ar-code); implementations in `base/hardware` (ar-hardware).
- **Canonical types:** `MemoryProvider`, `Memory`, `AbstractMemoryProvider`, `JVMMemoryProvider`, `CLMemoryProvider`, `MetalMemoryProvider`, `NativeMemoryProvider`.
- **Extension points:** Implement `MemoryProvider` (usually via `AbstractMemoryProvider`) for a new backend, supplying allocation and read/write of its `Memory` type; providers are selected through the hardware/`ComputeContext` layer.
- **Near-duplicates and anti-patterns:** Do not allocate native buffers or copy memory outside a `MemoryProvider`; route allocation and transfer through the provider so the device owns its memory. Do not special-case a concrete provider where the `MemoryProvider` interface suffices.
- **Keywords:** memory provider; allocation and deallocation; device memory; backend buffer; data transfer; memory backend.
