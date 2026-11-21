#!/usr/bin/env python3
"""Tests for the inline-comment policy: every known way round it is refused."""

import io
import json
import os
import tempfile
import unittest

import inline_comment_check as check

NOTE = "this explains at length what the code below is about to do"


def method(*body):
    """Return a Java class whose single method holds the given lines."""
    return "\n".join(["package p;", "", "/** A. */", "class A {", "\t/** M. */",
                      "\tvoid m() {"] + ["\t\t" + line for line in body]
                     + ["\t}", "}", ""])


def wall(count, text=NOTE):
    """Return ``count`` consecutive ``//`` lines."""
    return ["// %s %d" % (text, i) for i in range(count)]


class Base(unittest.TestCase):
    def blocked(self, text, path="A.java", added=None):
        found = check.violations(path, text, added)
        self.assertTrue(found, "expected a finding in:\n" + text)
        return found

    def allowed(self, text, path="A.java", added=None):
        self.assertEqual([], check.violations(path, text, added))

    def edit(self, old, tool_input, path="A.java"):
        tool_input = dict(tool_input, file_path=path)
        return check.decide_payload({"tool_input": tool_input}, read=lambda _: old)[2]


class TestWhatIsAllowed(Base):
    def test_a_three_line_comment_is_allowed(self):
        self.allowed(method(*wall(3, "short"), "int x = 1;"))

    def test_javadoc_of_any_length_on_a_declaration_is_allowed(self):
        doc = ["/**"] + [" * " + NOTE for _ in range(40)] + [" */"]
        text = "\n".join(["package p;"] + doc + ["class A {"]
                         + ["\t" + d for d in doc] + ["\tvoid m() { }", "}"])
        self.allowed(text)

    def test_the_licence_header_is_allowed(self):
        header = ["/*"] + [" * " + NOTE for _ in range(14)] + [" */"]
        self.allowed("\n".join(header + ["package p;", "class A { }"]))

    def test_comment_markers_inside_a_string_are_not_comments(self):
        lines = ['String s%d = "// %s";' % (i, NOTE) for i in range(8)]
        self.allowed(method(*lines))

    def test_a_text_block_holding_comment_markers_is_not_a_comment(self):
        self.allowed(method('String s = """', *wall(8), '""";'))

    def test_step_markers_between_real_code_are_allowed(self):
        body = []
        for i in range(6):
            body += ["// step %d" % i, "a%d();" % i, "b%d();" % i, "c%d();" % i]
        self.allowed(method(*body))


class TestShapesOfTheSameWall(Base):
    def test_four_line_comments_are_blocked(self):
        self.blocked(method(*wall(4), "int x = 1;"))

    def test_a_block_comment_is_blocked(self):
        self.blocked(method("/*", *[" * " + NOTE for _ in range(5)], " */", "int x;"))

    def test_a_block_comment_without_leading_stars_is_blocked(self):
        self.blocked(method("/*", *[NOTE for _ in range(5)], "*/", "int x;"))

    def test_stacked_single_line_block_comments_are_blocked(self):
        self.blocked(method(*["/* %s */" % NOTE for _ in range(5)], "int x;"))

    def test_a_javadoc_block_inside_a_method_body_is_blocked(self):
        self.blocked(method("/**", *[" * " + NOTE for _ in range(5)], " */", "int x = 1;"))

    def test_a_javadoc_block_on_a_local_variable_is_blocked(self):
        self.blocked(method("/**", *[" * " + NOTE for _ in range(5)], " */",
                            "final int x = 1;"))

    def test_a_dangling_javadoc_block_at_the_end_of_a_class_is_blocked(self):
        text = "\n".join(["class A {", "\tint x;", "\t/**"]
                         + ["\t * " + NOTE for _ in range(5)] + ["\t */", "}"])
        self.blocked(text)

    def test_a_javadoc_block_followed_by_another_is_blocked(self):
        doc = ["\t/**"] + ["\t * " + NOTE for _ in range(5)] + ["\t */"]
        self.blocked("\n".join(["class A {"] + doc + ["\t/** X. */", "\tint x;", "}"]))

    def test_a_javadoc_block_after_code_on_its_line_is_blocked(self):
        self.blocked(method("int x = 1; /** " + NOTE, *[" * " + NOTE for _ in range(4)],
                            " */", "int y = 2;"))

    def test_mixed_line_and_block_comments_are_one_wall(self):
        self.blocked(method("// " + NOTE, "/* " + NOTE + " */", "// " + NOTE,
                            "/* " + NOTE + " */", "int x;"))

    def test_one_very_long_line_is_blocked(self):
        self.blocked(method("// " + NOTE * 4, "int x;"))

    def test_one_very_long_trailing_comment_is_blocked(self):
        self.blocked(method("int x = 1; // " + NOTE * 4))

    def test_a_long_comment_embedded_in_an_expression_is_blocked(self):
        self.blocked(method("call(/* " + NOTE * 4 + " */ 1);"))

    def test_three_dense_lines_exceed_the_volume(self):
        self.blocked(method(*wall(3, NOTE * 2), "int x;"))


