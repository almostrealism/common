FROM python:3.11-slim

WORKDIR /app

# The build context is tools/mcp (see the ar-memory service in
# flowtree/runtime/controller/docker-compose.yml), so the image can carry the
# shared library in tools/mcp/common that the server imports. The whole
# directory is copied, not a chosen file: the memory server has already broken
# a deploy by importing a common module the image did not contain.

# Install dependencies first (cached layer)
COPY memory/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copy application code and the shared library it imports.
COPY memory/*.py ./
COPY common/ /app/common/
ENV PYTHONPATH=/app/common

# Data volume mount point
RUN mkdir -p /data

ENV AR_MEMORY_DATA_DIR=/data
ENV AR_MEMORY_HTTP_PORT=8020

EXPOSE 8020

HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8020/api/health', timeout=3)" || exit 1

ENTRYPOINT ["python", "server.py", "--http-only"]
