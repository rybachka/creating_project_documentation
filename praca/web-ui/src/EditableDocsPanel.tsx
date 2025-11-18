// src/EditableDocsPanel.tsx
import React from "react";
import yaml from "js-yaml";

interface EditableDocsPanelProps {
  yaml: string;
  onYamlChange: (yaml: string) => void;
  isAdvanced: boolean;
}

type Method = "get" | "post" | "put" | "delete" | "patch" | "head" | "options";

interface EndpointInfo {
  key: string;
  method: Method;
  path: string;
  summary?: string;
  description?: string;
}

interface EndpointFields {
  summary: string;
  description: string;
  response200Description: string;
  response200Example: string;
  notesText: string; // każda linia = osobna notatka
}

const HTTP_METHODS: Method[] = [
  "get",
  "post",
  "put",
  "delete",
  "patch",
  "head",
  "options",
];

const isObject = (v: unknown): v is Record<string, any> =>
  typeof v === "object" && v !== null;

const toYaml = (obj: any) =>
  yaml.dump(obj, {
    lineWidth: 120,
    noRefs: true,
  });

const parseYamlSafe = (text: string): any => {
  try {
    const doc = yaml.load(text);
    return doc ?? {};
  } catch (e) {
    console.error("Błąd parsowania YAML:", e);
    return {};
  }
};

const pathOperationKey = (method: Method, path: string) =>
  `${method.toUpperCase()} ${path}`;

const initialNotesPlaceholder =
  "Każda linia to osobna notatka, np.\n- Wersja BETA\n- Trzeba dodać paginację\n- Uwaga: endpoint zwraca 202 przy dużym obciążeniu";

