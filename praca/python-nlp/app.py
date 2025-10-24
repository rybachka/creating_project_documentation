import os
import re
import json
from typing import List, Optional, Dict, Any

from fastapi import FastAPI, Query, Request
from models import DescribeIn, DescribeOut, ParamDoc

# ====== Konfiguracja mT5 / Transformers ======
ENABLE_MT5: bool = os.getenv("NLP_ENABLE_MT5", "true").lower() == "true"
MT5_MODEL_NAME: str = os.getenv("MT5_MODEL_NAME", "google/mt5-small")
NLP_WARMUP: bool = os.getenv("NLP_WARMUP", "true").lower() == "true"
# Rekomendacja: na prod ustaw ENV MT5_TASK_PREFIX="" (bez prefiksu)
MT5_TASK_PREFIX: str = os.getenv("MT5_TASK_PREFIX", "summarize: ")
NLP_DEBUG: bool = os.getenv("NLP_DEBUG", "false").lower() == "true"

# Regulacja jakości/szybkości
MT5_NUM_BEAMS: int = int(os.getenv("MT5_NUM_BEAMS", "4"))
MT5_MAX_TOK_MED: int = int(os.getenv("MT5_MAX_TOK_MED", "140"))
MT5_MAX_TOK_RET: int = int(os.getenv("MT5_MAX_TOK_RET", "80"))

# Lazy-load zasobów HF
_tokenizer: Any = None
_model: Any = None
_pipe: Any = None  # pipeline text2text
_device: str = "cpu"
_warmed_up: bool = False
_mt5_error: Optional[str] = None

# —— blokada sentinel-i i diagnostyka
_bad_words_ids: Optional[List[List[int]]] = None
SENTINEL_RE = re.compile(r"<extra_id_\d+>", re.IGNORECASE)

_last_mt5: Dict[str, Optional[str]] = {
    "error": None,
    "medium_raw": None,
    "ret_raw": None,
    "attempts": "0"
}

app = FastAPI(title="NLP Describe Service", version="2.0.0")

# ---------- utilsy (tokenizacja zdań, proste heurystyki) ----------
_SENT_SPLIT = re.compile(r'(?<=[.!?])\s+')

def _sentences(txt: str) -> List[str]:
    if not txt:
        return []
    parts = re.split(r'[.;]\s+|\n+', txt.strip())
    return [p.strip().rstrip('.') for p in parts if p.strip()]

def _detect_statuses(txt: str) -> List[str]:
    if not txt:
        return []
    found = set()
    for code in ["200", "400", "401", "403", "404", "409", "422", "500"]:
        if re.search(rf"\b{code}\b", txt):
            found.add(code)
    if "200" not in found:
        found.add("200")
    return sorted(found)

def _type_to_words(t: Optional[str]) -> str:
    if not t:
        return "odpowiedź"
    t_clean = t.replace("java.lang.", "")
    low = t_clean.lower()
    if low in {"string"}: return "napis (string)"
    if any(x in low for x in ["int", "long", "integer"]): return "liczba całkowita"
    if any(x in low for x in ["double", "float", "bigdec"]): return "liczba"
    if "boolean" in low: return "wartość logiczna (true/false)"
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

# ---------- regexy i filtry jakości ----------
PREFIX_META_RE = re.compile(
    r'^\s*(?:napisz|zadanie|instrukcja|polecenie|opis)\s*:\s*',
    re.IGNORECASE
)
JUNK_RE = re.compile(r'^[\s:()\-\.\[\]{}_,;|\\/]+$')  # sama interpunkcja/symbole
BOOLEAN_RE = re.compile(r'^(true|false)\.?$', re.IGNORECASE)

