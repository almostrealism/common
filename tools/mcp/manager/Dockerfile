FROM python:3.11-slim

WORKDIR /app

# Install dependencies first (cached layer)
COPY manager/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copy shared libraries
COPY common/ /app/common/
ENV PYTHONPATH=/app/common

# Copy application code
COPY manager/server.py .

# Token file mount point
RUN mkdir -p /config

ENV MCP_TRANSPORT=http
ENV MCP_PORT=8010
ENV AR_MANAGER_TOKEN_FILE=/config/manager-tokens.json

EXPOSE 8010

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8010/_health', timeout=3)" || exit 1

ENTRYPOINT ["python", "server.py"]
