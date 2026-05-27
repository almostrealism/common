"""
Phase configuration parsing and validation for the AR Manager MCP server.

This module contains the client-side parsing, validation, and clearing-semantics
helpers for the ``default_phase_config`` and ``phase_configs`` parameters accepted
by ``workstream_submit_task``, ``workstream_register``, ``workstream_update_config``,
and ``workspace_update_config``.

Clearing semantics (used by the *_update_config tools only):
    - ``default_phase_config='{}'`` → clears the stored default config.
    - ``phase_configs='{}'``        → clears all per-phase overrides.
    - ``phase_configs='{"review": null}'`` → clears just the review phase override.

Submit and register tools should use the result with ``if parsed:`` (falsy empty
dict skips sending), while update tools should use ``if parsed is not None:``
(empty dict signals clearing and must be forwarded to the controller).
"""

import json as _json
from typing import Optional

# Valid Claude Code effort/thinking levels. Mirrors
# io.flowtree.jobs.agent.ClaudeCodeRunner#VALID_EFFORT_LEVELS; used to
# pre-validate the ``effort`` field of per-phase configuration client-side.
VALID_EFFORT_LEVELS = ("low", "medium", "high", "xhigh", "max")

# Phase wire names mirrored from io.flowtree.jobs.agent.Phase on the Java side.
_KNOWN_PHASE_WIRE_NAMES = (
    "primary",
    "review",
    "deduplication",
    "organizational-placement",
    "enforce-changes",
    "maven-dependency-protection",
    "post-completion",
    "commit-message",
    "git-tampering-restart",
)

# Runner identifiers mirrored from io.flowtree.jobs.agent.AgentRunnerRegistry.
# New runners are registered there; this list must be updated alongside.
_KNOWN_RUNNER_NAMES = (
    "claude",
    "opencode",
)


def _validate_phase_config_field(context: str, field: str, value: str) -> Optional[dict]:
    """Validate a single ``{runner, model, effort, provider}`` field value.

    ``runner`` is checked against the known set of registered runner
    identifiers; ``effort`` is checked against ``VALID_EFFORT_LEVELS``.
    Both ``model`` and ``provider`` are passed through without client-side
    validation — the controller validates models against the runner's
    supportedModels and rejects incompatible provider/runner combinations
    (e.g. runner=claude with provider!=anthropic) at submission time.

    Args:
        context: ``"default_phase_config"`` or ``"phase_configs['name']"``
            — used to build a human-readable error message.
        field: One of ``"runner"`` / ``"model"`` / ``"effort"`` / ``"provider"``.
        value: The non-empty string that the caller supplied.

    Returns:
        ``None`` when the value is valid; otherwise a 400-style
        ``{"ok": False, "error": ...}`` dict.
    """
    if field == "runner" and value not in _KNOWN_RUNNER_NAMES:
        return {
            "ok": False,
            "error": (context + ".runner='" + value + "' is not a known runner. "
                      "Allowed: " + ", ".join(_KNOWN_RUNNER_NAMES)
                      + ". Use agent_options to list runners on the controller."),
        }
    if field == "effort" and value not in VALID_EFFORT_LEVELS:
        return {
            "ok": False,
            "error": (context + ".effort='" + value + "' is not a valid effort level. "
                      "Allowed: " + ", ".join(VALID_EFFORT_LEVELS)),
        }
    return None


def _parse_default_phase_config_json(
        default_phase_config: str) -> "tuple[Optional[dict], Optional[dict]]":
    """Parse and validate the ``default_phase_config`` JSON argument.

    The argument is a JSON object string with optional keys ``runner``,
    ``model``, ``effort``, ``provider``. Unknown keys are rejected.

    Clearing semantics: an empty JSON object (``'{}'``) returns ``({}, None)``,
    which signals the controller to clear the stored default config. This
    distinction from "not supplied" (``""`` → ``(None, None)``) lets update
    tools forward the clearing signal while submit/register tools treat both
    as "no change" by using a plain ``if parsed_dict:`` check.

    Args:
        default_phase_config: A JSON object string, or empty string for none.

    Returns:
        A tuple ``(parsed_dict, error_dict)``:
          - ``(None, None)`` when the argument is empty (not supplied).
          - ``({}, None)`` when the argument is ``'{}'`` (clear signal).
          - ``({...}, None)`` on a non-empty valid object.
          - ``(None, {...})`` on parse/validation failure.
    """
    if not default_phase_config:
        return None, None
    try:
        parsed = _json.loads(default_phase_config)
    except ValueError as e:
        return None, {
            "ok": False,
            "error": "default_phase_config must be a JSON object: " + str(e),
        }
    if not isinstance(parsed, dict):
        return None, {
            "ok": False,
            "error": "default_phase_config must be a JSON object with optional "
                     "runner/model/effort/provider keys",
        }
    allowed = {"runner", "model", "effort", "provider"}
    cleaned: dict = {}
    for key, value in parsed.items():
        if key not in allowed:
            return None, {
                "ok": False,
                "error": ("Unknown key in default_phase_config: '"
                          + str(key) + "'. Allowed: runner, model, effort, provider"),
            }
        if value is None:
            continue
        if not isinstance(value, str) or not value:
            return None, {
                "ok": False,
                "error": ("default_phase_config." + str(key)
                          + " must be a non-empty string"),
            }
        err = _validate_phase_config_field("default_phase_config", key, value)
        if err is not None:
            return None, err
        cleaned[key] = value
    return cleaned, None


