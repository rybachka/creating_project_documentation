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

    returns_dict = None
    if getattr(payload, "returns", None):
        try:
            returns_dict = payload.returns.dict()
        except Exception:
            returns_dict = None

    success_status = _guess_success_status(method, returns_dict)

    return f"""
You are an assistant that writes REST API documentation for a **junior developer**.

The user sees exactly one endpoint at a time.

Your reply MUST be **ONLY** valid JSON (no additional text before or after), and it MUST strictly follow this schema:

{{
  "mediumDescription": "...",
  "notes": ["..."],
  "examples": {{
    "requests": ["curl ..."],
    "response": {{
      "status": {success_status},
      "body": {{}}
    }}
  }}
}}

GUIDELINES FOR THE BEGINNER LEVEL:

1. mediumDescription
   - 1–2 sentences in **Polish**.
   - Explain WHAT this endpoint does and in which simple business scenario it is used.
   - Use names from the method and path. For example:
     - GET /api/orders/{{id}} → "Pobiera szczegóły zamówienia o podanym ID."
   - Avoid empty phrases like "Pobiera zasób" or "Zwraca obiekt". Be concrete.

2. Authorization
   - The input IR does **not** contain any information about authentication or security.
   - You MUST NOT mention Authorization headers, tokens, JWT, login, sessions, roles, or permissions.
   - Simply skip the topic of security in this level.

3. notes
   - 0–3 short bullet points (still plain strings in JSON).
   - Use them to briefly mention typical outcomes and basic error codes if helpful, for example:
     - {success_status} – when the operation completes successfully,
     - 400 – when the input data is invalid,
     - 404 – when the resource with the given ID does not exist.
   - Keep it simple and user-friendly, avoid heavy technical jargon.
   - Do NOT mention 401 or 403, because we do not have security information.

4. examples.requests
   - Provide **exactly one** simple curl example for **this** endpoint.
   - You MUST use exactly:
     - HTTP method: {method}
     - Path: {path}
   - Always use the base URL placeholder: "{{{{BASE_URL}}}}". For example:
     - `curl -X {method} "{{{{BASE_URL}}}}{path}" ...`
   - You MUST NOT use any other domains or paths (even if you see them in notes or implNotes).
   - If the endpoint has a request body (requestBody or a parameter with in="body"):
     - add the header `Content-Type: application/json`,
     - include a very simple JSON body consistent with the IR.
   - Do NOT add Authorization or any other technical headers.

5. examples.response
   - Provide one example of a **happy-path** response.
   - The `status` field MUST be exactly `{success_status}` for this endpoint.
   - If the endpoint does not return a body (void type), set `body` to `{{}}`.
   - If the IR suggests an object or a list, show a small, simple JSON example that matches the structure, but do not invent extra fields beyond what is implied by the IR.

6. General rules
   - Do NOT invent paths, fields, or parameters that are not present in the input IR.
   - If you don’t know the exact structure of the response, you may say in Polish that the details are defined in the corresponding backend model (e.g. "Szczegółowa struktura odpowiedzi jest zdefiniowana w modelu po stronie backendu").
   - Do NOT use Markdown.
   - Output must be **only** the JSON object.

TECHNICAL ENDPOINT DATA:
- method: {method}
- path: {path}
- symbol: {symbol}
- rawComment: {raw_comment}

ENDPOINT IR (source data):
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

    returns_dict = None
    if getattr(payload, "returns", None):
        try:
            returns_dict = payload.returns.dict()
        except Exception:
            returns_dict = None

    success_status = _guess_success_status(method, returns_dict)

    return f"""
You write REST API documentation in **Polish** for an **advanced backend developer**.

Your reply MUST be **ONLY** valid JSON (no additional text before or after) and MUST strictly follow this schema:

{{
  "mediumDescription": "...",
  "notes": ["..."],
  "examples": {{
    "requests": ["curl ..."],
    "response": {{
      "status": {success_status},
      "body": {{}}
    }}
  }}
}}

GUIDELINES FOR THE ADVANCED LEVEL:

1) mediumDescription
   - 2–4 sentences, concise and technical, written in Polish.
   - Precisely describe the purpose of the operation:
     - which data it accepts (body/query/path),
     - which data it returns, according to the IR (`returns`),
     - what is the main success status code (here: {success_status}).
   - Do NOT describe authentication mechanisms, because the IR does not contain security information.

2) notes
   - 0–5 short technical bullet points (still plain strings in JSON).
   - Focus on:
     - validation rules that can be reasonably inferred from parameter names/types,
     - edge cases (missing resource, conflicts, invalid input),
     - typical error codes such as: 400, 404, 409, 422, 500.
   - Do NOT use 401 or 403, because we have no security data in the IR.
   - Do not invent very detailed business rules that are not supported by the IR.

3) examples.requests
   - Provide **exactly one** curl example for this endpoint.
   - You MUST use exactly:
     - HTTP method: {method}
     - Path: {path}
   - Always use the base URL placeholder: "{{{{BASE_URL}}}}", e.g.:
     - `curl -X {method} "{{{{BASE_URL}}}}{path}" ...`
   - You MUST NOT use any other domain or path, even if it appears in notes, implNotes or comments.
   - If there is a request body (requestBody or in="body" parameter):
     - add `Content-Type: application/json`,
     - construct a minimal JSON object that reflects the structure implied by the IR (if no field names are available, use generic placeholders like "field1", "field2").

4) examples.response
   - Provide a single **happy-path** example.
   - The `status` field MUST be exactly `{success_status}`.
   - If the return type is void or there is no meaningful body, set `body` to `{{}}`.
   - If the return type suggests an object or list, show a small, representative JSON example that matches the IR, without adding imaginary properties.

5) General rules
   - Do NOT guess or invent new fields, paths, parameters, or nested structures beyond what is indicated in the IR.
   - If the IR is incomplete, you may mention in Polish that the detailed structure is defined in the backend type referenced in `returns` (for example: "Szczegółowa struktura odpowiedzi jest zdefiniowana w typie X po stronie backendu").
   - Do NOT use Markdown.
   - Output must be exactly one JSON object following the schema above.

TECHNICAL ENDPOINT DATA:
- method: {method}
- path: {path}
- symbol: {symbol}
- rawComment: {raw_comment}

ENDPOINT IR (source data):
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

def _guess_success_status(method: str, returns: Optional[Dict[str, Any]]) -> int:
    method = (method or "").upper()
    returns_type = (returns or {}).get("type") if isinstance(returns, dict) else None
    returns_void = bool((returns or {}).get("void") if isinstance(returns, dict) else False)

    # bardzo prosta heurystyka, możesz dopracować pod swój DescribeOut
    if method == "GET":
        return 200
    if method == "POST":
        # jeśli nic nie zwraca → 201 lub 204, ja bym na start dał 201
        if returns_void or returns_type in (None, "", "void"):
            return 201
        return 200
    if method in ("DELETE", "PATCH"):
        if returns_void or returns_type in (None, "", "void"):
            return 204
        return 200
    # fallback
    return 200

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
