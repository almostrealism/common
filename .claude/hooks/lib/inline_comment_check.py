#!/usr/bin/env python3
"""Decide whether a source file carries inline commentary a change is answerable for.

This module is the single source of truth for the "inline comments do not
replace javadoc" policy. Every gate asks it the same question about the same
thing: the file as it will exist, and which of its lines the change wrote.

    violations(path, text, added)   the policy itself; pure, no I/O
    decide_edit(path, old, new)     an edit, given the file before and after
    main(["--stdin"])               PreToolUse payload for Write/Edit/MultiEdit
    main(["--diff", FILE])          a unified diff (-U0) against any base; the
                                    files are read from the working tree
    main(["--all"])                 every file named on stdin, whole-file

What counts as inline commentary is decided by a lexer, not by how a line
starts: `//` comments, `/* */` blocks, and `/** */` blocks that document
nothing (inside a method body, or followed by no declaration). Strings, text
blocks and character literals are not comments; Java unicode escapes are
decoded first, because the compiler decodes them first. A documentation
comment on a declaration, and the header comment before a file's first line of
code, are the only exempt forms.

Four limits apply to what the lexer finds, each measured on the resulting
file, never on the fragment an edit happened to carry:

    run      comment-only lines in one group (blank lines do not split a group)
    line     characters in one comment
    volume   characters in one group, trailing comments included
    density  comment lines and characters in any window of consecutive lines,
             so alternating commentary with one-line statements is one finding

A finding is charged to a change when the change wrote any line of the group
it sits in, so extending or rewording an old wall is answerable for the whole
wall. There is no suppression marker: no comment can excuse a comment.

Exit 0 allows, exit 2 blocks with the reason on stderr. A payload this module
cannot understand is blocked, not allowed.
"""

import difflib
import json
import re
import sys

#: Comment-only lines permitted in one group.
MAX_RUN = 3

#: Characters of text permitted in a single comment.
MAX_LINE = 180

#: Characters of comment text permitted in one group.
MAX_VOLUME = 300

#: Consecutive lines the density limits are measured over.
WINDOW = 15

#: Comment-only lines permitted in one window.
MAX_WINDOW_LINES = 7

#: Characters of comment text permitted in one window.
MAX_WINDOW_VOLUME = 450

#: Files whose comments this policy governs: every C-family source language.
SOURCE_EXTENSIONS = (".java", ".pdsl", ".proto", ".kt", ".kts", ".c", ".h", ".hpp",
                     ".cpp", ".cc", ".cl", ".metal", ".m", ".mm", ".glsl", ".js",
                     ".cjs", ".mjs", ".jsx", ".ts", ".cts", ".mts", ".tsx",
                     ".groovy", ".gradle", ".scala", ".swift", ".go", ".rs", ".cs")

#: Languages whose backtick template literals hold ``${...}`` expressions,
#: which are code (and may hold comments) inside the literal text.
INTERPOLATING_EXTENSIONS = (".js", ".cjs", ".mjs", ".jsx", ".ts", ".cts", ".mts", ".tsx")

#: Languages with no documentation comment, where the comment above a
#: declaration is its documentation.
DECLARATION_COMMENT_EXTENSIONS = (".pdsl", ".proto")

#: Path fragments naming files nobody wrote by hand.
GENERATED = ("/target/", "/node_modules/", "/generated/", "/build/")

#: How many findings to quote back.
SAMPLE_LIMIT = 6

_JAVA_TYPE_WORD = re.compile(r"\b(class|interface|enum|record)\b")
_TYPE_WORD = re.compile(r"\b(class|interface|enum|record|struct|trait|impl|namespace|object)\b")
_PARENTHESISED = re.compile(r"\([^()]*\)")
_CONTROL = re.compile(r"^\s*(if|for|while|switch|catch|try|synchronized|do|else)\b")
_ANONYMOUS = re.compile(r"\bnew\s+[\w.$<>?,\s\[\]]+\(.*\)\s*$", re.S)
_ENUM_CONSTANT = re.compile(r"^\s*[A-Z][A-Z0-9_]*\s*(\(.*\))?\s*$", re.S)
_UNICODE_ESCAPE = re.compile(r"(?<!\\)((?:\\\\)*)\\u+([0-9a-fA-F]{4})")


def governs(path):
    """Return whether this policy applies to the file at the given path."""
    normal = "/" + path.replace("\\", "/")
    return path.endswith(SOURCE_EXTENSIONS) and not any(g in normal for g in GENERATED)


