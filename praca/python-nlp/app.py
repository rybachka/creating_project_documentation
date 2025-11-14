import os
import re
import json
from typing import Any, Dict, List, Optional
import httpx
from fastapi import FastAPI, Query, Request
from pydantic import BaseModel, ValidationError

from models import DescribeIn, DescribeOut, ParamIn


#   KONFIGURACJA / ENV
OLLAMA_BASE_URL = os.getenv("OLLAMA_BASE_URL", "http://host.docker.internal:11434")
OLLAMA_MODEL = os.getenv("OLLAMA_MODEL", "llama3.1:8b-instruct-q4_K_M")
OLLAMA_TEMPERATURE = float(os.getenv("OLLAMA_TEMPERATURE", "0.3"))
OLLAMA_TOP_P = float(os.getenv("OLLAMA_TOP_P", "0.9"))
OLLAMA_TOP_K = int(os.getenv("OLLAMA_TOP_K", "60"))
OLLAMA_REPEAT_PENALTY = float(os.getenv("OLLAMA_REPEAT_PENALTY", "1.15"))
OLLAMA_NUM_CTX = int(os.getenv("OLLAMA_NUM_CTX", "4096"))
OLLAMA_NUM_PREDICT = int(os.getenv("OLLAMA_NUM_PREDICT", "256"))
NLP_DEBUG = os.getenv("NLP_DEBUG", "false").lower() == "true"


#   UTILS
def _build_param_docs(params: List[ParamIn]) -> List[Dict[str, str]]:
    out: List[Dict[str, str]] = []
    for p in params:
        name = getattr(p, "name", "") or ""
        desc = (getattr(p, "description", "") or "").strip()
        out.append({"name": name, "doc": desc})
    return out

#   PROMPTY (ENDPOINTY)
def _common_context(payload: DescribeIn) -> str:
    lines: List[str] = []
    lines.append("DANE ENDPOINTU (IR):")
    if getattr(payload, "operationId", None):
        lines.append(f"- operationId: {payload.operationId}")
    if getattr(payload, "method", None):
        lines.append(f"- method: {payload.method}")
    if getattr(payload, "path", None):
        lines.append(f"- path: {payload.path}")
    params = getattr(payload, "params", []) or []
    if params:
        p_lines = []
        for p in params:
            pin = getattr(p, "in_", None) or getattr(p, "inn", None) or getattr(p, "in", None) or "query"
            p_lines.append({
                "name": getattr(p, "name", ""),
                "in": pin,
                "type": getattr(p, "type", "") or "",
                "required": bool(getattr(p, "required", False)),
                "description": getattr(p, "description", "") or ""
            })
        lines.append(f"- params: {json.dumps(p_lines, ensure_ascii=False)}")
    if getattr(payload, "requestBody", None):
        try:
            lines.append(f"- requestBody: {json.dumps(payload.requestBody, ensure_ascii=False)}")
        except Exception:
            lines.append("- requestBody: {}")
    if getattr(payload, "returns", None):
        try:
            lines.append(f"- returns: {json.dumps(payload.returns.dict(), ensure_ascii=False)}")
        except Exception:
            lines.append("- returns: {}")
    if getattr(payload, "notes", None):
        try:
            lines.append(f"- notes: {json.dumps(payload.notes[:6], ensure_ascii=False)}")
        except Exception:
            pass
    if getattr(payload, "implNotes", None):
        try:
            lines.append(f"- implNotes: {json.dumps(payload.implNotes[:6], ensure_ascii=False)}")
        except Exception:
            pass
    return "\n".join(lines)

