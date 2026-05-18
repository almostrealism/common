"""
LLM inference backend for the AR Consultant.

This module re-exports from ``tools/mcp/common/inference.py`` which is the
shared implementation used by both ar-consultant and ar-manager.

All backend classes, the SYSTEM_PROMPT, and the ``create_backend`` factory
are available here for backward compatibility.
"""

import importlib.util
import os
import sys

# Load the shared module by absolute path to avoid circular import
# (this file is also named inference.py, so a plain `import inference`
# would resolve to itself).
_COMMON_INFERENCE = os.path.join(
    os.path.dirname(__file__), "..", "common", "inference.py"
)
_spec = importlib.util.spec_from_file_location("common_inference", _COMMON_INFERENCE)
_mod = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_mod)

# Re-export everything
SYSTEM_PROMPT = _mod.SYSTEM_PROMPT
InferenceBackend = _mod.InferenceBackend
LlamaCppBackend = _mod.LlamaCppBackend
OllamaBackend = _mod.OllamaBackend
MLXBackend = _mod.MLXBackend
PassthroughBackend = _mod.PassthroughBackend
create_backend = _mod.create_backend