def _decode(path, text):
    """Return the text as the compiler reads it: Java unicode escapes decoded."""
    if not path.endswith(".java") or "\\u" not in text:
        return text

    return _UNICODE_ESCAPE.sub(lambda m: m.group(1) + chr(int(m.group(2), 16)), text)


class Line:
    """What one physical line of a source file holds."""

    def __init__(self):
        self.code = False
        self.filler = True
        self.lead = ""
        self.depth = 0
        self.pieces = []

    def inline(self):
        """Return the texts of the inline comments on this line."""
        return [text for kind, text in self.pieces if kind == "inline"]

    def substantive(self):
        """Return whether the line holds code that does something.

        A line holding nothing but an opening brace or semicolons is filler:
        it compiles to nothing, so it cannot stand between two comments as
        though it were the code they describe.
        """
        return self.code and not self.filler

    def comment_only(self):
        """Return whether the line is inline commentary and nothing else."""
        return not self.substantive() and bool(self.inline())

    def volume(self):
        """Return the characters of inline comment text on this line."""
        return sum(len(text) for text in self.inline())


def _brace_kind(statement, enclosing, java):
    """Classify the body an opening brace starts, from the text leading to it.

    A type keyword only counts outside parentheses, so a parameter that
    happens to be named ``record`` does not make a method body a type body.
    """
    lead = statement.strip()
    bare = lead
    while _PARENTHESISED.search(bare):
        bare = _PARENTHESISED.sub(" ", bare)

    if _CONTROL.match(lead):
        return "block"
    if (_JAVA_TYPE_WORD if java else _TYPE_WORD).search(bare):
        return "type"
    if _ANONYMOUS.search(lead):
        return "type"
    if enclosing == "type" and _ENUM_CONSTANT.match(lead) and lead:
        return "type"
    if not java and lead.endswith(("=", ":", ",", "(")):
        return "data"

    return "block"


def lex(path, text, header=True):
    """Split a source file into lines of code and comment pieces.

    Returns a list of :class:`Line`. A comment piece is ``("inline", text)``
    or ``("doc", text)``; a documentation comment that documents nothing is
    reported as inline. The header comment before the first code is dropped
    unless ``header`` is false, which is how a bare fragment is read.
    """
    text = _decode(path, text)
    lines = [Line()]
    stack = []
    statement = []
    docs = []
    templates = path.endswith(INTERPOLATING_EXTENSIONS + (".kt", ".kts", ".groovy",
                                                          ".go", ".swift", ".scala"))
    interpolates = path.endswith(INTERPOLATING_EXTENSIONS)
    holes = []
    i, n = 0, len(text)

    def newline():
        lines.append(Line())
        lines[-1].depth = len(stack)

    def enclosing():
        return stack[-1] if stack else "type"

    def template(start):
        """Consume template text from ``start``; return where lexing resumes.

        Lexing resumes after the closing backtick, or after the ``${`` that
        opens an interpolation, whose expression is then lexed as code until
        its matching ``}`` hands back to this function.
        """
        end = start
        while end < n and text[end] != "`":
            if text[end] == "\\":
                end += 1
            elif interpolates and text.startswith("${", end):
                lines[-1].code = True
                lines[-1].filler = False
                holes.append(0)
                return end + 2
            elif text[end] in "\r\n":
                if text[end] == "\r" and text[end:end + 2] == "\r\n":
                    end += 1
                lines[-1].code = True
                lines[-1].filler = False
                newline()
            end += 1
        lines[-1].code = True
        lines[-1].filler = False
        return end + 1

    while i < n:
        ch = text[i]
        two = text[i:i + 2]

        if ch == "\r" or ch == "\n":
            if ch == "\r" and two == "\r\n":
                i += 1
            newline()
            i += 1
            continue

        if two == "//":
            end = i
            while end < n and text[end] not in "\r\n":
                end += 1
            lines[-1].pieces.append(["inline", text[i + 2:end].strip()])
            i = end
            continue

        if two == "/*":
            end = text.find("*/", i + 2)
            end = n if end == -1 else end + 2
            body = text[i:end]
            is_doc = body.startswith("/**") and not body.startswith("/**/")
            where = enclosing()
            start_line = len(lines) - 1
            code_before = lines[-1].code
            for k, part in enumerate(re.split(r"\r\n|\r|\n", body)):
                if k:
                    newline()
                cleaned = part.strip()
                cleaned = re.sub(r"^/\*+|\*+/$", "", cleaned).strip()
                cleaned = re.sub(r"^\*+", "", cleaned).strip()
                lines[-1].pieces.append(["doc" if is_doc else "inline", cleaned])
            if is_doc:
                docs.append((start_line, len(lines) - 1, where, code_before))
            i = end
            continue

        if text.startswith('"""', i):
            end = text.find('"""', i + 3)
            end = n if end == -1 else end + 3
            for k, _ in enumerate(re.split(r"\r\n|\r|\n", text[i:end])):
                if k:
                    newline()
                lines[-1].code = True
                lines[-1].filler = False
            statement.append('""')
            i = end
            continue

        if ch == "`" and templates:
            statement.append('""')
            i = template(i + 1)
            continue

        if ch == "}" and holes and holes[-1] == 0:
            holes.pop()
            i = template(i + 1)
            continue

        if ch == '"' or ch == "'":
            end = i + 1
            while end < n and text[end] != ch:
                if text[end] == "\\":
                    end += 1
                elif text[end] in "\r\n":
                    break
                end += 1
            lines[-1].code = True
            lines[-1].filler = False
            statement.append('""')
            i = end + 1
            continue

        if not ch.isspace():
            if not lines[-1].code:
                lines[-1].lead = ch
            lines[-1].code = True
            if ch not in "{;":
                lines[-1].filler = False

        if holes and ch in "{}":
            holes[-1] += 1 if ch == "{" else -1

        if ch == "{":
            stack.append(_brace_kind("".join(statement), enclosing(), path.endswith(".java")))
            statement = []
        elif ch == "}":
            if stack:
                stack.pop()
            statement = []
        elif ch == ";":
            statement = []
        else:
            statement.append(ch)

        i += 1

    _demote_misplaced_docs(lines, docs)
    _promote_declaration_comments(path, lines)
    if header:
        _drop_header(lines)
    for line in lines:
        line.pieces = [tuple(piece) for piece in line.pieces]
    return lines


