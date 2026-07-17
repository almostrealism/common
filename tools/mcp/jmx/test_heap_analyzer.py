"""Tests for heap_analyzer classpath construction and input validation."""

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parent))
import heap_analyzer
from heap_analyzer import HeapAnalyzerError


# Artifact path fragments (relative to a Maven repo root) for every runtime
# dependency the analyzer needs. Mirrors heap_analyzer._build_classpath.
_DEP_JARS = [
    "org/netbeans/modules/org-netbeans-lib-profiler/1.0/org-netbeans-lib-profiler-1.0.jar",
    "com/fasterxml/jackson/core/jackson-databind/2.0/jackson-databind-2.0.jar",
    "com/fasterxml/jackson/core/jackson-core/2.0/jackson-core-2.0.jar",
    "com/fasterxml/jackson/core/jackson-annotations/2.0/jackson-annotations-2.0.jar",
    "org/almostrealism/ar-io/0.75/ar-io-0.75.jar",
    "org/almostrealism/ar-meta/0.75/ar-meta-0.75.jar",
]


@pytest.fixture
def fake_env(tmp_path, monkeypatch):
    """A fake tools/target and ~/.m2 with every required jar present."""
    tools_target = tmp_path / "tools" / "target"
    tools_target.mkdir(parents=True)
    (tools_target / "ar-tools-0.75.jar").write_bytes(b"jar")

    m2 = tmp_path / "home" / ".m2" / "repository"
    for rel in _DEP_JARS:
        jar = m2 / rel
        jar.parent.mkdir(parents=True)
        jar.write_bytes(b"jar")

    monkeypatch.setattr(heap_analyzer, "_TOOLS_TARGET", tools_target)
    monkeypatch.setattr(heap_analyzer.Path, "home",
                        classmethod(lambda cls: tmp_path / "home"))
    return tmp_path


class TestBuildClasspath:
    def test_includes_tools_jar_and_every_dependency(self, fake_env):
        entries = heap_analyzer._build_classpath().split(":")
        assert entries[0].endswith("ar-tools-0.75.jar")
        assert len(entries) == 1 + len(_DEP_JARS)
        joined = ":".join(entries)
        for fragment in ("org-netbeans-lib-profiler", "jackson-databind",
                         "jackson-core", "jackson-annotations",
                         "ar-io", "ar-meta"):
            assert fragment in joined

    def test_missing_ar_io_raises_with_artifact_named(self, fake_env):
        ar_io = (fake_env / "home" / ".m2" / "repository"
                 / "org/almostrealism/ar-io/0.75/ar-io-0.75.jar")
        ar_io.unlink()
        with pytest.raises(HeapAnalyzerError, match="ar-io"):
            heap_analyzer._build_classpath()

    def test_missing_profiler_raises(self, fake_env):
        profiler = (fake_env / "home" / ".m2" / "repository"
                    / "org/netbeans/modules/org-netbeans-lib-profiler/1.0"
                    / "org-netbeans-lib-profiler-1.0.jar")
        profiler.unlink()
        with pytest.raises(HeapAnalyzerError, match="netbeans-lib-profiler"):
            heap_analyzer._build_classpath()

    def test_sources_and_javadoc_jars_are_ignored(self, fake_env):
        ar_io_dir = (fake_env / "home" / ".m2" / "repository"
                     / "org/almostrealism/ar-io/0.75")
        (ar_io_dir / "ar-io-0.75-sources.jar").write_bytes(b"jar")
        (ar_io_dir / "ar-io-0.75-javadoc.jar").write_bytes(b"jar")
        entries = heap_analyzer._build_classpath().split(":")
        io_entries = [e for e in entries if "ar-io" in e]
        assert io_entries == [str(ar_io_dir / "ar-io-0.75.jar")]

    def test_missing_tools_jar_raises_with_build_command(self, fake_env):
        tools_jar = fake_env / "tools" / "target" / "ar-tools-0.75.jar"
        tools_jar.unlink()
        with pytest.raises(HeapAnalyzerError, match="mvn install -pl tools"):
            heap_analyzer._build_classpath()


class TestParseAnalyzerOutput:
    def test_console_decorated_json_parses(self):
        out = '[18:57.03] {\n  "command" : "dominators",\n  "top" : 5\n}'
        parsed = heap_analyzer._parse_analyzer_output(out)
        assert parsed["command"] == "dominators"
        assert parsed["top"] == 5

    def test_undecorated_json_parses(self):
        parsed = heap_analyzer._parse_analyzer_output('{"command": "summary"}')
        assert parsed["command"] == "summary"

    def test_output_without_json_raises(self):
        with pytest.raises(HeapAnalyzerError, match="No JSON object"):
            heap_analyzer._parse_analyzer_output("Usage: histogram|dominators")

    def test_malformed_json_raises(self):
        with pytest.raises(HeapAnalyzerError, match="parse"):
            heap_analyzer._parse_analyzer_output('[18:57.03] {"broken": ')


class TestAnalyzeHeapDumpValidation:
    def test_missing_file_raises(self):
        with pytest.raises(HeapAnalyzerError, match="not found"):
            heap_analyzer.analyze_heap_dump("/nonexistent/dump.hprof")

    def test_wrong_suffix_raises(self, tmp_path):
        f = tmp_path / "dump.bin"
        f.write_bytes(b"")
        with pytest.raises(HeapAnalyzerError, match="hprof"):
            heap_analyzer.analyze_heap_dump(str(f))

    def test_invalid_mode_raises(self, tmp_path):
        f = tmp_path / "dump.hprof"
        f.write_bytes(b"")
        with pytest.raises(HeapAnalyzerError, match="Invalid mode"):
            heap_analyzer.analyze_heap_dump(str(f), mode="everything")
