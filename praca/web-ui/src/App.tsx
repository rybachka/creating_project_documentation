import React from "react";
import { useEffect, useState } from "react";

type HelloResponse = { message: string };
type DescribeOut = {
  shortDescription: string;
  mediumDescription: string;
  longDescription: string;
  paramDocs: { name: string; doc: string }[];
  returnDoc?: string | null;
};

export default function App() {
  const [hello, setHello] = useState<HelloResponse | null>(null);
  const [nlp, setNlp] = useState<DescribeOut | null>(null);
  const [name, setName] = useState("Mariia");
  const [comment, setComment] = useState(
    "Zwraca użytkownika po ID. 404 gdy nie znaleziono."
  );
  const [level, setLevel] = useState<"short" | "medium" | "long">("short");

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
    const r = await fetch("/nlp/describe", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
    setNlp(await r.json());
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
          &nbsp;<button onClick={runNlp}>Generuj opis</button>
        </div>

        <pre style={{ whiteSpace: "pre-wrap", background: "#f4f4f4", padding: 12 }}>
          {pick(nlp) || 'Brak danych – kliknij „Generuj opis”.'}
        </pre>
      </section>

      <section style={{ marginTop: 24 }}>
        <h2>Plik dokumentacji z wgranego ZIP</h2>
        <UploadBox />
      </section>
    </div>
  );
}

/** ————————————————— Upload + otwieranie pliku specyfikacji ————————————————— */
type UploadResult = {
  id: string;
  status: "READY" | "PENDING" | "ERROR" | "NOT_FOUND";
  message?: string;
  detectedSpec?: string | null; // np. "openapi.yaml" lub ścieżka względna
  projectDir?: string | null;
  zipPath?: string | null;
};

function UploadBox() {
  const [file, setFile] = React.useState<File | null>(null);
  const [res, setRes] = React.useState<any>(null);
  const [busy, setBusy] = React.useState(false);
  const [level, setLevel] = React.useState<"short"|"medium"|"long">("medium");

  const onUpload = async () => {
    if (!file) return;
    setBusy(true);
    const fd = new FormData();
    fd.append("file", file);
    try {
      const r = await fetch("/api/projects/upload", { method: "POST", body: fd });
      const j = await r.json();
      setRes(j);
    } catch (e) {
      setRes({ status: "ERROR", message: String(e) });
    } finally {
      setBusy(false);
    }
  };

  // Oryginalny plik z ZIP-a
  const openOriginal = () => {
    if (!res?.id) return;
    window.open(`/api/projects/${res.id}/spec`, "_blank");
  };

  // Wersja wzbogacona NLP
  const openEnriched = () => {
    if (!res?.id) return;
    window.open(`/api/projects/${res.id}/spec/enriched?level=${level}`, "_blank");
  };

  return (
    <div style={{border:'1px solid #ddd', padding:16, borderRadius:8, marginTop:24}}>
      <h3>Wgraj projekt (ZIP)</h3>
      <input type="file" accept=".zip" onChange={e => setFile(e.target.files?.[0] ?? null)} />
      <button onClick={onUpload} disabled={!file || busy} style={{marginLeft:8}}>
        {busy ? "Wysyłanie..." : "Wyślij"}
      </button>

      {/* status + akcje */}
      {res?.status && (
        <div style={{marginTop:12, fontWeight:600}}>
          Status: {res.status}
          {res.detectedSpec && <> — Znaleziono specyfikację: {res.detectedSpec}</>}
        </div>
      )}

      <div style={{ marginTop: 12 }}>
        Poziom opisu:&nbsp;
        <select value={level} onChange={e=>setLevel(e.target.value as any)}>
          <option value="short">krótki</option>
          <option value="medium">średni</option>
          <option value="long">długi</option>
        </select>
        &nbsp;
        <button onClick={openOriginal} disabled={!res?.id}>Otwórz dokumentację (plik)</button>
        &nbsp;
        <button onClick={openEnriched} disabled={!res?.id}>Otwórz wzbogaconą (NLP)</button>
      </div>

      {res && (
        <pre style={{background:'#f7f7f7', padding:12, marginTop:12}}>
{JSON.stringify(res, null, 2)}
        </pre>
      )}
    </div>
  );
}


