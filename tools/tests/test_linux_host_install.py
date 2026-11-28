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
"""Tests for the native Linux host install path, ``flowtree/runtime/agent/linux``.

``install.sh`` is run for real, as an ordinary user, against a copy of the
scripts placed in a fixture tree that stands in for the repository: a fake
built runtime JAR and dependency under ``flowtree/runtime/target``, and fake
``systemctl``, ``ss``, ``mvn``, ``java``, ``docker`` and ``uname`` commands
ahead of the real ones on ``PATH``. The fake ``systemctl`` answers ``show``
from files the test writes, which is how a test decides whether the unit is
registered, where systemd loaded it from, whether it needs a daemon-reload,
and which pid it runs.

The central check mirrors the macOS installer's ``cmp -s`` guard: the unit
``install.sh`` renders must be byte-for-byte the unit systemd loaded, or the
install stops and prints the registration command. Around it are the
preconditions (env file, Maven 3.9 for the split local repository, a
reachable Docker daemon for a docker Node), the atomic JAR staging, the
restart-by-identity and the controller-connection verification.

``register-unit.sh`` runs only as root, so it is not executed here; instead
the template's directives are checked against the lists it accepts, so the
two cannot drift apart. ``smoke.sh`` is the operations end-to-end check and
is not run here beyond its argument handling.
"""

import os
import shutil
import signal
import subprocess
import tempfile
import unittest

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_AGENT_DIR = os.path.join(_REPO_ROOT, "flowtree", "runtime", "agent")
_LINUX_DIR = os.path.join(_AGENT_DIR, "linux")
_UNIT_NAME = "almostrealism-flowtree-agent.service"

# Every script this install path adds, relative to flowtree/runtime/agent.
_SCRIPTS = [
    "linux/install.sh",
    "linux/run.sh",
    "linux/register-unit.sh",
    "linux/smoke.sh",
    "host/install-lib.sh",
]

_FAKE_SYSTEMCTL = r"""#!/bin/bash
# Answers `systemctl show -p PROP ... [--value] UNIT` from ${FAKE_SYSTEMD_STATE}/PROP.
# The first MainPID read promotes MainPID.next, so a test can hand the
# installer an old process first and a replacement after it.
state="${FAKE_SYSTEMD_STATE:?}"
[ "$1" = show ] || exit 0
shift
props=()
value=false
while [ "$#" -gt 0 ]; do
    case "$1" in
        -p) props+=("$2"); shift ;;
        --value) value=true ;;
    esac
    shift
done
for p in "${props[@]}"; do
    v="$(cat "${state}/${p}" 2>/dev/null || true)"
    if [ "${p}" = MainPID ] && [ -f "${state}/MainPID.next" ]; then
        mv "${state}/MainPID.next" "${state}/MainPID"
    fi
    if [ "${value}" = true ]; then echo "${v}"; else echo "${p}=${v}"; fi
done
"""

_FAKE_SS = r"""#!/bin/bash
# Reports one established connection owned by the unit's current pid when
# ${FAKE_SYSTEMD_STATE}/connected exists.
state="${FAKE_SYSTEMD_STATE:?}"
if [ -f "${state}/connected" ]; then
    echo "0 0 10.0.0.1:50000 10.0.0.2:7766 users:((\"java\",pid=$(cat "${state}/MainPID"),fd=12))"
fi
"""

_FAKE_UNAME = r"""#!/bin/bash
if [ "$1" = -s ]; then echo Linux; else /usr/bin/uname "$@"; fi
"""

_FAKE_MVN = r"""#!/bin/bash
echo "Apache Maven ${FAKE_MVN_VERSION:-3.9.10} (fake)"
"""

_FAKE_DOCKER = r"""#!/bin/bash
[ "$1" = info ] && exit "${FAKE_DOCKER_INFO_RC:-0}"
exit 0
"""


def _write(path, text, executable=False):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        f.write(text)
    if executable:
        os.chmod(path, 0o755)


