import React, { useEffect, useRef, useState } from "react";

type HelloResponse = { message: string };

// Zgodne z backendem (app.py -> DescribeOut)
type DescribeOut = {
  mediumDescription: string;
  paramDocs: { name: string; doc: string }[];
  returnDoc?: string | null;
  notes?: string[];
  examples?:
    | {
        // w UI obsługujemy tablicę obiektów { curl }, ale backend może odesłać też listę stringów
        requests?: ({ curl: string } | string)[];
        response?: { status: number; body: any };
      }
    | null;
};

// /nlp/healthz w aktualnym backendzie zwraca więcej pól; zostawiamy luźny kształt
type Healthz = {
  status: string;
  mode?: string;
  debug?: boolean;
  ollama?: {
    base_url?: string;
    model?: string;
    options?: Record<string, unknown>;
  };
};

type Level = "beginner" | "intermediate" | "advanced";
type Mode = "plain" | "rules" | "ai" | "all";

export default function App() {
  const [hello, setHello] = useState<HelloResponse | null>(null);
  const [nlp, setNlp] = useState<DescribeOut | null>(null);
  const [name, setName] = useState("Mariia");
  const [comment, setComment] = useState(
    "Zwraca użytkownika po ID. 404 gdy nie znaleziono."
  );
  const [level, setLevel] = useState<Level>("intermediate");

  // UI: status + licznik
  const [status, setStatus] = useState<string>("gotowa.");
  const [elapsed, setElapsed] = useState<string>("0.0s");
  const tickRef = useRef<number | null>(null);
  const t0Ref = useRef<number>(0);

  function startTimer() {
    t0Ref.current = performance.now();
    stopTimer();
    tickRef.current = window.setInterval(() => {
      const s = ((performance.now() - t0Ref.current) / 1000).toFixed(1) + "s";
      setElapsed(s);
    }, 100);
  }
  function stopTimer() {
    if (tickRef.current) {
      clearInterval(tickRef.current);
      tickRef.current = null;
    }
  }

  useEffect(() => {
    fetch(`/api/hello?name=${encodeURIComponent(name)}`)
      .then((r) => r.json())
      .then(setHello)
      .catch(console.error);
  }, [name]);

  const runNlp = async () => {
    const body = {
      symbol: "getUserById",
      kind: "endpoint",
      signature: "GET /api/users/{id}",
      comment,
      language: "pl",
      http: "GET",
      pathTemplate: "/api/users/{id}",
      params: [
        {
          name: "id",
          in: "path",
          type: "string",
          required: true,
          description: "Identyfikator",
        },
      ],
      returns: { type: "UserResponse", description: "Obiekt użytkownika" },
      notes: [],
      todos: [],
    };

    setNlp(null);
    setStatus("sprawdzam usługę NLP…");
    startTimer();

    // healthcheck
    try {
      const hr = await fetch("/nlp/healthz");
      if (hr.ok) {
        const health: Healthz = await hr.json();
        if (health?.ollama?.model) {
          setStatus(
            `LLM: ${health.ollama.model} – generuję opis dla poziomu ${level}…`
          );
        } else {
          setStatus("usługa NLP osiągalna – generuję opis…");
        }
      } else {
        setStatus("usługa NLP niedostępna – spróbuję mimo to…");
      }
    } catch {
      setStatus("usługa NLP niedostępna – spróbuję mimo to…");
    }

    try {
      const r = await fetch(
        `/nlp/describe?mode=ollama&audience=${encodeURIComponent(level)}`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(body),
        }
      );

      if (!r.ok) {
        if (r.status === 504) {
          setStatus(
            "serwer przekroczył czas oczekiwania (504) – model mógł się rozgrzewać."
          );
        } else {
          setStatus(`błąd: ${r.status} ${r.statusText}`);
        }
        setNlp(null);
        return;
      }

      const j: DescribeOut = await r.json();
      setNlp(j);
      setStatus("gotowe ✓");
    } catch (e: any) {
      setStatus(`błąd sieci: ${e?.message ?? String(e)}`);
    } finally {
      stopTimer();
    }
  };

  const pickedText = (d?: DescribeOut | null) =>
    !d ? "" : d.mediumDescription || "";

  return (
    <div style={{ maxWidth: 900, margin: "2rem auto", fontFamily: "sans-serif" }}>
      <h1>AI Docs – Web UI</h1>

      <section style={{ marginTop: 24 }}>
        <h2>Test Java API</h2>
        <label>
          Imię:&nbsp;
          <input value={name} onChange={(e) => setName(e.target.value)} />
        </label>
        <p style={{ background: "#f4f4f4", padding: 12 }}>
          {hello ? JSON.stringify(hello) : "Ładowanie..."}
        </p>
      </section>

      <section style={{ marginTop: 24 }}>
        <h2>Test NLP (Ollama / LLM)</h2>

        {/* STATUS BAR */}
        <div
          style={{
            padding: ".6rem",
            background: "#f6f6f6",
            borderRadius: 8,
            marginBottom: 12,
            display: "flex",
            justifyContent: "space-between",
          }}
        >
          <div>
            <b>Status:</b> {status}
          </div>
          <div style={{ color: "#666" }}>{elapsed}</div>
        </div>

        <label style={{ display: "block", marginBottom: 8 }}>
          Komentarz:&nbsp;
          <input
            style={{ width: "100%" }}
            value={comment}
            onChange={(e) => setComment(e.target.value)}
          />
        </label>

        <div style={{ marginBottom: 8 }}>
          Poziom odbiorcy:&nbsp;
          <select value={level} onChange={(e) => setLevel(e.target.value as Level)}>
            <option value="beginner">beginner</option>
            <option value="intermediate">intermediate</option>
            <option value="advanced">advanced</option>
          </select>
          &nbsp;
          <button onClick={runNlp}>Generuj opis</button>
        </div>

        <div style={{ background: "#f4f4f4", padding: 12, borderRadius: 8 }}>
          <h3 style={{ marginTop: 0 }}>Opis</h3>
          <pre style={{ whiteSpace: "pre-wrap", marginTop: 0 }}>
            {pickedText(nlp) || 'Brak danych – kliknij „Generuj opis”.'}
          </pre>

          {nlp?.notes && nlp.notes.length > 0 && (
            <>
              <h4>Notatki</h4>
              <ul>
                {nlp.notes.map((n, i) => (
                  <li key={i}>{n}</li>
                ))}
              </ul>
            </>
          )}

          {nlp?.examples && (
            <>
              {Array.isArray(nlp.examples.requests) &&
                nlp.examples.requests.length > 0 && (
                  <>
                    <h4>Przykłady wywołań</h4>
                    {nlp.examples.requests.map((r: any, i: number) => {
                      const curl = typeof r === "string" ? r : (r && r.curl) || "";
                      return (
                        <pre key={i} style={{ whiteSpace: "pre-wrap" }}>
                          {curl}
                        </pre>
                      );
                    })}
                  </>
                )}
              {nlp.examples.response && (
                <>
                  <h4>Przykładowa odpowiedź</h4>
                  <pre style={{ whiteSpace: "pre-wrap" }}>
                    {JSON.stringify(nlp.examples.response, null, 2)}
                  </pre>
                </>
              )}
            </>
          )}
        </div>
      </section>

      <section style={{ marginTop: 24 }}>
        <h2>Plik dokumentacji z wgranego ZIP</h2>
        <UploadBox
          parentSetStatus={setStatus}
          parentStartTimer={startTimer}
          parentStopTimer={stopTimer}
        />
      </section>
    </div>
  );
}