# --- Heurystyki „czy to w ogóle polski tekst?”
POLISH_KEEP_RE = re.compile(r"[^A-Za-z0-9ĄĆĘŁŃÓŚŹŻąćęłńóśźż ,.;:()\-\"'\/\[\]{}!?%+_=]")
MULTISCRIPT_RE = re.compile(r"[\u0400-\u052F\u0590-\u05FF\u0600-\u06FF\u0900-\u097F\u0E00-\u0E7F\u3040-\u30FF\u3400-\u9FFF]+")
URL_RE = re.compile(r"https?://|www\.", re.IGNORECASE)
HTML_RE = re.compile(r"[<][^>]*[>]")
REPEAT_SYLLABLES = re.compile(r"(lytte|moji|vulner|ul[ao]n|waa+a+|pomar)[\w-]*", re.IGNORECASE)

def _strip_non_polish(txt: str) -> str:
    t = POLISH_KEEP_RE.sub(" ", txt or "")
    t = re.sub(r"\s{2,}", " ", t).strip()
    return t

def _punct_ratio(txt: str) -> float:
    if not txt: return 1.0
    punct = sum(1 for c in txt if c in ".,;:()[]{}!?-–—\"'\\/|_")
    return punct / max(1, len(txt))

def _is_gibberish(txt: str) -> bool:
    if not txt: return True
    if MULTISCRIPT_RE.search(txt):  # obce alfabety
        return True
    if HTML_RE.search(txt) or URL_RE.search(txt):  # HTML/URL
        return True
    if REPEAT_SYLLABLES.search(txt):  # charakterystyczne śmieci
        return True
    kept = _strip_non_polish(txt)
    if _punct_ratio(txt) > 0.25:  # za dużo interpunkcji
        return True
    return len(kept) < 12         # za krótkie

def _strip_sentinels(t: str) -> str:
    return SENTINEL_RE.sub("", t or "")

def _clean_mt5(txt: str, max_sents: int) -> str:
    """Usuń metaprefiks/sentinle; obetnij do N zdań; przefiltruj znaki do PL."""
    if not txt:
        return ""
    txt = PREFIX_META_RE.sub("", txt.strip())
    txt = _strip_sentinels(txt)
    txt = re.sub(r'[\uFFFD\u200B]+', '', txt)
    sents = [s.strip() for s in _SENT_SPLIT.split(txt) if s.strip()]
    out = " ".join(sents[:max_sents]).strip()
    out = _strip_non_polish(out)
    if out and not out.endswith("."):
        out += "."
    return out

# ---------- Fallback (rule-based) — tylko MEDIUM + returnDoc ----------
def generate_descriptions_rule_based(payload: DescribeIn) -> DescribeOut:
    sentences = _sentences(payload.comment or "")
    statuses = _detect_statuses(payload.comment or "")

    parts_med = []
    if sentences:
        parts_med.append(_add_dot(sentences[0]))
    else:
        ret = _type_to_words(payload.returns.type if payload.returns else None)
        parts_med.append(_add_dot(f"Zwraca {ret}"))
    parts_med.append(f"Typowe kody odpowiedzi: {', '.join(statuses)}.")
    medium = " ".join(parts_med)

    ret_doc = None
    if payload.returns:
        ret_phrase = payload.returns.description or f"Zwraca {_type_to_words(payload.returns.type)}"
        ret_doc = _add_dot(ret_phrase)

    return DescribeOut(
        shortDescription="",             # wyłączone
        mediumDescription=medium,
        longDescription="",              # wyłączone
        paramDocs=_build_param_docs(payload.params),
        returnDoc=ret_doc or ""
    )

