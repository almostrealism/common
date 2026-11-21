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

# Re-export everything. The prompt-construction names live in
# ``inference_prompts`` now and are re-exported by the shared ``inference``
# module, so they are reached here through ``_mod`` exactly as before.
SYSTEM_PROMPT = _mod.SYSTEM_PROMPT
REFORMULATE_SYSTEM_PROMPT = _mod.REFORMULATE_SYSTEM_PROMPT
SPECULATION_PHRASES = _mod.SPECULATION_PHRASES
has_speculation = _mod.has_speculation
build_consult_prompt = _mod.build_consult_prompt
InferenceBackend = _mod.InferenceBackend
InferenceUnavailable = _mod.InferenceUnavailable
Synthesis = _mod.Synthesis
LlamaCppBackend = _mod.LlamaCppBackend
OllamaBackend = _mod.OllamaBackend
MLXBackend = _mod.MLXBackend
OpenRouterBackend = _mod.OpenRouterBackend
PassthroughBackend = _mod.PassthroughBackend
AutoBackend = _mod.AutoBackend
create_backend = _mod.create_backend
