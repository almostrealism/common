"""Tests for ``github_run_artifacts``, the workflow-run artifact reader.

A native crash on CI leaves its cause in the ``crash-reports-*`` artifact, so
the cases that matter are: listing a run's artifacts, reading a named file
inside the zip archive, the limits on what is downloaded and decompressed,
and the scope gate that keeps a token inside its own repositories.
"""

import base64
import io
import json
import os
import sys
import unittest
import zipfile
import zlib
from unittest.mock import MagicMock, patch

_MANAGER_DIR = os.path.dirname(os.path.abspath(__file__))
if _MANAGER_DIR not in sys.path:
    sys.path.insert(0, _MANAGER_DIR)

from manager_test_support import (  # noqa: E402
    server, _grant_all_scopes, _grant_scopes, _set_workspaces,
    _clear_workspaces, _reset_workspace_cache,
)

_DUMPSTREAM = ("Corrupted channel by directly writing to native stream in forked JVM 1. "
               "Stream '#  SIGSEGV (0xb) at pc=0x00007f3a1c49eb2c'.\n")
_HS_ERR = "# A fatal error has been detected by the Java Runtime Environment:\n"


def _archive(members: dict) -> str:
    """Build a zip archive of ``members`` and return it base64-encoded."""
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, text in members.items():
            archive.writestr(name, text)
    return base64.b64encode(buffer.getvalue()).decode("ascii")


def _listing(*artifacts):
    """A GitHub run-artifacts listing holding the given (id, name, size) triples."""
    return {"total_count": len(artifacts),
            "artifacts": [{"id": i, "name": n, "size_in_bytes": s, "expired": False,
                           "created_at": "2026-11-25T10:00:00Z"}
                          for i, n, s in artifacts]}


def _download(members: dict) -> dict:
    """The proxy's answer to an artifact download: the archive, base64-encoded."""
    return {"ok": True, "status": 200, "content_base64": _archive(members)}


_REPO = ("owner", "repo", "master", None)


