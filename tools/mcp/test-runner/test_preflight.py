"""Unit tests for the ar-test-runner preflight seeding helper.

These tests cover the preflight module's three public guarantees:

1. **Pom parsing** is correct. The helper finds every direct
   ``org.almostrealism`` dependency declared in a module's
   ``pom.xml`` and resolves the version (either explicit on the
   dependency or inherited from the project version).
2. **Missing-artifact detection** is accurate. When at least one
   direct ``org.almostrealism`` dep is missing from the supplied
   repository, :func:`find_missing_upstream_artifacts` returns the
   missing entries; when all are present it returns an empty list.
3. **Seeding is idempotent and short-circuits cleanly.** The mvn
   subprocess driver is fully replaceable so the tests can run on
   any host (no real Maven required). A "seed needed" call invokes
   the driver exactly once and only when at least one artifact is
   missing; a "nothing to seed" call returns ``"skipped"`` without
   touching the driver at all.

Run from the repo root::

    python3 -m unittest tools/mcp/test-runner/test_preflight.py -v
"""

import os
import sys
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

_HERE = Path(__file__).resolve().parent
if str(_HERE) not in sys.path:
    sys.path.insert(0, str(_HERE))

import preflight  # noqa: E402


def _write_root_pom(project_root: Path, version: str = "0.74") -> Path:
    pom = project_root / "pom.xml"
    pom.write_text(
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<project xmlns="http://maven.apache.org/POM/4.0.0">\n'
        '    <modelVersion>4.0.0</modelVersion>\n'
        '    <groupId>org.almostrealism</groupId>\n'
        '    <artifactId>common</artifactId>\n'
        f'    <version>{version}</version>\n'
        '    <packaging>pom</packaging>\n'
        '</project>\n'
    )
    return pom


def _write_module_pom(project_root: Path, module: str, *, deps: list,
                      explicit_version: bool = True) -> Path:
    """Create a child module pom under ``project_root/module``.

    Args:
        deps: list of ``(group_id, artifact_id, version_or_None)``.
            When ``version_or_None`` is ``None`` no ``<version>`` is
            emitted, exercising the parent-inheritance path.
    """
    module_dir = project_root / module
    module_dir.mkdir(parents=True, exist_ok=True)
    dep_xml = []
    for group_id, artifact_id, version in deps:
        block = ["        <dependency>"]
        block.append(f"            <groupId>{group_id}</groupId>")
        block.append(f"            <artifactId>{artifact_id}</artifactId>")
        if version is not None:
            block.append(f"            <version>{version}</version>")
        block.append("        </dependency>")
        dep_xml.append("\n".join(block))
    pom = module_dir / "pom.xml"
    parent_version_line = "        <version>0.74</version>\n" if explicit_version else ""
    deps_block = "\n".join(dep_xml)
    pom.write_text(
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<project xmlns="http://maven.apache.org/POM/4.0.0">\n'
        '    <parent>\n'
        '        <groupId>org.almostrealism</groupId>\n'
        '        <artifactId>common</artifactId>\n'
        f'{parent_version_line}'
        '    </parent>\n'
        '    <modelVersion>4.0.0</modelVersion>\n'
        f'    <artifactId>ar-{module.replace("/", "-")}</artifactId>\n'
        '    <dependencies>\n'
        f'{deps_block}\n'
        '    </dependencies>\n'
        '</project>\n'
    )
    return pom


def _install_jar(repository: Path, group_id: str, artifact_id: str,
                  version: str) -> Path:
    """Create the on-disk equivalent of ``mvn install`` for one artifact."""
    base = repository.joinpath(*group_id.split("."), artifact_id, version)
    base.mkdir(parents=True, exist_ok=True)
    jar = base / f"{artifact_id}-{version}.jar"
    jar.write_bytes(b"")
    return jar


