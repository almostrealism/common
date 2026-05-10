# Code Navigation Tools for Agents

Shell scripts in this directory help agents understand code structure without reading entire files.
All scripts are Python 3 and can be run directly from the repo root.

---

## ar-find-method

Find a Java method by name and return its full body with file path and line numbers.

```bash
# Find all implementations of a method by name
python3 tools/bin/ar-find-method isViolated
python3 tools/bin/ar-find-method forward flowtree/
python3 tools/bin/ar-find-method handleSubmit flowtree/src/
```

Output: file path, line range, and the complete method body for each match. Skips `/target/` and `/generated/` directories.

---

## ar-class-outline

Show the structure of a Java class without method bodies. Returns package, imports summary, class hierarchy, fields, and method signatures.

```bash
# Get a class table-of-contents without reading 1000+ lines
python3 tools/bin/ar-class-outline flowtree/src/main/java/io/flowtree/jobs/ClaudeCodeJob.java
python3 tools/bin/ar-class-outline flowtree/src/main/java/io/flowtree/jobs/EnforcementRule.java
```

Output: compact class structure — enough to know what methods exist and how to navigate to them, without reading the implementation.

---

## ar-find-symbol

Find all usages of a Java symbol (class name, method name, field name, constant) across the codebase.

```bash
# Find all references to a class
python3 tools/bin/ar-find-symbol ClaudeCodeJob --files
python3 tools/bin/ar-find-symbol EnforcementRule flowtree/
python3 tools/bin/ar-find-symbol DEFAULT_MAX_RULE_RETRIES --context 2
```

Options:
- `--files` — print only file paths
- `--context N` — show N lines of context (default: 1)
- `--all` — search all file types (default: java, py, xml, yaml, md)

---

## ar-find-callers

Find all call sites of a Java method. Shows invocations but not declarations.

```bash
# Find all places a method is called
python3 tools/bin/ar-find-callers handleSubmit flowtree/
python3 tools/bin/ar-find-callers isViolated --context 3
python3 tools/bin/ar-find-callers extractNewFilePaths flowtree/ --files
```

Options:
- `--context N` — show N lines of context (default: 2)
- `--files` — print only file paths

---

## Typical Agent Workflow

Instead of reading a 1700-line file to find one method:

```bash
# 1. Get class structure (5 seconds, not 1700 lines)
python3 tools/bin/ar-class-outline path/to/MyClass.java

# 2. Look up a specific method (returns just the method body)
python3 tools/bin/ar-find-method myMethod path/to/module/

# 3. Find all callers of that method
python3 tools/bin/ar-find-callers myMethod

# 4. Find all files that reference a class
python3 tools/bin/ar-find-symbol MyClass --files
```
