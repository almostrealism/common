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
"""Tests for ``tools.fleet.workflow_graph``.

The poller can only tell runner-availability wait from time blocked behind
an upstream job if it knows each job's ``needs:``, and it can only know that
if it maps the display name the jobs API reports back to the YAML key that
declared it. These tests pin that mapping rule by rule, and pin that an
ambiguous or unknown name is reported as unknown rather than guessed.

Run with:
    python -m unittest discover -v -s tools/tests -p "test_fleet_workflow_graph.py"
"""

import base64
import builtins
import os
import unittest
from unittest import mock

from tools.fleet.workflow_graph import WorkflowGraph, WorkflowGraphResolver, yaml_available

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))

FIXTURE_JOBS = {
    "changes": {},
    "build": {"needs": "changes"},
    "test-mac": {
        "needs": ["changes", "build"],
        "strategy": {"matrix": {"group": [1, 2, 3]}},
    },
    "test-cl": {"name": "OpenCL tests", "needs": ["build"]},
    "test-media": {"name": "Media ${{ matrix.group }}", "needs": ["build"]},
    "test-audio": {"name": "Audio ${{ matrix.group }}", "needs": ["build"]},
    "deploy": {"name": "Deploy controller", "needs": ["test-mac", "test-cl"]},
    "verify": {"uses": "./.github/workflows/verify.yaml", "needs": ["deploy"]},
}


class JobKeyTests(unittest.TestCase):
    """`WorkflowGraph.job_key` inverts the API's job-name rendering rules."""

    def setUp(self):
        self.graph = WorkflowGraph(FIXTURE_JOBS)

    def test_a_job_without_a_name_is_reported_by_its_key(self):
        self.assertEqual("build", self.graph.job_key("build"))

    def test_a_literal_name_maps_back_to_its_key(self):
        self.assertEqual("test-cl", self.graph.job_key("OpenCL tests"))

    def test_a_matrix_suffix_is_removed_before_matching_a_key(self):
        self.assertEqual("test-mac", self.graph.job_key("test-mac (2)"))
        self.assertEqual("test-mac", self.graph.job_key("test-mac (2, metal)"))

    def test_a_matrix_suffix_is_removed_before_matching_a_literal_name(self):
        self.assertEqual("test-cl", self.graph.job_key("OpenCL tests (rocm)"))

    def test_a_reusable_workflow_call_is_attributed_to_the_calling_job(self):
        self.assertEqual("verify", self.graph.job_key("verify / check-completion"))
        self.assertEqual("verify", self.graph.job_key("verify (1) / check-completion (a)"))

    def test_an_expression_in_the_name_matches_as_a_pattern(self):
        self.assertEqual("test-media", self.graph.job_key("Media 4"))
        self.assertEqual("test-audio", self.graph.job_key("Audio wav"))

    def test_an_expression_name_with_a_matrix_suffix_still_matches(self):
        self.assertEqual("test-media", self.graph.job_key("Media 4 (linux)"))

    def test_an_unknown_name_is_none_not_a_guess(self):
        self.assertIsNone(self.graph.job_key("something else entirely"))
        self.assertIsNone(self.graph.job_key(""))

    def test_an_ambiguous_pattern_match_is_none(self):
        graph = WorkflowGraph({
            "a": {"name": "run ${{ matrix.x }}"},
            "b": {"name": "run ${{ matrix.y }} again"},
            "c": {"name": "${{ matrix.z }}"},
        })
        # "run 1 again" fits both `a` (x = "1 again") and `b` (y = "1"),
        # and `c` fits everything: nothing should be attributed.
        self.assertIsNone(graph.job_key("run 1 again"))

    def test_a_duplicate_literal_name_is_none(self):
        graph = WorkflowGraph({"a": {"name": "same"}, "b": {"name": "same"}})
        self.assertIsNone(graph.job_key("same"))

    def test_a_key_match_beats_a_literal_name_match(self):
        graph = WorkflowGraph({"build": {}, "other": {"name": "build"}})
        self.assertEqual("build", graph.job_key("build"))


class NeedsTests(unittest.TestCase):

    def setUp(self):
        self.graph = WorkflowGraph(FIXTURE_JOBS)

    def test_an_entry_point_has_an_empty_needs_list(self):
        self.assertEqual([], self.graph.needs("changes"))

    def test_a_string_needs_is_a_one_element_list(self):
        self.assertEqual(["changes"], self.graph.needs("build"))

    def test_a_list_needs_is_preserved_in_order(self):
        self.assertEqual(["changes", "build"], self.graph.needs("test-mac"))

    def test_an_unknown_key_has_no_needs(self):
        self.assertIsNone(self.graph.needs("nope"))

    def test_keys_are_in_file_order(self):
        self.assertEqual(list(FIXTURE_JOBS), self.graph.keys)

    def test_display_name_keeps_the_expression_intact(self):
        self.assertEqual("Media ${{ matrix.group }}", self.graph.display_name("test-media"))
        self.assertEqual("build", self.graph.display_name("build"))


