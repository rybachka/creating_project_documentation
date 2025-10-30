package com.mariia.javaapi.docs;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.responses.ApiResponse;

import java.util.*;
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
            "file","avatar","avatarFile","upload","content"
    );

    public void apply(OpenAPI api) {
        if (api == null || api.getPaths() == null) return;

        for (Map.Entry<String, io.swagger.v3.oas.models.PathItem> e : api.getPaths().entrySet()) {
            final String path = e.getKey();
            final io.swagger.v3.oas.models.PathItem pi = e.getValue();
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

    private void processOperation(String path, String method, Operation op, io.swagger.v3.oas.models.PathItem pi) {
        if (op == null) return;

        // 1) Placeholdery → usuń lub podmień krótkim opisem
        scrubPlaceholders(op, path, method);

        // 2) Deduplikacja summary/description
        dedupeSummaryDescription(op);

        // 3) Query vs Body (jeśli jest body, usuwamy „request/payload…” z query)
        cleanupQueryVsBody(method, op);

        // 4) Normalizacja examples/cURL
        normalizeCurlExamples(op);

        // 5) Kody odpowiedzi (POST -> 201 gdy brak sensownej treści; DELETE -> 204)
        fixStatusesByHeuristics(method, op);
    }

    // ————— 1) Placeholdery —————
    private void scrubPlaceholders(Operation op, String path, String method) {
        String s = safe(op.getSummary());
        String d = safe(op.getDescription());

        if (isPlaceholder(s)) s = "";
        if (isPlaceholder(d)) d = "";

        // Jeżeli oba puste po czyszczeniu — zbuduj zwięzły opis z sygnatury
        if (s.isBlank() && d.isBlank()) {
            String base = humanizeFromPath(method, path);
            s = base;
            d = ""; // pojedyncze zdanie zostawiamy jako summary; description niepotrzebny
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
        // bardzo prosty humanizer
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

    // ————— 2) Deduplikacja summary/description —————
    private void dedupeSummaryDescription(Operation op) {
        String summary = safe(op.getSummary());
        String desc = safe(op.getDescription());

        if (summary.isBlank() && desc.isBlank()) return;

        if (summary.isBlank() && !desc.isBlank()) {
            // summary = pierwsze zdanie opisu
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
            // zostaw tylko summary
            op.setDescription(null);
            return;
        }

        // obie istnieją — jeśli równe lub description zaczyna się summary → skróć
        String firstSentence = "";
        {
            String[] sentences = splitSentences(desc);
            if (sentences.length > 0) firstSentence = (sentences[0] + ".").trim();
        }

        if (summary.equals(desc) || summary.equals(firstSentence) || desc.startsWith(summary)) {
            String[] sentences = splitSentences(desc);
            if (sentences.length <= 1) {
                op.setDescription(null); // jedno zdanie → tylko summary
            } else {
                op.setSummary((sentences[0] + ".").trim());
                op.setDescription(desc.trim()); // pełny opis zostaje
            }
        }
    }

    private String[] splitSentences(String text) {
        if (text == null || text.isBlank()) return new String[0];
        // ostrożny split: kropka + spacja / koniec
        return text.trim().split("\\.\\s+|\\.$");
    }

    // ————— 3) Query vs Body —————
    private void cleanupQueryVsBody(String method, Operation op) {
        boolean isWrite = "POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method);

        RequestBody rb = op.getRequestBody();
        Content bodyContent = (rb != null) ? rb.getContent() : null;
        boolean hasJsonBody = bodyContent != null && bodyContent.get("application/json") != null;

        if (hasJsonBody) {
            // usuń parametry w query, które wyglądają jak „body”
            List<Parameter> params = op.getParameters();
            if (params != null && !params.isEmpty()) {
                List<Parameter> kept = new ArrayList<>();
                for (Parameter p : params) {
                    if (p == null) continue;
                    String loc = safe(p.getIn());
                    String name = safe(p.getName());
                    if ("query".equals(loc) && BODYish_NAMES.contains(name)) {
                        // pomijamy (usuwamy z query)
                    } else {
                        kept.add(p);
                    }
                }
                op.setParameters(kept.isEmpty() ? null : kept);
            }
        } else if (isWrite) {
            // brak body, a metoda jest „write” — jeżeli w query są parametry „request/payload…”
            // utworzymy puste JSON body, a parametry zostawimy w query (bez migracji treści — to by wymagało mapowania typów).
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
                    c.addMediaType("application/json", new MediaType().schema(new Schema<>().type("object")));
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
            String multi = toMultilineCurl(s);
            if (!multi.isBlank()) normalized.add(multi);
        }

        if (!normalized.isEmpty()) {
            op.addExtension("x-request-examples", normalized);
        }
    }

    private String toMultilineCurl(String s) {
        String t = s.trim();
        if (t.isEmpty()) return t;
        if (!t.startsWith("curl")) return t;

        // rozbij po -H / -d / -X / 'http'
        // proste heurystyki: wstawiamy backslash na końcach linii
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

    // ————— 5) Statusy dla odpowiedzi —————
    private void fixStatusesByHeuristics(String method, Operation op) {
        ApiResponses rs = op.getResponses();
        if (rs == null || rs.isEmpty()) return;

        // Jeżeli POST i tylko 200 z pustą treścią → przemapuj na 201 Created
        if ("POST".equalsIgnoreCase(method)) {
            ApiResponse r200 = rs.get("200");
            if (r200 != null && isEffectivelyEmpty(r200)) {
                ApiResponse created = copyResponse(r200);
                if (created.getDescription() == null || created.getDescription().isBlank()) {
                    created.setDescription("Created");
                }
                rs.remove("200");
                rs.addApiResponse("201", created);
            }
        }

        // Jeżeli DELETE i odpowiedź ma puste body → pozwól 204 No Content
        if ("DELETE".equalsIgnoreCase(method)) {
            ApiResponse r200 = rs.get("200");
            if (r200 != null && isEffectivelyEmpty(r200)) {
                ApiResponse noContent = new ApiResponse().description("No Content");
                rs.remove("200");
                rs.addApiResponse("204", noContent);
            }
        }
    }

    private boolean isEffectivelyEmpty(ApiResponse r) {
        if (r == null) return true;
        Content c = r.getContent();
        if (c == null || c.isEmpty()) return true;
        MediaType mt = c.get("application/json");
        if (mt == null) return true;
        Schema<?> s = mt.getSchema();
        // bardzo ostrożna heurystyka: pusty object schema lub brak example
        return (s == null) || ("object".equalsIgnoreCase(String.valueOf(s.getType())) && (mt.getExample() == null));
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

    // ————— Utils —————
    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }
    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