def _read(path):
    with open(path) as f:
        return f.read()


def _detached_sleep():
    """Starts a `sleep` that is not this process's child and returns its pid.

    The installer signals the agent's process and waits for it to vanish; a
    direct child of the test would linger as a zombie until reaped, and so
    look alive to `ps` for the whole wait.
    """
    out = subprocess.run(["bash", "-c", "sleep 120 >/dev/null 2>&1 & echo $!"],
                         capture_output=True, text=True, timeout=10, check=True)
    return int(out.stdout.strip())


def _pid_alive(pid):
    try:
        os.kill(pid, 0)
        return True
    except OSError:
        return False


class _InstallFixture(unittest.TestCase):
    """A repository stand-in, an install directory and a fake systemd."""

    def setUp(self):
        # install.sh refuses root, so what it does after that refusal can
        # only be observed as an ordinary user.
        if os.geteuid() == 0:
            self.skipTest("install.sh refuses to run as root")
        self.tmp = tempfile.mkdtemp(prefix="linux_host_install_")
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.pids = []
        self.addCleanup(self._kill_pids)

        repo = os.path.join(self.tmp, "repo")
        agent = os.path.join(repo, "flowtree", "runtime", "agent")
        for name in ("install.sh", "run.sh", "flowtree-agent.service"):
            dest = os.path.join(agent, "linux", name)
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            shutil.copy2(os.path.join(_LINUX_DIR, name), dest)
        os.makedirs(os.path.join(agent, "host"), exist_ok=True)
        shutil.copy2(os.path.join(_AGENT_DIR, "host", "install-lib.sh"),
                     os.path.join(agent, "host", "install-lib.sh"))
        module = os.path.join(repo, "flowtree", "runtime")
        _write(os.path.join(module, "conf", "agent.properties"), "server.port=-1\n")
        _write(os.path.join(module, "target", "ar-flowtree-runtime-1.0.jar"), "jar")
        _write(os.path.join(module, "target", "ar-flowtree-runtime-1.0-sources.jar"), "src")
        _write(os.path.join(module, "target", "dependency", "dep-1.0.jar"), "dep")
        self.install_sh = os.path.join(agent, "linux", "install.sh")

        self.home = os.path.join(self.tmp, "home")
        self.agent_home = os.path.join(self.home, "flowtree-agent")
        os.makedirs(self.agent_home)
        self.env_file = os.path.join(self.agent_home, "agent.env")
        _write(self.env_file, "FLOWTREE_ROOT_HOST=controller\nFLOWTREE_ROOT_PORT=7766\n"
                              "CLAUDE_CODE_OAUTH_TOKEN=token\n")
        self.rendered_unit = os.path.join(self.agent_home, "conf", _UNIT_NAME)

        self.state = os.path.join(self.tmp, "systemd")
        os.makedirs(self.state)
        self.registered_unit = os.path.join(self.tmp, "etc", "systemd", "system", _UNIT_NAME)
        self.set_state(LoadState="not-found")

        self.bin = os.path.join(self.tmp, "bin")
        _write(os.path.join(self.bin, "systemctl"), _FAKE_SYSTEMCTL, True)
        _write(os.path.join(self.bin, "ss"), _FAKE_SS, True)
        _write(os.path.join(self.bin, "uname"), _FAKE_UNAME, True)
        _write(os.path.join(self.bin, "mvn"), _FAKE_MVN, True)
        _write(os.path.join(self.bin, "docker"), _FAKE_DOCKER, True)
        _write(os.path.join(self.bin, "java"), "#!/bin/bash\nexit 0\n", True)

    def _kill_pids(self):
        for pid in self.pids:
            try:
                os.kill(pid, signal.SIGKILL)
            except OSError:
                pass

    def set_state(self, **props):
        for key, value in props.items():
            _write(os.path.join(self.state, key), "%s\n" % value)

    def register(self, content=None):
        """Places a unit where the fake systemd says it loaded one from."""
        _write(self.registered_unit, content if content is not None else _read(self.rendered_unit))
        self.set_state(LoadState="loaded", FragmentPath=self.registered_unit,
                       NeedDaemonReload="no", UnitFileState="enabled", MainPID="0")

    def spawn(self):
        pid = _detached_sleep()
        self.pids.append(pid)
        return pid

    def run_install(self, *args, timeout_seconds="20", **env_overrides):
        env = {
            "PATH": self.bin + os.pathsep + "/usr/bin:/bin:/usr/sbin:/sbin",
            "HOME": self.home,
            "FAKE_SYSTEMD_STATE": self.state,
            "FLOWTREE_AGENT_HOME": self.agent_home,
            "CONNECT_TIMEOUT_SECONDS": timeout_seconds,
        }
        env.update(env_overrides)
        return subprocess.run(["bash", self.install_sh, "--no-build", *args],
                              capture_output=True, text=True, env=env, timeout=90)

    def render(self):
        """Runs an install against an unregistered unit, leaving it rendered."""
        result = self.run_install()
        self.assertEqual(1, result.returncode, result.stderr)
        return result


