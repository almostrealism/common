"""
Shared libraries for AR MCP servers.

Provides common clients and utilities used by ar-consultant, ar-manager,
and other MCP servers that interact with the ar-memory HTTP service.
"""

from .memory_http_client import MemoryHTTPClient
from .inference import InferenceBackend, create_backend, SYSTEM_PROMPT
from .git_context import detect_git_context, normalize_repo_url

__all__ = [
    "MemoryHTTPClient",
    "InferenceBackend",
    "create_backend",
    "SYSTEM_PROMPT",
    "detect_git_context",
    "normalize_repo_url",
]
