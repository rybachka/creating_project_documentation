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

const App: React.FC = () => {
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
        return prev + 1;           // +2% co 200 ms
      });
    }, 200);
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
  //  GENEROWANIE PDF PO „TAK”
  // ==============================
  const handleGenerateDocs = async () => {
    if (!uploadResult) return;

    setDocsReady(false);
    setPdfUrl(null);
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

      // sprzątanie starego URL-a, jeśli był
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
    // prosta nazwa pliku po stronie frontu
    link.download = `${projectLabel}_${level}.pdf`;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
  };

  const handlePreviewPdf = () => {
    if (!pdfUrl) return;
    window.open(pdfUrl, "_blank");
  };

  const handleEditPdf = () => {
    // tutaj później podepniemy widok edycji (EditableDocsPanel / YAML)
    console.log("TODO: tryb edycji PDF / YAML dla projektu", projectLabel);
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
      <StartBar />

      <main
        style={{
          maxWidth: 960,
          margin: "0 auto",
          padding: "24px 16px 40px",
        }}
      >
        {/* Intro tylko, gdy jeszcze nic nie wgrano */}
        {!projectUploaded && <HelloText />}

        {/* 1. Upload projektu – dopóki projekt NIE jest wgrany */}
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

        {/* Pasek statusu – tylko dla uploadu, NIE podczas generowania */}
        <StatusBar
          status={status}
          elapsed={elapsed}
          busy={busy}
          visible={busy && !isGenerating}
        />

        {/* 2. Po wgraniu projektu: panel wyboru poziomu + przycisk „Generuj” */}
        {projectUploaded && !isGenerating && !docsReady && (
          <LevelPanel
            level={level}
            projectLabel={projectLabel}
            onLevelChange={setLevel}
            onGenerate={handleGenerateDocs} // wywoływane po „Tak”
          />
        )}

        {/* 3. Podczas generowania – karta StatusGenerate */}
        {projectUploaded && isGenerating && (
          <StatusGenerate
            projectName={projectLabel}
            level={level}
            elapsed={elapsed}
            progressPercent={progress}
          />
        )}

        {/* 4. Po zakończeniu generowania – panel z akcjami PDF */}
        {projectUploaded && docsReady && !isGenerating && (
          <DocsActionsPanel
            projectLabel={projectLabel}
            level={level}
            onDownloadPdf={handleDownloadPdf}
            onPreviewPdf={handlePreviewPdf}
            onEditPdf={handleEditPdf}
          />
        )}
      </main>
    </div>
  );
};

export default App;