def _demote_misplaced_docs(lines, docs):
    """Turn a documentation comment that documents nothing into inline commentary.

    A documentation comment is real when it stands where a declaration can
    stand and the next thing after it is code that is not a closing brace:
    not inside a method body, not after code on its own line, not followed by
    another documentation comment, and not left dangling at the end of a body.
    """
    for start, end, where, code_before in docs:
        following = end if (lines[end].code and not code_before and start == end) else None
        if following is None and not lines[end].code:
            for k in range(end + 1, len(lines)):
                if lines[k].code:
                    following = k
                    break
                if any(kind == "doc" for kind, _ in lines[k].pieces):
                    break

        real = (following is not None and not code_before
                and where in ("type", "data")
                and (following == end or lines[following].lead != "}"))

        if not real:
            for k in range(start, end + 1):
                for piece in lines[k].pieces:
                    if piece[0] == "doc":
                        piece[0] = "inline"


def _promote_declaration_comments(path, lines):
    """Treat a comment above a declaration as documentation where no other form exists.

    PDSL and Protocol Buffers have no documentation comment, so the comment
    that stands directly above a declaration is its documentation and may be
    any length. In Protocol Buffers every line of code that is not a closing
    brace declares something; in PDSL the declarations are the top-level
    items, and the statements inside a body are not. Every other comment in
    these files (trailing, inside a PDSL body, or followed by no declaration)
    stays inline.
    """
    if not path.endswith(DECLARATION_COMMENT_EXTENSIONS):
        return

    top_level_only = path.endswith(".pdsl")

    for group in list(_groups(lines, Line.comment_only)):
        following = next((k for k in range(group[-1] + 1, len(lines)) if lines[k].code), None)
        if following is None or lines[following].lead == "}":
            continue
        if top_level_only and lines[following].depth:
            continue
        for k in group:
            if lines[k].code:
                continue
            for piece in lines[k].pieces:
                piece[0] = "doc"


def _drop_header(lines):
    """Remove the comment that precedes the first line of code in the file."""
    for line in lines:
        if line.code:
            return
        line.pieces = []


def _groups(lines, member):
    """Yield maximal runs of line indices satisfying ``member``.

    A blank line neither joins nor splits a run, so spacing commentary out
    does not turn one wall into several short comments.
    """
    current = []

    for index, line in enumerate(lines):
        if member(line):
            current.append(index)
        elif line.substantive() or line.pieces:
            if current:
                yield current
            current = []

    if current:
        yield current


