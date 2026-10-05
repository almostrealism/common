#!/usr/bin/env python3
# Copyright 2026 Michael Murray
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""Behavioural tests for the shell helpers in ``tools/ci/macos/fleet.sh``.

These extract individual trust helpers (``untrusted_ancestor``,
``untrusted_search_path``, ``untrusted_program``, ``acl_write_grant``,
``read_env``, ``runner_dir_problems``, ``exposed_secret`` and friends) from
``fleet.sh`` and run them against real fixtures, so the predicates are
exercised for real on any host. The source-contract and CLI tests, which read
``fleet.sh`` as text, live beside this file in ``test_ci_macos_fleet_sh.py``.
"""

import grp
import os
import platform
import pwd
import re
import shutil
import subprocess
import tempfile
import unittest

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_MACOS_FLEET = os.path.join(_REPO_ROOT, "tools", "ci", "macos", "fleet.sh")


def setUpModule():
    """Refuse to run as root, where a guard that failed to fire could install."""
    if os.geteuid() == 0:
        raise unittest.SkipTest("fleet tests must not run as root")


def _trust_functions(*names):
    """The source of fleet.sh's path-trust helpers, to run outside the script
    (which refuses to run anywhere but macOS). ``acl_write_grant`` and the
    ``acl_write_rights`` and ``acl_grant`` it delegates to are always included
    because ``untrusted_path`` and ``untrusted_tool_dir`` call them."""
    with open(_MACOS_FLEET) as f:
        src = f.read()
    names = names or ("untrusted_path", "untrusted_ancestor")
    names = tuple(dict.fromkeys(names + ("acl_write_grant", "acl_write_rights", "acl_grant")))
    return "\n".join(
        re.search(r"^%s\(\) \{.*?^\}" % name, src, re.M | re.S).group(0)
        for name in names)


class UntrustedAncestorTests(unittest.TestCase):
    """Runs ``untrusted_ancestor`` itself against real directories. Accounts
    other than the test's own cannot be created here, so foreign ownership is
    covered only at the source level above; symlinks, write bits and the walk
    order are exercised for real.

    The walk starts at /, and the directories above a temporary directory are
    the host's, not the test's: /tmp is 1777, and macOS's /var is a symlink.
    So ``untrusted_path`` is wrapped to treat everything above the fixture root
    as trusted, and to apply the real predicate to everything inside it.
    """

    @classmethod
    def setUpClass(cls):
        functions = _trust_functions().replace("untrusted_path() {", "real_untrusted_path() {", 1)
        cls.functions = functions + '''
untrusted_path() {
    case "$2" in
        "${FIXTURE_ROOT}"|"${FIXTURE_ROOT}"/*) real_untrusted_path "$@" ;;
    esac
}'''
        cls.user = pwd.getpwuid(os.geteuid()).pw_name

    def _walk(self, path, owner=None):
        result = subprocess.run(
            ["bash", "-c", self.functions + '\nuntrusted_ancestor "$1" "$2"', "_", owner or self.user, path],
            capture_output=True, text=True, timeout=30,
            env=dict(os.environ, FIXTURE_ROOT=self.root))
        if result.returncode != 0:
            raise AssertionError(result.stderr)
        return result.stdout.strip()

    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="fleet-trust-")
        self.addCleanup(shutil.rmtree, self.root, True)
        self.dir = os.path.join(self.root, "ci")
        os.mkdir(self.dir, 0o755)
        os.chmod(self.dir, 0o755)
        self.file = os.path.join(self.dir, ".env")
        with open(self.file, "w") as f:
            f.write("GITHUB_OWNER=x\n")
        os.chmod(self.file, 0o600)

    def test_a_private_file_in_private_directories_is_trusted(self):
        self.assertEqual("", self._walk(self.file))

    def test_a_group_writable_file_is_reported(self):
        os.chmod(self.file, 0o620)
        self.assertEqual(self.file, self._walk(self.file))

    def test_a_world_writable_parent_is_reported_even_when_the_file_is_private(self):
        """The case the file-only check missed: whoever can write the
        directory can rename the file away and put their own in its place."""
        os.chmod(self.dir, 0o777)
        self.addCleanup(os.chmod, self.dir, 0o755)
        self.assertEqual(self.dir, self._walk(self.file))

    def test_the_first_untrusted_component_is_the_one_reported(self):
        os.chmod(self.dir, 0o775)
        self.addCleanup(os.chmod, self.dir, 0o755)
        os.chmod(self.file, 0o666)
        self.assertEqual(self.dir, self._walk(self.file))

    def test_a_symlinked_directory_on_the_path_is_reported(self):
        link = os.path.join(self.root, "link")
        os.symlink(self.dir, link)
        self.assertEqual(link, self._walk(os.path.join(link, ".env")))

    def test_a_symlinked_file_is_reported(self):
        link = os.path.join(self.dir, "linked.env")
        os.symlink(self.file, link)
        self.assertEqual(link, self._walk(link))

    def test_a_missing_tail_ends_the_walk_at_the_last_trusted_directory(self):
        """A RUNNER_DIR that install has not created yet is fine, so long as
        the directory it will be created in is trusted."""
        self.assertEqual("", self._walk(os.path.join(self.dir, "missing", "deeper")))

    def test_a_missing_tail_below_an_untrusted_directory_is_still_reported(self):
        os.chmod(self.dir, 0o757)
        self.addCleanup(os.chmod, self.dir, 0o755)
        self.assertEqual(self.dir, self._walk(os.path.join(self.dir, "missing")))

    def test_repeated_separators_are_ignored(self):
        self.assertEqual("", self._walk(self.dir + "//.env"))

    def test_a_probe_that_cannot_run_reports_rather_than_passes(self):
        """find refuses an unknown user, so its probe of the component exits
        non-zero with no output; that must read as untrusted, not trusted. An
        unknown owner makes `! -user "${owner}"` fail at the first fixture-root
        component, which the walk then reports instead of waving it through."""
        self.assertEqual(self.root, self._walk(self.file, owner="fleet-no-such-user"))


class UntrustedSearchPathTests(unittest.TestCase):
    """Runs ``untrusted_search_path`` against real directories. As in
    ``UntrustedAncestorTests``, everything above the fixture root is treated as
    trusted. ``ADMIN_GROUP`` is set to a group the fixture can be given, and
    ``admin_members`` is replaced so the test decides who the administrators
    are; the predicate itself runs unchanged.
    """

    @classmethod
    def setUpClass(cls):
        functions = _trust_functions("untrusted_ancestor", "untrusted_tool_dir", "untrusted_search_path",
                                     "untrusted_program")
        functions = functions.replace("untrusted_tool_dir() {", "real_untrusted_tool_dir() {", 1)
        cls.functions = functions + '''
untrusted_tool_dir() {
    case "$2" in
        "${FIXTURE_ROOT}"|"${FIXTURE_ROOT}"/*) real_untrusted_tool_dir "$@" ;;
    esac
}
admin_members() { echo "${FIXTURE_ADMINS}"; }'''
        cls.user = pwd.getpwuid(os.geteuid()).pw_name
        cls.group = grp.getgrgid(os.getegid()).gr_name
        cls.other_group = grp.getgrgid(0).gr_name

    def _check(self, search, owner=None, admins="", admin_group=None, function="untrusted_search_path"):
        result = subprocess.run(
            ["bash", "-c", "set -euo pipefail\n" + self.functions + '\n%s "$1" "$2"' % function,
             "_", owner or self.user, search],
            capture_output=True, text=True, timeout=30,
            env=dict(os.environ, FIXTURE_ROOT=self.root, FIXTURE_ADMINS=admins,
                     ADMIN_GROUP=admin_group or self.group))
        if result.returncode != 0:
            raise AssertionError(result.stderr)
        return result.stdout.splitlines()

    def setUp(self):
        # Resolved, so the walk of what an entry resolves to (macOS's /var is
        # a symlink to /private/var) stays inside the fixture.
        self.root = os.path.realpath(tempfile.mkdtemp(prefix="fleet-path-"))
        self.addCleanup(shutil.rmtree, self.root, True)
        self.bin = os.path.join(self.root, "bin")
        os.mkdir(self.bin)
        os.chmod(self.bin, 0o755)

    def test_directories_only_root_and_the_runner_can_write_are_trusted(self):
        self.assertEqual([], self._check(self.bin + ":" + os.path.join(self.root, "missing")))

    def test_a_world_writable_directory_is_reported(self):
        """The reviewer's case: a /tmp/bin-style entry another account can
        drop a fake java into."""
        os.chmod(self.bin, 0o777)
        self.assertEqual([self.bin], self._check(self.bin, admin_group=self.group))

    def test_group_write_is_allowed_for_the_admin_group_only(self):
        """Homebrew's bin is group-writable by admin; any other group's write
        bit lets a non-administrator change it."""
        os.chmod(self.bin, 0o775)
        self.assertEqual([], self._check(self.bin, admin_group=self.group))
        self.assertEqual([self.bin], self._check(self.bin, admin_group=self.other_group))

    def test_a_directory_owned_by_another_account_needs_it_to_be_an_administrator(self):
        """Owned by neither root nor the runner (the owner passed is root, so
        the fixture's account is a third one): trusted only when that account
        is in the admin group, as Homebrew's installing administrator is. The
        fixture root is that account's too, and is the first component the
        walk reaches."""
        self.assertEqual([self.root], self._check(self.bin, owner="root"))
        self.assertEqual([], self._check(self.bin, owner="root", admins="root " + self.user))

    def test_a_symlinked_entry_is_judged_by_what_it_resolves_to(self):
        """Homebrew's opt/<formula>/bin entries are symlinks into the Cellar;
        the link is acceptable, but a link into a writable directory is not."""
        target = os.path.join(self.root, "cellar")
        os.mkdir(target)
        os.chmod(target, 0o755)
        link = os.path.join(self.root, "opt")
        os.symlink(target, link)
        self.assertEqual([], self._check(link))
        os.chmod(target, 0o777)
        self.assertEqual([link], self._check(link))

    def test_a_writable_directory_deep_in_a_link_target_is_reported(self):
        """The entry is a link to cellar/jdk/bin; the link and its final
        target are both fine, but cellar can be written by others, so the walk
        of what the entry resolves to must catch it."""
        cellar = os.path.join(self.root, "cellar")
        target = os.path.join(cellar, "jdk", "bin")
        os.makedirs(target)
        for d in (target, os.path.dirname(target)):
            os.chmod(d, 0o755)
        os.chmod(cellar, 0o777)
        link = os.path.join(self.root, "opt")
        os.symlink(target, link)
        self.assertEqual([cellar], self._check(link))

    def test_relative_and_empty_entries_are_reported(self):
        """They are looked up from whatever directory a job is in."""
        self.assertEqual(["bin", "(empty entry)"], self._check("bin::" + self.bin))

    def test_every_untrusted_entry_is_reported(self):
        other = os.path.join(self.root, "other")
        os.mkdir(other)
        os.chmod(other, 0o757)
        os.chmod(self.bin, 0o777)
        self.assertEqual([self.bin, other], self._check(self.bin + ":" + other))

    def test_a_check_that_cannot_run_reports_rather_than_passes(self):
        """find refuses an unknown group; that must not read as trusted. The
        walk stops at the first component, the fixture root."""
        self.assertEqual([self.root], self._check(self.bin, admin_group="fleet-no-such-group"))

    def test_a_root_entry_does_not_abort_the_walk(self):
        """A "/" entry leaves the component array empty; under `set -u` that is
        an unbound-variable abort on bash 3.2 unless the walk guards it. The
        entry resolves to the root directory, which is root's, so it is not
        reported - what matters is that the screen does not die, which would
        otherwise wave the whole path through."""
        self.assertEqual([], self._check("/:" + self.bin))

    def test_a_trailing_separator_is_reported(self):
        """`read -a` drops a trailing empty field, so "bin:" would split to
        bin alone; that trailing entry is the current directory all the same."""
        self.assertEqual(["(empty entry)"], self._check(self.bin + ":"))
        self.assertEqual(["(empty entry)", "(empty entry)"], self._check(":" + self.bin + ":"))

    def test_an_empty_search_is_reported(self):
        self.assertEqual(["(empty entry)"], self._check(""))

    def _program(self, directory, name="java", mode=0o755):
        path = os.path.join(directory, name)
        with open(path, "w") as f:
            f.write("#!/bin/sh\n")
        os.chmod(path, mode)
        return path

    def _screen_program(self, path):
        return self._check(path, function="untrusted_program")

    def test_a_private_program_in_a_trusted_directory_is_trusted(self):
        self.assertEqual([], self._screen_program(self._program(self.bin)))

    def test_a_writable_program_in_a_trusted_directory_is_reported(self):
        """The directory screen passes bin, but the java in it can be
        rewritten by anyone."""
        java = self._program(self.bin, mode=0o777)
        self.assertEqual([], self._check(self.bin))
        self.assertEqual([java], self._screen_program(java))

    def test_a_program_linked_into_a_writable_directory_is_reported(self):
        cellar = os.path.join(self.root, "cellar")
        os.mkdir(cellar)
        os.chmod(cellar, 0o777)
        link = os.path.join(self.bin, "java")
        os.symlink(self._program(cellar), link)
        self.assertEqual([cellar], self._screen_program(link))

    def test_a_link_chain_through_a_writable_directory_is_reported(self):
        """bin/java -> opt/java -> cellar/java: the first link and the final
        program are both safe, but opt, which holds the middle link, can be
        written by others, who could re-point it."""
        cellar = os.path.join(self.root, "cellar")
        opt = os.path.join(self.root, "opt")
        for d in (cellar, opt):
            os.mkdir(d)
            os.chmod(d, 0o755)
        os.symlink(self._program(cellar), os.path.join(opt, "java"))
        link = os.path.join(self.bin, "java")
        os.symlink(os.path.join("..", "opt", "java"), link)
        self.assertEqual([], self._screen_program(link))
        os.chmod(opt, 0o777)
        self.assertEqual([opt], self._screen_program(link))

    def test_a_program_whose_links_cannot_be_followed_is_reported(self):
        loop = os.path.join(self.bin, "java")
        os.symlink(loop, loop)
        self.assertEqual([loop], self._screen_program(loop))
        dangling = os.path.join(self.bin, "mvn")
        os.symlink(os.path.join(self.root, "missing"), dangling)
        self.assertEqual([dangling], self._screen_program(dangling))

    @unittest.skipUnless(platform.system() == "Darwin", "needs macOS's own directories and admin group")
    def test_the_host_system_directories_pass_and_tmp_does_not(self):
        """The real predicate, with the real admin group, on the real host:
        the system part of the default RUNNER_PATH must pass, or install could
        never succeed, and /tmp (a symlink to the sticky, world-writable
        /private/tmp) must not."""
        functions = _trust_functions("untrusted_path", "untrusted_ancestor", "admin_members",
                                     "untrusted_tool_dir", "untrusted_search_path")
        result = subprocess.run(
            ["bash", "-c", "set -euo pipefail\nADMIN_GROUP=admin\n" + functions
             + '\nuntrusted_search_path "$1" "$2"',
             "_", self.user, "/usr/bin:/bin:/usr/sbin:/sbin:/tmp/fleet-no-such-bin"],
            capture_output=True, text=True, timeout=30)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["/tmp"], result.stdout.splitlines())

    @unittest.skipUnless(platform.system() == "Darwin", "needs macOS's own programs and admin group")
    def test_the_host_system_programs_pass(self):
        """The real program screen, with the real admin group, on the real
        host: the system tools the daemon runs must pass, or install could
        never succeed."""
        functions = _trust_functions("untrusted_path", "untrusted_ancestor", "admin_members",
                                     "untrusted_tool_dir", "untrusted_program")
        programs = ["/usr/bin/curl", "/usr/bin/git", "/usr/sbin/lsof"]
        # Homebrew's links into its Cellar, where the host has them.
        programs += [p for p in ("/opt/homebrew/bin/mvn", "/opt/homebrew/bin/jq") if os.path.exists(p)]
        for program in programs:
            with self.subTest(program=program):
                result = subprocess.run(
                    ["bash", "-c", "set -euo pipefail\nADMIN_GROUP=admin\n" + functions
                     + '\nuntrusted_program "$1" "$2"', "_", self.user, program],
                    capture_output=True, text=True, timeout=30)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual("", result.stdout)


class AclWriteGrantTests(unittest.TestCase):
    """Runs ``acl_write_grant`` against canned ``ls -lde`` output, so the macOS
    ACL parsing is exercised on any host. A shell function shadows ``ls`` to
    emit the listing a real macOS ``ls -lde`` would print for a fixture, which
    is the one part of the check a Linux host cannot produce for real.
    """

    @classmethod
    def setUpClass(cls):
        cls.functions = _trust_functions("acl_write_grant")

    def _grant(self, listing):
        result = subprocess.run(
            ["bash", "-c",
             "set -euo pipefail\nls() { printf '%s' \"${LS_OUTPUT}\"; }\n"
             + self.functions + '\nacl_write_grant /some/path'],
            capture_output=True, text=True, timeout=30,
            env=dict(os.environ, LS_OUTPUT=listing))
        self.assertEqual(0, result.returncode, result.stderr)
        return result.stdout.strip()

    _MODE_LINE = "drwxr-xr-x+ 3 someone staff 96 Jan  1 00:00 path\n"

    def test_no_acl_is_not_reported(self):
        self.assertEqual("", self._grant(
            "drwxr-xr-x  3 someone staff 96 Jan  1 00:00 path\n"))

    def test_an_allow_write_entry_is_reported(self):
        self.assertEqual("/some/path", self._grant(
            self._MODE_LINE + " 0: user:someone allow write,delete\n"))

    def test_an_allow_append_entry_is_reported(self):
        """append lets a subject add to the file; it is a write-granting right."""
        self.assertEqual("/some/path", self._grant(
            self._MODE_LINE + " 0: user:someone allow append\n"))

    def test_a_deny_entry_is_not_reported(self):
        """The 'everyone deny delete' macOS puts on a home directory must not
        read as a grant."""
        self.assertEqual("", self._grant(
            self._MODE_LINE + " 0: group:everyone deny delete\n"))

    def test_a_read_only_allow_entry_is_not_reported(self):
        self.assertEqual("", self._grant(
            self._MODE_LINE + " 0: user:someone allow read,readattr\n"))

    def test_an_inherited_allow_write_entry_is_reported(self):
        """macOS lists an entry inherited from the parent directory as
        "N: <who> inherited allow <rights>"; it grants the same rights."""
        self.assertEqual("/some/path", self._grant(
            self._MODE_LINE + " 0: user:someone inherited allow read,write\n"))
        self.assertEqual("", self._grant(
            self._MODE_LINE + " 0: group:everyone inherited deny delete\n"))


class AclWriteGrantTreeTests(unittest.TestCase):
    """Runs ``acl_write_grant_tree`` against a canned recursive ``ls -lde``
    listing, as ``AclWriteGrantTests`` does for one path: ``xargs`` is shadowed
    to print the listing a real macOS run would produce."""

    _LIST = "xargs() { cat >/dev/null; printf '%s' \"${LS_OUTPUT}\"; }\n"

    @classmethod
    def setUpClass(cls):
        cls.functions = _trust_functions("acl_write_grant_tree")

    def _scan(self, listing, prelude=_LIST):
        result = subprocess.run(
            ["bash", "-c", "set -euo pipefail\nfind() { printf '/r\\0'; }\n" + prelude
             + self.functions + '\nacl_write_grant_tree /r /r/_work'],
            capture_output=True, text=True, timeout=30,
            env=dict(os.environ, LS_OUTPUT=listing))
        self.assertEqual(0, result.returncode, result.stderr)
        return result.stdout.strip()

    _DIR = "drwxr-xr-x  4 worker staff 128 Jan  1 00:00 /r\n"
    _RUN = "-rwxr-xr-x+ 1 worker staff 2048 Jan  1 00:00 /r/run.sh\n"

    def test_a_tree_without_acls_is_not_reported(self):
        self.assertEqual("", self._scan(
            self._DIR + "-rwxr-xr-x  1 worker staff 2048 Jan  1 00:00 /r/run.sh\n"))

    def test_a_nested_file_with_an_allow_write_entry_is_reported(self):
        """The line reported is the file's own, not the directory's above it."""
        self.assertEqual(self._RUN.strip(), self._scan(
            self._DIR + self._RUN + " 0: user:intruder allow write\n"))

    def test_an_inherited_write_entry_is_reported(self):
        self.assertEqual(self._RUN.strip(), self._scan(
            self._DIR + self._RUN + " 0: user:intruder inherited allow append\n"))

    def test_deny_and_read_only_entries_are_not_reported(self):
        self.assertEqual("", self._scan(
            self._DIR + self._RUN + " 0: group:everyone deny delete\n"
            + " 1: user:reader allow read,readattr\n"))

    def test_only_the_first_offender_is_reported(self):
        other = "-rw-r--r--+ 1 worker staff 10 Jan  1 00:00 /r/config.sh\n"
        self.assertEqual(self._RUN.strip(), self._scan(
            self._DIR + self._RUN + " 0: user:a allow write\n"
            + other + " 0: user:b allow write\n"))

    def test_a_listing_that_cannot_be_made_reports_the_tree(self):
        self.assertEqual("/r", self._scan("", prelude="xargs() { cat >/dev/null; return 1; }\n"))

    @unittest.skipUnless(platform.system() == "Darwin", "needs macOS ACLs")
    def test_real_acls_on_a_macos_tree(self):
        """The real find/xargs/ls -lde pipeline, on real macOS ACLs: a read-only
        entry and a write entry under the pruned _work pass; a write entry on a
        directory inside the tree is reported."""
        root = tempfile.mkdtemp(prefix="fleet-acl-")
        self.addCleanup(shutil.rmtree, root, True)
        tree = os.path.join(root, "r")
        os.makedirs(os.path.join(tree, "_work"))
        os.makedirs(os.path.join(tree, "bin"))
        for name in ("run.sh", os.path.join("_work", "job.sh")):
            with open(os.path.join(tree, name), "w") as f:
                f.write("x\n")

        def scan():
            result = subprocess.run(
                ["bash", "-c", "set -euo pipefail\n" + self.functions
                 + '\nacl_write_grant_tree "$1" "$1/_work"', "_", tree],
                capture_output=True, text=True, timeout=30)
            self.assertEqual(0, result.returncode, result.stderr)
            return result.stdout.strip()

        def add_acl(entry, path):
            subprocess.run(["chmod", "+a", entry, os.path.join(tree, path)],
                           check=True, capture_output=True, timeout=30)

        self.assertEqual("", scan())
        add_acl("everyone allow read", "run.sh")
        self.assertEqual("", scan())
        add_acl("everyone allow write,append", os.path.join("_work", "job.sh"))
        self.assertEqual("", scan(), "_work is pruned")
        add_acl("everyone allow add_file", "bin")
        reported = scan()
        self.assertTrue(reported.endswith(os.path.join(tree, "bin")), reported)


class UntrustedToolTreeTests(unittest.TestCase):
    """Runs ``untrusted_tool_tree`` against a real fixture tree. It is
    ``untrusted_tool_dir`` applied to every entry in a tree at once, so the
    monitor install's Python tree (``tools/fleet``) can be screened in one call.
    ``admin_members`` is replaced so the test decides who the administrators
    are, and ``acl_write_grant_tree`` — which has its own tests and whose real
    ``ls -lde`` pipeline needs macOS — is stubbed to empty; the mode and
    ownership scan that is this helper's own logic runs for real on any host.
    """

    @classmethod
    def setUpClass(cls):
        cls.functions = (_trust_functions("untrusted_tool_tree")
                         + '\nadmin_members() { echo "${FIXTURE_ADMINS}"; }'
                         + '\nacl_write_grant_tree() { :; }')
        cls.user = pwd.getpwuid(os.geteuid()).pw_name
        cls.group = grp.getgrgid(os.getegid()).gr_name
        cls.other_group = grp.getgrgid(0).gr_name

    def _scan(self, admins="", admin_group=None, find_fails=False):
        prelude = "find() { return 1; }\n" if find_fails else ""
        result = subprocess.run(
            ["bash", "-c", "set -euo pipefail\n" + prelude + self.functions
             + '\nuntrusted_tool_tree "$1" "$2"', "_", self.user, self.root],
            capture_output=True, text=True, timeout=30,
            env=dict(os.environ, FIXTURE_ADMINS=admins,
                     ADMIN_GROUP=admin_group or self.group))
        if result.returncode != 0:
            raise AssertionError(result.stderr)
        return result.stdout.strip()

    def setUp(self):
        self.root = os.path.realpath(tempfile.mkdtemp(prefix="fleet-tree-"))
        self.addCleanup(shutil.rmtree, self.root, True)
        os.chmod(self.root, 0o755)
        self.sub = os.path.join(self.root, "launchd")
        os.mkdir(self.sub)
        os.chmod(self.sub, 0o755)
        self.module = os.path.join(self.root, "collector.py")
        with open(self.module, "w") as f:
            f.write("x = 1\n")
        os.chmod(self.module, 0o644)

    def test_a_tree_only_root_the_owner_and_admins_can_change_is_trusted(self):
        self.assertEqual("", self._scan())

    def test_a_world_writable_module_is_reported(self):
        """A module a CI job could rewrite would run as the administrator when
        the monitor install imports it."""
        os.chmod(self.module, 0o666)
        self.assertEqual(self.module, self._scan())

    def test_a_world_writable_subdirectory_is_reported(self):
        """A writable directory in the tree is enough: another account could
        drop a module, or a link to one, into it."""
        os.chmod(self.sub, 0o777)
        self.addCleanup(os.chmod, self.sub, 0o755)
        self.assertEqual(self.sub, self._scan())

    def test_group_write_is_allowed_for_the_admin_group_only(self):
        """A file group-writable by the administrators' group is fine (they can
        already become root); any other group's write bit is not."""
        os.chmod(self.module, 0o664)
        self.assertEqual("", self._scan(admin_group=self.group))
        self.assertEqual(self.module, self._scan(admin_group=self.other_group))

    def test_a_symlinked_entry_is_reported(self):
        """A symlink is rejected rather than skipped: Python import follows it
        and runs whatever it resolves to, so a pre-existing admin-owned link
        whose target a CI job can write would execute that job's code as the
        administrator even though the link and its directory are trusted."""
        link = os.path.join(self.root, "shortcut.py")
        os.symlink("/tmp/anywhere.py", link)
        self.assertEqual(link, self._scan())

    def test_a_scan_that_cannot_run_reports_the_tree(self):
        """A find that cannot run must read as untrusted, never pass."""
        self.assertEqual(self.root, self._scan(find_fails=True))


