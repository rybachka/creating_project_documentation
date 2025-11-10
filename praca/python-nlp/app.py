import os
import re
import json
from typing import Any, Dict, List, Optional

import httpx
from fastapi import FastAPI, Query, Request
from pydantic import BaseModel, ValidationError

from models import DescribeIn, DescribeOut, ParamIn

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
#   MODELE DLA PODSUMOWANIA PROJEKTU
# =========================


class EndpointSummaryIn(BaseModel):
    method: str
    path: str
    summary: Optional[str] = ""
    description: Optional[str] = ""


class ProjectSummaryIn(BaseModel):
    projectName: str
    language: str = "pl"
    audience: str = "beginner"  # "beginner" | "intermediate" | "advanced"
    endpoints: List[EndpointSummaryIn]


class ProjectSummaryOut(BaseModel):
    summary: str


# =========================
#   UTILS
# =========================


def _build_param_docs(params: List[ParamIn]) -> List[Dict[str, str]]:
    out: List[Dict[str, str]] = []
    for p in params:
        base = (getattr(p, "description", "") or "").strip()
        if not base:
            n = (getattr(p, "name", "") or "").lower()
            if n in {"id", "user_id", "userid"}:
                base = "Identyfikator zasobu."
            elif n in {"page", "size", "limit"}:
                base = "Parametr paginacji."
            elif n in {"q", "query", "search"}:
                base = "Fraza wyszukiwania."
            else:
                base = f"Parametr `{getattr(p, 'name', '')}`."
        out.append({"name": getattr(p, "name", ""), "doc": base})
    return out


def _type_to_words(t: Optional[str]) -> str:
    if not t:
        return "odpowiedź"
    tl = t.lower()
    if "string" in tl:
        return "napis (string)"
    if any(x in tl for x in ["int", "long", "integer"]):
        return "liczba całkowita"
    if any(x in tl for x in ["double", "float", "bigdec"]):
        return "liczba"
    if "boolean" in tl:
        return "wartość logiczna (true/false)"
    return f"obiekt `{t}`"


def _safe_return_type(payload: DescribeIn) -> Optional[str]:
    if not getattr(payload, "returns", None):
        return None
    rt = getattr(payload.returns, "type", None)
    return rt or None


def _rule_based(payload: DescribeIn) -> DescribeOut:
    """
    Deterministyczny fallback gdy AI nic sensownego nie zwróci.
    """
    base = (getattr(payload, "comment", "") or "").strip()
    if not base:
        ret = _type_to_words(_safe_return_type(payload))
        base = f"Zwraca {ret}."
    if not base.endswith("."):
        base += "."
    return DescribeOut(
        mediumDescription=base,
        paramDocs=_build_param_docs(getattr(payload, "params", []) or []),
        returnDoc=(getattr(getattr(payload, "returns", None), "description", "") or "")
    )


# =========================
#   SCHEMAT DLA MODELU (ENDPOINTY)
# =========================

SCHEMA_TEXT = """
ZWRÓĆ WYŁĄCZNIE POPRAWNY JSON wg schematu:
{
  "mediumDescription": "1–3 zdania, naturalny polski, bez metatekstu i placeholderów",
  "notes": ["0–5 krótkich punktów albo []"],
  "examples": {
    "requests": ["curl ..."],              // 0–2 pozycji (dla beginner docelowo 1)
    "response": {
      "status": 200,
      "body": {}                           // dla DELETE/void: {"status": 204, "body": {}}
    }
  }
}

TWARDZE ZASADY:
- ŻADNYCH placeholderów typu „string (1–3 zdania…)”, „wpisz opis tutaj”.
- Nie wymyślaj pól biznesowych ani typów spoza wejścia.
- Jeśli operacja to DELETE lub typ zwrotny to void → response.status = 204, body = {}.
- Dla GET zwykle response.status = 200, dla POST tworzącego zasób preferuj 201.
- Odpowiedź musi być czystym JSON bez komentarzy, Markdown, tekstu przed ani po.
"""


# =========================
#   PROMPTY (ENDPOINTY)
# =========================


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

{SCHEMA_TEXT}
"""


def build_prompt_intermediate(payload: DescribeIn) -> str:
    """
    Prompt dla INTERMEDIATE — programista zna REST, ale nie zna Twojego API.
    Ma dostać konkretny opis domenowy, sensowne statusy, przykłady z paginacją/filtrami
    i krótkie techniczne uwagi. Bez glosariusza dla juniorów i bez przesadnych edge-case'ów.
    """
    method = (getattr(payload, "method", "") or "").upper()
    path = getattr(payload, "path", "") or ""
    raw_comment = (
        getattr(payload, "rawComment", None)
        or getattr(payload, "comment", None)
        or ""
    )

    return f"""
