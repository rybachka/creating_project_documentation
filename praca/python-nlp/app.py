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
MT5_TASK_PREFIX: str = os.getenv("MT5_TASK_PREFIX", "summarize: ")
NLP_DEBUG: bool = os.getenv("NLP_DEBUG", "false").lower() == "true"

# Regulacja jakości/szybkości
MT5_NUM_BEAMS: int = int(os.getenv("MT5_NUM_BEAMS", "4"))
MT5_MAX_TOK_SHORT: int = int(os.getenv("MT5_MAX_TOK_SHORT", "64"))
MT5_MAX_TOK_MED: int = int(os.getenv("MT5_MAX_TOK_MED", "140"))
MT5_MAX_TOK_LONG: int = int(os.getenv("MT5_MAX_TOK_LONG", "200"))
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
    "short_raw": None,
    "medium_raw": None,
    "long_raw": None,
    "ret_raw": None,
    "attempts": "0"
}

app = FastAPI(title="NLP Describe Service", version="1.0.0")

# ---------- utilsy ----------
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

PREFIX_META_RE = re.compile(
    r'^\s*(?:napisz|zadanie|instrukcja|polecenie|opis)\s*:\s*',
    re.IGNORECASE
)

JUNK_RE = re.compile(r'^[\s:()\-\.\[\]{}_,;|\\/]+$')  # sama interpunkcja
ONLY_WORD_RE = re.compile(r'^[A-Za-zĄĆĘŁŃÓŚŹŻąćęłńóśźż]+\.?$')  # pojedyncze słowo (np. "name.")
BOOLEAN_RE = re.compile(r'^(true|false)\.?$', re.IGNORECASE)

def _strip_sentinels(txt: str) -> str:
    if not txt:
        return ""
    txt = SENTINEL_RE.sub("", txt)
    txt = re.sub(r'\s{2,}', ' ', txt).strip()
    return "" if txt == "." else txt

def _quality_ok(s: str) -> bool:
    if not s: return False
    t = s.strip()
    if not t: return False
    if JUNK_RE.match(t): return False
    if BOOLEAN_RE.match(t): return False
    if len(t) < 8: return False
    return True

def _clean_mt5(txt: str, max_sents: int) -> str:
    """Usuń metaprefiks/sentinle; przytnij do N zdań; dopnij kropkę; odfiltruj śmieci."""
    if not txt:
        return ""
    txt = PREFIX_META_RE.sub("", txt.strip())
    txt = _strip_sentinels(txt)
    txt = re.sub(r'[\uFFFD\u200B]+', '', txt)
    sents = [s.strip() for s in _SENT_SPLIT.split(txt) if s.strip()]
    out = " ".join(sents[:max_sents]).strip()
    if out and not out.endswith("."):
        out += "."
    if not _quality_ok(out):
        return ""
    return out

def _looks_like_sentinel_only(*texts: str) -> bool:
    cleaned = [ _strip_sentinels((t or "").strip()) for t in texts ]
    return all(len(c) == 0 for c in cleaned)

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

        # —— zakazane tokeny: z obu metod (id + encode), żeby mieć pewność
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
    lines = []
    if payload.comment:
        lines.append(f"Komentarz: {payload.comment}")
    if payload.params:
        for p in payload.params:
            if p.description:
                lines.append(f"Parametr: {p.name} – {p.description}")
            else:
                lines.append(f"Parametr: {p.name}")
    if payload.returns and payload.returns.type:
        lines.append(f"Zwracany typ: {payload.returns.type}")

    instr = (
        "Zwróć zwięzły opis endpointu po polsku, w neutralnym tonie, bez metakomentarzy ani znaczników typu <extra_id_X>. "
        "Skup się na tym, co robi endpoint i co zwraca."
    )
    prefix = "" if no_prefix else MT5_TASK_PREFIX
    return prefix + instr + "\n" + "\n".join(lines)

