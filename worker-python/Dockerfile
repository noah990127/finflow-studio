FROM python:3.12-slim
WORKDIR /app
ARG DEBIAN_MIRROR=""
ARG PIP_INDEX_URL=https://pypi.org/simple
RUN if [ -n "$DEBIAN_MIRROR" ]; then \
      sed -i "s|http://deb.debian.org|$DEBIAN_MIRROR|g" /etc/apt/sources.list.d/debian.sources; \
    fi \
    && apt-get update && apt-get install -y --no-install-recommends \
    fonts-noto-cjk libreoffice-core libreoffice-writer libreoffice-calc libreoffice-impress poppler-utils \
    && rm -rf /var/lib/apt/lists/*
RUN useradd --system --uid 10001 finflow
COPY pyproject.toml .
COPY app app
RUN pip install --no-cache-dir --index-url "$PIP_INDEX_URL" .
USER finflow
EXPOSE 8001
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8001"]