Piszesz dokumentację REST API po polsku dla programisty ŚREDNIOZAAWANSOWANEGO.

Założenia ogólne:
- Odbiorca zna HTTP, JSON, kody statusów i podstawy REST.
- NIE zna domeny biznesowej ani szczegółów tego API.
- Potrzebuje konkretnych informacji: co zwraca endpoint, jak używać parametrów, jak działa paginacja/sortowanie, jakie są typowe odpowiedzi.

Twoja odpowiedź MUSI być WYŁĄCZNIE poprawnym JSON-em wg schematu (bez tekstu przed/po):

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

ZASADY DLA POZIOMU INTERMEDIATE:

1. mediumDescription (1–3 zdania):
   - Opisz domenowo CO robi operacja dla ścieżki "{path}" i metody "{method}".
   - Uwzględnij kluczowe szczegóły:
     - czy zwraca listę czy pojedynczy obiekt,
     - czy wynik jest posortowany lub filtrowany,
     - czy dotyczy "zalogowanego użytkownika" lub konkretnego zasobu (np. ID w ścieżce),
     - jeśli z IR wynika paginacja (page/size/sort) – wspomnij, że wynik jest stronicowany.
   - Bez ogólników typu "operacja na zasobie" i bez tłumaczenia podstaw REST.

2. examples.requests (1–2 przykłady cURL):
   - ZAWSZE podaj 1 poprawny przykład cURL dla TEGO endpointu.
   - Jeśli z danych wynika paginacja / sortowanie / filtry (np. parametry query page, size, sort, q, status itp.),
     dodaj DRUGI przykład cURL pokazujący te parametry w użyciu.
   - Używaj metody {method} i ścieżki {path}.
   - Jeśli endpoint wymaga autoryzacji (na podstawie danych o security / bearer / JWT),
     dodaj nagłówek: Authorization: Bearer <token>.
   - Dla żądań z body dodaj Content-Type: application/json i sensowny przykład JSON zgodny z wejściem.
   - Żadnych losowych nagłówków technicznych; żadnych placeholderów typu "<string>" poza oczywistym <token>.

3. examples.response:
   - Jedna przykładowa odpowiedź "szczęśliwej ścieżki":
     - GET → zazwyczaj 200,
     - POST tworzący nowy zasób → 201,
     - PUT/PATCH → 200 lub 204,
     - DELETE lub typ zwrotny void → 204 i body = {{}}.
   - Struktura body musi wynikać z przekazanego modelu odpowiedzi (returns / schema).
     Jeśli brak informacji → użyj neutralnej, prostej struktury bez wymyślania pól biznesowych.

4. notes (2–6 krótkich punktów, jeśli mają sens):
   - Wymień typowe kody odpowiedzi dla tego endpointu w formie:
     "200 – gdy operacja zakończy się poprawnie.",
     "400 – niepoprawne dane wejściowe.",
     "401 – brak ważnego tokenu dostępowego.",
     "403 – użytkownik nie ma uprawnień.",
     "404 – zasób o podanym identyfikatorze nie istnieje.",
     "422 – walidacja domenowa nie powiodła się (np. zbyt długi tytuł)."
   - Dodaj zwięzłe best practices:
     - jak korzystać z paginacji (page/size/sort),
     - jakie są istotne ograniczenia (np. limit wyników, wymagane pola),
     - czy endpoint jest idempotentny (dla PUT/DELETE, jeśli wynika z kontekstu),
     - ewentualne techniczne uwagi (np. filtry po statusie / dacie).
   - Nie powtarzaj mediumDescription słowo w słowo.
   - Nie tłumacz podstaw HTTP/REST – ten poziom już to zna.

5. TWARDY LIMIT:
   - Nie wymyślaj pól ani typów, których nie ma w danych wejściowych lub w przekazanych schematach.
   - Nie dodawaj glosariusza dla juniorów (to jest tylko dla beginner).
   - Nie rozpisuj skrajnych edge-case'ów i rozbudowanej polityki błędów 5xx (to domena poziomu advanced).
   - Odpowiedź musi być czystym JSON-em, bez Markdowna, bez komentarzy, bez tekstu wokół.

DANE ENDPOINTU (IR):
- method: {method}
- path: {path}
- rawComment: {raw_comment}

{_common_context(payload)}

{SCHEMA_TEXT}
"""



def build_prompt_advanced(payload: DescribeIn) -> str:
    return f"""
Piszesz dokumentację REST API po polsku dla zaawansowanego backend developera.

