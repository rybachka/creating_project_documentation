// src/components/StatusGenerate.tsx
import React from "react";
import type { Level } from "./LevelPanel";

interface StatusGenerateProps {
  projectName: string;
  level: Level;
  elapsed: string;
  progressPercent: number; // 0–100
}

export const StatusGenerate: React.FC<StatusGenerateProps> = ({
  projectName,
  level,
  elapsed,
  progressPercent,
}) => {
  // lekkie „bezpieczeństwo” – minimum 5%, maksimum 100%
  const safeProgress = Math.max(5, Math.min(100, progressPercent));
  const levelLabel = level === "beginner" ? "Początkujący" : "Zaawansowany";

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
        {/* nagłówek */}
        <div
          style={{
            display: "inline-flex",
            alignItems: "center",
            justifyContent: "center",
            padding: "10px 26px",
            borderRadius: 999,
            border: "2px solid #4f46e5",
            color: "#3730a3",
            fontSize: 15,
            fontWeight: 500,
            marginBottom: 20,
          }}
        >
          Trwa generowanie dokumentacji…
        </div>

        {/* czas */}
        <div
          style={{
            fontSize: 14,
            color: "#4b5563",
            marginBottom: 10,
          }}
        >
          {elapsed}
        </div>

        {/* pasek postępu */}
        <div
          style={{
            height: 10,
            borderRadius: 999,
            background: "#e5e7eb",
            overflow: "hidden",
            margin: "0 auto 20px",
            maxWidth: 360,
          }}
        >
          <div
            style={{
              width: `${safeProgress}%`,
              height: "100%",
              background: "#4f46e5",
              boxShadow: "0 0 18px rgba(79, 70, 229, 0.7)",
              transition: "width 0.3s ease",
            }}
          />
        </div>

        {/* info o projekcie */}
        <div
          style={{
            fontSize: 14,
            color: "#374151",
            lineHeight: 1.5,
          }}
        >
          <div>
            <strong>Projekt:</strong> {projectName}
          </div>
          <div>
            <strong>Poziom:</strong> {levelLabel}
          </div>
        </div>
      </div>
    </section>
  );
};