class TestSplittingTheWall(Base):
    def test_blank_lines_do_not_split_a_wall(self):
        self.blocked(method("// a " + NOTE, "// b " + NOTE, "", "// c " + NOTE, "",
                            "// d " + NOTE, "int x;"))

    def test_empty_comment_lines_do_not_split_a_wall(self):
        self.blocked(method("// a", "//", "// b", "//", "// c", "int x;"))

    def test_empty_statements_do_not_split_a_wall(self):
        body = []
        for i in range(9):
            body += ["// %s %d" % (NOTE, i), ";"]
        self.blocked(method(*body))

    def test_alternating_with_one_line_statements_is_blocked(self):
        body = []
        for i in range(9):
            body += ["// %s %d" % (NOTE, i), "int v%d = %d;" % (i, i)]
        self.blocked(method(*body))

    def test_moving_the_text_to_the_end_of_code_lines_is_blocked(self):
        self.blocked(method(*["int v%d = %d; // %s" % (i, i, NOTE) for i in range(8)]))

    def test_braces_as_separators_are_blocked(self):
        body = []
        for i in range(9):
            body += ["{", "// %s %d" % (NOTE, i), "}"]
        self.blocked(method(*body))


class TestLexicalTricks(Base):
    def test_unicode_escaped_comment_markers_are_comments(self):
        self.blocked(method(*["\\u002F\\u002F %s %d" % (NOTE, i) for i in range(5)], "int x;"))

    def test_carriage_return_line_endings_are_lines(self):
        self.blocked(method(*wall(5), "int x;").replace("\n", "\r"))

    def test_windows_line_endings_are_lines(self):
        self.blocked(method(*wall(5), "int x;").replace("\n", "\r\n"))

    def test_a_unicode_line_separator_does_not_hide_a_long_comment(self):
        self.blocked(method("// " + " ".join([NOTE] * 5), "int x;"))

    def test_a_comment_after_a_colon_is_still_a_comment(self):
        body = ["switch (k) {"] + ["case %d: // %s" % (i, NOTE) for i in range(8)] + ["}"]
        self.blocked(method(*body))

    def test_a_url_in_code_does_not_end_the_scan(self):
        self.blocked(method('String u = "http://example.invalid"; // ' + NOTE * 4))

    def test_a_quote_inside_a_comment_does_not_open_a_string(self):
        self.blocked(method(*["// don't \"%s %d" % (NOTE, i) for i in range(5)], "int x;"))

    def test_a_suppression_marker_buys_nothing(self):
        for marker in ("inline-comment-ok", "CHECKSTYLE:OFF", "noqa", "NOSONAR",
                       "temporary-identifier-ok: needed", "@formatter:off"):
            self.blocked(method("// " + marker, *wall(5), "int x;"))

    def test_a_parameter_named_record_does_not_make_a_type_body(self):
        text = "\n".join(["class A {", "\t/** M. */", "\tvoid log(LogRecord record) {",
                          "\t\t/**"] + ["\t\t * " + NOTE for _ in range(5)]
                         + ["\t\t */", "\t\tint x = 1;", "\t}", "}"])
        self.blocked(text)

    def test_javadoc_in_an_anonymous_class_is_real_but_its_methods_are_not(self):
        text = "\n".join([
            "class A {", "\t/** M. */", "\tRunnable m() {", "\t\treturn new Runnable() {",
            "\t\t\t/**"] + ["\t\t\t * " + NOTE for _ in range(6)] + [
            "\t\t\t */", "\t\t\tpublic void run() {"]
            + ["\t\t\t\t// %s %d" % (NOTE, i) for i in range(5)]
            + ["\t\t\t}", "\t\t};", "\t}", "}"])
        found = self.blocked(text)
        self.assertEqual(1, len([f for f in found if "consecutive" in f[1]]))


