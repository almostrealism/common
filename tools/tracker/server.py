#!/usr/bin/env python3
"""
ar-tracker server entry point.

Starts a Starlette HTTP REST API backed by a SQLite database.
The service is deployed via Docker and exposed on port 8030.

Configuration via environment variables:
    AR_TRACKER_DATA_DIR   - Directory for the SQLite database file
                            (default: /data)
    AR_TRACKER_PORT       - Port to listen on (default: 8030)
    AR_TRACKER_AUTH_TOKEN - Bearer token for authentication
                            (unset = open / dev mode)
"""

import os
import sys

sys.path.insert(0, os.path.dirname(__file__))

from store import TrackerStore
from api import create_http_app

DATA_DIR = os.environ.get("AR_TRACKER_DATA_DIR", "/data")
PORT = int(os.environ.get("AR_TRACKER_PORT", "8030"))
AUTH_TOKEN = os.environ.get("AR_TRACKER_AUTH_TOKEN", "").strip() or None

os.makedirs(DATA_DIR, exist_ok=True)
DB_PATH = os.path.join(DATA_DIR, "tracker.db")

store = TrackerStore(DB_PATH)
app = create_http_app(store, auth_token=AUTH_TOKEN)

if __name__ == "__main__":
    import uvicorn

    print(f"ar-tracker: Starting on port {PORT}", file=sys.stderr)
    print(f"ar-tracker: Database: {DB_PATH}", file=sys.stderr)
    print(f"ar-tracker: Auth: {'enabled' if AUTH_TOKEN else 'disabled'}", file=sys.stderr)

    uvicorn.run(app, host="0.0.0.0", port=PORT, log_level="info")