class RenderedUnitTests(_InstallFixture):

    def test_unregistered_unit_stages_files_and_prints_registration(self):
        result = self.render()
        self.assertIn("is not registered with systemd", result.stderr)
        self.assertIn("register-unit.sh almostrealism-flowtree-agent", result.stderr)
        unit = _read(self.rendered_unit)
        self.assertNotIn("@", unit.split("[Unit]", 1)[1])
        self.assertIn("User=%s\n" % subprocess.run(["id", "-un"], capture_output=True,
                                                    text=True).stdout.strip(), unit)
        self.assertIn("ExecStart=/bin/bash %s/bin/run.sh\n" % self.agent_home, unit)
        self.assertIn("Environment=FLOWTREE_AGENT_ENV=%s\n" % self.env_file, unit)
        self.assertIn("WorkingDirectory=%s\n" % self.agent_home, unit)

    def test_jars_are_swapped_in_with_provenance(self):
        self.render()
        lib = sorted(os.listdir(os.path.join(self.agent_home, "lib")))
        self.assertEqual(["BUILD_PROVENANCE", "ar-flowtree-runtime-1.0.jar", "dep-1.0.jar"], lib)
        self.assertIn("built=false", _read(os.path.join(self.agent_home, "lib", "BUILD_PROVENANCE")))
        self.assertFalse(os.path.exists(os.path.join(self.agent_home, "lib.staging")))
        self.render()
        self.assertTrue(os.path.isdir(os.path.join(self.agent_home, "lib.previous")))
        self.assertTrue(os.access(os.path.join(self.agent_home, "bin", "run.sh"), os.X_OK))

    def test_registered_unit_that_differs_is_refused(self):
        self.render()
        self.register(_read(self.rendered_unit).replace("RestartSec=30", "RestartSec=5"))
        result = self.run_install()
        self.assertEqual(1, result.returncode)
        self.assertIn("differs from %s" % self.rendered_unit, result.stderr)
        self.assertIn("register-unit.sh", result.stderr)

    def test_unit_that_needs_daemon_reload_is_refused(self):
        self.render()
        self.register()
        self.set_state(NeedDaemonReload="yes")
        result = self.run_install()
        self.assertEqual(1, result.returncode)
        self.assertIn("changed on disk since systemd loaded it", result.stderr)

    def test_unit_not_enabled_at_boot_is_refused(self):
        self.render()
        self.register()
        self.set_state(UnitFileState="disabled")
        result = self.run_install()
        self.assertEqual(1, result.returncode)
        self.assertIn("not enabled", result.stderr)

    def test_matching_unit_with_connected_agent_succeeds(self):
        self.render()
        self.register()
        _write(os.path.join(self.state, "MainPID.next"), "%d\n" % self.spawn())
        _write(os.path.join(self.state, "connected"), "")
        result = self.run_install()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("registered but has no process", result.stdout)
        self.assertIn("connected to the controller", result.stdout)

    def test_running_agent_is_restarted_and_replacement_verified(self):
        self.render()
        self.register()
        old_pid = self.spawn()
        self.set_state(MainPID=old_pid)
        _write(os.path.join(self.state, "MainPID.next"), "%d\n" % self.spawn())
        _write(os.path.join(self.state, "connected"), "")
        result = self.run_install()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("Restarting the agent", result.stdout)
        self.assertFalse(_pid_alive(old_pid), "the old agent process was not stopped")

    def test_agent_that_never_connects_fails(self):
        self.render()
        self.register()
        _write(os.path.join(self.state, "MainPID.next"), "%d\n" % self.spawn())
        result = self.run_install(timeout_seconds="1")
        self.assertEqual(1, result.returncode)
        self.assertIn("did not connect to controller:7766", result.stderr)


