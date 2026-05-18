"""
Git context detection for AR MCP servers.

Provides utilities for auto-detecting repository URL and branch name
from the current working directory. Used by ar-consultant to fill in
repo_url/branch when not explicitly provided.
"""

import logging
import subprocess
from typing import Optional

log = logging.getLogger(__name__)


def detect_git_context(working_dir: Optional[str] = None) -> tuple[str, str]:
    """Detect repo_url and branch from git in the specified directory.

    Returns the remote URL exactly as configured in git (typically SSH
    format for this project).

    Args:
        working_dir: Directory to run git commands in. Defaults to cwd.

    Returns:
        Tuple of (repo_url, branch).

    Raises:
        ValueError: If not in a git repository or cannot determine context.
    """
    kwargs = {}
    if working_dir:
        kwargs["cwd"] = working_dir

    # Get remote URL (prefer origin)
    result = subprocess.run(
        ["git", "remote", "get-url", "origin"],
        capture_output=True,
        text=True,
        timeout=5,
        **kwargs,
    )
    if result.returncode != 0:
        raise ValueError("Not in a git repository or no 'origin' remote")
    repo_url = result.stdout.strip()

    # Get current branch
    result = subprocess.run(
        ["git", "rev-parse", "--abbrev-ref", "HEAD"],
        capture_output=True,
        text=True,
        timeout=5,
        **kwargs,
    )
    if result.returncode != 0:
        raise ValueError("Cannot determine current branch")
    branch = result.stdout.strip()

    return (repo_url, branch)