class PomParsingTests(unittest.TestCase):
    """Cover :func:`read_project_version` and :func:`read_ar_dependencies`."""

    def test_read_project_version_from_module(self):
        with TemporaryDirectory() as tmp:
            project_root = Path(tmp)
            _write_root_pom(project_root, version="9.99")
            self.assertEqual("9.99", preflight.read_project_version(
                project_root / "pom.xml"))

    def test_read_project_version_falls_back_to_parent(self):
        with TemporaryDirectory() as tmp:
            project_root = Path(tmp)
            _write_root_pom(project_root, version="9.99")
            # explicit_version=True puts <version>0.74</version> inside the
            # module's <parent> block. The module itself has no top-level
            # <version>, so the helper must read the parent's value.
            _write_module_pom(project_root, "subA",
                              deps=[], explicit_version=True)
            version = preflight.read_project_version(
                project_root / "subA/pom.xml")
            self.assertEqual("0.74", version)

    def test_read_project_version_returns_none_when_neither_module_nor_parent_has_one(self):
        with TemporaryDirectory() as tmp:
            project_root = Path(tmp)
            _write_root_pom(project_root, version="9.99")
            # explicit_version=False removes the version from the parent
            # block too. The module pom carries no version anywhere — the
            # helper must return None instead of guessing.
            _write_module_pom(project_root, "subA",
                              deps=[], explicit_version=False)
            version = preflight.read_project_version(
                project_root / "subA/pom.xml")
            self.assertIsNone(version)

    def test_read_project_version_returns_none_when_missing(self):
        with TemporaryDirectory() as tmp:
            pom = Path(tmp) / "pom.xml"
            pom.write_text(
                "<project xmlns='http://maven.apache.org/POM/4.0.0'>"
                "<modelVersion>4.0.0</modelVersion>"
                "<artifactId>foo</artifactId></project>"
            )
            self.assertIsNone(preflight.read_project_version(pom))

    def test_read_project_version_handles_unreadable_pom(self):
        # Pointing at a directory rather than a file triggers an OSError
        # which the helper must swallow into None.
        with TemporaryDirectory() as tmp:
            self.assertIsNone(preflight.read_project_version(Path(tmp)))

    def test_read_ar_dependencies_collects_direct_org_almostrealism(self):
        with TemporaryDirectory() as tmp:
            project_root = Path(tmp)
            _write_root_pom(project_root)
            _write_module_pom(project_root, "engine/utils", deps=[
                ("org.almostrealism", "ar-space", "0.74"),
                ("org.almostrealism", "ar-chemistry", "0.74"),
                ("commons-io", "commons-io", "2.11"),
                ("org.almostrealism", "ar-optimize", "0.74"),
            ])
            deps = preflight.read_ar_dependencies(
                project_root / "engine/utils/pom.xml", fallback_version="0.74")
            self.assertEqual(
                [("ar-space", "0.74"), ("ar-chemistry", "0.74"),
                 ("ar-optimize", "0.74")],
                deps,
            )

    def test_read_ar_dependencies_uses_fallback_version_when_omitted(self):
        with TemporaryDirectory() as tmp:
            project_root = Path(tmp)
            _write_root_pom(project_root)
            _write_module_pom(project_root, "engine/utils", deps=[
                ("org.almostrealism", "ar-space", None),
                ("org.almostrealism", "ar-collect", "0.99"),
            ])
            deps = preflight.read_ar_dependencies(
                project_root / "engine/utils/pom.xml", fallback_version="0.74")
            self.assertIn(("ar-space", "0.74"), deps)
            self.assertIn(("ar-collect", "0.99"), deps)

    def test_read_ar_dependencies_drops_when_no_version_resolvable(self):
        with TemporaryDirectory() as tmp:
            project_root = Path(tmp)
            _write_root_pom(project_root)
            _write_module_pom(project_root, "engine/utils", deps=[
                ("org.almostrealism", "ar-space", None),
            ])
            deps = preflight.read_ar_dependencies(
                project_root / "engine/utils/pom.xml", fallback_version=None)
            self.assertEqual([], deps)

    def test_read_ar_dependencies_deduplicates(self):
        with TemporaryDirectory() as tmp:
            project_root = Path(tmp)
            _write_root_pom(project_root)
            _write_module_pom(project_root, "engine/utils", deps=[
                ("org.almostrealism", "ar-space", "0.74"),
                ("org.almostrealism", "ar-space", "0.74"),
            ])
            deps = preflight.read_ar_dependencies(
                project_root / "engine/utils/pom.xml", fallback_version="0.74")
            self.assertEqual([("ar-space", "0.74")], deps)

    def test_read_ar_dependencies_returns_empty_on_unreadable_pom(self):
        # A non-existent path must not crash the helper.
        self.assertEqual(
            [],
            preflight.read_ar_dependencies(
                Path("/no/such/path/pom.xml"), fallback_version="0.74"),
        )