/** ————————————————— Upload + obsługa specyfikacji ————————————————— */
type UploadResult = {
  id: string;
  status: "READY" | "PENDING" | "ERROR" | "NOT_FOUND";
  message?: string;
  detectedSpec?: string | null;
  projectDir?: string | null;
  zipPath?: string | null;
};

function UploadBox({
  parentSetStatus,
  parentStartTimer,
  parentStopTimer,
}: {
  parentSetStatus: (s: string) => void;
  parentStartTimer: () => void;
  parentStopTimer: () => void;
}) {
  const [file, setFile] = React.useState<File | null>(null);
  const [res, setRes] = React.useState<UploadResult | null>(null);
  const [busy, setBusy] = React.useState(false);
  const [genBusy, setGenBusy] = React.useState(false);

  const [level, setLevel] = React.useState<Level | "all">("intermediate");
  const [mode, setMode] = React.useState<Mode>("all");

  // Pobieranie PDF (poziom + tryb jak w kontrolerze)
  const downloadPdf = async () => {
    if (!res?.id) return;
    parentSetStatus("generuję PDF…");
    parentStartTimer();
    try {
      const params = new URLSearchParams();
      params.set("mode", mode);
      params.set("level", level as string); // "all" też dozwolone na backendzie

      const r = await fetch(`/api/projects/${res.id}/docs/pdf?${params}`, {
        method: "POST",
      });
      if (!r.ok) {
        const text = await r.text().catch(() => "");
        parentSetStatus(`błąd generowania PDF: ${r.status}`);
        alert(`Błąd generowania PDF: ${r.status} ${r.statusText}\n${text}`);
        return;
      }
      const blob = await r.blob();
      const filename = filenameFromCD(r, "openapi.pdf");

      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = filename;
      document.body.appendChild(a);
      a.click();
      a.remove();
      URL.revokeObjectURL(url);

      parentSetStatus("PDF pobrany ✓");
    } catch (e) {
      parentSetStatus(`błąd sieci podczas generowania PDF: ${String(e)}`);
    } finally {
      parentStopTimer();
    }
  };

// NOWE: pokazanie PDF w nowej karcie z ekranem "generuję..." i licznikem
const showPdfWeb = async () => {
  if (!res?.id) return;

  // 1) Pre-open karta (ominie blokadę popupów) i narysuj ekran "generuję..."
  const newTab = window.open("", "_blank");
  let tick: number | null = null;

  const paintLoading = () => {
    if (!newTab) return;
    newTab.document.open();
    newTab.document.write(`
      <html>
        <head>
          <meta charset="utf-8" />
          <title>Generuję dokumentację…</title>
          <style>
            body{margin:0;font-family:system-ui,-apple-system,Segoe UI,Roboto,Ubuntu,"Helvetica Neue",Arial}
            .wrap{display:flex;align-items:center;justify-content:center;min-height:100vh;background:#111827;color:#e5e7eb}
            .box{padding:24px 28px;border-radius:12px;background:#1f2937;box-shadow:0 10px 30px rgba(0,0,0,.35)}
            .msg{font-size:18px}
            .time{opacity:.8;margin-left:.35rem}
            .dot{display:inline-block;width:.5rem;height:.5rem;background:#60a5fa;border-radius:9999px;margin-left:.35rem;animation:bounce 1s infinite alternate}
            @keyframes bounce{to{transform:translateY(-3px)}}
          </style>
        </head>
        <body>
          <div class="wrap">
            <div class="box">
              <div class="msg">
                Generuję PDF… <span id="t" class="time">0.0s</span><span class="dot"></span>
              </div>
            </div>
          </div>
        </body>
      </html>
    `);
    newTab.document.close();
  };

  const startTickInNewTab = () => {
    if (!newTab) return;
    const t0 = Date.now();
    stopTickInNewTab();
    tick = window.setInterval(() => {
      try {
        const el = newTab.document.getElementById("t");
        if (el) el.textContent = ((Date.now() - t0) / 1000).toFixed(1) + "s";
      } catch {
        // ignoruj: user mógł zamknąć kartę
        stopTickInNewTab();
      }
    }, 100);
  };
  const stopTickInNewTab = () => {
    if (tick) {
      clearInterval(tick);
      tick = null;
    }
  };

  paintLoading();
  startTickInNewTab();

  // 2) Status w głównym UI
  parentSetStatus("generuję PDF…");
  parentStartTimer();

  try {
    const params = new URLSearchParams();
    params.set("mode", mode);
    params.set("level", level as string);

    const r = await fetch(`/api/projects/${res.id}/docs/pdf?${params}`, {
      method: "POST",
    });

    if (!r.ok) {
      const text = await r.text().catch(() => "");
      parentSetStatus(`błąd generowania PDF: ${r.status}`);
      alert(`Błąd generowania PDF: ${r.status} ${r.statusText}\n${text}`);
      if (newTab && !newTab.closed) newTab.close();
      return;
    }

    // 3) Mamy PDF — wstaw go do tej samej karty jako <embed>, bez nawigacji
    const blob = await r.blob();
    const url = URL.createObjectURL(blob);

    if (newTab && !newTab.closed) {
      stopTickInNewTab();
      newTab.document.open();
      newTab.document.write(`
        <html>
          <head><meta charset="utf-8"><title>Dokumentacja</title>
            <style>html,body{height:100%;margin:0} .pdf{width:100%;height:100%;border:0}</style>
          </head>
          <body>
            <embed class="pdf" src="${url}" type="application/pdf" />
          </body>
        </html>
      `);
      newTab.document.close();
      newTab.focus();
      parentSetStatus("PDF otwarty w nowej karcie ✓");
      // Revoke po chwili, by nie odciąć renderera PDF
      setTimeout(() => URL.revokeObjectURL(url), 60_000);
    } else {
      // fallback: pop-up zablokowany – otwórz w tej karcie
      window.location.href = url;
    }
  } catch (e) {
    parentSetStatus(`błąd sieci podczas generowania PDF: ${String(e)}`);
    if (newTab && !newTab.closed) newTab.close();
  } finally {
    stopTickInNewTab();
    parentStopTimer();
  }
};


  const onUpload = async () => {
    if (!file) return;
    setBusy(true);
    parentSetStatus("wysyłam ZIP…");
    parentStartTimer();
    const fd = new FormData();
    fd.append("file", file);
    try {
      const r = await fetch("/api/projects/upload", { method: "POST", body: fd });
      const j = (await r.json()) as UploadResult;
      setRes(j);
      parentSetStatus("plik wgrany ✓");
    } catch (e) {
      setRes({ id: "", status: "ERROR", message: String(e) });
      parentSetStatus("błąd podczas wgrywania.");
    } finally {
      setBusy(false);
      parentStopTimer();
    }
  };

  const openOriginal = () => {
    if (!res?.id) return;
    window.open(`/api/projects/${res.id}/spec`, "_blank");
  };

  const openEnriched = () => {
    if (!res?.id) return;
    // backend enrichment może nadal używać short/medium/long; tu przekazujemy nową skalę
    window.open(
      `/api/projects/${res.id}/spec/enriched?level=${level}`,
      "_blank",
      "noopener"
    );
  };

  const filenameFromCD = (r: Response, fallback: string) => {
    const cd = r.headers.get("Content-Disposition") || "";
    const m = cd.match(/filename="?([^"]+)"?/i);
    return m?.[1] || fallback;
  };

  const generateFromCode = async () => {
    if (!res?.id) return;
    parentSetStatus(
      mode === "all"
        ? "generuję dokumentację z kodu… (ZIP trybów)"
        : `generuję dokumentację z kodu… (${mode}${
            level === "all" ? ", all-levels" : ""
          })`
    );
    parentStartTimer();
    setGenBusy(true);
    try {
      const r = await fetch(
        `/api/projects/${res.id}/docs/from-code?mode=${encodeURIComponent(
          mode
        )}&level=${encodeURIComponent(level as string)}`,
        { method: "POST" }
      );
      if (!r.ok) {
        const text = await r.text().catch(() => "");
        parentSetStatus(
          r.status === 504
            ? "serwer przekroczył czas oczekiwania (504) – model mógł się rozgrzewać."
            : `błąd generowania: ${r.status}`
        );
        alert(`Błąd generowania: ${r.status} ${r.statusText}\n${text}`);
        return;
      }

      const ct = r.headers.get("Content-Type") || "";
      const blob = await r.blob();
      const filename = filenameFromCD(
        r,
        ct.includes("application/zip")
          ? "openapi.zip"
          : mode === "plain"
          ? "openapi.plain.yaml"
          : mode === "rules"
          ? `openapi.rules.${level}.yaml`
          : mode === "ai"
          ? `openapi.ai.${level}.yaml`
          : "openapi.generated.yaml"
      );

      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = filename;
      document.body.appendChild(a);
      a.click();
      a.remove();
      URL.revokeObjectURL(url);

      parentSetStatus("dokumentacja wygenerowana i pobrana ✓");
    } catch (e) {
      parentSetStatus(`błąd sieci podczas generowania: ${String(e)}`);
    } finally {
      setGenBusy(false);
      parentStopTimer();
    }
  };

  const hasOriginalSpec = Boolean(res?.detectedSpec);

  return (
    <div style={{ border: "1px solid #ddd", padding: 16, borderRadius: 8, marginTop: 24 }}>
      <h3>Wgraj projekt (ZIP)</h3>
      <input
        type="file"
        accept=".zip"
        onChange={(e) => setFile(e.target.files?.[0] ?? null)}
      />
      <button onClick={onUpload} disabled={!file || busy} style={{ marginLeft: 8 }}>
        {busy ? "Wysyłanie..." : "Wyślij"}
      </button>

      {res?.status && (
        <div style={{ marginTop: 12 }}>
          <div style={{ fontWeight: 600 }}>
            Status: {res.status}
            {res.detectedSpec && <> — Znaleziono specyfikację: {res.detectedSpec}</>}
          </div>

          <div style={{ marginTop: 8 }}>
            Poziom odbiorcy:&nbsp;
            <select value={level} onChange={(e) => setLevel(e.target.value as any)}>
              <option value="beginner">beginner</option>
              <option value="intermediate">intermediate</option>
              <option value="advanced">advanced</option>
              <option value="all">all</option>
            </select>
          </div>

          <div style={{ marginTop: 8 }}>
            Tryb generacji:&nbsp;
            <select value={mode} onChange={(e) => setMode(e.target.value as any)}>
              <option value="plain">plain (bez opisów)</option>
              <option value="rules">rules (reguły)</option>
              <option value="ai">ai (Ollama / LLM)</option>
              <option value="all">all (ZIP trybów)</option>
            </select>
          </div>

          <div style={{ marginTop: 8, display: "flex", gap: 8, flexWrap: "wrap" }}>
            <button onClick={openOriginal} disabled={!res?.id || !hasOriginalSpec}>
              Otwórz dokumentację (plik)
            </button>
            <button onClick={openEnriched} disabled={!res?.id || !hasOriginalSpec}>
              Otwórz wzbogaconą (NLP)
            </button>
            <button onClick={generateFromCode} disabled={!res?.id || genBusy}>
              {genBusy ? "Generuję z kodu…" : "Wygeneruj z kodu"}
            </button>
            <button onClick={downloadPdf} disabled={!res?.id}>
              Pobierz PDF
            </button>
            <button onClick={showPdfWeb} disabled={!res?.id}>
              Pokaż dokumentację
            </button>
          </div>
        </div>
      )}

      {res && (
        <pre style={{ background: "#f7f7f7", padding: 12, marginTop: 12 }}>
          {JSON.stringify(res, null, 2)}
        </pre>
      )}
    </div>
  );
}
