# ---- Build: install dependencies into a virtualenv ----
FROM python:3.12-slim AS build
ENV PIP_NO_CACHE_DIR=1 PIP_DISABLE_PIP_VERSION_CHECK=1
RUN python -m venv /opt/venv
ENV PATH=/opt/venv/bin:$PATH
COPY requirements.txt .
RUN pip install -r requirements.txt

# ---- Runtime: non-root, no build tools, no tests or eval datasets ----
FROM python:3.12-slim
RUN groupadd -g 10001 pitsch && useradd -u 10001 -g pitsch -M -s /usr/sbin/nologin pitsch
COPY --from=build /opt/venv /opt/venv
WORKDIR /app
COPY --chown=pitsch:pitsch app ./app
USER 10001
ENV PATH=/opt/venv/bin:$PATH \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PITSCH_MODE=production \
    PORT=8000 \
    WEB_CONCURRENCY=2
EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD python -c "import os,urllib.request;urllib.request.urlopen(f'http://127.0.0.1:{os.environ.get(\"PORT\",\"8000\")}/health/live',timeout=4)" || exit 1
# The service is internal (backend → AI service); keep it on a private network.
CMD ["sh", "-c", "exec uvicorn app.main:app --host 0.0.0.0 --port ${PORT} --workers ${WEB_CONCURRENCY} --no-server-header --proxy-headers --forwarded-allow-ips=${FORWARDED_ALLOW_IPS:-127.0.0.1}"]
