# app.py
import os
import re
import json
from typing import Any, Dict, List, Optional

import httpx
from fastapi import FastAPI, Query, Request
from pydantic import ValidationError

# <<< UŻYWAMY JEDNYCH MODELI I/O >>>
from models import DescribeIn, DescribeOut, ParamIn  # examples w DescribeOut = Optional[dict]

# =========================
#   KONFIGURACJA / ENV
# =========================
NLP_MODE = os.getenv("NLP_MODE", "ollama")  # plain | rule | ollama
OLLAMA_BASE_URL = os.getenv("OLLAMA_BASE_URL", "http://host.docker.internal:11434")
OLLAMA_MODEL = os.getenv("OLLAMA_MODEL", "llama3.1:8b-instruct-q4_K_M")
OLLAMA_TEMPERATURE = float(os.getenv("OLLAMA_TEMPERATURE", "0.3"))
OLLAMA_TOP_P = float(os.getenv("OLLAMA_TOP_P", "0.9"))
OLLAMA_TOP_K = int(os.getenv("OLLAMA_TOP_K", "60"))
OLLAMA_REPEAT_PENALTY = float(os.getenv("OLLAMA_REPEAT_PENALTY", "1.15"))
OLLAMA_NUM_CTX = int(os.getenv("OLLAMA_NUM_CTX", "4096"))
OLLAMA_NUM_PREDICT = int(os.getenv("OLLAMA_NUM_PREDICT", "256"))
NLP_DEBUG = os.getenv("NLP_DEBUG", "false").lower() == "true"

# =========================
#   UTILS
# =========================
def _build_param_docs(params: List[ParamIn]) -> List[Dict[str, str]]:
    out: List[Dict[str, str]] = []
    for p in params:
        base = (p.description or "").strip()
        if not base:
            n = (p.name or "").lower()
            if n in {"id", "user_id", "userid"}:
                base = "Identyfikator zasobu."
            elif n in {"page", "size", "limit"}:
                base = "Parametr paginacji."
            elif n in {"q", "query", "search"}:
                base = "Fraza wyszukiwania."
            else:
                base = f"Parametr `{p.name}`."
        out.append({"name": p.name, "doc": base})
    return out

def _type_to_words(t: Optional[str]) -> str:
    if not t:
        return "odpowiedź"
    tl = t.lower()
    if "string" in tl: return "napis (string)"
    if any(x in tl for x in ["int", "long", "integer"]): return "liczba całkowita"
    if any(x in tl for x in ["double", "float", "bigdec"]): return "liczba"
    if "boolean" in tl: return "wartość logiczna (true/false)"
    return f"obiekt `{t}`"

def _rule_based(payload: DescribeIn) -> DescribeOut:
    base = (payload.comment or "").strip()
    if not base:
        ret = _type_to_words(payload.returns.type if payload.returns else None)
        base = f"Zwraca {ret}."
    if not base.endswith("."):
        base += "."
    return DescribeOut(
        mediumDescription=base,
        paramDocs=_build_param_docs(payload.params or []),
        returnDoc=(payload.returns.description or "") if payload.returns else ""
    )

# =========================
#   PROMPT → OLLAMA
# =========================
SCHEMA_TEXT = """Zwróć TYLKO poprawny JSON o schemacie:
{
  "mediumDescription": "string (1–3 zdania, po polsku, zwięźle)",
  "notes": ["string","string","string"],
  "examples": {
    "requests": [{"curl": "curl -X ..."}],
    "response": {"status": 200, "body": {}}
  }
}
Reguły: dla endpointów tworzących zasoby użyj statusu 201; zawsze zwróć przynajmniej 1 przykład 'curl'.
"""


def build_prompt(payload: DescribeIn, audience: str = "intermediate") -> str:
    lines: List[str] = []
    lines.append("Piszesz dokumentację REST API po polsku dla inżyniera.")
    lines.append("Zwracasz TYLKO poprawny JSON w podanym schemacie. Bez Markdown, bez komentarzy, bez dodatkowego tekstu.")
    lines.append(f"Poziom odbiorcy: {audience}.")
    # —— NOWE, „twardsze” zasady ——
    lines.append("ZASADY:")
    lines.append("- Nie wymyślaj statusów ani pól – użyj WYŁĄCZNIE tego, co podano w kontekście.")
    lines.append("- Jeśli czegoś nie wiadomo z kontekstu – POMIŃ to (nie zgaduj).")
    lines.append("- Nie twórz zasad biznesowych (np. unikalność, wartości domyślne, walidacje), jeśli nie są jawnie podane.")
    lines.append("")
    # —— Kontekst wejściowy ——
    lines.append("Dane endpointu:")
    if payload.signature:
        lines.append(f"- Sygnatura: {payload.signature}")
    if payload.comment:
        lines.append(f"- Opis bazowy: {payload.comment}")
    if payload.params:
        lines.append("- Parametry:")
        for p in payload.params:
            pin = getattr(p, "inn", None) or "query"
            lines.append(f"  - {p.name} ({pin}, {p.type or ''}, required={str(p.required).lower()}): {p.description or ''}")
    if payload.returns and payload.returns.type:
        lines.append(f"- Zwracany typ: {payload.returns.type}")
    if getattr(payload, "implNotes", None):
        lines.append("- Notatki techniczne:")
        for n in payload.implNotes[:5]:
            lines.append(f"  - {n}")
    lines.append("")
    lines.append(SCHEMA_TEXT)
    return "\n".join(lines)


JSON_RE = re.compile(r"\{.*\}", re.DOTALL)