class ReadEnvTests(unittest.TestCase):
    """Runs ``read_env`` against real env files: it sources them in a clean
    shell and reports, besides the values install uses, any PATH the file
    assigns, which runner.sh would otherwise let replace the screened
    RUNNER_PATH."""

    @classmethod
    def setUpClass(cls):
        cls.functions = _trust_functions("read_env")

    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="fleet-env-")
        self.addCleanup(shutil.rmtree, self.root)
        self.env = os.path.join(self.root, ".env")

    def _read(self, text, variable):
        with open(self.env, "w") as f:
            f.write(text)
        result = subprocess.run(
            ["bash", "-c", "set -euo pipefail\n" + self.functions
             + '\nread_env "$1"\nprintf "%s" "${!2}"', "_", self.env, variable],
            capture_output=True, text=True, timeout=30,
            env=dict(os.environ, RUNNER_HOME=self.root))
        self.assertEqual(0, result.returncode, result.stderr)
        return result.stdout

    def test_an_env_file_without_path_reports_no_override(self):
        self.assertEqual("", self._read("GITHUB_OWNER=acme\n", "ENV_PATH_OVERRIDE"))
        self.assertEqual("acme", self._read("GITHUB_OWNER=acme\n", "ENV_GITHUB_OWNER"))

    def test_an_env_file_that_sets_path_reports_it(self):
        self.assertEqual("/tmp/evil:/usr/bin",
                         self._read("PATH=/tmp/evil:/usr/bin\n", "ENV_PATH_OVERRIDE"))

    def test_an_env_file_that_extends_path_reports_it(self):
        """Prepending to the inherited PATH is an override all the same."""
        self.assertEqual("/tmp/evil:/usr/bin:/bin",
                         self._read('PATH="/tmp/evil:${PATH}"\n', "ENV_PATH_OVERRIDE"))

    def test_runner_path_is_not_mistaken_for_path(self):
        self.assertEqual("", self._read("RUNNER_PATH=/opt/homebrew/bin:/usr/bin\n",
                                        "ENV_PATH_OVERRIDE"))
        self.assertEqual("/opt/homebrew/bin:/usr/bin",
                         self._read("RUNNER_PATH=/opt/homebrew/bin:/usr/bin\n", "ENV_RUNNER_PATH"))