def build_prompt_beginner(payload: DescribeIn) -> str:
    method = getattr(payload, "method", "") or ""
    path = getattr(payload, "path", "") or ""
    symbol = getattr(payload, "symbol", "") or ""
    raw_comment = (
        getattr(payload, "rawComment", None)
        or getattr(payload, "comment", None)
        or ""
    )

    return f"""
Jesteś asystentem, który pisze dokumentację REST API dla JUNIORA.

Użytkownik widzi jeden endpoint na raz.

Twoja odpowiedź MUSI być WYŁĄCZNIE poprawnym JSON-em (bez żadnego tekstu przed ani po) dokładnie wg schematu:

{{
  "mediumDescription": "…",
  "notes": ["…"],
  "examples": {{
    "requests": ["curl ..."],
    "response": {{
      "status": 200,
      "body": {{}}
    }}
  }}
}}

ZASADY DLA POZIOMU BEGINNER:

1. mediumDescription:
   - 1–2 zdania po polsku.
   - Wyjaśnij CO robi endpoint i w JAKIM prostym scenariuszu biznesowym go używamy.
   - Użyj nazw ze ścieżki/metody, np. "/api/orders/{{id}}" → "Pobiera szczegóły zamówienia o podanym ID."
   - Zero pustych formułek typu "Pobiera zasób", "Zwraca obiekt".

2. Autoryzacja:
   - Jeśli z danych wynika, że endpoint wymaga logowania → w mediumDescription wspomnij o nagłówku:
     "Wymaga nagłówka Authorization: Bearer <token>."
   - Jeśli wygląda na publiczny (np. login/rejestracja/health/docs) → NIE pisz o bearer.

3. notes:
   - 0–3 krótkie punkty.
   - Jeśli pasuje, użyj ich do prostego wyjaśnienia kluczowych kodów odpowiedzi:
     - 200 lub 201 – gdy wszystko poszło OK,
     - 400 – gdy dane wejściowe są niepoprawne,
     - 401 lub 403 – gdy brakuje uprawnień lub tokenu,
     - 404 – gdy zasób nie istnieje.
   - Pisz po ludzku, bez żargonu.

4. examples.requests:
   - DOKŁADNIE 1 prosty przykład curl dla TEGO endpointu.
   - Użyj metody {method} i ścieżki {path}.
   - Pokaż tylko to, co potrzebne: URL, ewentualnie Authorization Bearer i Content-Type przy body.
   - Żadnych dziwnych nagłówków technicznych, brak placeholderów typu "<string>".

5. examples.response:
   - Jedna przykładowa odpowiedź "szczęśliwej ścieżki":
     - Dla GET zwykle status 200,
     - Dla POST tworzących zasób zwykle 201,
     - Dla DELETE lub gdy typ zwrotny to void → 204 i body = {{}}.
   - body ma pokazywać strukturę na podstawie danych wejściowych, bez fantazji.

6. Ogólne:
   - Nie dodawaj pól ani logiki spoza wejścia.
   - Żadnego Markdowna. Tylko JSON.

DANE TECHNICZNE ENDPOINTU:
- method: {method}
- path: {path}
- symbol: {symbol}
- rawComment: {raw_comment}

Na podstawie powyższego i IR poniżej wygeneruj WYŁĄCZNIE JSON:
{_common_context(payload)}
"""

def build_prompt_advanced(payload: DescribeIn) -> str:
    method = getattr(payload, "method", "") or ""
    path = getattr(payload, "path", "") or ""
    symbol = getattr(payload, "symbol", "") or ""
    raw_comment = (
        getattr(payload, "rawComment", None)
        or getattr(payload, "comment", None)
        or ""
    )

    return f"""
Piszesz dokumentację REST API po polsku dla zaawansowanego backend developera.

Twoja odpowiedź MUSI być WYŁĄCZNIE poprawnym JSON-em (bez żadnego tekstu przed ani po) dokładnie wg schematu:

{{
  "mediumDescription": "…",
  "notes": ["…"],
  "examples": {{
    "requests": ["curl ..."],
    "response": {{
      "status": 200,
      "body": {{}}
    }}
  }}
}}

WYTYCZNE DLA POZIOMU ADVANCED:

1) mediumDescription:
   - 2–4 zdania, konkretnie i technicznie.
   - Opisz precyzyjnie cel operacji, strukturę danych (we/wy) oraz kluczowe kody odpowiedzi.
   - Jeśli z IR wynika autoryzacja (bearer/JWT), zasygnalizuj to, ale bez „wymyślania”.

2) notes:
   - 0–5 krótkich punktów technicznych (walidacje, edge-case’y, kluczowe błędy 4xx/5xx).
   - Unikaj frazesów i ogólników.

3) examples.requests:
   - DOKŁADNIE 1 przykład cURL dla tego endpointu.
   - Użyj metody {method} i ścieżki {path}.
   - Dodaj wyłącznie niezbędne nagłówki (np. Authorization: Bearer, Content-Type przy body).
   - Zero placeholderów w stylu "<string>".

4) examples.response:
   - Jedna „szczęśliwa ścieżka”:
     - GET → 200; POST tworzący zasób → 201; DELETE/void → 204 i body = {{}}
   - body ma odzwierciedlać strukturę wynikającą z IR (bez fantazji).

5) Ogólne:
   - Zero zgadywania pól/logiki spoza IR.
   - Brak Markdown; tylko czysty JSON zgodny ze schematem.

DANE TECHNICZNE ENDPOINTU:
- method: {method}
- path: {path}
- symbol: {symbol}
- rawComment: {raw_comment}

Na podstawie powyższego i IR poniżej wygeneruj WYŁĄCZNIE JSON:
{_common_context(payload)}
"""

