"""Guard: every JVM this repository launches is granted native access, and the
compiler targets the Java 17 API through ``--release``.

JDK 24 and later (JEP 472) print a restricted-method warning when code that was
not granted native access loads a native library, and a future JDK refuses the
load. The hardware backends, JOCL, ONNX Runtime and SentencePiece all load
native libraries from the classpath, so each launcher passes
``--enable-native-access=ALL-UNNAMED``. These tests fail when a launcher loses
the grant, when the grant is moved into an operator-overridable variable, or
when the surefire ``argLine`` stops composing the jacoco agent, a caller's
``-DargLine`` and the grant.
"""

import os
import re
import unittest

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_GRANT = "--enable-native-access=ALL-UNNAMED"

# Shell launchers whose ``java`` command line must carry the grant itself.
_JAVA_SCRIPTS = [
    "flowtree/runtime/agent/entrypoint.sh",
    "flowtree/runtime/agent/macos/run.sh",
    "engine/ml/scripts/run_moonbeam_inference.sh",
    "studio/compose/scripts/run-optimizer.sh",
    "studio/compose/scripts/run-optimizer-benchmark.sh",
]

# Launchers that run ``mvn exec:java``, which executes in Maven's own JVM.
_EXEC_JAVA_SCRIPTS = [
    "flowtree/runtime/bin/start-agent.sh",
    "flowtree/runtime/bin/start-controller.sh",
]

_CONTROLLER_DOCKERFILE = "flowtree/runtime/controller/Dockerfile"

# Module poms that previously repeated or overrode the compiler level.
_MODULE_POMS = [
    "base/hardware/pom.xml",
    "studio/compose/pom.xml",
    "studio/music/pom.xml",
]


def _read(relative):
    with open(os.path.join(_REPO_ROOT, relative)) as f:
        return f.read()


def _java_command_lines(text):
    """Return each ``java`` invocation as one string with continuations joined."""
    joined = re.sub(r"\\\n\s*", " ", text)
    return [line.strip() for line in joined.splitlines()
            if re.match(r"\s*(exec\s+)?java\s", line)]


def _surefire_configuration(pom):
    match = re.search(
        r"<artifactId>maven-surefire-plugin</artifactId>.*?<configuration>(.*?)</configuration>",
        pom, re.S)
    return match.group(1) if match else ""


class JavaLauncherGrantTest(unittest.TestCase):

    def test_every_java_line_carries_the_grant(self):
        for script in _JAVA_SCRIPTS:
            lines = _java_command_lines(_read(script))
            self.assertTrue(lines, "%s has no java command line" % script)
            for line in lines:
                self.assertIn(_GRANT, line, "%s: %s" % (script, line))

    def test_grant_precedes_overridable_options(self):
        # JAVA_OPTS is seeded from MAVEN_OPTS / AGENT_JVM_OPTS; an operator
        # override must not be able to drop the grant.
        for script in _JAVA_SCRIPTS[:2]:
            text = _read(script)
            for line in _java_command_lines(text):
                self.assertLess(line.index(_GRANT), line.index("${JAVA_OPTS}"),
                                "%s: %s" % (script, line))
            self.assertNotRegex(text, r"JAVA_OPTS=.*" + re.escape(_GRANT))

    def test_exec_java_scripts_grant_through_maven_opts(self):
        for script in _EXEC_JAVA_SCRIPTS:
            text = _read(script)
            grant = text.find('export MAVEN_OPTS="%s' % _GRANT)
            launch = text.find("exec mvn")
            self.assertGreaterEqual(grant, 0, script)
            self.assertLess(grant, launch, script)
            self.assertIn("${MAVEN_OPTS:-}", text, script)

    def test_controller_entrypoint_carries_the_grant(self):
        entry = re.search(r"^ENTRYPOINT \[(.*?)\]", _read(_CONTROLLER_DOCKERFILE), re.M | re.S)
        self.assertIsNotNone(entry)
        parts = [p.strip().strip('"') for p in entry.group(1).replace("\\\n", " ").split(",")]
        self.assertEqual(parts[0], "java")
        self.assertEqual(parts[1], _GRANT)


class RootPomTest(unittest.TestCase):

    def setUp(self):
        self.pom = _read("pom.xml")

    def test_surefire_arg_line_composes_jacoco_caller_and_grant(self):
        config = _surefire_configuration(self.pom)
        match = re.search(r"<argLine>(.*?)</argLine>", config, re.S)
        self.assertIsNotNone(match, "root surefire configuration has no argLine")
        arg_line = match.group(1).split()
        # The jacoco agent is substituted late; ${argLine} carries a caller's -DargLine.
        self.assertEqual(arg_line, ["@{jacocoArgLine}", "${argLine}", _GRANT])

    def test_jacoco_writes_its_own_property(self):
        # Writing into argLine would make @{argLine} replace a caller's -DargLine.
        prepare = re.search(
            r"<goal>prepare-agent</goal>.*?</goals>\s*<configuration>(.*?)</configuration>",
            self.pom, re.S)
        self.assertIsNotNone(prepare, "prepare-agent has no configuration")
        self.assertIn("<propertyName>jacocoArgLine</propertyName>", prepare.group(1))

    def test_empty_arg_line_property_defaults(self):
        properties = re.search(r"<properties>(.*?)</properties>", self.pom, re.S).group(1)
        self.assertRegex(properties, r"<argLine>\s*</argLine>")
        self.assertRegex(properties, r"<jacocoArgLine>\s*</jacocoArgLine>")

    def test_compiler_uses_release_17(self):
        compiler = re.search(
            r"<artifactId>maven-compiler-plugin</artifactId>.*?<configuration>(.*?)</configuration>",
            self.pom, re.S).group(1)
        self.assertIn("<release>17</release>", compiler)
        self.assertNotIn("<source>", compiler)
        self.assertNotIn("<target>", compiler)

    def test_modules_do_not_override_the_compiler_level(self):
        for pom in _MODULE_POMS:
            text = _read(pom)
            self.assertNotRegex(text, r"<(source|target)>\d+</(source|target)>", pom)


if __name__ == "__main__":
    unittest.main()
