"""Guard: the FlowTree agent and controller images take the JDK major as a
build arg, so a Java upgrade is one value rather than a scatter of hard-coded
pins.

The CI runner image (tools/ci/docker) already drives its JDK from an
``ARG JDK_MAJOR``; these deployment images did not, so the agent image carried
two independent Java pins (the ``eclipse-temurin`` base and the
``maven:3-eclipse-temurin`` stage it copies Maven from) and the controller image
carried one. These tests keep all of them parameterized from a single build arg
whose default is a bare major number, so re-hard-coding a major (which would
strand one of the pins behind the others on the next upgrade) fails here instead
of in a half-upgraded image.

The default stays whatever major the images ship today; this asserts the value
is a bare major number without hard-coding which one, so an intentional upgrade
needs no edit here.
"""

import os
import re
import unittest

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_AGENT = os.path.join(_REPO_ROOT, "flowtree", "runtime", "agent", "Dockerfile")
_CONTROLLER = os.path.join(_REPO_ROOT, "flowtree", "runtime", "controller", "Dockerfile")

# A temurin image/package reference pinned directly to a major, e.g.
# eclipse-temurin:17-jdk-jammy or maven:3-eclipse-temurin-17. The parameterized
# forms use ${JDK_MAJOR} in place of the digits, so any literal match is a pin
# that escaped the build arg.
_HARDCODED_TEMURIN = re.compile(r"temurin[:-]\d")


def _read(path):
    with open(path) as f:
        return f.read()


def _default_jdk_major(text):
    match = re.search(r"^ARG JDK_MAJOR=(\S+)", text, re.M)
    return match.group(1) if match else None


class FlowtreeImageJdkTest(unittest.TestCase):
    def test_both_dockerfiles_exist(self):
        """A smoke check so a move/rename cannot make the rules below pass
        vacuously by reading an absent file."""
        for path in (_AGENT, _CONTROLLER):
            with self.subTest(path=path):
                self.assertTrue(os.path.isfile(path), f"{path} must exist")

    def test_default_jdk_major_is_a_bare_major_defined_once(self):
        """Each image declares ``ARG JDK_MAJOR=<major>`` exactly once with a bare
        major number as its default, so the version has a single source of truth
        and an intentional upgrade is a one-value edit needing no change here."""
        for path in (_AGENT, _CONTROLLER):
            text = _read(path)
            with self.subTest(path=path):
                decls = re.findall(r"^ARG JDK_MAJOR=\S+", text, re.M)
                self.assertEqual(1, len(decls),
                                 f"{path} must declare ARG JDK_MAJOR with a default exactly once")
                self.assertRegex(_default_jdk_major(text), r"^\d+$",
                                 f"{path} ARG JDK_MAJOR default must be a bare major number")

    def test_no_dockerfile_hard_codes_a_temurin_major(self):
        """Every temurin reference must go through ${JDK_MAJOR}; a literal major
        (e.g. a reintroduced ``eclipse-temurin:17-jre-jammy`` or
        ``maven:3-eclipse-temurin-17``) is a pin that would not move with the
        build arg."""
        for path in (_AGENT, _CONTROLLER):
            text = _read(path)
            with self.subTest(path=path):
                hits = [line for line in text.splitlines()
                        if _HARDCODED_TEMURIN.search(line)]
                self.assertEqual([], hits,
                                 f"{path} hard-codes a temurin major; use ${{JDK_MAJOR}}: {hits}")

    def test_agent_base_and_maven_stage_both_use_the_build_arg(self):
        """The agent image's two independent Java pins — the base image and the
        Maven stage it copies Maven from — must both resolve from JDK_MAJOR, or a
        single-value upgrade would still leave one at the old major."""
        text = _read(_AGENT)
        self.assertIn("FROM eclipse-temurin:${JDK_MAJOR}-jdk-jammy", text,
                      "the agent base image must use ${JDK_MAJOR}")
        self.assertIn("maven:3-eclipse-temurin-${JDK_MAJOR}", text,
                      "the agent Maven stage must use ${JDK_MAJOR}")
        # The build arg is used in the FROM line (so it is declared globally,
        # before FROM) and again in a COPY --from within the stage (so it is
        # re-imported into the stage with a bare `ARG JDK_MAJOR`). Both must be
        # present for the Maven COPY --from expansion to resolve.
        self.assertRegex(text, r"ARG JDK_MAJOR=\d+\s+FROM eclipse-temurin",
                         "ARG JDK_MAJOR must be declared globally, immediately before FROM")
        self.assertIn("\nARG JDK_MAJOR\n", text,
                      "ARG JDK_MAJOR must be re-imported into the stage for the Maven COPY --from")

    def test_controller_base_uses_the_build_arg(self):
        text = _read(_CONTROLLER)
        self.assertIn("FROM eclipse-temurin:${JDK_MAJOR}-jre-jammy", text,
                      "the controller base image must use ${JDK_MAJOR}")


if __name__ == "__main__":
    unittest.main()
