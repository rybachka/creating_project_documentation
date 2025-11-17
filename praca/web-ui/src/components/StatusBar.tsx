// src/components/project/StatusBar.tsx
import React from "react";

interface StatusBarProps {
  status: string;
  elapsed: string;
  busy: boolean;      // true = coś trwa, false = gotowe
  visible?: boolean;  // można schować pasek
}

export const StatusBar: React.FC<StatusBarProps> = ({
  status,
  elapsed,
  busy,
  visible = true,
}) => {
  if (!visible) return null;

  const fillWidth = busy ? "80%" : "100%";

  return (
    <div style={{ marginTop: 16 }}>
      <div
        style={{
          fontSize: 13,
          color: "#4b5563",
          marginBottom: 4,
          display: "flex",
          justifyContent: "space-between",
        }}
      >
        <span>
          <strong>Status:</strong> {status}
        </span>
        <span>{elapsed}</span>
      </div>
      <div
        style={{
          height: 6,
          borderRadius: 999,
          background: "#e5e7eb",
          overflow: "hidden",
        }}
      >
        <div
          style={{
            width: fillWidth,
            height: "100%",
            background: "#3b82f6",
            transition: "width 0.3s ease",
          }}
        />
      </div>
    </div>
  );
};

export default StatusBar;
