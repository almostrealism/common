FROM python:3.11-slim

WORKDIR /app

# Install dependencies first (cached layer)
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copy application code
COPY embedder.py store.py http_api.py server.py ./

# Data volume mount point
RUN mkdir -p /data

ENV AR_MEMORY_DATA_DIR=/data
ENV AR_MEMORY_HTTP_PORT=8020

EXPOSE 8020

HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8020/api/health', timeout=3)" || exit 1

ENTRYPOINT ["python", "server.py", "--http-only"]
