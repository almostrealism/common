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
8. [Usage Examples](#usage-examples)

---

## FileStager Class Reference

**Source file:** `flowtree/src/main/java/io/flowtree/jobs/FileStager.java`

**Implements:** `ConsoleFeatures`

`FileStager` is a stateless utility class. It holds no mutable state between
calls, making it safe to share across threads and reuse across multiple
evaluations. A single instance can be used for the lifetime of the application
without concern for accumulated state or resource leaks.

### Inner Interface: `FileStager.GitOperations`

```java
public interface GitOperations {
    int execute(String... args) throws IOException, InterruptedException;
}
```

This functional interface abstracts over git command execution for the
base-branch existence check (guardrail 2). Callers provide their own
implementation -- typically a lambda or method reference wrapping the
concrete `GitOperations` class or `GitManagedJob`'s internal executor.

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

**Source file:** `flowtree/src/main/java/io/flowtree/jobs/FileStagingConfig.java`

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
any pattern in `config.getProtectedPathPatterns()` AND does the file exist on
the base branch?

**Effect:** If all three conditions are true, the file is blocked with reason
`"(protected - exists on base branch)"`.

**Applies to:** All files matching protected patterns that already exist on
the base branch. New test files (not present on the base branch) are allowed
through.

**Base branch check:** Uses `git cat-file -e origin/<baseBranch>:<file>` to
determine whether the file exists on the base branch. This is executed through
the `GitOperations` interface.

**Fail-safe behavior:** If the `git cat-file` command throws an exception
(e.g., the remote is unreachable), the method returns `true` (protected).
This prevents accidental modifications to test files when the check cannot
be performed.

**Rationale:** This guardrail prevents automated agents from hiding test
failures by modifying existing tests instead of fixing production code. New
test files are allowed because agents may legitimately need to create tests
for new functionality.

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
        if existsOnBaseBranch(file):
            skip("protected - exists on base branch")
            continue
        else:
            log("ALLOWED (branch-new file)")

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
| `.claude/**` | Claude Code project settings and session data. |
| `settings.local.json` | Local settings file (may contain user-specific configuration). |

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

When `protectTestFiles` is `true`:

1. If a file matches a protected path pattern AND already exists on the base
   branch, it is **blocked** from staging.
2. If a file matches a protected path pattern but does NOT exist on the base
   branch, it is **allowed** through (it is a new file).
3. If a file does not match any protected path pattern, this guardrail does
   not apply.

The base branch existence check uses `git cat-file -e origin/<baseBranch>:<file>`.
An exit code of `0` means the file exists on the base branch; any other exit
code means it does not.

### Rationale

This guardrail exists to prevent automated coding agents from modifying
existing tests or CI workflows to make failing tests pass. The correct
response to a test failure is to fix the production code, not to weaken the
test. New test files are allowed because agents may legitimately need to add
tests for new functionality.

### Determining "New" vs "Existing" Files

The distinction between new and existing test files is determined by checking
whether the file exists on the remote base branch using `git cat-file -e`:

```
git cat-file -e origin/<baseBranch>:<file>
```

- Exit code `0`: the file exists on the base branch (it is an "existing" file
  and is protected).
- Non-zero exit code: the file does not exist on the base branch (it is a
  "new" file and is allowed through).

This check runs against the remote ref (`origin/<baseBranch>`), not the local
ref. This means the check reflects the state of the base branch as of the last
`git fetch`, which is guaranteed to be recent because `prepareWorkingDirectory()`
fetches before any work begins.

The fail-safe behavior is critical: if `git cat-file` throws an exception (due
to network issues, missing refs, or other errors), `existsOnBaseBranch()`
returns `true`, which blocks the file. A warning is logged explaining why the
check failed. This prevents a transient error from allowing modifications to
protected test files.

---

## StagingResult Structure

**Source file:** `flowtree/src/main/java/io/flowtree/jobs/StagingResult.java`

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
    "ml/src/main/java/Fix.java",           // Production code -- allowed
    "ml/src/test/java/ExistingTest.java",   // Existing test -- blocked
    "ml/src/test/java/NewFeatureTest.java"  // New test (not on master) -- allowed
);

StagingResult result = stager.evaluateFiles(
    changedFiles, config,
    new File("/path/to/repo"),
    gitOps::execute
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

Within `GitManagedJob`, file staging is performed by the `stageFiles()` method
which applies the same guardrails inline. The extracted `FileStager` class
provides the same logic in a reusable, testable form. The `GitOperations`
interface parameter allows callers to bridge between `FileStager` and any
git command executor:

```java
// Using the concrete GitOperations class
io.flowtree.jobs.GitOperations git =
    new io.flowtree.jobs.GitOperations("/repo", "task-1");

StagingResult result = stager.evaluateFiles(
    changedFiles, config,
    new File("/repo"),
    git::execute    // Method reference satisfies the functional interface
);
```

### Testing with a Mock GitOperations

Because `FileStager.GitOperations` is a functional interface, you can use
a lambda for testing without requiring an actual git repository:

```java
// Mock that says all files exist on the base branch
FileStager.GitOperations allExist = args -> 0;

// Mock that says no files exist on the base branch
FileStager.GitOperations noneExist = args -> 1;

// Mock that selectively responds based on the file path
FileStager.GitOperations selective = args -> {
    String ref = args[2]; // "origin/master:path/to/file"
    if (ref.endsWith("ExistingTest.java")) return 0;
    return 1;
};

StagingResult result = stager.evaluateFiles(
    changedFiles, config,
    tempDir.toFile(),
    selective
);
```

This pattern makes it straightforward to unit test the guardrail logic
without starting git processes, creating repositories, or setting up remote
branches.
