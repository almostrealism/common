#!/usr/bin/env python3
"""
migrate_workstreams.py — convert a single-workspace workstreams.yaml to the
multi-workspace format introduced in the multi-tenant FlowTree update.

BEFORE RUNNING: back up your existing config:
    cp workstreams.yaml workstreams.yaml.bak

Basic usage:
    python3 tools/migrate_workstreams.py workstreams.yaml

Dry-run mode (no file written, shows what would change):
    python3 tools/migrate_workstreams.py --dry-run workstreams.yaml

Custom output path:
    python3 tools/migrate_workstreams.py --output my-new-config.yaml workstreams.yaml

Expected prompts
----------------
For each GitHub org found in the top-level ``githubOrgs`` section the tool asks:

  Which workspace for org '<name>'? Enter number to reuse, or press Enter for new:

  For a new workspace:
    Workspace name (human-readable label): my-team
    Slack workspace ID (T...): T0123456789
    Path to tokens JSON file (or press Enter for inline tokens): /config/slack-tokens.json
    Default channel ID (or Enter to skip): C0123456789
    Channel owner user ID (or Enter to skip): U0123456789

Verifying the migration
-----------------------
After migration, review the output file and rename it when satisfied:
    mv workstreams.migrated.yaml workstreams.yaml

Then restart the FlowTree controller. The controller validates the config on startup.
"""

import argparse
import copy
import sys
from typing import Dict, List, Optional, Tuple

try:
    import yaml
except ImportError:
    print("ERROR: PyYAML is required. Install it with: pip install pyyaml", file=sys.stderr)
    sys.exit(1)


# ---------------------------------------------------------------------------
# Format detection
# ---------------------------------------------------------------------------

def detect_format(config: dict) -> str:
    """Return 'multi' if already multi-workspace, 'single' otherwise."""
    return "multi" if config.get("slackWorkspaces") else "single"


# ---------------------------------------------------------------------------
# Org collection
# ---------------------------------------------------------------------------

def collect_defined_orgs(config: dict) -> List[str]:
    """Return org names from the top-level githubOrgs section, preserving order."""
    return list((config.get("githubOrgs") or {}).keys())


def collect_workstream_orgs(config: dict) -> Tuple[List[str], List[str]]:
    """
    Return (referenced_orgs, orphaned_orgs).

    referenced_orgs: all org names seen in workstream githubOrg fields (ordered).
    orphaned_orgs: orgs referenced by workstreams but absent from githubOrgs.
    """
    defined = set((config.get("githubOrgs") or {}).keys())
    referenced_ordered: List[str] = []
    seen: set = set()
    for ws in (config.get("workstreams") or []):
        org = ws.get("githubOrg")
        if org and org not in seen:
            referenced_ordered.append(org)
            seen.add(org)
    orphaned = [o for o in referenced_ordered if o not in defined]
    return referenced_ordered, orphaned


# ---------------------------------------------------------------------------
# Core migration logic (testable without I/O)
# ---------------------------------------------------------------------------

def apply_migration(
    config: dict,
    workspace_entries: List[dict],
    org_to_workspace_id: Dict[str, str],
    default_workspace_id: Optional[str] = None,
) -> dict:
    """
    Build and return the migrated config dict.

    Args:
        config: original parsed config (not mutated).
        workspace_entries: list of SlackWorkspaceEntry dicts to place in slackWorkspaces.
        org_to_workspace_id: maps org name to workspace ID.
        default_workspace_id: workspace ID for workstreams whose githubOrg is absent
            or not in org_to_workspace_id. Falls back to the first workspace entry.

    Returns:
        A new config dict with slackWorkspaces populated and slackWorkspaceId set
        on every workstream. The top-level githubOrgs section is moved into the
        appropriate workspace entries.
    """
    new_config = copy.deepcopy(config)

    # Remove top-level githubOrgs — tokens now live inside each workspace entry.
    top_orgs = new_config.pop("githubOrgs", {}) or {}

    # Embed the relevant githubOrgs into each workspace entry.
    for i, ws_entry in enumerate(workspace_entries):
        if not ws_entry.get("githubOrgs"):
            ws_id = ws_entry.get("workspaceId")
            ws_orgs = {
                org: token_entry
                for org, token_entry in top_orgs.items()
                if org_to_workspace_id.get(org) == ws_id
            }
            if ws_orgs:
                workspace_entries[i] = dict(ws_entry)
                workspace_entries[i]["githubOrgs"] = ws_orgs

    new_config["slackWorkspaces"] = workspace_entries

    # Determine fallback workspace ID for workstreams with no githubOrg.
    fallback = default_workspace_id
    if not fallback and workspace_entries:
        fallback = workspace_entries[0].get("workspaceId")

    # Assign slackWorkspaceId to each workstream.
    for ws in (new_config.get("workstreams") or []):
        if ws.get("slackWorkspaceId"):
            continue
        org = ws.get("githubOrg")
        if org and org in org_to_workspace_id:
            ws["slackWorkspaceId"] = org_to_workspace_id[org]
        elif fallback:
            ws["slackWorkspaceId"] = fallback

    return new_config


