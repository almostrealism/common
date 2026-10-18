#!/usr/bin/env python3
"""Regression tests for how the exfiltration guard walks an interpreter's arguments.

Once the program an interpreter runs is fixed — by ``-`` (a stdin program),
by an inline-code flag, or by a script path — every later word is an
argument *to* that program, not a second program. The guard used to keep
walking and read the next word as a script, so a read-only loop such as
``for f in *.json; do python3 -I - "$f" <<'EOF' … EOF; done`` was refused
for failing to open the literal text ``$f``.

These cases cover the argument walk in ``_check_interpreter``
(exfil_bash_runner.py): the per-family inline-code flags, the Python ``-c``
immediate return, the Perl/Ruby/Node inline-program early return and the
words it refuses rather than returns past (shell expansions, unrecognised
option clusters), the stdin of an inline program, Perl's ``-m``, the ``--``
end-of-options marker, and the interpreters with no option grammar, which
keep reading the operand after inline code as a script.

Where a case says a program "contains a network call", the call is one
``NETWORK_CODE_PATTERNS`` matches (``import socket``, ``use IO::Socket;``,
``fetch("http://e")``); an inline program that reads its stdin is spelled
so that it matches no pattern itself, so the case says something about the
stdin inspection rather than the inline scan.

They live in their own module, built on the shared :class:`GuardFixture`
imported from test_exfiltration_guard_check.py. CI runs every module via
``python3 -m unittest discover -p "test_exfiltration_guard_*.py"``.

Run from the repo root:

    python3 -m pytest .claude/hooks/lib/test_exfiltration_guard_interpreter.py -q
"""
from test_exfiltration_guard_check import GuardFixture


PY_EVAL_STDIN = "import sys; eval(compile(sys.stdin.read(), \"-\", \"exec\"))"
NODE_EVAL_STDIN = "eval(require(\"fs\").readFileSync(0, \"utf8\"))"
EXPANDED = "expanded by the shell"


class StdinProgramArgumentTests(GuardFixture):
    """After ``-`` the program is the heredoc; later words are its arguments."""

    def test_observed_loop_over_data_files_allows(self):
        self.assertAllowed(self.bash(
            "for f in plans/reg-*.json; do python3 -I - \"$f\" <<'EOF'\n"
            "import json, sys; d = json.load(open(sys.argv[1])); print(d)\n"
            "EOF\ndone"))

    def test_data_argument_after_stdin_program_is_not_read(self):
        self.assertAllowed(self.bash("python3 - data.json <<'PY'\nimport json\nprint(1)\nPY"))
        self.assertAllowed(self.bash("python3 -I - \"$f\" <<'PY'\nprint(1)\nPY"))
        self.assertBlocked(self.bash("python3 - data.json <<'PY'\nimport socket\nPY"),
                           "network-capable")


