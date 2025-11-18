// src/components/ProjectUploadPanel.tsx
import React from "react";

export interface UploadResult {
  id: string;
  status: string;
  message?: string;
  detectedSpec?: string;
  fileName?: string;
}

export interface ProjectUploadPanelProps {
  setStatus: (s: string) => void;
  startTimer: () => void;
  stopTimer: () => void;
  onUploadSuccess: (res: UploadResult) => void;
  onUploadError?: (msg: string) => void;
}

export const ProjectUploadPanel: React.FC<ProjectUploadPanelProps> = ({
  setStatus,
  startTimer,
  stopTimer,
  onUploadSuccess,
  onUploadError,
}) => {
  const [busy, setBusy] = React.useState(false);
  const [fileName, setFileName] = React.useState<string | null>(null);
  const inputRef = React.useRef<HTMLInputElement | null>(null);

  const handleCardClick = () => {
    if (busy) return;
    inputRef.current?.click();
  };

  const handleFileChange = async (
    e: React.ChangeEvent<HTMLInputElement>
  ) => {
    const file = e.target.files?.[0];
    if (!file) return;

    setFileName(file.name);
    setBusy(true);
    setStatus("Wysyłam projekt ZIP…");
    startTimer();

    const fd = new FormData();
    fd.append("file", file);

    try {
      const res = await fetch("/api/projects/upload", {
        method: "POST",
        body: fd,
      });

      if (!res.ok) {
        const text = await res.text().catch(() => "");
        const msg = `Błąd wgrywania projektu: ${res.status} ${res.statusText}`;
        console.error(msg, text);
        setStatus("Błąd wgrywania projektu.");
        onUploadError?.(msg);
        return;
      }

      const json = (await res.json()) as UploadResult;

      setStatus(
        "Projekt wgrany. Możesz wygenerować dokumentację z kodu."
      );
      onUploadSuccess({...json, fileName: file.name});
    } catch (err) {
      const msg = `Błąd sieci podczas wgrywania projektu: ${String(err)}`;
      console.error(msg);
      setStatus("Błąd sieci podczas wgrywania projektu.");
      onUploadError?.(msg);
    } finally {
      stopTimer();
      setBusy(false);
    }
  };

  return (
    <div
      style={{
        display: "flex",
        justifyContent: "center",
        marginTop: 24,
      }}
    >
      {/* ukryty input */}
      <input
        type="file"
        accept=".zip"
        ref={inputRef}
        style={{ display: "none" }}
        onChange={handleFileChange}
      />

      {/* niebieska karta */}
      <button
        type="button"
        onClick={handleCardClick}
        disabled={busy}
        style={{
          width: 360,
          padding: "32px 24px",
          borderRadius: 16,
          border: "none",
          cursor: busy ? "default" : "pointer",
          background: "#4F46E5",
          color: "white",
          boxShadow: "0 18px 45px rgba(37, 99, 235, 0.45)",
          display: "flex",
          flexDirection: "column",
          alignItems: "center",
          gap: 12,
        }}
      >
        <div
          style={{
            width: 52,
            height: 52,
            borderRadius: "999px",
            background: "rgba(255,255,255,0.15)",
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
            fontSize: 28,
            fontWeight: 700,
          }}
        >
          +
        </div>
        <div style={{ fontSize: 16, fontWeight: 600 }}>
          {fileName ? "Zmień projekt (ZIP)" : "Wybierz projekt (ZIP)"}
        </div>
        <div
          style={{
            fontSize: 12,
            opacity: 0.9,
          }}
        >
        </div>
      </button>
    </div>
  );
};