async def call_ollama(prompt: str) -> Dict[str, Any]:
    url = f"{OLLAMA_BASE_URL}/api/generate"
    body = {
        "model": OLLAMA_MODEL,
        "prompt": prompt,
        "stream": False,
        "options": {
            "temperature": OLLAMA_TEMPERATURE,
            "top_p": OLLAMA_TOP_P,
            "top_k": OLLAMA_TOP_K,
            "repeat_penalty": OLLAMA_REPEAT_PENALTY,
            "num_ctx": OLLAMA_NUM_CTX,
            "num_predict": OLLAMA_NUM_PREDICT,
        },
    }
    if NLP_DEBUG:
        print("[ollama:prompt]\n", prompt[:1200], "...\n")
    async with httpx.AsyncClient(timeout=90) as client:
        r = await client.post(url, json=body)
        r.raise_for_status()
        data = r.json()
    text = (data.get("response") or "").strip()
    if NLP_DEBUG:
        print("[ollama:raw] head:", text[:200].replace("\n", " "), "...")
    m = JSON_RE.search(text)
    if not m:
        return {}
    try:
        return json.loads(m.group(0))
    except Exception:
        return {}

def _sanitize_notes(notes: Any) -> List[str]:
    items = notes if isinstance(notes, list) else []
    out: List[str] = []
    for n in items[:3]:
        s = (str(n) if n is not None else "").strip()
        if not s:
            continue
        if len(s) > 220:
            s = s[:220] + "…"
        out.append(s)
    return out

def _coerce_examples(raw: Any) -> Optional[Dict[str, Any]]:
    """
    Oczekujemy słownika: {
      "requests": [{"curl": "..."}, ...],
      "response": {"status": int, "body": {...}}
    }
    Zwracamy dict lub None.
    """
    if not isinstance(raw, dict):
        return None

    out: Dict[str, Any] = {}

    # requests -> lista obiektów {"curl": "..."}
    reqs = raw.get("requests", [])
    coerced_reqs: List[Dict[str, str]] = []
    if isinstance(reqs, list):
        for r in reqs[:2]:
            if isinstance(r, dict) and "curl" in r:
                c = str(r.get("curl") or "").strip()
                if c:
                    coerced_reqs.append({"curl": c})
            elif isinstance(r, str):
                c = r.strip()
                if c:
                    coerced_reqs.append({"curl": c})
    if coerced_reqs:
        out["requests"] = coerced_reqs

    # response -> {"status": int, "body": dict}
    resp = raw.get("response", {})
    status = 200
    body = {}
    if isinstance(resp, dict):
        try:
            status = int(resp.get("status", 200))
        except Exception:
            status = 200
        body = resp.get("body", {})
        if not isinstance(body, dict):
            body = {}
    out["response"] = {"status": status, "body": body}

    return out or None

def _validate_ai_doc(raw: Dict[str, Any]) -> Optional[DescribeOut]:
    if not raw:
        return None
    try:
        md = (raw.get("mediumDescription") or "").strip()
        notes = _sanitize_notes(raw.get("notes"))
        examples = _coerce_examples(raw.get("examples"))

        if not (md or notes or examples):
            return None

        return DescribeOut(
            mediumDescription=md,
            notes=notes or [],
            examples=examples,           # <-- DICT (spójne z models.py)
            paramDocs=[],                # uzupełnimy z wejścia
            returnDoc=""
        )
    except ValidationError as ve:
        if NLP_DEBUG:
            print("[ai-validate] error:", ve)
        return None

# =========================
#   FASTAPI
# =========================
app = FastAPI(title="NLP Describe Service (Ollama)", version="2.0.1")

@app.get("/healthz")
def healthz():
    return {
        "status": "ok",
        "mode": NLP_MODE,
        "ollama": {
            "base_url": OLLAMA_BASE_URL,
            "model": OLLAMA_MODEL,
            "options": {
                "temperature": OLLAMA_TEMPERATURE,
                "top_p": OLLAMA_TOP_P,
                "top_k": OLLAMA_TOP_K,
                "repeat_penalty": OLLAMA_REPEAT_PENALTY,
                "num_ctx": OLLAMA_NUM_CTX,
                "num_predict": OLLAMA_NUM_PREDICT,
            },
        },
        "debug": NLP_DEBUG,
    }

@app.post("/describe", response_model=DescribeOut)
async def describe(
    payload: DescribeIn,
    request: Request,
    mode: str = Query("ollama", pattern="^(plain|rule|ollama)$"),
    audience: str = Query("intermediate", pattern="^(beginner|intermediate|advanced)$"),
    strict: bool = Query(True)
):
    if NLP_DEBUG:
        who = request.client.host if request.client else "?"
        print(f"[describe] from={who} mode={mode} symbol={payload.symbol}")

    if mode == "plain":
        return DescribeOut(
            mediumDescription="",
            paramDocs=_build_param_docs(payload.params or []),
            returnDoc=""
        )

    if mode == "rule":
        rb = _rule_based(payload)
        rb.paramDocs = _build_param_docs(payload.params or [])
        return rb

    # mode == "ollama"
    prompt = build_prompt(payload, audience=audience)
    raw = await call_ollama(prompt)
    doc = _validate_ai_doc(raw)
    if doc:
        doc.paramDocs = _build_param_docs(payload.params or [])
        return doc

    # fallback – nie zepsuj generowania po stronie Javy
    return DescribeOut(
        mediumDescription="",
        paramDocs=_build_param_docs(payload.params or []),
        returnDoc=""
    )

if __name__ == "__main__":
    import uvicorn
    uvicorn.run("app:app", host="0.0.0.0", port=int(os.getenv("PORT", "8000")))
