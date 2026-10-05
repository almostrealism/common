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

import os
import platform
import plistlib
import re
import subprocess
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
        self.assertIn('if sudo test -e "${fleet_python}" -o -L "${fleet_python}"; then', install,
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
        self.assertIn('if sudo test -e "${fleet_pip}" -o -L "${fleet_pip}"; then', install,
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

    def test_store_from_is_validated_as_user_at_host_before_it_is_forwarded(self):
        """install.sh hands "${STORE_FROM}:fleet/store-url" to scp as the
        administrator, and scp reads a leading-dash operand (e.g.
        -oProxyCommand=...) as an option that runs a command. install must hold
        STORE_FROM to a strict USER@HOST, with no leading dash, before it is
        forwarded to the monitor install."""
        install = re.search(r"^cmd_install\(\) \{.*?^\}", self.src, re.M | re.S).group(0)
        check = install.find(
            '[[ "${STORE_FROM}" =~ ^[A-Za-z0-9._][A-Za-z0-9._-]*@[A-Za-z0-9._-]+$ ]]')
        self.assertNotEqual(-1, check,
                            "install must match STORE_FROM against a strict USER@HOST pattern")
        run = install.find('"${MONITOR_INSTALL}" ${STORE_FROM')
        self.assertNotEqual(-1, run)
        self.assertLess(check, run,
                        "STORE_FROM must be validated before it is forwarded to install.sh")

    def test_the_monitor_home_write_access_is_probed_before_registration(self):
        """A trusted FLEET_HOME path can still be root-owned or read-only, which
        passes the ancestor walks but fails render.sh's mkdir -p after the runner
        is already registered. install must probe effective write and search
        access on the home (or the nearest existing ancestor) before registering
        the runner."""
        install = re.search(r"^cmd_install\(\) \{.*?^\}", self.src, re.M | re.S).group(0)
        self.assertIn('fleet_write_at="$(nearest_existing_dir "${fleet_home}")"', install,
                      "install must find the nearest existing directory of FLEET_HOME")
        probe = install.find('test -w "${fleet_write_at}" && test -x "${fleet_write_at}"')
        self.assertNotEqual(-1, probe,
                            "install must probe write and search access on FLEET_HOME")
        self.assertLess(probe, install.find('sudo "${REGISTER_SCRIPT}"'),
                        "FLEET_HOME write access must be probed before the runner is registered")

    def test_the_cpu_watcher_is_launched_by_absolute_interpreter(self):
        """runner.sh runs under the daemon's RUNNER_PATH, which install screens
        for the job's tools but need not contain the directory bash lives in.
        Resolving cpu-watcher.sh through its own `/usr/bin/env bash` shebang could
        leave the watcher unstarted and the CPU limit silently unenforced, so the
        watcher must be launched with /bin/bash by absolute path."""
        with open(os.path.join(_REPO_ROOT, "tools", "ci", "macos", "runner.sh")) as f:
            runner = f.read()
        self.assertIn('/bin/bash "${SCRIPT_DIR}/cpu-watcher.sh" $$ "${LIMIT_PCT}" &', runner,
                      "the CPU watcher must be launched with /bin/bash by absolute path")
        self.assertIsNone(
            re.search(r'^\s*"\$\{SCRIPT_DIR\}/cpu-watcher\.sh" \$\$', runner, re.M),
            "the watcher must not be launched through its own PATH-resolved shebang")

    def test_the_path_is_anchored_to_the_system_directories_before_any_command(self):
        """The installer resolves sudo, dscl, find, cp, launchctl, plutil and
        the rest by name, and runs as the administrator. If a directory another
        account can write sits earlier on the administrator's inherited PATH, it
        could shadow one of those commands and run in the administrator's
        context. Both entry points must prepend the system directories to PATH
        before they resolve any command, so the system binaries always come from
        a trusted location."""
        for script in (_FLEET, _MACOS_FLEET):
            with self.subTest(script=os.path.basename(script)):
                with open(script) as f:
                    src = f.read()
                anchor = 'PATH="/usr/bin:/bin:/usr/sbin:/sbin:${PATH}"'
                self.assertIn(anchor, src,
                              "PATH must be anchored to the system directories")
                anchor_at = src.index(anchor)
                # Nothing may resolve a command through the inherited PATH first:
                # the first external command either script runs is in SCRIPT_DIR's
                # `$(dirname ...)`, so the anchor must precede that.
                script_dir = src.index("SCRIPT_DIR=")
                self.assertLess(anchor_at, script_dir,
                                "PATH must be anchored before the first command runs")

    def test_the_monitor_python_tree_is_screened_before_the_monitor_install(self):
        """install.sh runs `python3 -m tools.fleet.collector` and
        `tools.fleet.cli` with PYTHONPATH=${CHECKOUT} as the administrator, so a
        runner-writable module anywhere under tools/fleet is a code path that
        runs as them. Screening install.sh and render.sh is not enough; the
        importable tree must be scanned too, before the monitor install runs."""
        install = re.search(r"^cmd_install\(\) \{.*?^\}", self.src, re.M | re.S).group(0)
        self.assertIn('untrusted_tool_tree "${admin_user}" "${CHECKOUT}/tools/fleet"', install,
                      "the tools/fleet Python tree must be screened with untrusted_tool_tree")
        scan = install.find('untrusted_tool_tree "${admin_user}" "${CHECKOUT}/tools/fleet"')
        run = install.find('"${MONITOR_INSTALL}" ${STORE_FROM:+')
        self.assertNotEqual(-1, run, "the monitor install invocation must be present")
        self.assertLess(scan, run, "the Python tree must be screened before the monitor install runs")


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