def violations(path, text, added=None, header=True):
    """Return the findings a change is answerable for in one file.

    Args:
        path: the file's path, which selects the language rules.
        text: the file's full content as it will exist.
        added: 1-based numbers of the lines the change wrote, or None when
            the whole file is being judged.
        header: whether a comment before the first code is the file's
            header; false for a fragment that is not a whole file.

    Returns:
        A list of ``(line, reason)``, ``line`` being 1-based.
    """
    if not governs(path):
        return []

    lines = lex(path, text, header)
    wrote = None if added is None else {n - 1 for n in added}
    found = []

    def charged(indices):
        return wrote is None or any(k in wrote for k in indices)

    for group in _groups(lines, Line.comment_only):
        if len(group) > MAX_RUN and charged(group):
            found.append((group[0] + 1, "%d consecutive comment lines (max %d)"
                          % (len(group), MAX_RUN)))

    for index, line in enumerate(lines):
        for piece in line.inline():
            if len(piece) > MAX_LINE and charged([index]):
                found.append((index + 1, "a single comment of %d chars (max %d)"
                              % (len(piece), MAX_LINE)))

    for group in _groups(lines, lambda l: bool(l.inline())):
        volume = sum(lines[k].volume() for k in group)
        if volume > MAX_VOLUME and charged(group):
            found.append((group[0] + 1, "%d chars of comment text in one group (max %d)"
                          % (volume, MAX_VOLUME)))

    counted = [k for k, line in enumerate(lines) if line.substantive() or line.pieces]
    resume = 0
    for at, start in enumerate(counted):
        if at < resume or not lines[start].inline():
            continue
        window = counted[at:at + WINDOW]
        carrying = [k for k in window if lines[k].inline()]
        only = [k for k in window if lines[k].comment_only()]
        volume = sum(lines[k].volume() for k in carrying)
        if (len(only) > MAX_WINDOW_LINES or volume > MAX_WINDOW_VOLUME) and charged(carrying):
            found.append((start + 1, "%d comment lines and %d chars within %d lines of code "
                          "(max %d lines, %d chars)"
                          % (len(only), volume, WINDOW, MAX_WINDOW_LINES, MAX_WINDOW_VOLUME)))
            resume = at + WINDOW

    return sorted(set(found))


def added_lines(old, new):
    """Return the 1-based numbers of the lines of ``new`` that ``old`` lacks."""
    before = re.split(r"\r\n|\r|\n", old)
    after = re.split(r"\r\n|\r|\n", new)
    wrote = set()

    matcher = difflib.SequenceMatcher(None, before, after, autojunk=False)
    for tag, _, _, j1, j2 in matcher.get_opcodes():
        if tag in ("replace", "insert"):
            wrote.update(range(j1 + 1, j2 + 1))

    return wrote


def decide_edit(path, old, new):
    """Return the findings an edit taking a file from ``old`` to ``new`` owns."""
    return violations(path, new, added_lines(old, new) if old else None)


def block_reason(where, text, found):
    """Render the message shown when the policy is exceeded."""
    source = re.split(r"\r\n|\r|\n", text)
    shown = []
    for line, reason in found[:SAMPLE_LIMIT]:
        quoted = source[line - 1].strip() if 0 < line <= len(source) else ""
        shown.append("  %s:%d  %s\n      | %s" % (where, line, reason, quoted[:120]))

    return (
        "BLOCKED: LONG INLINE COMMENTS in " + where + "\n\n"
        + "\n".join(shown) + "\n\n"
        "WHY: design and rationale belong in method- or class-level JAVADOC, which is\n"
        "indexed and searchable. An inline comment is for a line or two of nuance, or to\n"
        "mark a step in a hard-to-follow sequence. Notes about what you were doing while\n"
        "you worked belong nowhere.\n\n"
        "This is measured on the FILE AS IT WILL EXIST, by a lexer: splitting the text\n"
        "over several edits, adding to a comment that is already there, switching to\n"
        "/* */ or to a /** */ inside a method, separating lines with blanks or empty\n"
        "statements, moving the text to the end of code lines, or writing the file from\n"
        "a shell command all reach the same finding. There is no suppression marker.\n"
        "The only fix is to RELOCATE the content:\n"
        "  - design or rationale            -> the method or class javadoc\n"
        "  - a note about your own work     -> delete it\n"
        "  - what remains inline            -> at most %d lines, %d chars\n"
        % (MAX_RUN, MAX_VOLUME)
    )


def _read(path):
    """Return a file's text, or the empty string when it does not exist.

    Only a missing file reads as empty: that is a new file an edit is creating,
    which has no prior text to judge. A file that exists but cannot be read (a
    permission or I/O error) is not judgeable, so the error propagates and the
    gate fails closed rather than treating an unreadable change as clean.
    """
    try:
        with open(path, encoding="utf-8", errors="replace", newline="") as handle:
            return handle.read()
    except FileNotFoundError:
        return ""


