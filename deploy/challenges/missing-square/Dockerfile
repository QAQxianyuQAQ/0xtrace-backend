FROM python:3.12-slim-bookworm
WORKDIR /app
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PORT=5000
COPY app.py entrypoint.py puzzle.py sessions.py read_flag.py ./
COPY web ./web
USER 10001:10001
EXPOSE 5000
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:5000/SACC', timeout=2)"
ENTRYPOINT ["python", "/app/entrypoint.py"]
