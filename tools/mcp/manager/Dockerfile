# ─── Documentation corpus ────────────────────────────────────────────
# ar-manager grounds its memory answers in the project documentation, so the
# corpus has to travel with the image — a container has no checkout to read.
# DocsRetriever reads only *.md and *.html, so this stage takes the directories
# that can contain them and throws away everything else. Copying the module
# trees directly into the final image instead would carry the whole Java source
# tree for the sake of a few README files.
FROM python:3.11-slim AS docs

WORKDIR /src
COPY docs/ ./docs/
COPY CLAUDE.md ./
COPY base/ ./base/
COPY compute/ ./compute/
COPY domain/ ./domain/
COPY engine/ ./engine/
COPY extern/ ./extern/
COPY studio/ ./studio/
COPY flowtree/ ./flowtree/
COPY tools/ ./tools/

RUN find . -type f ! -name '*.md' ! -name '*.html' -delete \
    && find . -type d -empty -delete

# ─── Runtime ─────────────────────────────────────────────────────────
FROM python:3.11-slim

WORKDIR /app

# Install dependencies first (cached layer)
COPY tools/mcp/manager/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copy shared libraries
COPY tools/mcp/common/ /app/common/
ENV PYTHONPATH=/app/common

# Copy application code. Use a glob so that every module server.py imports is
# packaged automatically — server.py was refactored to import sibling modules
# (config.py, auth.py, controller_client.py, tracker_client.py, workspace_map.py,
# …) and an explicit file list silently omitted them, breaking startup with
# "ModuleNotFoundError: No module named 'config'". The glob copies all runtime
# modules; the test_*.py suites are not needed at runtime and are removed.
# test_dockerfile_packaging.py verifies this stays in sync with the imports.
COPY tools/mcp/manager/*.py ./
RUN rm -f /app/test_*.py

# Documentation corpus, pruned to markdown/HTML by the stage above.
# AR_DOCS_DIR points DocsRetriever at it; the directory it names must be the
# "docs" directory, whose parent is treated as the repo root.
COPY --from=docs /src /app/docs-root
ENV AR_DOCS_DIR=/app/docs-root/docs

# Token file mount point
RUN mkdir -p /config

ENV MCP_TRANSPORT=http
ENV MCP_PORT=8010
ENV AR_MANAGER_TOKEN_FILE=/config/manager-tokens.json

EXPOSE 8010

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8010/_health', timeout=3)" || exit 1

ENTRYPOINT ["python", "server.py"]
