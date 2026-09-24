# File Staging System

The file staging system evaluates which changed files should be committed to
git, applying a series of configurable guardrails that prevent secrets, build
artifacts, binary files, and oversized files from entering the repository. The
system is intentionally separated from git operations: it classifies files but
does not stage them. The caller is responsible for running `git add` on the
approved files.

**Package:** `io.flowtree.jobs`

**Key classes:**
- `FileStager` -- Stateless evaluation engine
- `FileStagingConfig` -- Immutable configuration (builder pattern)
- `StagingResult` -- Immutable result container
- `GitJobConfig` -- Provides default exclusion and protection patterns
- `TestMethodProtection` -- Test-method-granularity analysis for guardrail 2's
  `.java` protected-path check (see [Guardrail 2](#guardrail-2-test-file-protection))

### Design Principles

The file staging system follows several design principles:

**Separation of evaluation from action.** `FileStager` classifies files but
never calls `git add`. This makes the evaluation logic independently testable,
allows callers to inspect and override decisions, and prevents unintended side
effects during evaluation.

**Fail-safe defaults.** When a guardrail check encounters an error (e.g., the
git remote is unreachable for a base-branch check), the system errs on the
side of caution by blocking the file rather than allowing it through. This
prevents accidental commits of sensitive or protected files.

**Immutable configuration.** `FileStagingConfig` is frozen after construction
through the builder pattern, with all collections stored as unmodifiable
copies. This eliminates race conditions between configuration and evaluation,
and prevents callers from accidentally mutating shared configuration objects.

**Comprehensive logging.** Every file decision (stage or skip) is logged with
the reason and the file path. This creates a complete audit trail that is
invaluable for debugging why a particular file was or was not committed.

---

## Table of Contents

1. [FileStager Class Reference](#filestager-class-reference)
2. [FileStagingConfig Options and Builder Usage](#filestagingconfig-options-and-builder-usage)
3. [The Four Guardrails](#the-four-guardrails)
4. [Glob Pattern Syntax and Matching Rules](#glob-pattern-syntax-and-matching-rules)
5. [Default Exclusion Patterns](#default-exclusion-patterns)
6. [Protected Path Patterns](#protected-path-patterns)
7. [StagingResult Structure](#stagingresult-structure)
8. [Discarded Changes Are Reported, Not Silent](#discarded-changes-are-reported-not-silent)
9. [Usage Examples](#usage-examples)

---

## FileStager Class Reference

**Source file:** `flowtree/runtime/src/main/java/io/flowtree/jobs/FileStager.java`

**Implements:** `ConsoleFeatures`

`FileStager` is a stateless utility class. It holds no mutable state between
calls, making it safe to share across threads and reuse across multiple
evaluations. A single instance can be used for the lifetime of the application
without concern for accumulated state or resource leaks.

### Inner Interface: `FileStager.GitOperations`

```java
public interface GitOperations {
    int execute(String... args) throws IOException, InterruptedException;

    default String executeWithOutput(String... args) throws IOException, InterruptedException {
        throw new UnsupportedOperationException(
                "executeWithOutput is not implemented by this GitOperations");
    }
}
```

This interface abstracts over git command execution for guardrail 2:
`execute` reports an exit code, `executeWithOutput` reports command output
(merge-base resolution, the merge-base file listing, and reading file content
at the merge-base for `TestMethodProtection`). Only `execute` is abstract, so
this remains a valid functional interface for guardrails that never enable
`protectTestFiles`. `evaluateFiles` resolves the merge-base and its file
listing once per call whenever `protectTestFiles` is set, before it knows
whether any candidate file is under a protected path — so any caller that
enables `protectTestFiles` must supply a real `executeWithOutput`, or every
guardrail-2 file (whole-file paths and `.java` protected sources alike) fails
closed. See `GitManagedJob.asGitOperations()` for the production adapter.

### `evaluateFiles`

```java
public StagingResult evaluateFiles(
        List<String> changedFiles,
        FileStagingConfig config,
        File workingDirectory,
        GitOperations gitOps)
```

Evaluates a list of changed files against the configured guardrails and
returns a `StagingResult`.

| Parameter          | Type                 | Description |
|--------------------|----------------------|-------------|
| `changedFiles`     | `List<String>`       | Changed file paths, relative to the working directory. |
| `config`           | `FileStagingConfig`  | The staging configuration with guardrail rules. |
| `workingDirectory` | `File`               | The git working directory, used to resolve file paths for size and binary checks. |
| `gitOps`           | `GitOperations`      | Git operations interface for base-branch existence checks. |

**Returns:** A `StagingResult` with two lists: files that passed all
guardrails (staged) and files that were skipped (with reasons).

**Important:** This method does NOT perform any git operations. It does not
call `git add`. The caller is responsible for staging the files listed in
`StagingResult.getStagedFiles()`.

**Deleted file handling:** Files are detected as deleted when the resolved
`File` object does not exist on disk. Deleted files are exempt from the size
limit and binary detection guardrails (guardrails 3 and 4) because there is
no on-disk content to measure. They are still subject to pattern exclusion
(guardrail 1) and test file protection (guardrail 2) because those guardrails
operate on the file path, not its content.

**Evaluation order guarantee:** Files are evaluated in the order they appear
in the `changedFiles` list. The order of the resulting `stagedFiles` and
`skippedFiles` lists preserves this order. This is relevant when callers want
deterministic output (e.g., for testing or for consistent log output).

### `matchesAnyPattern` (static)

```java
public static boolean matchesAnyPattern(String path, Set<String> patterns)
```

Tests whether a file path matches any pattern in a set of glob patterns.
Iterates through the pattern set and returns `true` on the first match, so
the order of patterns does not affect the result, only performance (patterns
that match frequently should ideally come first, but since `Set` does not
guarantee order, this is not something callers can control).

| Parameter  | Type           | Description |
|------------|----------------|-------------|
| `path`     | `String`       | The file path to test (relative to the repository root). |
| `patterns` | `Set<String>`  | The set of glob patterns to match against. |

**Returns:** `true` if the path matches at least one pattern.

### `matchesGlobPattern` (static)

```java
public static boolean matchesGlobPattern(String path, String pattern)
```

Tests whether a file path matches a single glob pattern. See
[Glob Pattern Syntax](#glob-pattern-syntax-and-matching-rules) for the
detailed conversion algorithm and multi-strategy matching behavior.

| Parameter | Type     | Description |
|-----------|----------|-------------|
| `path`    | `String` | The file path to test. |
| `pattern` | `String` | The glob pattern to match. |

**Returns:** `true` if the path matches the pattern under any of the four
matching strategies (full regex, prefix-agnostic, suffix, exact equality).

### `isBinaryFile` (static)

```java
public static boolean isBinaryFile(File file)
```

Determines whether a file appears to be binary by checking for null bytes
in its content. Reads the entire file into memory (via `Files.readAllBytes`)
and examines up to the first 8000 bytes.

| Parameter | Type   | Description |
|-----------|--------|-------------|
| `file`    | `File` | The file to examine. |

**Returns:** `true` if the file appears to be binary (more than 10% null
bytes in the first 8000 bytes). Returns `false` for non-existent files and
directories. Returns `true` if the file cannot be read (fail-safe: assume
binary if unreadable).

**Note on memory usage:** The method reads the entire file into a byte array
via `Files.readAllBytes()`, even though it only examines the first 8000
bytes. For very large files, this allocates more memory than strictly
necessary. However, since files that reach this guardrail have already passed
the size limit check (guardrail 3, default 1 MB), the memory impact is
bounded by the configured maximum file size.

### `formatSize` (static)

```java
public static String formatSize(long bytes)
```

Formats a byte count into a human-readable string using appropriate units.
Returns values like `"512 B"`, `"1.5 KB"`, or `"2.3 MB"`.

| Input Range | Unit | Example |
|-------------|------|---------|
| 0 -- 1023   | B    | `"512 B"` |
| 1024 -- 1048575 | KB | `"1.5 KB"` |
| 1048576+    | MB   | `"2.3 MB"` |

This method is used in skip reason messages to make file size limits readable
for human review.

---

## FileStagingConfig Options and Builder Usage

**Source file:** `flowtree/runtime/src/main/java/io/flowtree/jobs/FileStagingConfig.java`

`FileStagingConfig` is an immutable configuration object constructed through a
builder. All `Set` fields are stored as unmodifiable copies to guarantee
immutability after construction.

### Builder Methods

| Method                          | Type             | Default             | Description |
|---------------------------------|------------------|---------------------|-------------|
| `maxFileSizeBytes(long)`        | `long`           | `1024 * 1024` (1 MB)| Maximum file size threshold. Files exceeding this are skipped. |
| `excludedPatterns(Set<String>)` | `Set<String>`    | Empty set           | Glob patterns used to exclude files from staging. |
| `protectedPathPatterns(Set<String>)` | `Set<String>` | Empty set         | Glob patterns identifying protected test/CI files. |
| `protectTestFiles(boolean)`     | `boolean`        | `false`             | Whether test file protection is active. |
| `baseBranch(String)`            | `String`         | `"master"`          | Base branch for test file existence checks. |

### Construction Example

```java
FileStagingConfig config = FileStagingConfig.builder()
    .maxFileSizeBytes(2 * 1024 * 1024)       // 2 MB
    .excludedPatterns(GitJobConfig.DEFAULT_EXCLUDED_PATTERNS)
    .protectedPathPatterns(GitJobConfig.PROTECTED_PATH_PATTERNS)
    .protectTestFiles(true)
    .baseBranch("main")
    .build();
```

### Getter Methods

| Method                       | Returns            |
|------------------------------|--------------------|
| `getMaxFileSizeBytes()`      | `long`             |
| `getExcludedPatterns()`      | `Set<String>` (unmodifiable) |
| `getProtectedPathPatterns()` | `Set<String>` (unmodifiable) |
| `isProtectTestFiles()`       | `boolean`          |
| `getBaseBranch()`            | `String`           |

### Constant

```java
public static final long DEFAULT_MAX_FILE_SIZE = 1024 * 1024;  // 1 MB
```

### Immutability Guarantee

After `build()` is called, the resulting `FileStagingConfig` is completely
immutable. The builder's `Set` fields are copied into new `HashSet` instances
and then wrapped with `Collections.unmodifiableSet()`. This means:

- Subsequent modifications to the builder do not affect previously built
  configurations.
- The `Set` objects returned by getter methods cannot be modified -- calling
  `add()` or `remove()` on them throws `UnsupportedOperationException`.
- The configuration can be safely shared between threads or stored for reuse
  across multiple `evaluateFiles()` calls.

### Relationship to GitJobConfig

`FileStagingConfig` and `GitJobConfig` serve complementary roles.
`GitJobConfig` is the broader configuration object for the entire
`GitManagedJob` lifecycle (branch names, push settings, dry run mode, etc.)
and also defines the default exclusion and protection pattern constants.
`FileStagingConfig` is a focused configuration for the file staging step
only.

Typical usage is to pull patterns from `GitJobConfig` constants into a
`FileStagingConfig` builder:

```java
FileStagingConfig stagingConfig = FileStagingConfig.builder()
    .excludedPatterns(gitJobConfig.getAllExcludedPatterns())
    .protectedPathPatterns(GitJobConfig.PROTECTED_PATH_PATTERNS)
    .protectTestFiles(gitJobConfig.isProtectTestFiles())
    .baseBranch(gitJobConfig.getBaseBranch())
    .maxFileSizeBytes(gitJobConfig.getMaxFileSizeBytes())
    .build();
```

---

## The Four Guardrails

`FileStager.evaluateFiles()` applies four guardrails to each file, in order.
A file is skipped as soon as it fails any guardrail -- subsequent guardrails
are not evaluated for that file. A file that passes all four guardrails is
added to the staged list.

### Guardrail 1: Pattern Exclusion

**Check:** Does the file path match any pattern in
`config.getExcludedPatterns()`?

**Effect:** If matched, the file is skipped with reason `"(excluded pattern)"`.

**Applies to:** All files, including deleted files.

**Rationale:** This is the primary defense against committing secrets, build
artifacts, IDE configuration, binary media, and other files that should never
enter the repository. The default patterns provided by
`GitJobConfig.DEFAULT_EXCLUDED_PATTERNS` cover the most common cases.

### Guardrail 2: Test File Protection

**Check:** Is `config.isProtectTestFiles()` true AND does the file path match
any pattern in `config.getProtectedPathPatterns()`?

**Granularity:** The check is **whole-file** for `.github/workflows/**` and
`.github/actions/**` (CI/workflow configuration has no "method" structure and
is locked in full), and for any non-`.java` file matching a protected pattern
(e.g. a test resource file). For a `.java` file it is **test-method-level**,
delegated to `TestMethodProtection`:

- If the file did not exist at the merge-base of `origin/<baseBranch>` and
  `HEAD`, it is a branch-new file and is allowed through in full.
- Otherwise its `@Test`-annotated methods at the merge-base are compared
  against its current content. A file is blocked only when an existing test
  method's exact content (annotations through the closing brace) differs or
  the method is gone — "differs" includes a pure addition, such as an early
  `return` or a newly added `@Ignore`/`@TestDepth` annotation on an existing
  method, since an addition-only diff can still hide a test. Adding a new
  `@Test` method, and editing fixtures, helpers, fields, or a method that was
  itself absent at the merge-base, are all allowed.

**Effect:** A blocked file is skipped with reason
`"(protected - <detail>)"`, where `<detail>` names why — e.g. `"exists on
base branch"` (whole-file path), or `"existing test method(s) changed or
removed: testFoo"` (method-level path).

**Merge-base, not base-branch tip:** Every existence and content check is
against the merge-base of `origin/<baseBranch>` and `HEAD`, not the live tip
of the base branch. The base branch keeps moving after a feature branch
forks from it; comparing against its current tip would misattribute the base
branch's own later edits to the agent's branch.

**Shared implementation:** The method-level check invokes
`tools/ci/agent-protection/test-method-lines.awk` as a subprocess, read from
the merge-base so a branch cannot alter the extractor it is judged by. The
same script is what `validate-agent-commit.sh` uses to tell a new test
method from an edited one.

**A per-job lock, not the repository rule.** Guardrail 2 is on only for a job
submitted with `protectTestFiles` — one whose premise is that the existing
tests are the reference, above all a job sent to make failing tests pass.
Every branch, locked or not, is held to `test-integrity-check` in CI, which
allows an existing test to be edited but not weakened (see the root
`CLAUDE.md`, "Agent integrity").

**Fail-safe behavior:** Any failure along the way — the merge-base cannot be
resolved, the merge-base file listing cannot be read, base or current content
cannot be read, the shared awk script is missing from the merge-base, or the
awk subprocess itself fails — makes the file fail closed: it is treated as
protected in full, the same as the whole-file check. A warning names which
step failed.

**Rationale:** This guardrail prevents automated agents from hiding test
failures by modifying existing tests instead of fixing production code, while
still letting an agent add new test methods (or edit ones it introduced on
the branch) to an existing test class — see `TestMethodProtection` for the
full rationale, including why fixtures/helpers/fields are never locked.

### Guardrail 3: File Size Limit

**Check:** Is the file larger than `config.getMaxFileSizeBytes()`?

**Effect:** If exceeded, the file is skipped with reason
`"(exceeds <formatted size>)"`.

**Applies to:** Existing files only. Deleted files are exempt because they
have no on-disk size.

**Default threshold:** 1 MB (`1024 * 1024` bytes).

**Rationale:** Large files bloat the repository history permanently (even if
later deleted) and are usually generated artifacts, data files, or binaries
that should not be versioned.

### Guardrail 4: Binary Detection

**Check:** Does the file contain more than 10% null bytes in its first 8000
bytes?

**Effect:** If classified as binary, the file is skipped with reason
`"(binary file)"`.

**Applies to:** Existing files only. Deleted files are exempt. Non-existent
files and directories return `false` (not binary).

**Algorithm:**
1. Read up to 8000 bytes from the file.
2. Count null (`0x00`) bytes.
3. If the count exceeds `checkLength / 10` (more than 10%), classify as
   binary.

The 10% threshold with early termination is an optimization: the loop breaks
as soon as the null count exceeds the threshold, avoiding a full scan of the
8000-byte buffer.

**Fail-safe:** If the file cannot be read (IOException), it is classified as
binary. This prevents unreadable files from being committed.

**Rationale:** Binary files that slip past the pattern exclusion guardrail
(e.g., a binary file with an unusual extension) are caught here. The null-byte
heuristic is the same approach used by `git diff` internally to determine
whether a file should be shown as a text diff or as `"Binary files differ"`.

### Guardrail Evaluation Flow

The following pseudocode summarizes the complete evaluation flow for a single
file:

```
for each file in changedFiles:
    resolve file on disk
    is_deleted = !file.exists()

    if matchesAnyPattern(file, excludedPatterns):
        skip("excluded pattern")
        continue

    if protectTestFiles AND matchesAnyPattern(file, protectedPathPatterns):
        if isCiWorkflowFile(file) OR NOT file.endsWith(".java"):
            if existsOnBaseBranch(file, mergeBase):
                skip("protected - exists on base branch")
                continue
            else:
                log("ALLOWED (branch-new file)")
        else:
            verdict = TestMethodProtection.evaluate(file, mergeBase)
            if NOT verdict.allowed:
                skip("protected - " + verdict.reason)
                continue
            else:
                log("ALLOWED (" + verdict.reason + ")")

    if NOT is_deleted AND file.size > maxFileSizeBytes:
        skip("exceeds <size>")
        continue

    if NOT is_deleted AND isBinaryFile(file):
        skip("binary file")
        continue

    stage(file)
```

The `continue` statements after each skip mean that once a file fails any
guardrail, no further guardrails are evaluated. This is a minor performance
optimization but, more importantly, it ensures the skip reason reflects the
first guardrail that rejected the file, providing clear diagnostic information.

---

## Glob Pattern Syntax and Matching Rules

`FileStager.matchesGlobPattern()` converts glob patterns to Java regular
expressions and tests file paths against them. The conversion handles four
special characters and performs multiple match attempts for flexibility.

### Glob-to-Regex Conversion Table

| Glob Token | Regex Replacement | Description |
|------------|-------------------|-------------|
| `**/`      | `(.*/)?`          | Matches zero or more directory components. Only recognized when followed by `/`. |
| `**` (trailing) | `.*`        | Matches everything (any characters including `/`). Used at the end of a pattern. |
| `*`        | `[^/]*`           | Matches any characters except `/` (within a single path component). |
| `?`        | `[^/]`            | Matches exactly one character except `/`. |
| `.`        | `\\.`             | Escaped to match a literal dot. |
| All other characters | Literal | Passed through unchanged. |

### Conversion Algorithm

The algorithm processes the pattern character by character:

1. If the current character is `*` and the next character is also `*`:
   - If followed by `/`, emit `(.*/)?` and advance by 3 characters.
   - Otherwise (trailing `**`), emit `.*` and advance by 2 characters.
2. If the current character is `*` (single), emit `[^/]*` and advance by 1.
3. If the current character is `?`, emit `[^/]` and advance by 1.
4. If the current character is `.`, emit `\\.` and advance by 1.
5. Otherwise, emit the character as-is and advance by 1.

### Multi-Strategy Matching

After converting the glob to a regex `r`, the method tries four match
strategies. A path matches if **any** strategy succeeds:

1. **Full regex match:** `Pattern.matches(r, path)` -- the regex matches the
   entire path.
2. **Prefix-agnostic match:** `Pattern.matches(".*/" + r, path)` -- the
   regex matches after any leading directory prefix. This allows patterns
   like `*.java` to match `src/main/Foo.java`.
3. **Suffix match:** `path.endsWith("/" + pattern)` -- the path ends with
   the literal pattern preceded by `/`. This handles simple filename patterns
   like `.DS_Store`.
4. **Exact equality:** `path.equals(pattern)` -- the path is identical to
   the pattern string. This handles the case where the file is at the
   repository root (e.g., `.env` matching `.env`).

### Examples

| Pattern | Path | Matches? | Matching Strategy |
|---------|------|----------|-------------------|
| `*.java` | `Foo.java` | Yes | Full regex |
| `*.java` | `src/main/Foo.java` | Yes | Prefix-agnostic |
| `target/**` | `target/classes/Foo.class` | Yes | Full regex |
| `**/src/test/**` | `ml/src/test/FooTest.java` | Yes | Full regex |
| `.DS_Store` | `.DS_Store` | Yes | Exact equality |
| `.DS_Store` | `subdir/.DS_Store` | Yes | Suffix match |
| `.env` | `.env` | Yes | Exact equality |
| `.env.*` | `.env.local` | Yes | Full regex |
| `*.pem` | `certs/key.pem` | Yes | Prefix-agnostic |
| `?.txt` | `a.txt` | Yes | Full regex |
| `?.txt` | `ab.txt` | No | None |

### Limitations and Edge Cases

**No character class support.** The conversion does not handle `[abc]` or
`[a-z]` character classes. If a pattern contains square brackets, they are
passed through as literal characters to the regex, which may cause unexpected
matches or regex syntax errors.

**No negation support.** The system does not support negation patterns (e.g.,
`!*.java` to mean "everything except .java files"). All patterns are positive
matches. To exclude a file from exclusion, you would need to modify the
pattern set itself.

**Case sensitivity.** Pattern matching is case-sensitive. The pattern `*.Java`
would not match `Foo.java`. This matches git's default behavior on
case-sensitive filesystems.

**Path separators.** Patterns must use forward slashes (`/`) as path
separators, even on Windows. File paths should also use forward slashes for
consistent matching. The underlying `Pattern.matches()` operates on strings,
so backslash path separators would need to be normalized before matching.

**Dot at the start of filenames.** Patterns like `*` do not have special
handling for leading dots. The pattern `*` matches both `README.md` and
`.gitignore` because `[^/]*` allows any non-slash character including dot.
This differs from shell globbing where `*` typically does not match
dot-prefixed filenames.

---

## Default Exclusion Patterns

`GitJobConfig.DEFAULT_EXCLUDED_PATTERNS` defines the comprehensive set of
patterns that are excluded from staging by default. These are organized into
seven categories.

### Secrets and Credentials

| Pattern | Rationale |
|---------|-----------|
| `.env` | Environment variable files, often containing API keys and database passwords. |
| `.env.*` | Environment variants (`.env.local`, `.env.production`, etc.). |
| `*.pem` | PEM-encoded certificates and private keys. |
| `*.key` | Private key files. |
| `*.p12` | PKCS#12 keystores. |
| `*.pfx` | PKCS#12 keystores (Windows naming convention). |
| `credentials.json` | Service account credentials (e.g., Google Cloud). |
| `secrets.json` | Application secret configuration. |
| `**/secrets/**` | Any file in a directory named `secrets`, at any depth. |

### Build Outputs and Dependencies

| Pattern | Rationale |
|---------|-----------|
| `target/**` | Maven build output directory. |
| `build/**` | Gradle build output directory. |
| `dist/**` | Distribution/package output (Node.js, Python, etc.). |
| `out/**` | IntelliJ IDEA output directory. |
| `node_modules/**` | Node.js dependency tree (can contain hundreds of thousands of files). |
| `.gradle/**` | Gradle cache and wrapper files. |
| `.m2/**` | Local Maven repository cache. |
| `*.class` | Compiled Java bytecode. |
| `*.jar` | Java archive files. |
| `*.war` | Web application archives. |
| `*.ear` | Enterprise application archives. |

### IDE and OS Files

| Pattern | Rationale |
|---------|-----------|
| `.idea/**` | IntelliJ IDEA project configuration. |
| `.vscode/**` | Visual Studio Code workspace settings. |
| `*.iml` | IntelliJ module files. |
| `.DS_Store` | macOS Finder metadata. |
| `Thumbs.db` | Windows Explorer thumbnail cache. |

### Binary and Media Files

| Pattern | Rationale |
|---------|-----------|
| `*.exe`, `*.dll`, `*.so`, `*.dylib` | Platform-specific executables and shared libraries. |
| `*.zip`, `*.tar`, `*.gz`, `*.rar`, `*.7z` | Compressed archives. |
| `*.png`, `*.jpg`, `*.jpeg`, `*.gif`, `*.bmp`, `*.ico` | Image files. |
| `*.mp3`, `*.mp4`, `*.wav`, `*.avi`, `*.mov` | Audio and video files. |
| `*.pdf`, `*.doc`, `*.docx`, `*.xls`, `*.xlsx` | Document files. |

### Database and Logs

| Pattern | Rationale |
|---------|-----------|
| `*.db` | Database files (SQLite, etc.). |
| `*.sqlite` | SQLite database files. |
| `*.log` | Log files (can be very large and contain sensitive information). |

### Hardware Acceleration Outputs (AR-Specific)

| Pattern | Rationale |
|---------|-----------|
| `Extensions/**` | Directory where the AR framework generates JNI libraries, OpenCL kernels, and Metal shaders at runtime. These are machine-specific and regenerated on each run. |
| `*.cl` | OpenCL kernel source files (generated). |
| `*.metal` | Metal shader source files (generated). |

### Claude Code Agent Outputs and Settings

| Pattern | Rationale |
|---------|-----------|
| `claude-output/**` | Directory where the Claude Code agent writes its output artifacts. |
| `commit.txt` | Temporary file used to pass commit messages to the git operations step. Cleaned up after commit. |
| `.claude/projects/**` | Per-project local memory/session state Claude Code writes under `<project>/.claude/projects/<id>/`. Genuinely machine-local. |
| `.claude/*.local.json` | Machine-local settings (per-user overrides of `settings.json`). |
| `.claude/scheduled_tasks.lock` | Lock file regenerated on every Claude Code run. |
| `settings.local.json` | Local settings file (may contain user-specific configuration; matches `.gitignore`'s bare-machine-local settings.json at the project root, outside `.claude/`). |

The remaining contents of `.claude/` — `hooks/`, `agents/`, `commands/`,
`settings.json` — are project-shared and MUST be committable. A blanket
`.claude/**` exclusion previously dropped them silently; the narrow patterns
above replaced it and are covered by `FileStagerTest.allowsProjectSharedClaudeContent`
and `FileStagerTest.excludesMachineLocalClaudeContent`.

### Extending the Default Patterns

`GitJobConfig.Builder` provides methods to customize exclusion patterns:

```java
// Add patterns on top of the defaults
GitJobConfig.builder()
    .addExcludedPatterns("*.tmp", "*.bak", "scratch/**")
    .build();

// Replace defaults entirely (use with caution)
GitJobConfig.builder()
    .clearDefaultExcludedPatterns()
    .excludedPatterns(myCustomPatterns)
    .build();

// Add to a separate set that is merged at query time
GitJobConfig.builder()
    .additionalExcludedPatterns(extraPatterns)
    .build();
```

The combined set is retrieved via `GitJobConfig.getAllExcludedPatterns()`, which
merges `excludedPatterns` and `additionalExcludedPatterns` into a single
unmodifiable set.

### Pattern Set Architecture in GitJobConfig

`GitJobConfig` maintains two separate pattern sets:

1. **`excludedPatterns`** -- initialized from `DEFAULT_EXCLUDED_PATTERNS` by
   default. Can be replaced entirely via `excludedPatterns(Set<String>)` or
   cleared via `clearDefaultExcludedPatterns()`.

2. **`additionalExcludedPatterns`** -- starts empty. Populated via
   `additionalExcludedPatterns(Set<String>)` or `addExcludedPatterns(String...)`
   for incremental additions.

The two-set design allows callers to extend the defaults without losing them.
A common pattern is to keep all defaults and add project-specific patterns:

```java
GitJobConfig config = GitJobConfig.builder()
    .addExcludedPatterns("*.tmp", "*.bak", "scratch/**")
    .build();
// getAllExcludedPatterns() returns DEFAULT_EXCLUDED_PATTERNS + the 3 additions
```

This is safer than replacing the entire set, because the default patterns
protect against secrets, build artifacts, and other dangerous file categories.
The `clearDefaultExcludedPatterns()` method exists for rare cases where the
defaults are not appropriate, but it should be used with extreme caution.

---

## Protected Path Patterns

`GitJobConfig.PROTECTED_PATH_PATTERNS` defines patterns for test and CI files
that receive special protection when `protectTestFiles` is enabled.

| Pattern | Matches |
|---------|---------|
| `**/src/test/**` | All files under any `src/test` directory (unit and integration test sources). |
| `**/src/it/**` | All files under any `src/it` directory (Maven integration test sources). |
| `.github/workflows/**` | GitHub Actions workflow definitions. |
| `.github/actions/**` | Custom GitHub Actions. |

### Protection Logic

When `protectTestFiles` is `true` and a file matches a protected path
pattern, one of two checks applies, chosen by `isCiWorkflowFile(file)` and
the file's extension:

**Whole-file** (`.github/workflows/**`, `.github/actions/**`, and any
non-`.java` protected file, e.g. a test resource):

1. If the file already exists at the merge-base of `origin/<baseBranch>` and
   `HEAD`, it is **blocked** from staging.
2. If it does not, it is **allowed** through (it is a branch-new file).

**Test-method-level** (any `.java` file under a protected path), delegated to
`TestMethodProtection`:

1. If the file did not exist at the merge-base, it is **allowed** through in
   full (branch-new file) — no method comparison is needed.
2. Otherwise, its `@Test` methods at the merge-base are compared against its
   current content via the shared
   `tools/ci/agent-protection/test-method-lines.awk` script. If any existing
   method's record (name + full annotated body) is missing from the current
   set — because it was edited, even by a pure addition, or removed
   entirely — the file is **blocked**. If every existing method's record is
   unchanged (whether or not new methods were added, and whatever else in
   the file changed), it is **allowed**.
3. If a file does not match any protected path pattern, this guardrail does
   not apply.

The merge-base existence check is answered from a one-time
`git ls-tree -r --name-only <mergeBase>` listing (`TestMethodProtection.
resolveMergeBaseFiles`), not a per-file `git cat-file -e <mergeBase>:<file>`
probe: for the `<rev>:<path>` object-name form, git's revision parser reports
the identical exit code and message whether a path is genuinely absent from
that tree or the lookup itself failed for an unrelated reason, so a per-file
exit-code probe cannot tell the two apart. Listing the tree once turns
"does file X exist at the merge-base" into a plain set-membership check, with
the listing's own success judged exactly once.

### Rationale

This guardrail exists to stop an agent sent to make failing tests pass from
modifying the existing tests or CI workflows instead. The correct response to
a test failure is to fix the production code, not to weaken the test; agents
on such jobs have repeatedly loosened the test, which fails
`test-integrity-check` and dispatches another agent to restore it, in a loop.
New test files, new test methods, and edits to fixtures/helpers/fields are all
allowed because agents may legitimately need to add or extend coverage — see
`TestMethodProtection` for the full reasoning, including why the lock is
scoped to test methods rather than whole test classes.

### Determining "New" vs "Existing" Files

The distinction between new and existing test files is determined by checking
whether the file is present in a listing of the merge-base commit's tree:

```
git merge-base origin/<baseBranch> HEAD
git ls-tree -r --name-only <mergeBase>
```

- Present in the listing: the file exists at the merge-base (it is an
  "existing" file and is protected — in full for non-`.java`/CI paths, at
  method granularity for `.java` test sources).
- Absent from the listing: the file does not exist at the merge-base (it is a
  "new" file and is allowed through).
- Listing unreadable (the `ls-tree` command itself fails): the file is treated
  as protected, the same as "present" — a listing failure must never be read
  as "every file is branch-new."

The merge-base, not the base branch's current tip, is what "existing" is
measured against: the base branch keeps moving after a feature branch forks
from it, and comparing against its live tip would misattribute the base
branch's own later edits to the agent's branch. `TestMethodProtection`
resolves the merge-base and its file listing once per `evaluateFiles()` call,
not once per file.

The fail-safe behavior is critical: if any step of the check throws (network
issues, missing refs, an unreadable merge-base listing, an unreadable file, a
missing or failing `test-method-lines.awk` subprocess), the file is treated
as protected in full. A warning is logged explaining why the check failed.
This prevents a
transient error from allowing modifications to protected test files.

---

## StagingResult Structure

**Source file:** `flowtree/runtime/src/main/java/io/flowtree/jobs/StagingResult.java`

`StagingResult` is a simple, immutable container holding two lists. Both lists
are stored as unmodifiable copies.

### Constructor

```java
public StagingResult(List<String> stagedFiles, List<String> skippedFiles)
```

| Parameter      | Type            | Description |
|----------------|-----------------|-------------|
| `stagedFiles`  | `List<String>`  | Files that passed all guardrails. Contains plain file paths. |
| `skippedFiles` | `List<String>`  | Files that failed a guardrail. Format: `"filename (reason)"`. |

### Getter Methods

```java
public List<String> getStagedFiles()    // Unmodifiable list of staged paths
public List<String> getSkippedFiles()   // Unmodifiable list of "path (reason)" strings
```

### Skip Reason Format

Skipped files include a parenthesized reason suffix:

| Reason String | Guardrail |
|---------------|-----------|
| `"(excluded pattern)"` | Pattern exclusion (guardrail 1) |
| `"(protected - exists on base branch)"` | Test file protection (guardrail 2) |
| `"(exceeds 1.0 MB)"` | File size limit (guardrail 3), with formatted threshold |
| `"(binary file)"` | Binary detection (guardrail 4) |

### `toString()`

Returns a summary string including counts and full file lists:

```
StagingResult{staged=3, skipped=2, stagedFiles=[...], skippedFiles=[...]}
```

### Immutability and Safety

Both lists are defensively copied in the constructor and wrapped with
`Collections.unmodifiableList()`. This means:

- Modifications to the original lists passed to the constructor do not affect
  the `StagingResult`.
- Attempting to modify the lists returned by `getStagedFiles()` or
  `getSkippedFiles()` throws `UnsupportedOperationException`.
- The `StagingResult` can be safely passed between threads or stored for later
  inspection.

### Interpreting Skip Reasons

The skip reason format is designed to be both machine-parsable and
human-readable. The parenthesized suffix follows the file path, separated by
a space. When processing skip reasons programmatically, split on the last
occurrence of `" ("` to extract the path and reason separately.

The `evaluateFiles()` method at the end of its execution logs a summary line:

```
Evaluated 15 files: 10 staged, 5 skipped
```

This provides a quick overview in the job logs without requiring inspection of
individual file decisions.

---

## Discarded Changes Are Reported, Not Silent

A guardrail skip used to be visible only after the fact, in the completion
event's `skippedFiles` list — easy to miss, and invisible to the agent
itself, whose session had already ended by the time `GitCommitHandler`
staged anything. Two mechanisms close that gap.

**Mid-session correction.** `StagingSkipRule` (an `EnforcementRule`) runs
`GitManagedJob.previewStaging()` — the same `FileStager` guardrails
`GitCommitHandler` will eventually apply, evaluated against the working
tree's current uncommitted changes without staging anything — during the
agent's enforcement retry loop, while the session can still react. When any
file would be skipped, the agent gets a correction turn naming the skipped
files and reasons, so it can find another approach (for a protected test
file: add a new method, or edit only one it introduced on the branch) or say
explicitly that a human needs to intervene. It is active for every
git-enabled job, not only those with `protectTestFiles` set, since any
guardrail — pattern exclusion, size, binary detection — can silently discard
a real fix.

**Non-success completion status.** `GitManagedJob.hasAllChangesDropped()`
returns `true` when the working tree had changes to stage but every one of
them was skipped, so nothing was staged or committed. `createEvent()` (and,
for Claude Code jobs, `CodingAgentJobEvent.forJob()`) checks this and returns
`Status.DEGRADED` instead of `Status.SUCCESS`, with an error message naming
the skipped files. This is what PR #492 was missing: a job whose only change
was silently dropped by guardrail 2 reported `SUCCESS`, so nothing in the
job's own status said the fix never landed. A partial drop (some files
staged, some skipped) still reports `SUCCESS`, but `skippedFiles` is part of
every completion event, so the job summary and status message always list
what was dropped, whether the run succeeded, was degraded, or failed.

**`ENFORCE_CHANGES` reads the same preview.** `EnforceChangesRule` used to
check `git status` on the raw working tree, which is fooled by a guardrail-
doomed change: the file sits there uncommitted (so `git status` is dirty)
and yet will never be staged. `EnforceChangesRule.isViolated()` now checks
`previewStaging().getStagedFiles().isEmpty()` instead, so "no real change yet"
is judged by what would actually survive staging, not by working-tree
dirtiness.

---

## Usage Examples

### Basic Evaluation with Default Configuration

```java
FileStager stager = new FileStager();

FileStagingConfig config = FileStagingConfig.builder()
    .excludedPatterns(GitJobConfig.DEFAULT_EXCLUDED_PATTERNS)
    .maxFileSizeBytes(GitJobConfig.DEFAULT_MAX_FILE_SIZE)
    .build();

List<String> changedFiles = Arrays.asList(
    "src/main/java/com/example/Foo.java",
    "target/classes/com/example/Foo.class",
    ".env",
    "docs/README.md"
);

GitOperations gitOps = new GitOperations("/path/to/repo", "task-1");
StagingResult result = stager.evaluateFiles(
    changedFiles, config,
    new File("/path/to/repo"),
    gitOps::execute
);

// result.getStagedFiles():  [src/main/java/com/example/Foo.java, docs/README.md]
// result.getSkippedFiles(): [target/classes/com/example/Foo.class (excluded pattern),
//                            .env (excluded pattern)]

for (String file : result.getStagedFiles()) {
    gitOps.execute("add", file);
}
```

### With Test File Protection

```java
FileStagingConfig config = FileStagingConfig.builder()
    .excludedPatterns(GitJobConfig.DEFAULT_EXCLUDED_PATTERNS)
    .protectedPathPatterns(GitJobConfig.PROTECTED_PATH_PATTERNS)
    .protectTestFiles(true)
    .baseBranch("master")
    .build();

List<String> changedFiles = Arrays.asList(
    "ml/src/main/java/Fix.java",            // Production code -- allowed
    "ml/src/test/java/ExistingTest.java",    // Existing method edited -- blocked
    "ml/src/test/java/ExistingTest.java",    // New method added to it -- allowed
    "ml/src/test/java/NewFeatureTest.java"   // New file (absent at merge-base) -- allowed
);

// The .java protected-path check needs BOTH execute() (exit codes) and
// executeWithOutput() (merge-base resolution, `git show` content) -- a bare
// exit-code lambda is not enough; see "Testing with a Mock GitOperations".
StagingResult result = stager.evaluateFiles(
    changedFiles, config,
    new File("/path/to/repo"),
    gitOps
);
```

### Custom Size Limit and Additional Patterns

```java
FileStagingConfig config = FileStagingConfig.builder()
    .excludedPatterns(GitJobConfig.DEFAULT_EXCLUDED_PATTERNS)
    .maxFileSizeBytes(5 * 1024 * 1024)  // 5 MB
    .build();
```

### Standalone Pattern Matching

The static methods on `FileStager` can be used independently for pattern
matching without evaluating files:

```java
// Check if a single file matches a pattern
boolean isExcluded = FileStager.matchesGlobPattern(
    "target/classes/Foo.class", "target/**");
// true

// Check if a file matches any pattern in a set
boolean shouldSkip = FileStager.matchesAnyPattern(
    "secrets/api-key.txt",
    GitJobConfig.DEFAULT_EXCLUDED_PATTERNS);
// true (matches **/secrets/**)

// Check if a file is binary
boolean binary = FileStager.isBinaryFile(new File("/path/to/image.dat"));
// true or false depending on content
```

### Integration with GitManagedJob

Within `GitManagedJob`, file staging is performed by `GitCommitHandler.stageFiles()`
which applies the same guardrails via `FileStager`. `GitManagedJob.asGitOperations()`
is the production bridge: it returns a `FileStager.GitOperations` adapter bound
to the job's own `executeGit`/`executeGitWithOutput`, implementing both interface
methods so content-based checks (merge-base resolution, `git show`) work, not
just exit-code ones:

```java
// Production usage (GitCommitHandler.stageFiles / GitManagedJob.previewStaging)
StagingResult result = stager.evaluateFiles(
    changedFiles, config,
    workDir,
    job.asGitOperations()
);
```

`FileStager.GitOperations` also has a `public` constructor-free concrete
implementation, `io.flowtree.jobs.GitOperations`, that wraps a real git
process:

```java
io.flowtree.jobs.GitOperations git =
    new io.flowtree.jobs.GitOperations("/repo", "task-1");
// git::execute alone only satisfies the exit-code half of the interface --
// wrap both methods to get content-based checks too:
FileStager.GitOperations gitOps = new FileStager.GitOperations() {
    @Override public int execute(String... args) throws IOException, InterruptedException {
        return git.execute(args);
    }
    @Override public String executeWithOutput(String... args) throws IOException, InterruptedException {
        return git.executeWithOutput(args);
    }
};
```

### Testing with a Mock GitOperations

`FileStager.GitOperations` has one abstract method (`execute`) and one
`default` method (`executeWithOutput`, which throws
`UnsupportedOperationException` unless overridden), so a bare exit-code
lambda still type-checks but silently loses content-based checks. Because
`evaluateFiles` resolves the merge-base and its file listing once per call
whenever `protectTestFiles` is set — before it knows whether any candidate
file is under a protected path — this is not limited to `.java` files: every
guardrail-2 file, whole-file paths included, will fail closed under such a
lambda. Only guardrails that never enable `protectTestFiles` at all (pattern
exclusion, size, binary detection) are unaffected:

```java
// Fine as long as protectTestFiles is false; with it true, every
// guardrail-2 file fails closed (merge-base cannot be resolved).
FileStager.GitOperations noOutputSupport = args -> 0;
```

For a test that exercises test file protection — whole-file or
method-level — implement both methods, matching how `TestMethodProtection`
actually resolves the merge-base and its file listing:

```java
FileStager.GitOperations gitOps = new FileStager.GitOperations() {
    @Override
    public int execute(String... args) {
        return 0;
    }

    @Override
    public String executeWithOutput(String... args) {
        if (args.length >= 1 && "merge-base".equals(args[0])) {
            return "abc1234def5678901234567890abcdef1234567";
        }
        if (args.length >= 1 && "ls-tree".equals(args[0])) {
            return "path/to/ExistingTest.java\n"; // files present at the merge-base
        }
        if (args.length >= 1 && "show".equals(args[0])) {
            return baseFileContent; // content at the merge-base
        }
        return "";
    }
};

StagingResult result = stager.evaluateFiles(
    changedFiles, config,
    tempDir.toFile(),
    gitOps
);
```

A merge-base listing failure is simulated by making `execute` return non-zero
for `ls-tree` (`executeOrNull` then returns `null` regardless of what
`executeWithOutput` would produce), which exercises the fail-closed path.

See `TestMethodProtectionTest` and `FileStagerTest` for the full set of
scenarios this pattern covers (branch-new file, added method, modified
method, deleted method, annotation added to an existing method, merge-base
unresolvable, merge-base listing unreadable, missing awk script).

This pattern makes it straightforward to unit test the guardrail logic
without starting git processes, creating repositories, or setting up remote
branches.
