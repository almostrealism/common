"""
Divergence-guard tests for phase-wire-name consistency.

The phase wire names used by the Python MCP layer (_KNOWN_PHASE_WIRE_NAMES in
phase_config.py) must always exactly match the wire names declared by the Java
Phase enum (io.flowtree.jobs.agent.Phase).

Each enum constant carries a two-argument constructor:
    PHASE_NAME("wire-name", "description")

This test extracts the wire names from Phase.java using a regex and asserts
set-equality with _KNOWN_PHASE_WIRE_NAMES.  A mismatch in EITHER direction
fails — a phase that exists in the enum but is absent from the Python
allow-list means clients cannot configure that phase; a name accepted by
the Python layer but absent from the enum is a no-op or a typo.

The test is deliberately NOT a hardcoded expected set — that would itself be
another copy to drift.  Instead it derives both sides dynamically and compares
them so that adding a phase to the Java enum (the canonical source) naturally
causes this test to fail until the Python side is updated.
"""

import os
import re
import sys
import unittest

# Add the manager directory to the path so phase_config can be imported
_MANAGER_DIR = os.path.dirname(__file__)
if _MANAGER_DIR not in sys.path:
    sys.path.insert(0, _MANAGER_DIR)

from phase_config import _KNOWN_PHASE_WIRE_NAMES  # noqa: E402


def _locate_phase_java() -> str:
    """Walk up from this file to find Phase.java in the repo."""
    start = os.path.dirname(os.path.abspath(__file__))
    rel = os.path.join(
        "flowtree", "agents", "src", "main", "java",
        "io", "flowtree", "jobs", "agent", "Phase.java",
    )
    cwd = start
    for _ in range(8):
        candidate = os.path.join(cwd, rel)
        if os.path.isfile(candidate):
            return candidate
        parent = os.path.dirname(cwd)
        if parent == cwd:
            break
        cwd = parent
    return ""


def _extract_wire_names_from_java(path: str) -> set:
    """Parse Phase.java and return the set of wire-name string literals.

    Each Phase enum constant has a two-argument constructor:
        CONSTANT_NAME("wire-name", "description")
    This regex captures the first string argument (the wire name).
    """
    with open(path, encoding="utf-8") as fh:
        source = fh.read()
    # Match enum constant lines:  IDENT("wire-name", "description"),
    pattern = re.compile(
        r'^\s+[A-Z_]+\s*\(\s*"([^"]+)"\s*,',
        re.MULTILINE,
    )
    return {m.group(1) for m in pattern.finditer(source)}


class TestPhaseWireNameConsistency(unittest.TestCase):
    """Verifies that _KNOWN_PHASE_WIRE_NAMES in phase_config.py matches
    the Phase enum in Phase.java in both directions."""

    def setUp(self):
        self.phase_java = _locate_phase_java()
        if not self.phase_java:
            self.skipTest(
                "Phase.java not found — skipping divergence guard "
                "(test requires a checkout of the full repo)"
            )

    def test_python_allows_all_java_phases(self):
        """Every phase in the Java enum must be accepted by the Python validator."""
        java_names = _extract_wire_names_from_java(self.phase_java)
        python_names = set(_KNOWN_PHASE_WIRE_NAMES)
        missing_from_python = java_names - python_names
        self.assertEqual(
            missing_from_python,
            set(),
            "Phase(s) exist in Phase.java but are MISSING from "
            "_KNOWN_PHASE_WIRE_NAMES in phase_config.py — clients cannot "
            "configure these phases via phase_configs: " + str(sorted(missing_from_python)),
        )

    def test_python_accepts_no_phantom_phases(self):
        """Every phase accepted by the Python validator must exist in the Java enum."""
        java_names = _extract_wire_names_from_java(self.phase_java)
        python_names = set(_KNOWN_PHASE_WIRE_NAMES)
        phantom_in_python = python_names - java_names
        self.assertEqual(
            phantom_in_python,
            set(),
            "Phase(s) are in _KNOWN_PHASE_WIRE_NAMES but do NOT exist in "
            "Phase.java — these are phantom/stale entries that silently "
            "accept invalid phase keys: " + str(sorted(phantom_in_python)),
        )