@unittest.skipUnless(yaml_available(), "PyYAML is not installed")
class FromYamlTests(unittest.TestCase):

    def test_parses_jobs_from_workflow_text(self):
        graph = WorkflowGraph.from_yaml(
            "name: ci\non: [push]\njobs:\n  a:\n    runs-on: ubuntu-latest\n  b:\n    needs: a\n    name: Bee\n"
        )
        self.assertEqual(["a", "b"], graph.keys)
        self.assertEqual(["a"], graph.needs("b"))
        self.assertEqual("b", graph.job_key("Bee"))

    def test_a_document_without_jobs_is_an_empty_graph(self):
        self.assertEqual([], WorkflowGraph.from_yaml("name: nothing\n").keys)
        self.assertEqual([], WorkflowGraph.from_yaml("").keys)
        self.assertEqual([], WorkflowGraph.from_yaml("- just\n- a list\n").keys)

    def test_the_repository_workflows_parse_and_every_needs_names_a_declared_job(self):
        """A smoke test against the real files the production poller will
        read: every `needs:` entry must be a key in the same file, or the
        dependency-completion lookup could never find the upstream job."""
        workflows = os.path.join(REPO_ROOT, ".github", "workflows")
        checked = 0
        for filename in sorted(os.listdir(workflows)):
            if not filename.endswith((".yaml", ".yml")):
                continue
            with open(os.path.join(workflows, filename), encoding="utf-8") as handle:
                graph = WorkflowGraph.from_yaml(handle.read())
            for key in graph.keys:
                for needed in graph.needs(key):
                    self.assertIn(needed, graph.keys, "%s: %s needs undeclared %s" % (filename, key, needed))
            checked += 1
        self.assertGreater(checked, 0)


