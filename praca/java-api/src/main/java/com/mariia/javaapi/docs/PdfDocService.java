package com.mariia.javaapi.docs;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.parser.OpenAPIV3Parser;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@Service
public class PdfDocService {

    /**
     * Renderuje PDF na podstawie pliku OpenAPI YAML.
     * Wymaga obecności czcionki w classpath: /fonts/times.ttf
     * (czyli: src/main/resources/fonts/times.ttf)
     */
    public Path renderPdfFromYaml(Path openapiYaml, Path outPdf) throws Exception {
        OpenAPI api = new OpenAPIV3Parser().read(openapiYaml.toAbsolutePath().toString());
        if (api == null) {
            throw new IllegalStateException("Nie można wczytać OpenAPI z: " + openapiYaml);
        }

        String html = buildHtml(api);
        if (!html.isEmpty() && html.charAt(0) == '\uFEFF') {
            html = html.substring(1);
        }

        Files.createDirectories(outPdf.getParent());
        try (OutputStream os = Files.newOutputStream(outPdf)) {
            PdfRendererBuilder b = new PdfRendererBuilder();
            b.useFastMode();

            b.useFont(() -> {
                var is = PdfDocService.class.getResourceAsStream("/fonts/times.ttf");
                if (is == null) {
                    throw new IllegalStateException("Brak czcionki w classpath: /fonts/times.ttf");
                }
                return is;
            }, "TimesCustom");

            b.withHtmlContent(html, "file:/");
            b.toStream(os);
            b.run();
        }
        return outPdf;
    }

    // ————————————————————— HTML helpers —————————————————————

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String nz(String s) { return (s == null) ? "" : s; }

    private static String getInfoLevel(OpenAPI api) {
        if (api.getInfo() != null && api.getInfo().getExtensions() != null) {
            Object v = api.getInfo().getExtensions().get("x-user-level");
            if (v != null) return v.toString().trim().toLowerCase(Locale.ROOT);
        }
        return "intermediate";
    }

    private static String getOpLevel(Operation op, String fallback) {
        if (op != null && op.getExtensions() != null) {
            Object v = op.getExtensions().get("x-user-level");
            if (v != null) return v.toString().trim().toLowerCase(Locale.ROOT);
        }
        return fallback;
    }

    /** Nazwa projektu do nagłówka: x-project-name > info.title > "API". */
    private static String getProjectName(OpenAPI api) {
        if (api != null && api.getInfo() != null) {
            Map<String, Object> ext = api.getInfo().getExtensions();
            if (ext != null && ext.get("x-project-name") != null) {
                String n = String.valueOf(ext.get("x-project-name")).trim();
                if (!n.isBlank()) return n;
            }
            String t = nz(api.getInfo().getTitle()).trim();
            if (!t.isBlank()) return t;
        }
        return "API";
    }