class TestOtherLanguages(Base):
    def test_every_c_family_source_extension_is_governed(self):
        body = "\n".join(["void f() {"] + wall(5) + ["}"])
        for ext in check.SOURCE_EXTENSIONS:
            self.blocked(body, path="src/f" + ext)

    def test_kernel_and_shader_sources_are_governed(self):
        for ext in (".cl", ".metal", ".glsl", ".mm"):
            self.assertTrue(check.governs("k" + ext))

    def test_a_typescript_function_body_is_checked(self):
        self.blocked("\n".join(["export function f() {"] + wall(5) + ["  return 1", "}"]),
                     path="a.ts")

    def test_a_template_literal_is_not_a_comment(self):
        self.allowed("\n".join(["const s = `"] + wall(8) + ["`"]), path="a.ts")

    def test_a_comment_inside_a_template_interpolation_is_checked(self):
        for ext in (".ts", ".js", ".cjs", ".tsx"):
            self.blocked("\n".join(["const s = `head ${f("] + wall(5) + [")} tail`"]),
                         path="a" + ext)

    def test_literal_text_after_an_interpolation_is_still_not_a_comment(self):
        text = "\n".join(["const s = `${a({b: 1})} then"] + wall(8) + ["${c} end`", "const t = 1"])
        self.allowed(text, path="a.ts")

    def test_a_template_nested_in_an_interpolation_is_lexed(self):
        inner = "\n".join(["const s = `x ${`y ${g("] + wall(5) + [")} z`} w`"])
        self.blocked(inner, path="a.ts")
        literal = "\n".join(["const s = `x ${`y"] + wall(8) + ["z`} w`"])
        self.allowed(literal, path="a.ts")

    def test_code_after_a_template_is_lexed_normally(self):
        text = "\n".join(["const s = `a ${b} c`", "function f() {"] + wall(5) + ["  return 1", "}"])
        self.blocked(text, path="a.ts")

    def test_a_dollar_brace_outside_javascript_does_not_open_an_interpolation(self):
        self.allowed("\n".join(["val s = `x ${"] + wall(8) + ["`"]), path="a.go")

    def test_generated_sources_are_not_governed(self):
        self.assertFalse(check.governs("a/target/generated-sources/A.java"))

    def test_a_line_continuation_in_a_template_keeps_line_numbers_aligned(self):
        text = "\n".join(["const s = `a\\", "b`", "function f() {"]
                         + wall(5) + ["  return 1", "}"])
        found = self.blocked(text, path="a.ts")
        self.assertEqual(4, found[0][0])


class TestWhatAChangeOwns(Base):
    def setUp(self):
        self.old = method(*wall(3, "old"), "int x = 1;")

    def test_an_untouched_old_wall_is_not_charged(self):
        text = method(*wall(6), "int x = 1;", "int y = 2;")
        self.allowed(text, added={text.split("\n").index("\t\tint y = 2;") + 1})

    def test_adding_one_line_to_an_existing_comment_is_charged(self):
        new = method(*wall(3, "old"), "// one more", "int x = 1;")
        self.assertTrue(check.decide_edit("A.java", self.old, new))

    def test_rewording_one_line_of_an_old_wall_is_charged(self):
        old = method(*wall(6), "int x = 1;")
        new = old.replace(NOTE + " 2", "reworded")
        self.assertTrue(check.decide_edit("A.java", old, new))

    def test_inserting_a_comment_above_an_existing_one_is_charged(self):
        new = method("// new a", "// new b", *wall(3, "old"), "int x = 1;")
        self.assertTrue(check.decide_edit("A.java", self.old, new))

    def test_deleting_the_code_between_two_comments_is_charged(self):
        old = method(*wall(2, "a"), "int x = 1;", *wall(2, "b"), "int y;")
        new = old.replace("\t\tint x = 1;\n", "\t\t// joined\n")
        self.assertTrue(check.decide_edit("A.java", old, new))

    def test_moving_an_old_wall_elsewhere_is_charged(self):
        code = ["int v%d = %d;" % (i, i) for i in range(10)]
        old = method(*wall(6), *code)
        new = method(*code, *wall(6))
        self.assertTrue(check.decide_edit("A.java", old, new))

    def test_pasting_an_old_wall_into_another_file_is_charged(self):
        self.assertTrue(check.decide_edit("B.java", method("int x;"),
                                          method(*wall(6), "int x;")))

    def test_reindenting_an_old_wall_is_charged(self):
        old = method(*wall(6), "int x = 1;")
        self.assertTrue(check.decide_edit("A.java", old, old.replace("\t\t//", "\t\t\t//")))