# ---------- mT5 ----------
def _lazy_load_mt5():
    """Ładuje tokenizer+model przez pipeline NA CPU (device=-1); przygotowuje bad_words_ids."""
    global _tokenizer, _model, _pipe, _device, _mt5_error, _bad_words_ids
    if _pipe is not None or not ENABLE_MT5:
        return
    try:
        from transformers import AutoTokenizer, pipeline
        _device = "cpu"
        _tokenizer = AutoTokenizer.from_pretrained(MT5_MODEL_NAME)
        _pipe = pipeline(
            task="text2text-generation",
            model=MT5_MODEL_NAME,
            tokenizer=_tokenizer,
            device=-1  # CPU
        )
        _model = _pipe.model
        _model.eval()

        # —— zakazane tokeny: różne warianty kodowania ekstra-id
        _bad_words_ids = []
        for i in range(100):
            tok = f"<extra_id_{i}>"
            tid = _tokenizer.convert_tokens_to_ids(tok)
            if tid is not None and tid != _tokenizer.unk_token_id:
                _bad_words_ids.append([tid])
            enc = _tokenizer.encode(tok, add_special_tokens=False)
            if enc:
                _bad_words_ids.append(enc)
        if NLP_DEBUG:
            print(f"[mt5-init] bad_words_ids variants={len(_bad_words_ids)}")

    except Exception as e:
        _mt5_error = f"{e}"
        print(f"[WARN] mT5 load error (pipeline): {e}")
        _tokenizer = None
        _model = None
        _pipe = None
        _bad_words_ids = None

def _prompt_from_payload(payload: DescribeIn, no_prefix: bool = False) -> str:
    # Minimalny, prosty prompt – celowo bez JSON/extra, bo generujemy tylko medium i return
    lines = []
    if payload.comment:
        lines.append(f"Opis: {payload.comment}")
    if payload.params:
        for p in payload.params:
            if p.description:
                lines.append(f"Parametr: {p.name} – {p.description}")
            else:
                lines.append(f"Parametr: {p.name}")
    if payload.returns and payload.returns.type:
        lines.append(f"Zwracany typ: {payload.returns.type}")

    instr = (
        "Napisz 1–2 zdania po polsku opisujące działanie endpointu (mediumDescription),"
        " bez metakomentarzy i bez znaczników <extra_id_X>."
    )
    prefix = "" if no_prefix else MT5_TASK_PREFIX
    return prefix + instr + "\n" + "\n".join(lines)

