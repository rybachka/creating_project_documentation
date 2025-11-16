package com.mariia.javaapi.docs;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.parser.OpenAPIV3Parser;
import org.springframework.stereotype.Service;
import io.swagger.v3.oas.models.security.SecurityScheme;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;


@Service
public class PdfDocService {

    public Path renderPdfFromYaml(Path openapiYaml, Path outPdf) throws Exception {
        OpenAPI api = new OpenAPIV3Parser().read(openapiYaml.toAbsolutePath().toString());
        if (api == null) {
            throw new IllegalStateException("Nie można wczytać OpenAPI z: " + openapiYaml);
        }

        String html = buildHtml(api);
        if (!html.isEmpty() && html.charAt(0) == '\uFEFF') { // usuń BOM jeśli występuje
            html = html.substring(1);
        }

        Files.createDirectories(outPdf.getParent());
        try (OutputStream os = Files.newOutputStream(outPdf)) {
            PdfRendererBuilder b = new PdfRendererBuilder();
            b.useFastMode();

            b.useFont(() -> {
                var is = PdfDocService.class.getResourceAsStream("/fonts/times.ttf");
                if (is == null) {
                    throw new IllegalStateException("Brak czcionki w classpath: /fonts/DejaVuSerif.ttf");
                }
                return is;
            }, "DejaVuSerif");

            b.withHtmlContent(html, "file:/");
            b.toStream(os);
            b.run();
        }
        return outPdf;
    }

    //  HTML / CSS / helpers
    private static final String CSS = """
      @page { size: A4; margin: 24mm; }
      body{font-family: 'DejaVuSerif', serif; margin:0; color:#111; font-size:12pt;}
      header{margin-bottom:16px}
      h1{font-size:22pt;margin:0 0 4px}
      .muted{color:#666; font-size:10pt;}
      h2{margin:18px 0 6px; padding-bottom:4px; border-bottom:1px solid #ddd; font-size:14pt;}
      .op{border:1px solid #e6e6e6;border-radius:8px;padding:10px;margin:8px 0}
      .badge{display:inline-block;padding:2px 8px;border-radius:999px;font-size:10pt;background:#eef}
      .lvl-badge{display:inline-block;padding:2px 8px;border-radius:999px;font-size:10pt;margin-left:8px;border:1px solid #ddd}
      .lvl-beginner{background:#e9f8ec}
      .lvl-advanced{background:#fff2e6}
      .method{font-weight:700}
      .path{font-family: monospace}
      .notes,.examples{background:#fafafa;border:1px solid #eee;border-radius:6px;padding:8px;margin-top:8px}
      code{background:#f4f4f4;padding:2px 4px;border-radius:4px;font-family: monospace; font-size:10pt;}
      pre{white-space:pre-wrap; word-break:break-word; margin:6px 0;}
      table{border-collapse:collapse;width:100%;margin-top:8px;font-size:10pt;}
      th,td{border:1px solid #eee;padding:6px;text-align:left;vertical-align:top;}
      .schemas h3{margin-top:16px}
      .security{background:#fbfcff;border:1px solid #e6ecff;border-radius:6px;padding:8px;margin:10px 0}
    """;

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private static String nz(String s) {
        return (s == null) ? "" : s;
    }
     //Poziomu dokumentu: GLOBALNIE
     ///info.extensions["x-user-level"] = "beginner" -> beginner
     //cokolwiek innego -> advanced (domyślnie).
    private static String getInfoLevel(OpenAPI api) {
        if (api.getInfo() != null && api.getInfo().getExtensions() != null) {
            Object v = api.getInfo().getExtensions().get("x-user-level");
            if (v != null) {
                String lvl = v.toString().trim().toLowerCase(Locale.ROOT);
                if ("beginner".equals(lvl)) {
                    return "beginner";
                }
            }
        }
        return "advanced";
    }
    //Poziom operacji  PER-POINT – z x-user-level, w przeciwnym wypadku fallback do poziomu dokumentu.
    private static String getOpLevel(Operation op, String fallback) {
        if (op != null && op.getExtensions() != null) {
            Object v = op.getExtensions().get("x-user-level");
            if (v != null) {
                String lvl = v.toString().trim().toLowerCase(Locale.ROOT);
                if ("beginner".equals(lvl) || "advanced".equals(lvl)) {
                    return lvl;
                }
            }
        }
        return fallback;
    }

