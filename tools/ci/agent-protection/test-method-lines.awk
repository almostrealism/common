# ─── Emit the test methods of a Java source file ────────────────────
#
# Two output modes, selected with -v mode=<lines|methods>:
#
#   lines   (default) prints the 1-based line number of every line that
#           is part of a test method: the @Test annotation block, the
#           signature, and the body through the closing brace.
#
#   methods prints one record per test method, "<name><TAB><body>",
#           where <body> is every line of the method joined by \001.
#           Comparing these records between two revisions of a file is
#           what distinguishes "a test method was changed or removed"
#           from "a test method was added", which are different acts
#           with different risk.
#
# Lines that belong to anything else in the file — fields, constructors,
# nested classes, and the private helpers that accumulate in test classes
# — are NOT reported in either mode. This is what lets an agent-commit
# check distinguish "the agent edited a test" from "the agent edited a
# method that happens to live in a test class".
#
# Brace counting ignores braces inside string and character literals and
# inside comments, since an unbalanced brace in a message string would
# otherwise shift every method boundary after it.
#
# Usage:  awk -f test-method-lines.awk SomeTest.java
#         awk -f test-method-lines.awk -v mode=methods SomeTest.java

# ── Reports the name of the method a signature declares ─────────────
#
# The signature is truncated at its last brace — which is the method's
# own opening brace, since any earlier brace belongs to an annotation
# argument such as @ValueSource(ints = {1, 2}) — and the last identifier
# that is followed by an opening parenthesis is the method name. Scanning
# for the LAST such identifier is what skips the annotations, whose own
# arguments (@Test(timeout = 5000)) look identical to a call otherwise.
function methodName(text,    last, rest, brace, i) {
    brace = 0
    for (i = length(text); i > 0; i--) {
        if (substr(text, i, 1) == "{") { brace = i; break }
    }
    if (brace > 0) text = substr(text, 1, brace - 1)

    last = ""
    rest = text
    while (match(rest, /[A-Za-z_$][A-Za-z0-9_$]*[ \t]*\(/)) {
        last = substr(rest, RSTART, RLENGTH)
        rest = substr(rest, RSTART + RLENGTH)
    }

    sub(/[ \t]*\($/, "", last)
    return last
}

# ── Records a completed method under its name ───────────────────────
#
# Methods are accumulated rather than assigned so that an overloaded or
# duplicated name still contributes every one of its bodies to the
# record, and a change to any of them is therefore still detected.
function flush() {
    if (buf == "") return
    body[name] = body[name] buf
    buf = ""
}

BEGIN { depth = 0; pending = 0; inTest = 0; inBlockComment = 0; SEP = "\001" }

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
    if (depth <= 1 && code ~ /@Test([ \t(]|$)/) { pending = 1; sig = ""; pend = "" }

    if (pending) { sig = sig " " code; pend = pend raw SEP }

    opens = gsub(/\{/, "{", code)
    closes = gsub(/\}/, "}", code)

    # Entering the body of an annotated method makes it, and everything up
    # to its closing brace, part of the test surface.
    if (depth == 1 && pending && opens > 0) {
        inTest = 1
        pending = 0
        name = methodName(sig)
        if (name == "") name = "method@" NR
        buf = pend
        pend = ""
    } else if (inTest) {
        buf = buf raw SEP
    }

    if (mode != "methods" && (inTest || pending)) print NR

    depth += opens - closes

    if (inTest && depth <= 1) {
        inTest = 0
        flush()
    }
}

END {
    if (mode == "methods") {
        flush()
        for (n in body) print n "\t" body[n]
    }
}