def build_prompt(payload: DescribeIn, audience: str = "beginner") -> str:
    lvl = (audience or "beginner").strip().lower()
    if lvl in ("advanced", "long", "senior"):
        return build_prompt_advanced(payload)
    # domyślnie: beginner
    return build_prompt_beginner(payload)

#   OLLAMA
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
    async with httpx.AsyncClient(timeout=600) as client:
        r = await client.post(url, json=body)
        r.raise_for_status()
        data = r.json()
    text = (data.get("response") or "").strip()
    if NLP_DEBUG:
        print("[ollama:raw] head:", text[:600].replace("\n", " "), "...")
    m = JSON_RE.search(text)
    if not m:
        return {}
    try:
        return json.loads(m.group(0))
    except Exception:
        return {}

#   SANITY / POSTPROCESS
def _sanitize_notes(notes: Any) -> List[str]:
    items = notes if isinstance(notes, list) else []
    out: List[str] = []
    for n in items[:5]:
        s = ("" if n is None else str(n)).strip()
        if not s:
            continue
        if len(s) > 400:
            s = s[:400] + "…"
        out.append(s)
    return out

def _validate_ai_doc(raw: Dict[str, Any], payload: DescribeIn) -> Optional[DescribeOut]:
    if not raw or not isinstance(raw, dict):
        return None

    md = str(raw.get("mediumDescription") or "").strip()
    if not md:
        return None  # żadnych fallbacków

    notes = _sanitize_notes(raw.get("notes"))
    ex_raw = raw.get("examples")
    examples = ex_raw if isinstance(ex_raw, dict) else None

    return DescribeOut(
        mediumDescription=md,
        notes=notes or [],
        examples=examples,
        paramDocs=[],   # uzupełnimy w endpointzie (bez fallbacków treści)
        returnDoc=""
    )

#   FASTAPI
app = FastAPI(title="NLP Describe Service (Ollama)", version="3.0.0")
@app.get("/healthz")
def healthz():
    return {
        "status": "ok",
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
    audience: str = Query("beginner", pattern="^(beginner|advanced)$"),
):
    if NLP_DEBUG:
        who = request.client.host if request.client else "?"
        print(
            f"[describe] from={who} "
            f"symbol={getattr(payload, 'symbol', '?')} audience={audience}"
        )

    prompt = build_prompt(payload, audience=audience)
    prompt += "\nPAMIĘTAJ: Zwróć wyłącznie poprawny JSON zgodny ze schematem i zasadami powyżej.\n"

    raw = await call_ollama(prompt)
    doc = _validate_ai_doc(raw, payload)

    if doc:
        doc.paramDocs = _build_param_docs(getattr(payload, "params", []) or [])
        return doc

    # jeśli model nic nie zwrócił – zwróć 502 lub 422 (inaczej FastAPI krzyknie 500 za brak return)
    from fastapi import HTTPException
    raise HTTPException(status_code=502, detail="Model nie zwrócił poprawnego JSON-u")

    


async def call_ollama_raw(prompt: str) -> str:
    """
    Woła Ollamę i zwraca SUROWĄ odpowiedź (response field) bez wycinania JSON-u.
    Używane wyłącznie do debug podglądu.
    """
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
    async with httpx.AsyncClient(timeout=90) as client:
        r = await client.post(url, json=body)
        r.raise_for_status()
        data = r.json()
    # Ollama zwraca pole 'response' jako tekst
    return (data.get("response") or "").strip()

@app.post("/nlp/output-preview")
async def nlp_output_preview(
    payload: DescribeIn,
    request: Request,
    audience: str = Query("beginner", pattern="^(beginner|advanced)$"),
):
    """
    Endpoint debugowy.
    Zwraca dokładny prompt zbudowany dla danego endpointu + surową odpowiedź modelu.
    NIC nie czyścimy, nie walidujemy, nie zmieniamy.
    """
    if NLP_DEBUG:
        who = request.client.host if request.client else "?"
        print(
            f"[nlp-output-preview] from={who} "
            f"symbol={getattr(payload, 'symbol', '?')} audience={audience}"
        )

    prompt = build_prompt(payload, audience=audience)
    prompt += "\nPAMIĘTAJ: Zwróć wyłącznie poprawny JSON zgodny ze schematem i zasadami powyżej.\n"

    raw = await call_ollama_raw(prompt)

    return {
        "prompt": prompt,
        "raw": raw,
    }


if __name__ == "__main__":
    import uvicorn

    uvicorn.run("app:app", host="0.0.0.0", port=int(os.getenv("PORT", "8000")))