const EditableDocsPanel: React.FC<EditableDocsPanelProps> = ({
  yaml: yamlText,
  onYamlChange,
  isAdvanced,
}) => {
  const [doc, setDoc] = React.useState<any>(() => parseYamlSafe(yamlText));
  const [endpoints, setEndpoints] = React.useState<EndpointInfo[]>([]);
  const [selectedKey, setSelectedKey] = React.useState<string | null>(null);
  const [fieldsByKey, setFieldsByKey] = React.useState<
    Record<string, EndpointFields>
  >({});

  // Reparsowanie, gdy backend zwróci nowy YAML (np. po wygenerowaniu od nowa)
  React.useEffect(() => {
    const parsed = parseYamlSafe(yamlText);
    setDoc(parsed);

    const paths = (parsed && parsed.paths) || {};
    const eps: EndpointInfo[] = [];
    const newFieldsByKey: Record<string, EndpointFields> = {};

    Object.entries(paths as Record<string, any>).forEach(
      ([path, pathItem]) => {
        if (!isObject(pathItem)) return;
        HTTP_METHODS.forEach((method) => {
          const op = pathItem[method];
          if (!isObject(op)) return;

          const key = pathOperationKey(method, path);
          const summary = (op.summary as string) || "";
          const description = (op.description as string) || "";

          // Response 200
          const responses = isObject(op.responses) ? op.responses : {};
          const resp200 = isObject(responses["200"]) ? responses["200"] : {};
          const resp200Desc = (resp200.description as string) || "";

          let example = "";
          if (isObject(resp200.content)) {
            // bierzemy pierwszy content-type
            const firstCtKey = Object.keys(resp200.content)[0];
            if (firstCtKey) {
              const media = resp200.content[firstCtKey];
              if (isObject(media) && media.example != null) {
                if (
                  typeof media.example === "string" ||
                  typeof media.example === "number" ||
                  typeof media.example === "boolean"
                ) {
                  example = String(media.example);
                } else {
                  try {
                    example = JSON.stringify(media.example, null, 2);
                  } catch {
                    example = String(media.example);
                  }
                }
              }
            }
          }

          // 🔹 x-notes → textarea
          let notesText = "";
          if (Array.isArray(op["x-impl-notes"]) && op["x-impl-notes"].length > 0) {
            notesText = (op["x-impl-notes"] as any[])
              .map((n) => String(n))
              .join("\n");
          } else if (isAdvanced) {
            notesText = "";
          }

          eps.push({
            key,
            method,
            path,
            summary,
            description,
          });

          newFieldsByKey[key] = {
            summary,
            description,
            response200Description: resp200Desc,
            response200Example: example,
            notesText,
          };
        });
      }
    );

    setEndpoints(eps);

    setFieldsByKey((prev) => {
      const merged: Record<string, EndpointFields> = {};
      eps.forEach((e) => {
        const existing = prev[e.key];
        merged[e.key] = existing ?? newFieldsByKey[e.key];
      });
      return merged;
    });

    if (!selectedKey || !eps.some((e) => e.key === selectedKey)) {
      setSelectedKey(eps[0]?.key ?? null);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [yamlText, isAdvanced]);

  const selectedFields: EndpointFields | null =
    (selectedKey && fieldsByKey[selectedKey]) || null;

  const handleFieldsChange = (patch: Partial<EndpointFields>) => {
    if (!selectedKey) return;

    setFieldsByKey((prev) => {
      const current = prev[selectedKey] || {
        summary: "",
        description: "",
        response200Description: "",
        response200Example: "",
        notesText: "",
      };

      const updated: EndpointFields = { ...current, ...patch };
      const next = { ...prev, [selectedKey]: updated };

      // Od razu aktualizujemy doc → YAML
      const updatedDoc = applyFieldsToDoc(doc, selectedKey, updated, isAdvanced);
      setDoc(updatedDoc);
      const newYaml = toYaml(updatedDoc);
      onYamlChange(newYaml);

      return next;
    });
  };

  const applyFieldsToDoc = (
    oldDoc: any,
    key: string,
    fields: EndpointFields,
    advanced: boolean
  ) => {
    const cloned = JSON.parse(JSON.stringify(oldDoc || {}));
    const paths = cloned.paths || {};
    cloned.paths = paths;

    const [methodStr, path] = key.split(" ");
    const method = methodStr.toLowerCase() as Method;

    const pathItem = (paths[path] = paths[path] || {});
    const op = (pathItem[method] = pathItem[method] || {});

    op.summary = fields.summary || undefined;
    op.description = fields.description || undefined;

    // responses / 200
    op.responses = op.responses || {};
    const resp200 = (op.responses["200"] = op.responses["200"] || {});
    resp200.description = fields.response200Description || undefined;

    // przykład
    if (!resp200.content) {
      resp200.content = {
        "application/json": {
          example: {},
        },
      };
    }
    const content = resp200.content;
    const firstCtKey = Object.keys(content)[0] || "application/json";
    content[firstCtKey] = content[firstCtKey] || {};

    const trimmedExample = fields.response200Example.trim();
    if (trimmedExample) {
      try {
        content[firstCtKey].example = JSON.parse(trimmedExample);
      } catch {
        content[firstCtKey].example = trimmedExample;
      }
    } else {
      delete content[firstCtKey].example;
    }

    // 🔹 zapisz x-notes tylko dla advanced
    if (advanced) {
      const lines = fields.notesText
        .split(/\r?\n/)
        .map((l) => l.trim())
        .filter((l) => l.length > 0);

      if (lines.length > 0) {
        op["x-impl-notes"] = lines;
      } else {
        delete op["x-impl-notes"];
      }
    }

    return cloned;
  };

  if (!selectedKey || !selectedFields) {
    return (
      <div
        style={{
          marginTop: 24,
          display: "flex",
          justifyContent: "center",
        }}
      >
        <div
          style={{
            maxWidth: 720,
            width: "100%",
            background: "white",
            borderRadius: 24,
            padding: "20px 24px",
            border: "1px solid #e5e7eb",
            boxShadow: "0 18px 45px rgba(15, 23, 42, 0.06)",
            fontSize: 14,
            color: "#4b5563",
            textAlign: "center",
          }}
        >
          Brak endpointów w dokumencie YAML.
          <br />
          Upewnij się, że wygenerowana dokumentacja zawiera sekcję{" "}
          <code>paths</code>.
        </div>
      </div>
    );
  }

  // ❤️ sam wygląd listy i formularza – w stylu obecnych kart
  return (
    <div
      style={{
        marginTop: 24,
        display: "flex",
        justifyContent: "center",
      }}
    >
      <div
        style={{
          width: "100%",
          maxWidth: 960,
          background: "white",
          borderRadius: 24,
          border: "1px solid #e5e7eb",
          boxShadow: "0 18px 45px rgba(15, 23, 42, 0.08)",
          padding: "24px 24px 28px",
        }}
      >
        {/* nagłówek panelu */}
        <div
          style={{
            display: "flex",
            justifyContent: "space-between",
            alignItems: "baseline",
            marginBottom: 16,
            gap: 12,
          }}
        >
          <div>
            <h3
              style={{
                margin: 0,
                fontSize: 18,
                fontWeight: 600,
                color: "#111827",
              }}
            >
              Edycja treści endpointów
            </h3>
            <p
              style={{
                margin: "4px 0 0",
                fontSize: 12,
                color: "#6b7280",
              }}
            >
              Zmieniasz tylko pola opisowe. Definicje ścieżek, parametrów i
              schematów danych pozostają bez zmian.
            </p>
          </div>

          <div
            style={{
              padding: "4px 10px",
              borderRadius: 999,
              background: "#eef2ff",
              color: "#4f46e5",
              fontSize: 12,
              fontWeight: 500,
              whiteSpace: "nowrap",
            }}
          >
            YAML aktualizuje się na bieżąco
          </div>
        </div>

        {/* layout: lista endpointów + formularz */}
        <div
          style={{
            display: "grid",
            gridTemplateColumns: "280px 1fr",
            gap: 20,
            alignItems: "flex-start",
          }}
        >
          {/* Lista endpointów */}
          <div
            style={{
              borderRadius: 18,
              border: "1px solid #e5e7eb",
              background: "#f9fafb",
              padding: "10px 10px 12px",
              maxHeight: 520,
              overflowY: "auto",
            }}
          >
            <div
              style={{
                fontSize: 13,
                fontWeight: 600,
                marginBottom: 4,
                color: "#111827",
              }}
            >
              Endpointy
            </div>
            <p
              style={{
                margin: "0 0 8px",
                fontSize: 11,
                color: "#6b7280",
              }}
            >
              Wybierz endpoint z listy, aby edytować jego opis.
            </p>

            {endpoints.map((e) => {
              const isActive = e.key === selectedKey;

              // małe kolorowe „badge” dla metody
              const methodColor =
                e.method === "get"
                  ? "#047857"
                  : e.method === "post"
                  ? "#1d4ed8"
                  : e.method === "put"
                  ? "#92400e"
                  : e.method === "delete"
                  ? "#b91c1c"
                  : "#4b5563";

              const methodBg = methodColor + "1A"; // lekkie tło

              return (
                <button
                  key={e.key}
                  type="button"
                  onClick={() => setSelectedKey(e.key)}
                  style={{
                    display: "block",
                    width: "100%",
                    textAlign: "left",
                    padding: "8px 9px",
                    marginBottom: 4,
                    borderRadius: 10,
                    border: isActive
                      ? "1px solid #4f46e5"
                      : "1px solid transparent",
                    background: isActive ? "#eef2ff" : "transparent",
                    cursor: "pointer",
                    fontSize: 12,
                  }}
                >
                  <div
                    style={{
                      display: "flex",
                      alignItems: "center",
                      gap: 6,
                      marginBottom: 2,
                    }}
                  >
                    <span
                      style={{
                        fontSize: 10,
                        fontWeight: 700,
                        textTransform: "uppercase",
                        padding: "2px 6px",
                        borderRadius: 999,
                        background: methodBg,
                        color: methodColor,
                      }}
                    >
                      {e.method.toUpperCase()}
                    </span>
                    <span
                      style={{
                        fontFamily: "monospace",
                        fontSize: 11,
                        color: "#111827",
                      }}
                    >
                      {e.path}
                    </span>
                  </div>
                  {e.summary && (
                    <div
                      style={{
                        fontSize: 11,
                        color: "#4b5563",
                        lineHeight: 1.3,
                      }}
                    >
                      {e.summary}
                    </div>
                  )}
                </button>
              );
            })}
          </div>

          {/* Formularz edycji */}
          <div
            style={{
              borderRadius: 18,
              border: "1px solid #e5e7eb",
              padding: "14px 14px 16px",
              background: "#ffffff",
            }}
          >
            <div
              style={{
                fontSize: 13,
                fontWeight: 600,
                marginBottom: 4,
                color: "#111827",
              }}
            >
              Edycja endpointu:{" "}
              <span
                style={{
                  fontWeight: 500,
                  fontFamily: "monospace",
                  fontSize: 12,
                  color: "#4b5563",
                }}
              >
                {selectedKey}
              </span>
            </div>
            <p
              style={{
                fontSize: 11,
                color: "#6b7280",
                marginTop: 0,
                marginBottom: 10,
              }}
            >
              Uzupełnij pola opisowe. Zmiany zostaną zapisane w YAML i
              wykorzystane przy kolejnym renderowaniu PDF.
            </p>

            {/* Summary */}
            <div style={{ marginTop: 8 }}>
              <label
                style={{ display: "block", fontSize: 12, fontWeight: 600 }}
              >
                Summary (krótkie zdanie)
              </label>
              <input
                type="text"
                value={selectedFields.summary}
                onChange={(e) =>
                  handleFieldsChange({ summary: e.target.value })
                }
                style={{
                  width: "100%",
                  padding: "7px 9px",
                  borderRadius: 10,
                  border: "1px solid #e5e7eb",
                  fontSize: 12,
                  marginTop: 3,
                }}
              />
            </div>

            {/* Description */}
            <div style={{ marginTop: 10 }}>
              <label
                style={{ display: "block", fontSize: 12, fontWeight: 600 }}
              >
                Description (pełny opis endpointu)
              </label>
              <textarea
                value={selectedFields.description}
                onChange={(e) =>
                  handleFieldsChange({ description: e.target.value })
                }
                rows={5}
                style={{
                  width: "100%",
                  padding: 9,
                  borderRadius: 10,
                  border: "1px solid #e5e7eb",
                  fontSize: 12,
                  marginTop: 3,
                  resize: "vertical",
                }}
              />
            </div>

            {/* Response 200 description */}
            <div style={{ marginTop: 10 }}>
              <label
                style={{ display: "block", fontSize: 12, fontWeight: 600 }}
              >
                Response 200 – opis
              </label>
              <input
                type="text"
                value={selectedFields.response200Description}
                onChange={(e) =>
                  handleFieldsChange({
                    response200Description: e.target.value,
                  })
                }
                style={{
                  width: "100%",
                  padding: "7px 9px",
                  borderRadius: 10,
                  border: "1px solid #e5e7eb",
                  fontSize: 12,
                  marginTop: 3,
                }}
              />
            </div>

            {/* Response 200 example */}
            <div style={{ marginTop: 10 }}>
              <label
                style={{ display: "block", fontSize: 12, fontWeight: 600 }}
              >
                Response 200 – przykład (JSON lub tekst)
              </label>
              <textarea
                value={selectedFields.response200Example}
                onChange={(e) =>
                  handleFieldsChange({
                    response200Example: e.target.value,
                  })
                }
                rows={6}
                style={{
                  width: "100%",
                  padding: 9,
                  borderRadius: 10,
                  border: "1px solid #e5e7eb",
                  fontSize: 12,
                  marginTop: 3,
                  fontFamily: "monospace",
                  resize: "vertical",
                }}
              />
            </div>

            {/* Notes – tylko advanced */}
            {isAdvanced && (
              <div style={{ marginTop: 12 }}>
                <label
                  style={{
                    display: "block",
                    fontSize: 12,
                    fontWeight: 600,
                  }}
                >
                  Notatki (notes) – advanced
                </label>
                <textarea
                  value={selectedFields.notesText || ""}
                  placeholder={initialNotesPlaceholder}
                  onChange={(e) =>
                    handleFieldsChange({ notesText: e.target.value })
                  }
                  rows={6}
                  style={{
                    width: "100%",
                    padding: 9,
                    borderRadius: 10,
                    border: "1px solid #e5e7eb",
                    fontSize: 12,
                    marginTop: 3,
                    fontFamily: "monospace",
                    background: "#f9fafb",
                    resize: "vertical",
                  }}
                />
                <p
                  style={{
                    fontSize: 10,
                    color: "#6b7280",
                    marginTop: 4,
                  }}
                >
                  Notatki trafiają do YAML jako tablica{" "}
                  <code>x-impl-notes</code> i będą widoczne w PDF dla
                  poziomu <b>advanced</b>.
                </p>
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
};

export default EditableDocsPanel;
