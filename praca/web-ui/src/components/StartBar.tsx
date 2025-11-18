// src/components/StartBar.tsx
import React from "react";

const navBtn: React.CSSProperties = {
  background: "none",
  border: "none",
  padding: 0,
  cursor: "pointer",
  font: "inherit",
  color: "#111827",
};

export const StartBar: React.FC = () => {
  return (
    <header
      style={{
        position: "sticky",
        top: 0,
        zIndex: 10,
        background: "#f7f7fb",
        borderBottom: "1px solid #e2e4f0",
      }}
    >
      <div
        style={{
          maxWidth: 1200,
          margin: "0 auto",
          padding: "18px 24px",
          display: "flex",
          alignItems: "center",
          justifyContent: "space-between",
        }}
      >
        {/* logo */}
        <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
          <div
            style={{
              width: 56,
              height: 36,
              borderRadius: 8,
              background: "#4F46E5",
              color: "white",
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
              fontWeight: 800,
              fontSize: 18,
            }}
          >
            C2D
          </div>
          <span style={{ fontWeight: 600, fontSize: 18, color: "#111827" }}>
            Code2Docs AI
          </span>
        </div>

        {/* menu */}
        <nav
          style={{
            display: "flex",
            alignItems: "center",
            gap: 32,
            fontSize: 16,
            color: "#111827",
          }}
        >
          <button style={navBtn}>Funkcje</button>
          <button style={navBtn}>Jak to działa</button>
          <button style={navBtn}>Kontakt</button>
        </nav>
      </div>
    </header>
  );
};

export default StartBar;
