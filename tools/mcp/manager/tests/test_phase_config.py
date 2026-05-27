"""Tests for the phase_config module (parsing and clearing semantics).

Tests ``_parse_default_phase_config_json``, ``_parse_phase_configs_json``,
and ``_reject_removed_config_params`` in isolation, covering:

- Empty string → (None, None) for both parsers (not-supplied sentinel)
- ``'{}'`` → ({}, None) for both parsers (clearing sentinel)
- ``'{"review": null}'`` → ({"review": None}, None) for phase_configs (clear one phase)
- Valid and invalid runner / effort / model / provider values
- Unknown keys rejected
- ``_reject_removed_config_params`` returns errors for legacy params
"""

import os
import sys
import unittest

_TESTS_DIR = os.path.dirname(os.path.abspath(__file__))
_MANAGER_DIR = os.path.dirname(_TESTS_DIR)
if _MANAGER_DIR not in sys.path:
    sys.path.insert(0, _MANAGER_DIR)

from phase_config import (  # noqa: E402
    _parse_default_phase_config_json,
    _parse_phase_configs_json,
    _reject_removed_config_params,
    VALID_EFFORT_LEVELS,
)


class TestParseDefaultPhaseConfigJson(unittest.TestCase):
    """Unit tests for ``_parse_default_phase_config_json``."""

    def test_empty_string_returns_none_none(self):
        parsed, err = _parse_default_phase_config_json("")
        self.assertIsNone(parsed)
        self.assertIsNone(err)

    def test_empty_object_returns_empty_dict_clear_signal(self):
        parsed, err = _parse_default_phase_config_json("{}")
        self.assertIsNotNone(parsed)
        self.assertEqual(parsed, {})
        self.assertIsNone(err)

    def test_valid_runner_only(self):
        parsed, err = _parse_default_phase_config_json('{"runner": "claude"}')
        self.assertIsNone(err)
        self.assertEqual(parsed, {"runner": "claude"})

    def test_valid_opencode_runner(self):
        parsed, err = _parse_default_phase_config_json('{"runner": "opencode"}')
        self.assertIsNone(err)
        self.assertEqual(parsed, {"runner": "opencode"})

    def test_valid_full_config(self):
        parsed, err = _parse_default_phase_config_json(
            '{"runner": "opencode", "model": "qwen3-coder", "effort": "medium", "provider": "openrouter"}'
        )
        self.assertIsNone(err)
        self.assertEqual(parsed, {
            "runner": "opencode",
            "model": "qwen3-coder",
            "effort": "medium",
            "provider": "openrouter",
        })

    def test_null_field_values_are_skipped(self):
        parsed, err = _parse_default_phase_config_json(
            '{"runner": "claude", "model": null}'
        )
        self.assertIsNone(err)
        self.assertEqual(parsed, {"runner": "claude"})

    def test_unknown_runner_rejected(self):
        parsed, err = _parse_default_phase_config_json('{"runner": "unknown-runner"}')
        self.assertIsNone(parsed)
        self.assertIsNotNone(err)
        self.assertFalse(err["ok"])
        self.assertIn("runner", err["error"])

    def test_invalid_effort_rejected(self):
        parsed, err = _parse_default_phase_config_json('{"effort": "super-high"}')
        self.assertIsNone(parsed)
        self.assertIsNotNone(err)
        self.assertFalse(err["ok"])
        self.assertIn("effort", err["error"])

    def test_all_valid_effort_levels_accepted(self):
        for level in VALID_EFFORT_LEVELS:
            with self.subTest(effort=level):
                parsed, err = _parse_default_phase_config_json(
                    '{"effort": "' + level + '"}'
                )
                self.assertIsNone(err)
                self.assertEqual(parsed["effort"], level)

    def test_unknown_key_rejected(self):
        parsed, err = _parse_default_phase_config_json('{"runner": "claude", "unknown": "x"}')
        self.assertIsNone(parsed)
        self.assertIsNotNone(err)
        self.assertFalse(err["ok"])
        self.assertIn("unknown", err["error"])

    def test_not_an_object_rejected(self):
        parsed, err = _parse_default_phase_config_json('"just-a-string"')
        self.assertIsNone(parsed)
        self.assertIsNotNone(err)
        self.assertFalse(err["ok"])

    def test_invalid_json_rejected(self):
        parsed, err = _parse_default_phase_config_json("{not valid json}")
        self.assertIsNone(parsed)
        self.assertIsNotNone(err)
        self.assertFalse(err["ok"])

    def test_empty_string_value_rejected(self):
        parsed, err = _parse_default_phase_config_json('{"runner": ""}')
        self.assertIsNone(parsed)
        self.assertIsNotNone(err)
        self.assertFalse(err["ok"])

    def test_provider_passes_through_without_validation(self):
        parsed, err = _parse_default_phase_config_json(
            '{"provider": "any-provider-value"}'
        )
        self.assertIsNone(err)
        self.assertEqual(parsed, {"provider": "any-provider-value"})


