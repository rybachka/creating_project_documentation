import React from "react";
import type { Level } from "./LevelPanel";

interface DocsActionsPanelProps {
  projectLabel: string;
  level: Level;
  onDownloadPdf: () => void;
  onPreviewPdf: () => void;
  onEditPdf: () => void;
  // nowe, zaawansowane funkcje
  onDownloadYaml?: () => void;
  onDownloadNlpInput?: () => void;
}

export const DocsActionsPanel: React.FC<DocsActionsPanelProps> = ({
  projectLabel,
  level,
  onDownloadPdf,
  onPreviewPdf,
  onEditPdf,
  onDownloadYaml,
  onDownloadNlpInput,
}) => {
  const levelLabel = level === "beginner" ? "początkujący" : "zaawansowany";

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
          maxWidth: 720,
          padding: "32px 40px 32px",
          background: "white",
          borderRadius: 32,
          boxShadow: "0 24px 60px rgba(15, 23, 42, 0.12)",
          textAlign: "center",
        }}
      >
        <h2
          style={{
            fontSize: 18,
            fontWeight: 600,
            margin: "0 0 8px",
            color: "#111827",
          }}
        >
          Dokumentacja wygenerowana.
        </h2>
        <p
          style={{
            fontSize: 14,
            color: "#4b5563",
            margin: 0,
            lineHeight: 1.6,
          }}
        >
          Projekt <strong>{projectLabel}</strong>, poziom{" "}
          <strong>{levelLabel}</strong>.
          <br />
          Wybierz, co chcesz zrobić z dokumentacją:
        </p>

        {/* główne akcje PDF */}
        <div
          style={{
            display: "flex",
            justifyContent: "center",
            gap: 16,
            marginTop: 24,
          }}
        >
          <button
            type="button"
            onClick={onDownloadPdf}
            style={{
              minWidth: 180,
              padding: "12px 24px",
              borderRadius: 999,
              border: "none",
              background: "#4f46e5",
              color: "white",
              fontSize: 14,
              fontWeight: 600,
              cursor: "pointer",
              boxShadow: "0 16px 40px rgba(79, 70, 229, 0.45)",
            }}
          >
            Pobrać PDF
          </button>

          <button
            type="button"
            onClick={onPreviewPdf}
            style={{
              minWidth: 180,
              padding: "12px 24px",
              borderRadius: 999,
              border: "none",
              background: "#4f46e5",
              color: "white",
              fontSize: 14,
              fontWeight: 600,
              cursor: "pointer",
              boxShadow: "0 16px 40px rgba(79, 70, 229, 0.45)",
            }}
          >
            Przeglądnąć PDF
          </button>

          <button
            type="button"
            onClick={onEditPdf}
            style={{
              minWidth: 180,
              padding: "12px 24px",
              borderRadius: 999,
              border: "1px solid #4f46e5",
              background: "white",
              color: "#4f46e5",
              fontSize: 14,
              fontWeight: 600,
              cursor: "pointer",
            }}
          >
            Edytować PDF
          </button>
        </div>

        {/* separator */}
        {(onDownloadYaml || onDownloadNlpInput) && (
          <div
            style={{
              height: 1,
              background: "#e5e7eb",
              margin: "24px auto 16px",
              maxWidth: 520,
            }}
          />
        )}

        {/* Funkcje zaawansowane */}
        {(onDownloadYaml || onDownloadNlpInput) && (
          <div
            style={{
              textAlign: "left",
              maxWidth: 520,
              margin: "0 auto",
            }}
          >
            <div
              style={{
                fontSize: 13,
                fontWeight: 600,
                color: "#111827",
                marginBottom: 8,
              }}
            >
              Funkcje zaawansowane
            </div>

            <div
              style={{
                display: "flex",
                flexWrap: "wrap",
                gap: 12,
              }}
            >

                {onDownloadNlpInput && (
                <button
                  type="button"
                  onClick={onDownloadNlpInput}
                  style={{
                    padding: "8px 14px",
                    borderRadius: 999,
                    border: "1px solid #e5e7eb",
                    background: "#f9fafb",
                    fontSize: 13,
                    fontWeight: 500,
                    color: "#111827",
                    cursor: "pointer",
                  }}
                >
                  Pobrać dane wejściowe do modelu
                </button>
              )}
              {onDownloadYaml && (
                <button
                  type="button"
                  onClick={onDownloadYaml}
                  style={{
                    padding: "8px 14px",
                    borderRadius: 999,
                    border: "1px solid #e5e7eb",
                    background: "#f9fafb",
                    fontSize: 13,
                    fontWeight: 500,
                    color: "#111827",
                    cursor: "pointer",
                  }}
                >
                  Pobrać wygenerowany YAML
                </button>
              )}


            </div>
          </div>
        )}
      </div>
    </section>
  );
};

export default DocsActionsPanel;