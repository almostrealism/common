"""Unit tests for per-repository configuration.

``repo_config`` is what makes reformulation settable per repository rather
than per ar-manager process (one process serves every repository, so an
environment variable cannot express it). See
docs/plans/MANAGER_CONSULTANT_CONSOLIDATION.md.
"""

import json
import os
import sys
import unittest
from tempfile import TemporaryDirectory

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "common"))

import repo_config  # noqa: E402


class RepoConfigTest(unittest.TestCase):

    def setUp(self):
        self._tmp = TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self._original_file = repo_config.CONFIG_FILE
        self.addCleanup(self._restore)

    def _restore(self):
        repo_config.CONFIG_FILE = self._original_file
        repo_config._cache = None
        repo_config._cache_expires = 0.0

    def _write(self, data):
        """Point repo_config at a fresh config file holding *data*."""
        path = os.path.join(self._tmp.name, "repo-config.json")
        with open(path, "w", encoding="utf-8") as f:
            if isinstance(data, str):
                f.write(data)
            else:
                json.dump(data, f)
        repo_config.CONFIG_FILE = path
        repo_config._cache = None
        repo_config._cache_expires = 0.0
        return path

    def test_repo_key_normalises_url_spellings(self):
        for url in ("git@github.com:Org/Repo.git",
                    "https://github.com/Org/Repo",
                    "https://github.com/Org/Repo.git",
                    "https://github.com/Org/Repo/"):
            self.assertEqual(repo_config.repo_key(url), "org/repo", url)

    def test_repo_key_rejects_unparseable(self):
        self.assertIsNone(repo_config.repo_key(""))

    def test_setting_read_from_repo_entry(self):
        self._write({"org/repo": {"reformulateOnStore": True}})
        self.assertTrue(repo_config.repo_setting(
            "git@github.com:org/repo.git", "reformulateOnStore"))

    def test_setting_falls_back_to_default_entry(self):
        self._write({"default": {"reformulateOnStore": True}})
        self.assertTrue(repo_config.repo_setting(
            "https://github.com/other/thing", "reformulateOnStore"))

    def test_repo_entry_wins_over_default(self):
        self._write({
            "default": {"reformulateOnStore": True},
            "org/repo": {"reformulateOnStore": False},
        })
        self.assertFalse(repo_config.repo_setting(
            "https://github.com/org/repo", "reformulateOnStore"))

    def test_unset_setting_uses_caller_fallback(self):
        self._write({"org/repo": {"preferReformulatedOnRead": True}})
        self.assertTrue(repo_config.repo_setting(
            "https://github.com/org/repo", "reformulateOnStore", True))
        self.assertFalse(repo_config.repo_setting(
            "https://github.com/org/repo", "reformulateOnStore", False))

    def test_missing_file_is_not_an_error(self):
        repo_config.CONFIG_FILE = os.path.join(self._tmp.name, "absent.json")
        repo_config._cache = None
        repo_config._cache_expires = 0.0
        self.assertFalse(repo_config.repo_setting(
            "https://github.com/org/repo", "reformulateOnStore"))

    def test_malformed_file_degrades_to_defaults(self):
        self._write("{ not json")
        self.assertTrue(repo_config.repo_setting(
            "https://github.com/org/repo", "reformulateOnStore", True))

    def test_non_object_file_degrades_to_defaults(self):
        self._write(["not", "an", "object"])
        self.assertFalse(repo_config.repo_setting(
            "https://github.com/org/repo", "reformulateOnStore"))


if __name__ == "__main__":
    unittest.main()