class MissingDetectionTests(unittest.TestCase):
    """Cover :func:`find_missing_upstream_artifacts`."""

    def test_no_missing_when_all_installed(self):
        with TemporaryDirectory() as tmp:
            project_root = Path(tmp) / "src"
            repository = Path(tmp) / "m2"
            project_root.mkdir()
            repository.mkdir()
            _write_root_pom(project_root)
            _write_module_pom(project_root, "engine/utils", deps=[
                ("org.almostrealism", "ar-space", "0.74"),
                ("org.almostrealism", "ar-collect", "0.74"),
            ])
            _install_jar(repository, "org.almostrealism", "ar-space", "0.74")
            _install_jar(repository, "org.almostrealism", "ar-collect", "0.74")
            missing = preflight.find_missing_upstream_artifacts(
                project_root, "engine/utils", repository=repository)
            self.assertEqual([], missing)

    def test_reports_each_missing_artifact_with_expected_path(self):
        with TemporaryDirectory() as tmp:
            project_root = Path(tmp) / "src"
            repository = Path(tmp) / "m2"
            project_root.mkdir()
            repository.mkdir()
            _write_root_pom(project_root)
            _write_module_pom(project_root, "engine/utils", deps=[
                ("org.almostrealism", "ar-space", "0.74"),
                ("org.almostrealism", "ar-collect", "0.74"),
            ])
            _install_jar(repository, "org.almostrealism", "ar-space", "0.74")
            # ar-collect is intentionally NOT installed.
            missing = preflight.find_missing_upstream_artifacts(
                project_root, "engine/utils", repository=repository)
            self.assertEqual(1, len(missing))
            self.assertEqual("ar-collect", missing[0].artifact_id)
            self.assertEqual("0.74", missing[0].version)
            self.assertEqual(
                repository / "org/almostrealism/ar-collect/0.74/ar-collect-0.74.jar",
                missing[0].expected_path,
            )

    def test_returns_empty_when_module_pom_does_not_exist(self):
        with TemporaryDirectory() as tmp:
            project_root = Path(tmp) / "src"
            repository = Path(tmp) / "m2"
            project_root.mkdir()
            repository.mkdir()
            _write_root_pom(project_root)
            # No module pom written — the helper should return [] so the
            # caller can fall back gracefully.
            missing = preflight.find_missing_upstream_artifacts(
                project_root, "nope/missing", repository=repository)
            self.assertEqual([], missing)

    def test_module_without_ar_deps_is_treated_as_complete(self):
        with TemporaryDirectory() as tmp:
            project_root = Path(tmp) / "src"
            repository = Path(tmp) / "m2"
            project_root.mkdir()
            repository.mkdir()
            _write_root_pom(project_root)
            _write_module_pom(project_root, "leaf", deps=[
                ("commons-io", "commons-io", "2.11"),
            ])
            missing = preflight.find_missing_upstream_artifacts(
                project_root, "leaf", repository=repository)
            self.assertEqual([], missing)


