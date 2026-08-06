# ─── Emit the line numbers that belong to a test method ─────────────
#
# Reads a Java source file and prints the 1-based line number of every
# line that is part of a test method: the @Test annotation block, the
# signature, and the body through the closing brace.
#
# Lines that belong to anything else in the file — fields, constructors,
# nested classes, and the private helpers that accumulate in test classes
# — are NOT printed. This is what lets an agent-commit check distinguish
# "the agent edited a test" from "the agent edited a method that happens
# to live in a test class", which are different acts with different risk.
#
# Brace counting ignores braces inside string and character literals and
# inside comments, since an unbalanced brace in a message string would
# otherwise shift every method boundary after it.
#
# Usage:  awk -f test-method-lines.awk <SomeTest.java>

BEGIN { depth = 0; pending = 0; inTest = 0; inBlockComment = 0 }

{
    raw = $0
    code = raw

    # Strip block comments (possibly spanning lines), line comments, and
    # literals, so their braces and annotations do not count as code.
    if (inBlockComment) {
        if (match(code, /\*\//)) {
            code = substr(code, RSTART + RLENGTH)
            inBlockComment = 0
        } else {
            code = ""
        }
    }

    gsub(/\\"/, "", code)
    gsub(/"[^"]*"/, "\"\"", code)
    gsub(/'[^']*'/, "''", code)
    sub(/\/\/.*/, "", code)

    while (match(code, /\/\*/)) {
        before = substr(code, 1, RSTART - 1)
        rest = substr(code, RSTART + RLENGTH)
        if (match(rest, /\*\//)) {
            code = before substr(rest, RSTART + RLENGTH)
        } else {
            code = before
            inBlockComment = 1
            break
        }
    }

    # A @Test annotation at class level opens an annotation block that runs
    # up to the method it decorates. @TestDepth and the rest travel with it.
    if (depth <= 1 && code ~ /@Test([ \t(]|$)/) pending = 1

    opens = gsub(/\{/, "{", code)
    closes = gsub(/\}/, "}", code)

    # Entering the body of an annotated method makes it, and everything up
    # to its closing brace, part of the test surface.
    if (depth == 1 && pending && opens > 0) {
        inTest = 1
        pending = 0
    }

    if (inTest || pending) print NR

    depth += opens - closes

    if (inTest && depth <= 1) inTest = 0
}
