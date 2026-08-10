"""
Shared libraries for AR MCP servers.

Provides common clients and utilities used by ar-consultant, ar-manager,
and other MCP servers that interact with the ar-memory HTTP service.
"""

from .memory_http_client import MemoryHTTPClient
from .inference import (
    InferenceBackend,
    InferenceUnavailable,
    Synthesis,
    create_backend,
    SYSTEM_PROMPT,
)
from .git_context import detect_git_context
from .memory_text import (
    BETA_NOTICE,
    encode_dual_source,
    is_reformulated,
    original_text,
    present,
    presented_entries,
    presented_entry,
    prefers_reformulated,
    reformulated_text,
    text_notice,
)

__all__ = [
    "MemoryHTTPClient",
    "InferenceBackend",
    "InferenceUnavailable",
    "Synthesis",
    "create_backend",
    "SYSTEM_PROMPT",
    "detect_git_context",
    "BETA_NOTICE",
    "encode_dual_source",
    "is_reformulated",
    "original_text",
    "present",
    "presented_entries",
    "presented_entry",
    "prefers_reformulated",
    "reformulated_text",
    "text_notice",
]