class RunnerDirProblemsTests(unittest.TestCase):
    """Runs ``runner_dir_problems`` against real directories, as the test's own
    account standing in for the runner. ``sudo`` is shadowed to run the command
    as that account, and, as in ``UntrustedAncestorTests``, everything above the
    fixture root is treated as trusted."""

    @classmethod
    def setUpClass(cls):
        functions = _trust_functions(
            "untrusted_path", "untrusted_ancestor", "nearest_existing_dir", "runner_dir_problems"
        ).replace("untrusted_path() {", "real_untrusted_path() {", 1)
        cls.functions = '''sudo() {
    if [ "$1" = "-u" ]; then shift 2; fi
    "$@"
}
''' + functions + '''
untrusted_path() {
    case "$2" in
        "${FIXTURE_ROOT}"|"${FIXTURE_ROOT}"/*) real_untrusted_path "$@" ;;
    esac
}'''
        cls.user = pwd.getpwuid(os.geteuid()).pw_name

    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="fleet-stage-")
        self.addCleanup(self._cleanup)
        os.chmod(self.root, 0o755)
        self.home = os.path.join(self.root, "home")
        os.mkdir(self.home, 0o755)
        os.chmod(self.home, 0o755)

    def _cleanup(self):
        os.chmod(self.home, 0o755)
        shutil.rmtree(self.root, True)

    def _problems(self, path):
        result = subprocess.run(
            ["bash", "-c", "set -euo pipefail\n" + self.functions
             + '\nrunner_dir_problems "the stage directory" "$1"', "_", path],
            capture_output=True, text=True, timeout=30,
            env=dict(os.environ, FIXTURE_ROOT=self.root, RUNNER_USER=self.user))
        self.assertEqual(0, result.returncode, result.stderr)
        return result.stdout.strip()

    def test_a_missing_directory_under_a_trusted_writable_home_passes(self):
        self.assertEqual("", self._problems(os.path.join(self.home, "ci-runner")))

    def test_an_existing_trusted_directory_passes(self):
        stage = os.path.join(self.home, "ci-runner")
        os.mkdir(stage, 0o755)
        os.chmod(stage, 0o755)
        self.assertEqual("", self._problems(stage))

    def test_a_relative_path_is_refused(self):
        self.assertIn("must be an absolute path", self._problems("ci-runner"))

    def test_a_symlinked_stage_directory_is_reported(self):
        target = os.path.join(self.root, "elsewhere")
        os.mkdir(target, 0o755)
        stage = os.path.join(self.home, "ci-runner")
        os.symlink(target, stage)
        problems = self._problems(stage)
        self.assertTrue(problems.startswith(stage + ", on the path to the stage directory"), problems)

    def test_a_writable_ancestor_is_reported(self):
        os.chmod(self.home, 0o777)
        problems = self._problems(os.path.join(self.home, "ci-runner"))
        self.assertTrue(problems.startswith(self.home + ", on the path to"), problems)

    def test_an_existing_non_directory_is_reported(self):
        stage = os.path.join(self.home, "ci-runner")
        with open(stage, "w") as f:
            f.write("")
        os.chmod(stage, 0o644)
        self.assertIn("exists but is not a directory", self._problems(stage))

    def test_a_home_the_runner_cannot_create_in_is_reported(self):
        """A trusted but read-only home passes the walk, yet mkdir -p of the
        stage directory would fail after preflight."""
        os.chmod(self.home, 0o555)
        problems = self._problems(os.path.join(self.home, "ci-runner"))
        self.assertIn("cannot create the stage directory", problems)
        self.assertIn(self.home + ", the nearest existing directory", problems)