class SeedingTests(unittest.TestCase):
    """Cover :func:`seed_upstream_artifacts` end-to-end via a stub runner."""

    def test_returns_skipped_and_does_not_call_runner_when_nothing_missing(self):
        with TemporaryDirectory() as tmp:
            project_root = Path(tmp) / "src"
            repository = Path(tmp) / "m2"
            project_root.mkdir()
            repository.mkdir()
            _write_root_pom(project_root)
            _write_module_pom(project_root, "engine/utils", deps=[
                ("org.almostrealism", "ar-space", "0.74"),
            ])
            _install_jar(repository, "org.almostrealism", "ar-space", "0.74")

            invocations = []

            def fake_runner(cmd, cwd, writer):
                invocations.append((list(cmd), Path(cwd)))
                return 0

            result = preflight.seed_upstream_artifacts(
                project_root, "engine/utils",
                repository=repository, runner=fake_runner)
            self.assertEqual("skipped", result.action)
            self.assertEqual([], invocations,
                             "runner must not be invoked when nothing is missing")
            self.assertEqual([], result.command)
            self.assertIsNone(result.exit_code)

    def test_runs_mvn_install_when_artifact_is_missing(self):
        with TemporaryDirectory() as tmp:
            project_root = Path(tmp) / "src"
            repository = Path(tmp) / "m2"
            project_root.mkdir()
            repository.mkdir()
            _write_root_pom(project_root)
            _write_module_pom(project_root, "engine/utils", deps=[
                ("org.almostrealism", "ar-space", "0.74"),
            ])
            # ar-space is NOT installed; runner must execute.

            invocations = []
            captured_writer = []

            def fake_runner(cmd, cwd, writer):
                invocations.append((list(cmd), Path(cwd)))
                captured_writer.append(writer)
                if writer is not None:
                    writer("simulated mvn install output\n")
                return 0

            result = preflight.seed_upstream_artifacts(
                project_root, "engine/utils",
                repository=repository, runner=fake_runner)

            self.assertEqual("seeded", result.action)
            self.assertEqual(0, result.exit_code)
            self.assertEqual(1, len(invocations))
            cmd, cwd = invocations[0]
            self.assertEqual(
                ["mvn", "-pl", "engine/utils", "-am", "install",
                 "-DskipTests", "-B"],
                cmd,
            )
            self.assertEqual(project_root.resolve(), cwd.resolve())
            self.assertEqual(["ar-space"],
                             [m.artifact_id for m in result.missing])

    def test_reports_failed_action_when_runner_returns_nonzero(self):
        with TemporaryDirectory() as tmp:
            project_root = Path(tmp) / "src"
            repository = Path(tmp) / "m2"
            project_root.mkdir()
            repository.mkdir()
            _write_root_pom(project_root)
            _write_module_pom(project_root, "engine/utils", deps=[
                ("org.almostrealism", "ar-space", "0.74"),
            ])

            def fake_runner(cmd, cwd, writer):
                return 1

            result = preflight.seed_upstream_artifacts(
                project_root, "engine/utils",
                repository=repository, runner=fake_runner)
            self.assertEqual("failed", result.action)
            self.assertEqual(1, result.exit_code)
            self.assertIn("exited with code 1", result.reason)

    def test_seed_command_uses_dash_am_to_build_upstream_chain(self):
        # Sanity check: an agent reading the command should see -am (not -amd
        # or no flag at all) so the full upstream reactor is built.
        cmd = preflight.build_seed_command("flowtree/runtime")
        self.assertIn("-am", cmd)
        self.assertNotIn("-amd", cmd)
        self.assertNotIn("--also-make-dependents", cmd)
        # Tests are skipped so the seed stays fast.
        self.assertIn("-DskipTests", cmd)

    def test_output_writer_failure_does_not_propagate(self):
        # The Maven driver is wrapped so output-writer exceptions don't
        # break the seed. A pure-python regression test for that behaviour:
        with TemporaryDirectory() as tmp:
            project_root = Path(tmp) / "src"
            repository = Path(tmp) / "m2"
            project_root.mkdir()
            repository.mkdir()
            _write_root_pom(project_root)
            _write_module_pom(project_root, "engine/utils", deps=[
                ("org.almostrealism", "ar-space", "0.74"),
            ])

            def writer_that_explodes(_chunk):
                raise RuntimeError("simulated disk failure")

            def fake_runner(cmd, cwd, writer):
                # Stub a real driver: emit a chunk through the writer.
                # _default_runner swallows writer exceptions, but the test
                # runs without _default_runner so the stub must not propagate
                # the writer error itself either.
                try:
                    writer("some output\n")
                except Exception:
                    pass
                return 0

            result = preflight.seed_upstream_artifacts(
                project_root, "engine/utils",
                repository=repository, runner=fake_runner,
                output_writer=writer_that_explodes)
            self.assertEqual("seeded", result.action)


