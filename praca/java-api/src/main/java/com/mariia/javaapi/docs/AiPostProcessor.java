package com.mariia.javaapi.docs;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.responses.ApiResponse;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AiPostProcessor {

    // ————— Konfiguracja —————
    private static final List<Pattern> PLACEHOLDER_PATTERNS = List.of(
            Pattern.compile("string\\s*\\(\\s*1\\s*[-–]\\s*3\\s*zdania.*\\)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE),
            Pattern.compile("wpisz\\s+opis.*", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE),
            Pattern.compile("<extra_id_\\d+>", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
    );

    private static final Set<String> BODYish_NAMES = Set.of(
            "request","payload","body","dto","data","json",
            "file","avatar","avatarFile","avatarfile","upload","content"
    );

    public void apply(OpenAPI api) {
        if (api == null || api.getPaths() == null) return;

        for (Map.Entry<String, PathItem> e : api.getPaths().entrySet()) {
            final String path = e.getKey();
            final PathItem pi = e.getValue();
            if (pi == null) continue;

            processOperation(path, "GET",    pi.getGet(),    pi);
            processOperation(path, "POST",   pi.getPost(),   pi);
            processOperation(path, "PUT",    pi.getPut(),    pi);
            processOperation(path, "PATCH",  pi.getPatch(),  pi);
            processOperation(path, "DELETE", pi.getDelete(), pi);
            processOperation(path, "HEAD",   pi.getHead(),   pi);
            processOperation(path, "OPTIONS",pi.getOptions(),pi);
            processOperation(path, "TRACE",  pi.getTrace(),  pi);
        }
    }

    private void processOperation(String path, String method, Operation op, PathItem pi) {
        if (op == null) return;

        // 1) Placeholdery → usuń / skróć
        scrubPlaceholders(op, path, method);

        // 2) Deduplikacja summary/description
        dedupeSummaryDescription(op);

        // 3) Query vs Body (jeśli jest body, usuń „request/payload…” z query)
        cleanupQueryVsBody(method, op);

        // 4) Normalizacja cURL
        normalizeCurlExamples(op);

        // 4b) Walidacja cURL + filtr (po normalizacji)
        validateAndFilterCurlExamples(method, path, op);

        // 5) Kody odpowiedzi (POST→201 gdy pusto; DELETE→204)
        fixStatusesByHeuristics(method, op);

        // 6) Delikatne doprecyzowania opisów dziedzinowych (orders/users) + sensowny summary
        enrichDomainDescriptions(method, path, op);
    }

    // ————— 1) Placeholdery —————
    private void scrubPlaceholders(Operation op, String path, String method) {
        String s = safe(op.getSummary());
        String d = safe(op.getDescription());

        if (isPlaceholder(s)) s = "";
        if (isPlaceholder(d)) d = "";

        if (s.isBlank() && d.isBlank()) {
            String base = humanizeFromPath(method, path);
            s = base;
            d = "";
        }
        if (!Objects.equals(s, op.getSummary())) op.setSummary(blankToNull(s));
        if (!Objects.equals(d, op.getDescription())) op.setDescription(blankToNull(d));
    }

    private boolean isPlaceholder(String txt) {
        if (txt == null) return false;
        String t = txt.trim();
        if (t.isEmpty()) return false;
        for (Pattern p : PLACEHOLDER_PATTERNS) {
            if (p.matcher(t).find()) return true;
        }
        return false;
    }

    private String humanizeFromPath(String method, String path) {
        String p = path == null ? "/" : path.trim();
        if (p.equals("/")) return method + " /";
        if (method.equalsIgnoreCase("GET")) {
            if (p.endsWith("}")) return "Pobierz zasób po identyfikatorze.";
            return "Pobierz listę zasobów.";
        }
        if (method.equalsIgnoreCase("POST")) return "Utwórz nowy zasób.";
        if (method.equalsIgnoreCase("PUT")) return "Całkowicie zaktualizuj zasób.";
        if (method.equalsIgnoreCase("PATCH")) return "Częściowo zaktualizuj zasób.";
        if (method.equalsIgnoreCase("DELETE")) return "Usuń zasób.";
        return method + " " + path;
    }

    // ————— 2) Deduplikacja —————
    private void dedupeSummaryDescription(Operation op) {
        String summary = safe(op.getSummary());
        String desc = safe(op.getDescription());

        if (summary.isBlank() && desc.isBlank()) return;

        if (summary.isBlank() && !desc.isBlank()) {
            String[] sentences = splitSentences(desc);
            if (sentences.length <= 1) {
                op.setSummary(desc.trim());
                op.setDescription(null);
                return;
            } else {
                op.setSummary((sentences[0] + ".").trim());
                op.setDescription(desc.trim());
                return;
            }
        }

        if (!summary.isBlank() && desc.isBlank()) {
            op.setDescription(null);
            return;
        }

        String firstSentence = "";
        {
            String[] sentences = splitSentences(desc);
            if (sentences.length > 0) firstSentence = (sentences[0] + ".").trim();
        }

        if (summary.equals(desc) || summary.equals(firstSentence) || desc.startsWith(summary)) {
            String[] sentences = splitSentences(desc);
            if (sentences.length <= 1) {
                op.setDescription(null);
            } else {
                op.setSummary((sentences[0] + ".").trim());
                op.setDescription(desc.trim());
            }
        }
    }

    private String[] splitSentences(String text) {
        if (text == null || text.isBlank()) return new String[0];
        return text.trim().split("\\.\\s+|\\.$");
    }

    // ————— 3) Query vs Body —————
    private void cleanupQueryVsBody(String method, Operation op) {
        boolean isWrite = "POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method);

        RequestBody rb = op.getRequestBody();
        Content bodyContent = (rb != null) ? rb.getContent() : null;
        boolean hasJsonBody = bodyContent != null && bodyContent.get("application/json") != null;

        if (hasJsonBody) {
            List<Parameter> params = op.getParameters();
            if (params != null && !params.isEmpty()) {
                List<Parameter> kept = new ArrayList<>();
                for (Parameter p : params) {
                    if (p == null) continue;
                    String loc = safe(p.getIn());
                    String name = safe(p.getName());
                    if ("query".equals(loc) && BODYish_NAMES.contains(name)) {
                        // drop
                    } else {
                        kept.add(p);
                    }
                }
                op.setParameters(kept.isEmpty() ? null : kept);
            }
        } else if (isWrite) {
            if (op.getParameters() != null) {
                boolean foundBodyishInQuery = false;
                for (Parameter p : op.getParameters()) {
                    if (p == null) continue;
                    if ("query".equalsIgnoreCase(safe(p.getIn())) && BODYish_NAMES.contains(safe(p.getName()))) {
                        foundBodyishInQuery = true;
                        break;
                    }
                }
                if (foundBodyishInQuery) {
                    RequestBody newRb = new RequestBody();
                    Content c = new Content();
                    c.addMediaType("application/json", new io.swagger.v3.oas.models.media.MediaType().schema(new Schema<>().type("object")));
                    newRb.setContent(c);
                    op.setRequestBody(newRb);
                }
            }
        }
    }

    // ————— 4) Normalizacja cURL —————
    @SuppressWarnings("unchecked")
    private void normalizeCurlExamples(Operation op) {
        if (op.getExtensions() == null) return;
        Object raw = op.getExtensions().get("x-request-examples");
        if (!(raw instanceof List)) return;

        List<?> list = (List<?>) raw;
        if (list.isEmpty()) return;

        List<String> normalized = new ArrayList<>();
        for (Object o : list) {
            if (o == null) continue;
            String s = Objects.toString(o, "");
            s = s.replace("\\n", "\n").replace("\\\"", "\"").trim();
            String t = toMultilineCurl(s);
            if (!t.isBlank()) normalized.add(t);
        }
        if (!normalized.isEmpty()) {
            op.addExtension("x-request-examples", normalized);
        }
    }

    private String toMultilineCurl(String s) {
        String t = s.trim();
        if (t.isEmpty()) return t;
        if (!t.startsWith("curl")) return t;

        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        String[] tokens = t.split("\\s+");
        for (String tok : tokens) {
            if (tok.equals("-H") || tok.equals("-d") || tok.equals("-X") || tok.startsWith("http")) {
                if (cur.length() > 0) {
                    parts.add(cur.toString().trim());
                    cur.setLength(0);
                }
            }
            if (cur.length() > 0) cur.append(' ');
            cur.append(tok);
        }
        if (cur.length() > 0) parts.add(cur.toString().trim());

        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            out.append(parts.get(i));
            if (i < parts.size() - 1) out.append(" \\\n  ");
        }
        return out.toString();
    }

    // ————— 4b) Validator cURL + filtr —————
    @SuppressWarnings("unchecked")
    private void validateAndFilterCurlExamples(String method, String pathTemplate, Operation op) {
        if (op.getExtensions() == null) return;
        Object raw = op.getExtensions().get("x-request-examples");
        if (!(raw instanceof List)) return;
        List<?> src = (List<?>) raw;
        if (src.isEmpty()) return;

        List<String> ok = new ArrayList<>();
        for (Object o : src) {
            String curl = Objects.toString(o, "").trim();
            if (curl.isEmpty()) continue;
            if (isValidCurlExample(curl, method, pathTemplate)) ok.add(curl);
        }
        if (ok.isEmpty()) {
            // usuń całkowicie, żeby PDF nie renderował „śmieci”
            op.getExtensions().remove("x-request-examples");
        } else {
            op.addExtension("x-request-examples", ok);
        }
    }

    private boolean isValidCurlExample(String curl, String expectedMethod, String pathTemplate) {
        // 1) Zakaz '#' w host/path
        if (curl.contains("://") && curl.contains("#")) return false;

        // 2) Ustal metodę z -X <METHOD> lub domyślnie GET
        String method = "GET";
        Matcher m = Pattern.compile("(?i)\\s-?X\\s+([A-Z]+)").matcher(curl);
        if (m.find()) method = m.group(1).toUpperCase(Locale.ROOT);
        if (!curl.contains(" -X ") && curl.toLowerCase(Locale.ROOT).contains("--request")) {
            Matcher m2 = Pattern.compile("(?i)--request\\s+([A-Z]+)").matcher(curl);
            if (m2.find()) method = m2.group(1).toUpperCase(Locale.ROOT);
        }
        if (!method.equalsIgnoreCase(expectedMethod)) return false;

        // 3) Wyciągnij URL
        Matcher mu = Pattern.compile("(https?://\\S+)").matcher(curl);
        if (!mu.find()) return false;
        String url = mu.group(1);

        // 4) Jeśli pathTemplate zawiera {var}, wymagamy segmentu zamiast query '?id='
        // Budujemy regex na podstawie template: {xxx} -> ([^/]+)
        String regexPath = Pattern.quote(pathTemplate);
        regexPath = regexPath.replace("\\{", "{").replace("}", "}");
        regexPath = regexPath.replaceAll("\\{[^}/]+}", "([^/]+)");
        // Pozwól na prefix bazowego URL (domena itp.)
        // np. https://api.example.com + /api/orders/123/items
        String mustMatch = "https?://[^\\s]+?" + regexPath + "(?:\\?|$)";
        if (!url.matches(mustMatch)) return false;

        // 5) Dodatkowo: gdy w template występuje {id|orderId|userId}, zabroń '?id=' dla tego endpointu
        if (pathTemplate.matches(".*\\{[^}]*id[^}]*}.*") && url.matches(".*\\?.*\\bid=.*")) return false;

        return true;
    }

    // ————— 5) Statusy —————
    private void fixStatusesByHeuristics(String method, Operation op) {
        ApiResponses rs = op.getResponses();
        if (rs == null || rs.isEmpty()) return;

        // POST: 200 (puste) → 201
        if ("POST".equalsIgnoreCase(method)) {
            ApiResponse r200 = rs.get("200");
            if (r200 != null && isEffectivelyEmpty(r200)) {
                ApiResponse created = copyResponse(r200);
                if (safe(created.getDescription()).isBlank()) created.setDescription("Created");
                rs.remove("200");
                rs.addApiResponse("201", created);
            }
        }

        // DELETE: puste body → 204; usuń pozostałe 2xx
        if ("DELETE".equalsIgnoreCase(method)) {
            ApiResponse r200 = rs.get("200");
            if (r200 != null && isEffectivelyEmpty(r200)) {
                rs.remove("200");
            }
            if (rs.get("204") == null) {
                rs.addApiResponse("204", new ApiResponse().description("No Content"));
            }
            rs.keySet().removeIf(k -> k.startsWith("2") && !"204".equals(k));
        }
    }

    // ————— 6) Delikatne doprecyzowanie opisów —————
    private void enrichDomainDescriptions(String method, String path, Operation op) {
        String s = safe(op.getSummary());
        String d = safe(op.getDescription());

        boolean isOrders = path != null && path.contains("/api/orders");
        boolean isUsers  = path != null && path.contains("/api/users");

        if (isOrders) {
            if (method.equalsIgnoreCase("GET") && path.endsWith("}")) {
                if (s.isBlank()) op.setSummary("Pobierz zamówienie po identyfikatorze (UUID).");
            }
            if (method.equalsIgnoreCase("DELETE")) {
                if (s.isBlank()) op.setSummary("Usuń zamówienie po identyfikatorze (UUID).");
            }
            if (method.equalsIgnoreCase("POST") && path.endsWith("/items")) {
                if (s.isBlank()) op.setSummary("Dodaj pozycję do zamówienia.");
                if (d.isBlank()) op.setDescription("Tworzy nową pozycję w zamówieniu wskazanym przez {orderId}. Prześlij JSON z polami „sku” i „qty”.");
            }
        }
        if (isUsers) {
            if (method.equalsIgnoreCase("GET") && path.endsWith("}")) {
                if (s.isBlank()) op.setSummary("Pobierz użytkownika po identyfikatorze (UUID).");
            }
            if (method.equalsIgnoreCase("POST") && path.endsWith("/users")) {
                if (s.isBlank()) op.setSummary("Utwórz nowego użytkownika.");
                if (d.isBlank()) op.setDescription("Tworzy użytkownika na podstawie JSON w formacie CreateUserRequest.");
            }
        }

        // jeżeli summary puste, spróbuj z 1. zdania description
        if ((op.getSummary() == null || op.getSummary().isBlank()) && op.getDescription() != null && !op.getDescription().isBlank()) {
            String fs = firstSentenceOf(op.getDescription());
            if (!fs.isBlank()) op.setSummary(trim100(fs + "."));
        }
    }

    // ————— Utils —————
    private boolean isEffectivelyEmpty(ApiResponse r) {
        if (r == null) return true;
        Content c = r.getContent();
        if (c == null || c.isEmpty()) return true;
        MediaType mt = c.get("application/json");
        if (mt == null) return true;
        Schema<?> s = mt.getSchema();
        return (s == null) || ("object".equalsIgnoreCase(String.valueOf(s.getType())) && mt.getExample() == null);
    }

    private ApiResponse copyResponse(ApiResponse src) {
        if (src == null) return new ApiResponse();
        ApiResponse dst = new ApiResponse();
        dst.set$ref(src.get$ref());
        dst.setDescription(src.getDescription());
        if (src.getContent() != null) {
            Content dstC = new Content();
            for (Map.Entry<String, MediaType> e : src.getContent().entrySet()) {
                String k = e.getKey();
                MediaType v = e.getValue();
                MediaType nv = new MediaType();
                nv.setSchema(v.getSchema());
                nv.setExample(v.getExample());
                nv.setExamples(v.getExamples());
                nv.setEncoding(v.getEncoding());
                nv.setExtensions(v.getExtensions());
                dstC.addMediaType(k, nv);
            }
            dst.setContent(dstC);
        }
        dst.setHeaders(src.getHeaders());
        dst.setLinks(src.getLinks());
        dst.setExtensions(src.getExtensions());
        return dst;
    }

    private static String safe(String s) { return s == null ? "" : s.trim(); }
    private static String blankToNull(String s) { return (s == null || s.isBlank()) ? null : s; }
    private static String trim100(String s) { return (s != null && s.length() > 100) ? s.substring(0, 100) + "…" : s; }
    private static String firstSentenceOf(String text) {
        if (text == null) return "";
        String t = text.trim();
        int idx = t.indexOf('.');
        if (idx < 0) return t;
        return t.substring(0, idx).trim();
    }
}
