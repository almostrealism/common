FROM python:3.11-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY api.py store.py migrate.py server.py ./

RUN mkdir -p /data

ENV AR_TRACKER_DATA_DIR=/data
ENV AR_TRACKER_PORT=8030

EXPOSE 8030

HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8030/api/health', timeout=3)" || exit 1

ENTRYPOINT ["python", "server.py"]
