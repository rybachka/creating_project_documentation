// src/components/HowItWorksPanel.tsx
import React from "react";

interface HowItWorksPanelProps {
  onBack?: () => void;
}

interface Section {
  id: number;
  title: string;
  body: React.ReactNode;
}

const sections: Section[] = [
  {
    id: 1,
    title: "C2D – co to za aplikacja?",
    body: (
      <>
        <p>
          C2D (Code2Docs AI) zamienia projekt Java Spring w czytelną
          dokumentację API. Na podstawie kodu generuje specyfikację OpenAPI
          (YAML) oraz gotowy plik PDF z opisem endpointów.
        </p>
      </>
    ),
  },
  {
    id: 2,
    title: "Jak przygotować projekt?",
    body: (
      <ul>
        <li>Projekt powinien być aplikacją Java Spring (Spring Boot / MVC).</li>
        <li>
          Spakuj go do pliku <b>ZIP</b> z katalogu głównego (tam, gdzie jest{" "}
          <code>pom.xml</code> / <code>build.gradle</code>).
        </li>
        <li>W ZIP-ie powinien być katalog z kodem: <code>src/main/java</code>.</li>
      </ul>
    ),
  },
  {
    id: 3,
    title: "Wgranie projektu",
    body: (
      <ol>
        <li>Na stronie głównej wybierz plik ZIP z projektem.</li>
        <li>
          Aplikacja wyśle projekt na serwer i pokaże pasek postępu oraz status
          wgrywania.
        </li>
        <li>
          Po poprawnym wgraniu zobaczysz komunikat z nazwą projektu
          (np. <code>sample-java-project.zip</code>).
        </li>
      </ol>
    ),
  },
  {
    id: 4,
    title: "Poziom zaawansowania dokumentacji",
    body: (
      <>
        <p>
          Po wgraniu projektu wybierasz poziom, w jakim ma zostać wygenerowana
          dokumentacja:
        </p>
        <ul>
          <li>
            <b>Początkujący</b> – prostszy język, mniej technicznych szczegółów,
            idealny dla juniorów, QA i osób biznesowych.
          </li>
          <li>
            <b>Zaawansowany</b> – bardziej techniczne opisy i dodatkowe notatki
            dla developerów i architektów.
          </li>
        </ul>
      </>
    ),
  },
  {
    id: 5,
    title: "Generowanie dokumentacji",
    body: (
      <>
        <ol>
          <li>Po wyborze poziomu klikasz „Generuj dokumentację”.</li>
          <li>
            Aplikacja prosi o potwierdzenie poziomu („Tak / Nie”), a następnie
            rozpoczyna generowanie.
          </li>
          <li>
            Widzisz kartę „Trwa generowanie dokumentacji…”, licznik czasu i
            pasek postępu.
          </li>
        </ol>
        <p>
          W tle C2D analizuje kod, wykrywa endpointy, tworzy OpenAPI (YAML) i
          na jego podstawie renderuje PDF.
        </p>
      </>
    ),
  },
  {
    id: 6,
    title: "Gotowa dokumentacja – co możesz zrobić?",
    body: (
      <ul>
        <li>
          <b>Pobrać PDF</b> – zapisujesz dokumentację na dysku.
        </li>
        <li>
          <b>Przeglądnąć PDF</b> – otwierasz dokument w nowej karcie
          przeglądarki.
        </li>
        <li>
          <b>Edytować PDF</b> – przechodzisz do trybu edycji treści (opisów
          endpointów) przed wygenerowaniem wersji po poprawkach.
        </li>
      </ul>
    ),
  },
  {
    id: 7,
    title: "Tryb edycji dokumentu",
    body: (
      <>
        <p>
          W trybie edycji pracujesz na treści dokumentacji (YAML), bez
          dotykania kodu.
        </p>
        <ul>
          <li>
            Po lewej stronie widzisz listę endpointów
            (np. <code>GET /users</code>, <code>POST /users/{`{id}`}</code>).
          </li>
          <li>
            Po prawej edytujesz <b>summary</b>, <b>description</b>,
            opis/ przykład odpowiedzi 200 oraz (dla advanced) notatki techniczne.
          </li>
        </ul>
      </>
    ),
  },
];

export const HowItWorksPanel: React.FC<HowItWorksPanelProps> = ({ onBack }) => {
  const [openId, setOpenId] = React.useState<number | null>(0);

  return (
    <section
      style={{
        marginTop: 32,
        marginBottom: 40,
      }}
    >
      <div
        style={{
          maxWidth: 960,
          margin: "0 auto",
        }}
      >
        {onBack && (
          <button
            type="button"
            onClick={onBack}
            style={{
              marginBottom: 16,
              padding: "8px 14px",
              borderRadius: 999,
              border: "1px solid #e5e7eb",
              background: "white",
              fontSize: 16,
              cursor: "pointer",
            }}
          >
             ⤬
          </button>
        )}

        <div
          style={{
            background: "white",
            borderRadius: 24,
            boxShadow: "0 18px 45px rgba(15,23,42,0.08)",
            padding: "24px 16px 24px",
          }}
        >
          {sections.map((section) => {
            const isOpen = openId === section.id;
            return (
              <div key={section.id} style={{ marginBottom: 8 }}>
                <button
                  type="button"
                  onClick={() =>
                    setOpenId(isOpen ? null : section.id)
                  }
                  style={{
                    width: "100%",
                    padding: "10px 14px",
                    borderRadius: 999,
                    border: "2px solid #4f46e5",
                    background: "white",
                    display: "flex",
                    alignItems: "center",
                    justifyContent: "space-between",
                    fontSize: 14,
                    cursor: "pointer",
                  }}
                >
                  <span>
                    {section.id}. {section.title}
                  </span>
                  <span
                    style={{
                      transform: isOpen ? "rotate(180deg)" : "rotate(0deg)",
                      transition: "transform 0.15s ease",
                    }}
                  >
                    ˅
                  </span>
                </button>

                {isOpen && (
                  <div
                    style={{
                      marginTop: 8,
                      marginBottom: 12,
                      padding: "12px 16px",
                      borderRadius: 18,
                      background: "#f9fafb",
                      fontSize: 13,
                      lineHeight: 1.6,
                    }}
                  >
                    {section.body}
                  </div>
                )}
              </div>
            );
          })}
        </div>
      </div>
    </section>
  );
};