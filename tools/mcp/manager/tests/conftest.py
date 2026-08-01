"""Shared pytest configuration for ``tools/mcp/manager/tests``.

Sets the env vars required by the real-transport tests
(``test_send_message_transport.py``) before any test file imports
``server``. The env var values are read at import time by
``server._load_shared_secret()`` and the module is cached, so a
test file that sets them after another test file has already
imported ``server`` is too late. Conftest runs first.
"""
import os


def pytest_configure(config):
    # Use a controller URL that fails fast (unroutable port 1) so
    # the workspace-cache lookup inside the auth middleware doesn't
    # sit on a real-network timeout during the test. The tests in
    # this directory only exercise the FastMCP transport layer; the
    # controller is never actually called by any of the tools the
    # test invokes. Force the value (not setdefault) so the
    # controller URL set by an outer runner — if any — is
    # overridden for the test suite.
    os.environ["AR_CONTROLLER_URL"] = "http://127.0.0.1:1"
    # The HMAC temp-token secret used by the real-transport tests.
    # Mints against this value at test time; the server thread that
    # runs uvicorn loads the same value at import time. Setting
    # the env var here guarantees the value is in place before any
    # test file imports ``server``. ``_load_shared_secret()`` reads
    # the env var once at module-import time and the value is then
    # frozen on the module, so the server thread's copy of the
    # module and the main test thread's copy MUST agree.
    os.environ["AR_MANAGER_SHARED_SECRET"] = "real-transport-test-secret"