def validate_migrated(config: dict) -> List[str]:
    """
    Return a list of validation error strings (empty means valid).

    Checks:
    - Every workstream has a slackWorkspaceId.
    - Every slackWorkspaceId references a defined workspace.
    - Every workspace has token credentials (tokensFile or botToken+appToken).
    """
    errors: List[str] = []
    defined_ids = {ws.get("workspaceId") for ws in (config.get("slackWorkspaces") or [])}

    for i, ws in enumerate(config.get("workstreams") or []):
        ws_id = ws.get("slackWorkspaceId")
        label = ws.get("channelName") or ws.get("channelId") or f"workstream[{i}]"
        if not ws_id:
            errors.append(f"{label}: missing slackWorkspaceId")
        elif ws_id not in defined_ids:
            errors.append(f"{label}: slackWorkspaceId '{ws_id}' not defined in slackWorkspaces")

    for ws_entry in (config.get("slackWorkspaces") or []):
        ws_id = ws_entry.get("workspaceId", "<unknown>")
        has_tokens = ws_entry.get("tokensFile") or (
            ws_entry.get("botToken") and ws_entry.get("appToken")
        )
        if not has_tokens:
            errors.append(
                f"Workspace '{ws_id}': no tokens configured "
                "(set tokensFile or both botToken and appToken)"
            )

    return errors


# ---------------------------------------------------------------------------
# YAML serialization
# ---------------------------------------------------------------------------

class _OrderedDumper(yaml.Dumper):
    """YAML dumper that preserves dict insertion order and omits aliases."""

    def ignore_aliases(self, data):
        return True


def _dict_representer(dumper, data):
    return dumper.represent_mapping("tag:yaml.org,2002:map", data.items())


_OrderedDumper.add_representer(dict, _dict_representer)


def dump_yaml(config: dict) -> str:
    """Serialize config to YAML, preserving dict insertion order."""
    return yaml.dump(config, Dumper=_OrderedDumper, default_flow_style=False,
                     allow_unicode=True)


# ---------------------------------------------------------------------------
# Interactive prompts
# ---------------------------------------------------------------------------

def _prompt(message: str, required: bool = True) -> str:
    """Prompt until a non-empty value is entered (or allow empty when not required)."""
    while True:
        value = input(message).strip()
        if value or not required:
            return value
        print("  (This field is required — please enter a value.)")


