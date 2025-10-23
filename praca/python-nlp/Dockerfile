FROM mirror.gcr.io/library/python:3.11-slim

# --- Podstawowe ENV + nazwa modelu ---
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    HF_HOME=/root/.cache/huggingface \
    NLP_ENABLE_MT5=true \
    MT5_MODEL_NAME=google/mt5-small

WORKDIR /app

# (opcjonalnie) systemowe zależności dla niektórych kółek – zwykle slim nie wymaga nic więcej
# RUN apt-get update && apt-get install -y --no-install-recommends \
#     git && \
#     rm -rf /var/lib/apt/lists/*

# --- Instalacja zależności Pythona (CPU) ---
COPY requirements.txt .
RUN pip install --no-cache-dir --upgrade pip && \
    pip install --no-cache-dir --index-url https://download.pytorch.org/whl/cpu torch==2.3.1 && \
    pip install --no-cache-dir \
        "transformers>=4.44.0" \
        "sentencepiece>=0.2.0" \
        "accelerate>=0.33.0" \
        "uvicorn[standard]>=0.30.0" \
        "fastapi>=0.115.0" && \
    pip install --no-cache-dir -r requirements.txt

# --- Pre-cache modelu mT5 w obrazie (online w trakcie buildu) ---
RUN python - <<'PY'
import os
from transformers import AutoTokenizer, AutoModelForSeq2SeqLM
model_name = os.environ.get("MT5_MODEL_NAME", "google/mt5-small")
print(f"[build] prefetching model: {model_name}")
AutoTokenizer.from_pretrained(model_name)
AutoModelForSeq2SeqLM.from_pretrained(model_name)
print("[build] mT5 cached OK")
PY

# --- Kod aplikacji ---
COPY app.py models.py ./

# --- Runtime ENV (offline + spokojniejsze tokenizery) ---
ENV TRANSFORMERS_OFFLINE=1 \
    TOKENIZERS_PARALLELISM=false

EXPOSE 8000

CMD ["python","-m","uvicorn","app:app","--host","0.0.0.0","--port","8000","--log-level","info"]
