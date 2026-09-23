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
import urllib.error
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
    "test-cl": {
        "name": "OpenCL tests",
        "needs": ["build"],
        "strategy": {"matrix": {"backend": ["rocm"]}},
    },
    "test-media": {"name": "Media ${{ matrix.group }}", "needs": ["build"]},
    "test-audio": {"name": "Audio ${{ matrix.group }}", "needs": ["build"]},
    "deploy": {"name": "Deploy controller", "needs": ["test-mac", "test-cl"]},
    "verify": {
        "uses": "./.github/workflows/verify.yaml",
        "needs": ["deploy"],
        "strategy": {"matrix": {"shard": [1, 2]}},
    },
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

    def test_a_key_match_and_a_literal_name_match_are_one_candidate_set(self):
        """A `build: {}` job and a separate `other: {name: build}` job both
        render as "build" in the jobs API and are genuinely indistinguishable
        from the name alone - a key match must not be preferred over a
        literal display-name match, since doing so would silently attribute
        `other`'s dependencies to `build` instead of reporting the
        ambiguity the documented ambiguous -> None contract promises."""
        graph = WorkflowGraph({"build": {}, "other": {"name": "build"}})
        self.assertIsNone(graph.job_key("build"))

    def test_a_key_match_with_no_colliding_literal_name_is_unambiguous(self):
        graph = WorkflowGraph({"build": {}, "other": {"name": "not-build"}})
        self.assertEqual("build", graph.job_key("build"))

    def test_a_matrix_suffixed_match_and_a_literal_name_match_are_one_candidate_set(self):
        """A matrix job `foo` (no `name:`) rendered with its matrix suffix
        and a separate literal job `name: "foo (bar)"` both produce the
        api_name "foo (bar)" - one via the stripped-suffix candidate form,
        one via the literal form. The first matching candidate form must
        not win outright: every form has to be checked before deciding, or
        this genuine ambiguity would be silently resolved to whichever form
        happens to be tried first."""
        graph = WorkflowGraph({
            "foo": {"strategy": {"matrix": {"group": ["bar"]}}},
            "collider": {"name": "foo (bar)"},
        })
        self.assertIsNone(graph.job_key("foo (bar)"))

    def test_a_matrix_suffixed_match_with_no_colliding_literal_name_is_unambiguous(self):
        graph = WorkflowGraph({"foo": {"strategy": {"matrix": {"group": ["bar"]}}}})
        self.assertEqual("foo", graph.job_key("foo (bar)"))

    def test_a_non_matrix_job_is_not_a_candidate_for_the_stripped_suffix_form(self):
        """Only an actual matrix job can render with a "(values)" suffix in
        the jobs API. A non-matrix job `foo` (no `strategy.matrix`) must not
        be treated as a candidate for the api_name "foo (bar)" just because
        stripping the suffix happens to match its key - the api_name can
        only have come from the literal job below, and reporting it as
        ambiguous would silently drop a valid queue metric."""
        graph = WorkflowGraph({
            "foo": {},
            "collider": {"name": "foo (bar)"},
        })
        self.assertEqual("collider", graph.job_key("foo (bar)"))

    def test_an_exact_match_and_a_pattern_match_are_one_candidate_set(self):
        """A literal job `name: "test ubuntu"` and a pattern job
        `name: "test ${{ matrix.os }}"` are both plausible explanations for
        the api_name "test ubuntu" - the exact tier must not win outright
        just because it is non-empty, or this genuine ambiguity would be
        silently resolved to the literal job instead of reported."""
        graph = WorkflowGraph({
            "matrix-job": {"name": "test ${{ matrix.os }}"},
            "literal-job": {"name": "test ubuntu"},
        })
        self.assertIsNone(graph.job_key("test ubuntu"))

    def test_an_exact_match_with_no_colliding_pattern_is_unambiguous(self):
        graph = WorkflowGraph({
            "matrix-job": {"name": "test ${{ matrix.os }}"},
            "literal-job": {"name": "test ubuntu"},
        })
        self.assertEqual("matrix-job", graph.job_key("test macos"))

    def test_a_literal_name_containing_a_slash_resolves_directly_without_uses(self):
        """GitHub permits an ordinary top-level job named e.g. `"build /
        test"` - that literal name must resolve on its own, not be
        misread as the `caller / inner` form of a reusable-workflow call,
        since this job never declares `uses:`."""
        graph = WorkflowGraph({"combo": {"name": "build / test", "needs": ["changes"]}})
        self.assertEqual("combo", graph.job_key("build / test"))
        key, is_inner = graph.resolve("build / test")
        self.assertEqual("combo", key)
        self.assertFalse(is_inner)

    def test_a_slash_form_with_no_reusable_caller_is_unknown(self):
        """A "caller / inner"-shaped name whose caller half does not call a
        reusable workflow (no `uses:`) must not be attributed to that
        caller - there is no called workflow whose inner job it could be."""
        graph = WorkflowGraph({"build": {}})
        self.assertIsNone(graph.job_key("build / something"))

    def test_an_ambiguous_full_name_match_does_not_fall_back_to_the_caller_form(self):
        """A "caller / inner"-shaped api_name that is ALREADY ambiguous as
        a literal full-name match must stay ambiguous, not fall through to
        the weaker caller-only interpretation and resolve to a caller job -
        the caller-split fallback is only for names with NO full-name
        evidence at all, not for names with conflicting full-name evidence."""
        graph = WorkflowGraph({
            "a": {"name": "verify / thing"},
            "b": {"name": "verify / thing"},
            "verify": {"uses": "./.github/workflows/verify.yaml"},
        })
        self.assertIsNone(graph.job_key("verify / thing"))

    def test_a_reusable_workflow_call_sets_the_inner_job_reference_flag(self):
        graph = WorkflowGraph(FIXTURE_JOBS)
        key, is_inner = graph.resolve("verify / check-completion")
        self.assertEqual("verify", key)
        self.assertTrue(is_inner)

    def test_a_direct_match_does_not_set_the_inner_job_reference_flag(self):
        graph = WorkflowGraph(FIXTURE_JOBS)
        key, is_inner = graph.resolve("verify")
        self.assertEqual("verify", key)
        self.assertFalse(is_inner)


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
        "  deploy:\n    needs: [test]\n    uses: ./.github/workflows/verify.yaml\n"
    )

    def setUp(self):
        self.requests = []

        def fetch_json(url):
            self.requests.append(url)
            if "missing" in url:
                raise urllib.error.HTTPError(url, 404, "Not Found", {}, None)
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

    @unittest.skipUnless(yaml_available(), "PyYAML is not installed")
    def test_needs_is_unknown_for_a_job_inside_a_called_reusable_workflow(self):
        """The API renders a job inside a called reusable workflow as
        "caller / inner" (see the module docstring). `WorkflowGraph.job_key`
        attributes that name to the calling job's key for other purposes
        (see its own docstring), but the inner job's own dependencies live
        in the *called* workflow file - unfetched and unparsed here - so
        reporting the caller's `needs:` would misattribute a dependency
        list the inner job never declared."""
        self.assertEqual(["test"], self.resolver.needs(self.run, {"name": "deploy"}))
        self.assertIsNone(self.resolver.needs(self.run, {"name": "deploy / check-completion"}))

    @unittest.skipUnless(yaml_available(), "PyYAML is not installed")
    def test_dependency_completed_at_is_unknown_for_a_job_inside_a_called_reusable_workflow(self):
        run_jobs = [{"name": "test (1)", "completed_at": "2026-09-21T10:20:00Z"}]
        self.assertIsNone(
            self.resolver.dependency_completed_at(self.run, {"name": "deploy / check-completion"}, run_jobs)
        )

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

    def test_a_5xx_is_retried_on_the_next_poll_instead_of_cached(self):
        """Unlike a 404 (deterministic — the file never existed at this
        commit), a 5xx is a transient failure: the same commit's workflow
        file may well be servable a moment later, so it must not be cached
        as permanently unknown."""
        run = dict(self.run, path=".github/workflows/flaky.yaml")

        def fetch_json(url):
            self.requests.append(url)
            raise urllib.error.HTTPError(url, 503, "Service Unavailable", {}, None)

        resolver = WorkflowGraphResolver("acme/repo", fetch_json)
        self.assertIsNone(resolver.needs(run, {"name": "build"}))
        self.assertIsNone(resolver.needs(run, {"name": "build"}))
        self.assertEqual(2, len(self.requests))

    def test_a_network_error_is_retried_on_the_next_poll_instead_of_cached(self):
        run = dict(self.run, path=".github/workflows/unreachable.yaml")

        def fetch_json(url):
            self.requests.append(url)
            raise urllib.error.URLError("connection refused")

        resolver = WorkflowGraphResolver("acme/repo", fetch_json)
        self.assertIsNone(resolver.needs(run, {"name": "build"}))
        self.assertIsNone(resolver.needs(run, {"name": "build"}))
        self.assertEqual(2, len(self.requests))

    def test_a_403_without_rate_limit_headers_is_cached_as_unknown(self):
        """A 403 without any rate-limit signal is a persistent permission
        failure (missing scope, revoked token) - retrying it on every job
        of every poll cycle would just repeat the same failed request
        forever, so it must be cached like a 404."""
        run = dict(self.run, path=".github/workflows/forbidden.yaml")

        def fetch_json(url):
            self.requests.append(url)
            raise urllib.error.HTTPError(url, 403, "Forbidden", {}, None)

        resolver = WorkflowGraphResolver("acme/repo", fetch_json)
        self.assertIsNone(resolver.needs(run, {"name": "build"}))
        self.assertIsNone(resolver.needs(run, {"name": "build"}))
        self.assertEqual(1, len(self.requests))

    def test_a_401_is_cached_as_unknown(self):
        run = dict(self.run, path=".github/workflows/unauthorized.yaml")

        def fetch_json(url):
            self.requests.append(url)
            raise urllib.error.HTTPError(url, 401, "Unauthorized", {}, None)

        resolver = WorkflowGraphResolver("acme/repo", fetch_json)
        self.assertIsNone(resolver.needs(run, {"name": "build"}))
        self.assertIsNone(resolver.needs(run, {"name": "build"}))
        self.assertEqual(1, len(self.requests))

    def test_a_403_with_exhausted_rate_limit_is_retried_not_cached(self):
        """A 403 carrying GitHub's rate-limit signal is transient - the
        limit resets, so it must be retried on the next poll rather than
        cached as a permanent failure."""
        run = dict(self.run, path=".github/workflows/rate-limited.yaml")

        def fetch_json(url):
            self.requests.append(url)
            raise urllib.error.HTTPError(url, 403, "Forbidden", {"X-RateLimit-Remaining": "0"}, None)

        resolver = WorkflowGraphResolver("acme/repo", fetch_json)
        self.assertIsNone(resolver.needs(run, {"name": "build"}))
        self.assertIsNone(resolver.needs(run, {"name": "build"}))
        self.assertEqual(2, len(self.requests))

    def test_a_403_with_retry_after_is_retried_not_cached(self):
        run = dict(self.run, path=".github/workflows/secondary-rate-limited.yaml")

        def fetch_json(url):
            self.requests.append(url)
            raise urllib.error.HTTPError(url, 403, "Forbidden", {"Retry-After": "30"}, None)

        resolver = WorkflowGraphResolver("acme/repo", fetch_json)
        self.assertIsNone(resolver.needs(run, {"name": "build"}))
        self.assertIsNone(resolver.needs(run, {"name": "build"}))
        self.assertEqual(2, len(self.requests))

    def test_a_429_without_rate_limit_headers_is_retried_not_cached(self):
        """A 429 is always a rate limit on the GitHub API, unlike a 403 which
        is ambiguous - so it must be retried on the next poll even without a
        Retry-After or X-RateLimit-Remaining header, matching how
        github_poller._is_retryable_rate_limit treats 429 at the transport
        layer. Caching a headerless 429 here would strand the graph as
        permanently unknown once the transport-level retry budget in
        _get_json is exhausted, even though the rate limit itself resets."""
        run = dict(self.run, path=".github/workflows/bare-429.yaml")

        def fetch_json(url):
            self.requests.append(url)
            raise urllib.error.HTTPError(url, 429, "Too Many Requests", {}, None)

        resolver = WorkflowGraphResolver("acme/repo", fetch_json)
        self.assertIsNone(resolver.needs(run, {"name": "build"}))
        self.assertIsNone(resolver.needs(run, {"name": "build"}))
        self.assertEqual(2, len(self.requests))

    def test_a_transient_failure_does_not_evict_the_cache(self):
        """A retried-not-cached outcome must not consume a slot in the
        bounded cache either, or a run of transient failures could evict
        graphs that were fetched successfully."""
        fail_next = []

        def fetch_json(url):
            self.requests.append(url)
            if fail_next:
                raise urllib.error.HTTPError(url, 503, "Service Unavailable", {}, None)
            return {"encoding": "base64", "content": base64.b64encode(self.WORKFLOW.encode("utf-8")).decode("ascii")}

        with mock.patch.object(WorkflowGraphResolver, "MAX_CACHED_GRAPHS", 1):
            resolver = WorkflowGraphResolver("acme/repo", fetch_json)
            resolver.graph(dict(self.run, head_sha="a"))
            self.assertEqual(1, len(self.requests))

            fail_next.append(True)
            resolver.graph(dict(self.run, path=".github/workflows/flaky.yaml", head_sha="b"))
            self.assertEqual(2, len(self.requests))

            fail_next.clear()
            resolver.graph(dict(self.run, head_sha="a"))
            self.assertEqual(2, len(self.requests))

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