CEL:
- W mediumDescription opisz precyzyjnie strukturę danych, kody statusów, edge-case'y.
- Szczegóły techniczne i błędy w 'notes'.
- Dodaj 1 przykład cURL z sensownymi nagłówkami.

ZASADY:
- Zero zgadywania poza tym, co wynika z IR.
- DELETE/void → response.status=204, body={{}}.
- Tylko JSON zgodny ze schematem. Bez Markdown.

{_common_context(payload)}

{SCHEMA_TEXT}
"""


def build_prompt(payload: DescribeIn, audience: str = "intermediate") -> str:
    lvl = (audience or "intermediate").strip().lower()
    if lvl in ("beginner", "short", "junior"):
        return build_prompt_beginner(payload)
    if lvl in ("advanced", "long", "senior"):
        return build_prompt_advanced(payload)
    return build_prompt_intermediate(payload)


# =========================
#   PROMPT DLA PODSUMOWANIA PROJEKTU
# =========================


def build_project_summary_prompt(payload: ProjectSummaryIn) -> str:
    lines: List[str] = []
    lines.append(
        "Napisz rozbudowane, ale konkretne podsumowanie tego REST API dla początkującego programisty."
    )
    lines.append(f"Nazwa projektu: {payload.projectName}")
    lines.append(
        "Lista endpointów (skrótowo, tylko do analizy – NIE cytuj ich dosłownie w podsumowaniu):"
    )
    for ep in (payload.endpoints or [])[:200]:
        line = f"- {ep.method} {ep.path}"
        detail = (ep.summary or ep.description or "").strip()
        if detail:
            line += f" :: {detail}"
        lines.append(line)

    lines.append(
        """
ZWRÓĆ WYŁĄCZNIE POPRAWNY JSON:
{
  "summary": "…"
}

TWARDZE ZASADY:
- Język: polski.
- Forma: 2–3 zwarte akapity w jednym polu "summary" (użyj znaków nowej linii "\\n" tam, gdzie to naturalne).
- Długość: orientacyjnie 900–1500 znaków.
- Opisz sensownie CAŁE API, a nie pojedyncze endpointy.
- Uwzględnij:
  - jaki problem biznesowy rozwiązuje aplikacja / do czego służy API,
  - główne grupy funkcjonalności (np. użytkownicy, zamówienia, konfiguracja, raporty),
  - typowe operacje: pobieranie list, szczegółów, tworzenie, modyfikacja, usuwanie,
  - jeśli z listy endpointów wynika logowanie / token / autoryzacja – krótko wspomnij o wymaganym uwierzytelnianiu,
  - jeśli widać paginację / filtrowanie / standardowy format błędów – wspomnij o tym jednym zdaniem.
