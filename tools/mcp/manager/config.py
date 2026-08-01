"""
Global configuration constants for the AR Manager MCP server.

All environment-variable reads and startup constants live here so that
auth, controller client, tracker client, and workspace-map modules can
import them without depending on server.py (and thus without circular
imports).
"""

import logging
import os
import sys

# ---------------------------------------------------------------------------
# Controller
# ---------------------------------------------------------------------------

CONTROLLER_URL = os.environ.get("AR_CONTROLLER_URL", "http://localhost:7780")
TOKEN_FILE = os.environ.get(
    "AR_MANAGER_TOKEN_FILE",
    os.path.expanduser("~/.config/ar/manager-tokens.json"),
)

# Rate limit: requests per minute per token/IP (configurable)
RATE_LIMIT = int(os.environ.get("AR_MANAGER_RATE_LIMIT", "60"))

# Input length limits
MAX_PROMPT_LEN = 50_000
MAX_CONTENT_LEN = 100_000
MAX_SHORT_STRING_LEN = 1_000

# Paths that are never valid targets for project_commit_plan
_SENSITIVE_PATH_PREFIXES = (".github/workflows/", ".github/actions/")

# Short-lived cache TTL (seconds) — shared by workspace-map and
# dispatch-capable caches, both sourced from the same /api/workstreams list.
WORKSPACE_CACHE_TTL = 30.0

# ---------------------------------------------------------------------------
# Tracker service
# ---------------------------------------------------------------------------

TRACKER_URL = os.environ.get("AR_TRACKER_URL", "http://ar-tracker:8030")
_TRACKER_AUTH_TOKEN = os.environ.get("AR_TRACKER_AUTH_TOKEN", "")

# ---------------------------------------------------------------------------
# Audit logger — writes to stderr alongside normal diagnostics
# ---------------------------------------------------------------------------

audit_log = logging.getLogger("ar-manager.audit")
audit_log.setLevel(logging.INFO)
if not audit_log.handlers:
    _handler = logging.StreamHandler(sys.stderr)
    _handler.setFormatter(logging.Formatter("%(asctime)s %(message)s"))
    audit_log.addHandler(_handler)


# ---------------------------------------------------------------------------
# Shared secret (HMAC temp-token signing)
# ---------------------------------------------------------------------------

def _load_shared_secret() -> str:
    """Load the shared secret from file or environment variable."""
    secret_file = os.environ.get("AR_MANAGER_SHARED_SECRET_FILE", "").strip()
    if secret_file and os.path.isfile(secret_file):
        try:
            with open(secret_file) as f:
                return f.read().strip()
        except OSError as e:
            print(f"ar-manager: WARNING: Failed to read shared secret file: {e}",
                  file=sys.stderr)
    return os.environ.get("AR_MANAGER_SHARED_SECRET", "").strip()


SHARED_SECRET = _load_shared_secret()