class TestTheEditGate(Base):
    def setUp(self):
        self.old = method(*wall(3, "old"), "int x = 1;")

    def test_an_edit_that_grows_a_comment_across_edits_is_blocked(self):
        found = self.edit(self.old, {"old_string": "\t\tint x = 1;",
                                     "new_string": "\t\t// one more\n\t\tint x = 1;"})
        self.assertTrue(found)

    def test_a_write_of_the_whole_file_is_blocked(self):
        self.assertTrue(self.edit("", {"content": method(*wall(5), "int x;")}))

    def test_a_multi_edit_whose_parts_are_each_small_is_blocked(self):
        edits = [{"old_string": "\t\tint x = 1;", "new_string": "\t\t// m1\n\t\tint x = 1;"},
                 {"old_string": "\t\tint x = 1;", "new_string": "\t\t// m2\n\t\tint x = 1;"}]
        self.assertTrue(self.edit(self.old, {"edits": edits}))

    def test_replace_all_is_applied_everywhere(self):
        old = method("// a", "int x = 1;", "// b", "int x = 1;")
        found = self.edit(old, {"old_string": "int x = 1;", "replace_all": True,
                                "new_string": "// p\n\t\t// q\n\t\t// r\n\t\tint x = 1;"})
        self.assertTrue(found)

    def test_an_edit_naming_text_the_file_lacks_is_judged_whole(self):
        found = self.edit(self.old, {"old_string": "not there",
                                     "new_string": "\n".join(wall(5))})
        self.assertTrue(found)

    def test_an_opencode_payload_is_read(self):
        found = check.decide_payload(
            {"tool_input": {"filePath": "A.java", "content": method(*wall(5), "int x;")}},
            read=lambda _: "")[2]
        self.assertTrue(found)

    def test_a_notebook_edit_path_is_read(self):
        found = check.decide_payload(
            {"tool_input": {"notebook_path": "A.java", "new_source": method(*wall(5), "int x;")}},
            read=lambda _: "")[2]
        self.assertTrue(found)

    def test_a_moved_file_is_read_from_its_source_and_judged_at_its_destination(self):
        reads = []

        def read(path):
            reads.append(path)
            return self.old

        found = check.decide_payload(
            {"tool_input": {"file_path": "B.java", "move_from": "A.txt",
                            "edits": [{"old_string": "\t\tint x = 1;",
                                       "new_string": "\t\t// one more\n\t\tint x = 1;"}]}},
            read=read)[2]
        self.assertEqual(["A.txt"], reads)
        self.assertTrue(found)

    def test_a_move_without_edits_adds_no_comment(self):
        found = check.decide_payload(
            {"tool_input": {"file_path": "B.java", "move_from": "A.java", "edits": []}},
            read=lambda _: method(*wall(6), "int x = 1;"))[2]
        self.assertEqual([], found)

    def test_an_unrelated_edit_to_a_file_with_an_old_wall_is_allowed(self):
        old = method(*wall(6), "int x = 1;")
        self.assertEqual([], self.edit(old, {"old_string": "int x = 1;",
                                             "new_string": "int x = 2;"}))

    def test_a_payload_that_cannot_be_read_is_blocked(self):
        for payload in ("not json", "{}", '{"tool_input": 3}',
                        '{"tool_input": {"file_path": "A.java", "edits": [3]}}'):
            err = io.StringIO()
            self.assertEqual(2, check.main(["--stdin"], io.StringIO(payload), err), payload)
            self.assertIn("BLOCKED", err.getvalue())

    def test_a_blocked_edit_exits_two_with_the_reason(self):
        payload = json.dumps({"tool_input": {"file_path": "/nonexistent/A.java",
                                             "content": method(*wall(5), "int x;")}})
        err = io.StringIO()
        self.assertEqual(2, check.main(["--stdin"], io.StringIO(payload), err))
        self.assertIn("LONG INLINE COMMENTS", err.getvalue())