class PreconditionTests(_InstallFixture):

    def test_missing_env_file_fails_before_staging(self):
        os.remove(self.env_file)
        result = self.run_install()
        self.assertEqual(1, result.returncode)
        self.assertIn("agent env file not found", result.stderr)
        self.assertFalse(os.path.exists(os.path.join(self.agent_home, "lib")))

    def test_maven_without_split_repository_support_is_refused(self):
        result = self.run_install(FAKE_MVN_VERSION="3.8.6")
        self.assertEqual(1, result.returncode)
        self.assertIn("older than 3.9", result.stderr)
        self.assertFalse(os.path.exists(os.path.join(self.agent_home, "lib")))

    def test_maven_with_split_repository_support_is_accepted(self):
        result = self.run_install(FAKE_MVN_VERSION="4.0.0")
        self.assertNotIn("older than 3.9", result.stderr)
        self.assertIn("is not registered", result.stderr)

    def test_docker_node_requires_a_reachable_daemon(self):
        _write(os.path.join(self.agent_home, "nodes.properties"),
               "nodes.0.kind=native\nnodes.1.kind=docker\n")
        result = self.run_install(FAKE_DOCKER_INFO_RC="1")
        self.assertEqual(1, result.returncode)
        self.assertIn("'docker info' fails", result.stderr)

    def test_docker_node_detection_is_case_insensitive(self):
        _write(os.path.join(self.agent_home, "nodes.properties"),
               "nodes.0.kind=native\nnodes.1.kind=Docker\n")
        result = self.run_install(FAKE_DOCKER_INFO_RC="1")
        self.assertEqual(1, result.returncode)
        self.assertIn("'docker info' fails", result.stderr)

    def test_node_list_is_appended_to_the_installed_properties(self):
        _write(os.path.join(self.agent_home, "nodes.properties"),
               "nodes.0.kind=native\nnodes.0.labels.runtime=native\n")
        self.render()
        properties = _read(os.path.join(self.agent_home, "conf", "agent.properties"))
        self.assertTrue(properties.startswith("server.port=-1\n"))
        self.assertIn("nodes.0.kind=native\nnodes.0.labels.runtime=native\n", properties)

    def test_without_a_node_list_the_module_defaults_are_installed_unchanged(self):
        self.render()
        self.assertEqual("server.port=-1\n",
                         _read(os.path.join(self.agent_home, "conf", "agent.properties")))

    def test_install_dir_unsafe_for_a_unit_is_refused(self):
        unsafe = os.path.join(self.tmp, "agent home")
        os.makedirs(unsafe)
        shutil.copy2(self.env_file, os.path.join(unsafe, "agent.env"))
        result = self.run_install(FLOWTREE_AGENT_HOME=unsafe)
        self.assertEqual(1, result.returncode)
        self.assertIn("may contain only", result.stderr)


