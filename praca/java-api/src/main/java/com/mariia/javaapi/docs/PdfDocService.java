package com.mariia.javaapi.docs;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.parser.OpenAPIV3Parser;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

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
        // Usuń ewentualny BOM
        if (!html.isEmpty() && html.charAt(0) == '\uFEFF') {
            html = html.substring(1);
        }

        Files.createDirectories(outPdf.getParent());
        try (OutputStream os = Files.newOutputStream(outPdf)) {
            PdfRendererBuilder b = new PdfRendererBuilder();
            b.useFastMode();

            // Czcionka z zasobów (UTF-8, polskie znaki)
            b.useFont(() -> {
                var is = PdfDocService.class.getResourceAsStream("/fonts/times.ttf");
                if (is == null) {
                    throw new IllegalStateException("Brak czcionki w classpath: /fonts/times.ttf");
                }
                return is;
            }, "TimesCustom");

            // baseUri nie może być null – podajemy schemat plikowy
            b.withHtmlContent(html, "file:/");
            b.toStream(os);
            b.run();
        }
        return outPdf;
    }

    // ————————————————————— HTML helpers —————————————————————

    /** Proste escapowanie do HTML. */
    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
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
      .method{font-weight:700}
      .path{font-family: monospace}
      .notes,.examples{background:#fafafa;border:1px solid #eee;border-radius:6px;padding:8px;margin-top:8px}
      code{background:#f4f4f4;padding:2px 4px;border-radius:4px;font-family: monospace; font-size:10pt;}
      pre{white-space:pre-wrap; word-break:break-word; margin:6px 0;}
      table{border-collapse:collapse;width:100%;margin-top:8px;font-size:10pt;}
      th,td{border:1px solid #eee;padding:6px;text-align:left;vertical-align:top;}
    """;

    private String buildHtml(OpenAPI api) {
        // Uwaga: BEZ doctype – czysty XHTML
        StringBuilder sb = new StringBuilder(32_000);
        sb.append("<html xmlns=\"http://www.w3.org/1999/xhtml\"><head>")
          .append("<meta charset=\"utf-8\" />")
          .append("<style>").append(CSS).append("</style>")
          .append("</head><body>");

        String title = api.getInfo() != null ? api.getInfo().getTitle() : "API";
        String ver   = api.getInfo() != null ? api.getInfo().getVersion() : "";
        String spec  = api.getOpenapi() != null ? api.getOpenapi() : "3.x";

        sb.append("<header>");
        sb.append("<h1>").append(esc(title)).append("</h1>");
        sb.append("<div class='muted'>OpenAPI ").append(esc(spec)).append("</div>");
        if (!ver.isBlank()) {
            sb.append("<div class='muted'>Wersja: ").append(esc(ver)).append("</div>");
        }
        sb.append("</header>");

        if (api.getPaths() != null) {
            api.getPaths().forEach((path, pathItem) -> {
                sb.append("<h2><span class='path'>").append(esc(path)).append("</span></h2>");
                renderOp(sb, "GET",   pathItem.getGet(),    path);
                renderOp(sb, "POST",  pathItem.getPost(),   path);
                renderOp(sb, "PUT",   pathItem.getPut(),    path);
                renderOp(sb, "PATCH", pathItem.getPatch(),  path);
                renderOp(sb, "DELETE",pathItem.getDelete(), path);
            });
        }

        sb.append("</body></html>");
        return sb.toString();
    }

    private void renderOp(StringBuilder sb, String method, Operation op, String path) {
        if (op == null) return;

        sb.append("<div class='op'>");
        sb.append("<div class='badge'><span class='method'>")
          .append(esc(method))
          .append("</span> <span class='path'>")
          .append(esc(path))
          .append("</span></div>");

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
            op.getParameters().forEach(p -> {
                String typ = p.getSchema() != null
                        ? (p.getSchema().getType() == null ? "" : p.getSchema().getType())
                        : "";
                sb.append("<tr>")
                  .append("<td>").append(esc(p.getName())).append("</td>")
                  .append("<td>").append(esc(p.getIn())).append("</td>")
                  .append("<td>").append(esc(typ)).append("</td>")
                  .append("<td>").append(Boolean.TRUE.equals(p.getRequired()) ? "tak" : "nie").append("</td>")
                  .append("<td>").append(esc(p.getDescription())).append("</td>")
                  .append("</tr>");
            });
            sb.append("</tbody></table></div>");
        }

        // RequestBody
        if (op.getRequestBody() != null && op.getRequestBody().getContent() != null) {
            Content c = op.getRequestBody().getContent();
            var mt = c.get("application/json");
            if (mt != null) {
                sb.append("<div style='margin-top:8px'><strong>Body (application/json)</strong>");
                if (mt.getSchema() != null && mt.getSchema().get$ref() != null) {
                    sb.append("<div>Schema: <code>")
                      .append(esc(mt.getSchema().get$ref()))
                      .append("</code></div>");
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

        // Responses (z przykładami)
        if (op.getResponses() != null && !op.getResponses().isEmpty()) {
            sb.append("<div style='margin-top:8px'><strong>Odpowiedzi</strong>");
            sb.append("<table><thead><tr><th>Status</th><th>Opis</th><th>Przykład</th></tr></thead><tbody>");
            op.getResponses().forEach((code, resp) -> {
                String ex = "";
                if (resp.getContent() != null && resp.getContent().get("application/json") != null) {
                    var mt = resp.getContent().get("application/json");
                    if (mt.getExample() != null) ex = toPrettyJson(mt.getExample());
                }
                sb.append("<tr>")
                  .append("<td>").append(esc(code)).append("</td>")
                  .append("<td>").append(esc(resp.getDescription())).append("</td>")
                  .append("<td>").append(ex.isEmpty() ? "" : "<pre><code>"+esc(ex)+"</code></pre>").append("</td>")
                  .append("</tr>");
            });
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

    private String toPrettyJson(Object obj) {
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter();
            return mapper.writeValueAsString(obj);
        } catch (Exception e) {
            return String.valueOf(obj);
        }
    }
}
