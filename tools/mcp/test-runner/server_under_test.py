"""Loads this directory's ``server.py`` under an unambiguous module name.

Nine MCP server directories each define a top-level ``server.py``. A test
run spanning more than one of them shares a single interpreter and a single
``sys.modules``, so a plain ``import server`` resolves to whichever
directory got there first — and the losing directory's tests then exercise
the wrong module. The symptom does not read as a name clash: ar-manager's
``server`` has no ``runner``, so these tests failed with ``AttributeError:
module 'server' has no attribute 'runner'``.

Adjusting ``sys.path`` cannot fix it, because the stale binding is already
cached under the name ``server``. Loading by explicit path under a name
only this directory uses removes the ambiguity outright, independent of
``sys.path`` order and of the order pytest happens to collect in. The
module is cached under that name so every test file here shares one
instance, exactly as ``import server`` used to give them.
"""

import importlib.util
import sys
from pathlib import Path

#: Distinct from the bare ``server`` that other directories also claim.
MODULE_NAME = "ar_test_runner_server"

_SERVER_PATH = Path(__file__).resolve().parent / "server.py"


def _load():
    """Return the cached module, importing it from disk on first use."""
    cached = sys.modules.get(MODULE_NAME)
    if cached is not None:
        return cached

    spec = importlib.util.spec_from_file_location(MODULE_NAME, _SERVER_PATH)
    module = importlib.util.module_from_spec(spec)
    # Registered before execution so a circular import inside server.py
    # resolves to the partially initialised module rather than re-entering.
    sys.modules[MODULE_NAME] = module
    spec.loader.exec_module(module)
    return module


server = _load()
