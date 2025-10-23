import React, { useEffect, useRef, useState } from "react";

type HelloResponse = { message: string };
type DescribeOut = {
  shortDescription: string;
  mediumDescription: string;
  longDescription: string;
  paramDocs: { name: string; doc: string }[];
  returnDoc?: string | null;
};

type Healthz = { status: string; mt5?: string; device?: string };

export default function App() {
  const [hello, setHello] = useState<HelloResponse | null>(null);
  const [nlp, setNlp] = useState<DescribeOut | null>(null);
  const [name, setName] = useState("Mariia");
  const [comment, setComment] = useState(
    "Zwraca użytkownika po ID. 404 gdy nie znaleziono."
  );
  const [level, setLevel] = useState<"short" | "medium" | "long">("short");

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
      kind: "function",
      signature: "getUserById(id: string): User",
      comment,
      params: [{ name: "id", type: "string", description: "Identyfikator" }],
      returns: { type: "User", description: "Obiekt użytkownika" },
    };

    setNlp(null);
    setStatus("sprawdzam usługę NLP…");
    startTimer();

    // mały healthcheck
    let health: Healthz | null = null;
    try {
      const hr = await fetch("/nlp/healthz");
      if (hr.ok) health = await hr.json();
    } catch {}

    if (!health) setStatus("usługa NLP niedostępna – spróbuję mimo to…");
    else if (!health.mt5 || health.mt5 === "unavailable")
      setStatus("rozgrzewam model (pierwsze wywołanie może potrwać)…");
    else setStatus(`model: ${health.mt5} (${health.device}) – generuję opis…`);

    const ac = new AbortController();
    try {
      const r = await fetch("/nlp/describe", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
        signal: ac.signal,
      });

      if (!r.ok) {
        if (r.status === 504) {
          setStatus(
            "serwer przekroczył czas oczekiwania (504) – model mógł się jeszcze ładować."
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

  const pick = (d?: DescribeOut | null) =>
    !d
      ? ""
      : level === "short"
      ? d.shortDescription
      : level === "medium"
      ? d.mediumDescription
      : d.longDescription;

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
        <h2>Test NLP</h2>

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
          Poziom szczegółowości:&nbsp;
          <select value={level} onChange={(e) => setLevel(e.target.value as any)}>
            <option value="short">krótki</option>
            <option value="medium">średni</option>
            <option value="long">długi</option>
          </select>
          &nbsp;
          <button onClick={runNlp}>Generuj opis</button>
        </div>

        <pre style={{ whiteSpace: "pre-wrap", background: "#f4f4f4", padding: 12 }}>
          {pick(nlp) || 'Brak danych – kliknij „Generuj opis”.'}
        </pre>
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
  const [genBusy, setGenBusy] = React.useState(false); // osobny boolean (fix TS2345)
  const [level, setLevel] = React.useState<"short" | "medium" | "long">("medium");
  const [mode, setMode] = React.useState<"plain" | "rules" | "mt5" | "all">("all");

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
    window.open(`/api/projects/${res.id}/spec/enriched?level=${level}`, "_blank");
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
        ? "generuję dokumentację z kodu… (ZIP z 3 plikami)"
        : `generuję dokumentację z kodu… (${mode})`
    );
    parentStartTimer();
    setGenBusy(true);
    try {
      const r = await fetch(
        `/api/projects/${res.id}/docs/from-code?mode=${encodeURIComponent(
          mode
        )}&level=${encodeURIComponent(level)}`,
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
      // Jeśli ZIP -> pobierz binarnie
      if (ct.includes("application/zip")) {
        const blob = await r.blob();
        const filename = filenameFromCD(r, "openapi.all.zip");
        const url = URL.createObjectURL(blob);
        const a = document.createElement("a");
        a.href = url;
        a.download = filename;
        document.body.appendChild(a);
        a.click();
        a.remove();
        URL.revokeObjectURL(url);
        parentSetStatus("ZIP pobrany ✓");
        return;
      }

      // W przeciwnym razie traktujemy to jako YAML (tekst)
      const yamlText = await r.text();
      // jeśli backend w trybie =all jednak zwróci JSON (np. ścieżki), pokaż komunikat
      if (yamlText.startsWith("{") || yamlText.startsWith("[")) {
        // UX: uprzejmy komunikat, ale i tak spróbujemy zapisać jako .json
        console.warn("Otrzymano JSON — możliwe, że to mapa ścieżek plików.");
        const blob = new Blob([yamlText], { type: "application/json" });
        const url = URL.createObjectURL(blob);
        const a = document.createElement("a");
        a.href = url;
        a.download = "openapi.generated.json";
        document.body.appendChild(a);
        a.click();
        a.remove();
        URL.revokeObjectURL(url);
        parentSetStatus("plik JSON pobrany (zamiast YAML) ✓");
        return;
      }

      const blob = new Blob([yamlText], { type: "text/yaml" });
      const filename =
        mode === "plain"
          ? "openapi.plain.yaml"
          : mode === "rules"
          ? "openapi.rules.yaml"
          : mode === "mt5"
          ? "openapi.mt5.yaml"
          : "openapi.generated.yaml"; // fallback
      const url = URL.createObjectURL(blob);

      const a = document.createElement("a");
      a.href = url;
      a.download = filenameFromCD(r, filename);
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
            Poziom opisu:&nbsp;
            <select value={level} onChange={(e) => setLevel(e.target.value as any)}>
              <option value="short">krótki</option>
              <option value="medium">średni</option>
              <option value="long">długi</option>
            </select>
          </div>

          <div style={{ marginTop: 8 }}>
            Tryb generacji:&nbsp;
            <select value={mode} onChange={(e) => setMode(e.target.value as any)}>
              <option value="plain">plain (bez opisów)</option>
              <option value="rules">rules (fallback)</option>
              <option value="mt5">mt5 (model)</option>
              <option value="all">all (ZIP 3 plików)</option>
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
