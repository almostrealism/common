# PDSL Language Reference

PDSL (Producer DSL, package `org.almostrealism.dsl` in `domain/graph`) is the language in which
the platform describes its models. The language core lives beside the `Block` and `Model` types it
builds, so any module from the domain layer up can describe its models in PDSL; modules above it
add primitives of their own (see [Extending PDSL](#extending-pdsl)). A `.pdsl` file declares layers, models and the configuration,
data and state they read. The interpreter turns them into `Block` and `Model` graphs built from
the same `CollectionProducer` framework as hand-written Java layers. Like Java code in this
project, a PDSL program does not run the math itself. It describes a computation graph, and
that graph is compiled to native kernels.

This page covers the language itself: its lexical structure, grammar, the meaning of each
definition and statement, name resolution, imports, the built-in function library, how a
domain adds primitives of its own, the errors it reports, and its known limits. The audio
primitives and the multi-channel constructs are covered in
[PDSL Audio DSP Reference](pdsl-audio-dsp.md). The assets that build the platform's attention and
transformer layers are described in [ML Inference Pipeline](ml-inference-pipeline.md).

Every complete example on this page (each fenced block marked `pdsl`) is parsed by
`PdslLanguageReferenceExamplesTest`, and the layers named under
[Worked examples](#worked-examples) are built and their output shapes checked. The same test checks
that every keyword the lexer reserves, every `PdslNode` type, and every built-in in the catalog
below appears on this page. Fragments that are not complete programs are marked `text`.

## Overview and pipeline

```text
source text --PdslLexer--> tokens --PdslParser--> PdslNode.Program --PdslInterpreter--> Block / Model
```

| Stage | Class | What it does |
|-------|-------|--------------|
| Lexing | `PdslLexer` | Turns source text into `PdslToken`s: keywords, identifiers, numbers, strings, operators. Skips whitespace and comments. |
| Parsing | `PdslParser` | Recursive descent over the token list. Produces a `PdslNode.Program`: the program's `import`s and its definitions. |
| Interpretation | `PdslInterpreter` | Indexes the definitions by kind and name, then builds a `Block` (`buildLayer`) or `Model` (`buildModel`), or evaluates a `config`, `data` or `state` block to a map. |
| Loading | `PdslLoader` | The public entry point. It parses source text, files and classpath resources, resolves imports, creates an interpreter per build, and applies a registrar that installs domain primitives. Every interpreter also registers the primitives of each `PdslExtension` on the classpath. |

The common Java entry points:

```java
PdslLoader loader = new PdslLoader();                       // built-ins only
PdslLoader audio = new PdslLoader(AudioDspPrimitives::registerWith); // plus a domain's primitives

PdslNode.Program program = loader.parseResource("/pdsl/transformer.pdsl"); // resolves imports
Block block = loader.buildLayer(program, "transformer", shape(1, dim), args, requirements);
Model model = loader.buildModel(program, "qwen3", shape(1, dim), stateDict, extraArgs);
Map<String, Object> settings = loader.evaluateConfig(program, "tiny");
```

**Resource caching.** `PdslLoader.parseResource` and `parseResources` parse each classpath
resource once per JVM and keep the result in `RESOURCE_CACHE`, keyed by the canonical resource
path. A cached `PdslNode.Program` is never mutated. Its definition and import lists are
unmodifiable copies. Import resolution builds a new `Program` from copies of the cached lists.
Every build evaluates `data` and `state` derivations into a fresh environment. Mutable state,
such as a key/value cache, is allocated by the caller or by `zeros(...)` during the build, never
held in a node. One parsed asset can therefore serve every build. `parse(String)` and
`parse(Path)` do not cache.

## Lexical structure

**Whitespace and comments.** Spaces, tabs, carriage returns and newlines separate tokens and
are otherwise ignored. A line comment runs from `//` to the end of the line. A block comment
runs from `/*` to `*/`. A file conventionally opens with a `/** ... */` header that describes the
asset and its arguments. The lexer treats it as an ordinary block comment.

**Identifiers** start with a letter or `_` and continue with letters, digits and `_`. A word in
the keyword table below is a keyword, not an identifier. The one exception is the word right
after a `.`, where any keyword is accepted as a field name. `weights.model.layers` names the
checkpoint group `model.layers` even though `model` is a keyword.

**Literals.**

| Literal | Form | Value |
|---------|------|-------|
| Number | digits, an optional fraction `.digits`, an optional exponent `e`/`E` with an optional sign (`1`, `0.5`, `1e-6`, `2.5E+3`) | A Java `Double`. There is no separate integer type: `16` is `16.0`, and integer contexts truncate with `intValue()`. A number cannot start with `.`, and `1..4` lexes as `1`, `..`, `4`. |
| String | `"..."` with the escapes `\n`, `\t`, `\\`, `\"` (any other `\x` is kept as written) | A Java `String`. Used for `import` paths and the key of `weight(...)`. |
| Boolean | `true`, `false` | A Java `Boolean`. |
| Null | `null` | Java `null`: an omitted optional argument, such as a missing bias. |
| Shape | `[e1, e2, ...]`, possibly empty | A `TraversalPolicy`, each dimension evaluated and truncated to an integer. |

**Keywords.** Every reserved word, as listed by `PdslToken.Type.keywords()`:

| Keyword | Role |
|---------|------|
| `layer` | Introduces a layer definition. |
| `model` | Introduces a model definition. |
| `import` | Introduces an import of another `.pdsl` resource. |
| `config` | Introduces a configuration block. |
| `data` | Introduces a data block. |
| `state` | Introduces a state block. |
| `let` | Binds a local name. |
| `return` | Appends a value to the block, exactly like an expression statement. |
| `if` | **Reserved, no meaning.** The lexer reserves it, but the parser has no production for it and there is no `PdslNode` for a conditional. PDSL has no conditional statement, and a program that uses `if` fails to parse. |
| `else` | **Reserved, no meaning.** Same as `if`. |
| `for` | Introduces a range loop (`for i in a..b`) or `for each channel`. |
| `in` | Separates the loop variable from its range. |
| `stack` | Repeats a body once per member of a weight group. |
| `as` | Names the member of a `stack` that each repetition reads. |
| `branch` | Forks the current signal into a sub-block (`branch name { }`, or `let x = branch { }`). |
| `accum` | Residual connection: adds a sub-block's output to its input. |
| `product` | Element-wise product of two sub-blocks applied to the same input. |
| `accum_blocks` | Element-wise sum of N sub-blocks applied to the same input. |
| `concat_blocks` | Concatenation of N sub-blocks applied to the same input. |
| `weight` | Parameter type, and the `weight(key)` expression. |
| `scalar` | Parameter type. |
| `producer` | Parameter type `producer([shape])`. |
| `int` | Parameter type. |
| `float` | Parameter type. |
| `bool` | Parameter type. |
| `true` | Boolean literal. |
| `false` | Boolean literal. |
| `null` | Null literal. |

**Contextual words that are not keywords.** `each` and `channel` in `for each channel` are
identifiers. `PdslParser` recognizes them by their text, right after `for`, so `each` is still
usable as a name anywhere else. The names `channels`, `signal_size` and `channel` have meaning
to the multi-channel machinery (see [Name resolution and scope](#name-resolution-and-scope)), but
they are ordinary identifiers to the lexer. Type names such as `checkpoint` are also identifiers.

**Operators and delimiters.**

| Token | Use |
|-------|-----|
| `( )` | Grouping, parameter lists, call arguments. |
| `{ }` | Bodies of definitions and statements; inline blocks. |
| `[ ]` | Shape literals; subscripts. |
| `,` `:` | List separator; parameter type annotation. |
| `;` | Optional statement separator inside layer, model, `data` and `state` bodies. |
| `=` | Binding in `let`, `config`, `data`, `state`. |
| `->` | Introduces a layer's shape annotation. |
| `..` | Range in `for i in a..b`. |
| `.` | Field access and method-call sugar. |
| `+ - * /` | Arithmetic. |
| `== != < <= > >=` | **Lexed, but no grammar uses them.** The parser rejects them wherever they appear. |

**Precedence**, from loosest to tightest, all left-associative: `+ -`, then `* /`, then unary `-`,
then the postfix forms `f(args)`, `.field`, `.method(args)` and `[index]`.

## Grammar

Derived from `PdslParser`. `{ x }` means zero or more, `[ x ]` means optional, and quoted text is
literal.

```text
program        = { import } { definition } EOF
import         = "import" STRING                  (* must begin with "/" *)
definition     = config_def | data_def | state_def | layer_def | model_def

config_def     = "config" IDENT "{" { IDENT "=" expr } "}"
data_def       = "data"  IDENT "{" { data_entry { ";" } } "}"
state_def      = "state" IDENT "{" { data_entry { ";" } } "}"
data_entry     = IDENT ":" type | IDENT "=" expr
layer_def      = "layer" IDENT "(" [ params ] ")" [ "->" shape ] "{" body "}"
model_def      = "model" IDENT "(" [ params ] ")" "{" body "}"

params         = param { "," param }
param          = IDENT ":" type
type           = "weight" [ shape ] | "scalar" | "producer" "(" shape ")"
               | "int" | "float" | "bool" | shape | IDENT

body           = { statement { ";" } }
statement      = let_stmt | return_stmt | branch_stmt | accum_stmt | product_stmt
               | accum_blocks_stmt | concat_blocks_stmt | for_stmt | stack_stmt | expr

let_stmt       = "let" IDENT "=" ( "branch" "{" body "}" | expr )
return_stmt    = "return" expr
branch_stmt    = "branch" [ IDENT ] "{" body "}"
accum_stmt     = "accum" "{" body "}"
product_stmt   = "product" "(" block_arg "," block_arg ")"
accum_blocks_stmt  = "accum_blocks"  "(" block_arg { "," block_arg } ")"
concat_blocks_stmt = "concat_blocks" "(" block_arg { "," block_arg } ")"
block_arg      = "{" body "}" | product_stmt | accum_blocks_stmt | concat_blocks_stmt | expr
for_stmt       = "for" "each" "channel" "{" body "}"
               | "for" IDENT "in" expr ".." expr "{" body "}"
stack_stmt     = "stack" expr "as" IDENT "{" body "}"

expr           = term { ( "+" | "-" ) term }
term           = unary { ( "*" | "/" ) unary }
unary          = "-" unary | postfix
postfix        = primary { "(" [ args ] ")"          (* only directly after an IDENT *)
                         | "." FIELD [ "(" [ args ] ")" ]
                         | "[" expr "]" }
args           = expr { "," expr }
primary        = NUMBER | STRING | "true" | "false" | "null"
               | "weight" [ "(" expr ")" ] | IDENT | shape | "(" expr ")"
shape          = "[" [ expr { "," expr } ] "]"
FIELD          = IDENT | any keyword
```

Notes on the grammar:

- Imports must come before the first definition. An `import` after a definition fails with
  "Import statements must precede every definition".
- A `config` body takes no `;` separators. Layer, model, `data` and `state` bodies do.
- `x.f(a, b)` is sugar for `f(x, a, b)`: a `FunctionCall` whose first argument is `x`.
- `{ ... }` is an inline block only as a `block_arg`. As a statement, a `{` is a parse error.
- `product(...)`, `accum_blocks(...)` and `concat_blocks(...)` nested as a `block_arg` are
  wrapped in a synthetic `InlineBlock`, so they nest freely.

### AST node types

Every `PdslNode` subclass and the source construct that produces it:

| Node | Kind | Produced by |
|------|------|-------------|
| `Program` | root | a whole file: its `Import`s and `Definition`s |
| `Import` | top level | `import "/path.pdsl"` |
| `Definition` | abstract | base of the five definition kinds |
| `ConfigDef` | definition | `config Name { k = expr ... }` |
| `DataDef` | definition | `data Name { ... }` |
| `StateDef` | definition | `state Name { ... }`, a subclass of `DataDef` |
| `LayerDef` | definition | `layer name(params) [-> shape] { body }` |
| `ModelDef` | definition | `model name(params) { body }` |
| `Parameter` | part | `name: type` in a parameter list or a `data`/`state` block |
| `Statement` | abstract | base of the statements |
| `LetStatement` | statement | `let x = expr`, `let x = branch { }` |
| `ReturnStatement` | statement | `return expr` |
| `BranchStatement` | statement | `branch [name] { }` |
| `AccumStatement` | statement | `accum { }` |
| `ProductStatement` | statement | `product(a, b)` |
| `AccumBlocksStatement` | statement | `accum_blocks(a, ...)` |
| `ConcatBlocksStatement` | statement | `concat_blocks(a, ...)` |
| `ForStatement` | statement | `for i in a..b { }` |
| `ForEachChannelStatement` | statement | `for each channel { }` |
| `StackStatement` | statement | `stack group as name { }` |
| `ExpressionStatement` | statement | an expression on its own |
| `Expression` | abstract | base of the expressions |
| `NumberLiteral` | expression | `1`, `1e-6` |
| `StringLiteral` | expression | `"text"` |
| `BoolLiteral` | expression | `true`, `false` |
| `NullLiteral` | expression | `null` |
| `Identifier` | expression | a name |
| `ShapeLiteral` | expression | `[a, b]` |
| `FunctionCall` | expression | `f(args)`, `x.f(args)` |
| `BinaryOp` | expression | `a + b`, `a - b`, `a * b`, `a / b` |
| `UnaryOp` | expression | `-a` |
| `FieldAccess` | expression | `x.field` |
| `Subscript` | expression | `x[index]` |
| `InlineBlock` | expression | `{ body }` as a block argument; the body of `let x = branch { }` |
| `WeightRef` | expression | `weight(key)` |

## Definitions and parameters

A program is a set of named definitions. Each kind has its own namespace: a `layer foo` and a
`config foo` do not collide.

| Definition | Produces | How Java reaches it |
|------------|----------|---------------------|
| `config Name { k = expr }` | A `Map<String, Object>`. Entries are evaluated in order, and each sees the entries before it. | `PdslLoader.evaluateConfig` / `PdslInterpreter.evaluateConfig`. Inside a program, the bare name `Name` evaluates the config, and `Name.k` reads an entry. |
| `data Name { p: type; d = expr }` | Program-level bindings. The parameters (`p: type`) come from the build's argument map. The derivations (`d = expr`) are evaluated in declaration order, each seeing the entries before it. | Every build binds every `data` block into the program scope, so each block's parameters must be present in the arguments of every `buildLayer`/`buildModel`. `evaluateDataDef` returns one block's entries as a map. |
| `state Name { ... }` | The same grammar and evaluation as `data`. The different node type (`StateDef`) signals that the entries are written during execution: caches, ring buffers, filter histories. | Bound into the program scope only when *all* of the block's parameters are in the build's arguments. Otherwise the block is skipped, so a program can declare state that only some of its layers use. `PdslInterpreter.evaluateStateDef` returns its entries. |
| `layer name(params) [-> shape] { body }` | A `SequentialBlock` holding the body's stages. | `buildLayer(program, name, inputShape, args[, requirements])`, or a call `name(args)` from another layer or model. |
| `model name(params) { body }` | A `Model` whose blocks are the body's stages. | `buildModel(program, name, inputShape, args)`, or `buildModel(program, name, inputShape, weights, extraArgs, requirements)`, which binds the `WeightGroup` (a `StateDictionary`, for example) to every parameter typed `checkpoint`. |

**The shape annotation.** `layer f(...) -> [s] { }` declares the *input* shape `f` is built for
**when another layer or model calls it**: the call builds `f` for `[s]`, evaluated with the
call's arguments and the program's `data`/`state` entries in scope. Without the annotation, a call
yields a block factory, and that factory is built for the signal at the point where the call is
placed. That point is after the stages before it in a body, or the input of the `accum`,
`product`, `accum_blocks` or `concat_blocks` that holds it. `buildLayer` from Java uses the
`inputShape` it is given and never evaluates the annotation (see
[Known limits](#known-limits)). `PdslNestedLayerShapeTest` pins both behaviors.

### Parameter types

Arguments are bound by name from the Java argument map (`Map<String, Object>`) when a layer or
model is built directly, and by position when one layer calls another. A missing argument is an
error. An extra one is ignored. **Only `producer` converts or checks its value.** Every other type
annotation documents the expected value but does not check it.

| Type | Expected value | Build time or graph | Notes |
|------|----------------|---------------------|-------|
| `weight`, `weight [shape]` | A `PackedCollection` (or `null` for an omitted optional tensor) | Graph operand. A built-in such as `dense` or `rmsnorm` registers it as a trainable weight, or reads it as a constant (see the catalog). | The optional shape is parsed but not checked. |
| `scalar` | A number, or a `PackedCollection`/`Producer` of size 1 (a position, for example) | Either | A position usually arrives as a shape-`[1]` collection that is updated between forward passes. |
| `producer([shape])` | A `Number`, `PackedCollection` or `Producer<PackedCollection>` | Graph operand | Converted by `PdslInterpreter.normalizeToProducer` to a `CollectionProducer` of the declared shape. A value of the same total size is reshaped. A size-1 value is broadcast. Any other size is rejected. A literal folds to a constant. |
| `int`, `float`, `bool` | A Java number or boolean | Build time | Used in shape and arithmetic expressions and as build-time built-in arguments. |
| `[shape]` | Anything | Build time | Parsed with the type name `shape`. No conversion. |
| any identifier (`checkpoint`, `model_settings`, ...) | Anything | Build time | Documentary, except `checkpoint` on a model parameter: `buildModel(..., WeightGroup, ...)` binds the weights to every parameter of that type. A config-like value is passed as a `Map` (e.g. `TransformerConfig.toPdslSettings()`) and read with `settings.dim`. |

**Checkpoint paths.** On a `WeightGroup` value (such as a `StateDictionary`), `x.f` returns the
tensor named `f` if there is one. Otherwise it returns the group of everything under `f.`
(`WeightGroup.group`), and if that is empty it fails through `WeightGroup.require`, which names the
closest weight names. So `weights.model.norm.weight` is a tensor,
and `weights.model.layers` is the group that `stack` repeats over.

## Statements and their block semantics

A layer body is interpreted top to bottom into one `SequentialBlock`. The output shape of each
stage is the input shape of the next. Every expression statement must evaluate to a `Block` or a
block factory (`Function<TraversalPolicy, Block>`), and a factory is applied to the current
output shape. Anything else fails with "Cannot add *type* to block".

| Statement | Builds | Shape rule |
|-----------|--------|------------|
| `expr` / `return expr` | Appends the `Block`, or the factory applied to the current shape. `return` does not end the body. | The stage's output shape. |
| `let x = expr` | Binds `x` for the rest of the body. Appends nothing. | No change. |
| `let x = branch { body }` | `SequentialBlock.branch()`: a `BranchBlock` at the current point forwards the signal both down the main path and into a new `SequentialBlock` that holds `body`. `x` is bound to that branch. | Main path unchanged. The branch starts at the current shape. |
| `branch [name] { body }` | The same fork. With a name, the branch `Block` is bound to `name`, usually so that `scaled_dot_product(name, ...)` can read it as an operand. | Main path unchanged. |
| `accum { body }` | `SequentialBlock.accum`: the current tail is wrapped in a `BranchBlock` that feeds a sub-block holding `body`, and the sub-block's output is added to the main path (a residual connection). A body with one expression uses that block directly. | The sub-block's output must have the input's total size. Output: the input shape. |
| `product(a, b)` | `SequentialBlock.product`: `b` is branched from the current output, `a` is applied on the main path, and the result is `a(x) * b(x)` element-wise. Each argument is an inline block `{ }`, a block-valued expression, or a nested `product`/`accum_blocks`/`concat_blocks`. | Both operands start at the current shape. Output: `a`'s output shape. |
| `accum_blocks(a, b, ...)` | `LayerRoutingFeatures.accumBlocks`: every part receives the same input, and the outputs are summed element-wise. The backward pass gives each part the full output gradient and sums their input gradients. | All parts start at the current shape. The parts' outputs must agree. |
| `concat_blocks(a, b, ...)` | `LayerRoutingFeatures.concatBlocks`: every part receives the same input, and the outputs are concatenated. | Output: the flat `[sum of the parts' sizes]`. |
| `for i in a..b { body }` | Interprets `body` once for each integer `i` from `a` (inclusive) to `b` (exclusive), each time in a new scope that binds `i`. Unrolled at build time. | Stages append in sequence. |
| `stack group as m { body }` | Evaluates `group` to a `WeightGroup` and interprets `body` once per member, in `WeightGroup.members()` order (numeric names sorted numerically), with `m` bound to that member's sub-dictionary. The body has no index to compute with. | Stages append in sequence. |
| `for each channel { body }` | Needs `channels` in scope and a `PdslMultiChannelDispatcher` installed (the audio registrar installs one). See [pdsl-audio-dsp.md](pdsl-audio-dsp.md#multi-channel-constructs). | `[C, S]` → per-channel body output. |

**Model bodies** accept only expression statements, `let`, `for` and `stack`. Each expression
appends a block to the `Model`. `branch`, `accum`, `product`, `accum_blocks`, `concat_blocks`,
`return` and `for each channel` are rejected with "Unsupported statement in model body". Put the
composition in a layer and call the layer from the model.

**Compute requirements.** `buildLayer(..., requirements)` and `buildModel(..., requirements)` apply
the requirements to every layer the build constructs, as soon as each one is built. This includes
layers called from other layers and layers placed inside `accum_blocks` or `concat_blocks`, which
do not forward requirements to their parts. `PdslProgramScopeTest` pins this.

### Expressions and values

Expressions are evaluated in Java, at build time, to Java values: `Double`, `String`, `Boolean`,
`null`, `TraversalPolicy` (shape literals), `Map` (configs), `StateDictionary`,
`PackedCollection`, `Producer`, `Block` or block factory. **Arithmetic is build-time
arithmetic.** `+ - * /` and unary `-` convert both operands to `double` and fail on anything that
is not a number, so `dim / heads` works and `x + y` on two tensors does not. `/` is floating-point
division, and the result is truncated only when an integer is needed. Graph-level math on tensors
is always a built-in call: `scale(...)`, `dense(...)`, and so on.

`x[i]` is a per-channel subscript. It slices row `i` of `x` into `size / channels` elements and
needs `channels` in scope. Under vectorized `for each channel` it yields the whole bank. It is not
a general indexing operator.

## Name resolution and scope

**Scopes.** Every `buildLayer` or `buildModel` call is one *build*. It has a **program scope**,
which holds the entries of every `data` block and of every `state` block whose parameters were all
supplied. Each layer is built in a new scope directly inside the program scope, holding only its
parameters. A layer that is called from another layer sees its own parameters and the program
scope, **not** the caller's parameters or local names. `let`, `branch`, loop variables and `stack`
members are bound in nested scopes and are visible to the rest of the enclosing body.

**Identifiers** resolve to the innermost binding. A name bound to `null` resolves to `null`. If
there is no binding, a `config` of that name is evaluated to its map. Otherwise the result is
"Undefined identifier".

**Function calls** `f(args)` resolve in this order:

1. a `layer` the program defines named `f`, unless `f` is under construction in this build;
2. a primitive registered with `PdslInterpreter.registerPrimitive` (domain primitives);
3. a built-in in `PdslBuiltins`;
4. otherwise, "Unknown function". If `f` is under construction, the call is reported as a
   self-recursive layer with its cycle (`a -> b -> a`).

So a program's own layer **shadows** a registered primitive or built-in of the same name. Inside
that layer's own construction, directly or through layers it calls, its name reaches the
primitive or built-in it shadows. This lets a layer wrap the function it is named after. A layer
that really recurses into itself, with nothing else of that name, is rejected, because layers are
built eagerly and could never finish. `PdslProgramScopeTest` pins every one of these rules.

```pdsl
/** Named after the relu built-in: inside its own construction, relu() is the built-in. */
layer relu(size: int) -> [1, size] {
    relu()
    scale(2.0)
}
```

**Environment conventions.** These names have meaning to the multi-channel machinery:
`channels` (read by subscripts, `for each channel` and `PdslPrimitiveContext.channels()`),
`signal_size` (`PdslPrimitiveContext.signalSize()`), and `channel` (the per-channel loop index,
bound by `for each channel`).

## Imports

```pdsl
import "/pdsl/attention.pdsl"
import "/pdsl/feed_forward.pdsl"

/** Declared after the imports; may call any layer the imported assets define. */
layer block_with_imports(size: int) {
    accum { identity() }
}
```

- **Syntax.** `import "path"` statements come before every definition. The path is an absolute
  classpath resource beginning with `/`. A relative path, or an import after a definition, is a
  parse error with its line and column.
- **Resolution.** Imports are resolved only by `PdslLoader.parseResource` and `parseResources`.
  The loader merges the definitions of the root and of everything it imports, transitively,
  depth-first, with imports before the importer. Paths are canonicalized (`.`/`..` segments
  collapsed), so `/pdsl/a/../b.pdsl` and `/pdsl/b.pdsl` are one resource.
- **Diamonds are de-duplicated by resource.** If A imports B and C, and both import D, then D is
  merged once.
- **Duplicate definitions are an error.** If two *distinct* resources define the same name for the
  same kind of definition, the result is `'name' is defined more than once (in X and Y)`.
- **Cycles are an error.** `'/x.pdsl' imports itself (/x.pdsl -> /y.pdsl -> /x.pdsl)`.
- **Evidence.** The `engine/ml/src/test/resources/pdsl/imports/*.pdsl` fixtures and the import
  tests of `PdslLoaderTest` (`testImportBringsInDefinitions`, `testTransitiveImportChain`,
  `testDiamondImportResolvesOnce`, `testAliasedImportPathsDedupToOneResource`,
  `testImportCycleRejected`, `testImportDuplicateDefinitionRejected`,
  `testMissingImportFailsClearly`, `testRelativeImportRejected`, `testImportPlacement`).
- **`parseResources(a, b, ...)`** still assembles *unrelated* assets into one program, following
  each one's imports under the same rules. Prefer an `import` in the asset that depends on
  another, so the dependency travels with the asset and callers name only the root.

## Builtin catalog

The 29 built-ins dispatched by `PdslBuiltins` (package-private). These are the domain-agnostic
core, available to every program. The attention and rotary position primitives of the
machine-learning engine are an extension, cataloged under
[The machine-learning extension](#the-machine-learning-extension). The interpreter itself evaluates
the four arithmetic operators `+ - * /` and unary `-`. Unless noted, a built-in returns a block
factory, so its shape checks run when the stage is placed, not at the call. "Input" means the
signal the stage receives.

**Gradient column.** *trains*: the operand is registered as a layer weight, so the backward pass
delivers its gradient. *passes*: the gradient flows back to the stage input through the layer's
automatic differentiation. *constant*: the operand is captured as a constant in the graph and
receives no gradient. *none*: there is no backward path. Each entry is read from how the built-in
is lowered (the cited method). A test is cited where one checks the gradient. "Not verified"
means neither the code nor a test settles it.

### Projection, normalization and activation

| Builtin | Call forms | Input → output | Lowers to | Gradient |
|---------|------------|----------------|-----------|----------|
| `dense` | `dense(w)`, `dense(w, b)` | `[..., in]` → `[..., out]`, with `w` `[out, in]` | `LayerFeatures.dense` | input: passes; `w`, `b`: trains (registered weights) |
| `conv1d` | `conv1d(w, b, stride, padding)`, `conv1d(w, stride, padding)` | `[batch, in_ch, len]` → `[batch, out_ch, len']`, with `w` `[out_ch, in_ch, kernel]` | `ConvolutionLayerFeatures.convolution1d` | input: passes; `w`, `b`: trains (registered weights); no PDSL gradient test |
| `conv_transpose1d` | `conv_transpose1d(w, b, stride, padding, output_padding)`, `conv_transpose1d(w, stride, padding, output_padding)` | `[batch, in_ch, len]` → `[batch, out_ch, (len - 1) * stride - 2 * padding + kernel + output_padding]`, with `w` `[in_ch, out_ch, kernel]` | `ConvolutionLayerFeatures.convTranspose1d` | input: passes; `w`, `b`: trains (registered weights); no PDSL gradient test |
| `rmsnorm` | `rmsnorm(w, eps)`, `rmsnorm(w, b, eps)` (`b` may be `null`) | shape preserved; normalizes runs of `w`'s last-axis length | `NormalizationLayerFeatures.rmsnorm` | input: passes; `w`, `b`: trains (registered weights); `eps`: build-time constant |
| `layernorm` | `layernorm(w, b, eps)` (`b` may be `null`; `w` required) | shape preserved; runs of `size(w)` must tile the input | `NormalizationLayerFeatures.norm` | input: passes (`SequenceAttentionAssetTest.qkLayerNormGradientsMatchJavaAssembly`); `w`, `b`: trains (registered weights) |
| `softmax` | `softmax()` | `[rows, size]` (≥ 2 axes) preserved; max-subtracted, over the last axis | `ActivationFeatures.softmax(shape, true)` | input: passes |
| `silu` | `silu()` | preserved | `ActivationFeatures.silu` | input: passes |
| `relu` | `relu()` | preserved | `ActivationFeatures.relu` | input: passes |
| `gelu` | `gelu()` | preserved | `ActivationFeatures.gelu` | input: passes |
| `sigmoid` | `sigmoid()` | preserved | `ActivationFeatures.sigmoid` | input: passes |
| `tanh_act` | `tanh_act()` | preserved | `ActivationFeatures.tanh` | input: passes |
| `snake` | `snake(alpha, beta)` | `[batch, ch, len]` preserved; `alpha`, `beta` `[ch]` | `ActivationFeatures.snake` | input: passes; `alpha`, `beta`: **constant**. They are copied into pre-broadcast tensors at build time and not registered as weights, so they do not train despite the "learnable" name. |
| `scale` | `scale(f)` | preserved; `f` normalized to shape `[1]` (number, collection or producer) | `LayerFeatures.layer` around `multiply` | input: passes; `f`: **constant**. Under vectorized `for each channel`, `scale(bank[channel])` takes a `[channels]` bank. |
| `scale` | `scale(factors, axis)`, `scale(factors, axis, w1, ..., wN)` | preserved; `factors` `[batch, length(axis)]`, one factor per batch entry and position along `axis` (a 0 masks a position) | `LayerFeatures.scale(shape, axis, factors, weights)` | input: passes; `factors`: constant, **except** that the non-null `w1..wN` it was computed from are registered weights and train (`DifferentialAttentionAssetTest.theLearnedLambdaReceivesItsGradient`) |

### Shape and routing

| Builtin | Call forms | Input → output | Lowers to | Gradient |
|---------|------------|----------------|-----------|----------|
| `identity` | `identity()` | preserved | `LayerFeatures.passThrough` | input: passes unchanged |
| `reshape` | `reshape([out])`; `reshape([in], [out])` returns a `Block`, not a factory | equal total size | `LayerFeatures.reshape` | input: passes (backward reshapes back) |
| `permute` | `permute(a0, a1, ...)` | output axis `i` is input axis `a_i` | `LayerFeatures.permute` | input: passes; not verified by a test |
| `slice` | `slice(offset, size)`; `slice([shape], p0, p1, ...)` | flat → `[size]`; N-d → `shape` at position `p` | `LayerFeatures.subset` | input: passes; not verified by a test |
| `lerp` | `lerp(hidden)` | total `3 * hidden` (`[from, weight, to]`) → `[hidden]` | `LayerFeatures.lerpLayer` | input: passes; not verified by a test |
| `repeat` | `repeat(n)` | `[C, S]` → `[C * n, S]` | `LayerFeatures.layer` around `concat` | input: passes; not verified by a test |
| `repeat_each` | `repeat_each(n)` | `[rows, size]` → `[rows * n, size]` (`n == 1` is a pass-through) | `LayerFeatures.repeatEach` | input: passes; not verified by a test |
| `sum_channels` | `sum_channels()` | `[C, S]` → `[1, S]` | `LayerFeatures.layer` around `subset` sums | input: passes; not verified by a test |
| `capture` | `capture(slot)` | preserved; copies the input into `slot` (same total size) | `PdslBuiltins.callCapture` (`DefaultBlock`) | **none.** The backward cell is empty, so a `capture` cuts the gradient to everything upstream of it. |

### Caches and batched products

| Builtin | Call forms | Input → output | Lowers to | Gradient |
|---------|------------|----------------|-----------|----------|
| `cache_write` | `cache_write(cache, position)` | preserved; the input fills one row of the `[rows, size]` cache | `LayerFeatures.cacheWrite` | input: passes unchanged (pass-through backward); `cache`, `position`: constant |
| `cache_read` | `cache_read(cache, position)` | any → `[size]` (row `position`; the input is not read) | `LayerFeatures.cacheRead` | input: none (the output does not depend on it); `cache`, `position`: constant |
| `scaled_dot_product` | `scaled_dot_product(other, transpose)` | `transpose = true`: `[b, h, q, d]` × `[b, h, k, d]` → `[b, h, q, k]`; `false`: `[b, h, q, k]` × `[b, h, k, d]` → `[b, h, q, d]` | `MatrixFeatures.scaledDotProduct`; a branch operand is wired in with `LayerRoutingFeatures.compose` | input: passes. `other` as a **branch**: trains, because the gradient is carried into the branch (`SdpaPrimitivesTest.branchOperandCarriesTheGradientIntoTheBranch`, `SequenceAttentionAssetTest.assetCarriesGradientsIntoKeysAndValues`). `other` as a **tensor or producer**: constant. A branch feeds one product only, and a second use is rejected. |

### Build-time values

These return values, not stages. They are evaluated once, when the layer is built, and are never
part of a backward pass.

| Builtin | Call forms | Returns | Lowers to | Gradient |
|---------|------------|---------|-----------|----------|
| `sqrt` | `sqrt(x)` | `Double` | `Math.sqrt` | not a graph value |
| `shape` | `shape(d0, d1, ...)` (integers, not a shape literal) | `TraversalPolicy` | `TraversalPolicy` constructor | not a graph value |
| `range` | `range(source, [shape], offset)` | a zero-copy `PackedCollection` view | `PackedCollection.range` | not a graph value; a view of `source` that trains with `source` wherever it is used as a weight |
| `zeros` | `zeros([shape])` | a new zero-filled `PackedCollection`, one per call | `PackedCollection` + `clear()` | not a graph value (state storage) |

## Extending PDSL

A domain adds functions to PDSL by registering `PdslPrimitive`s. It never edits the interpreter.

```java
/** Registers the domain's primitives with an interpreter. */
static void registerWith(PdslInterpreter interpreter) {
    interpreter.registerPrimitive("gain", (args, ctx) -> {
        CollectionProducer factor = ctx.toProducer(args.get(0), shape(1), "gain() factor");
        return (Function<TraversalPolicy, Block>) inputShape ->
                layer("gain", inputShape, inputShape,
                        input -> multiply(c(input).each(), factor));
    });
}

PdslLoader loader = new PdslLoader(MyPrimitives::registerWith);
```

- **`PdslPrimitive<T>.dispatch(args, ctx)`** receives the evaluated arguments and returns a
  `Block`, a block factory or a build-time value, just as a built-in does.
- **`PdslPrimitiveContext`** is the per-call context. `toProducer(value, shape, name)` is the
  *single* conversion of a `Number`, `PackedCollection` or `Producer` argument to a
  `CollectionProducer` of the declared shape, and a primitive must not inspect the argument's
  type itself. `channels()`, `signalSize()` and `setChannels(int)` read and update the
  multi-channel environment. The static `toInt`, `toInts` and `toDouble` are the shared numeric
  coercions.
- **Registration.** There are two ways to register, and they differ in who decides.
  - *An extension, present whenever its module is.* A module whose primitives belong in every
    program that can see it implements `PdslExtension` and names the class in
    `META-INF/services/org.almostrealism.dsl.PdslExtension`. Every `PdslInterpreter` discovers the
    extensions on the classpath through `java.util.ServiceLoader` and registers them as it is
    constructed, so no caller has to ask for them. `AttentionPrimitives` in `engine/ml` is the
    example (see [The machine-learning extension](#the-machine-learning-extension)).
  - *A registrar, chosen per loader.* `PdslLoader(Consumer<PdslInterpreter>)` applies the
    registrar to every interpreter the loader creates, before any build, after the extensions.
    `AudioDspPrimitives::registerWith` is the canonical example: it registers the audio
    primitives and installs the `PdslMultiChannelDispatcher` that `for each channel` needs.
- **Resolution.** A registered primitive is consulted after the program's own layers and before
  the built-ins, so it may shadow a built-in. See
  [Name resolution and scope](#name-resolution-and-scope).
- **Placement.** A domain-agnostic tensor operation belongs in `PdslBuiltins`, in this module, so
  that every program can call it. An operation that only means something in one domain (audio
  filters, MIDI routing) belongs in that domain module's registrar. The interpreter core holds
  no domain-specific dispatch.
- **Vectorized `for each channel`.** A primitive that accepts a `PdslChannelBank` (one value per
  channel) can apply the whole bank in one computation. Any other primitive rejects the bank,
  and the interpreter falls back to per-channel dispatch.
- **Weights.** A model's `checkpoint` parameter is bound to a `WeightGroup` (in `domain/graph`),
  the interface the interpreter reads weights through. `StateDictionary` in `engine/ml` implements
  it; any other weight store can too.

### The machine-learning extension

`AttentionPrimitives` (`engine/ml`, package `org.almostrealism.ml.dsl`) is a `PdslExtension`, so
these primitives are available to every program whenever `engine/ml` is on the classpath. Each one
lowers to the `AttentionFeatures`, `SequenceAttentionFeatures` or `RotationFeatures` method of the
same purpose, and normalizes its tensor arguments through `PdslPrimitiveContext.toProducer`.

| Primitive | Call forms | Input → output | Lowers to | Gradient |
|-----------|------------|----------------|-----------|----------|
| `attention_scores` | `attention_scores(keys)` | `[heads, head_size]` → `[heads, seq_len]` against the `[seq_len, heads * head_size]` key cache | `AttentionFeatures.attentionScores` | input: passes; `keys`: constant |
| `causal_mask` | `causal_mask(position)` | `[heads, seq_len]` preserved; columns after `position` penalized | `AttentionFeatures.causalMask` | input: passes; `position`: constant |
| `weighted_values` | `weighted_values(values)` | `[heads, seq_len]` → `[1, heads * head_size]` | `AttentionFeatures.weightedValues` | input: passes; `values`: constant |
| `sequence_causal_mask` | `sequence_causal_mask()` | `[batch, heads, queries, keys]` preserved | `SequenceAttentionFeatures.causalLogitMask` | input: passes (`SequenceAttentionAssetTest.causalGradientsMatchJavaAssembly`) |
| `key_mask` | `key_mask(mask)` | `[batch, heads, queries, keys]` preserved; `mask` `[batch, keys]`, with 1 for valid and 0 for masked | `LayerFeatures.layer` adding `(mask - 1) * MASKED_LOGIT_PENALTY` | input: passes; `mask`: constant |
| `rope_rotation` | `rope_rotation([heads, head_size / 2, 2], freq_cis, position)`, which returns a `Block` | preserved | `RotationFeatures.ropeRotation` | input: passes; not verified by a test; `freq_cis`, `position`: constant |
| `sequence_rope` | `sequence_rope(inv_freq)` | `[batch, heads, seq_len, head_dim]` preserved; the leading `2 * size(inv_freq)` features rotate | `RotationFeatures.applyRotaryPositionEmbedding` | input: passes (`SequenceAttentionAssetTest` gradient tests); `inv_freq`: constant |
| `mra_rope_rotation` | `mra_rope_rotation([heads, head_size / 2, 2], head_groups)`, which returns a `Block`; `head_groups` is a Java `HeadGroupConfig[]` argument | preserved | `RotationFeatures.mraRopeRotation` | input: not verified; group tables: constant |
| `split_half_rope` | `split_half_rope(heads, head_size)` | total `heads * head_size` → `[heads, head_size / 2, 2]` | `RotationFeatures.reshapeToSplitHalfRope` | input: not verified |
| `merge_half_rope` | `merge_half_rope(heads, head_size)`, which returns a `Block` | `[heads, head_size / 2, 2]` → `[heads, head_size]` | `RotationFeatures.reshapeFromSplitHalfRope` | input: not verified |
| `rope_freqs` | `rope_freqs(theta, head_size, seq_len)`, a build-time value | `CollectionProducer` `[seq_len, head_size / 2, 2]` | `RotationFeatures.computeRopeFreqs` | constant |

## Errors and diagnostics

Every language error is a `PdslParseException`, an unchecked `RuntimeException` that carries
**only a message**. There are no structured position fields. Whether and how a position appears
depends on where the error comes from:

| Source | Position in the message | Examples |
|--------|-------------------------|----------|
| Parser (`PdslParser.error`) | ends with "at line L, column C" | "Expected RBRACE but found ... at line 3, column 5"; "Import statements must precede every definition at line ..."; "Import of 'x.pdsl' must name an absolute classpath resource beginning with '/' at line ..."; "Expected 'channel' after 'each' ..." |
| Lexer (`PdslLexer`) | "L:C" | "Unexpected character '!' at 2:7"; "Unexpected character '#' at 1:1" |
| Interpreter, call sites | "at line L" only | "Unknown function 'f' at line 12"; "Layer 'a' calls itself (a -> b -> a) at line 4; ..."; "stack repeats over a group of weights ... at line 9"; "No weight named '...' ..., at line 7" |
| Interpreter, elsewhere | none | "Undefined identifier: 'x'"; "Missing argument 'w' for layer 'l'"; "Layer 'l' expects 2 arguments, got 3"; "Cannot add Double to block"; "Unsupported statement in model body: AccumStatement"; "Layer 'l' not found"; "Field 'k' not found in config" |
| Built-ins | none, often with shapes | "dense() expects 1 or 2 arguments, got 0"; "softmax() expects a [rows, size] input shape, got [4]"; "scaled_dot_product() operand is a branch already consumed by another product ..." |
| Loader | none | "'x' is defined more than once (in /a.pdsl and /b.pdsl)"; "'/a.pdsl' imports itself (/a.pdsl -> /b.pdsl -> /a.pdsl) ..."; a missing resource is an `IllegalStateException` ("PDSL resource not found on classpath: ...") |

Two other failure modes are not `PdslParseException`s. A size mismatch caught by `SequentialBlock`
(for example an `accum` body whose output size differs from its input) is an
`IllegalArgumentException` with no message. A built-in that casts its argument (see below) throws
`ClassCastException` when given the wrong kind of value.

## Worked examples

Each of these complete programs is parsed by `PdslLanguageReferenceExamplesTest`. The named
layers are also built there for the stated input, and their output shapes are checked.

A residual two-layer perceptron. Built for `[1, 4]` with `w1` `[8, 4]` and `w2` `[4, 8]`, it
outputs `[1, 4]`:

```pdsl
/** A residual two-layer perceptron: x + W2 relu(W1 x). */
layer residual_mlp(w1: weight, w2: weight) {
    accum {
        dense(w1)
        relu()
        dense(w2)
    }
}
```

A gated feed-forward block, the shape of SwiGLU. Built for `[1, 4]` with the gate and up
projections `[8, 4]` and the down projection `[4, 8]`, it outputs `[1, 4]`:

```pdsl
/** A gated feed-forward network: W_down (silu(W_gate x) * W_up x). */
layer gated_ffn(w_gate: weight, w_up: weight, w_down: weight) {
    product(
        { dense(w_gate); silu() },
        { dense(w_up) }
    )
    dense(w_down)
}
```

Two projections of the same input, concatenated. Built for `[1, 4]` with `wa` `[3, 4]` and `wb`
`[5, 4]`, it outputs the flat `[8]`:

```pdsl
/** Two heads read the same input; their outputs are concatenated. */
layer two_heads(wa: weight, wb: weight) {
    concat_blocks(
        { dense(wa) },
        { dense(wb) }
    )
}
```

A configuration, a data block that slices one stacked tensor into two views, and a layer that sums
the two projections. `evaluateConfig("tiny")` gives `head_size = 16`. `sum_of_halves`, built for
`[1, 4]` with `stacked` `[12, 4]`, `rows = 6` and `cols = 4`, outputs `[1, 6]`:

```pdsl
config tiny {
    dim = 64
    heads = 4
    head_size = dim / heads
}

/** Two zero-copy views of one stacked weight tensor. */
data split_weights {
    stacked: weight
    rows: int
    cols: int
    top = range(stacked, [rows, cols], 0)
    bottom = range(stacked, [rows, cols], rows * cols)
}

/** Applies both views to the same input and sums the results. */
layer sum_of_halves() {
    accum_blocks(
        { dense(top) },
        { dense(bottom) }
    )
}
```

A layer called from another layer with a shape annotation, a loop, and a value-driven gain. Built
for `[1, 4]`, `scaled_twice(4)` and `halved(3)` each output `[1, 4]`:

```pdsl
/** Built for [1, size] wherever it is called. */
layer scaled(size: int, factor: float) -> [1, size] {
    scale(factor)
}

layer scaled_twice(size: int) {
    scaled(size, 2.0)
    scaled(size, 3.0)
}

/** Unrolled at build time: depth stages of scale(0.5). */
layer halved(depth: int) {
    for i in 0..depth {
        scale(0.5)
    }
}

/** The gain is a graph operand: a number, a collection or a producer of one value. */
layer gain(volume: producer([1])) {
    scale(volume)
}
```

The score half of self-attention, with its keys taken from a branch so that they train. Built for
`[1, 2, 3, 4]`, it outputs `[1, 2, 3, 3]`:

```pdsl
/** Q Kᵀ where the keys are a branch of the layer's own input. */
layer self_scores(factor: float) {
    branch keys {
        identity()
    }
    scale(factor)
    scaled_dot_product(keys, true)
}
```

A model whose weights come from a checkpoint. `stack` repeats a residual layer once per member of
`weights.model.layers`. The composition lives in a layer, because a model body cannot hold
`accum`. Built from a `StateDictionary` holding `model.layers.0.norm.weight` `[4]`,
`model.layers.0.proj.weight` `[4, 4]` (and the same for layer `1`) and `model.norm.weight` `[4]`,
for the input `[1, 4]`, it has three blocks and outputs `[1, 4]`:

```pdsl
/** Pre-norm residual projection: x + W rmsnorm(x). */
layer residual_projection(norm: weight, proj: weight) {
    accum {
        rmsnorm(norm, 1e-6)
        dense(proj)
    }
}

model tiny_decoder(weights: checkpoint) {
    stack weights.model.layers as block {
        residual_projection(block.norm.weight, block.proj.weight)
    }
    rmsnorm(weights.model.norm.weight, 1e-6)
}
```

The platform's shipped assets, under `engine/ml/src/main/resources/pdsl/`, are the larger
examples. `attention.pdsl` and `transformer.pdsl` are the autoregressive attention block and
pre-norm transformer layer. `sequence_attention.pdsl`, `sdpa.pdsl` and
`differential_attention.pdsl` are full-sequence attention. `feed_forward.pdsl` holds the
feed-forward networks. `qwen2.pdsl`, `qwen3.pdsl` and `t5gemma_encoder.pdsl` are whole models and
encoders. `midi/` and `audio/` hold the SkyTNT, GRU decoder, Oobleck codec and mixdown assets.

## Known limits

Each limit below is recorded with its evidence. Fixing any of them changes this page.

- **`if`/`else` have no meaning.** They are reserved keyword types of `PdslToken.Type`, but `PdslParser` has
  no production for them and `PdslNode` has no conditional node. A program that uses them does
  not parse.
- **Comparison operators are lexed, never parsed.** `PdslLexer` emits `==`, `!=`, `<`, `<=`, `>`
  and `>=`, but no grammar rule consumes them, so they are always a parse error.
- **`weight(key)` evaluates to a string.** The `WeightRef` expression evaluates to the string
  `"weight:<key>"`, and nothing in the interpreter, loader or built-ins resolves that string to a
  tensor. Read checkpoint tensors with a `checkpoint` parameter and field paths instead.
- **The `-> [shape]` annotation is ignored for a direct build.** `PdslInterpreter.buildLayer`
  builds for the `inputShape` the caller passes and never evaluates the annotation. The annotation
  can even name an unbound identifier, as `dense_relu` in `test_layers.pdsl` does, and the layer
  still builds directly. The annotation is the *input* shape used when another layer calls it, not
  the output shape that the arrow suggests.
- **Parameter types are not checked**, except `producer([shape])`. A `weight [shape]` shape is
  parsed and ignored. A wrong value surfaces later, as a cast or shape error inside a built-in.
- **Several built-ins cast instead of normalizing.** `dense`, `conv1d`, `conv_transpose1d`,
  `rmsnorm`, `layernorm`, `snake`, `sequence_rope` and `range` cast their tensor arguments to
  `PackedCollection`, so a producer argument fails with `ClassCastException`, not a
  `PdslParseException`.
- **`snake` parameters do not train.** `ActivationFeatures.snake` evaluates the broadcast of
  `alpha` and `beta` once, at build time, and registers no weights. A later change to `alpha` or
  `beta` is not seen by the built layer either.
- **`capture` cuts the gradient.** Its backward cell is empty (`PdslBuiltins.callCapture`), so
  nothing upstream of a `capture` receives a gradient.
- **`concat_blocks` has no backward pass.** `LayerRoutingFeatures.concatBlocks` builds its layer
  with a `null` back-propagation. Whether any gradient reaches the parts is not verified, and none
  is expected.
- **Imports are ignored by `parse(String)` and `parse(Path)`.** Those methods record the `Import`
  nodes, but only `parseResource`/`parseResources` resolve them. A source string that imports an
  asset and calls its layers fails at build time with "Unknown function".
- **Duplicate definitions within one source are silent.** The loader rejects a name defined by
  two *resources*. Within one file, a second `layer foo` silently replaces the first, because the
  interpreter indexes definitions in a map.
- **Every `data` block's parameters are required in every build**, even for a layer that never
  reads them. `state` blocks are skipped when a parameter is missing.
- **The `config` body takes no `;`**, unlike every other body.
- **Subscripts need `channels`.** `x[i]` divides `x` into `channels` rows, so it is unusable
  outside a multi-channel layer.
- **Errors carry no structured position**, and the textual position differs by source (see
  [Errors and diagnostics](#errors-and-diagnostics)).
- **No slicing of weight groups.** `stack` always repeats over every member of the group.

## Related documentation

- [Tutorial 10: Writing Models in PDSL](../tutorials/10-pdsl-model-definitions.html): a guided,
  progressive read of the shipped assets that ends in a small model built from a checkpoint
- [PDSL Audio DSP Reference](pdsl-audio-dsp.md): audio primitives, `for each channel`,
  producer-valued arguments, state blocks for DSP
- [ML Inference Pipeline](ml-inference-pipeline.md): which assets build which attention and
  transformer layers
- [Training a Language Model](training-a-language-model.md): how gradients reach the attention
  projections through the PDSL assets
- [ML module README](../../engine/ml/README.md)