    //Nazwa projektu:- info.extensions["x-project-name"] (pierwotna nazwa zipa),
    //- potem info.title bez sufiksu "-API",
    //- inaczej "API".
    private static String getProjectName(OpenAPI api) {
        if (api != null && api.getInfo() != null) {
            Map<String, Object> ext = api.getInfo().getExtensions();
            if (ext != null && ext.get("x-project-name") != null) {
                String n = String.valueOf(ext.get("x-project-name")).trim();
                if (!n.isBlank()) return n;
            }
            String t = nz(api.getInfo().getTitle()).trim();
            if (!t.isBlank()) {
                if (t.endsWith("-API") && t.length() > 4) {
                    return t.substring(0, t.length() - 4);
                }
                return t;
            }
        }
        return "API";
    }

    private static String levelLabelPl(String lvl) {
        String v = (lvl == null ? "" : lvl).toLowerCase(Locale.ROOT);
        return switch (v) {
            case "beginner" -> "Poziom: początkujący";
            case "advanced" -> "Poziom: zaawansowany";
            default         -> "Poziom: zaawansowany";
        };
    }

    private static String levelClass(String lvl) {
        String v = (lvl == null ? "" : lvl).toLowerCase(Locale.ROOT);
        return switch (v) {
            case "beginner" -> "lvl-beginner";
            case "advanced" -> "lvl-advanced";
            default         -> "lvl-advanced";
        };
    }

    // BUDUJE HTML
    private String buildHtml(OpenAPI api) {
        StringBuilder sb = new StringBuilder(48_000);
        sb.append("<html xmlns=\"http://www.w3.org/1999/xhtml\"><head>")
          .append("<meta charset=\"utf-8\" />")
          .append("<style>").append(CSS).append("</style>")
          .append("</head><body>");

        String projectName = getProjectName(api);
        String infoLevel = getInfoLevel(api);

        // Nagłówek
        sb.append("<header>");
        sb.append("<h1>").append(esc(projectName)).append("</h1>");
        sb.append("<div class='lvl-badge ")
          .append(levelClass(infoLevel))
          .append("'>")
          .append(esc(levelLabelPl(infoLevel)))
          .append("</div>");
        sb.append("</header>");

        sb.append(renderSecurityOverviewSection(api));

        // Globalna macierz błędów
        renderGlobalErrorsSection(sb);

        // Słowniczek dla początkujących
        sb.append(renderBeginnerGlossaryIfNeeded(api));

        // Ścieżki + operacje
        if (api.getPaths() != null) {
            api.getPaths().forEach((path, pathItem) -> {
                sb.append("<h2><span class='path'>")
                  .append(esc(path))
                  .append("</span></h2>");
                renderOp(sb, api, "GET",    pathItem.getGet(),    path, infoLevel);
                renderOp(sb, api, "POST",   pathItem.getPost(),   path, infoLevel);
                renderOp(sb, api, "PUT",    pathItem.getPut(),    path, infoLevel);
                renderOp(sb, api, "PATCH",  pathItem.getPatch(),  path, infoLevel);
                renderOp(sb, api, "DELETE", pathItem.getDelete(), path, infoLevel);
            });
        }

        // Schematy
        renderComponents(sb, api.getComponents());

        sb.append("</body></html>");
        return sb.toString();
    }

