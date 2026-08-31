FROM python:3.12-slim
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends \
    fonts-noto-cjk libreoffice-core libreoffice-writer libreoffice-calc libreoffice-impress poppler-utils \
    && rm -rf /var/lib/apt/lists/*
RUN useradd --system --uid 10001 finflow
COPY pyproject.toml .
COPY app app
RUN pip install --no-cache-dir .
USER finflow
EXPOSE 8001
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8001"]
