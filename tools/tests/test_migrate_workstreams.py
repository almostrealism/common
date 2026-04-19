#!/usr/bin/env python3
"""
Tests for tools/migrate_workstreams.py

Run with:
    python3 -m unittest tools/tests/test_migrate_workstreams.py
or from the repo root:
    python3 -m unittest discover -s tools/tests -p "test_*.py"
"""

import importlib.util
import os
import sys
import tempfile
import unittest

import yaml

# Load the migration module from its file path (avoids hyphen issues).
_SCRIPT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                       "migrate_workstreams.py")
_spec = importlib.util.spec_from_file_location("migrate_workstreams", _SCRIPT)
migrate = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(migrate)


# ---------------------------------------------------------------------------
# Sample configs
# ---------------------------------------------------------------------------

SINGLE_WORKSPACE_YAML = """
githubOrgs:
  my-org:
    token: "ghp_org_token"
  other-org:
    token: "ghp_other_token"
workstreams:
  - channelId: "C0000001"
    channelName: "#alpha"
    defaultBranch: "feature/alpha"
    githubOrg: "my-org"
  - channelId: "C0000002"
    channelName: "#beta"
    defaultBranch: "feature/beta"
    githubOrg: "other-org"
  - channelId: "C0000003"
    channelName: "#gamma"
    defaultBranch: "feature/gamma"
"""

MULTI_WORKSPACE_YAML = """
slackWorkspaces:
  - workspaceId: "T111"
    name: "primary"
    botToken: "xoxb-one"
    appToken: "xapp-one"
workstreams:
  - channelId: "C0000001"
    channelName: "#alpha"
    slackWorkspaceId: "T111"
    defaultBranch: "feature/alpha"
"""

NO_ORGS_YAML = """
workstreams:
  - channelId: "C0000001"
    channelName: "#alpha"
    defaultBranch: "feature/alpha"
"""

ORPHAN_ORG_YAML = """
githubOrgs:
  defined-org:
    token: "ghp_defined"
workstreams:
  - channelId: "C0000001"
    channelName: "#alpha"
    githubOrg: "undefined-org"
"""

EMPTY_WORKSTREAMS_YAML = """
githubOrgs:
  solo-org:
    token: "ghp_solo"
workstreams: []
"""


# ---------------------------------------------------------------------------
# detect_format
# ---------------------------------------------------------------------------

class TestDetectFormat(unittest.TestCase):

    def test_single_workspace_config(self):
        config = yaml.safe_load(SINGLE_WORKSPACE_YAML)
        self.assertEqual("single", migrate.detect_format(config))

    def test_multi_workspace_config(self):
        config = yaml.safe_load(MULTI_WORKSPACE_YAML)
        self.assertEqual("multi", migrate.detect_format(config))

    def test_empty_slack_workspaces_is_single(self):
        config = {"slackWorkspaces": [], "workstreams": []}
        self.assertEqual("single", migrate.detect_format(config))

    def test_no_orgs_config_is_single(self):
        config = yaml.safe_load(NO_ORGS_YAML)
        self.assertEqual("single", migrate.detect_format(config))


# ---------------------------------------------------------------------------
# collect_defined_orgs / collect_workstream_orgs
# ---------------------------------------------------------------------------

class TestCollectOrgs(unittest.TestCase):

    def test_collect_defined_orgs(self):
        config = yaml.safe_load(SINGLE_WORKSPACE_YAML)
        orgs = migrate.collect_defined_orgs(config)
        self.assertEqual(["my-org", "other-org"], orgs)

    def test_collect_defined_orgs_empty(self):
        config = yaml.safe_load(NO_ORGS_YAML)
        self.assertEqual([], migrate.collect_defined_orgs(config))

    def test_collect_workstream_orgs_no_orphans(self):
        config = yaml.safe_load(SINGLE_WORKSPACE_YAML)
        referenced, orphaned = migrate.collect_workstream_orgs(config)
        self.assertIn("my-org", referenced)
        self.assertIn("other-org", referenced)
        self.assertEqual([], orphaned)

    def test_collect_workstream_orgs_with_orphan(self):
        config = yaml.safe_load(ORPHAN_ORG_YAML)
        referenced, orphaned = migrate.collect_workstream_orgs(config)
        self.assertIn("undefined-org", referenced)
        self.assertIn("undefined-org", orphaned)
        self.assertNotIn("defined-org", orphaned)

    def test_no_githubOrg_workstream_not_in_referenced(self):
        config = yaml.safe_load(SINGLE_WORKSPACE_YAML)
        referenced, _ = migrate.collect_workstream_orgs(config)
        # #gamma has no githubOrg
        # The org list should only contain orgs, not None
        self.assertNotIn(None, referenced)