def _mt5_generate(text: str, max_new_tokens=120, num_beams=4, do_sample=False, temperature=0.8) -> str:
    if _pipe is None:
        raise RuntimeError("mT5 pipeline is not initialized")
    gen_args = dict(
        max_new_tokens=max_new_tokens,
        min_new_tokens=min(20, max_new_tokens // 2),
        num_beams=num_beams,
        no_repeat_ngram_size=3,
        length_penalty=1.0,
        eos_token_id=_tokenizer.eos_token_id if _tokenizer else None
    )
    if _bad_words_ids:
        gen_args["bad_words_ids"] = _bad_words_ids
    if do_sample:
        gen_args.update(dict(do_sample=True, temperature=temperature, top_k=50, top_p=0.9))
    out = _pipe(text, **gen_args)
    return (out[0].get("generated_text") or "").strip()

def generate_descriptions_mt5(payload: DescribeIn) -> Dict[str, str]:
    """
    Tryb MT5 bez fallbacku:
    - zwracamy TYLKO mediumDescription i returnDoc (krótkie/długie puste),
    - jeśli model nie działa lub generacja słaba → puste.
    """
    out: Dict[str, str] = {"mediumDescription": "", "returnDoc": ""}
    _last_mt5.update({"error": None, "medium_raw": None, "ret_raw": None, "attempts": "1"})

    if _pipe is None:
        _last_mt5["error"] = "mt5 pipeline is None"
        return out

    # 1) medium
    base = _prompt_from_payload(payload, no_prefix=False)
    try:
        raw = _mt5_generate(base, max_new_tokens=MT5_MAX_TOK_MED, num_beams=MT5_NUM_BEAMS)
        _last_mt5["medium_raw"] = raw
        if NLP_DEBUG:
            head = raw[:120].replace("\n", " ")
            print(f"[mt5][medium] len={len(raw)} head={head!r}")
        cand = _clean_mt5(raw, max_sents=2)
        if not _is_gibberish(cand):
            out["mediumDescription"] = cand
    except Exception as e:
        _last_mt5["error"] = f"[medium] {e}"

    # 2) returnDoc (krótkie, 1 zdanie)
    try:
        ret_type = payload.returns.type if (payload.returns and payload.returns.type) else "obiekt"
        raw = _mt5_generate(
            base + f"\nJednym zdaniem opisz co zwraca endpoint (typ: {ret_type}).",
            max_new_tokens=MT5_MAX_TOK_RET, num_beams=MT5_NUM_BEAMS
        )
        _last_mt5["ret_raw"] = raw
        if NLP_DEBUG:
            head = raw[:120].replace("\n", " ")
            print(f"[mt5][return] len={len(raw)} head={head!r}")
        cand = _add_dot(_clean_mt5(raw, max_sents=1))
        if not _is_gibberish(cand):
            out["returnDoc"] = cand
    except Exception as e:
        _last_mt5["error"] = f"[return] {e}"

    # — ZERO fallbacku do reguł: jeśli pusto – tak zostaje
    # (świadomie nie wywołujemy rule-based tutaj)
    return out

# ---------- lifecycle ----------
@app.on_event("startup")
def _warmup():
    global _warmed_up
    if not ENABLE_MT5 or not NLP_WARMUP:
        return
    try:
        _lazy_load_mt5()
        if _pipe is not None:
            _ = _mt5_generate("Krótkie zdanie testowe o endpointach.", max_new_tokens=16, num_beams=2)
            _warmed_up = True
            print("[warmup] mT5 ready")
    except Exception as e:
        print(f"[warmup] skipped: {e}")

# ---------- endpoints ----------
@app.get("/healthz")
def healthz():
    status = "ok"
    m = "disabled" if not ENABLE_MT5 else "unavailable"
    loaded = False
    try:
        if ENABLE_MT5:
            _lazy_load_mt5()
            loaded = bool(_pipe is not None)
            if loaded:
                m = MT5_MODEL_NAME
            elif _mt5_error:
                m = f"error: {_mt5_error}"
    except Exception as e:
        m = f"error: {e.__class__.__name__}"
    return {
        "status": status,
        "mt5": m,
        "device": _device,
        "warmed": _warmed_up,
        "enable_mt5": ENABLE_MT5,
        "model_loaded": loaded,
        "last_mt5": _last_mt5,
    }

@app.post("/describe", response_model=DescribeOut)
async def describe(
    payload: DescribeIn,
    request: Request,
    mode: str = Query("mt5", regex="^(plain|rule|mt5)$"),
    strict: bool = Query(True)  # zostawione dla kompatybilności, nieużywane w mt5
):
    if NLP_DEBUG:
        try:
            peer = request.client.host if request.client else "?"
            print(f"[describe] from={peer} mode={mode} symbol={payload.symbol}")
        except Exception:
            pass

    if mode == "plain":
        # Puste opisy; ale zwracamy paramDocs z heurystyk
        return DescribeOut(
            shortDescription="", mediumDescription="", longDescription="",
            paramDocs=_build_param_docs(payload.params), returnDoc=""
        )

    if mode == "rule":
        return generate_descriptions_rule_based(payload)

    # mode == "mt5": ZERO fallbacku – zwróć tylko to, co oddał model (albo pusto)
    try:
        _lazy_load_mt5()
        if _pipe is not None:
            mt5 = generate_descriptions_mt5(payload)
            return DescribeOut(
                shortDescription = "",
                mediumDescription= mt5.get("mediumDescription", ""),
                longDescription  = "",
                paramDocs        = _build_param_docs(payload.params),  # to możemy dodać niezależnie
                returnDoc        = mt5.get("returnDoc", "")
            )
    except Exception as e:
        if NLP_DEBUG:
            print(f"[WARN] mT5 error inside /describe: {e}")

    # mT5 niedostępny → puste pola + paramDocs (bez fallbacku opisów!)
    return DescribeOut(
        shortDescription="", mediumDescription="", longDescription="",
        paramDocs=_build_param_docs(payload.params), returnDoc=""
    )