- Nie wypisuj pełnych ścieżek ani metod HTTP.
- Nie używaj list wypunktowanych ani metakomentarzy ("to podsumowanie", "na podstawie powyższych endpointów").
- Żadnych placeholderów typu "TODO", "uzupełnij tutaj".
- Styl prosty, produktowy, zrozumiały dla juniora.
"""
    )
    return "\n".join(lines)


# =========================
#   OLLAMA
# =========================

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


# =========================
#   SANITY / POSTPROCESS
# =========================


def _sanitize_notes(notes: Any) -> List[str]:
    items = notes if isinstance(notes, list) else []
    out: List[str] = []
    for n in items[:5]:
        s = ("" if n is None else str(n)).strip()
        if not s:
            continue
        if len(s) > 240:
            s = s[:240] + "…"
        out.append(s)
    return out


def _fallback_medium_from_ir(payload: DescribeIn) -> str:
    method = (getattr(payload, "method", "") or "").upper()
    path = getattr(payload, "path", "") or ""
    if not path:
        return ""

    if "/users" in path:
        if "{id}" in path:
            return "Pobiera dane użytkownika na podstawie jego identyfikatora."
        if method == "POST":
            return "Tworzy nowego użytkownika na podstawie danych z JSON."
        return "Operacja na zasobie użytkowników."
    if "/orders" in path:
        if "{id}" in path and method == "GET":
            return "Pobiera szczegóły zamówienia o podanym identyfikatorze."
        if "{id}" in path and method == "DELETE":
            return "Usuwa wskazane zamówienie z systemu."
        return "Operacja na zasobie zamówień."

    if method == "GET":
        return f"Pobiera dane z endpointu {path}."
    if method == "POST":
        return f"Tworzy nowy zasób poprzez wywołanie {path} z danymi w JSON."
    if method in ("PUT", "PATCH"):
        return f"Aktualizuje zasób powiązany z {path}."
    if method == "DELETE":
        return f"Usuwa zasób powiązany z {path}."
    return f"Operacja na endpointzie {path}."


def _validate_ai_doc(raw: Dict[str, Any], payload: DescribeIn) -> Optional[DescribeOut]:
    """
    Parsowanie odpowiedzi modelu:
    - mediumDescription: wymagany (z fallbacku jeśli brak),
    - notes: czyszczone,
    - examples: przepuszczone 1:1 (żeby w OpenAPI/PDF było dokładnie to, co wygenerował model).
    """
    if not raw or not isinstance(raw, dict):
        return None

    try:
        md = (
            raw.get("mediumDescription")
            or raw.get("description")
            or raw.get("summary")
            or ""
        )
        md = str(md).strip()
        if not md:
            md = _fallback_medium_from_ir(payload).strip()
        if not md:
            return None

        notes = _sanitize_notes(raw.get("notes"))

        ex_raw = raw.get("examples")
        examples = ex_raw if isinstance(ex_raw, dict) else None

        return DescribeOut(
            mediumDescription=md,
            notes=notes or [],
            examples=examples,
            paramDocs=[],
            returnDoc=""
        )
    except ValidationError as ve:
        if NLP_DEBUG:
            print("[ai-validate] error:", ve)
        return None


# =========================
#   FASTAPI
# =========================

app = FastAPI(title="NLP Describe Service (Ollama)", version="2.5.0")


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
    strict: bool = Query(True),
):
    if NLP_DEBUG:
        who = request.client.host if request.client else "?"
        print(
            f"[describe] from={who} mode={mode} "
            f"symbol={getattr(payload, 'symbol', '?')} audience={audience}"
        )

    # plain → tylko paramDocs, bez AI
    if mode == "plain":
        return DescribeOut(
            mediumDescription="",
            paramDocs=_build_param_docs(getattr(payload, "params", []) or []),
            returnDoc=""
        )

    # rule → deterministyczny opis bez LLM
    if mode == "rule":
        rb = _rule_based(payload)
        rb.paramDocs = _build_param_docs(getattr(payload, "params", []) or [])
        return rb

    # ollama → próbujemy AI, w razie czego fallback na reguły

    prompt = build_prompt(payload, audience=audience)
    if strict:
        prompt += "\nPAMIĘTAJ: Zwróć wyłącznie poprawny JSON zgodny ze schematem i zasadami powyżej.\n"

    raw = await call_ollama(prompt)
    doc = _validate_ai_doc(raw, payload)

    # jeśli strict parsing padł → jedno podejście bez dopisku strict
    if not doc and strict:
        if NLP_DEBUG:
            print("[describe] strict parsing failed, retrying with non-strict prompt")
        prompt2 = build_prompt(payload, audience=audience)
        raw2 = await call_ollama(prompt2)
        doc = _validate_ai_doc(raw2, payload)

    if doc:
        doc.paramDocs = _build_param_docs(getattr(payload, "params", []) or [])
        return doc

    # ostateczny fallback – zawsze coś zwróci
    rb = _rule_based(payload)
    rb.paramDocs = _build_param_docs(getattr(payload, "params", []) or [])
    return rb


@app.post("/project-summary", response_model=ProjectSummaryOut)
async def project_summary(
    payload: ProjectSummaryIn,
    request: Request,
):
    if NLP_DEBUG:
        who = request.client.host if request.client else "?"
        print(
            f"[project-summary] from={who} "
            f"project={payload.projectName} eps={len(payload.endpoints or [])}"
        )

    prompt = build_project_summary_prompt(payload)
    raw = await call_ollama(prompt)

    summary_text = ""
    if isinstance(raw, dict):
        # tylko strip — żadnych zmian merytorycznych
        summary_text = str(raw.get("summary") or "").strip()

    # Fallback tylko jeśli model kompletnie nic nie zwrócił sensownego
    if not summary_text:
        eps = payload.endpoints or []
        unique_paths = {f"{e.method} {e.path}" for e in eps}
        if unique_paths:
            summary_text = (
                f"To API udostępnia zestaw endpointów do obsługi danych aplikacji "
                f"{payload.projectName}. Pozwala pobierać, tworzyć, aktualizować "
                f"i usuwać kluczowe zasoby domenowe w spójny i przewidywalny sposób."
            )
        else:
            summary_text = (
                f"To API udostępnia endpointy do obsługi danych aplikacji {payload.projectName}."
            )

    return ProjectSummaryOut(summary=summary_text)


if __name__ == "__main__":
    import uvicorn

    uvicorn.run("app:app", host="0.0.0.0", port=int(os.getenv("PORT", "8000")))