# ---------------------------------------------------------------------------
# apply_migration — basic single-workspace to multi-workspace
# ---------------------------------------------------------------------------

class TestApplyMigration(unittest.TestCase):

    def _two_workspace_entries(self):
        return [
            {
                "workspaceId": "T111",
                "name": "primary",
                "tokensFile": "/config/slack-tokens-primary.json",
            },
            {
                "workspaceId": "T222",
                "name": "secondary",
                "botToken": "xoxb-two",
                "appToken": "xapp-two",
            },
        ]

    def test_slackWorkspaces_added(self):
        config = yaml.safe_load(SINGLE_WORKSPACE_YAML)
        entries = self._two_workspace_entries()
        org_map = {"my-org": "T111", "other-org": "T222"}
        result = migrate.apply_migration(config, entries, org_map, "T111")
        self.assertIn("slackWorkspaces", result)
        self.assertEqual(2, len(result["slackWorkspaces"]))

    def test_top_level_githubOrgs_removed(self):
        config = yaml.safe_load(SINGLE_WORKSPACE_YAML)
        org_map = {"my-org": "T111", "other-org": "T222"}
        result = migrate.apply_migration(config, self._two_workspace_entries(), org_map, "T111")
        self.assertNotIn("githubOrgs", result)

    def test_top_level_githubOrgs_embedded_in_workspace_entries(self):
        # Regression test: prior bug reassigned ws_entry as a local variable only,
        # so githubOrgs were never written back into workspace_entries.
        config = yaml.safe_load(SINGLE_WORKSPACE_YAML)
        org_map = {"my-org": "T111", "other-org": "T222"}
        result = migrate.apply_migration(config, self._two_workspace_entries(), org_map, "T111")
        ws_list = result["slackWorkspaces"]
        primary = next(w for w in ws_list if w["workspaceId"] == "T111")
        secondary = next(w for w in ws_list if w["workspaceId"] == "T222")
        self.assertIn("githubOrgs", primary, "T111 workspace must receive my-org token")
        self.assertIn("my-org", primary["githubOrgs"])
        self.assertIn("githubOrgs", secondary, "T222 workspace must receive other-org token")
        self.assertIn("other-org", secondary["githubOrgs"])

    def test_workstream_gets_slackWorkspaceId_from_githubOrg(self):
        config = yaml.safe_load(SINGLE_WORKSPACE_YAML)
        org_map = {"my-org": "T111", "other-org": "T222"}
        result = migrate.apply_migration(config, self._two_workspace_entries(), org_map, "T111")
        ws_list = result["workstreams"]
        alpha = next(w for w in ws_list if w["channelId"] == "C0000001")
        beta = next(w for w in ws_list if w["channelId"] == "C0000002")
        self.assertEqual("T111", alpha["slackWorkspaceId"])
        self.assertEqual("T222", beta["slackWorkspaceId"])

    def test_workstream_without_githubOrg_gets_default(self):
        config = yaml.safe_load(SINGLE_WORKSPACE_YAML)
        org_map = {"my-org": "T111", "other-org": "T222"}
        result = migrate.apply_migration(config, self._two_workspace_entries(), org_map, "T111")
        gamma = next(w for w in result["workstreams"] if w["channelId"] == "C0000003")
        self.assertEqual("T111", gamma["slackWorkspaceId"])

    def test_existing_slackWorkspaceId_not_overwritten(self):
        config = yaml.safe_load(SINGLE_WORKSPACE_YAML)
        config["workstreams"][0]["slackWorkspaceId"] = "T_EXISTING"
        org_map = {"my-org": "T111", "other-org": "T222"}
        entries = self._two_workspace_entries()
        result = migrate.apply_migration(config, entries, org_map, "T111")
        self.assertEqual("T_EXISTING", result["workstreams"][0]["slackWorkspaceId"])

    def test_original_config_not_mutated(self):
        config = yaml.safe_load(SINGLE_WORKSPACE_YAML)
        original_orgs = dict(config.get("githubOrgs", {}))
        org_map = {"my-org": "T111", "other-org": "T222"}
        migrate.apply_migration(config, self._two_workspace_entries(), org_map, "T111")
        # Original config must be unchanged
        self.assertIn("githubOrgs", config)
        self.assertEqual(original_orgs, config["githubOrgs"])

    def test_empty_workstreams(self):
        config = yaml.safe_load(EMPTY_WORKSTREAMS_YAML)
        entries = [{"workspaceId": "T111", "name": "solo", "tokensFile": "/tok.json"}]
        org_map = {"solo-org": "T111"}
        result = migrate.apply_migration(config, entries, org_map, "T111")
        self.assertEqual([], result.get("workstreams", []))

    def test_no_workspace_entries_no_slackWorkspaceId(self):
        config = yaml.safe_load(NO_ORGS_YAML)
        result = migrate.apply_migration(config, [], {}, None)
        # No workspace to assign, so no slackWorkspaceId added
        for ws in result.get("workstreams", []):
            self.assertIsNone(ws.get("slackWorkspaceId"))

    def test_orphan_org_workstream_gets_fallback(self):
        config = yaml.safe_load(ORPHAN_ORG_YAML)
        entries = [{"workspaceId": "T111", "name": "primary", "tokensFile": "/tok.json"}]
        # undefined-org is not in org_map → workstream falls back to default
        org_map = {"defined-org": "T111"}
        result = migrate.apply_migration(config, entries, org_map, "T111")
        ws = result["workstreams"][0]
        self.assertEqual("T111", ws["slackWorkspaceId"])


