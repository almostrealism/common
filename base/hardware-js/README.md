# Hardware JS Module (ar-hardware-js)

The JavaScript code-generation backend. It is the first backend in a module of its own: the
others (C, OpenCL, Metal, CUDA) are still in `ar-hardware`, and the intent is for each to
become a module like this one.

## Contents

- `JavaScriptLanguageOperations` — the `LanguageOperations` for JavaScript: untyped
  variables, a single work item (every kernel index is `0`), no memory-space annotations, and
  `Math.PI`.
- `JavaScriptPrintWriter` — a `CodePrintWriter` that writes declarations as `var`, method calls
  as statements (`member.name(args);`), named scopes as functions whose signature lists the
  scope's array arguments followed by its scalar parameters (by name, untyped), and unnamed
  scopes as blocks.

```java
StringBuilder js = new StringBuilder();
JavaScriptPrintWriter out = new JavaScriptPrintWriter(PrintWriter.of(js::append));
out.beginScope("main", null, null, List.of(), List.of());
out.println(new Method<>("gl", "clear", List.of(new IntegerConstant(0))));
out.endScope();
```

## Dependencies

Only `ar-code`, for the expression, scope and code-printing types. It does not need
`ar-hardware`.

## Tests

The tests are in `engine/utils` (`JavaScriptPrintWriterTest`), where they can extend
`TestSuiteBase` and run in the existing CI lanes.