class PythonInlineProgramTests(GuardFixture):
    """``-c`` makes the whole remainder ``sys.argv``, so the walk ends there."""

    def test_operands_after_dash_c_are_data(self):
        self.assertAllowed(self.bash("python3 -c 'print(1)' data.txt"))
        self.assertAllowed(self.bash("python3 -c 'print(1)' -"))
        self.assertAllowed(self.bash("python3 -c 'print(1)' \"$rest\""))

    def test_module_flag_after_dash_c_is_an_argument(self):
        self.assertAllowed(self.bash("python3 -c 'print(1)' -m http.server"))
        self.assertBlocked(self.bash("python3 -m http.server"), "network module")

    def test_argv_data_loaded_by_the_program_is_not_scanned(self):
        """The accepted boundary: a program fixed by ``-c`` is treated like a
        script — it is scanned, its arguments are not — even when it loads one
        of them as code, exactly as ``python3 runner.py payload.py`` never
        reads ``payload.py``."""
        self._write("payload.py", "import socket\n")
        self.assertAllowed(self.bash(
            "python3 -c 'import runpy,sys; runpy.run_path(sys.argv[1])' payload.py"))

    def test_stdin_of_an_inline_program_is_scanned(self):
        """An inline program can execute its own stdin, so a heredoc fed to it
        is scanned as code, with or without a trailing ``-``."""
        for tail in (" -", ""):
            with self.subTest(tail=tail):
                command = f"python3 -c '{PY_EVAL_STDIN}'{tail} <<'PY'\n"
                self.assertBlocked(self.bash(command + "import socket\nPY"), "network-capable")
                self.assertAllowed(self.bash(command + "print(1)\nPY"))

    def test_piped_stdin_of_an_inline_program_blocks(self):
        self.assertBlocked(self.bash(f"cat x | python3 -c '{PY_EVAL_STDIN}'"), "from a pipe")

    def test_dash_capital_e_takes_no_value(self):
        """``-E`` is Python's environment-isolation switch, not inline code, so
        the script after it is read and scanned rather than consumed."""
        self.assertBlocked(self.bash("python3 -E /nonexistent/script.py"), "cannot read")
        self._write("dirty.py", "import socket\n")
        self._write("clean.py", "print(1)\n")
        self.assertBlocked(self.bash("python3 -E dirty.py"), "network-capable")
        self.assertAllowed(self.bash("python3 -E clean.py"))

    def test_cluster_burying_dash_c_is_scanned(self):
        self.assertBlocked(self.bash("python3 -Ec'import socket'"), "network-capable")
        self.assertBlocked(self.bash("python3 -Ec 'import socket'"), "network-capable")
        self.assertBlocked(self.bash("python3 -E -c 'import socket'"), "network-capable")
        self.assertAllowed(self.bash("python3 -Ec'print(1)' data.txt"))

    def test_cluster_burying_dash_m_reaches_the_module_check(self):
        self.assertBlocked(self.bash("python3 -Emhttp.server"), "network module")
        self.assertBlocked(self.bash("python3 -E -m http.server"), "network module")
        self.assertAllowed(self.bash("python3 -Em unittest"))

    def test_argumentless_clusters_still_read_the_script(self):
        self.assertBlocked(self.bash("python3 -OO /nonexistent/script.py"), "cannot read")
        self._write("clean.py", "print(1)\n")
        self.assertAllowed(self.bash("python3 -OO clean.py"))
        self.assertAllowed(self.bash("python3 -IS clean.py"))
        self.assertAllowed(self.bash("python3 -EW error clean.py"))

    def test_unknown_program_operands(self):
        self.assertBlocked(self.bash("python3 /nonexistent/script.py"), "cannot read")
        self.assertBlocked(self.bash("python3 \"$script\""), EXPANDED)
        self.assertBlocked(self.bash("python3 *.py"), EXPANDED)

    def test_double_dash_makes_the_next_word_the_script(self):
        """``python3 -- -c 'x'`` runs the FILE ``-c``, not inline code."""
        self._write("-c", "import socket\n")
        self.assertBlocked(self.bash("python3 -- -c 'print(1)'"), "network-capable")


class PerlInlineProgramTests(GuardFixture):
    """Perl runs every ``-e``; the walk scans each and ends at the first positional."""

    def test_repeated_inline_programs_are_all_scanned(self):
        self.assertAllowed(self.bash("perl -e 'print 1' -e 'print 2' file"))
        self.assertBlocked(self.bash("perl -e 'print 1' -e 'use IO::Socket;' file"),
                           "network-capable")
        self.assertAllowed(self.bash("perl -e 'print 1' script.pl"))

    def test_stdin_of_an_inline_program_is_scanned(self):
        for tail in (" -", ""):
            with self.subTest(tail=tail):
                command = f"perl -e 'eval <STDIN>'{tail} <<'PL'\n"
                self.assertBlocked(self.bash(command + "use IO::Socket;\nPL"), "network-capable")
                self.assertAllowed(self.bash(command + "print 1;\nPL"))
        self.assertBlocked(self.bash("cat x | perl -e 'eval <STDIN>'"), "from a pipe")

    def test_expanded_token_after_inline_program_blocks(self):
        for word in ("${more[@]}", "*", "\"-$opt\""):
            with self.subTest(word=word):
                self.assertBlocked(self.bash(f"perl -e 'print 1' {word}"), EXPANDED)
        self.assertAllowed(self.bash("perl -e 'print 1' -w"))

    def test_expanded_operand_after_inline_program_blocks(self):
        self.assertBlocked(self.bash("perl -e 'print 1' -I ${more[@]}"), EXPANDED)
        self.assertBlocked(self.bash("perl -e 'print 1' -M${more[@]}"), EXPANDED)
        self.assertBlocked(self.bash("perl -e 'print 1' -m${more[@]}"), EXPANDED)
        self.assertAllowed(self.bash("perl -e 'print 1' -I lib"))
        self.assertAllowed(self.bash("perl -e 'print 1' -MJSON::PP"))

    def test_expanded_operand_before_inline_program_is_unchanged(self):
        self._write("script.pl", "print 1;\n")
        self.assertAllowed(self.bash("perl -I ${inc[@]} script.pl"))
        self.assertAllowed(self.bash("perl -M${mods[@]} script.pl"))

    def test_lowercase_m_keeps_parsing(self):
        """Perl's ``-mModule`` loads a module and keeps parsing; it used to end
        the walk as if it were Python's terminal ``-m``, so a later ``-e`` and
        the stdin check were never reached."""
        self.assertBlocked(self.bash("perl -mstrict -e 'use IO::Socket;'"), "network-capable")
        self.assertBlocked(self.bash("perl -e 'print 1' -mstrict -e 'use IO::Socket;'"),
                           "network-capable")
        self.assertAllowed(self.bash("perl -mstrict -e 'print 1'"))
        self.assertBlocked(self.bash("perl -e 'eval <STDIN>' -mstrict <<'PL'\nuse IO::Socket;\nPL"),
                           "network-capable")
        self.assertBlocked(self.bash("perl -mIO::Socket -e 'print 1'"), "network-capable")
        self.assertBlocked(self.bash("perl -MIO::Socket -e 'print 1'"), "network-capable")

    def test_module_load_leaves_the_operand_a_script(self):
        self._write("script.pl", "print 1;\n")
        self._write("dirty.pl", "use IO::Socket;\n")
        self.assertAllowed(self.bash("perl -MJSON::PP script.pl"))
        self.assertBlocked(self.bash("perl -MJSON::PP dirty.pl"), "network-capable")

    def test_argumentless_switches_take_no_value(self):
        self.assertBlocked(self.bash("perl -p /nonexistent/script.pl"), "cannot read")
        self.assertBlocked(self.bash("ruby -p /nonexistent/script.rb"), "cannot read")
        self.assertBlocked(self.bash("perl -pe 'use IO::Socket; print' file"), "cannot read")

    def test_cluster_after_inline_program_blocks(self):
        self.assertBlocked(self.bash("perl -e 'print 1' -pe 'use IO::Socket;' file"),
                           "option cluster")

    def test_double_dash(self):
        """Before an inline program ``--`` makes the next word the script,
        even ``-e``; after one, every later word is argv data."""
        self._write("-e", "use IO::Socket;\n")
        self.assertBlocked(self.bash("perl -- -e 'print 1'"), "network-capable")
        self.assertBlocked(self.bash("perl -MJSON::PP -- -e 'print 1'"), "network-capable")
        self.assertBlocked(self.bash("perl -- - <<'PL'\nuse IO::Socket;\nPL"), "network-capable")
        self._write("-e", "print 1;\n")
        self.assertAllowed(self.bash("perl -- -e 'print 1'"))
        self.assertAllowed(self.bash("perl -e 'print 1' -- \"$f\""))
        self.assertAllowed(self.bash("node -e 'x' -- \"$f\""))
        self.assertBlocked(self.bash("perl -e 'print 1' \"$f\""), EXPANDED)