    /** Opcjonalny pakiet nagłówka: title/subtitle/generatedAt z info.extensions["x-doc-header"]. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> getDocHeader(OpenAPI api) {
        if (api != null && api.getInfo() != null && api.getInfo().getExtensions() != null) {
            Object v = api.getInfo().getExtensions().get("x-doc-header");
            if (v instanceof Map) return (Map<String, Object>) v;
        }
        return Collections.emptyMap();
    }

    private static String levelLabelPl(String lvl) {
        switch ((lvl == null ? "" : lvl).toLowerCase(Locale.ROOT)) {
            case "beginner":    return "Poziom: początkujący";
            case "advanced":    return "Poziom: zaawansowany";
            case "intermediate":
            default:            return "Poziom: średniozaawansowany";
        }
    }

    private static String levelClass(String lvl) {
        switch ((lvl == null ? "" : lvl).toLowerCase(Locale.ROOT)) {
            case "beginner":    return "lvl-beginner";
            case "advanced":    return "lvl-advanced";
            case "intermediate":
            default:            return "lvl-intermediate";
        }
    }

    private static final String CSS = """
      @page { size: A4; margin: 24mm; }
      body{font-family: 'TimesCustom', serif; margin:0; color:#111; font-size:12pt;}
      header{margin-bottom:16px}
      h1{font-size:22pt;margin:0 0 4px}
      .muted{color:#666; font-size:10pt;}
      h2{margin:18px 0 6px; padding-bottom:4px; border-bottom:1px solid #ddd; font-size:14pt;}
      .op{border:1px solid #e6e6e6;border-radius:8px;padding:10px;margin:8px 0}
      .badge{display:inline-block;padding:2px 8px;border-radius:999px;font-size:10pt;background:#eef}
      .lvl-badge{display:inline-block;padding:2px 8px;border-radius:999px;font-size:10pt;margin-left:8px;border:1px solid #ddd}
      .lvl-beginner{background:#e9f8ec}        /* delikatna zieleń */
      .lvl-intermediate{background:#eef4ff}    /* delikatny niebieski */
      .lvl-advanced{background:#fff2e6}        /* delikatny pomarańcz */
      .method{font-weight:700}
      .path{font-family: monospace}
      .notes,.examples{background:#fafafa;border:1px solid #eee;border-radius:6px;padding:8px;margin-top:8px}
      code{background:#f4f4f4;padding:2px 4px;border-radius:4px;font-family: monospace; font-size:10pt;}
      pre{white-space:pre-wrap; word-break:break-word; margin:6px 0;}
      table{border-collapse:collapse;width:100%;margin-top:8px;font-size:10pt;}
      th,td{border:1px solid #eee;padding:6px;text-align:left;vertical-align:top;}
      .schemas h3{margin-top:16px}
    """;

    private String buildHtml(OpenAPI api) {
        StringBuilder sb = new StringBuilder(48_000);
        sb.append("<html xmlns=\"http://www.w3.org/1999/xhtml\"><head>")
          .append("<meta charset=\"utf-8\" />")
          .append("<style>").append(CSS).append("</style>")
          .append("</head><body>");

        // === Nagłówek z nazwą projektu ===
        String projectName = getProjectName(api);                 // << klucz: nazwa projektu
        String spec  = api.getOpenapi() != null ? api.getOpenapi() : "3.x";
        String ver   = (api.getInfo() != null ? nz(api.getInfo().getVersion()) : "");

        // poziom z Info.x-user-level
        String infoLevel = getInfoLevel(api);

        // opcjonalny pakiet z x-doc-header
        Map<String, Object> hdr = getDocHeader(api);
        String subtitle   = Objects.toString(hdr.getOrDefault("subtitle", ""), "");
        String generated  = Objects.toString(hdr.getOrDefault("generatedAt", ""), "");

        sb.append("<header>");
        sb.append("<h1>").append(esc(projectName)).append("</h1>");  // NAZWA PROJEKTU u góry
        if (!subtitle.isBlank()) {
            sb.append("<div class='muted'>").append(esc(subtitle)).append("</div>");
        }
        sb.append("<div class='muted'>OpenAPI ").append(esc(spec)).append("</div>");
        if (!ver.isBlank()) {
            sb.append("<div class='muted'>Wersja: ").append(esc(ver)).append("</div>");
        }
        if (!generated.isBlank()) {
            sb.append("<div class='muted'>Wygenerowano: ").append(esc(generated)).append("</div>");
        }
        // badge poziomu
        sb.append("<div class='lvl-badge ").append(levelClass(infoLevel)).append("'>")
          .append(esc(levelLabelPl(infoLevel)))
          .append("</div>");
        sb.append("</header>");

        if (api.getPaths() != null) {
            api.getPaths().forEach((path, pathItem) -> {
                sb.append("<h2><span class='path'>").append(esc(path)).append("</span></h2>");
                renderOp(sb, "GET",   pathItem.getGet(),    path, infoLevel);
                renderOp(sb, "POST",  pathItem.getPost(),   path, infoLevel);
                renderOp(sb, "PUT",   pathItem.getPut(),    path, infoLevel);
                renderOp(sb, "PATCH", pathItem.getPatch(),  path, infoLevel);
                renderOp(sb, "DELETE",pathItem.getDelete(), path, infoLevel);
            });
        }

        // —— sekcja components/schemas na końcu dokumentu ——
        renderComponents(sb, api.getComponents());

        sb.append("</body></html>");
        return sb.toString();
    }

    private void renderOp(StringBuilder sb, String method, Operation op, String path, String infoLevel) {
        if (op == null) return;

        // poziom dla tej operacji (nadpisuje nagłówny, jeśli ustawiony)
        String opLevel = getOpLevel(op, infoLevel);

        sb.append("<div class='op'>");
        sb.append("<div class='badge'><span class='method'>")
          .append(esc(method))
          .append("</span> <span class='path'>")
          .append(esc(path))
          .append("</span></div>");

        // badge poziomu per operacja
        sb.append("<span class='lvl-badge ").append(levelClass(opLevel)).append("'>")
          .append(esc(levelLabelPl(opLevel)))
          .append("</span>");

        if (op.getSummary() != null && !op.getSummary().isBlank()) {
            sb.append("<div style='margin-top:6px'><strong>")
              .append(esc(op.getSummary()))
              .append("</strong></div>");
        }
        if (op.getDescription() != null && !op.getDescription().isBlank()) {
            sb.append("<div class='muted'>")
              .append(esc(op.getDescription()).replace("\n", "<br />"))
              .append("</div>");
        }

        // Parametry
        if (op.getParameters() != null && !op.getParameters().isEmpty()) {
            sb.append("<div style='margin-top:8px'><strong>Parametry</strong>");
            sb.append("<table><thead><tr><th>Nazwa</th><th>W</th><th>Typ</th><th>Wymagany</th><th>Opis</th></tr></thead><tbody>");
            for (Parameter p : op.getParameters()) {
                String typ = (p.getSchema() != null)
                        ? (p.getSchema().getType() == null ? "" : p.getSchema().getType())
                        : "";
                sb.append("<tr>")
                  .append("<td>").append(esc(p.getName())).append("</td>")
                  .append("<td>").append(esc(p.getIn())).append("</td>")
                  .append("<td>").append(esc(typ)).append("</td>")
                  .append("<td>").append(Boolean.TRUE.equals(p.getRequired()) ? "tak" : "nie").append("</td>")
                  .append("<td>").append(esc(p.getDescription())).append("</td>")
                  .append("</tr>");
            }
            sb.append("</tbody></table></div>");
        }

        // RequestBody (application/json)
        if (op.getRequestBody() != null && op.getRequestBody().getContent() != null) {
            Content c = op.getRequestBody().getContent();
            var mt = c.get("application/json");
            if (mt != null) {
                sb.append("<div style='margin-top:8px'><strong>Body (application/json)</strong>");
                if (mt.getSchema() != null) {
                    Schema<?> s = mt.getSchema();
                    if (s.get$ref() != null) {
                        sb.append("<div>Schema: <code>")
                          .append(esc(s.get$ref()))
                          .append("</code></div>");
                    } else if (s.getType() != null) {
                        sb.append("<div>Schema: <code>")
                          .append(esc(s.getType()))
                          .append("</code></div>");
                    }
                }
                if (mt.getExample() != null) {
                    String ex = toPrettyJson(mt.getExample());
                    sb.append("<div class='examples'><div><strong>Przykład żądania</strong></div><pre><code>")
                      .append(esc(ex))
                      .append("</code></pre></div>");
                }
                sb.append("</div>");
            }
        }

        // Responses (schema + przykłady)
        if (op.getResponses() != null && !op.getResponses().isEmpty()) {
            sb.append("<div style='margin-top:8px'><strong>Odpowiedzi</strong>");
            sb.append("<table><thead><tr><th>Status</th><th>Opis</th><th>Schema</th><th>Przykład</th></tr></thead><tbody>");
            for (Map.Entry<String, ApiResponse> e : op.getResponses().entrySet()) {
                String code = e.getKey();
                ApiResponse resp = e.getValue();

                String schemaTxt = "";
                String exTxt = "";
                if (resp.getContent() != null) {
                    MediaType mt = resp.getContent().get("application/json");
                    if (mt != null) {
                        Schema<?> s = mt.getSchema();
                        if (s != null) {
                            if (s.get$ref() != null) schemaTxt = s.get$ref();
                            else if (s.getType() != null) schemaTxt = s.getType();
                        }
                        if (mt.getExample() != null) {
                            exTxt = toPrettyJson(mt.getExample());
                        }
                    }
                }

                sb.append("<tr>")
                  .append("<td>").append(esc(code)).append("</td>")
                  .append("<td>").append(esc(resp.getDescription())).append("</td>")
                  .append("<td>").append(schemaTxt.isEmpty() ? "" : "<code>"+esc(schemaTxt)+"</code>").append("</td>")
                  .append("<td>").append(exTxt.isEmpty() ? "" : "<pre><code>"+esc(exTxt)+"</code></pre>").append("</td>")
                  .append("</tr>");
            }
            sb.append("</tbody></table></div>");
        }

        // x-impl-notes
        if (op.getExtensions() != null && op.getExtensions().get("x-impl-notes") instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> notes = (List<Object>) op.getExtensions().get("x-impl-notes");
            if (!notes.isEmpty()) {
                sb.append("<div class='notes'><strong>Notatki implementacyjne</strong><ul>");
                notes.stream()
                        .map(Objects::toString)
                        .map(PdfDocService::esc)
                        .forEach(n -> sb.append("<li>").append(n).append("</li>"));
                sb.append("</ul></div>");
            }
        }

        // x-request-examples (lista cURL)
        if (op.getExtensions() != null && op.getExtensions().get("x-request-examples") instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> reqs = (List<Object>) op.getExtensions().get("x-request-examples");
            if (!reqs.isEmpty()) {
                sb.append("<div class='examples'><strong>Przykłady wywołań</strong>");
                reqs.stream()
                        .map(Objects::toString)
                        .map(PdfDocService::esc)
                        .forEach(curl -> sb.append("<pre><code>").append(curl).append("</code></pre>"));
                sb.append("</div>");
            }
        }

        sb.append("</div>");
    }

    private void renderComponents(StringBuilder sb, Components components) {
        if (components == null || components.getSchemas() == null || components.getSchemas().isEmpty()) return;

        sb.append("<h2>Components / Schemas</h2>");
        sb.append("<div class='schemas'>");

        components.getSchemas().forEach((name, schema) -> {
            sb.append("<h3>").append(esc(name)).append("</h3>");
            // pokaż ogólny typ/ref
            if (schema.get$ref() != null) {
                sb.append("<div>Ref: <code>").append(esc(schema.get$ref())).append("</code></div>");
            } else if (schema.getType() != null) {
                sb.append("<div>Type: <code>").append(esc(schema.getType())).append("</code></div>");
            }

            // jeśli obiekt – pokaż pola
            if (schema instanceof ObjectSchema obj) {
                Map<String, Schema> props = obj.getProperties();
                if (props != null && !props.isEmpty()) {
                    sb.append("<table><thead><tr><th>Pole</th><th>Typ</th><th>Opis</th></tr></thead><tbody>");
                    props.forEach((propName, propSchema) -> {
                        String t = propSchema.get$ref() != null
                                ? propSchema.get$ref()
                                : (propSchema.getType() != null ? propSchema.getType() : "");
                        String desc = propSchema.getDescription() != null ? propSchema.getDescription() : "";
                        sb.append("<tr>")
                          .append("<td>").append(esc(propName)).append("</td>")
                          .append("<td>").append(t.isEmpty() ? "" : "<code>"+esc(t)+"</code>").append("</td>")
                          .append("<td>").append(esc(desc)).append("</td>")
                          .append("</tr>");
                    });
                    sb.append("</tbody></table>");
                }
            }

            // jeśli tablica – pokaż typ elementu
            if (schema instanceof ArraySchema arr) {
                Schema<?> items = arr.getItems();
                String it = (items != null && items.get$ref() != null) ? items.get$ref()
                        : (items != null && items.getType() != null) ? items.getType() : "";
                if (!it.isEmpty()) {
                    sb.append("<div>Items: <code>").append(esc(it)).append("</code></div>");
                }
            }
        });

        sb.append("</div>");
    }

    private String toPrettyJson(Object obj) {
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter();
            return mapper.writeValueAsString(obj);
        } catch (Exception e) {
            return String.valueOf(obj);
        }
    }
}