class TestParsePhaseConfigsJson(unittest.TestCase):
    """Unit tests for ``_parse_phase_configs_json``."""

    def test_empty_string_returns_none_none(self):
        parsed, err = _parse_phase_configs_json("")
        self.assertIsNone(parsed)
        self.assertIsNone(err)

    def test_empty_object_returns_empty_dict_clear_all_signal(self):
        parsed, err = _parse_phase_configs_json("{}")
        self.assertIsNotNone(parsed)
        self.assertEqual(parsed, {})
        self.assertIsNone(err)

    def test_null_phase_value_is_clear_signal_for_that_phase(self):
        parsed, err = _parse_phase_configs_json('{"review": null}')
        self.assertIsNone(err)
        self.assertIsNotNone(parsed)
        self.assertIn("review", parsed)
        self.assertIsNone(parsed["review"])

    def test_null_phase_mixed_with_normal_phase(self):
        parsed, err = _parse_phase_configs_json(
            '{"review": null, "primary": {"runner": "claude"}}'
        )
        self.assertIsNone(err)
        self.assertIsNone(parsed["review"])
        self.assertEqual(parsed["primary"], {"runner": "claude"})

    def test_valid_single_phase_config(self):
        parsed, err = _parse_phase_configs_json(
            '{"primary": {"runner": "opencode", "effort": "high"}}'
        )
        self.assertIsNone(err)
        self.assertEqual(parsed, {"primary": {"runner": "opencode", "effort": "high"}})

    def test_valid_multiple_phases(self):
        parsed, err = _parse_phase_configs_json(
            '{"primary": {"runner": "opencode"}, "review": {"runner": "claude", "model": "claude-sonnet-4-6"}}'
        )
        self.assertIsNone(err)
        self.assertEqual(parsed["primary"], {"runner": "opencode"})
        self.assertEqual(parsed["review"]["runner"], "claude")
        self.assertEqual(parsed["review"]["model"], "claude-sonnet-4-6")

    def test_null_inner_field_is_skipped(self):
        parsed, err = _parse_phase_configs_json(
            '{"primary": {"runner": "claude", "model": null}}'
        )
        self.assertIsNone(err)
        self.assertEqual(parsed, {"primary": {"runner": "claude"}})

    def test_empty_inner_object_skipped(self):
        parsed, err = _parse_phase_configs_json('{"primary": {}}')
        self.assertIsNone(err)
        self.assertNotIn("primary", parsed)

    def test_unknown_phase_key_rejected(self):
        parsed, err = _parse_phase_configs_json('{"unknown-phase": {"runner": "claude"}}')
        self.assertIsNone(parsed)
        self.assertIsNotNone(err)
        self.assertFalse(err["ok"])
        self.assertIn("unknown-phase", err["error"])

    def test_unknown_inner_key_rejected(self):
        parsed, err = _parse_phase_configs_json(
            '{"primary": {"runner": "claude", "unknown_key": "x"}}'
        )
        self.assertIsNone(parsed)
        self.assertIsNotNone(err)
        self.assertFalse(err["ok"])

    def test_unknown_runner_in_phase_rejected(self):
        parsed, err = _parse_phase_configs_json(
            '{"primary": {"runner": "bad-runner"}}'
        )
        self.assertIsNone(parsed)
        self.assertIsNotNone(err)
        self.assertFalse(err["ok"])
        self.assertIn("runner", err["error"])

    def test_invalid_effort_in_phase_rejected(self):
        parsed, err = _parse_phase_configs_json(
            '{"primary": {"effort": "ultra"}}'
        )
        self.assertIsNone(parsed)
        self.assertIsNotNone(err)
        self.assertFalse(err["ok"])
        self.assertIn("effort", err["error"])

    def test_not_an_object_rejected(self):
        parsed, err = _parse_phase_configs_json('["array"]')
        self.assertIsNone(parsed)
        self.assertIsNotNone(err)
        self.assertFalse(err["ok"])

    def test_phase_value_not_object_rejected(self):
        parsed, err = _parse_phase_configs_json('{"primary": "not-an-object"}')
        self.assertIsNone(parsed)
        self.assertIsNotNone(err)
        self.assertFalse(err["ok"])

    def test_all_known_phases_accepted(self):
        from phase_config import _KNOWN_PHASE_WIRE_NAMES
        for phase in _KNOWN_PHASE_WIRE_NAMES:
            with self.subTest(phase=phase):
                parsed, err = _parse_phase_configs_json(
                    '{"' + phase + '": {"runner": "claude"}}'
                )
                self.assertIsNone(err, f"Unexpected error for phase {phase}: {err}")
                self.assertIn(phase, parsed)

    def test_invalid_json_rejected(self):
        parsed, err = _parse_phase_configs_json("{not valid}")
        self.assertIsNone(parsed)
        self.assertIsNotNone(err)
        self.assertFalse(err["ok"])

    def test_clear_signal_distinction_from_not_supplied(self):
        none_parsed, _ = _parse_phase_configs_json("")
        clear_parsed, _ = _parse_phase_configs_json("{}")
        self.assertIsNone(none_parsed)
        self.assertIsNotNone(clear_parsed)
        self.assertEqual(clear_parsed, {})


