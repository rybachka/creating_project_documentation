import React from "react";

export const HelloText: React.FC = () => {
  return (
    <section
      style={{
        textAlign: "center",
        marginTop: 32,
        marginBottom: 24,
      }}
    >
      <h1
        style={{
          fontSize: 28,
          fontWeight: 700,
          marginBottom: 8,
          color: "#111827",
        }}
      >
        Generator dokumentacji API dla Java
      </h1>
      <p
        style={{
          maxWidth: 720,
          margin: "0 auto",
          fontSize: 14,
          lineHeight: 1.5,
          color: "#4b5563",
        }}
      >
        Code2Docs AI zamienia kod Java Spring w czytelną dokumentację API w
        kilka minut. Wgraj projekt ZIP, wybierz poziom odbiorcy, a aplikacja
        wygeneruje dokument oparty na OpenAPI, gotowy jako PDF lub YAML.
        Następnie możesz w prostym formularzu edytować opisy, przykłady i
        notatki oraz pobrać dopracowaną dokumentację.
      </p>
    </section>
  );
};

export default HelloText;
