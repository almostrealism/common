FROM python:3.11-slim

WORKDIR /app

# Install dependencies first (cached layer)
COPY manager/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copy shared libraries
COPY common/ /app/common/
ENV PYTHONPATH=/app/common

# Copy application code. Use a glob so that every module server.py imports is
# packaged automatically — server.py was refactored to import sibling modules
# (config.py, auth.py, controller_client.py, tracker_client.py, workspace_map.py,
# …) and an explicit file list silently omitted them, breaking startup with
# "ModuleNotFoundError: No module named 'config'". The glob copies all runtime
# modules; the test_*.py suites are not needed at runtime and are removed.
# test_dockerfile_packaging.py verifies this stays in sync with the imports.
COPY manager/*.py ./
RUN rm -f /app/test_*.py

# Token file mount point
RUN mkdir -p /config

ENV MCP_TRANSPORT=http
ENV MCP_PORT=8010
ENV AR_MANAGER_TOKEN_FILE=/config/manager-tokens.json

EXPOSE 8010

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8010/_health', timeout=3)" || exit 1

ENTRYPOINT ["python", "server.py"]