class UnitTemplateTests(unittest.TestCase):
    """The template may only use directives register-unit.sh accepts."""

    @staticmethod
    def _allowed(name):
        for line in _read(os.path.join(_LINUX_DIR, "register-unit.sh")).splitlines():
            if line.startswith(name + '="'):
                return set(line.split('"')[1].split())
        raise AssertionError("%s not found in register-unit.sh" % name)

    def test_every_template_directive_is_accepted_by_the_registrar(self):
        allowed = {
            "[Unit]": self._allowed("ALLOWED_UNIT"),
            "[Service]": self._allowed("ALLOWED_SERVICE"),
            "[Install]": self._allowed("ALLOWED_INSTALL"),
        }
        section = None
        users = 0
        for line in _read(os.path.join(_LINUX_DIR, "flowtree-agent.service")).splitlines():
            if not line or line.startswith("#"):
                continue
            if line.startswith("["):
                self.assertIn(line, allowed)
                section = line
                continue
            key = line.split("=", 1)[0]
            self.assertIn(key, allowed[section], "%s %s" % (section, key))
            users += key == "User"
        self.assertEqual(1, users)

    def test_registrar_restricts_wants_and_after_to_the_template_target(self):
        script = _read(os.path.join(_LINUX_DIR, "register-unit.sh"))
        self.assertIn('Wants|After)', script,
                      "register-unit.sh must value-check Wants=/After=; Wants= has systemd start the named unit as root")
        accepted = set()
        for line in script.splitlines():
            stripped = line.strip()
            if stripped.startswith('[ "${value}" = "') and "network" in stripped:
                accepted.add(stripped.split('"')[3])
        template_targets = set()
        for line in _read(os.path.join(_LINUX_DIR, "flowtree-agent.service")).splitlines():
            if line.startswith(("Wants=", "After=")):
                template_targets.add(line.split("=", 1)[1])
        self.assertTrue(template_targets)
        self.assertTrue(template_targets.issubset(accepted),
                        "template Wants/After %s not all accepted by the registrar %s"
                        % (template_targets, accepted))

    def test_template_never_has_systemd_open_files_as_root(self):
        text = _read(os.path.join(_LINUX_DIR, "flowtree-agent.service"))
        for directive in ("EnvironmentFile=", "StandardOutput=", "StandardError=", "ExecStartPre="):
            self.assertNotIn("\n" + directive, text)


class ScriptHygieneTests(unittest.TestCase):

    def test_scripts_parse(self):
        for script in _SCRIPTS:
            result = subprocess.run(["bash", "-n", os.path.join(_AGENT_DIR, script)],
                                    capture_output=True, text=True, timeout=30)
            self.assertEqual(0, result.returncode, "%s: %s" % (script, result.stderr))

    def test_scripts_are_shellcheck_clean(self):
        if shutil.which("shellcheck") is None:
            self.skipTest("shellcheck is not installed on this host")
        for script in _SCRIPTS:
            path = os.path.join(_AGENT_DIR, script)
            result = subprocess.run(["shellcheck", "-x", os.path.basename(path)],
                                    cwd=os.path.dirname(path),
                                    capture_output=True, text=True, timeout=60)
            self.assertEqual(0, result.returncode, "%s:\n%s" % (script, result.stdout))

    def test_entry_points_are_executable(self):
        for script in _SCRIPTS:
            if script.startswith("linux/"):
                self.assertTrue(os.access(os.path.join(_AGENT_DIR, script), os.X_OK), script)

    def test_smoke_requires_a_workstream(self):
        result = subprocess.run(["bash", os.path.join(_LINUX_DIR, "smoke.sh")],
                                capture_output=True, text=True, timeout=30)
        self.assertEqual(1, result.returncode)
        self.assertIn("--workstream is required", result.stderr)

    def test_smoke_help_describes_it_as_operations_only(self):
        result = subprocess.run(["bash", os.path.join(_LINUX_DIR, "smoke.sh"), "--help"],
                                capture_output=True, text=True, timeout=30)
        self.assertEqual(0, result.returncode)
        self.assertIn("Operations only", result.stdout)


if __name__ == "__main__":
    unittest.main()