class TestTheDiffGate(Base):
    def diff(self, path, first, count):
        plus = "\n".join("+x" for _ in range(count))
        return ("diff --git a/%s b/%s\n--- a/%s\n+++ b/%s\n@@ -%d,0 +%d,%d @@\n%s\n"
                % (path, path, path, path, first - 1, first, count, plus))

    def test_lines_a_shell_command_appended_to_a_comment_are_charged(self):
        text = method(*wall(3, "old"), "// by sed", "int x = 1;")
        line = text.split("\n").index("\t\t// by sed") + 1
        found = check.decide_diff(self.diff("A.java", line, 1), read=lambda _: text)
        self.assertEqual("A.java", found[0][0])

    def test_a_new_file_written_by_a_script_is_charged_in_full(self):
        text = method(*wall(5), "int x;")
        diff = self.diff("A.java", 1, len(text.split("\n")))
        self.assertTrue(check.decide_diff(diff, read=lambda _: text))

    def test_a_diff_that_leaves_an_old_wall_alone_passes(self):
        text = method(*wall(6), "int x = 1;", "int y = 2;")
        line = text.split("\n").index("\t\tint y = 2;") + 1
        self.assertEqual([], check.decide_diff(self.diff("A.java", line, 1),
                                               read=lambda _: text))

    def test_a_diff_with_context_lines_is_positioned_correctly(self):
        text = method(*wall(3, "old"), "// added", "int x = 1;")
        lines = text.split("\n")
        at = lines.index("\t\t// added")
        diff = ("--- a/A.java\n+++ b/A.java\n@@ -%d,2 +%d,3 @@\n %s\n+%s\n %s\n"
                % (at, at, lines[at - 1], lines[at], lines[at + 1]))
        self.assertTrue(check.decide_diff(diff, read=lambda _: text))

    def test_a_deleted_file_is_ignored(self):
        diff = "--- a/A.java\n+++ /dev/null\n@@ -1,3 +0,0 @@\n-a\n-b\n-c\n"
        self.assertEqual([], check.decide_diff(diff, read=lambda _: ""))


class TestReadingFailsClosed(Base):
    def test_a_missing_file_reads_as_empty(self):
        self.assertEqual("", check._read("/no/such/path/A.java"))

    def test_an_unreadable_file_is_not_swallowed(self):
        with self.assertRaises(OSError):
            check._read(".")

    def test_the_diff_gate_propagates_a_read_failure(self):
        def boom(_):
            raise PermissionError("denied")
        diff = "--- a/A.java\n+++ b/A.java\n@@ -0,0 +1,1 @@\n+// x\n"
        with self.assertRaises(PermissionError):
            check.decide_diff(diff, read=boom)

    def test_the_diff_gate_blocks_an_unreadable_file(self):
        with tempfile.TemporaryDirectory() as directory:
            unreadable = os.path.join(directory, "A.java")
            os.mkdir(unreadable)
            diff = ("--- a/%s\n+++ b/%s\n@@ -0,0 +1,1 @@\n+// x\n"
                    % (unreadable, unreadable))
            err = io.StringIO()
            self.assertEqual(2, check.main(["--diff", "-"], io.StringIO(diff), err))
            self.assertIn("BLOCKED", err.getvalue())


class TestDeclarationComments(Base):
    """PDSL and Protocol Buffers document a declaration with the comment above it."""

    LONG = ["// %s %d" % (NOTE, i) for i in range(12)]

    def test_a_long_comment_above_a_pdsl_layer_is_documentation(self):
        text = "\n".join(["import a"] + self.LONG + ["layer f(x: int) {", "    scale(x)", "}"])
        self.allowed(text, path="a.pdsl")

    def test_a_blank_line_before_the_pdsl_layer_keeps_it_documentation(self):
        text = "\n".join(["import a"] + self.LONG + ["", "layer f(x: int) {", "}"])
        self.allowed(text, path="a.pdsl")

    def test_a_long_comment_inside_a_pdsl_body_is_blocked(self):
        text = "\n".join(["layer f(x: int) {"] + ["    " + c for c in self.LONG]
                         + ["    scale(x)", "}"])
        self.blocked(text, path="a.pdsl")

    def test_a_long_comment_at_the_end_of_a_pdsl_body_is_blocked(self):
        text = "\n".join(["layer f(x: int) {", "    scale(x)"]
                         + ["    " + c for c in self.LONG] + ["}"])
        self.blocked(text, path="a.pdsl")

    def test_a_long_comment_after_the_last_pdsl_declaration_is_blocked(self):
        text = "\n".join(["layer f(x: int) {", "    scale(x)", "}"] + self.LONG)
        self.blocked(text, path="a.pdsl")

    def test_a_long_trailing_comment_in_pdsl_is_blocked(self):
        self.blocked("layer f(x: int) {\n    scale(x) // " + NOTE * 4 + "\n}", path="a.pdsl")

    def test_a_long_comment_above_a_proto_message_is_documentation(self):
        text = "\n".join(['syntax = "proto3";'] + self.LONG + ["message M {", "}"])
        self.allowed(text, path="a.proto")

    def test_a_long_comment_above_a_proto_field_is_documentation(self):
        text = "\n".join(['syntax = "proto3";', "message M {"]
                         + ["  " + c for c in self.LONG] + ["  int32 a = 1;", "}"])
        self.allowed(text, path="a.proto")

    def test_a_long_comment_before_a_closing_brace_in_proto_is_blocked(self):
        text = "\n".join(['syntax = "proto3";', "message M {", "  int32 a = 1;"]
                         + ["  " + c for c in self.LONG] + ["}"])
        self.blocked(text, path="a.proto")

    def test_a_long_trailing_comment_in_proto_is_blocked(self):
        self.blocked('syntax = "proto3";\nmessage M {\n  int32 a = 1; // '
                     + NOTE * 4 + "\n}", path="a.proto")

    def test_a_comment_above_a_statement_is_not_documentation_in_java(self):
        self.blocked(method(*self.LONG, "int x = 1;"))