class TestGithubRunArtifacts(unittest.TestCase):
    """Listing and fetching through the tool, with the proxy mocked."""

    def setUp(self):
        _grant_all_scopes()

    @patch.object(server, "_resolve_github_repo", return_value=_REPO)
    @patch.object(server.github_api, "_github_request")
    def test_lists_the_runs_artifacts(self, mock_gh, mock_repo):
        mock_gh.return_value = _listing((11, "crash-reports-cpu", 900),
                                        (12, "surefire-render", 4000))
        result = server.github_run_artifacts(run_id=55)
        self.assertTrue(result["ok"])
        self.assertEqual(["crash-reports-cpu", "surefire-render"],
                         [a["name"] for a in result["artifacts"]])
        self.assertEqual(900, result["artifacts"][0]["size_in_bytes"])
        self.assertEqual("/repos/owner/repo/actions/runs/55/artifacts?per_page=100&page=1",
                         mock_gh.call_args[0][1])
        mock_gh.assert_called_once()

    @patch.object(server, "_resolve_github_repo", return_value=_REPO)
    @patch.object(server.github_api, "_github_request")
    def test_follows_pagination_to_list_all_artifacts(self, mock_gh, mock_repo):
        # A run with more artifacts than a page holds: the later pages must be
        # fetched, or a matching artifact on page 2 would be invisible.
        page_one = _listing((1, "crash-reports-cpu", 10), (2, "surefire-a", 10))
        page_one["total_count"] = 3
        page_two = _listing((3, "surefire-b", 10))
        page_two["total_count"] = 3
        mock_gh.side_effect = [page_one, page_two]
        result = server.github_run_artifacts(run_id=55)
        self.assertTrue(result["ok"], result)
        self.assertEqual(["crash-reports-cpu", "surefire-a", "surefire-b"],
                         [a["name"] for a in result["artifacts"]])
        self.assertEqual(3, result["total_count"])
        self.assertEqual(2, mock_gh.call_count)
        self.assertIn("page=2", mock_gh.call_args_list[1][0][1])

    @patch.object(server, "_resolve_github_repo", return_value=_REPO)
    @patch.object(server.github_api, "_github_request")
    def test_fetches_a_named_file_inside_the_archive(self, mock_gh, mock_repo):
        mock_gh.side_effect = [
            _listing((11, "crash-reports-cpu", 900), (12, "surefire-render", 4000)),
            _download({"crash-summary.txt": "summary\n",
                       "dumpstreams/engine-jvmRun1.dumpstream": _DUMPSTREAM,
                       "hs_err/hs_err_pid4242.log": _HS_ERR}),
        ]
        result = server.github_run_artifacts(run_id=55, artifact="crash-reports-*",
                                              file="*.dumpstream")
        self.assertTrue(result["ok"], result)
        self.assertEqual("crash-reports-cpu", result["artifact"]["name"])
        self.assertEqual("/repos/owner/repo/actions/artifacts/11/zip",
                         mock_gh.call_args_list[1][0][1])
        self.assertEqual(1, len(result["files"]))
        entry = result["files"][0]
        self.assertEqual("dumpstreams/engine-jvmRun1.dumpstream", entry["path"])
        self.assertEqual(_DUMPSTREAM, entry["content"])
        self.assertFalse(entry["truncated"])
        self.assertFalse(result["truncated"])
        # Every member is listed, so a caller can choose another file.
        self.assertEqual(3, result["member_count"])

    @patch.object(server, "_resolve_github_repo", return_value=_REPO)
    @patch.object(server.github_api, "_github_request")
    def test_a_base_name_selects_the_member(self, mock_gh, mock_repo):
        mock_gh.side_effect = [_listing((11, "crash-reports-cpu", 900)),
                               _download({"hs_err/hs_err_pid4242.log": _HS_ERR})]
        result = server.github_run_artifacts(run_id=55, artifact="crash-reports-cpu",
                                              file="hs_err_pid4242.log")
        self.assertEqual(_HS_ERR, result["files"][0]["content"])

    @patch.object(server, "_resolve_github_repo", return_value=_REPO)
    @patch.object(server.github_api, "_github_request")
    def test_extracted_bytes_are_capped(self, mock_gh, mock_repo):
        # Five megabytes that compress to a few kilobytes: the cap must bound
        # what is decompressed, not only what is returned.
        big = "a" * (5 * 1024 * 1024)
        mock_gh.side_effect = [_listing((11, "crash-reports-cpu", 9000)),
                               _download({"one.log": big, "two.log": "second\n"})]
        result = server.github_run_artifacts(run_id=55, artifact="crash-reports-cpu",
                                              max_bytes=100)
        self.assertTrue(result["ok"], result)
        first, second = result["files"]
        self.assertEqual(100, first["returned_bytes"])
        self.assertEqual("a" * 100, first["content"])
        self.assertTrue(first["truncated"])
        self.assertTrue(second["omitted"])
        self.assertNotIn("content", second)
        self.assertTrue(result["truncated"])

    @patch.object(server, "_resolve_github_repo", return_value=_REPO)
    @patch.object(server.github_api, "_github_request")
    def test_max_bytes_is_clamped_to_the_limit(self, mock_gh, mock_repo):
        big = "b" * (3 * 1024 * 1024)
        mock_gh.side_effect = [_listing((11, "crash-reports-cpu", 9000)),
                               _download({"big.log": big})]
        result = server.github_run_artifacts(run_id=55, artifact="crash-reports-cpu",
                                              max_bytes=50 * 1024 * 1024)
        limit = server._GITHUB_READ_FILE_SIZE_LIMIT
        self.assertEqual(limit, result["max_bytes"])
        self.assertEqual(limit, result["files"][0]["returned_bytes"])

    @patch.object(server, "_resolve_github_repo", return_value=_REPO)
    @patch.object(server.github_api, "_github_request")
    def test_oversized_archive_is_not_downloaded(self, mock_gh, mock_repo):
        mock_gh.return_value = _listing((11, "coverage-cpu", 40 * 1024 * 1024))
        result = server.github_run_artifacts(run_id=55, artifact="coverage-cpu")
        self.assertFalse(result["ok"])
        self.assertIn("download limit", result["error"])
        mock_gh.assert_called_once()

    @patch.object(server, "_resolve_github_repo", return_value=_REPO)
    @patch.object(server.github_api, "_github_request")
    def test_ambiguous_artifact_is_refused(self, mock_gh, mock_repo):
        mock_gh.return_value = _listing((11, "crash-reports-cpu", 900),
                                        (12, "crash-reports-media", 900))
        result = server.github_run_artifacts(run_id=55, artifact="crash-reports-*")
        self.assertFalse(result["ok"])
        self.assertEqual(["crash-reports-cpu", "crash-reports-media"], result["matches"])
        mock_gh.assert_called_once()

    @patch.object(server, "_resolve_github_repo", return_value=_REPO)
    @patch.object(server.github_api, "_github_request")
    def test_missing_file_lists_the_members(self, mock_gh, mock_repo):
        mock_gh.side_effect = [_listing((11, "crash-reports-cpu", 900)),
                               _download({"crash-summary.txt": "summary\n"})]
        result = server.github_run_artifacts(run_id=55, artifact="crash-reports-cpu",
                                              file="*.dumpstream")
        self.assertFalse(result["ok"])
        self.assertEqual(["crash-summary.txt"], result["members"])

    @patch.object(server, "_resolve_github_repo", return_value=_REPO)
    @patch.object(server.github_api, "_github_request")
    def test_unreadable_member_is_reported_not_raised(self, mock_gh, mock_repo):
        # An encrypted, unsupported or corrupt member raises on open/read; the
        # whole read must not abort, so the entry carries an error instead.
        mock_gh.side_effect = [_listing((11, "crash-reports-cpu", 900)),
                               _download({"good.log": "ok\n", "bad.log": "x\n"})]
        real_open = zipfile.ZipFile.open

        def fake_open(self, name, *args, **kwargs):
            member = name.filename if isinstance(name, zipfile.ZipInfo) else name
            if member == "bad.log":
                raise zlib.error("Error -3 while decompressing data")
            return real_open(self, name, *args, **kwargs)

        with patch.object(zipfile.ZipFile, "open", fake_open):
            result = server.github_run_artifacts(run_id=55, artifact="crash-reports-cpu")
        self.assertTrue(result["ok"], result)
        by_path = {f["path"]: f for f in result["files"]}
        self.assertEqual("ok\n", by_path["good.log"]["content"])
        self.assertIn("decompressing", by_path["bad.log"]["error"])
        self.assertNotIn("content", by_path["bad.log"])
        self.assertTrue(result["truncated"])

    def test_requires_run_id(self):
        result = server.github_run_artifacts(run_id=0)
        self.assertFalse(result["ok"])
        self.assertIn("run_id", result["error"])