def _mt5_generate(text: str, max_new_tokens=120, num_beams=4, do_sample=False, temperature=0.8) -> str:
    if _pipe is None:
        raise RuntimeError("mT5 pipeline is not initialized")
    gen_args = dict(
        max_new_tokens=max_new_tokens,
        min_new_tokens=min(20, max_new_tokens//2),
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

def _log_raw(where: str, raw: str):
    if NLP_DEBUG:
        head = raw[:80].replace("\n", " ")
        print(f"[mt5-raw][{where}] len={len(raw)} head={head!r}")

def _fill_with_rules(payload: DescribeIn, out: Dict[str, str]):
    rb = generate_descriptions_rule_based(payload)
    out.setdefault("shortDescription", _add_dot(rb.shortDescription or "")) if not out.get("shortDescription") else None
    out.setdefault("mediumDescription", _add_dot(rb.mediumDescription or "")) if not out.get("mediumDescription") else None
    out.setdefault("longDescription", _add_dot(rb.longDescription or "")) if not out.get("longDescription") else None
    out.setdefault("returnDoc", _add_dot(rb.returnDoc or "")) if not out.get("returnDoc") else None

def generate_descriptions_mt5(payload: DescribeIn) -> Dict[str, str]:
    out: Dict[str, str] = {}
    _last_mt5.update({"error": None, "short_raw": None, "medium_raw": None, "long_raw": None, "ret_raw": None, "attempts": "1"})

    # --- Podejście #1: z prefixem, beams ---
    base = _prompt_from_payload(payload, no_prefix=False)

    try:
        raw = _mt5_generate(base + "\nZwróć 1–2 krótkie zdania.", max_new_tokens=MT5_MAX_TOK_SHORT, num_beams=MT5_NUM_BEAMS)
        _last_mt5["short_raw"] = raw; _log_raw("short#1", raw)
        out["shortDescription"] = _clean_mt5(raw, max_sents=2)
    except Exception as e:
        _last_mt5["error"] = f"[short#1] {e}"

    try:
        raw = _mt5_generate(base + "\nZwróć 2–4 zdania z kluczowymi szczegółami.", max_new_tokens=MT5_MAX_TOK_MED, num_beams=MT5_NUM_BEAMS)
        _last_mt5["medium_raw"] = raw; _log_raw("medium#1", raw)
        out["mediumDescription"] = _clean_mt5(raw, max_sents=4)
    except Exception as e:
        _last_mt5["error"] = f"[medium#1] {e}"

    try:
        raw = _mt5_generate(base + "\nZwróć 4–6 zdań, w tym typowe sytuacje błędowe (np. 404).", max_new_tokens=MT5_MAX_TOK_LONG, num_beams=MT5_NUM_BEAMS)
        _last_mt5["long_raw"] = raw; _log_raw("long#1", raw)
        out["longDescription"] = _clean_mt5(raw, max_sents=6)
    except Exception as e:
        _last_mt5["error"] = f"[long#1] {e}"

    try:
        ret_type = payload.returns.type if (payload.returns and payload.returns.type) else "obiekt"
        raw = _mt5_generate(base + f"\nJednym zdaniem opisz strukturę zwracanej odpowiedzi (typ: {ret_type}).",
                            max_new_tokens=MT5_MAX_TOK_RET, num_beams=MT5_NUM_BEAMS)
        _last_mt5["ret_raw"] = raw; _log_raw("ret#1", raw)
        out["returnDoc"] = _add_dot(_clean_mt5(raw, max_sents=2))
    except Exception as e:
        _last_mt5["error"] = f"[ret#1] {e}"

    # — jeśli po czyszczeniu wyszło pusto / sentinel-only -> retry
    def _empty_after_clean(o: Dict[str, str]) -> bool:
        vals = [o.get("shortDescription",""), o.get("mediumDescription",""), o.get("longDescription",""), o.get("returnDoc","")]
        only_sentinels = _looks_like_sentinel_only(*[ _last_mt5.get(k, "") or "" for k in ("short_raw","medium_raw","long_raw","ret_raw") ])
        all_empty = not any(v.strip() for v in vals)
        return all_empty or only_sentinels

    if _empty_after_clean(out):
        _last_mt5["attempts"] = "2"
        base2 = _prompt_from_payload(payload, no_prefix=True)
        try:
            raw = _mt5_generate(base2 + "\nZwróć 1–2 krótkie zdania.", max_new_tokens=MT5_MAX_TOK_SHORT, num_beams=1, do_sample=True, temperature=0.9)
            _log_raw("short#2", raw)
            cand = _clean_mt5(raw, max_sents=2)
            if cand: out["shortDescription"] = cand
        except Exception as e:
            _last_mt5["error"] = f"[short#2] {e}"

        try:
            raw = _mt5_generate(base2 + "\nZwróć 2–4 zdania z kluczowymi szczegółami.", max_new_tokens=MT5_MAX_TOK_MED, num_beams=1, do_sample=True, temperature=0.9)
            _log_raw("medium#2", raw)
            cand = _clean_mt5(raw, max_sents=4)
            if cand: out["mediumDescription"] = cand
        except Exception as e:
            _last_mt5["error"] = f"[medium#2] {e}"

        try:
            raw = _mt5_generate(base2 + "\nZwróć 4–6 zdań, w tym typowe sytuacje błędowe (np. 404).", max_new_tokens=MT5_MAX_TOK_LONG, num_beams=1, do_sample=True, temperature=0.9)
            _log_raw("long#2", raw)
            cand = _clean_mt5(raw, max_sents=6)
            if cand: out["longDescription"] = cand
        except Exception as e:
            _last_mt5["error"] = f"[long#2] {e}"

        try:
            ret_type = payload.returns.type if (payload.returns and payload.returns.type) else "obiekt"
            raw = _mt5_generate(base2 + f"\nJednym zdaniem opisz strukturę zwracanej odpowiedzi (typ: {ret_type}).",
                                max_new_tokens=MT5_MAX_TOK_RET, num_beams=1, do_sample=True, temperature=0.9)
            _log_raw("ret#2", raw)
            cand = _add_dot(_clean_mt5(raw, max_sents=2))
            if cand.strip(): out["returnDoc"] = cand
        except Exception as e:
            _last_mt5["error"] = f"[ret#2] {e}"

    # — QUALITY GATE per-pole: jeśli pojedyncze pole wciąż śmieciowe → uzupełnij rule-based
    if not _quality_ok(out.get("shortDescription","")) \
       or not _quality_ok(out.get("mediumDescription","")) \
       or not _quality_ok(out.get("longDescription","")) \
       or not _quality_ok(out.get("returnDoc","")):
        _fill_with_rules(payload, out)

    # log podsumowujący
    if NLP_DEBUG:
        print("[mt5-raw][summary]", json.dumps({
            "attempts": _last_mt5["attempts"],
            "short_len": len(_last_mt5["short_raw"] or ""),
            "medium_len": len(_last_mt5["medium_raw"] or ""),
            "long_len": len(_last_mt5["long_raw"] or ""),
            "ret_len": len(_last_mt5["ret_raw"] or "")
        }, ensure_ascii=False))

    # Trim końcowy
    for k, v in list(out.items()):
        if isinstance(v, str):
            out[k] = v.strip()
    return out

# ---------- Fallback (rule-based) ----------
def generate_descriptions_rule_based(payload: DescribeIn) -> DescribeOut:
    sentences = _sentences(payload.comment or "")
    statuses = _detect_statuses(payload.comment or "")

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
async def describe(payload: DescribeIn, request: Request, mode: str = Query("mt5", regex="^(plain|rule|mt5)$")):
    if NLP_DEBUG:
        try:
            peer = request.client.host if request.client else "?"
            print(f"[describe] from={peer} mode={mode} symbol={payload.symbol}")
        except Exception:
            pass

    if mode == "plain":
        rb = generate_descriptions_rule_based(payload)
        return DescribeOut(shortDescription="", mediumDescription="", longDescription="",
                           paramDocs=rb.paramDocs, returnDoc="")

    if mode == "rule":
        return generate_descriptions_rule_based(payload)

    # mode == "mt5"
    try:
        _lazy_load_mt5()
        if _pipe is not None:
            mt5 = generate_descriptions_mt5(payload)
            return DescribeOut(
                shortDescription = mt5.get("shortDescription", ""),
                mediumDescription= mt5.get("mediumDescription", ""),
                longDescription  = mt5.get("longDescription", ""),
                paramDocs        = _build_param_docs(payload.params),
                returnDoc        = mt5.get("returnDoc", "")
            )
    except Exception as e:
        if NLP_DEBUG:
            print(f"[WARN] mT5 error inside /describe: {e}")

    return DescribeOut(shortDescription="", mediumDescription="", longDescription="",
                       paramDocs=_build_param_docs(payload.params), returnDoc="")
