// src/App.tsx
import React, { useRef, useState } from "react";
import { StartBar } from "./components/StartBar";
import { HelloText } from "./components/HelloText";
import {
  ProjectUploadPanel,
  UploadResult,
} from "./components/ProjectUploadPanel";
import { StatusBar } from "./components/StatusBar";
import { LevelPanel, Level } from "./components/LevelPanel";
import { StatusGenerate } from "./components/StatusGenerate";
import { DocsActionsPanel } from "./components/DocsActionsPanel";
import EditableDocsPanel from "./EditableDocsPanel";
import { HowItWorksPanel } from "./components/HowItWorksPanel";
import { ContactPanel } from "./components/ContactPanel";

const App: React.FC = () => {
  const [showContact, setShowContact] = useState(false);
  const [showHowItWorks, setShowHowItWorks] = useState(false);

  // wspólny timer (upload + generowanie)
  const [status, setStatus] = useState<string>("Gotowa.");
  const [elapsed, setElapsed] = useState<string>("0.0s");
  const [busy, setBusy] = useState(false);

  const [level, setLevel] = useState<Level>("beginner");
  const [uploadResult, setUploadResult] = useState<UploadResult | null>(null);

  // stan dla generowania
  const [isGenerating, setIsGenerating] = useState(false);
  const [docsReady, setDocsReady] = useState(false);
  const [pdfUrl, setPdfUrl] = useState<string | null>(null);

  // stan paska postępu (0–100)
  const [progress, setProgress] = useState(0);

  // stan edycji YAML
  const [editableYaml, setEditableYaml] = useState<string | null>(null);

  const tickRef = useRef<number | null>(null);
  const t0Ref = useRef<number>(0);
  const progressRef = useRef<number | null>(null);

  const startTimer = () => {
    t0Ref.current = performance.now();
    setBusy(true);

    if (tickRef.current) {
      clearInterval(tickRef.current);
    }

    tickRef.current = window.setInterval(() => {
      const s =
        ((performance.now() - t0Ref.current) / 1000).toFixed(1) + "s";
      setElapsed(s);
    }, 100);
  };

  const stopTimer = () => {
    if (tickRef.current) {
      clearInterval(tickRef.current);
      tickRef.current = null;
    }
    setBusy(false);
  };

  // pseudo-progres dla generowania PDF
  const startProgress = () => {
    setProgress(5); // zaczynamy od 5%, żeby coś było widać
    if (progressRef.current) {
      clearInterval(progressRef.current);
    }
    progressRef.current = window.setInterval(() => {
      setProgress((prev) => {
        if (prev >= 90) return 90; // czekamy na backend – max 90%
        return prev + 0.5; // wolniejsze wypełnianie
      });
    }, 400);
  };

  const stopProgress = (success: boolean) => {
    if (progressRef.current) {
      clearInterval(progressRef.current);
      progressRef.current = null;
    }
    setProgress(success ? 100 : 0);
  };

  const projectUploaded = !!uploadResult;
  const projectLabel =
    uploadResult?.fileName ||
    uploadResult?.message ||
    uploadResult?.id ||
    "projekt";

  // ==============================
  //  POWRÓT NA EKRAN DOMOWY (logo / „Funkcje”)
  // ==============================
  const handleHome = () => {
    // zatrzymujemy wszystkie timery
    if (tickRef.current) {
      clearInterval(tickRef.current);
      tickRef.current = null;
    }
    if (progressRef.current) {
      clearInterval(progressRef.current);
      progressRef.current = null;
    }

    // chowamy panele pomocnicze
    setShowHowItWorks(false);
    setShowContact(false);

    // resetujemy stan generatora
    setUploadResult(null);
    setDocsReady(false);
    setIsGenerating(false);
    setEditableYaml(null);
    setPdfUrl(null);
    setProgress(0);

    // reset statusu / zegara
    setStatus("Gotowa.");
    setElapsed("0.0s");
    setBusy(false);

    // opcjonalnie: przewijanie na górę
    window.scrollTo({ top: 0, behavior: "smooth" });
  };

  // ==============================
  //  GENEROWANIE PDF PO „TAK”
  // ==============================
  const handleGenerateDocs = async () => {
    if (!uploadResult) return;

    setDocsReady(false);
    setPdfUrl(null);
    setEditableYaml(null); // reset edytora
    setStatus("Trwa generowanie dokumentacji…");
    setElapsed("0.0s");
    setIsGenerating(true);

    startTimer();
    startProgress();

    let ok = false;

    try {
      const url = `/api/projects/${uploadResult.id}/docs/pdf?level=${level}`;
      const res = await fetch(url, { method: "POST" });

      if (!res.ok) {
        const text = await res.text().catch(() => "");
        console.error(
          "Błąd generowania PDF:",
          res.status,
          res.statusText,
          text
        );
        setStatus("Błąd generowania dokumentacji.");
        return;
      }

      const blob = await res.blob();

      if (pdfUrl) {
        URL.revokeObjectURL(pdfUrl);
      }
      const objectUrl = URL.createObjectURL(blob);
      setPdfUrl(objectUrl);

      setStatus("Dokumentacja wygenerowana.");
      setDocsReady(true);
      ok = true;
    } catch (err) {
      console.error("Błąd sieci podczas generowania PDF:", err);
      setStatus("Błąd sieci podczas generowania dokumentacji.");
    } finally {
      stopTimer();
      stopProgress(ok);
      setIsGenerating(false);
    }
  };

  // ==============================
  //  AKCJE PO WYGNEROWANIU PDF
  // ==============================

  const handleDownloadPdf = () => {
    if (!pdfUrl) return;
    const link = document.createElement("a");
    link.href = pdfUrl;
    link.download = `${projectLabel}_${level}.pdf`;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
  };

  const handlePreviewPdf = () => {
    if (!pdfUrl) return;
    window.open(pdfUrl, "_blank");
  };

  // Pobrać YAML
  const handleDownloadYaml = async () => {
    if (!uploadResult) return;

    setStatus("Pobieram YAML…");

    try {
      const url = `/api/projects/${uploadResult.id}/docs/yaml/download?level=${level}`;
      const res = await fetch(url);

      if (!res.ok) {
        const text = await res.text().catch(() => "");
        console.error(
          "Błąd pobierania YAML:",
          res.status,
          res.statusText,
          text
        );
        setStatus("Błąd pobierania YAML.");
        return;
      }

      const blob = await res.blob();
      const link = document.createElement("a");
      link.href = URL.createObjectURL(blob);
      link.download = `${projectLabel}_${level}.yaml`;
      document.body.appendChild(link);
      link.click();
      document.body.removeChild(link);

      setStatus("YAML pobrany.");
    } catch (err) {
      console.error("Błąd sieci przy pobieraniu YAML:", err);
      setStatus("Błąd sieci przy pobieraniu YAML.");
    }
  };

  // Dane wejściowe do modelu (JSON z /nlp-input)
  const handleDownloadNlpInput = async () => {
    if (!uploadResult) return;

    setStatus("Pobieram dane wejściowe do modelu…");

    try {
      const url = `/api/projects/${uploadResult.id}/docs/nlp-input?level=${level}`;
      const res = await fetch(url);

      if (!res.ok) {
        const text = await res.text().catch(() => "");
        console.error(
          "Błąd pobierania NLP input:",
          res.status,
          res.statusText,
          text
        );
        setStatus("Błąd pobierania danych wejściowych.");
        return;
      }

      const data = await res.json();
      const blob = new Blob([JSON.stringify(data, null, 2)], {
        type: "application/json",
      });
      const link = document.createElement("a");
      link.href = URL.createObjectURL(blob);
      link.download = `${projectLabel}_${level}_nlp-input.json`;
      document.body.appendChild(link);
      link.click();
      document.body.removeChild(link);

      setStatus("Dane wejściowe pobrane.");
    } catch (err) {
      console.error("Błąd sieci przy pobieraniu NLP input:", err);
      setStatus("Błąd sieci przy pobieraniu danych wejściowych.");
    }
  };

  // 1) wczytanie YAML do edycji
  const handleEditPdf = async () => {
    if (!uploadResult) return;

    setStatus("Otwieram dokumentację w trybie edycji…");

    try {
      const url = `/api/projects/${uploadResult.id}/docs/editable?level=${level}`;
      const res = await fetch(url, { method: "GET" });

      if (!res.ok) {
        const text = await res.text().catch(() => "");
        console.error(
          "Błąd wczytywania YAML do edycji:",
          res.status,
          res.statusText,
          text
        );
        setStatus("Błąd wczytywania dokumentacji do edycji.");
        return;
      }

      const text = await res.text();
      setEditableYaml(text);
      setStatus("Dokumentacja otwarta w trybie edycji.");
    } catch (err) {
      console.error("Błąd sieci podczas wczytywania YAML:", err);
      setStatus("Błąd sieci podczas wczytywania dokumentacji.");
    }
  };

  // 2) wygenerowanie PDF z edytowanego YAML
  const handleDownloadEditedPdf = async () => {
    if (!uploadResult || !editableYaml) return;

    setStatus("Generuję PDF z edytowanej dokumentacji…");
    setElapsed("0.0s");
    startTimer();

    try {
      const url = `/api/projects/${uploadResult.id}/docs/edited/pdf?level=${level}`;
      const res = await fetch(url, {
        method: "POST",
        headers: {
          "Content-Type": "text/plain; charset=utf-8",
        },
        body: editableYaml,
      });

      if (!res.ok) {
        const text = await res.text().catch(() => "");
        console.error(
          "Błąd generowania edytowanego PDF:",
          res.status,
          res.statusText,
          text
        );
        setStatus("Błąd generowania edytowanego PDF.");
        return;
      }

      const blob = await res.blob();
      const link = document.createElement("a");
      link.href = URL.createObjectURL(blob);
      link.download = `${projectLabel}_${level}_edited.pdf`;
      document.body.appendChild(link);
      link.click();
      document.body.removeChild(link);

      setStatus("Edytowany PDF wygenerowany i pobrany.");
    } catch (err) {
      console.error("Błąd sieci przy generowaniu edytowanego PDF:", err);
      setStatus("Błąd sieci przy generowaniu edytowanego PDF.");
    } finally {
      stopTimer();
    }
  };

  return (
    <div
      style={{
        fontFamily:
          "system-ui, -apple-system, BlinkMacSystemFont, 'SF Pro Text', sans-serif",
        background: "#f3f4f6",
        minHeight: "100vh",
      }}
    >
      <StartBar
        onHowItWorksClick={() => setShowHowItWorks((v) => !v)}
        onHomeClick={handleHome}
        onContactClick={() => setShowContact(true)}
      />

      <main
        style={{
          maxWidth: 960,
          margin: "0 auto",
          padding: "24px 16px 40px",
        }}
      >
        {/* ekran domowy: HelloText + ProjectUploadPanel gdy brak projektu */}
        {!projectUploaded && <HelloText />}

        {!projectUploaded && (
          <section style={{ marginTop: 24 }}>
            <ProjectUploadPanel
              setStatus={setStatus}
              startTimer={startTimer}
              stopTimer={stopTimer}
              onUploadSuccess={(res) => {
                setUploadResult(res);
              }}
              onUploadError={(msg) => {
                console.error(msg);
              }}
            />
          </section>
        )}

        <StatusBar
          status={status}
          elapsed={elapsed}
          busy={busy}
          visible={busy && !isGenerating}
        />

        {projectUploaded && !isGenerating && !docsReady && (
          <LevelPanel
            level={level}
            projectLabel={projectLabel}
            onLevelChange={setLevel}
            onGenerate={handleGenerateDocs}
          />
        )}

        {projectUploaded && isGenerating && (
          <StatusGenerate
            projectName={projectLabel}
            level={level}
            elapsed={elapsed}
            progressPercent={progress}
          />
        )}

        {projectUploaded && docsReady && !isGenerating && !editableYaml && (
          <DocsActionsPanel
            projectLabel={projectLabel}
            level={level}
            onDownloadPdf={handleDownloadPdf}
            onPreviewPdf={handlePreviewPdf}
            onEditPdf={handleEditPdf}
            onDownloadYaml={handleDownloadYaml}
            onDownloadNlpInput={handleDownloadNlpInput}
          />
        )}

        {projectUploaded && editableYaml && (
          <section style={{ marginTop: 32 }}>
            <h2
              style={{
                fontSize: 18,
                fontWeight: 600,
                marginBottom: 8,
                color: "#111827",
              }}
            >
              Edytuj dokumentację przed wygenerowaniem PDF
            </h2>
            <p
              style={{
                fontSize: 13,
                color: "#6b7280",
                marginTop: 0,
                marginBottom: 12,
              }}
            >
              Zmieniasz treść opisu endpointów w YAML. Na podstawie tej wersji
              możesz pobrać nowy, edytowany PDF.
            </p>

            <EditableDocsPanel
              yaml={editableYaml}
              onYamlChange={setEditableYaml}
              isAdvanced={level === "advanced"}
            />

            <div style={{ marginTop: 16, textAlign: "right" }}>
              <button
                type="button"
                onClick={handleDownloadEditedPdf}
                style={{
                  padding: "10px 20px",
                  borderRadius: 999,
                  border: "none",
                  background: "#4f46e5",
                  color: "white",
                  fontSize: 14,
                  fontWeight: 600,
                  cursor: "pointer",
                  boxShadow: "0 10px 24px rgba(79, 70, 229, 0.35)",
                }}
              >
                Pobrać edytowany PDF
              </button>
            </div>
          </section>
        )}

        {showHowItWorks && (
          <HowItWorksPanel onBack={() => setShowHowItWorks(false)} />
        )}

        {showContact && <ContactPanel onClose={() => setShowContact(false)} />}
      </main>
    </div>
  );
};

export default App;