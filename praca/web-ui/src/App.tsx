import React, { useRef, useState } from "react";
import EditableDocsPanel from "./EditableDocsPanel";

type Level = "beginner" | "advanced";
type LevelWithAll = Level | "all";

type UploadResult = {
  id: string;
  status: "READY" | "PENDING" | "ERROR" | "NOT_FOUND";
  message?: string;
  detectedSpec?: string | null;
  projectDir?: string | null;
  zipPath?: string | null;
};

type ParamIn = {
  name?: string;
  in?: string;
  in_?: string;
  type?: string;
  required?: boolean;
  description?: string;
};

type ReturnsIn = {
  type?: string;
  description?: string;
};

type NlpInputEntry = {
  symbol?: string;
  kind?: string;
  signature?: string;
  method?: string;
  http?: string;
  path?: string;
  pathTemplate?: string;
  comment?: string;
  rawComment?: string;
  javadoc?: string;
  notes?: string[];
  todos?: string[];
  implNotes?: string[];
  language?: string;
  params?: ParamIn[];
  returns?: ReturnsIn;
  requestBody?: any;

  audience?: string;
  mode?: string;

  [key: string]: any;
};

type NlpOutputPreview = {
  symbol?: string;
  prompt: string;
  raw: string;
};

export default function App() {
  const [status, setStatus] = useState<string>("gotowa.");
  const [elapsed, setElapsed] = useState<string>("0.0s");

  const tickRef = useRef<number | null>(null);
  const t0Ref = useRef<number>(0);

  // YAML do edycji + poziom odbiorcy
  const [editableYaml, setEditableYaml] = useState<string | null>(null);
  const [audience, setAudience] = useState<LevelWithAll>("beginner");

  const startTimer = () => {
    t0Ref.current = performance.now();
    stopTimer();
    tickRef.current = window.setInterval(() => {
      const s = ((performance.now() - t0Ref.current) / 1000).toFixed(1) + "s";
      setElapsed(s);
    }, 100);
  };

  const stopTimer = () => {
    if (tickRef.current) {
      clearInterval(tickRef.current);
      tickRef.current = null;
    }
  };

  return (
    <div
      style={{
        maxWidth: 900,
        margin: "2rem auto",
        fontFamily: "system-ui, -apple-system, BlinkMacSystemFont, sans-serif",
      }}
    >
      <h1>AI Docs – Web UI</h1>

      <section style={{ marginTop: 24 }}>
        <h2>Test NLP (Ollama / LLM)</h2>

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
      </section>

      <section style={{ marginTop: 24 }}>
        <h2>Plik dokumentacji z wgranego ZIP</h2>
        <UploadBox
          parentSetStatus={setStatus}
          parentStartTimer={startTimer}
          parentStopTimer={stopTimer}
          onEditableYamlLoaded={setEditableYaml}
          onAudienceChange={setAudience}
          editableYaml={editableYaml}
        />
      </section>

      {editableYaml && (
        <section style={{ marginTop: 32 }}>
          <h2>Edytuj dokumentację (tryb formularz per endpoint)</h2>
          <p style={{ fontSize: 13, color: "#555", marginBottom: 8 }}>
            Wybierz endpoint z lewej, a po prawej edytuj opisy, odpowiedzi,
            przykłady i notatki. Reszta dokumentu OpenAPI (info, servers,
            components…) zostaje zachowana automatycznie.
          </p>
          <EditableDocsPanel
            yaml={editableYaml}
            onYamlChange={setEditableYaml}
            isAdvanced={audience === "advanced"}
          />
        </section>
      )}
    </div>
  );
}

/** ————————————————— Upload + akcje + NLP preview ————————————————— */