def resulting_text(old, tool_input):
    """Return the content a Write/Edit/MultiEdit payload leaves in the file.

    Returns ``(new, trusted)``. When an edit names text the file does not
    hold, the result cannot be reconstructed; the added text is then judged
    on its own, whole, which is the stricter reading.
    """
    for whole in ("content", "new_source"):
        if isinstance(tool_input.get(whole), str):
            return tool_input[whole], True

    edits = tool_input.get("edits")
    if not isinstance(edits, list):
        edits = [tool_input]

    new = old
    for edit in edits:
        if not isinstance(edit, dict) or not isinstance(edit.get("new_string"), str):
            raise ValueError("edit without new_string")
        target = edit.get("old_string") or ""
        if target and target in new:
            count = -1 if edit.get("replace_all") else 1
            new = new.replace(target, edit["new_string"], count)
        elif not target and not new:
            new = edit["new_string"]
        else:
            return "\n".join(e.get("new_string", "") for e in edits
                             if isinstance(e, dict)), False

    return new, True


def decide_payload(data, read=_read):
    """Return ``(path, text, findings)`` for a PreToolUse payload.

    A payload carrying ``move_from`` describes a file moved to ``file_path``
    as it is edited: the prior text is read from ``move_from`` and the result
    is judged under the destination path, where it will live.
    """
    tool_input = data.get("tool_input")
    if not isinstance(tool_input, dict):
        raise ValueError("payload without tool_input")

    path = (tool_input.get("file_path") or tool_input.get("filePath")
            or tool_input.get("notebook_path") or "")
    if not isinstance(path, str) or not governs(path):
        return path, "", []

    source = tool_input.get("move_from")
    old = read(source if isinstance(source, str) and source else path)
    new, trusted = resulting_text(old, tool_input)
    found = (decide_edit(path, old, new) if trusted
             else violations(path, new, header=False))
    return path, new, found


def diff_added(diff):
    """Return ``{path: added line numbers}`` from a unified diff."""
    wrote = {}
    path = None
    position = 0

    for raw in re.split(r"\r\n|\n", diff):
        if raw.startswith("+++ "):
            name = raw[4:].strip()
            path = None if name == "/dev/null" else re.sub(r"^b/", "", name)
            if path is not None:
                wrote.setdefault(path, set())
        elif raw.startswith("@@"):
            match = re.search(r"\+(\d+)", raw)
            position = int(match.group(1)) if match else 0
        elif path is not None and raw.startswith("+") and not raw.startswith("+++"):
            wrote[path].add(position)
            position += 1
        elif path is not None and raw.startswith(" "):
            position += 1

    return wrote


def decide_diff(diff, read=_read):
    """Return ``[(path, text, findings)]`` for the files a unified diff touches."""
    results = []
    for path, added in sorted(diff_added(diff).items()):
        if not governs(path) or not added:
            continue
        text = read(path)
        found = violations(path, text, added)
        if found:
            results.append((path, text, found))
    return results


def main(argv=None, stdin=None, stderr=None):
    """Run one of the gates; see the module documentation for the modes."""
    argv = list(sys.argv[1:] if argv is None else argv)
    stdin = sys.stdin if stdin is None else stdin
    stderr = sys.stderr if stderr is None else stderr

    try:
        if argv[:1] == ["--stdin"]:
            path, text, found = decide_payload(json.loads(stdin.read()))
            results = [(path, text, found)] if found else []
        elif argv[:1] == ["--diff"] and len(argv) == 2:
            results = decide_diff(_read(argv[1]) if argv[1] != "-" else stdin.read())
        elif argv[:1] == ["--all"]:
            results = []
            for path in stdin.read().split("\n"):
                if path and governs(path):
                    text = _read(path)
                    found = violations(path, text)
                    if found:
                        results.append((path, text, found))
        else:
            stderr.write("usage: inline_comment_check.py --stdin | --diff FILE | --all\n")
            return 2
    except Exception as failure:
        stderr.write("BLOCKED: the inline-comment check could not read this change (%s); "
                     "a change it cannot read is not allowed through.\n" % failure)
        return 2

    if not results:
        return 0

    if argv[:1] == ["--all"]:
        for path, _, found in results:
            for line, reason in found:
                stderr.write("%s:%d: %s\n" % (path, line, reason))
        return 2

    stderr.write("\n".join(block_reason(path, text, found) for path, text, found in results))
    return 2


if __name__ == "__main__":
    sys.exit(main())
