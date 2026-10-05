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
"""Tests for ``tools/bin/fleet`` and ``tools/ci/macos/fleet.sh``.

Installing needs sudo and launchd, so the install itself cannot run here.
What can: the dispatcher, the argument guards (which run before anything
touches the host, and before the macOS check, so they hold on Linux too),
and the LaunchDaemon template, which must render into a plist that
``register-daemon.sh`` would accept - every key on its allow-list, and
every placeholder filled.
"""

import grp
import os
import platform
import plistlib
import pwd
import re
import shutil
import subprocess
import tempfile
import unittest

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_FLEET = os.path.join(_REPO_ROOT, "tools", "bin", "fleet")
_MACOS_FLEET = os.path.join(_REPO_ROOT, "tools", "ci", "macos", "fleet.sh")
_TEMPLATE = os.path.join(_REPO_ROOT, "tools", "ci", "macos", "com.almostrealism.ci-runner.plist")
_REGISTER = os.path.join(_REPO_ROOT, "flowtree", "runtime", "agent", "macos", "register-daemon.sh")


def setUpModule():
    """Refuse to run as root, where a guard that failed to fire could install."""
    if os.geteuid() == 0:
        raise unittest.SkipTest("fleet tests must not run as root")


def _run(script, *args):
    return subprocess.run(["bash", script, *args], capture_output=True, text=True, timeout=30)


class FleetDispatcherTests(unittest.TestCase):

    def test_help_lists_both_platforms(self):
        result = _run(_FLEET)
        self.assertEqual(0, result.returncode)
        self.assertIn("macos", result.stdout)
        self.assertIn("rocm", result.stdout)

    def test_rejects_an_unknown_platform(self):
        result = _run(_FLEET, "windows", "start")
        self.assertEqual(2, result.returncode)
        self.assertIn("Unknown platform: windows", result.stderr)

    def test_dispatches_to_the_macos_script(self):
        result = _run(_FLEET, "macos", "--help")
        self.assertEqual(0, result.returncode)
        self.assertIn("install", result.stdout)
        self.assertIn("--instance", result.stdout)


class MacosFleetArgumentTests(unittest.TestCase):

    def test_rejects_user_root(self):
        result = _run(_MACOS_FLEET, "install", "--user", "root")
        self.assertEqual(1, result.returncode)
        self.assertIn("must not be root", result.stderr)

    def test_rejects_a_path_in_the_user(self):
        """The user name becomes part of a path under /Users; a separator
        must never reach it."""
        result = _run(_MACOS_FLEET, "install", "--user", "../etc")
        self.assertEqual(1, result.returncode)
        self.assertIn("valid account name", result.stderr)

    def test_rejects_an_instance_that_is_not_a_label_fragment(self):
        """The instance becomes part of a launchd label and of file names."""
        for instance in ("a/b", "Deploy", "-x", "a b"):
            with self.subTest(instance=instance):
                result = _run(_MACOS_FLEET, "start", "--instance", instance)
                self.assertEqual(1, result.returncode)
                self.assertIn("--instance", result.stderr)

    def test_rejects_an_unknown_option(self):
        result = _run(_MACOS_FLEET, "status", "--bogus")
        self.assertEqual(2, result.returncode)
        self.assertIn("unknown argument", result.stderr)

    def test_an_option_without_its_value_is_refused(self):
        result = _run(_MACOS_FLEET, "install", "--user")
        self.assertEqual(2, result.returncode)
        self.assertIn("needs a value", result.stderr)

    @unittest.skipUnless(platform.system() == "Darwin", "needs launchd")
    def test_status_of_an_uninstalled_instance_says_how_to_install(self):
        result = _run(_MACOS_FLEET, "status", "--instance", "fleet-test-never-installed")
        self.assertEqual(1, result.returncode)
        self.assertIn("is not installed", result.stderr)
        self.assertIn("install --instance fleet-test-never-installed", result.stderr)


