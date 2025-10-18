import re
from typing import List

from fastapi import FastAPI
# jeśli app.py i models.py są w tym samym katalogu:
from models import DescribeIn, DescribeOut, ParamDoc
# jeśli masz pakiet:
# from .models import DescribeIn, DescribeOut, ParamDoc

app = FastAPI(title="NLP Describe Service", version="0.2.0")

# ---------- utilsy ----------

def _sentences(txt: str) -> List[str]:
    if not txt:
        return []
    parts = re.split(r'[.;]\s+|\n+', txt.strip())
    return [p.strip().rstrip('.') for p in parts if p.strip()]

def _detect_statuses(txt: str) -> List[str]:
    if not txt:
        return []
    found = set()
    for code in ["400", "401", "403", "404", "409", "422", "500"]:
        if re.search(rf"\b{code}\b", txt):
            found.add(code)
    return sorted(found)

def _type_to_words(t: str | None) -> str:
    if not t:
        return "odpowiedź"
    t_clean = t.replace("java.lang.", "")
    low = t_clean.lower()
    if low in {"string"}: return "napis (string)"
    if any(x in low for x in ["int", "long", "integer"]): return "liczba całkowita"
    if any(x in low for x in ["double", "float", "bigdec"]): return "liczba"
    if "boolean" in low: return "wartość logiczna (true/false)"
    if low.endswith("response") or low.endswith("dto"):
        return f"obiekt `{t_clean}`"
    return f"obiekt `{t_clean}`"

def _build_param_docs(params) -> List[ParamDoc]:
    out: List[ParamDoc] = []
    if not params:
        return out
    for p in params:
        base = (p.description or "").strip()
        if not base:
            n = (p.name or "").lower()
            if n in {"id", "userid", "user_id"}:
                base = "Identyfikator zasobu."
            elif n in {"page", "limit", "size"}:
                base = "Parametr paginacji."
            elif n in {"q", "query", "search"}:
                base = "Fraza wyszukiwania."
            else:
                base = f"Parametr `{p.name}`."
        out.append(ParamDoc(name=p.name, doc=base))
    return out

def _add_dot(s: str) -> str:
    s = s.strip()
    return s if not s or s.endswith('.') else s + '.'

# ---------- generator ----------

def generate_descriptions(payload: DescribeIn) -> DescribeOut:
    sentences = _sentences(payload.comment or "")
    statuses = _detect_statuses(payload.comment or "")
    if "200" not in statuses:
        statuses = ["200"] + statuses

    if payload.signature:
        short = payload.signature
    elif payload.kind == "endpoint":
        short = f"Operacja {payload.symbol}"
    else:
        short = f"Funkcja {payload.symbol}"

    parts_med = []
    if sentences:
        parts_med.append(_add_dot(sentences[0]))
    else:
        ret = _type_to_words(payload.returns.type if payload.returns else None)
        parts_med.append(_add_dot(f"Zwraca {ret}"))
    parts_med.append(f"Typowe kody odpowiedzi: {', '.join(statuses)}.")
    medium = " ".join(parts_med)

    long_parts: List[str] = []
    if sentences:
        long_parts.append(_add_dot(sentences[0]))
        if len(sentences) > 1:
            long_parts.append(_add_dot(" ".join(sentences[1:2])))
    else:
        long_parts.append(parts_med[0])

    pdocs = _build_param_docs(payload.params)
    if pdocs:
        param_lines = "; ".join([f"`{p.name}` – {p.doc}" for p in pdocs])
        long_parts.append(_add_dot(f"Parametry: {param_lines}"))

    ret_doc = None
    if payload.returns:
        ret_phrase = payload.returns.description or f"Zwraca {_type_to_words(payload.returns.type)}"
        ret_doc = _add_dot(ret_phrase)
        long_parts.append(ret_doc)

    long_parts.append(f"Typowe kody odpowiedzi: {', '.join(dict.fromkeys(statuses))}.")
    long_text = " ".join(long_parts)

    return DescribeOut(
        shortDescription=short,
        mediumDescription=medium,
        longDescription=long_text,
        paramDocs=pdocs,
        returnDoc=ret_doc
    )

# ---------- endpoints ----------

@app.get("/healthz")
def healthz():
    return {"status": "ok"}

@app.post("/describe", response_model=DescribeOut)
def describe(payload: DescribeIn):
    return generate_descriptions(payload)