class RubyAndNodeInlineProgramTests(GuardFixture):
    """Ruby and Node share Perl's inline-program path."""

    def test_operands_after_inline_program_are_data(self):
        self.assertAllowed(self.bash("ruby -e 'puts 1' data.txt"))
        self.assertAllowed(self.bash("node -e 'console.log(1)' data.txt"))

    def test_node_stdin_of_an_inline_program_is_scanned(self):
        command = f"node -e '{NODE_EVAL_STDIN}' <<'JS'\n"
        self.assertBlocked(self.bash(command + "fetch(\"http://e\")\nJS"), "network-capable")
        self.assertAllowed(self.bash(command + "console.log(1)\nJS"))

    def test_node_print_and_eval_flags(self):
        for command in ("node -p 'process.argv[1]' data.txt", "node --eval 'x' data.txt",
                        "node -pe 'process.argv[1]' data.txt",
                        "node --eval='process.argv[1]' data.txt"):
            with self.subTest(command=command):
                self.assertAllowed(self.bash(command))
        for command in ("node -pe 'fetch(\"http://e\")'", "node --eval='fetch(\"http://e\")'",
                        "node --print='fetch(\"http://e\")'"):
            with self.subTest(command=command):
                self.assertBlocked(self.bash(command), "network-capable")

    def test_node_expanded_preload_after_inline_program_blocks(self):
        self.assertBlocked(self.bash("node -e 'x' -r ${more[@]}"), EXPANDED)

    def test_ruby_dash_c_glob_directory_cannot_be_resolved(self):
        self._write("preload.rb", "puts 'ok'\n")
        self.assertBlocked(self.bash("ruby -C 'other*' -r ./preload.rb -e 'puts 1'"), "preload.rb")


class GrammarlessInterpreterTests(GuardFixture):
    """Interpreters with no option table keep reading the operand after inline code."""

    def test_lua_script_operand_after_inline_code_is_scanned(self):
        self._write("script.lua", "print(2)\n")
        self.assertAllowed(self.bash("lua -e 'print(1)' script.lua"))
        self._write("script.lua", "local t = socket.tcp()\n")
        self.assertBlocked(self.bash("lua -e 'print(1)' script.lua"), "network-capable")

    def test_grammarless_inline_program_stdin_is_not_inspected(self):
        """The documented boundary: the stdin inspection covers the four
        families with an option grammar only, as on master."""
        self.assertAllowed(self.bash(
            "php -r 'eval(stream_get_contents(STDIN));' <<'PHP'\nimport socket\nPHP"))