function UploadBox({
  parentSetStatus,
  parentStartTimer,
  parentStopTimer,
  onEditableYamlLoaded,
  onAudienceChange,
  editableYaml,
}: {
  parentSetStatus: (s: string) => void;
  parentStartTimer: () => void;
  parentStopTimer: () => void;
  onEditableYamlLoaded: (yaml: string) => void;
  onAudienceChange: (level: LevelWithAll) => void;
  editableYaml: string | null;
}) {
  const [file, setFile] = React.useState<File | null>(null);
  const [res, setRes] = React.useState<UploadResult | null>(null);
  const [busy, setBusy] = React.useState(false);

  const [level, setLevel] = React.useState<LevelWithAll>("beginner");
  const DEFAULT_MODE: "ollama" = "ollama";

  const [nlpInput, setNlpInput] = React.useState<NlpInputEntry[] | null>(null);
  const [selectedOutput, setSelectedOutput] =
    React.useState<NlpOutputPreview | null>(null);
  const [loadingOutputFor, setLoadingOutputFor] = React.useState<string | null>(
    null
  );
  const [htmlBusy, setHtmlBusy] = React.useState(false);

  React.useEffect(() => {
    onAudienceChange(level);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const onUpload = async () => {
    if (!file) return;

    setBusy(true);
    parentSetStatus("wysyłam ZIP…");
    parentStartTimer();

    const fd = new FormData();
    fd.append("file", file);

    try {
      const r = await fetch("/api/projects/upload", {
        method: "POST",
        body: fd,
      });
      const j = (await r.json()) as UploadResult;
      setRes(j);
      setNlpInput(null);
      setSelectedOutput(null);

      // po nowym uploadzie czyścimy YAML do edycji
      onEditableYamlLoaded("");

      parentSetStatus(
        "PENDING — Projekt wgrany. Możesz wygenerować dokumentację z kodu."
      );
    } catch (e) {
      setRes({ id: "", status: "ERROR", message: String(e) });
      setNlpInput(null);
      setSelectedOutput(null);
      parentSetStatus("błąd podczas wgrywania.");
    } finally {
      setBusy(false);
      parentStopTimer();
    }
  };

  const filenameFromCD = (r: Response, fallback: string) => {
    const cd = r.headers.get("Content-Disposition") || "";
    const m = cd.match(/filename="?([^"]+)"?/i);
    return m?.[1] || fallback;
  };

  const downloadPdf = async () => {
    if (!res?.id) return;

    parentSetStatus("generuję PDF…");
    parentStartTimer();

    try {
      const params = new URLSearchParams();
      params.set("level", level as string);

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

  const showPdfWeb = async () => {
    if (!res?.id) return;

    const newTab = window.open("", "_blank");
    let tick: number | null = null;

    const stopTick = () => {
      if (tick) {
        clearInterval(tick);
        tick = null;
      }
    };
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

    const startTick = () => {
      if (!newTab) return;
      const t0 = Date.now();
      stopTick();
      tick = window.setInterval(() => {
        try {
          const el = newTab.document.getElementById("t");
          if (el) el.textContent = ((Date.now() - t0) / 1000).toFixed(1) + "s";
        } catch {
          stopTick();
        }
      }, 100);
    };

    paintLoading();
    startTick();

    parentSetStatus("generuję PDF…");
    parentStartTimer();

    try {
      const params = new URLSearchParams();
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

      const blob = await r.blob();
      const url = URL.createObjectURL(blob);

      if (newTab && !newTab.closed) {
        stopTick();
        newTab.document.open();
        newTab.document.write(`
          <html>
            <head>
              <meta charset="utf-8" />
              <title>Dokumentacja</title>
              <style>
                html,body{height:100%;margin:0}
                .pdf{width:100%;height:100%;border:0}
              </style>
            </head>
            <body>
              <embed class="pdf" src="${url}" type="application/pdf" />
            </body>
          </html>
        `);
        newTab.document.close();
        newTab.focus();
        parentSetStatus("PDF otwarty w nowej karcie ✓");
        setTimeout(() => URL.revokeObjectURL(url), 60_000);
      } else {
        window.location.href = url;
      }
    } catch (e) {
      parentSetStatus(`błąd sieci podczas generowania PDF: ${String(e)}`);
      if (newTab && !newTab.closed) newTab.close();
    } finally {
      stopTick();
      parentStopTimer();
    }
  };

  const showYamlWeb = async () => {
    if (!res?.id) return;

    const params = new URLSearchParams();
    params.set("level", level as string);

    const url = `/api/projects/${res.id}/docs/yaml?${params.toString()}`;
    window.open(url, "_blank");
  };

  const downloadYamlFile = async () => {
    if (!res?.id) return;

    parentSetStatus("generuję YAML…");
    parentStartTimer();

    try {
      const params = new URLSearchParams();
      params.set("level", level as string);

      const r = await fetch(
        `/api/projects/${res.id}/docs/yaml/download?${params.toString()}`,
        { method: "GET" }
      );

      if (!r.ok) {
        const text = await r.text().catch(() => "");
        parentSetStatus(`błąd generowania YAML: ${r.status}`);
        alert(`Błąd generowania YAML: ${r.status} ${r.statusText}\n${text}`);
        return;
      }

      const blob = await r.blob();
      const filename = filenameFromCD(r, "openapi.yaml");

      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = filename;
      document.body.appendChild(a);
      a.click();
      a.remove();
      URL.revokeObjectURL(url);

      parentSetStatus("YAML pobrany ✓");
    } catch (e) {
      parentSetStatus(`błąd sieci podczas generowania YAML: ${String(e)}`);
    } finally {
      parentStopTimer();
    }
  };

  const loadNlpInput = async () => {
    if (!res?.id) return;

    parentSetStatus("pobieram dane wejściowe dla modelu…");
    parentStartTimer();

    try {
      const params = new URLSearchParams();
      params.set("level", level as string);
      params.set("mode", DEFAULT_MODE);

      const r = await fetch(
        `/api/projects/${res.id}/docs/nlp-input?${params.toString()}`,
        { method: "GET" }
      );

      if (!r.ok) {
        const text = await r.text().catch(() => "");
        parentSetStatus(`błąd pobierania danych dla modelu: ${r.status}`);
        alert(
          `Błąd pobierania danych wejściowych: ${r.status} ${r.statusText}\n${text}`
        );
        setNlpInput(null);
        setSelectedOutput(null);
        return;
      }

      const data = await r.json();
      const arr: NlpInputEntry[] = Array.isArray(data) ? data : [data];
      setNlpInput(arr);
      setSelectedOutput(null);

      parentSetStatus("dane wejściowe dla modelu załadowane ✓");
    } catch (e) {
      parentSetStatus(
        `błąd sieci podczas pobierania danych dla modelu: ${String(e)}`
      );
      setNlpInput(null);
      setSelectedOutput(null);
    } finally {
      parentStopTimer();
    }
  };

  const loadNlpOutput = async (entry: NlpInputEntry) => {
    if (!entry) return;

    const key =
      entry.symbol ||
      `${entry.method || entry.http || ""} ${
        entry.path || entry.pathTemplate || entry.signature || ""
      }`;

    setLoadingOutputFor(key);
    parentSetStatus("pobieram dane wyjściowe od modelu…");
    parentStartTimer();

    try {
      const r = await fetch("/api/nlp/output-preview", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
        },
        body: JSON.stringify(entry),
      });

      if (!r.ok) {
        const text = await r.text().catch(() => "");
        parentSetStatus(`błąd pobierania danych wyjściowych: ${r.status}`);
        alert(
          `Błąd pobierania danych wyjściowych: ${r.status} ${r.statusText}\n${text}`
        );
        return;
      }

      const data = await r.json();

      setSelectedOutput({
        symbol: entry.symbol,
        prompt: data.prompt || "",
        raw: data.raw || "",
      });

      parentSetStatus("dane wyjściowe od modelu załadowane ✓");
    } catch (e) {
      parentSetStatus(
        `błąd sieci podczas pobierania danych wyjściowych: ${String(e)}`
      );
    } finally {
      setLoadingOutputFor(null);
      parentStopTimer();
    }
  };

  // pobranie pełnego YAML do edycji (cały dokument)
  const generateEditableYaml = async () => {
    if (!res?.id) return;

    parentSetStatus("generuję YAML do edycji…");
    parentStartTimer();
    setHtmlBusy(true);

    try {
      const params = new URLSearchParams();
      params.set("level", level as string);

      const r = await fetch(
        `/api/projects/${res.id}/docs/editable?${params.toString()}`,
        { method: "GET" }
      );

      if (!r.ok) {
        const text = await r.text().catch(() => "");
        parentSetStatus(`błąd generowania YAML: ${r.status}`);
        alert(`Błąd generowania YAML: ${r.status} ${r.statusText}\n${text}`);
        return;
      }

      const text = await r.text();
      onEditableYamlLoaded(text);
      parentSetStatus("YAML do edycji załadowany ✓");
    } catch (e) {
      parentSetStatus(`błąd sieci: ${String(e)}`);
    } finally {
      parentStopTimer();
      setHtmlBusy(false);
    }
  };

  // 🔹 PDF z edytowanego YAML
  const downloadEditedPdf = async () => {
    if (!res?.id || !editableYaml) {
      alert("Brak edytowanej dokumentacji YAML.");
      return;
    }

    parentSetStatus("generuję PDF z edytowanej dokumentacji…");
    parentStartTimer();

    try {
      const params = new URLSearchParams();
      params.set("level", level as string);

      const r = await fetch(
        `/api/projects/${res.id}/docs/edited/pdf?${params.toString()}`,
        {
          method: "POST",
          headers: {
            "Content-Type": "text/plain; charset=utf-8",
          },
          body: editableYaml,
        }
      );

      if (!r.ok) {
        const text = await r.text().catch(() => "");
        parentSetStatus(`błąd generowania edytowanego PDF: ${r.status}`);
        alert(
          `Błąd generowania edytowanego PDF: ${r.status} ${r.statusText}\n${text}`
        );
        return;
      }

      const blob = await r.blob();
      const filename = filenameFromCD(r, "openapi_edited.pdf");

      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = filename;
      document.body.appendChild(a);
      a.click();
      a.remove();
      URL.revokeObjectURL(url);

      parentSetStatus("Edytowany PDF pobrany ✓");
    } catch (e) {
      parentSetStatus(
        `błąd sieci podczas generowania edytowanego PDF: ${String(e)}`
      );
    } finally {
      parentStopTimer();
    }
  };

  const renderParams = (params?: ParamIn[]) => {
    if (!params || params.length === 0) return "";
    return params
      .map((p) => {
        const place = p.in_ || p.in || "query";
        const req = p.required ? "req" : "opt";
        return `${place}:${p.name}:${p.type || "any"}:${req}${
          p.description ? ` — ${p.description}` : ""
        }`;
      })
      .join("\n");
  };

  const renderReturns = (ret?: ReturnsIn) => {
    if (!ret) return "";
    return `${ret.type || "void"}${
      ret.description ? ` — ${ret.description}` : ""
    }`;
  };

  const pretty = (v: any) =>
    v == null || v === "" ? "" : JSON.stringify(v, null, 2);

  return (
    <div
      style={{
        border: "1px solid #ddd",
        padding: 16,
        borderRadius: 8,
        marginTop: 8,
      }}
    >
      <h3>Wgraj projekt (ZIP)</h3>
      <input
        type="file"
        accept=".zip"
        onChange={(e) => setFile(e.target.files?.[0] ?? null)}
      />
      <button
        onClick={onUpload}
        disabled={!file || busy}
        style={{ marginLeft: 8 }}
      >
        {busy ? "Wysyłanie..." : "Wyślij"}
      </button>

      {res?.status && (
        <div style={{ marginTop: 12 }}>
          <div style={{ fontWeight: 600 }}>
            Status: {res.status}
            {res.message && (
              <span style={{ fontWeight: 400, marginLeft: 6 }}>
                — {res.message}
              </span>
            )}
            {res.detectedSpec && (
              <span style={{ fontWeight: 400, marginLeft: 6 }}>
                (Znaleziono specyfikację: {res.detectedSpec})
              </span>
            )}
          </div>

          <div style={{ marginTop: 8 }}>
            Poziom odbiorcy:&nbsp;
            <select
              value={level}
              onChange={(e) => {
                const newLevel = e.target.value as LevelWithAll;
                setLevel(newLevel);
                onAudienceChange(newLevel);
              }}
            >
              <option value="beginner">beginner</option>
              <option value="advanced">advanced</option>
            </select>
          </div>

          <div
            style={{
              marginTop: 8,
              display: "flex",
              gap: 8,
              flexWrap: "wrap",
            }}
          >
            <button onClick={downloadPdf} disabled={!res?.id}>
              Pobierz PDF
            </button>
            <button onClick={showPdfWeb} disabled={!res?.id}>
              Pokaż dokumentację
            </button>
            <button onClick={loadNlpInput} disabled={!res?.id}>
              Pobierz dane wejściowe dla modelu
            </button>

            <button onClick={showYamlWeb} disabled={!res?.id}>
              Podgląd YAML
            </button>
            <button onClick={downloadYamlFile} disabled={!res?.id}>
              Pobierz YAML
            </button>

            <button
              onClick={generateEditableYaml}
              disabled={!res?.id || htmlBusy}
            >
              {htmlBusy ? "Generuję…" : "Generuj (do edycji)"}
            </button>

            <button
              onClick={downloadEditedPdf}
              disabled={!res?.id || !editableYaml}
            >
              Pobierz edytowany PDF
            </button>
          </div>

          {nlpInput && nlpInput.length > 0 && (
            <>
              <div
                style={{
                  marginTop: 16,
                  fontWeight: 600,
                  marginBottom: 4,
                }}
              >
                Dane wejściowe przekazywane do modelu
                <span style={{ fontWeight: 400, marginLeft: 6 }}>
                  (pełny IR + audience + mode dla każdego endpointu)
                </span>
              </div>
              <div
                style={{
                  maxHeight: 400,
                  overflow: "auto",
                  border: "1px solid #eee",
                }}
              >
                <table
                  style={{
                    borderCollapse: "collapse",
                    width: "100%",
                    fontSize: 12,
                  }}
                >
                  <thead>
                    <tr>
                      <th style={th}>symbol</th>
                      <th style={th}>kind</th>
                      <th style={th}>method</th>
                      <th style={th}>path</th>
                      <th style={th}>audience</th>
                      <th style={th}>mode</th>
                      <th style={th}>params (pełne)</th>
                      <th style={th}>returns</th>
                      <th style={th}>comment / javadoc</th>
                      <th style={th}>notes / todos / implNotes</th>
                      <th style={th}>requestBody</th>
                      <th style={th}>podgląd odpowiedzi</th>
                    </tr>
                  </thead>
                  <tbody>
                    {nlpInput.map((entry, i) => {
                      const method =
                        (entry.method || entry.http || "").toString();
                      const path =
                        entry.pathTemplate ||
                        entry.path ||
                        entry.signature ||
                        "";
                      const audience = entry.audience;
                      const mode = entry.mode || DEFAULT_MODE;

                      const rowKey =
                        entry.symbol ||
                        `${method || ""} ${path || ""}` ||
                        String(i);

                      return (
                        <tr key={rowKey}>
                          <td style={td}>{entry.symbol}</td>
                          <td style={td}>{entry.kind}</td>
                          <td style={td}>{method}</td>
                          <td style={td}>{path}</td>
                          <td style={td}>{audience}</td>
                          <td style={td}>{mode}</td>
                          <td style={{ ...td, whiteSpace: "pre-wrap" }}>
                            {renderParams(entry.params)}
                          </td>
                          <td style={{ ...td, whiteSpace: "pre-wrap" }}>
                            {renderReturns(entry.returns)}
                          </td>
                          <td style={{ ...td, whiteSpace: "pre-wrap" }}>
                            {[
                              entry.comment,
                              entry.javadoc,
                              entry.rawComment,
                            ]
                              .filter(Boolean)
                              .join("\n---\n")}
                          </td>
                          <td style={{ ...td, whiteSpace: "pre-wrap" }}>
                            {[
                              ...(entry.notes || []),
                              ...(entry.todos || []),
                              ...(entry.implNotes || []),
                            ]
                              .filter(Boolean)
                              .join("\n")}
                          </td>
                          <td style={{ ...td, whiteSpace: "pre-wrap" }}>
                            {pretty(entry.requestBody)}
                          </td>
                          <td style={td}>
                            <button
                              onClick={() => loadNlpOutput(entry)}
                              style={{ fontSize: 10 }}
                              disabled={
                                !!loadingOutputFor &&
                                loadingOutputFor !== rowKey
                              }
                            >
                              {loadingOutputFor === rowKey
                                ? "Ładuję…"
                                : "Pokaż dane wyjściowe"}
                            </button>
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>

              {selectedOutput && (
                <div style={{ marginTop: 16 }}>
                  <div
                    style={{
                      fontWeight: 600,
                      marginBottom: 4,
                    }}
                  >
                    Podgląd danych wyjściowych od modelu
                    {selectedOutput.symbol && (
                      <span
                        style={{ fontWeight: 400, marginLeft: 6 }}
                      >{`(endpoint: ${selectedOutput.symbol})`}</span>
                    )}
                  </div>
                  <div
                    style={{
                      display: "grid",
                      gridTemplateColumns: "1fr 1fr",
                      gap: 8,
                      alignItems: "stretch",
                    }}
                  >
                    <div>
                      <div
                        style={{
                          fontSize: 12,
                          fontWeight: 600,
                          marginBottom: 4,
                        }}
                      >
                        Prompt wysłany do modelu
                      </div>
                      <pre
                        style={{
                          whiteSpace: "pre-wrap",
                          wordBreak: "break-word",
                          border: "1px solid #eee",
                          borderRadius: 4,
                          padding: 8,
                          fontSize: 11,
                          maxHeight: 300,
                          overflow: "auto",
                          background: "#fafafa",
                        }}
                      >
                        {selectedOutput.prompt}
                      </pre>
                    </div>
                    <div>
                      <div
                        style={{
                          fontSize: 12,
                          fontWeight: 600,
                          marginBottom: 4,
                        }}
                      >
                        Surowa odpowiedź modelu
                      </div>
                      <pre
                        style={{
                          whiteSpace: "pre-wrap",
                          wordBreak: "break-word",
                          border: "1px solid #eee",
                          borderRadius: 4,
                          padding: 8,
                          fontSize: 11,
                          maxHeight: 300,
                          overflow: "auto",
                          background: "#fafafa",
                        }}
                      >
                        {selectedOutput.raw}
                      </pre>
                    </div>
                  </div>
                </div>
              )}
            </>
          )}
        </div>
      )}
    </div>
  );
}

const th: React.CSSProperties = {
  borderBottom: "1px solid #ddd",
  padding: "4px 6px",
  textAlign: "left",
  background: "#fafafa",
  position: "sticky",
  top: 0,
  zIndex: 1,
};

const td: React.CSSProperties = {
  borderBottom: "1px solid #f0f0f0",
  padding: "4px 6px",
  verticalAlign: "top",
};