class ResolverTests(unittest.TestCase):
    """`WorkflowGraphResolver` fetches one file per (path, sha) and answers the poller's two callbacks."""

    WORKFLOW = (
        "jobs:\n"
        "  changes: {}\n"
        "  build:\n    needs: changes\n"
        "  test:\n    needs: [build]\n    strategy: {matrix: {g: [1, 2]}}\n"
        "  deploy:\n    needs: [test]\n"
    )

    def setUp(self):
        self.requests = []

        def fetch_json(url):
            self.requests.append(url)
            if "missing" in url:
                raise RuntimeError("HTTP Error 404: Not Found")
            return {"encoding": "base64", "content": base64.b64encode(self.WORKFLOW.encode("utf-8")).decode("ascii")}

        self.resolver = WorkflowGraphResolver("acme/repo", fetch_json)
        self.run = {"id": 1, "path": ".github/workflows/ci.yaml", "head_sha": "abc123def456789"}

    @unittest.skipUnless(yaml_available(), "PyYAML is not installed")
    def test_fetches_the_workflow_file_at_the_run_commit(self):
        self.assertEqual([], self.resolver.needs(self.run, {"name": "changes"}))
        self.assertEqual(
            ["https://api.github.com/repos/acme/repo/contents/.github/workflows/ci.yaml?ref=abc123def456789"],
            self.requests,
        )

    @unittest.skipUnless(yaml_available(), "PyYAML is not installed")
    def test_one_fetch_serves_every_job_of_the_same_file_and_commit(self):
        self.resolver.needs(self.run, {"name": "changes"})
        self.resolver.needs(self.run, {"name": "build"})
        self.resolver.needs(dict(self.run, id=2), {"name": "test (1)"})
        self.assertEqual(1, len(self.requests))
        self.resolver.needs(dict(self.run, head_sha="other"), {"name": "build"})
        self.assertEqual(2, len(self.requests))

    @unittest.skipUnless(yaml_available(), "PyYAML is not installed")
    def test_needs_follow_the_graph(self):
        self.assertEqual(["changes"], self.resolver.needs(self.run, {"name": "build"}))
        self.assertEqual(["build"], self.resolver.needs(self.run, {"name": "test (2)"}))
        self.assertIsNone(self.resolver.needs(self.run, {"name": "unknown job"}))

    def test_a_run_without_a_path_or_commit_is_unknown(self):
        self.assertIsNone(self.resolver.needs({"id": 1}, {"name": "build"}))
        self.assertEqual([], self.requests)

    def test_an_unavailable_file_is_unknown_and_fetched_only_once(self):
        run = dict(self.run, path=".github/workflows/missing.yaml")
        self.assertIsNone(self.resolver.needs(run, {"name": "build"}))
        self.assertIsNone(self.resolver.needs(run, {"name": "changes"}))
        self.assertEqual(1, len(self.requests))

    def test_the_cache_is_bounded_and_drops_the_oldest_file_first(self):
        with mock.patch.object(WorkflowGraphResolver, "MAX_CACHED_GRAPHS", 2):
            for sha in ("a", "b", "c"):
                self.resolver.graph(dict(self.run, head_sha=sha))
            self.assertEqual(3, len(self.requests))
            self.resolver.graph(dict(self.run, head_sha="c"))
            self.resolver.graph(dict(self.run, head_sha="b"))
            self.assertEqual(3, len(self.requests))
            self.resolver.graph(dict(self.run, head_sha="a"))
            self.assertEqual(4, len(self.requests))

    def test_a_response_without_content_is_unknown(self):
        resolver = WorkflowGraphResolver("acme/repo", lambda url: {"message": "too large"})
        self.assertIsNone(resolver.needs(self.run, {"name": "build"}))

    @unittest.skipUnless(yaml_available(), "PyYAML is not installed")
    def test_dependency_completed_at_is_the_latest_upstream_completion(self):
        run_jobs = [
            {"name": "changes", "completed_at": "2026-09-21T10:00:00Z"},
            {"name": "build", "completed_at": "2026-09-21T10:05:00Z"},
            {"name": "test (1)", "completed_at": "2026-09-21T10:20:00Z"},
            {"name": "test (2)", "completed_at": "2026-09-21T10:30:00Z"},
            {"name": "deploy", "completed_at": None},
        ]
        self.assertEqual(
            "2026-09-21T10:30:00Z",
            self.resolver.dependency_completed_at(self.run, run_jobs[4], run_jobs),
        )
        self.assertEqual(
            "2026-09-21T10:05:00Z",
            self.resolver.dependency_completed_at(self.run, run_jobs[2], run_jobs),
        )

    @unittest.skipUnless(yaml_available(), "PyYAML is not installed")
    def test_dependency_completed_at_is_unknown_for_an_entry_point(self):
        run_jobs = [{"name": "changes", "completed_at": "2026-09-21T10:00:00Z"}]
        self.assertIsNone(self.resolver.dependency_completed_at(self.run, run_jobs[0], run_jobs))

    @unittest.skipUnless(yaml_available(), "PyYAML is not installed")
    def test_dependency_completed_at_is_unknown_while_an_upstream_job_is_unfinished(self):
        run_jobs = [
            {"name": "build", "completed_at": "2026-09-21T10:05:00Z"},
            {"name": "test (1)", "completed_at": "2026-09-21T10:20:00Z"},
            {"name": "test (2)", "completed_at": None},
            {"name": "deploy"},
        ]
        self.assertIsNone(self.resolver.dependency_completed_at(self.run, run_jobs[3], run_jobs))

    @unittest.skipUnless(yaml_available(), "PyYAML is not installed")
    def test_dependency_completed_at_is_unknown_when_an_upstream_job_is_absent(self):
        run_jobs = [{"name": "build", "completed_at": "2026-09-21T10:05:00Z"}, {"name": "deploy"}]
        self.assertIsNone(self.resolver.dependency_completed_at(self.run, run_jobs[1], run_jobs))

    def test_dependency_completed_at_is_unknown_without_a_graph(self):
        self.assertIsNone(self.resolver.dependency_completed_at({"id": 1}, {"name": "deploy"}, []))


class YamlAvailableTests(unittest.TestCase):

    def test_reports_a_boolean(self):
        self.assertIn(yaml_available(), (True, False))

    def test_from_yaml_names_the_missing_package(self):
        real_import = builtins.__import__

        def no_yaml(name, *args, **kwargs):
            if name == "yaml":
                raise ImportError("no yaml")
            return real_import(name, *args, **kwargs)

        with mock.patch.object(builtins, "__import__", side_effect=no_yaml):
            self.assertFalse(yaml_available())
            with self.assertRaises(RuntimeError) as raised:
                WorkflowGraph.from_yaml("jobs: {}")
        self.assertIn("PyYAML", str(raised.exception))


if __name__ == "__main__":
    unittest.main()