class GovernsTest(unittest.TestCase):

    def test_source_files_are_governed(self):
        self.assertTrue(check.governs("src/Foo.java"))
        self.assertTrue(check.governs("a/b/Thing.ts"))
        for name in ("smoke.cjs", "m.mjs", "c.cts", "m.mts"):
            self.assertTrue(check.governs(name), name)

    def test_other_files_are_not(self):
        self.assertFalse(check.governs("README.md"))
        self.assertFalse(check.governs("script.py"))


class CommentTextTest(unittest.TestCase):

    def test_reads_the_comment(self):
        self.assertEqual(["hello"], check.lex("A.java", "int x; // hello", header=False)[0].inline())

    def test_a_url_is_not_a_comment(self):
        line = check.lex("A.java", ' String s = "http://example.com";')[0]
        self.assertEqual([], line.inline())

    def test_a_line_without_one_reads_as_none(self):
        self.assertEqual([], check.lex("A.java", "int x = 1;")[0].inline())


class MeasureTest(Base):

    def test_counts_a_run_of_full_line_comments(self):
        found = self.blocked(method(*["// line %d" % i for i in range(6)], "int x;"))
        self.assertIn("6 consecutive", found[0][1])

    def test_javadoc_is_exempt(self):
        text = "class A {\n/**\n * design rationale\n * more of it\n * and more\n * still\n */\nint x;\n}"
        self.assertEqual([], check.violations("A.java", text))
        self.assertEqual(0, sum(line.volume() for line in check.lex("A.java", text)))

    def test_a_trailing_comment_breaks_the_run(self):
        self.allowed(method("// one", "// two", "int x; // three", "// four", "int y;"))

    def test_total_accumulates_across_fragments(self):
        found = self.blocked(method("// " + "a" * 110, "// " + "b" * 110, "// " + "c" * 110,
                                    "int x;"))
        self.assertIn("in one group", found[0][1])


class DecideTest(Base):

    def test_a_short_comment_is_allowed(self):
        self.allowed(method("// brief nuance", "int x;"))

    def test_a_wall_of_comments_is_refused(self):
        found = self.blocked(method(*["// narrating what I did here"] * 8, "int x;"))
        self.assertIn("8 consecutive", " ".join(reason for _, reason in found))

    def test_one_very_long_comment_is_refused(self):
        found = self.blocked(method("// " + ("x" * 200), "int x;"))
        self.assertIn("single comment", found[0][1])

    def test_splitting_the_same_text_does_not_evade(self):
        """Breaking the text into two-line comments between statements changes nothing.

        Each run is two lines and no single comment is long; the density over
        consecutive lines gives it away.
        """
        body = ["// %s" % ("y" * 50), "// %s" % ("y" * 50), "int x;"] * 5
        found = self.blocked(method(*body))
        self.assertIn("within", found[0][1])

    def test_a_file_outside_the_policy_is_left_alone(self):
        text = "\n".join("// line %d" % i for i in range(20))
        self.assertEqual([], check.violations("notes.md", text))


class AddedTextTest(unittest.TestCase):

    def test_reads_every_edit_tool_shape(self):
        apply = check.resulting_text
        self.assertEqual(("w", True), apply("old", {"content": "w"}))
        self.assertEqual(("e", True), apply("", {"new_string": "e"}))
        self.assertEqual(("am", True), apply("ab", {"edits": [{"old_string": "b",
                                                               "new_string": "m"}]}))
        self.assertEqual(("n", True), apply("old", {"new_source": "n"}))


if __name__ == "__main__":
    unittest.main()