class ExposedSecretTests(unittest.TestCase):
    """Runs ``exposed_secret`` against real files owned by the test's account.
    Foreign ownership cannot be produced here; mode bits, symlinks and (through
    a shadowed ``ls``) a macOS read-granting ACL are exercised for real."""

    @classmethod
    def setUpClass(cls):
        cls.functions = _trust_functions("exposed_secret", "untrusted_path")
        cls.user = pwd.getpwuid(os.geteuid()).pw_name

    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="fleet-secret-")
        self.addCleanup(shutil.rmtree, self.root)
        self.secret = os.path.join(self.root, "store-url")
        with open(self.secret, "w") as f:
            f.write("postgres://example\n")

    def _exposed(self, path, prelude="", env=None):
        result = subprocess.run(
            ["bash", "-c", "set -euo pipefail\n" + prelude + self.functions
             + '\nexposed_secret "$1" "$2"', "_", self.user, path],
            capture_output=True, text=True, timeout=30,
            env=dict(os.environ, **(env or {})))
        self.assertEqual(0, result.returncode, result.stderr)
        return result.stdout.strip()

    def test_an_owner_only_file_is_not_exposed(self):
        os.chmod(self.secret, 0o600)
        self.assertEqual("", self._exposed(self.secret))

    def test_a_world_readable_file_is_exposed(self):
        os.chmod(self.secret, 0o644)
        self.assertEqual(self.secret, self._exposed(self.secret))

    def test_a_group_readable_file_is_exposed(self):
        os.chmod(self.secret, 0o640)
        self.assertEqual(self.secret, self._exposed(self.secret))

    def test_a_group_writable_file_is_exposed(self):
        os.chmod(self.secret, 0o620)
        self.assertEqual(self.secret, self._exposed(self.secret))

    def test_a_symlink_to_a_private_file_is_exposed(self):
        os.chmod(self.secret, 0o600)
        link = os.path.join(self.root, "link")
        os.symlink(self.secret, link)
        self.assertEqual(link, self._exposed(link))

    def test_a_read_granting_acl_is_exposed(self):
        """The mode bits say owner-only, but an ACL lets another account read."""
        os.chmod(self.secret, 0o600)
        listing = ("-rw-------+ 1 someone staff 20 Jan  1 00:00 store-url\n"
                   " 0: user:worker allow read\n")
        self.assertEqual(self.secret, self._exposed(
            self.secret, "ls() { printf '%s' \"${LS_OUTPUT}\"; }\n", {"LS_OUTPUT": listing}))

    def test_a_deny_read_acl_is_not_exposed(self):
        os.chmod(self.secret, 0o600)
        listing = ("-rw-------+ 1 someone staff 20 Jan  1 00:00 store-url\n"
                   " 0: group:everyone deny read\n")
        self.assertEqual("", self._exposed(
            self.secret, "ls() { printf '%s' \"${LS_OUTPUT}\"; }\n", {"LS_OUTPUT": listing}))

    def test_a_probe_that_cannot_run_reports_rather_than_passes(self):
        os.chmod(self.secret, 0o600)
        self.assertEqual(self.secret, self._exposed(
            self.secret, "find() { return 1; }\n"))


if __name__ == "__main__":
    unittest.main()
