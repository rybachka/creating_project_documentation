// src/components/DocsActionsPanel.tsx
import React from "react";
import type { Level } from "./LevelPanel";

interface DocsActionsPanelProps {
  projectLabel: string;
  level: Level;
  onDownloadPdf: () => void;
  onPreviewPdf: () => void;
  onEditPdf: () => void;
}

const levelLabel = (level: Level) =>
  level === "beginner" ? "początkujący" : "zaawansowany";

export const DocsActionsPanel: React.FC<DocsActionsPanelProps> = ({
  projectLabel,
  level,
  onDownloadPdf,
  onPreviewPdf,
  onEditPdf,
}) => {
  return (
    <section
      style={{
        marginTop: 40,
        display: "flex",
        justifyContent: "center",
      }}
    >
      <div
        style={{
          width: "100%",
          maxWidth: 620,
          padding: "28px 32px 32px",
          background: "white",
          borderRadius: 24,
          boxShadow: "0 18px 45px rgba(15, 23, 42, 0.08)",
          textAlign: "center",
        }}
      >
        <p
          style={{
            fontSize: 16,
            fontWeight: 500,
            marginBottom: 8,
            color: "#111827",
          }}
        >
          Dokumentacja wygenerowana.
        </p>
        <p
          style={{
            fontSize: 14,
            color: "#4b5563",
            marginBottom: 24,
          }}
        >
          Projekt <strong>{projectLabel}</strong>, poziom{" "}
          <strong>{levelLabel(level)}</strong>.
          <br />
          Wybierz, co chcesz zrobić z dokumentacją:
        </p>

        <div
          style={{
            display: "flex",
            flexWrap: "wrap",
            justifyContent: "center",
            gap: 16,
          }}
        >
          <button
            type="button"
            onClick={onDownloadPdf}
            style={{
              minWidth: 160,
              padding: "10px 20px",
              borderRadius: 999,
              border: "1px solid #4f46e5",
              background: "white",
              color: "#4f46e5",
              fontSize: 14,
              fontWeight: 600,
              cursor: "pointer",
            }}
          >
            Pobrać PDF
          </button>

          <button
            type="button"
            onClick={onPreviewPdf}
            style={{
              minWidth: 160,
              padding: "10px 20px",
              borderRadius: 999,
              border: "none",
              background: "#4f46e5",
              color: "white",
              fontSize: 14,
              fontWeight: 600,
              cursor: "pointer",
              boxShadow: "0 14px 30px rgba(79, 70, 229, 0.35)",
            }}
          >
            Przeglądnąć PDF
          </button>

          <button
            type="button"
            onClick={onEditPdf}
            style={{
              minWidth: 160,
              padding: "10px 20px",
              borderRadius: 999,
              border: "1px solid #e5e7eb",
              background: "#f9fafb",
              color: "#374151",
              fontSize: 14,
              fontWeight: 500,
              cursor: "pointer",
            }}
          >
            Edytować PDF
          </button>
        </div>
      </div>
    </section>
  );
};