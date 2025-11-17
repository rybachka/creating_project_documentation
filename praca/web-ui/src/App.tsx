// src/App.tsx
import React, { useRef, useState } from "react";
import { StartBar } from "./components/StartBar";
import { HelloText } from "./components/HelloText";
import {
  ProjectUploadPanel,
  UploadResult,
} from "./components/ProjectUploadPanel";
import { StatusBar } from "./components/StatusBar";

const App: React.FC = () => {
  const [status, setStatus] = useState<string>("Gotowa.");
  const [elapsed, setElapsed] = useState<string>("0.0s");
  const [busy, setBusy] = useState(false);
  const [uploadResult, setUploadResult] = useState<UploadResult | null>(
    null
  );

  const tickRef = useRef<number | null>(null);
  const t0Ref = useRef<number>(0);

  const startTimer = () => {
    t0Ref.current = performance.now();
    setBusy(true);

    if (tickRef.current) {
      clearInterval(tickRef.current);
    }

    tickRef.current = window.setInterval(() => {
      const s =
        ((performance.now() - t0Ref.current) / 1000).toFixed(1) + "s";
      setElapsed(s);
    }, 100);
  };

  const stopTimer = () => {
    if (tickRef.current) {
      clearInterval(tickRef.current);
      tickRef.current = null;
    }
    setBusy(false);
  };

  return (
    <div
      style={{
        fontFamily:
          "system-ui, -apple-system, BlinkMacSystemFont, 'SF Pro Text', sans-serif",
        background: "#f3f4f6",
        minHeight: "100vh",
      }}
    >
      <StartBar />

      <main
        style={{
          maxWidth: 960,
          margin: "0 auto",
          padding: "24px 16px 40px",
        }}
      >
        <HelloText />


        {/* Panel uploadu – pokazujemy dopóki nie ma poprawnie wgranego projektu */}
        {!uploadResult && (
          <section style={{ marginTop: 24 }}>
            <ProjectUploadPanel
              setStatus={setStatus}
              startTimer={startTimer}
              stopTimer={stopTimer}
              onUploadSuccess={(res) => {
                setUploadResult(res);
              }}
              onUploadError={(msg) => {
                console.error(msg);
              }}
            />
          </section>
        )}

        {/* Pasek statusu */}
        <StatusBar
          status={status}
          elapsed={elapsed}
          busy={busy}
          visible={busy || !!uploadResult}
        />
        {/* Komunikat po sukcesie */}
        {uploadResult && (
          <section style={{ marginTop: 24 }}>
            <p
              style={{
                fontSize: 14,
                color: "#374151",
                margin: 0,
              }}
            >
              Projekt <strong>{uploadResult.id}</strong> został poprawnie
              wgrany. Możesz teraz wygenerować dokumentację z kodu (PDF /
              YAML) za pomocą backendu.
            </p>
          </section>
        )}
      </main>
    </div>
  );
};

export default App;