# ---------------------------------------------------------------------------
# validate_migrated
# ---------------------------------------------------------------------------

class TestValidateMigrated(unittest.TestCase):

    def test_valid_config_no_errors(self):
        config = {
            "slackWorkspaces": [
                {"workspaceId": "T111", "name": "p", "tokensFile": "/tok.json"}
            ],
            "workstreams": [
                {"channelId": "C1", "slackWorkspaceId": "T111"}
            ],
        }
        self.assertEqual([], migrate.validate_migrated(config))

    def test_missing_slackWorkspaceId(self):
        config = {
            "slackWorkspaces": [
                {"workspaceId": "T111", "name": "p", "tokensFile": "/tok.json"}
            ],
            "workstreams": [
                {"channelId": "C1", "channelName": "#ch"}
            ],
        }
        errors = migrate.validate_migrated(config)
        self.assertTrue(any("missing slackWorkspaceId" in e for e in errors))

    def test_invalid_slackWorkspaceId(self):
        config = {
            "slackWorkspaces": [
                {"workspaceId": "T111", "name": "p", "tokensFile": "/tok.json"}
            ],
            "workstreams": [
                {"channelId": "C1", "slackWorkspaceId": "T_UNKNOWN"}
            ],
        }
        errors = migrate.validate_migrated(config)
        self.assertTrue(any("not defined" in e for e in errors))

    def test_workspace_missing_tokens(self):
        config = {
            "slackWorkspaces": [{"workspaceId": "T111", "name": "p"}],
            "workstreams": [{"channelId": "C1", "slackWorkspaceId": "T111"}],
        }
        errors = migrate.validate_migrated(config)
        self.assertTrue(any("no tokens" in e for e in errors))

    def test_workspace_with_inline_tokens_valid(self):
        config = {
            "slackWorkspaces": [
                {
                    "workspaceId": "T111",
                    "name": "p",
                    "botToken": "xoxb-x",
                    "appToken": "xapp-x",
                }
            ],
            "workstreams": [{"channelId": "C1", "slackWorkspaceId": "T111"}],
        }
        self.assertEqual([], migrate.validate_migrated(config))

    def test_empty_workstreams_valid(self):
        config = {
            "slackWorkspaces": [
                {"workspaceId": "T111", "name": "p", "tokensFile": "/tok.json"}
            ],
            "workstreams": [],
        }
        self.assertEqual([], migrate.validate_migrated(config))