    // Globalna macierz błędów
    private void renderGlobalErrorsSection(StringBuilder sb) {
        sb.append("<section class='security'>")
          .append("<strong>Standardowe kody błędów</strong>")
          .append("<table><thead><tr>")
          .append("<th>Status</th><th>Znaczenie</th></tr></thead><tbody>")
          .append("<tr><td>400</td><td>Nieprawidłowe lub niekompletne dane w żądaniu.</td></tr>")
          .append("<tr><td>401</td><td>Brak poprawnego tokenu uwierzytelniającego (nagłówek Authorization: Bearer &lt;token&gt;).</td></tr>")
          .append("<tr><td>403</td><td>Token jest poprawny, ale użytkownik nie ma wymaganych uprawnień.</td></tr>")
          .append("<tr><td>404</td><td>Żądany zasób nie istnieje lub nie jest dostępny.</td></tr>")
          .append("<tr><td>409</td><td>Konflikt danych, np. duplikat lub niezgodny stan.</td></tr>")
          .append("<tr><td>422</td><td>Dane są poprawne technicznie, ale nie spełniają reguł biznesowych.</td></tr>")
          .append("<tr><td>429</td><td>Przekroczono limit zapytań – spróbuj ponownie później.</td></tr>")
          .append("<tr><td>500</td><td>Nieoczekiwany błąd po stronie serwera.</td></tr>")
          .append("</tbody></table>")
          .append("<div class='muted'>Struktura błędu jest opisana w schemacie <code>ApiError</code> w sekcji Components.</div>")
          .append("</section>");
    }
    //  BEGINNER Podstawowe pojecia
    private String renderBeginnerGlossaryIfNeeded(OpenAPI api) {
        String level = getInfoLevel(api);
        if (!isBeginnerLevel(level)) {
            return "";
        }

        boolean bearer = hasBearerAuth(api);
        boolean basic  = hasBasicAuth(api);
        boolean oauth2 = hasOAuth2(api);

        StringBuilder sb = new StringBuilder();
        sb.append("<section>")
        .append("<h2>Podstawowe pojęcia</h2>")
        .append("<ul>")
        .append("<li><b>Endpoint</b> – konkretny adres URL, pod który wysyłasz żądanie HTTP.</li>")
        .append("<li><b>Metoda HTTP</b> (GET, POST, PUT, DELETE) – określa, czy pobierasz, tworzysz, zmieniasz lub usuwasz dane.</li>");

        if (bearer) {
            sb.append("<li><b>Token JWT</b> – „bilet” potwierdzający zalogowanie użytkownika.</li>")
            .append("<li><b>Authorization: Bearer &lt;token&gt;</b> – nagłówek z tokenem wymagany przez większość chronionych endpointów.</li>");
        }
        if (basic) {
            sb.append("<li><b>HTTP Basic</b> – logowanie za pomocą loginu i hasła przesyłanych w nagłówku Authorization.</li>");
        }
        if (oauth2) {
            sb.append("<li><b>OAuth2</b> – standard autoryzacji używany m.in. przez zewnętrznych dostawców tożsamości (np. Google, Keycloak).</li>");
        }

        sb.append("<li><b>Paginacja</b> – podział wyników na strony (page/size/sort), aby nie pobierać wszystkich danych naraz.</li>")
        .append("</ul>")
        .append("</section>");

        return sb.toString();
    }
    private boolean hasBearerAuth(OpenAPI api) {
        Components comps = api.getComponents();
        if (comps == null || comps.getSecuritySchemes() == null) return false;
        return comps.getSecuritySchemes().values().stream()
                .anyMatch(s ->
                        s != null &&
                        s.getType() == SecurityScheme.Type.HTTP &&
                        "bearer".equalsIgnoreCase(nz(s.getScheme()))
                );
    }
    private boolean hasBasicAuth(OpenAPI api) {
        Components comps = api.getComponents();
        if (comps == null || comps.getSecuritySchemes() == null) return false;
        return comps.getSecuritySchemes().values().stream()
                .anyMatch(s ->
                        s != null &&
                        s.getType() == SecurityScheme.Type.HTTP &&
                        "basic".equalsIgnoreCase(nz(s.getScheme()))
                );
    }
    private boolean hasOAuth2(OpenAPI api) {
        Components comps = api.getComponents();
        if (comps == null || comps.getSecuritySchemes() == null) return false;
        return comps.getSecuritySchemes().values().stream()
                .anyMatch(s -> s != null && s.getType() == SecurityScheme.Type.OAUTH2);
    }
    //  RENDER ENDPOINT / OPERATION
    private void renderOp(StringBuilder sb,
                          OpenAPI api,
                          String method,
                          Operation op,
                          String path,
                          String infoLevel) {
        if (op == null) return;

        String opLevel = getOpLevel(op, infoLevel);
        boolean isBeginnerDoc = isBeginnerLevel(infoLevel);
        boolean isBeginnerOp  = isBeginnerLevel(opLevel);
        boolean isBeginner    = isBeginnerDoc || isBeginnerOp;
        String levelForResponses = isBeginner ? "beginner" : opLevel;

        sb.append("<div class='op'>");

        // Pasek z metodą i ścieżką
        sb.append("<div class='badge'><span class='method'>")
          .append(esc(method))
          .append("</span> <span class='path'>")
          .append(esc(path))
          .append("</span></div>");

        // Badge poziomu operacji
        sb.append("<span class='lvl-badge ")
          .append(levelClass(opLevel))
          .append("'>")
          .append(esc(levelLabelPl(opLevel)))
          .append("</span>");

        // Security label
        String opSecurityLabel = computeOperationSecurityLabel(api, op);
        if (!opSecurityLabel.isBlank()) {
            sb.append("<div class='muted' style='margin-top:6px'><strong>Security:</strong> ")
              .append(esc(opSecurityLabel))
              .append("</div>");
        }

        // Opis (summary / description) + fallback dla beginner
        String summary = nz(op.getSummary());
        String description = nz(op.getDescription());

        if (!summary.isBlank()) {
            sb.append("<div style='margin-top:6px'><strong>")
            .append(esc(summary))
            .append("</strong></div>");
        }

        if (!description.isBlank()) {
            sb.append("<div class='muted'>")
            .append(esc(description).replace("\n", "<br />"))
            .append("</div>");
        }

        // Parametry
        if (op.getParameters() != null && !op.getParameters().isEmpty()) {
            sb.append("<div style='margin-top:8px'><strong>Parametry</strong>");
            sb.append("<table><thead><tr>")
              .append("<th>Nazwa</th>")
              .append("<th>W</th>")
              .append("<th>Typ</th>")
              .append("<th>Wymagany</th>")
              .append("<th>Ograniczenia</th>")
              .append("<th>Opis</th>")
              .append("</tr></thead><tbody>");
            for (Parameter p : op.getParameters()) {
                String typ = (p.getSchema() != null && p.getSchema().getType() != null)
                        ? p.getSchema().getType()
                        : "";
                String constraints = paramConstraints(p);
                sb.append("<tr>")
                  .append("<td>").append(esc(p.getName())).append("</td>")
                  .append("<td>").append(esc(p.getIn())).append("</td>")
                  .append("<td>").append(esc(typ)).append("</td>")
                  .append("<td>").append(Boolean.TRUE.equals(p.getRequired()) ? "tak" : "nie").append("</td>")
                  .append("<td>").append(esc(constraints)).append("</td>")
                  .append("<td>").append(esc(p.getDescription())).append("</td>")
                  .append("</tr>");
            }
            sb.append("</tbody></table></div>");
        }

        // Request body (application/json)
        if (op.getRequestBody() != null && op.getRequestBody().getContent() != null) {
            Content c = op.getRequestBody().getContent();
            MediaType mt = c.get("application/json");
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
                    sb.append("<div class='examples'><div><strong>Przykład żądania</strong></div>")
                      .append("<pre><code>")
                      .append(esc(ex))
                      .append("</code></pre></div>");
                }
                sb.append("</div>");
            }
        }

        // Odpowiedzi – filtr dla beginner (kluczowe statusy)
        if (op.getResponses() != null && !op.getResponses().isEmpty()) {
            sb.append("<div style='margin-top:8px'><strong>Odpowiedzi</strong>");
            sb.append("<table><thead><tr>")
              .append("<th>Status</th>")
              .append("<th>Opis</th>")
              .append("<th>Schema</th>")
              .append("<th>Przykład</th>")
              .append("</tr></thead><tbody>");

            for (Map.Entry<String, ApiResponse> e : op.getResponses().entrySet()) {
                String code = e.getKey();
                ApiResponse resp = e.getValue();

                if (isBeginner && !shouldShowResponseForBeginner(code, levelForResponses)) {
                    continue;
                }

                String schemaTxt = "";
                String exTxt = "";
                if (resp.getContent() != null) {
                    MediaType mt = resp.getContent().get("application/json");
                    if (mt != null) {
                        Schema<?> s = mt.getSchema();
                        if (s != null) {
                            schemaTxt = schemaToLabel(s);
                        }
                        if (mt.getExample() != null) {
                            exTxt = toPrettyJson(mt.getExample());
                        }
                    }
                }
                //budujemy HTML z ewentualnym komentarzem ---
                String schemaHtml = "";
                if (!schemaTxt.isEmpty()) {
                    schemaHtml = "<code>" + esc(schemaTxt) + "</code>";
                    if ("#/components/schemas/?".equals(schemaTxt)) {
                        schemaHtml += "<div class='muted'>"
                                + "Typ odpowiedzi nie został jednoznacznie określony podczas generowania OpenAPI "
                                + "(użyto symbolicznego placeholdera <code>?</code>)."
                                + "</div>";
                    }
                }

                String exampleHtml = "";
                if (!exTxt.isEmpty()) {
                    exampleHtml = "<pre><code>" + esc(exTxt) + "</code></pre>";
                    if ("{}".equals(exTxt.trim())) {
                        exampleHtml += "<div class='muted'>"
                                + "Endpoint nie zwraca treści albo szczegółowa struktura odpowiedzi "
                                + "nie była dostępna w modelu IR podczas generowania dokumentacji."
                                + "</div>";
                    }
                }

                sb.append("<tr>")
                .append("<td>").append(esc(code)).append("</td>")
                .append("<td>").append(esc(nz(resp.getDescription()))).append("</td>")
                .append("<td>").append(schemaHtml).append("</td>")
                .append("<td>").append(exampleHtml).append("</td>")
                .append("</tr>");

            }

            sb.append("</tbody></table></div>");
        }

        // Notatki implementacyjne tylko dla advanced
        if (!isBeginner
                && op.getExtensions() != null
                && op.getExtensions().get("x-impl-notes") instanceof List) {
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

        // Przykłady curl:
        if (op.getExtensions() != null
                && op.getExtensions().get("x-request-examples") instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> reqs = (List<Object>) op.getExtensions().get("x-request-examples");
            if (!reqs.isEmpty()) {
                boolean secured = isOperationSecured(api, op);

                sb.append("<div class='examples'><strong>Przykłady wywołań</strong>");
                int limit = isBeginner ? 1 : reqs.size();
                for (int i = 0; i < limit; i++) {
                    String curl = Objects.toString(reqs.get(i), "");
                    if (!curl.isBlank()) {
                        String adjusted = injectAuthHeaderIfNeeded(curl, secured);
                        sb.append("<pre><code>")
                        .append(esc(adjusted))
                        .append("</code></pre>");
                    }
                }
                sb.append("</div>");
            }
        }

        sb.append("</div>");
    }

    //Wylicza etykietę security dla operacji: lokalne -> globalne.
    //Uwzględnia fakt, że security: [] na poziomie operacji oznacza PUBLIC.
    private String computeOperationSecurityLabel(OpenAPI api, Operation op) {
        if (op == null || api == null) {
            return "";
        }

        // 0. Najpierw honorujemy x-security z YAML
        if (op.getExtensions() != null) {
            Object xs = op.getExtensions().get("x-security");
            if (xs != null) {
                String v = xs.toString().trim().toLowerCase(Locale.ROOT);
                if ("public".equals(v)) {
                    // Wymuszenie, że to endpoint publiczny – ignorujemy globalne Bearer
                    return "publiczny (brak uwierzytelniania)";
                }
                // "secured" po prostu przepuszczamy dalej, żeby policzyć konkretny schemat (Bearer / Basic / itd.)
            }
        }

        // 1. Per-operation security
        if (op.getSecurity() != null) {
            // jeśli parser jednak kiedyś zachowa [] – to nadal działa
            if (op.getSecurity().isEmpty()) {
                return "publiczny (brak uwierzytelniania)";
            }

            Set<String> schemeNames = new LinkedHashSet<>();
            for (SecurityRequirement r : op.getSecurity()) {
                if (r != null) {
                    schemeNames.addAll(r.keySet());
                }
            }

            if (!schemeNames.isEmpty()) {
                return buildSecurityLabel(api, schemeNames);
            }

            return "zabezpieczony (wymaga uwierzytelniania)";
        }

        // 2. Global security – fallback
        List<SecurityRequirement> global = api.getSecurity();
        if (global != null && !global.isEmpty()) {
            Set<String> schemeNames = new LinkedHashSet<>();
            for (SecurityRequirement r : global) {
                if (r != null) {
                    schemeNames.addAll(r.keySet());
                }
            }

            if (!schemeNames.isEmpty()) {
                return buildSecurityLabel(api, schemeNames);
            }

            return "zabezpieczony (wymaga uwierzytelniania)";
        }

        // 3. Brak jakiegokolwiek security
        return "";
    }
    // Mapuje nazwy schematów security na przyjazne etykiety na podstawie components.securitySchemes.
    private String buildSecurityLabel(OpenAPI api, Set<String> names) {
        Components comps = api.getComponents();
        Map<String, SecurityScheme> schemes =
                (comps != null) ? comps.getSecuritySchemes() : null;

        List<String> labels = new ArrayList<>();

        for (String name : names) {
            SecurityScheme s = (schemes != null) ? schemes.get(name) : null;
            if (s == null) {
                // Nie znamy definicji – pokaż czystą nazwę z OpenAPI
                labels.add(name);
                continue;
            }

            SecurityScheme.Type type = s.getType();
            if (type == null) {
                labels.add(name);
                continue;
            }

            switch (type) {
                case HTTP -> {
                    String scheme = nz(s.getScheme()).toLowerCase(Locale.ROOT);
                    if ("bearer".equals(scheme)) {
                        String fmt = nz(s.getBearerFormat());
                        if (!fmt.isBlank()) {
                            labels.add("Bearer " + fmt);
                        } else {
                            labels.add("Bearer token");
                        }
                    } else if ("basic".equals(scheme)) {
                        labels.add("HTTP Basic");
                    } else {
                        labels.add("HTTP " + scheme);
                    }
                }
                case APIKEY -> {
                    String in = (s.getIn() != null) ? s.getIn().toString().toLowerCase(Locale.ROOT) : "";
                    String n = nz(s.getName());
                    if (!in.isBlank() || !n.isBlank()) {
                        labels.add("API Key" + (n.isBlank() ? "" : " (" + in + " " + n + ")"));
                    } else {
                        labels.add("API Key");
                    }
                }
                case OAUTH2 -> labels.add("OAuth2");
                case OPENIDCONNECT -> labels.add("OpenID Connect");
                default -> labels.add(name);
            }
        }

        return String.join(", ", labels);
    }
    // Sekcja podsumowująca security na początku PDF
    private String renderSecurityOverviewSection(OpenAPI api) {
        Components comps = api.getComponents();
        Map<String, SecurityScheme> schemes =
                (comps != null) ? comps.getSecuritySchemes() : null;

        boolean hasSchemes = schemes != null && !schemes.isEmpty();
        List<SecurityRequirement> global = api.getSecurity();
        boolean hasGlobalSecurity = global != null && !global.isEmpty();

        // Sprawdź, czy mamy jakieś endpointy oznaczone jako publiczne
        boolean hasPublicEndpoints = false;
        if (api.getPaths() != null) {
            outer:
            for (var entry : api.getPaths().entrySet()) {
                PathItem pi = entry.getValue();
                if (pi == null) continue;
                for (Operation op : Arrays.asList(
                        pi.getGet(), pi.getPost(), pi.getPut(), pi.getPatch(), pi.getDelete()
                )) {
                    if (op == null) continue;
                    // x-security: public
                    if (op.getExtensions() != null) {
                        Object xs = op.getExtensions().get("x-security");
                        if (xs != null && "public".equalsIgnoreCase(xs.toString().trim())) {
                            hasPublicEndpoints = true;
                            break outer;
                        }
                    }
                    // security: [] – parser może to zjeść, ale jeśli nie, też uwzględniamy
                    if (op.getSecurity() != null && op.getSecurity().isEmpty()) {
                        hasPublicEndpoints = true;
                        break outer;
                    }
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<section class='security'>")
          .append("<strong>Podsumowanie bezpieczeństwa (security)</strong>");

        if (!hasSchemes && !hasGlobalSecurity) {
            sb.append("<div>W tym projekcie <b>nie wykryto zdefiniowanych mechanizmów uwierzytelniania</b> w OpenAPI.</div>")
              .append("<div>W praktyce oznacza to, że wszystkie endpointy są domyślnie publiczne, ")
              .append("chyba że w kodzie lub w rozszerzeniach (np. <code>x-security</code>) wskazano inaczej.</div>");
            sb.append("</section>");
            return sb.toString();
        }

        if (hasSchemes) {
            sb.append("<div>W tym projekcie wykryto następujące mechanizmy uwierzytelniania:</div>")
              .append("<ul>");

            for (Map.Entry<String, SecurityScheme> e : schemes.entrySet()) {
                String name = e.getKey();
                SecurityScheme s = e.getValue();
                if (s == null) continue;

                SecurityScheme.Type type = s.getType();
                String label;

                if (type == SecurityScheme.Type.HTTP) {
                    String scheme = nz(s.getScheme()).toLowerCase(Locale.ROOT);
                    if ("bearer".equals(scheme)) {
                        String fmt = nz(s.getBearerFormat());
                        if (!fmt.isBlank()) {
                            label = "Bearer " + fmt + " (nagłówek <code>Authorization: Bearer &lt;token&gt;</code>)";
                        } else {
                            label = "Bearer token (nagłówek <code>Authorization: Bearer &lt;token&gt;</code>)";
                        }
                    } else if ("basic".equals(scheme)) {
                        label = "HTTP Basic (login i hasło w nagłówku <code>Authorization</code>)";
                    } else {
                        label = "HTTP " + scheme;
                    }
                } else if (type == SecurityScheme.Type.APIKEY) {
                    String in = (s.getIn() != null) ? s.getIn().toString().toLowerCase(Locale.ROOT) : "";
                    String n = nz(s.getName());
                    if (!in.isBlank() || !n.isBlank()) {
                        label = "API Key (" + in + (n.isBlank() ? "" : ", nazwa: <code>" + esc(n) + "</code>") + ")";
                    } else {
                        label = "API Key";
                    }
                } else if (type == SecurityScheme.Type.OAUTH2) {
                    label = "OAuth2 (zewnętrzny dostawca tożsamości, np. Keycloak / Google)";
                } else if (type == SecurityScheme.Type.OPENIDCONNECT) {
                    label = "OpenID Connect";
                } else {
                    label = name;
                }

                sb.append("<li>")
                  .append("<code>").append(esc(name)).append("</code>: ")
                  .append(label)
                  .append("</li>");
            }

            sb.append("</ul>");
        } else {
            sb.append("<div>W projekcie nie zdefiniowano konkretnych schematów security w sekcji <code>components.securitySchemes</code>, ")
              .append("ale istnieje globalna konfiguracja <code>security</code> w OpenAPI.</div>");
        }

        if (hasGlobalSecurity) {
            sb.append("<div style='margin-top:4px'>")
              .append("Globalna sekcja <code>security</code> jest ustawiona – oznacza to, że ")
              .append("<b>wszystkie endpointy domyślnie wymagają uwierzytelniania</b>, ")
              .append("chyba że dany endpoint nadpisze to przez <code>security: []</code> ")
              .append("lub <code>x-security: public</code>.</div>");
        } else {
            sb.append("<div style='margin-top:4px'>")
              .append("Globalna sekcja <code>security</code> nie jest ustawiona – ")
              .append("wymóg logowania musi być definiowany osobno na poziomie poszczególnych operacji (endpointów).</div>");
        }

        if (hasPublicEndpoints) {
            sb.append("<div style='margin-top:4px'>Wykryto również endpointy oznaczone jako publiczne ")
              .append("(np. z <code>x-security: public</code> lub <code>security: []</code>) – ")
              .append("w PDF zobaczysz przy nich etykietę „publiczny (brak uwierzytelniania)”.</div>");
        }

        sb.append("</section>");
        return sb.toString();
    }

    //NAGLOWEK DLA CURL BARIER TOKEN    
    private boolean isOperationSecured(OpenAPI api, Operation op) {
        if (api == null || op == null) {
            return false;
        }
        // 1) x-security: public → traktujemy jako publiczny
        if (op.getExtensions() != null) {
            Object xs = op.getExtensions().get("x-security");
            if (xs != null && "public".equalsIgnoreCase(xs.toString().trim())) {
                return false;
            }
        }
        // 2) per-operation security
        if (op.getSecurity() != null) {
            // security: [] → jawnie publiczny
            if (op.getSecurity().isEmpty()) {
                return false;
            }
            // niepusta lista z jakimikolwiek schematami → secured
            for (SecurityRequirement r : op.getSecurity()) {
                if (r != null && !r.isEmpty()) {
                    return true;
                }
            }
        }
        // 3) global security – fallback
        List<SecurityRequirement> global = api.getSecurity();
        if (global != null && !global.isEmpty()) {
            for (SecurityRequirement r : global) {
                if (r != null && !r.isEmpty()) {
                    return true;
                }
            }
        }
        // 4) brak info o security w OpenAPI → traktujemy jako publiczny
        return false;
    }
    private String injectAuthHeaderIfNeeded(String curl, boolean secured) {
        if (!secured) {
            return curl; // publiczny endpoint → nic nie ruszamy
        }
        if (curl == null || curl.isBlank()) {
            return curl;
        }

        // Jeżeli model już dodał Authorization – nie dotykamy
        String lower = curl.toLowerCase(Locale.ROOT);
        if (lower.contains("authorization:")) {
            return curl;
        }

        // Najprościej: dokładamy header na końcu komendy
        // Z łamaniem linii, żeby w PDF wyglądało ok.
        if (curl.contains("\n")) {
            // już jest w formacie wieloliniowym
            return curl + "\n  -H \"Authorization: Bearer <token>\"";
        } else {
            // jednowierszowy curl
            return curl + " \\\n  -H \"Authorization: Bearer <token>\"";
        }
    }




    //  COMPONENTS / SCHEMAS
    private void renderComponents(StringBuilder sb, Components components) {
        if (components == null) return;
        if (components.getSchemas() == null || components.getSchemas().isEmpty()) return;

        sb.append("<h2>Components / Schemas</h2>");
        sb.append("<div class='schemas'>");

        components.getSchemas().forEach((name, schema) -> {
            sb.append("<h3>").append(esc(name)).append("</h3>");
            if (schema.get$ref() != null) {
                sb.append("<div>Ref: <code>").append(esc(schema.get$ref())).append("</code></div>");
            } else if (schema.getType() != null) {
                sb.append("<div>Type: <code>").append(esc(schema.getType())).append("</code></div>");
            }

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

            if (schema instanceof ArraySchema arr) {
                Schema<?> items = arr.getItems();
                String it = (items != null && items.get$ref() != null)
                        ? items.get$ref()
                        : (items != null && items.getType() != null)
                            ? items.getType()
                            : "";
                if (!it.isEmpty()) {
                    sb.append("<div>Items: <code>").append(esc(it)).append("</code></div>");
                }
            }
        });

        sb.append("</div>");
    }
    //  JSON / SCHEMA HELPERS
    private String toPrettyJson(Object obj) {
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter();
            return mapper.writeValueAsString(obj);
        } catch (Exception e) {
            return String.valueOf(obj);
        }
    }
    //Zwięzła etykieta schema
    //generuje krótką etykietę typu: preferuje $ref, 
    //obsługuje array<…>, ma specjalny skrót dla PageResponse<T>, łączy allOf jako A + B, a na końcu zwraca type lub pustkę.
    private static String schemaToLabel(Schema<?> s) {
        if (s == null) return "";
        if (s.get$ref() != null && !s.get$ref().isBlank()) {
            return s.get$ref();
        }
        if (s instanceof ArraySchema arr) {
            String it = schemaToLabel(arr.getItems());
            return it.isBlank() ? "array" : "array<" + it + ">";
        }
        if (s instanceof ComposedSchema cs && cs.getAllOf() != null && !cs.getAllOf().isEmpty()) {
            boolean isPage = cs.getAllOf().stream().anyMatch(x ->
                    (x.get$ref() != null && x.get$ref().endsWith("/PageResponse")) ||
                    "PageResponse".equalsIgnoreCase(Objects.toString(x.getName(), ""))
            );
            if (isPage) {
                Schema<?> t = extractPageItemSchema(cs);
                String tLabel = schemaToLabel(t);
                if (tLabel.isBlank()) tLabel = "object";
                return "PageResponse<" + tLabel + ">";
            }
            return cs.getAllOf().stream()
                    .map(PdfDocService::schemaToLabel)
                    .filter(x -> x != null && !x.isBlank())
                    .reduce((a, b) -> a + " + " + b)
                    .orElse("composed");
        }
        if (s.getType() != null && !s.getType().isBlank()) {
            return s.getType();
        }
        return "";
    }
    //Metoda znajduje typ elementu stron w PageResponse patrząc na pole content (tablica) w jednej z części allOf.
    private static Schema<?> extractPageItemSchema(ComposedSchema cs) {
        for (Schema<?> part : cs.getAllOf()) {
            if (part instanceof ObjectSchema obj && obj.getProperties() != null) {
                Object prop = obj.getProperties().get("content");
                if (prop instanceof ArraySchema arr) {
                    return arr.getItems();
                }
                if (prop instanceof Schema<?> ps && ps instanceof ArraySchema arr2) {
                    return arr2.getItems();
                }
            }
        }
        return null;
    }

    //  UTILS
    private static String paramConstraints(Parameter p) {
        if (p == null) return "";
        Schema<?> s = p.getSchema();
        String name = nz(p.getName()).toLowerCase(Locale.ROOT);
        StringJoiner j = new StringJoiner(", ");

        if ("page".equals(name)) j.add("≥ 0");
        if ("size".equals(name)) j.add("1..100");

        if (s != null) {
            if (s.getMinimum() != null) {
                String op = Boolean.TRUE.equals(s.getExclusiveMinimum()) ? ">" : "≥";
                j.add(op + " " + s.getMinimum());
            }
            if (s.getMaximum() != null) {
                String op = Boolean.TRUE.equals(s.getExclusiveMaximum()) ? "<" : "≤";
                j.add(op + " " + s.getMaximum());
            }
            if (s.getMinLength() != null) j.add("minLen " + s.getMinLength());
            if (s.getMaxLength() != null) j.add("maxLen " + s.getMaxLength());
            if (s.getPattern() != null && !s.getPattern().isBlank()) j.add("pattern /" + s.getPattern() + "/");

            if (s instanceof ArraySchema arr) {
                if (arr.getMinItems() != null) j.add("minItems " + arr.getMinItems());
                if (arr.getMaxItems() != null) j.add("maxItems " + arr.getMaxItems());
            }

            if (s.getEnum() != null && !s.getEnum().isEmpty()) {
                String values = s.getEnum().stream()
                        .limit(6)
                        .map(String::valueOf)
                        .reduce((a, b) -> a + ", " + b)
                        .orElse("");
                j.add("enum {" + values + (s.getEnum().size() > 6 ? ", …" : "") + "}");
            }

            if (s.getFormat() != null && !s.getFormat().isBlank()) j.add("format " + s.getFormat());
            if (s.getDefault() != null) j.add("default " + s.getDefault());
            if (s.getExample() != null) j.add("example " + s.getExample());
        }

        if (p.getExample() != null) j.add("example " + p.getExample());

        return j.toString();
    }

    private boolean isBeginnerLevel(String level) {
        if (level == null) return false;
        String v = level.trim().toLowerCase(Locale.ROOT);
        return "beginner".equals(v) || "początkujący".equals(v);
    }

    private boolean shouldShowResponseForBeginner(String code, String level) {
        if (!isBeginnerLevel(level)) {
            return true;
        }
        return switch (code) {
            case "200", "201", "204",
                 "400", "401", "403", "404" -> true;
            default -> false;
        };
    }

}