class TestGithubRunArtifactsScope(unittest.TestCase):
    """The tool is gated like the other ``github_*`` read tools."""

    def setUp(self):
        _grant_all_scopes()
        _clear_workspaces()
        _reset_workspace_cache()

    def tearDown(self):
        _clear_workspaces()
        _reset_workspace_cache()

    @patch.object(server.github_api, "_github_request")
    def test_requires_github_scope(self, mock_gh):
        _grant_scopes("read", "write")
        with self.assertRaises(PermissionError):
            server.github_run_artifacts(run_id=55)
        mock_gh.assert_not_called()

    @patch.object(server.github_api, "_github_request")
    @patch.object(server, "_controller_get")
    def test_scoped_token_cannot_reach_another_workspaces_repo(self, mock_get, mock_gh):
        mock_get.return_value = [
            {"workstreamId": "w-1", "slackWorkspaceId": "TAAA",
             "repoUrl": "git@github.com:almostrealism/common.git"},
            {"workstreamId": "w-2", "slackWorkspaceId": "TBBB",
             "repoUrl": "https://github.com/other-org/private.git"},
        ]
        _set_workspaces("TAAA")
        with self.assertRaises(PermissionError):
            server.github_run_artifacts(run_id=55, artifact="crash-reports-cpu",
                                        org="other-org", repo="private")
        mock_gh.assert_not_called()


class TestProxyBinaryBody(unittest.TestCase):
    """The proxy envelope's base64 form reaches the caller undecoded as text."""

    @patch.object(server.github_api, "urlopen")
    def test_base64_envelope_is_returned_as_content_base64(self, mock_open):
        payload = base64.b64encode(b"PK\x03\x04\xff\xfe").decode("ascii")
        body = json.dumps({"status": 200, "link": "", "encoding": "base64",
                           "body": payload}).encode("utf-8")
        response = MagicMock()
        response.read.return_value = body
        mock_open.return_value.__enter__.return_value = response
        result = server.github_api._github_proxy_request(
            "GET", "/repos/o/r/actions/artifacts/1/zip")
        self.assertEqual({"ok": True, "status": 200, "content_base64": payload}, result)


if __name__ == "__main__":
    unittest.main()
