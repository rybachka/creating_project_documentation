FROM python:3.11-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 TOKENIZERS_PARALLELISM=false NLP_DEBUG=true OLLAMA_BASE_URL=http://host.docker.internal:11434
WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir --upgrade pip && \
    pip install --no-cache-dir -r requirements.txt && \
    # awaryjnie, gdyby reqs nie miał httpx:
    pip install --no-cache-dir httpx==0.27.2 || true

COPY app.py models.py ./
EXPOSE 8000
CMD ["python","-m","uvicorn","app:app","--host","0.0.0.0","--port","8000","--log-level","info"]