# ---------------------------------------------------------------------------
# Round-trip: migrated YAML can be re-parsed by yaml.safe_load
# ---------------------------------------------------------------------------

class TestYamlRoundTrip(unittest.TestCase):

    def test_migrated_yaml_is_valid(self):
        """The output of dump_yaml should parse back to an equivalent dict."""
        config = yaml.safe_load(SINGLE_WORKSPACE_YAML)
        entries = [
            {
                "workspaceId": "T111",
                "name": "primary",
                "tokensFile": "/config/slack-tokens.json",
            }
        ]
        org_map = {"my-org": "T111", "other-org": "T111"}
        migrated = migrate.apply_migration(config, entries, org_map, "T111")
        yaml_str = migrate.dump_yaml(migrated)
        reparsed = yaml.safe_load(yaml_str)
        # Top-level keys preserved.
        self.assertIn("slackWorkspaces", reparsed)
        self.assertIn("workstreams", reparsed)
        self.assertNotIn("githubOrgs", reparsed)

    def test_round_trip_preserves_workstream_fields(self):
        """Fields not touched by migration are preserved after round-trip."""
        config = yaml.safe_load(SINGLE_WORKSPACE_YAML)
        entries = [{"workspaceId": "T111", "name": "p", "tokensFile": "/t.json"}]
        org_map = {"my-org": "T111", "other-org": "T111"}
        migrated = migrate.apply_migration(config, entries, org_map, "T111")
        yaml_str = migrate.dump_yaml(migrated)
        reparsed = yaml.safe_load(yaml_str)
        channels = {ws["channelId"] for ws in reparsed["workstreams"]}
        self.assertIn("C0000001", channels)
        self.assertIn("C0000002", channels)
        self.assertIn("C0000003", channels)

    def test_already_multi_workspace_detected(self):
        """A config that already has slackWorkspaces is detected as multi-workspace."""
        config = yaml.safe_load(MULTI_WORKSPACE_YAML)
        self.assertEqual("multi", migrate.detect_format(config))

    def test_full_migration_round_trip(self):
        """Full pipeline: parse -> migrate -> dump -> reparse -> validate."""
        config = yaml.safe_load(SINGLE_WORKSPACE_YAML)
        entries = [
            {"workspaceId": "T111", "name": "w1", "botToken": "xoxb-a", "appToken": "xapp-a"},
            {"workspaceId": "T222", "name": "w2", "botToken": "xoxb-b", "appToken": "xapp-b"},
        ]
        org_map = {"my-org": "T111", "other-org": "T222"}
        migrated = migrate.apply_migration(config, entries, org_map, "T111")
        yaml_str = migrate.dump_yaml(migrated)
        reparsed = yaml.safe_load(yaml_str)
        errors = migrate.validate_migrated(reparsed)
        self.assertEqual([], errors, f"Unexpected validation errors: {errors}")


# ---------------------------------------------------------------------------
# File-level integration: write to temp file and read back
# ---------------------------------------------------------------------------

class TestFileIntegration(unittest.TestCase):

    def test_write_and_reload(self):
        config = yaml.safe_load(SINGLE_WORKSPACE_YAML)
        entries = [{"workspaceId": "T111", "name": "p", "tokensFile": "/tok.json"}]
        org_map = {"my-org": "T111", "other-org": "T111"}
        migrated = migrate.apply_migration(config, entries, org_map, "T111")
        yaml_str = migrate.dump_yaml(migrated)

        with tempfile.NamedTemporaryFile(mode="w", suffix=".yaml", delete=False) as f:
            f.write(yaml_str)
            tmp_path = f.name

        try:
            with open(tmp_path) as f:
                loaded = yaml.safe_load(f)
            self.assertEqual(
                migrated.get("slackWorkspaces"),
                loaded.get("slackWorkspaces"),
            )
        finally:
            os.unlink(tmp_path)


if __name__ == "__main__":
    unittest.main()
