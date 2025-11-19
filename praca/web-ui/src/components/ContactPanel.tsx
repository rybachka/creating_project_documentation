// src/components/ContactPanel.tsx
import React from "react";

interface ContactPanelProps {
  onClose: () => void;
}

export const ContactPanel: React.FC<ContactPanelProps> = ({ onClose }) => {
  return (
    <div
      style={{
        position: "fixed",
        top: 90,            // tu możesz delikatnie korygować położenie
        right: 32,
        zIndex: 30,
      }}
    >
      <div
        style={{
          position: "relative",
          maxWidth: 360,
          background: "#ffffff",
          borderRadius: 16,
          border: "2px solid #4F46E5",
          boxShadow: "0 18px 45px rgba(15, 23, 42, 0.18)",
          padding: "14px 18px 16px",
          fontSize: 14,
          color: "#111827",
        }}
      >
        {/* przycisk X */}
        <button
          type="button"
          onClick={onClose}
          aria-label="Zamknij panel kontaktu"
          style={{
            position: "absolute",
            top: -18,
            right: -18,
            width: 32,
            height: 32,
            borderRadius: 999,
            border: "2px solid #4F46E5",
            background: "#ffffff",
            color: "#111827",
            fontSize: 14,
            fontWeight: 600,
            cursor: "pointer",
            boxShadow: "0 10px 25px rgba(15, 23, 42, 0.25)",
          }}
        >
          X
        </button>

        <div
          style={{
            fontWeight: 600,
            marginBottom: 6,
          }}
        >
          Kontakt:
        </div>

        <p
          style={{
            margin: 0,
            marginBottom: 8,
            lineHeight: 1.5,
          }}
        >
          Masz pytania o Code2Docs AI, chcesz zgłosić błąd albo porozmawiać
          o współpracy? Napisz do nas – odpowiemy tak szybko, jak to możliwe.
        </p>

        <p style={{ margin: 0, lineHeight: 1.5 }}>
          <strong>E-mail:</strong>{" "}
          <a
            href="mailto:173700@stud.prz.edu.pl"
            style={{ color: "#4F46E5", textDecoration: "none" }}
          >
            173700@stud.prz.edu.pl
          </a>
          <br />
          <strong>LinkedIn:</strong>{" "}
          <a
            href="https://www.linkedin.com/in/rybachka"
            target="_blank"
            rel="noreferrer"
            style={{ color: "#4F46E5", textDecoration: "none" }}
          >
            linkedin.com/in/rybachka
          </a>
        </p>
      </div>
    </div>
  );
};

export default ContactPanel;