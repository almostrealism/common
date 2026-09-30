#!/usr/bin/env python3
"""Resolve the workstream settings a plan branch declares for itself.

A plan can need something from the machine that implements it — Metal, for
instance — and the plan's author knows that before anyone else. The plan
states it in a YAML file beside the plan document, and the Verify Completion
workflow applies it to the branch's workstream when a person dispatches the
implementation, so approving the plan also approves the environment it asks
for.

Which file applies
------------------
Only branches under ``project/`` declare settings. The branch name without
``project/`` is matched against every ``<prefix>-workstream.yaml`` directly
in ``docs/plans/``. A prefix matches when the stripped branch name equals it
or continues it at a ``-`` boundary, so for ``project/plan-20260930-180401``
both ``plan-20260930-workstream.yaml`` and
``plan-20260930-180401-workstream.yaml`` match, while
``plan-2026093-workstream.yaml`` does not. When several match, the longest
prefix wins: a shorter prefix is a default, a longer one overrides it.

These files stay in ``docs/plans/`` after their branch merges, so a short
prefix keeps applying to every later branch it matches.

What a file may say
-------------------
Only the keys in ``ALLOWED_KEYS``. An unknown key, or a value of the wrong
shape, is an error rather than something to skip: a setting that silently
does nothing is worse than a dispatch that stops and says why.

- ``requiredLabels``: a mapping of node label names to values (strings), the
  labels every job on the workstream must match by default.

Usage::

    plan_workstream_config.py <branch> <plans-dir>

Prints a JSON object: ``{}`` when no file applies, otherwise ``{"file": ...}``
plus the settings. Exits 1, with a GitHub Actions ``::error::`` line, when
the applicable file is invalid.
"""

import json
import os
import re
import sys

import yaml

BRANCH_ROOT = "project/"
FILE_PATTERN = re.compile(r"^(?P<prefix>.+)-workstream\.yaml$")
ALLOWED_KEYS = ("requiredLabels",)


class InvalidConfig(Exception):
    """The applicable workstream file cannot be applied as written."""


def matching_file(branch, plans_dir):
    """Return the path of the workstream file that applies to *branch*, or None.

    Args:
        branch: The full branch name, e.g. ``project/plan-20260930-180401``.
        plans_dir: The directory holding the plan documents.
    """
    if not branch.startswith(BRANCH_ROOT) or not os.path.isdir(plans_dir):
        return None
    name = branch[len(BRANCH_ROOT):]
    best = None
    for entry in sorted(os.listdir(plans_dir)):
        match = FILE_PATTERN.match(entry)
        if not match:
            continue
        prefix = match.group("prefix")
        if name != prefix and not name.startswith(prefix + "-"):
            continue
        if best is None or len(prefix) > len(best[0]):
            best = (prefix, os.path.join(plans_dir, entry))
    return best[1] if best else None


def parse(path):
    """Read and validate one workstream file.

    Returns:
        The settings it declares, keyed as in ``ALLOWED_KEYS``.

    Raises:
        InvalidConfig: when the file is not valid YAML, is not a mapping, or
            declares a key or value this script does not accept.
    """
    try:
        with open(path) as f:
            data = yaml.safe_load(f)
    except yaml.YAMLError as e:
        raise InvalidConfig(f"{path} is not valid YAML: {e}")
    if data is None:
        return {}
    if not isinstance(data, dict):
        raise InvalidConfig(f"{path} must be a mapping of settings")
    unknown = sorted(set(data) - set(ALLOWED_KEYS))
    if unknown:
        raise InvalidConfig(
            f"{path} declares unsupported setting(s) {unknown}; allowed: {list(ALLOWED_KEYS)}")
    settings = {}
    if "requiredLabels" in data:
        labels = data["requiredLabels"]
        if not isinstance(labels, dict) or not labels:
            raise InvalidConfig(f"{path}: requiredLabels must be a non-empty mapping")
        for key, value in labels.items():
            if not isinstance(key, str) or not isinstance(value, str) or not key or not value:
                raise InvalidConfig(
                    f"{path}: requiredLabels entries must be non-empty strings "
                    f"(got {key!r}: {value!r}; quote values such as 'true')")
        settings["requiredLabels"] = labels
    return settings


def resolve(branch, plans_dir):
    """Return the settings that apply to *branch*, with the file they came from."""
    path = matching_file(branch, plans_dir)
    if path is None:
        return {}
    settings = parse(path)
    if not settings:
        return {}
    settings["file"] = "docs/plans/" + os.path.basename(path)
    return settings


def main(argv):
    if len(argv) != 3:
        print("Usage: plan_workstream_config.py <branch> <plans-dir>", file=sys.stderr)
        return 1
    try:
        print(json.dumps(resolve(argv[1], argv[2]), sort_keys=True))
    except InvalidConfig as e:
        print(f"::error::{e}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
