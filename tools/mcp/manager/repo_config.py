"""
Per-repository configuration for the AR Manager MCP server.

A single ar-manager process serves every repository, so settings that vary
by repository cannot live in environment variables the way process-wide
defaults do (``AR_MEMORY_REFORMULATED`` and friends). This module reads a
small JSON file, mounted alongside ``manager-tokens.json``, keyed by
repository:

    {
      "almostrealism/common": {
        "reformulateOnStore": true,
        "preferReformulatedOnRead": false
      }
    }

Keys are matched on normalised ``owner/repo``, so the ``git@``, ``https://``
and ``.git``-suffixed spellings of the same repository all resolve to one
entry. A ``"default"`` key supplies values for repositories with no entry
of their own.

The backing store is deliberately behind :func:`repo_setting`: moving these
settings into a ``repos:`` section of ``workstreams.yaml`` (served by the
controller, editable through a tool) only has to change this module, not
any caller. See docs/plans/MANAGER_CONSULTANT_CONSOLIDATION.md §3.3.
"""

import json
import logging
import os
import time
from typing import Optional

from github_api import _parse_github_remote

CONFIG_FILE = os.environ.get(
    "AR_MANAGER_REPO_CONFIG_FILE", "/config/repo-config.json",
)

# The file is operator-edited and tiny; re-reading it on a short TTL means a
# change takes effect without restarting the server, at negligible cost.
_CACHE_TTL = 30.0
_cache: Optional[dict] = None
_cache_expires: float = 0.0

_log = logging.getLogger("ar-manager")


def _load() -> dict:
    """Return the parsed config, re-reading it when the cache has expired.

    A missing file is normal (no repository has been configured) and yields
    an empty mapping. A malformed file is reported once per TTL and treated
    the same way, so a bad edit degrades to defaults rather than breaking
    every memory call.
    """
    global _cache, _cache_expires

    now = time.monotonic()
    if _cache is not None and now < _cache_expires:
        return _cache

    data: dict = {}
    try:
        with open(CONFIG_FILE, encoding="utf-8") as f:
            loaded = json.load(f)
        if isinstance(loaded, dict):
            data = loaded
        else:
            _log.warning("repo config %s is not a JSON object; ignoring",
                         CONFIG_FILE)
    except FileNotFoundError:
        pass
    except (OSError, json.JSONDecodeError) as e:
        _log.warning("Could not read repo config %s: %s", CONFIG_FILE, e)

    _cache = data
    _cache_expires = now + _CACHE_TTL
    return data


def repo_key(repo_url: str) -> Optional[str]:
    """Normalise a repository URL to the ``owner/repo`` key used by the config.

    Args:
        repo_url: Any spelling of a GitHub repository URL.

    Returns:
        Lowercased ``owner/repo``, or None when the URL cannot be parsed.
    """
    if not repo_url:
        return None
    # _parse_github_remote anchors on the end of the string, so a trailing
    # slash hides the repository name from it. Normalising here keeps that
    # shared parser untouched for its other callers.
    parsed = _parse_github_remote(repo_url.strip().rstrip("/"))
    return f"{parsed[0]}/{parsed[1]}".lower() if parsed else None


def repo_setting(repo_url: str, name: str, fallback: bool = False) -> bool:
    """Return a boolean setting for a repository.

    Resolution order: the repository's own entry, then the ``"default"``
    entry, then *fallback*.

    Args:
        repo_url: Repository the call is operating on.
        name: Setting name (e.g. ``"reformulateOnStore"``).
        fallback: Value to use when neither the repository nor the default
            entry declares this setting.

    Returns:
        The configured value, or *fallback*.
    """
    config = _load()
    key = repo_key(repo_url)

    for entry in (config.get(key) if key else None, config.get("default")):
        if isinstance(entry, dict) and name in entry:
            return bool(entry[name])

    return fallback