def _parse_phase_configs_json(
        phase_configs: str) -> "tuple[Optional[dict], Optional[dict]]":
    """Parse and validate the ``phase_configs`` JSON argument.

    The argument is a JSON object whose keys are phase wire names
    (``"primary"``, ``"review"``, ``"deduplication"``, ...) and whose
    values are nested JSON objects with optional ``runner`` / ``model`` /
    ``effort`` / ``provider`` fields. Unknown phase keys are rejected.

    Clearing semantics:
      - ``'{}'`` (empty object) → ``({}, None)``, signals clearing all per-phase overrides.
      - ``'{"review": null}'`` → ``({"review": None}, None)``, signals clearing just
        the review phase override. Other per-phase entries are unaffected.
      - ``""`` (not supplied) → ``(None, None)``.

    Update tools should use ``if parsed_dict is not None:`` to forward empty
    dicts (clearing) to the controller. Submit/register tools should use
    ``if parsed_dict:`` to skip both ``None`` and ``{}`` (no clearing applies).

    Args:
        phase_configs: A JSON object string, or empty string for none.

    Returns:
        A tuple ``(parsed_dict, error_dict)`` matching the pattern of
        ``_parse_default_phase_config_json``.
    """
    if not phase_configs:
        return None, None
    try:
        parsed = _json.loads(phase_configs)
    except ValueError as e:
        return None, {
            "ok": False,
            "error": "phase_configs must be a JSON object: " + str(e),
        }
    if not isinstance(parsed, dict):
        return None, {
            "ok": False,
            "error": "phase_configs must be a JSON object mapping phase names to "
                     "objects with optional runner/model/effort/provider keys",
        }
    valid_phase_keys = set(_KNOWN_PHASE_WIRE_NAMES)
    allowed_inner = {"runner", "model", "effort", "provider"}
    cleaned: dict = {}
    for key, inner in parsed.items():
        if key not in valid_phase_keys:
            return None, {
                "ok": False,
                "error": ("Unknown phase key in phase_configs: '" + str(key)
                          + "'. Allowed phase keys: "
                          + ", ".join(sorted(valid_phase_keys))),
            }
        if inner is None:
            # null value → clearing signal for this specific phase
            cleaned[key] = None
            continue
        if not isinstance(inner, dict):
            return None, {
                "ok": False,
                "error": ("phase_configs['" + str(key)
                          + "'] must be an object with runner/model/effort/provider keys, "
                          "or null to clear that phase's override"),
            }
        inner_cleaned: dict = {}
        for inner_key, value in inner.items():
            if inner_key not in allowed_inner:
                return None, {
                    "ok": False,
                    "error": ("Unknown key in phase_configs['" + str(key) + "']: '"
                              + str(inner_key) + "'. Allowed: runner, model, effort, provider"),
                }
            if value is None:
                continue
            if not isinstance(value, str) or not value:
                return None, {
                    "ok": False,
                    "error": ("phase_configs['" + str(key) + "']."
                              + str(inner_key) + " must be a non-empty string"),
                }
            err = _validate_phase_config_field(
                "phase_configs['" + str(key) + "']", inner_key, value)
            if err is not None:
                return None, err
            inner_cleaned[inner_key] = value
        if inner_cleaned:
            cleaned[key] = inner_cleaned
        # TODO(review): empty inner object silently drops the key, leaving `cleaned`
        # potentially empty. An update tool then forwards {} as {"phaseConfigs":{}},
        # which clears ALL phase overrides rather than being a no-op for this phase.
        # Fix: return a distinct sentinel (e.g. empty-dict per-phase marker) so the
        # update path can distinguish "no phases provided" from "all-null inner fields".
    return cleaned, None


# ---------------------------------------------------------------------------
# Removed legacy configuration parameters.
# ---------------------------------------------------------------------------

# Replacement hints for each removed parameter, shown in error responses.
_REMOVED_CONFIG_PARAM_HINT = {
    "model": "Use default_phase_config='{\"model\": \"...\"}' to set one model "
             "across all phases, or phase_configs to set per-phase model values.",
    "effort": "Use default_phase_config='{\"effort\": \"...\"}' to set one effort "
              "level across all phases, or phase_configs to set per-phase effort.",
    "default_runner": "Use default_phase_config='{\"runner\": \"...\"}' to set the "
                      "default runner across all phases.",
    "runners": "Use phase_configs (a per-phase map of {runner, model, effort, "
               "provider} objects), or default_phase_config for a single default.",
}


def _reject_removed_config_params(model="", effort="",
                                  default_runner="", runners="") -> Optional[dict]:
    """Return a 400-style error dict when a caller passes any removed legacy
    configuration parameter, or ``None`` when none were supplied.

    The removed parameters are ``model``, ``effort``, ``default_runner``, and
    ``runners``. Each is replaced by per-phase configuration via
    ``default_phase_config`` and ``phase_configs``. This is a deliberate clean
    break: rather than silently translating the legacy shape, the call is
    rejected so the caller migrates explicitly.

    Args:
        model: Removed ``model`` argument; non-empty means the caller passed it.
        effort: Removed ``effort`` argument.
        default_runner: Removed ``default_runner`` argument.
        runners: Removed ``runners`` argument.

    Returns:
        A ``{"ok": False, "error": ...}`` dict naming the first removed
        parameter supplied, or ``None`` when none were supplied.
    """
    supplied = []
    if model:
        supplied.append("model")
    if effort:
        supplied.append("effort")
    if default_runner:
        supplied.append("default_runner")
    if runners:
        supplied.append("runners")
    if not supplied:
        return None
    first = supplied[0]
    return {
        "ok": False,
        "error": (
            "The `" + first + "` parameter is no longer supported. "
            + _REMOVED_CONFIG_PARAM_HINT[first]
            + " See tools/mcp/CLAUDE.md for the per-phase configuration reference."
        ),
        "removed_parameters": supplied,
    }
