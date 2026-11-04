FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    HOST=0.0.0.0 \
    PORT=8080 \
    TTL_SECONDS=7200

WORKDIR /app
COPY --chown=10001:10001 app/server.py /app/server.py
COPY --chown=10001:10001 app/public/index.html /app/public/index.html

USER 10001:10001
EXPOSE 8080

HEALTHCHECK --interval=60s --timeout=3s --start-period=5s --retries=3 \
  CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8080/api/health', timeout=2).read()"]

CMD ["python", "-B", "/app/server.py"]