class TestClearingSemanticSentinels(unittest.TestCase):
    """Verifies the semantic contract: update tools use ``is not None``,
    submit/register tools use truthy check."""

    def test_not_supplied_is_falsy_and_none(self):
        parsed, _ = _parse_default_phase_config_json("")
        self.assertIsNone(parsed)
        self.assertFalse(bool(parsed))

    def test_clear_signal_is_not_none_but_falsy(self):
        """Empty dict must pass ``is not None`` but fail bool check — both correct."""
        parsed, _ = _parse_default_phase_config_json("{}")
        self.assertIsNotNone(parsed)
        self.assertFalse(bool(parsed))

    def test_nonempty_config_passes_both_checks(self):
        parsed, _ = _parse_default_phase_config_json('{"runner": "claude"}')
        self.assertIsNotNone(parsed)
        self.assertTrue(bool(parsed))

    def test_phase_configs_clear_signal_same_semantics(self):
        parsed, _ = _parse_phase_configs_json("{}")
        self.assertIsNotNone(parsed)
        self.assertFalse(bool(parsed))


class TestRejectRemovedConfigParams(unittest.TestCase):
    """Unit tests for ``_reject_removed_config_params``."""

    def test_no_legacy_params_returns_none(self):
        result = _reject_removed_config_params()
        self.assertIsNone(result)

    def test_model_param_rejected(self):
        result = _reject_removed_config_params(model="claude-opus")
        self.assertIsNotNone(result)
        self.assertFalse(result["ok"])
        self.assertIn("model", result["error"])
        self.assertIn("model", result["removed_parameters"])

    def test_effort_param_rejected(self):
        result = _reject_removed_config_params(effort="high")
        self.assertIsNotNone(result)
        self.assertFalse(result["ok"])
        self.assertIn("effort", result["error"])

    def test_default_runner_param_rejected(self):
        result = _reject_removed_config_params(default_runner="claude")
        self.assertIsNotNone(result)
        self.assertFalse(result["ok"])
        self.assertIn("default_runner", result["error"])

    def test_runners_param_rejected(self):
        result = _reject_removed_config_params(runners='{"review": "opencode"}')
        self.assertIsNotNone(result)
        self.assertFalse(result["ok"])
        self.assertIn("runners", result["error"])

    def test_multiple_legacy_params_reports_first(self):
        result = _reject_removed_config_params(model="opus", effort="high")
        self.assertIsNotNone(result)
        self.assertFalse(result["ok"])
        self.assertIn("model", result["removed_parameters"])
        self.assertIn("effort", result["removed_parameters"])

    def test_error_includes_replacement_hint(self):
        result = _reject_removed_config_params(model="opus")
        self.assertIsNotNone(result)
        self.assertIn("default_phase_config", result["error"])


if __name__ == "__main__":
    unittest.main()