class MacosFleetSecurityTests(unittest.TestCase):
    """The install path needs sudo and launchd, so these guard the security
    properties of ``fleet.sh`` at the source level instead - the same way the
    template tests read ``fleet.sh`` rather than running it. Each one fails if a
    future edit reopens a credential leak or a path-trust hole a reviewer closed.
    """

    def setUp(self):
        with open(_MACOS_FLEET) as f:
            self.src = f.read()

    def test_github_token_never_reaches_a_curl_command_line(self):
        """`ps` shows every process's arguments to every account on the host,
        so the token must travel through a curl config file on stdin, never in
        a `-H` argument where the runner account could read it."""
        self.assertIsNone(
            re.search(r'-H\s+"Authorization: token \$\{ENV_GITHUB_PAT\}"', self.src),
            "GITHUB_PAT is passed in a curl -H argument, exposing it via `ps`")
        self.assertIn("--config -", self.src,
                      "the token should be fed to curl through a config file on stdin")
        self.assertRegex(
            self.src,
            r'printf \'header = "Authorization: token %s".*\$\{ENV_GITHUB_PAT\}',
            "the Authorization header should be built with printf and piped to curl --config -")

    def test_env_file_trust_check_validates_ownership_not_only_mode_bits(self):
        """A mode-0644 env file owned by the runner account passes a
        write-bit-only check yet is still the runner's to edit before it is
        sourced as the administrator; the check must reject foreign ownership,
        symlinks, and group/world write together."""
        self.assertRegex(self.src, r"untrusted_path\(\)\s*\{",
                         "a reusable path-trust helper should exist")
        self.assertIn("-type l", self.src, "the trust check must reject symlinks")
        self.assertIn("! -user", self.src, "the trust check must reject foreign ownership")
        self.assertRegex(
            self.src,
            r'untrusted_ancestor "\$\{admin_user\}" "\$\{ENV_FILE\}"',
            "cmd_install should screen the env file and every directory above it before sourcing it")

    def test_runner_dir_path_is_walked_for_a_swappable_ancestor(self):
        """RUNNER_DIR may be any absolute path, and the daemon runs
        ${RUNNER_DIR}/run.sh as the runner; a writable or symlinked ancestor
        lets another account swap it, so every component must be checked."""
        self.assertRegex(
            self.src,
            r'untrusted_ancestor "\$\{RUNNER_USER\}" "\$\{RUNNER_DIR\}" sudo',
            "cmd_install should walk RUNNER_DIR's ancestors with untrusted_ancestor")
        self.assertIn('IFS=\'/\' read -r -a parts <<< "${path#/}"', self.src,
                      "the walk should split the path into components")

    def test_status_checks_the_env_file_path_before_sourcing_it(self):
        """status sources the env file as the invoker, exactly as install
        does, so it must hold the file to the same path-trust standard."""
        status = re.search(r"^cmd_status\(\) \{.*?^\}", self.src, re.M | re.S).group(0)
        check = status.find('untrusted_ancestor "$(id -un)" "${ENV_FILE}"')
        source = status.find('read_env "${ENV_FILE}"')
        self.assertNotEqual(-1, check, "status should walk the env file's path")
        self.assertNotEqual(-1, source)
        self.assertLess(check, source, "the check must come before the env file is sourced")

    def test_a_relative_env_file_is_made_absolute_before_the_walk(self):
        """The walk starts at /, so a relative --env would leave every
        directory above the working directory unchecked."""
        self.assertIn('*) ENV_FILE="${PWD}/${ENV_FILE}" ;;', self.src)

    def test_nothing_is_staged_into_the_runner_home_through_sudo(self):
        """The stage directory belongs to the runner, which can plant a
        symlink in it; a root write there (sudo install -o ...) would follow
        that link and could chown or overwrite any file on the host."""
        install = re.search(r"^cmd_install\(\) \{.*?^\}", self.src, re.M | re.S).group(0)
        self.assertIsNone(re.search(r"sudo install\b", install),
                          "staging must not write through sudo install")
        self.assertRegex(self.src, r'sudo -u "\$\{RUNNER_USER\}" /bin/sh -c \'umask 077 && cat > "\$1"')
        for dest in (r'"\$\{STAGE_DIR\}/bin/\$\{script\}" 755',
                     r'"\$\{STAGE_DIR\}/runner.env" 600',
                     r'"\$\{STAGE_DIR\}/\$\{LABEL\}.plist" 644'):
            self.assertRegex(install, r"stage_file " + dest)

    def test_the_runner_log_is_read_as_the_runner(self):
        """The log lives in the runner's stage directory; reading it as root
        would follow a symlink the runner put in its place."""
        self.assertIn('sudo -u "${RUNNER_USER}" tail -n 30 "${LOG_FILE}"', self.src)
        self.assertNotIn('sudo tail', self.src)

    def test_logs_are_read_as_the_runner(self):
        """`logs` reads a file in the runner-owned stage directory, whose home
        may not be traversable by the administrator and where the runner could
        plant a symlink; both the normal and follow modes must read it as the
        runner, the same trust boundary wait_online uses."""
        logs = re.search(r"^cmd_logs\(\) \{.*?^\}", self.src, re.M | re.S).group(0)
        self.assertRegex(
            logs,
            r'exec sudo -u "\$\{RUNNER_USER\}" tail -n 50 -f "\$\{LOG_FILE\}"',
            "the follow mode must tail the log as the runner")
        self.assertRegex(
            logs,
            r'sudo -u "\$\{RUNNER_USER\}" tail -n 50 "\$\{LOG_FILE\}"',
            "the normal mode must tail the log as the runner")
        self.assertIsNone(
            re.search(r'^\s*(exec\s+)?tail -n 50', logs, re.M),
            "logs must never read the runner-owned log directly as the administrator")

    def test_status_suggests_a_start_command_scoped_to_the_instance(self):
        """For a non-default instance a stopped runner must be started with its
        --instance selector; a bare `start` would act on the default instance.
        The host-wide monitor, which is not per-instance, keeps a bare start."""
        self.assertRegex(
            self.src,
            r'describe_service "\$\{LABEL\}" '
            r'"tools/bin/fleet macos start\$\{INSTANCE:\+ --instance \$\{INSTANCE\}\}"',
            "the runner's stopped-start hint must carry the instance selector")
        self.assertRegex(
            self.src,
            r'describe_service "\$\{MONITOR_LABEL\}" "tools/bin/fleet macos start"',
            "the host-wide monitor's start hint must not carry an instance selector")

    def test_start_bootstraps_the_root_owned_installed_plist(self):
        """start must load the root:wheel plist register-daemon.sh installs under
        /Library/LaunchDaemons, never the runner-writable staged copy."""
        self.assertRegex(
            self.src,
            r'launchctl bootstrap system "\$\{INSTALLED_PLIST\}"',
            "start should bootstrap INSTALLED_PLIST")
        self.assertIn('INSTALLED_PLIST="${DAEMONS_DIR}/${LABEL}.plist"', self.src)
        self.assertIn('DAEMONS_DIR="/Library/LaunchDaemons"', self.src)


    def test_install_screens_every_runner_path_directory(self):
        """The daemon runs java and mvn from the first RUNNER_PATH directory
        that has them; finding them there proves nothing about who else could
        put a program in that directory, so install must walk every entry."""
        install = re.search(r"^cmd_install\(\) \{.*?^\}", self.src, re.M | re.S).group(0)
        self.assertIn('untrusted_search_path "${RUNNER_USER}" "${RUNNER_PATH}" sudo', install)
        check = install.find("untrusted_search_path")
        render = install.find('s|@RUNNER_PATH@|')
        self.assertLess(check, render, "RUNNER_PATH must be screened before it is rendered into the plist")

    def test_runner_path_screen_captures_the_checks_exit_status(self):
        """A crash in untrusted_search_path (e.g. an empty-array abort under
        `set -u` on bash 3.2) must count as an error, not pass every entry: the
        output is captured into a variable with its exit status, rather than
        read through `done < <(...)` whose failure the loop never saw."""
        install = re.search(r"^cmd_install\(\) \{.*?^\}", self.src, re.M | re.S).group(0)
        self.assertIsNone(
            re.search(r"done < <\(untrusted_search_path", install),
            "the fail-open process-substitution form must be gone")
        self.assertRegex(
            install,
            r'path_untrusted="\$\(untrusted_search_path "\$\{RUNNER_USER\}" '
            r'"\$\{RUNNER_PATH\}" sudo\)" \|\| path_status=\$\?',
            "untrusted_search_path's output and exit status must both be captured")
        self.assertRegex(
            install,
            r'if \[ "\$\{path_status\}" -ne 0 \]',
            "a non-zero exit from the check must be reported as an error")

    def test_trust_helpers_reject_write_granting_acls(self):
        """macOS ACLs can grant write with the mode bits clear; the env-file,
        RUNNER_DIR and RUNNER_PATH trust checks must reject them too, the same
        way register-daemon.sh does on the plist path."""
        self.assertRegex(self.src, r"acl_write_grant\(\)\s*\{",
                         "a reusable ACL-trust helper should exist")
        self.assertIn("ls -lde", self.src, "ACL entries are read with `ls -e`")
        for helper in ("untrusted_path", "untrusted_tool_dir"):
            body = re.search(r"^%s\(\) \{.*?^\}" % helper, self.src, re.M | re.S).group(0)
            self.assertIn("acl_write_grant", body,
                          "%s must consult the ACL check, not only mode bits" % helper)

    def test_install_screens_every_program_the_daemon_will_run(self):
        """A trusted RUNNER_PATH directory can still hold a writable java, or a
        link to one elsewhere; install must screen each resolved program, for
        every required tool, before the PATH is rendered into the plist."""
        install = re.search(r"^cmd_install\(\) \{.*?^\}", self.src, re.M | re.S).group(0)
        check = install.find('untrusted_program "${RUNNER_USER}" "${program}" sudo')
        self.assertNotEqual(-1, check, "install must screen each resolved program")
        self.assertLess(check, install.find('s|@RUNNER_PATH@|'))
        self.assertEqual(2, install.count("_ ${REQUIRED_TOOLS}"),
                         "the lookup and the screen must cover the same tool list")

    def test_the_xcode_check_runs_the_system_xcodebuild_by_absolute_path(self):
        """The optional Xcode check runs as the administrator, with the
        administrator's inherited PATH. Resolving ``xcodebuild`` from that PATH
        would let an account that can write a PATH entry run its own program as
        the administrator, so the check must call the OS shim by absolute
        path."""
        install = re.search(r"^cmd_install\(\) \{.*?^\}", self.src, re.M | re.S).group(0)
        self.assertIn("/usr/bin/xcodebuild -version", install,
                      "the Xcode check must invoke the system xcodebuild by absolute path")
        self.assertNotRegex(
            install, r"(?<![/\w])xcodebuild -version",
            "xcodebuild must never be resolved through the inherited PATH")

    def test_runner_dir_files_others_can_write_are_refused(self):
        """Files the runner owns are still another account's to rewrite when a
        group or world write bit is set; the daemon would run them."""
        install = re.search(r"^cmd_install\(\) \{.*?^\}", self.src, re.M | re.S).group(0)
        self.assertRegex(
            install,
            r'sudo find "\$\{RUNNER_DIR\}" -path "\$\{RUNNER_DIR\}/_work" -prune\s*\\\s*'
            r'-o ! -type l \\\( -perm -g\+w -o -perm -o\+w \\\) -print -quit')

    def test_install_verifies_the_runner_can_write_the_runner_dir(self):
        """Trust is not usability: a RUNNER_DIR owned by root, or owned by the
        runner with its own write bit cleared, passes the ownership, mode, and
        ancestor checks yet leaves runner.sh unable to create config.sh, so the
        daemon registers and then waits for a runner that never comes online.
        install must probe effective write access as the runner — walking up to
        the nearest existing directory when RUNNER_DIR does not exist yet, since
        runner.sh creates it with mkdir -p."""
        install = re.search(r"^cmd_install\(\) \{.*?^\}", self.src, re.M | re.S).group(0)
        self.assertIn('writable_at="$(nearest_existing_dir "${RUNNER_DIR}")"', install,
                      "install must fall back to the nearest existing directory")
        helper = re.search(r"^nearest_existing_dir\(\) \{.*?^\}", self.src, re.M | re.S).group(0)
        self.assertRegex(
            helper,
            r'while ! sudo test -d "\$\{dir\}" && \[ "\$\{dir\}" != "/" \]',
            "nearest_existing_dir must walk up to the nearest existing directory")
        self.assertRegex(
            install,
            r'sudo -u "\$\{RUNNER_USER\}" /bin/sh -c \'test -w "\$1" && test -x "\$1"\'',
            "install must probe write and search access as the runner account")
        probe = install.find("writable_at")
        render = install.find('s|@RUNNER_DIR@|')
        if render != -1:
            self.assertLess(probe, render,
                            "the write-access probe must run before install proceeds")

    def test_install_rejects_a_runner_dir_that_is_not_a_directory(self):
        """A pre-existing RUNNER_DIR that is a regular file is skipped by the
        [ -d ] ownership/write-bit blocks, passes the ancestor walk as a
        runner-owned leaf, and lets the write probe fall back to its parent, so
        it clears preflight. runner.sh then runs mkdir -p on it, which fails on
        a non-directory, and the daemon waits for a runner that never registers.
        install must reject an existing non-directory RUNNER_DIR, before the
        plist is rendered."""
        install = re.search(r"^cmd_install\(\) \{.*?^\}", self.src, re.M | re.S).group(0)
        self.assertRegex(
            install,
            r'sudo test -e "\$\{RUNNER_DIR\}" && ! sudo test -d "\$\{RUNNER_DIR\}"',
            "install must reject a RUNNER_DIR that exists but is not a directory")
        reject = install.find('! sudo test -d "${RUNNER_DIR}"')
        render = install.find('s|@RUNNER_DIR@|')
        if render != -1:
            self.assertLess(reject, render,
                            "the non-directory check must run before install proceeds")

    def test_the_monitor_home_is_walked_before_the_monitor_is_installed(self):
        """FLEET_HOME comes from the administrator's environment and holds the
        monitor's database credential; one under the runner's home would put
        it within CI jobs' reach."""
        install = re.search(r"^cmd_install\(\) \{.*?^\}", self.src, re.M | re.S).group(0)
        check = install.find('untrusted_ancestor "${admin_user}" "${fleet_home}"')
        self.assertNotEqual(-1, check, "install must walk FLEET_HOME's path")
        self.assertLess(check, install.find('"${MONITOR_INSTALL}" ${STORE_FROM'))
        self.assertIn('*) fleet_bad="${fleet_home}" ;;', install,
                      "a relative FLEET_HOME must be refused, not walked from /")

    def test_an_existing_monitor_credential_must_be_private(self):
        """install.sh restricts store-url's mode only after the runner is online,
        so an existing credential the runner can read (mode 0644, or owned by the
        runner) must be refused in preflight, before anything is installed."""
        install = re.search(r"^cmd_install\(\) \{.*?^\}", self.src, re.M | re.S).group(0)
        check = install.find('exposed_secret "${admin_user}" "${store_url}"')
        self.assertNotEqual(-1, check, "install must screen an existing store-url")
        self.assertLess(check, install.find('s|@RUNNER_DIR@|'),
                        "the credential must be screened before anything is installed")
        self.assertLess(check, install.find('echo "  ✓ monitor credential present'),
                        "an exposed credential must not be reported as present")

    def test_an_existing_store_url_must_be_a_regular_file(self):
        """`test -s` is true for a non-empty directory, so an accidental
        store-url directory must not be reported as a present credential:
        install.sh would skip its missing-file branch, chmod the directory, and
        the collector would fail reading it. A non-regular store-url is refused."""
        install = re.search(r"^cmd_install\(\) \{.*?^\}", self.src, re.M | re.S).group(0)
        present = install.find('echo "  ✓ monitor credential present')
        self.assertIn('elif [ -f "${store_url}" ] && [ -s "${store_url}" ]; then', install,
                      "a present credential must be a non-empty regular file")
        self.assertIn('elif [ -e "${store_url}" ] && [ ! -f "${store_url}" ]; then', install,
                      "an existing non-regular store-url must be refused")
        self.assertLess(install.find('elif [ -f "${store_url}" ]'), present)

    def test_install_refuses_a_readable_env_file(self):
        """The env file holds GITHUB_PAT; the ancestor walk permits a readable
        file, so install must separately refuse one any other account can read,
        before it is sourced and before anything is staged."""
        install = re.search(r"^cmd_install\(\) \{.*?^\}", self.src, re.M | re.S).group(0)
        check = install.find('exposed_secret "${admin_user}" "${ENV_FILE}"')
        self.assertNotEqual(-1, check, "install must screen the env file for read exposure")
        self.assertLess(check, install.find('read_env "${ENV_FILE}"'),
                        "the read-exposure check must run before the env file is sourced")
        self.assertLess(check, install.find('s|@RUNNER_DIR@|'),
                        "the env file must be screened before anything is staged")

    def test_a_custom_runner_workdir_is_walked_like_the_runner_dir(self):
        """Jobs run as the runner in RUNNER_WORKDIR; one set apart from
        RUNNER_DIR needs the same ancestor walk, and a relative one is refused."""
        self.assertIn(" RUNNER_WORKDIR ", re.search(r"^read_env\(\) \{.*?^\}", self.src, re.M | re.S).group(0),
                      "read_env must read RUNNER_WORKDIR for install to screen it")
        install = re.search(r"^cmd_install\(\) \{.*?^\}", self.src, re.M | re.S).group(0)
        check = install.find('$(runner_dir_problems RUNNER_WORKDIR "${runner_workdir}")')
        self.assertNotEqual(-1, check, "install must screen the effective RUNNER_WORKDIR")
        self.assertLess(check, install.find('s|@RUNNER_DIR@|'))
        helper = re.search(r"^runner_dir_problems\(\) \{.*?^\}", self.src, re.M | re.S).group(0)
        self.assertIn('bad="$(untrusted_ancestor "${RUNNER_USER}" "${path}" sudo)"', helper,
                      "the helper must walk the path with the runner trust boundary")
        self.assertIn('must be an absolute path"; return 0 ;;', helper,
                      "a relative path must be refused, not walked from /")

    def test_the_default_runner_workdir_is_validated(self):
        """When the env file leaves RUNNER_WORKDIR unset, runner.sh still runs
        mkdir -p on ${RUNNER_DIR}/_work, so the default is not exempt: a
        pre-existing _work regular file, or one the runner cannot write, must be
        caught in preflight. install must screen the effective default, not only
        an explicitly set custom path."""
        install = re.search(r"^cmd_install\(\) \{.*?^\}", self.src, re.M | re.S).group(0)
        self.assertIn('runner_workdir="${ENV_RUNNER_WORKDIR:-${RUNNER_DIR}/_work}"', install,
                      "install must fall back to ${RUNNER_DIR}/_work so the default is screened")
        self.assertNotIn('if [ -n "${ENV_RUNNER_WORKDIR}" ]; then', install,
                         "the workdir checks must not be gated on a non-empty RUNNER_WORKDIR")

    def test_a_custom_runner_workdir_is_checked_for_runner_write_access(self):
        """runner.sh runs mkdir -p on RUNNER_WORKDIR unconditionally, so a
        trusted path the runner cannot create — an existing non-directory, or a
        missing one under a runner-unwritable parent — must be refused in
        preflight rather than left to fail after launchd starts retrying."""
        helper = re.search(r"^runner_dir_problems\(\) \{.*?^\}", self.src, re.M | re.S).group(0)
        self.assertRegex(
            helper,
            r'sudo test -e "\$\{path\}" && ! sudo test -d "\$\{path\}"',
            "the helper must reject a path that exists but is not a directory")
        self.assertIn('at="$(nearest_existing_dir "${path}")"', helper,
                      "the helper must probe the nearest existing ancestor of the path")
        self.assertRegex(
            helper,
            r'sudo -u "\$\{RUNNER_USER\}" /bin/sh -c \'test -w "\$1" && test -x "\$1"\' _ "\$\{at\}"',
            "the write probe must run as the runner account")
        install = re.search(r"^cmd_install\(\) \{.*?^\}", self.src, re.M | re.S).group(0)
        probe = install.find('runner_dir_problems RUNNER_WORKDIR')
        self.assertLess(probe, install.find('s|@RUNNER_DIR@|'),
                        "the write-access probe must run before install proceeds")

    def test_the_register_script_path_is_walked_before_it_runs_as_root(self):
        """register-daemon.sh is handed to sudo and runs as root; checking only
        its -x bit leaves a TOCTOU hole — another account that can write any
        component of the checkout path could swap it between preflight and the
        sudo call and have root run its code. install must walk the full path
        with the root/administrator trust boundary, before the sudo invocation."""
        install = re.search(r"^cmd_install\(\) \{.*?^\}", self.src, re.M | re.S).group(0)
        walk = install.find('untrusted_ancestor "${admin_user}" "${REGISTER_SCRIPT}"')
        self.assertNotEqual(-1, walk, "install must walk the register script's path")
        run = install.find('sudo "${REGISTER_SCRIPT}"')
        self.assertNotEqual(-1, run, "install runs the register script through sudo")
        self.assertLess(walk, run, "the path must be screened before it runs as root")

    def test_the_staged_source_files_are_screened_before_they_are_staged(self):
        """runner.sh and cpu-watcher.sh are read from the checkout and staged into
        STAGE_DIR, where the runner runs them under launchd with the staged
        GITHUB_PAT beside them; the ci-runner plist template is read here and
        installed, rendered, as root by register-daemon.sh. All three are in the
        register script's position: a runner- or ACL-writable one, or a symlink on
        the path, lets another account swap it between preflight and staging — a
        swapped runner.sh could exfiltrate the credential, a swapped template could
        inject launchd keys that run as root. install must walk each with the
        root/administrator trust boundary before it is staged."""
        install = re.search(r"^cmd_install\(\) \{.*?^\}", self.src, re.M | re.S).group(0)
        self.assertRegex(
            install,
            r'for staged_source in "\$\{SCRIPT_DIR\}/runner\.sh" '
            r'"\$\{SCRIPT_DIR\}/cpu-watcher\.sh" "\$\{TEMPLATE\}"; do',
            "install must screen runner.sh, cpu-watcher.sh and the plist template")
        walk = install.find('untrusted_ancestor "${admin_user}" "${staged_source}"')
        self.assertNotEqual(-1, walk, "install must walk each staged source path")
        stage = install.find('stage_file "${STAGE_DIR}/bin/${script}" 755 < "${SCRIPT_DIR}/${script}"')
        self.assertNotEqual(-1, stage, "install stages runner.sh and cpu-watcher.sh")
        self.assertLess(walk, stage, "the sources must be screened before they are staged")

    def test_the_env_file_path_is_screened_before_the_template_is_copied(self):
        """When the default .env is missing, cmd_install copies .env.example into
        place with the administrator's cp, which follows a symlink at the target;
        a dangling symlink there, or a checkout under an attacker-writable parent,
        would redirect that write. The path must be walked before the copy, not
        only after it, so the trust check is not too late to matter."""
        install = re.search(r"^cmd_install\(\) \{.*?^\}", self.src, re.M | re.S).group(0)
        walk = install.find('create_bad="$(untrusted_ancestor "${admin_user}" "${ENV_FILE}")"')
        copy = install.find('cp "${SCRIPT_DIR}/.env.example" "${ENV_FILE}"')
        self.assertNotEqual(-1, walk, "install must walk the env file's path before creating it")
        self.assertNotEqual(-1, copy)
        self.assertLess(walk, copy, "the path must be screened before the template is copied")

    def test_the_monitor_installer_and_its_code_paths_are_screened(self):
        """install.sh and the render.sh it calls run with the administrator's
        privileges straight from the checkout, exactly as the register script
        does; a runner-writable one would run as the administrator during the
        monitor step, so each must be walked before the monitor install runs."""
        self.assertIn('MONITOR_RENDER="${CHECKOUT}/tools/fleet/launchd/render.sh"', self.src,
                      "the render script path must be defined")
        install = re.search(r"^cmd_install\(\) \{.*?^\}", self.src, re.M | re.S).group(0)
        self.assertRegex(
            install,
            r'for monitor_code in "\$\{MONITOR_INSTALL\}" "\$\{MONITOR_RENDER\}"',
            "install must screen both the monitor installer and render.sh")
        walk = install.find('untrusted_ancestor "${admin_user}" "${monitor_code}"')
        run = install.find('"${MONITOR_INSTALL}" ${STORE_FROM')
        self.assertNotEqual(-1, walk, "install must walk the monitor code paths")
        self.assertNotEqual(-1, run, "install runs the monitor installer")
        self.assertLess(walk, run, "the code paths must be screened before the monitor install runs")

    def test_the_monitor_home_descendants_are_screened(self):
        """A trusted FLEET_HOME path does not vouch for what already lives inside
        it. render.sh writes the rendered plists and the services' logs into
        ${FLEET_HOME}/launchd and ${FLEET_HOME}/logs and creates the venv under
        ${FLEET_HOME}/venv, keeping an existing one. A launchd, logs, or venv
        directory left there from an earlier, looser state that is now a symlink,
        writable by others, or foreign-owned would let another account redirect
        what the administrator writes, or run code as the administrator through
        the venv. Each must be walked before the monitor install runs."""
        install = re.search(r"^cmd_install\(\) \{.*?^\}", self.src, re.M | re.S).group(0)
        self.assertIn(
            'for monitor_dir in "${fleet_home}/launchd" "${fleet_home}/logs" "${fleet_home}/venv"; do',
            install,
            "install must screen the launchd, logs and venv descendants of FLEET_HOME")
        walk = install.find('untrusted_ancestor "${admin_user}" "${monitor_dir}"')
        self.assertNotEqual(-1, walk, "the descendants must be walked")
        self.assertLess(walk, install.find('"${MONITOR_INSTALL}" ${STORE_FROM'),
                        "the descendants must be screened before the monitor install runs")

    def test_the_monitor_interpreter_is_followed_and_screened(self):
        """install.sh runs ${FLEET_PYTHON:-${FLEET_HOME}/venv/bin/python3} as the
        administrator. A venv's python3 is a symlink to the base interpreter, so a
        plain ancestor walk would both falsely reject every real venv and miss a
        link into attacker-controlled space; the interpreter is followed with
        untrusted_program, and only once it exists, so a first install (the venv
        not yet created) is not refused. The effective path covers both an
        explicit FLEET_PYTHON and the default venv interpreter."""
        install = re.search(r"^cmd_install\(\) \{.*?^\}", self.src, re.M | re.S).group(0)
        self.assertIn('fleet_python="${FLEET_PYTHON:-${fleet_home}/venv/bin/python3}"', install,
                      "the interpreter must cover both an explicit FLEET_PYTHON and the default venv")
        self.assertIn('if sudo test -e "${fleet_python}"; then', install,
                      "the interpreter must be screened only once it exists, so a first install is not refused")
        screen = install.find('untrusted_program "${admin_user}" "${fleet_python}" sudo')
        self.assertNotEqual(-1, screen,
                            "a venv python3 is a symlink, so the interpreter must be followed, not walked")
        self.assertIn('*) python_bad="${fleet_python}" ;;', install,
                      "a relative interpreter path must be refused, not followed from the cwd")
        self.assertLess(screen, install.find('"${MONITOR_INSTALL}" ${STORE_FROM'),
                        "the interpreter must be screened before the monitor install runs")

    def test_status_refuses_to_source_a_readable_env_file(self):
        """status sources the env file as the invoker just as install does, and
        the file holds GITHUB_PAT; install refuses a group- or world-readable one,
        so status must apply the same read-exposure check before sourcing it
        rather than condoning a credential file the runner account can read."""
        status = re.search(r"^cmd_status\(\) \{.*?^\}", self.src, re.M | re.S).group(0)
        check = status.find('exposed_secret "$(id -un)" "${ENV_FILE}"')
        source = status.find('read_env "${ENV_FILE}"')
        self.assertNotEqual(-1, check, "status should screen the env file for read exposure")
        self.assertNotEqual(-1, source)
        self.assertLess(check, source, "the read-exposure check must come before the env file is sourced")

    def test_install_rejects_a_runner_dir_shared_by_another_instance(self):
        """RUNNER_DIR carries the instance suffix only when it falls back to the
        default, so a custom ENV_RUNNER_DIR could name a directory another
        installed instance already uses; the two would share .runner/config.sh
        state and overwrite each other's GitHub registration. install must reject
        a RUNNER_DIR already registered to a different service's plist, skipping
        the current instance's own plist on re-install."""
        install = re.search(r"^cmd_install\(\) \{.*?^\}", self.src, re.M | re.S).group(0)
        self.assertIn('for other_plist in "${DAEMONS_DIR}/${LABEL_BASE}"*.plist; do', install,
                      "install must enumerate the installed runner plists")
        self.assertIn('[ "${other_plist}" = "${INSTALLED_PLIST}" ] && continue', install,
                      "the current instance's own plist must be skipped on re-install")
        self.assertRegex(
            install,
            r'other_dir="\$\(plist_value "\$\{other_plist\}" ProgramArguments:3\)"',
            "install must read each plist's runner directory to compare it")
        self.assertRegex(
            install,
            r'\[ "\$\{other_dir\}" = "\$\{RUNNER_DIR\}" \]',
            "install must reject a RUNNER_DIR already used by another instance")

    def test_runner_dir_scans_stat_through_sudo(self):
        """A RUNNER_DIR under a home the administrator cannot enter reads as
        missing to an unprivileged [ -d ], which would skip the ownership and
        write-bit scans and misplace the write probe."""
        install = re.search(r"^cmd_install\(\) \{.*?^\}", self.src, re.M | re.S).group(0)
        self.assertIn('if sudo test -d "${RUNNER_DIR}"; then', install)
        self.assertNotIn('[ -d "${RUNNER_DIR}" ]', install)
        self.assertNotIn('[ ! -d "${writable_at}" ]', install)

    def test_stop_disables_a_service_launchd_does_not_have(self):
        """A plist launchd has not loaded (booted out by hand) is loaded again
        at the next boot unless disabled, so stop must disable it even when
        there is nothing to boot out."""
        stop = re.search(r"^cmd_stop\(\) \{.*?^\}", self.src, re.M | re.S).group(0)
        not_running = stop[:stop.find("return 0")]
        self.assertIn('sudo launchctl disable "system/${LABEL}"', not_running,
                      "the not-running path must disable the label before returning")

    def test_the_shutdown_trap_stops_the_cpu_watcher(self):
        """The trap exits before the loop reaches the lines that stop the
        watcher, so it must stop the watcher itself."""
        with open(os.path.join(_REPO_ROOT, "tools", "ci", "macos", "runner.sh")) as f:
            runner = f.read()
        cleanup = re.search(r"^cleanup\(\) \{.*?^\}", runner, re.M | re.S).group(0)
        self.assertIn('kill "${WATCHER_PID}"', cleanup)
        self.assertIn('wait "${WATCHER_PID}"', cleanup)
        self.assertLess(cleanup.find('kill "${WATCHER_PID}"'), cleanup.find("exit 0"))
        self.assertIn('WATCHER_PID=""\n\nstart_agent()', runner,
                      "WATCHER_PID must be initialised so the trap is safe under set -u")

    def test_the_path_walk_guards_an_empty_component_array(self):
        """A "/" path leaves the components array empty, and expanding an empty
        array under `set -u` aborts on the bash macOS ships (3.2); the walk
        guards both its arrays with the ${arr[@]+...} idiom."""
        for array in ("parts", "dirs"):
            self.assertIn('${%s[@]+"${%s[@]}"}' % (array, array), self.src,
                          "%s must be expanded with the empty-array guard" % array)

    def test_the_render_templates_are_screened(self):
        """render.sh reads the two plist templates beside it and renders them, as
        the administrator, into admin-owned plists register-daemon.sh accepts; a
        writable or ACL-modified template could inject launchd keys into one of
        those plists. The templates must be walked with the same trust boundary
        as the scripts, before the monitor install runs."""
        for const in ("MONITOR_COLLECTOR_TEMPLATE", "MONITOR_POLLER_TEMPLATE"):
            self.assertRegex(
                self.src,
                r'%s="\$\{CHECKOUT\}/tools/fleet/launchd/com\.almostrealism\.fleet-[a-z]+\.plist"' % const,
                "the render template path %s must be defined" % const)
        install = re.search(r"^cmd_install\(\) \{.*?^\}", self.src, re.M | re.S).group(0)
        self.assertIn('"${MONITOR_COLLECTOR_TEMPLATE}" "${MONITOR_POLLER_TEMPLATE}"', install,
                      "install must screen both render templates alongside the monitor scripts")
        walk = install.find('untrusted_ancestor "${admin_user}" "${monitor_code}"')
        run = install.find('"${MONITOR_INSTALL}" ${STORE_FROM')
        self.assertNotEqual(-1, walk, "install must walk the monitor code and template paths")
        self.assertLess(walk, run, "the templates must be screened before the monitor install runs")

    def test_the_monitor_venv_pip_is_followed_and_screened(self):
        """render.sh reuses an existing venv and runs its pip as the administrator
        to bring an old install's dependencies up to date, so an existing
        ${FLEET_HOME}/venv/bin/pip is a code path the monitor install executes.
        It must be followed with untrusted_program and screened only once it
        exists, exactly as the interpreter is, before the monitor install runs."""
        install = re.search(r"^cmd_install\(\) \{.*?^\}", self.src, re.M | re.S).group(0)
        self.assertIn('fleet_pip="${fleet_home}/venv/bin/pip"', install,
                      "install must screen the venv pip render.sh may run")
        self.assertIn('if sudo test -e "${fleet_pip}"; then', install,
                      "the pip must be screened only once it exists, so a first install is not refused")
        screen = install.find('untrusted_program "${admin_user}" "${fleet_pip}" sudo')
        self.assertNotEqual(-1, screen,
                            "the venv pip must be followed, not walked, like the interpreter")
        self.assertIn('*) pip_bad="${fleet_pip}" ;;', install,
                      "a relative pip path must be refused, not followed from the cwd")
        self.assertLess(screen, install.find('"${MONITOR_INSTALL}" ${STORE_FROM'),
                        "the pip must be screened before the monitor install runs")

    def test_the_runner_home_is_stat_through_sudo(self):
        """A service account's home is commonly not traversable by the
        administrator, so an unprivileged `[ -d ]` reads a real home as missing
        and rejects a supported setup; the rest of the preflight already uses
        sudo for paths under this home, so the home test must too."""
        install = re.search(r"^cmd_install\(\) \{.*?^\}", self.src, re.M | re.S).group(0)
        self.assertIn('! sudo test -d "${RUNNER_HOME}"', install,
                      "the home must be stat'd through sudo, not with an unprivileged [ -d ]")
        self.assertNotIn('[ ! -d "${RUNNER_HOME}" ]', install,
                         "the unprivileged home test must be gone")

    def test_a_timed_out_install_boots_out_the_runner(self):
        """The plist is registered and enabled before wait_online, and carries
        KeepAlive, so on timeout launchd would keep retrying a half-installed
        runner after install reports failure. wait_online must disable and boot
        the service out before exiting, so a failed install leaves nothing
        running."""
        wait = re.search(r"^wait_online\(\) \{.*?^\}", self.src, re.M | re.S).group(0)
        disable = wait.find('sudo launchctl disable "system/${LABEL}"')
        bootout = wait.find('sudo launchctl bootout "system/${LABEL}"')
        fail = wait.rfind("exit 1")
        self.assertNotEqual(-1, disable, "the timeout path must disable the label")
        self.assertNotEqual(-1, bootout, "the timeout path must boot the service out")
        self.assertLess(disable, fail, "the service must be disabled before the install fails")
        self.assertLess(bootout, fail, "the service must be booted out before the install fails")

    def test_the_env_template_source_is_screened_before_it_is_copied(self):
        """The destination walk keeps the cp from being redirected, but
        .env.example is a trust input in its own right: its contents become the
        owner-only .env a later invocation sources as the administrator. A
        symlinked, other-writable, or foreign-owned template could carry shell
        commands that read_env then runs as you, with the mode-600 copy passing
        every later check. The source must be walked with the same trust
        boundary, before the cp reads it."""
        install = re.search(r"^cmd_install\(\) \{.*?^\}", self.src, re.M | re.S).group(0)
        walk = install.find(
            'template_bad="$(untrusted_ancestor "${admin_user}" "${SCRIPT_DIR}/.env.example")"')
        copy = install.find('cp "${SCRIPT_DIR}/.env.example" "${ENV_FILE}"')
        self.assertNotEqual(-1, walk, "install must walk the .env.example source path")
        self.assertNotEqual(-1, copy)
        self.assertLess(walk, copy, "the template source must be screened before it is copied")

    def test_a_failed_monitor_install_keeps_the_online_runner(self):
        """By the monitor step the runner is registered and online. A monitor
        failure must not read as an install that changed nothing and failed, and
        must not boot the working runner out to make the state tidy: the runner
        is the deliverable and is taking jobs. install must catch the failure,
        report the partial state, and exit non-zero without rolling the runner
        back."""
        install = re.search(r"^cmd_install\(\) \{.*?^\}", self.src, re.M | re.S).group(0)
        self.assertRegex(
            install,
            r'if ! "\$\{MONITOR_INSTALL\}" \$\{STORE_FROM:\+--store-from "\$\{STORE_FROM\}"\}; then',
            "the monitor install must be run in a condition so its failure is caught")
        guard = install.find('if ! "${MONITOR_INSTALL}"')
        self.assertNotEqual(-1, guard, "install must guard the monitor install")
        tail = install[guard:]
        self.assertIn("is installed and online, but the metrics collector failed", tail,
                      "the failure must report the runner is up and only monitoring is missing")
        self.assertIn("exit 1", tail,
                      "a monitor failure must still exit non-zero")
        self.assertNotIn('launchctl bootout "system/${LABEL}"', tail,
                         "a monitor failure must not boot the online runner out")

    def test_both_entry_points_run_bash_by_absolute_path(self):
        """fleet.sh calls sudo and installs the monitor as the administrator, and
        the administrator's PATH is not screened before it starts. A
        `/usr/bin/env bash` shebang would let a writable PATH entry supply the
        shell that does all of that, so both the dispatcher and the macOS script
        name /bin/bash directly."""
        for script in (_FLEET, _MACOS_FLEET):
            with self.subTest(script=os.path.relpath(script, _REPO_ROOT)):
                with open(script) as f:
                    self.assertEqual("#!/bin/bash", f.readline().rstrip("\n"))

    def test_an_env_file_that_sets_path_is_refused_before_staging(self):
        """runner.sh sources the staged env file after launchd has set the
        screened RUNNER_PATH, so a PATH assignment in it would replace the
        screened value. install must refuse one, before anything is staged."""
        install = re.search(r"^cmd_install\(\) \{.*?^\}", self.src, re.M | re.S).group(0)
        check = install.find('if [ -n "${ENV_PATH_OVERRIDE}" ]; then')
        self.assertNotEqual(-1, check, "install must check whether the env file sets PATH")
        self.assertLess(install.find('read_env "${ENV_FILE}"'), check,
                        "the override is known only once the env file has been read")
        self.assertLess(check, install.find('stage_file "${STAGE_DIR}/runner.env"'),
                        "the env file must be refused before it is staged")
        self.assertIn("exit 1", install[check:check + 600])

    def test_runner_dir_tree_is_scanned_for_write_granting_acls(self):
        """A mode-755 run.sh can still carry an ACL entry letting another account
        rewrite it; the mode-bit scan of the runner tree cannot see that, so the
        same tree (minus _work) is scanned for write-granting ACL entries."""
        install = re.search(r"^cmd_install\(\) \{.*?^\}", self.src, re.M | re.S).group(0)
        check = install.find('acl_write_grant_tree "${RUNNER_DIR}" "${RUNNER_DIR}/_work" sudo')
        self.assertNotEqual(-1, check, "install must scan RUNNER_DIR for write-granting ACLs")
        self.assertLess(check, install.find('s|@RUNNER_DIR@|'),
                        "the ACL scan must run before anything is installed")

    def test_the_host_python3_is_screened_before_the_monitor_install(self):
        """install.sh runs the PATH's python3 as the administrator to check for
        the venv module, and render.sh creates the venv with it on a first
        install. That interpreter must be followed and screened whether or not
        the venv exists, and a missing or relative one refused."""
        install = re.search(r"^cmd_install\(\) \{.*?^\}", self.src, re.M | re.S).group(0)
        self.assertIn('host_python="$(command -v python3 || true)"', install)
        screen = install.find('untrusted_program "${admin_user}" "${host_python}"')
        self.assertNotEqual(-1, screen, "the host python3 must be followed, not just walked")
        self.assertIn('"") host_python_bad="python3 (not found on your PATH)" ;;', install)
        self.assertIn('*) host_python_bad="${host_python}" ;;', install,
                      "a relative python3 must be refused")
        block = install[install.find('host_python="$(command -v python3'):screen]
        self.assertNotIn("sudo test -e", block,
                         "the host interpreter must be screened even before any venv exists")
        self.assertLess(screen, install.find('"${MONITOR_INSTALL}" ${STORE_FROM'),
                        "the interpreter must be screened before the monitor install runs")

    def test_the_stage_directory_is_screened_before_staging(self):
        """The stage directory receives runner.env, with GITHUB_PAT, before
        register-daemon.sh checks the plist path. Its ancestors must be trusted
        and the runner must be able to create it, all before anything is
        staged."""
        install = re.search(r"^cmd_install\(\) \{.*?^\}", self.src, re.M | re.S).group(0)
        check = install.find('$(runner_dir_problems "the stage directory" "${STAGE_DIR}")')
        self.assertNotEqual(-1, check, "install must screen STAGE_DIR")
        self.assertLess(check, install.find('mkdir -p "$1/bin"'),
                        "STAGE_DIR must be screened before it is created")
        self.assertLess(check, install.find('stage_file "${STAGE_DIR}/runner.env"'))


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


