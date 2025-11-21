import React from "react";

const navBtn: React.CSSProperties = {
  background: "none",
  border: "none",
  padding: 0,
  cursor: "pointer",
  font: "inherit",
  color: "#111827",
};

interface StartBarProps {
  onHowItWorksClick: () => void;
  onHomeClick: () => void;
  onContactClick?: () => void; // opcjonalnie, jeśli chcesz ContactPanel
}

export const StartBar: React.FC<StartBarProps> = ({
  onHowItWorksClick,
  onHomeClick,
  onContactClick,
}) => {
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
        {/* logo – kliknięcie wraca do generatora */}
        <button
          type="button"
          onClick={onHomeClick}
          style={{
            display: "flex",
            alignItems: "center",
            gap: 8,
            background: "none",
            border: "none",
            padding: 0,
            cursor: "pointer",
          }}
        >
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
        </button>

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
          {/* Jak to działa = HowItWorksPanel */}
          <button style={navBtn} onClick={onHowItWorksClick}>
            Jak to działa
          </button>

          {/* Kontakt – jeśli podasz onContactClick w App */}
          <button
            style={navBtn}
            onClick={onContactClick}
            disabled={!onContactClick}
          >
            Kontakt
          </button>
        </nav>
      </div>
    </header>
  );
};

export default StartBar;