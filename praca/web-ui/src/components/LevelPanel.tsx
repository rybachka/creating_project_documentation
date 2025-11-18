import React from "react";

export type Level = "beginner" | "advanced";

interface LevelPanelProps {
  level: Level;
  projectLabel: string;              // nazwa ZIP-a / projektu
  onLevelChange: (level: Level) => void;
  onGenerate: () => void;            // wywoływane dopiero po "Tak"
}

export const LevelPanel: React.FC<LevelPanelProps> = ({
  level,
  projectLabel,
  onLevelChange,
  onGenerate,
}) => {
  const [showConfirm, setShowConfirm] = React.useState(false);

  const handleClickGenerate = () => {
    setShowConfirm(true);
  };

  const handleConfirm = () => {
    setShowConfirm(false);
    onGenerate();
  };

  const handleCancel = () => {
    setShowConfirm(false);
  };

  const levelLabel = level === "beginner" ? "Początkujący" : "Zaawansowany";

  return (
    <>
      {/* główny panel z wyborem poziomu */}
      <section
        style={{
          marginTop: 40,
          textAlign: "center",
        }}
      >
        <p
          style={{
            fontSize: 15,
            lineHeight: 1.5,
            color: "#374151",
            marginBottom: 24,
          }}
        >
          Projekt <strong>{projectLabel}</strong> wgrany poprawnie.
          <br />
          Wybierz poziom zaawansowania i wygeneruj dokumentację z kodu:
        </p>

        <div
          style={{
            maxWidth: 560,
            margin: "0 auto",
            padding: "24px 32px 28px",
            background: "white",
            borderRadius: 24,
            boxShadow: "0 18px 45px rgba(15, 23, 42, 0.08)",
          }}
        >
          <div
            style={{
              fontSize: 14,
              marginBottom: 12,
              color: "#111827",
            }}
          >
            Poziom:
          </div>

          <div
            style={{
              display: "flex",
              justifyContent: "center",
              gap: 16,
              marginBottom: 20,
            }}
          >
            <button
              type="button"
              onClick={() => onLevelChange("beginner")}
              style={{
                minWidth: 140,
                padding: "10px 18px",
                borderRadius: 999,
                border:
                  level === "beginner"
                    ? "1px solid #4f46e5"
                    : "1px solid #e5e7eb",
                background:
                  level === "beginner" ? "#eef2ff" : "#f9fafb",
                color: level === "beginner" ? "#111827" : "#4b5563",
                fontSize: 14,
                fontWeight: 500,
                cursor: "pointer",
              }}
            >
              Początkujący
            </button>

            <button
              type="button"
              onClick={() => onLevelChange("advanced")}
              style={{
                minWidth: 140,
                padding: "10px 18px",
                borderRadius: 999,
                border:
                  level === "advanced"
                    ? "1px solid #4f46e5"
                    : "1px solid #e5e7eb",
                background:
                  level === "advanced" ? "#eef2ff" : "#f9fafb",
                color: level === "advanced" ? "#111827" : "#4b5563",
                fontSize: 14,
                fontWeight: 500,
                cursor: "pointer",
              }}
            >
              Zaawansowany
            </button>
          </div>

          <button
            type="button"
            onClick={handleClickGenerate}
            style={{
              marginTop: 4,
              minWidth: 260,
              padding: "12px 24px",
              borderRadius: 999,
              border: "none",
              background: "#4f46e5",
              color: "white",
              fontSize: 15,
              fontWeight: 600,
              cursor: "pointer",
              boxShadow: "0 14px 30px rgba(79, 70, 229, 0.35)",
            }}
          >
            Generuj dokumentację
          </button>
        </div>
      </section>

      {/* okno potwierdzenia */}
      {showConfirm && (
        <div
          style={{
            position: "fixed",
            inset: 0,
            background: "rgba(15,23,42,0.14)",
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
            zIndex: 40,
          }}
        >
          <div
            style={{
              width: 460,
              maxWidth: "90%",
              background: "white",
              borderRadius: 24,
              boxShadow: "0 20px 60px rgba(15, 23, 42, 0.35)",
              padding: "28px 32px 24px",
              textAlign: "center",
            }}
          >
            <p
              style={{
                margin: 0,
                marginBottom: 6,
                fontSize: 16,
                color: "#111827",
              }}
            >
              Wybrany poziom:{" "}
              <strong>{levelLabel}</strong>
            </p>
            <p
              style={{
                margin: 0,
                marginBottom: 24,
                fontSize: 14,
                color: "#4b5563",
              }}
            >
              Potwierdzasz?
            </p>

            <div
              style={{
                display: "flex",
                justifyContent: "center",
                gap: 16,
              }}
            >
              <button
                type="button"
                onClick={handleConfirm}
                style={{
                  minWidth: 120,
                  padding: "10px 18px",
                  borderRadius: 12,
                  border: "none",
                  background: "#dcfce7",
                  color: "#166534",
                  fontSize: 14,
                  fontWeight: 600,
                  cursor: "pointer",
                }}
              >
                Tak
              </button>

              <button
                type="button"
                onClick={handleCancel}
                style={{
                  minWidth: 120,
                  padding: "10px 18px",
                  borderRadius: 12,
                  border: "none",
                  background: "#fee2e2",
                  color: "#b91c1c",
                  fontSize: 14,
                  fontWeight: 600,
                  cursor: "pointer",
                }}
              >
                Nie
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
};