class ShellSyntaxTests(unittest.TestCase):
    """`bash -n` catches a syntax error in any of the installer scripts without
    needing macOS; it runs anywhere and guards every future edit to them."""

    def test_installer_scripts_parse(self):
        for script in (_FLEET, _MACOS_FLEET,
                       os.path.join(_REPO_ROOT, "tools", "ci", "macos", "runner.sh"),
                       os.path.join(_REPO_ROOT, "tools", "ci", "macos", "cpu-watcher.sh")):
            with self.subTest(script=os.path.relpath(script, _REPO_ROOT)):
                result = subprocess.run(["bash", "-n", script],
                                        capture_output=True, text=True, timeout=30)
                self.assertEqual(0, result.returncode, result.stderr)


class RunnerPlistTemplateTests(unittest.TestCase):

    def _render(self):
        with open(_TEMPLATE) as f:
            text = f.read()
        values = {
            "LABEL": "com.almostrealism.ci-runner-test",
            "RUNNER_USER": "worker",
            "RUNNER_HOME": "/Users/worker",
            "RUNNER_DIR": "/Users/worker/actions-runner-test",
            "RUNNER_PATH": "/opt/homebrew/bin:/usr/bin:/bin",
            "STAGE_DIR": "/Users/worker/ci-runner-test",
        }
        for key, value in values.items():
            text = text.replace("@%s@" % key, value)
        return text

    def test_every_placeholder_is_one_fleet_sh_fills(self):
        with open(_TEMPLATE) as f:
            placeholders = set(re.findall(r"@([A-Z_]+)@", f.read()))
        with open(_MACOS_FLEET) as f:
            filled = set(re.findall(r"s\|@([A-Z_]+)@\|", f.read()))
        self.assertEqual(placeholders, filled)

    def test_renders_to_a_plist_register_daemon_accepts(self):
        plist = plistlib.loads(self._render().encode())
        with open(_REGISTER) as f:
            allowed = re.search(r'^ALLOWED_KEYS="([^"]+)"', f.read(), re.M).group(1).split()
        self.assertLessEqual(set(plist), set(allowed))
        self.assertEqual("worker", plist["UserName"])
        self.assertNotIn("GroupName", plist)
        self.assertTrue(plist["Label"].startswith("com.almostrealism."))

    def test_runs_the_staged_runner_with_its_env_file_and_directory(self):
        """fleet.sh reads the runner directory back from argument 3, so the
        argument order is part of its contract with the template."""
        plist = plistlib.loads(self._render().encode())
        self.assertEqual(
            ["/bin/bash", "/Users/worker/ci-runner-test/bin/runner.sh",
             "/Users/worker/ci-runner-test/runner.env", "/Users/worker/actions-runner-test"],
            plist["ProgramArguments"])
        self.assertEqual("/Users/worker/ci-runner-test", plist["WorkingDirectory"])
        self.assertEqual("/Users/worker", plist["EnvironmentVariables"]["HOME"])


if __name__ == "__main__":
    unittest.main()
