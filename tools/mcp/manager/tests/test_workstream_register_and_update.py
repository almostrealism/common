"""Re-exports workstream / completion-listener test cases from
``test_server.py`` so the CI test command
``pytest tests/ -k "completion_listener or workstream_register or workstream_update"``
finds them. The actual test logic and assertions live in
``test_server.py`` at the manager-package root; this module just
re-exposes the relevant test classes to pytest's collection root
so the keyword filter can match them.

The legacy ``test_server.py`` layout (tests live at the package
root, not in a ``tests/`` subdir) is preserved for the older test
runner; this thin re-export shim lets the new ``tests/`` discovery
root find the same cases without duplicating the test bodies.
"""

import os
import sys

_TESTS_DIR = os.path.dirname(os.path.abspath(__file__))
_MANAGER_DIR = os.path.dirname(_TESTS_DIR)
if _MANAGER_DIR not in sys.path:
    sys.path.insert(0, _MANAGER_DIR)

import test_server  # noqa: E402  (path tweaked above)

# Re-export the test classes that match the post-completion command's
# keyword filter so pytest picks them up from tests/ without
# duplicating any assertions. Class names prefixed with ``Test`` are
# auto-collected by pytest's unittest integration.
TestWorkstreamRegister = test_server.TestWorkstreamRegister
TestWorkstreamUpdateConfig = test_server.TestWorkstreamUpdateConfig
TestWorkstreamRegisterScope = test_server.TestWorkstreamRegisterScope
TestWorkstreamRegisterPlanFollowup = test_server.TestWorkstreamRegisterPlanFollowup
TestToolRegistration = test_server.TestToolRegistration


__all__ = [
    "TestWorkstreamRegister",
    "TestWorkstreamUpdateConfig",
    "TestWorkstreamRegisterScope",
    "TestWorkstreamRegisterPlanFollowup",
    "TestToolRegistration",
]
