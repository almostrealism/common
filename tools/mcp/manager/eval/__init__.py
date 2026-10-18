"""Version-controlled evaluation set for the ``consult`` tool.

This directory is a package so that ``unittest`` discovery reaches
``test_consult_eval.py``. The CI step that runs the manager's Python tests is::

    python3 -m unittest discover -v -s tools/mcp/manager -p "test_*.py"

and discovery only recurses into importable packages — without this
``__init__.py`` the test would exist but never run in CI.

The model-backed evaluation runner (``run_consult_eval.py``) is *not* run in
CI: it needs an inference backend. What runs in CI is ``test_consult_eval.py``,
which exercises the deterministic graders against frozen responses (including
today's observed failures) and validates the case schema.
"""