class ArtifactPathTests(unittest.TestCase):
    """Sanity-check :func:`artifact_jar_path`."""

    def test_path_layout_matches_maven_convention(self):
        path = preflight.artifact_jar_path(
            Path("/tmp/m2"), "org.almostrealism", "ar-foo", "0.74")
        self.assertEqual(
            Path("/tmp/m2/org/almostrealism/ar-foo/0.74/ar-foo-0.74.jar"),
            path,
        )

    def test_path_layout_handles_multi_segment_group(self):
        path = preflight.artifact_jar_path(
            Path("/tmp/m2"), "com.example.sub", "thing", "1.0")
        self.assertEqual(
            Path("/tmp/m2/com/example/sub/thing/1.0/thing-1.0.jar"),
            path,
        )


class ArtifactAgeReportTests(unittest.TestCase):
    """Cover :func:`find_repo_module_artifacts` and :func:`format_artifact_age_report`."""

    def _scaffold(self, tmp: str):
        """Build a sandbox repo: a pom-packaging root plus two jar modules."""
        project_root = Path(tmp)
        _write_root_pom(project_root, version="0.74")  # packaging=pom
        _write_module_pom(project_root, "base/code", deps=[])
        _write_module_pom(project_root, "engine/ml", deps=[])
        repository = project_root / ".m2" / "repository"
        return project_root, repository

    def test_find_excludes_pom_packaging_and_reports_missing(self):
        with TemporaryDirectory() as tmp:
            project_root, repository = self._scaffold(tmp)
            # Install only base/code's jar; engine/ml's is absent.
            jar = _install_jar(repository, "org.almostrealism", "ar-base-code", "0.74")
            os.utime(jar, (1_000_000, 1_000_000))

            rows = preflight.find_repo_module_artifacts(
                project_root, repository=repository)
            by_artifact = {r[1]: r for r in rows}

            # The pom-packaging root ("common") must not appear.
            self.assertNotIn("common", by_artifact)
            self.assertEqual(1_000_000, by_artifact["ar-base-code"][3])
            self.assertIsNone(by_artifact["ar-engine-ml"][3])

    def test_report_marks_module_under_test_and_sorts_oldest_first(self):
        with TemporaryDirectory() as tmp:
            project_root, repository = self._scaffold(tmp)
            old = _install_jar(repository, "org.almostrealism", "ar-base-code", "0.74")
            new = _install_jar(repository, "org.almostrealism", "ar-engine-ml", "0.74")
            os.utime(old, (1_000_000, 1_000_000))
            os.utime(new, (2_000_000, 2_000_000))

            report = preflight.format_artifact_age_report(
                project_root, "engine/ml", repository=repository)

            # The recompiled module is flagged; the dependency is not.
            self.assertRegex(report, r"\*\s+\S+ \S+\s+ar-engine-ml\s+engine/ml")
            self.assertRegex(report, r"\n\s{2}\s+\S+ \S+\s+ar-base-code\s+base/code")
            # Oldest (base/code) listed before newest (engine/ml).
            self.assertLess(report.index("ar-base-code"), report.index("ar-engine-ml"))
            # The disclaimer explains the implicit-vs-manual rebuild distinction.
            self.assertIn("recompiles ONLY engine/ml", report)
            self.assertIn("NOT rebuilt", report)

    def test_report_shows_not_installed_for_absent_jar(self):
        with TemporaryDirectory() as tmp:
            project_root, repository = self._scaffold(tmp)
            _install_jar(repository, "org.almostrealism", "ar-engine-ml", "0.74")
            # base/code intentionally not installed.

            report = preflight.format_artifact_age_report(
                project_root, "engine/ml", repository=repository)
            self.assertIn("NOT INSTALLED", report)
            # A missing dependency sorts to the very top (before installed ones).
            self.assertLess(report.index("ar-base-code"), report.index("ar-engine-ml"))


if __name__ == "__main__":
    unittest.main()