def interactive_collect_workspace_assignments(
    orgs: List[str],
) -> Tuple[List[dict], Dict[str, str]]:
    """
    Interactively prompt the admin to assign each GitHub org to a Slack workspace.

    Returns:
        workspace_entries: ordered list of workspace config dicts.
        org_to_workspace_id: maps org name to Slack team ID.
    """
    workspace_entries: List[dict] = []
    org_to_workspace_id: Dict[str, str] = {}

    print()
    print("=" * 60)
    print("Workspace Assignment")
    print("=" * 60)
    orgs_display = ", ".join(orgs) if orgs else "(none)"
    print(f"Found {len(orgs)} GitHub org(s) to assign: {orgs_display}")
    print()

    for org in orgs:
        print(f"GitHub org: {org}")

        # Offer reuse of previously-entered workspaces.
        if workspace_entries:
            print("  Existing workspaces:")
            for idx, we in enumerate(workspace_entries, start=1):
                print(f"    [{idx}] {we['name']} ({we['workspaceId']})")
            choice = input(
                f"  Which workspace for org '{org}'?"
                " Enter number to reuse, or press Enter for new: "
            ).strip()
            if choice.isdigit():
                chosen_idx = int(choice) - 1
                if 0 <= chosen_idx < len(workspace_entries):
                    ws_id = workspace_entries[chosen_idx]["workspaceId"]
                    org_to_workspace_id[org] = ws_id
                    print(f"  -> Assigned to '{workspace_entries[chosen_idx]['name']}'")
                    print()
                    continue
                print("  Invalid choice — creating a new workspace.")

        # Collect new workspace details.
        print(f"  New workspace for org '{org}':")
        ws_name = _prompt("    Workspace name (human-readable label): ")
        ws_id = _prompt("    Slack workspace ID (T...): ")

        ws_entry: dict = {"workspaceId": ws_id, "name": ws_name}

        tokens_file = input(
            "    Path to tokens JSON file (or press Enter for inline tokens): "
        ).strip()
        if tokens_file:
            ws_entry["tokensFile"] = tokens_file
        else:
            ws_entry["botToken"] = _prompt("    Bot token (xoxb-...): ")
            ws_entry["appToken"] = _prompt("    App token (xapp-...): ")

        default_channel = input("    Default channel ID (or Enter to skip): ").strip()
        if default_channel:
            ws_entry["defaultChannel"] = default_channel

        channel_owner = input("    Channel owner user ID (or Enter to skip): ").strip()
        if channel_owner:
            ws_entry["channelOwnerUserId"] = channel_owner

        workspace_entries.append(ws_entry)
        org_to_workspace_id[org] = ws_id
        print(f"  -> New workspace '{ws_name}' ({ws_id}) created.")
        print()

    return workspace_entries, org_to_workspace_id


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(
        description=(
            "Migrate a single-workspace workstreams.yaml to the multi-workspace format. "
            "Back up your original file before running!"
        )
    )
    parser.add_argument("input", help="Path to the existing workstreams.yaml")
    parser.add_argument(
        "--output",
        default=None,
        help="Output file path (default: <input base>.migrated.yaml)",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Print the migrated YAML without writing a file",
    )
    args = parser.parse_args()

    input_path: str = args.input

    if args.output:
        output_path = args.output
    elif input_path.endswith(".yaml"):
        output_path = input_path[:-5] + ".migrated.yaml"
    elif input_path.endswith(".yml"):
        output_path = input_path[:-4] + ".migrated.yaml"
    else:
        output_path = input_path + ".migrated.yaml"

    # Load input.
    try:
        with open(input_path) as f:
            config = yaml.safe_load(f) or {}
    except FileNotFoundError:
        print(f"ERROR: File not found: {input_path}", file=sys.stderr)
        sys.exit(1)
    except yaml.YAMLError as exc:
        print(f"ERROR: Failed to parse YAML: {exc}", file=sys.stderr)
        sys.exit(1)

    # Detect format.
    if detect_format(config) == "multi":
        print(
            "Config already uses multi-workspace format (slackWorkspaces section present).\n"
            "No migration needed."
        )
        sys.exit(0)

    # Enumerate orgs.
    defined_orgs = collect_defined_orgs(config)
    referenced_orgs, orphaned_orgs = collect_workstream_orgs(config)

    print(f"Detected single-workspace config: {input_path}")
    print(f"  Defined GitHub orgs (githubOrgs): {', '.join(defined_orgs) or '(none)'}")
    print(f"  Orgs referenced by workstreams:   {', '.join(referenced_orgs) or '(none)'}")
    if orphaned_orgs:
        print(
            f"  WARNING: Orgs used by workstreams but not defined in githubOrgs: "
            f"{', '.join(orphaned_orgs)}"
        )

    # Workstreams with no githubOrg will fall back to the first workspace.
    no_org_workstreams = [
        ws for ws in (config.get("workstreams") or []) if not ws.get("githubOrg")
    ]
    if no_org_workstreams:
        labels = [ws.get("channelName") or ws.get("channelId") or "?" for ws in no_org_workstreams]
        print(
            f"  Workstreams without githubOrg (assigned to first workspace): "
            f"{', '.join(labels)}"
        )

    # All orgs to assign: defined first, then orphans (deduplicated).
    all_orgs = list(dict.fromkeys(defined_orgs + orphaned_orgs))

    if not all_orgs:
        print()
        print(
            "WARNING: No GitHub orgs found. The migrated config will have an empty\n"
            "  slackWorkspaces section. Add workspace entries manually after migration."
        )
        print()
        answer = input("Continue anyway? [y/N]: ").strip().lower()
        if answer != "y":
            sys.exit(0)
        workspace_entries: List[dict] = []
        org_to_workspace_id: Dict[str, str] = {}
    elif args.dry_run:
        print()
        print(
            f"DRY-RUN: would prompt for workspace assignment of "
            f"{len(all_orgs)} org(s): {', '.join(all_orgs)}"
        )
        workspace_entries = [
            {
                "workspaceId": "T_PLACEHOLDER",
                "name": "placeholder",
                "tokensFile": "/config/slack-tokens.json",
            }
        ]
        org_to_workspace_id = {org: "T_PLACEHOLDER" for org in all_orgs}
    else:
        workspace_entries, org_to_workspace_id = interactive_collect_workspace_assignments(all_orgs)

    # Apply migration.
    fallback_id = workspace_entries[0]["workspaceId"] if workspace_entries else None
    migrated = apply_migration(config, workspace_entries, org_to_workspace_id, fallback_id)

    # Validate.
    errors = validate_migrated(migrated)
    output_yaml = dump_yaml(migrated)

    if args.dry_run:
        print()
        print("--- DRY-RUN OUTPUT (not written to disk) ---")
        print(output_yaml)
        print("--- END DRY-RUN OUTPUT ---")
        if errors:
            print("\nValidation issues:")
            for e in errors:
                print(f"  x {e}")
        else:
            print("Validation: OK")
        sys.exit(0)

    # Write output.
    with open(output_path, "w") as f:
        f.write(output_yaml)

    print(f"\nMigrated config written to: {output_path}")

    if errors:
        print("\nValidation issues (review before deploying):")
        for e in errors:
            print(f"  x {e}")
        print("\nFix the above issues before deploying.")
    else:
        print("\nValidation: all workstreams have a valid slackWorkspaceId")
        print(f"\nNext steps:")
        print(f"  1. Review the output:         {output_path}")
        print(f"  2. Back up your original:     cp {input_path} {input_path}.bak")
        print(f"  3. Replace the original:      mv {output_path} {input_path}")
        print(f"  4. Restart the FlowTree controller.")


if __name__ == "__main__":
    main()
