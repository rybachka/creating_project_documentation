package com.mariia.javaapi.code;

import com.mariia.javaapi.code.ir.EndpointIR;
import com.mariia.javaapi.code.ir.ParamIR;
import io.swagger.v3.core.util.Yaml;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.stream.Stream;
import java.util.regex.Pattern;


@Service
public class CodeToDocsService {

    private final WebClient nlp;
    private final Duration timeout = Duration.ofSeconds(600);

    public CodeToDocsService(@Qualifier("nlpClient") WebClient nlp) {
        this.nlp = nlp;
    }

    private static final List<Pattern> PLACEHOLDER_PATTERNS = List.of(
        Pattern.compile("string\\s*\\(\\s*1\\s*[-–]\\s*3\\s*zdania.*\\)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE),
        Pattern.compile("wpisz\\s+opis.*", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE),
        Pattern.compile("<extra_id_\\d+>", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
    );
    private static String safe(String s) { return s == null ? "" : s.trim(); }
    private static String blankToNull(String s) { return (s == null || s.isBlank()) ? null : s; }

    public Path generateYamlFromCode(
            List<EndpointIR> eps,
            String projectName,
            String level,
            Path outFile,
            Path projectRoot
    ) throws Exception {

        System.out.println("[from-code] level=" + level);

        OpenAPI api = new OpenAPI();
        Info info = new Info()
                .title(projectName + "-API")
                .version("1.0.0");

        Map<String, Object> infoExt = new LinkedHashMap<>();
        String audience = level; 

        infoExt.put("x-user-level", audience);
        infoExt.put("x-project-name", projectName); 

        info.setExtensions(infoExt);

        api.setInfo(info);
        api.setPaths(new Paths());
        api.setComponents(new Components());
        Components comps = api.getComponents();
        if (comps.getSchemas() == null) comps.setSchemas(new LinkedHashMap<>());
        if (comps.getParameters() == null) comps.setParameters(new LinkedHashMap<>());
        if (comps.getSecuritySchemes() == null) comps.setSecuritySchemes(new LinkedHashMap<>());

        //ensureApiErrorComponent(api);//на кінці
        //następne metody będą zmieniani 
        //ensureBearerAuth(api); //security schemes
        //applyGlobalSecurity(api); //на початку

        // DTOs
        Path projectPath = projectRoot;
        System.out.println("[DTO] użyty katalog do skanowania: " + projectPath);
        try {
            JavaDtoParser dtoParser = new JavaDtoParser();
            Map<String, Schema> dtoSchemas = dtoParser.parseDtos(projectPath);
            if (!dtoSchemas.isEmpty()) {
                api.getComponents().getSchemas().putAll(dtoSchemas);
                System.out.println("[DTO] dodano do components/schemas: " + dtoSchemas.keySet());
            } else {
                System.out.println("[DTO] nie znaleziono żadnych DTO – components/schemas będzie puste.");
            }
        } catch (Exception e) {
            System.err.println("[WARN] Pomijam parsowanie DTO: " + e.getMessage());
        }

        // Przykładowy hack pod CreateUserRequest: dopilnuj wymaganych pól
        Schema<?> cur = api.getComponents().getSchemas().get("CreateUserRequest");
        if (cur instanceof ObjectSchema os) {
            Set<String> req = new LinkedHashSet<>();
            if (os.getRequired() != null) req.addAll(os.getRequired());
            req.add("name");
            req.add("email");
            os.setRequired(new ArrayList<>(req));
        }

        // Endpointy
        for (EndpointIR ep : eps) {

            PathItem pi = api.getPaths().get(ep.path);
            if (pi == null) {
                pi = new PathItem();
                api.getPaths().addPathItem(ep.path, pi);
            }

            io.swagger.v3.oas.models.Operation op = new io.swagger.v3.oas.models.Operation();
            op.setOperationId(ep.operationId);

            // SECURITY per operacja
            // Map<String, Object> opExt = new LinkedHashMap<>();
            // if (isPublicEndpoint(ep)) {
            //     op.setSecurity(Collections.emptyList());
            //     opExt.put("x-security", "public");
            // } else {
            //     opExt.put("x-security", "bearerAuth");
            // }
            // opExt.put("x-user-level", audience);
            // op.setExtensions(opExt);

            // Extensions per operation – only user level, no guessed security
            Map<String, Object> opExt = new LinkedHashMap<>();
            opExt.put("x-user-level", audience);
            op.setExtensions(opExt);


            // NLP /describe – zawsze AI (ollama)
            Map<String, Object> nlpRes = callNlp(buildNlpBody(ep), level);

            applyParamsAndRequestBody(op, ep);//"Parametry"
            applyAiDescriptionsAndExamples(op, ep, nlpRes);
            ensureDefaultResponses(op, ep, nlpRes);////status 200/201 w tabeli opis-ok 
            //attachStandardErrors(op, isWriteMethod(ep.http));//wszystkie odpowiedzi errors od 400 do 404

            // request/response/examples fallback
            ensureRequestExample(api, op);//"Przyklad zadania"
            ensureHappyResponseExample(api, op, ep);
            ensureCurlExample(op, ep);//"Przykłady wywołań"
            scrubPlaceholders(op, ep.path, String.valueOf(ep.http));

            // podpięcie pod PathItem wg metody
            switch (String.valueOf(ep.http).toUpperCase(Locale.ROOT)) {
                case "GET"    -> pi.setGet(op);
                case "POST"   -> pi.setPost(op);
                case "PUT"    -> pi.setPut(op);
                case "PATCH"  -> pi.setPatch(op);
                case "DELETE" -> pi.setDelete(op);
                default       -> pi.setGet(op);
            }
        }

        // zapis YAML
        Files.createDirectories(outFile.getParent());
        String yaml = Yaml.mapper().writeValueAsString(api);
        Files.writeString(outFile, yaml);

        return outFile;
    }

    // NLP CALLS
    @SuppressWarnings("unchecked")
    private Map<String, Object> callNlp(Map<String, Object> body, String level) {
        try {
            String audience = level;
            String uri = "/describe?mode=ollama&audience=" + audience;
            return nlp.post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(timeout);
        } catch (Exception e) {
            System.err.println("[NLP] Błąd połączenia z NLP: " + e.getMessage());
            return Collections.emptyMap();
        }
    }

    ///zawiera wszystko, co endpoint o sobie wie (operationId, metoda, ścieżka, parametry, zwrotka, javadoc, notatki),
    //jest dokładnie tym, co FastAPI przyjmuje jako DescribeIn i używa do zbudowania promptu.
    private Map<String, Object> buildNlpBody(EndpointIR ep) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("symbol", ep.operationId);
        body.put("kind", "endpoint");
        body.put("signature", ep.http + " " + ep.path);
        body.put("comment", ep.description == null ? "" : ep.description);
        body.put("http", ep.http);
        body.put("pathTemplate", ep.path);
        body.put("javadoc", ep.javadoc == null ? "" : ep.javadoc);
        body.put("notes", ep.notes == null ? List.of() : ep.notes);
        body.put("todos", ep.todos == null ? List.of() : ep.todos);
        body.put("language", "pl");

        List<Map<String, Object>> nlpParams = new ArrayList<>();
        for (ParamIR p : ep.params) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", p.name);
            m.put("in", p.in);
            m.put("type", p.type);
            m.put("required", p.required);
            m.put("description", p.description == null ? "" : p.description);
            nlpParams.add(m);
        }
        body.put("params", nlpParams);

        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("type", ep.returns != null ? ep.returns.type : "void");
        ret.put("description", ep.returns != null
                ? (ep.returns.description == null ? "" : ep.returns.description)
                : "");
        body.put("returns", ret);

        return body;
    }

    //NLP opisuje endpoint → mediumDescription i notes trafiają do summary/description/x-impl-notes.
    //NLP generuje curl → ląduje w x-request-examples.
    //NLP generuje przykładową odpowiedź → ląduje w responses[status].content[*].example + schema.
    //Jeśli AI nic nie da w examples → później wchodzą fallbacki ensureRequestExample, ensureHappyResponseExample, ensureCurlExample.
    @SuppressWarnings("unchecked")
    private void applyAiDescriptionsAndExamples(io.swagger.v3.oas.models.Operation op,
                                                EndpointIR ep,
                                                Map<String, Object> nlpRes) {
        if (nlpRes == null) {
            return;
        }
        // mediumDescription -> description + summary
        String medD = asStr(nlpRes.get("mediumDescription"));
        if (medD != null && !medD.isBlank()) {
            String trimmed = medD.trim();
            if (op.getDescription() == null || op.getDescription().isBlank()) {
                op.setDescription(trimmed);
            }
            String fs = firstSentenceOf(trimmed);
            if (!fs.isBlank() && (op.getSummary() == null || op.getSummary().isBlank())) {
                op.setSummary(trim(fs, 400));
            }
        }
        // notes -> x-impl-notes
        Object notesObj = nlpRes.get("notes");
        if (notesObj instanceof List<?>) {
            boolean alreadyHas = op.getExtensions()!=null && op.getExtensions().containsKey("x-impl-notes");
            if (!alreadyHas) {
                List<String> implNotes = ((List<?>) notesObj).stream()
                        .map(x -> Objects.toString(x, ""))
                        .map(String::trim)
                        .filter(s -> !s.isBlank())
                        .limit(20)
                        .toList();
                if (!implNotes.isEmpty()) {
                    op.addExtension("x-impl-notes", implNotes);
                }
            }
        }
        // examples
        Object examplesObj = nlpRes.get("examples");
        if (!(examplesObj instanceof Map<?, ?> exMap)) {
            return;
        }

        // requests -> x-request-examples
        Object reqsObj = exMap.get("requests");
        if (reqsObj instanceof List<?>) {
            List<String> curls = new ArrayList<>();
            for (Object r : (List<?>) reqsObj) {
                if (r instanceof Map<?, ?>) {
                    Object curl = ((Map<?, ?>) r).get("curl");
                    if (curl != null && !curl.toString().isBlank()) {
                        curls.add(normalizeCurl(curl.toString()));
                    }
                } else if (r instanceof String) {
                    String curl = ((String) r).trim();
                    if (!curl.isBlank()) {
                        curls.add(normalizeCurl(curl));
                    }
                }
            }
            if (!curls.isEmpty()) {
                Object already = op.getExtensions()==null ? null : op.getExtensions().get("x-request-examples");
                boolean empty = !(already instanceof List) || ((List<?>)already).isEmpty();
                if (empty) {
                    op.addExtension("x-request-examples", curls);
                }
            }
        }

        // response example
        Object respObj = exMap.get("response");
        if (respObj instanceof Map<?, ?> rm) {
            int status = 200;
            Object st = rm.get("status");
            if (st != null) {
                try {
                    status = Integer.parseInt(st.toString());
                } catch (NumberFormatException ignore) {
                }
            }
            Object bodyEx = rm.get("body");

            if (bodyEx != null || status == 204) {
                ApiResponses rs = (op.getResponses() == null) ? new ApiResponses() : op.getResponses();
                String code = String.valueOf(status);

                ApiResponse ar = rs.get(code);
                if (ar == null) {
                    ar = new ApiResponse().description(status == 204 ? "No Content" : "OK");
                }

                Content c = (ar.getContent() == null) ? new Content() : ar.getContent();
                io.swagger.v3.oas.models.media.MediaType mt =
                        (c.get(MediaType.APPLICATION_JSON_VALUE) == null)
                                ? new io.swagger.v3.oas.models.media.MediaType()
                                : c.get(MediaType.APPLICATION_JSON_VALUE);

                if (status != 204 && bodyEx != null && mt.getExample() == null) {
                    mt.setExample(bodyEx);
                }
                if (mt.getSchema() == null && status != 204) {
                    mt.setSchema(schemaForType(ep.returns != null ? ep.returns.type : null));
                }

                if (status != 204) {
                    c.addMediaType(MediaType.APPLICATION_JSON_VALUE, mt);
                    ar.setContent(c);
                } else {
                    ar.setContent(null);
                }

                rs.addApiResponse(code, ar);
                op.setResponses(rs);
            }
        }
    }

    // PARAMS / BODY / RESPONSES
    //in="body" → requestBody z JSON-em,
    //inne (path, query, header) → parameters[].
    //To jest czysto techniczna warstwa: „jak endpoint wygląda formalnie”.
    //uzupelnia op RequestBody oraz parameters
    //w pdf tabela "Parametry"
    private void applyParamsAndRequestBody(io.swagger.v3.oas.models.Operation op, EndpointIR ep) {
        if (ep.params == null || ep.params.isEmpty()) return;

        List<Parameter> params = new ArrayList<>();
        for (ParamIR p : ep.params) {
            if ("body".equalsIgnoreCase(p.in)) {
                RequestBody rb = new RequestBody()
                        .description(p.description)
                        .required(p.required)
                        .content(new Content().addMediaType(
                                MediaType.APPLICATION_JSON_VALUE,
                                new io.swagger.v3.oas.models.media.MediaType()
                                        .schema(schemaForType(p.type))
                        ));
                op.setRequestBody(rb);
            } else {
                Parameter par = new Parameter()
                        .name(p.name)
                        .in(p.in)
                        .required(p.required)
                        .description(p.description);
                par.setSchema(schemaForType(p.type));
                params.add(par);
            }
        }
        if (!params.isEmpty()) {
            op.setParameters(params);
        }
    }

    //jesli applyAiDescriptionsAndExamples oraz NLP nie dadza sensowniej response
    //dla DELETE i void → daje 204 No Content,
    //dla reszty → daje 200 z JSON-em o typie wynikającym z ep.returns,
    //dzięki temu każdy endpoint ma chociaż sensowną domyślną odpowiedź
    //status 200/201 w tabeli opis-ok 
    private void ensureDefaultResponses(io.swagger.v3.oas.models.Operation op,
                                        EndpointIR ep,
                                        Map<String, Object> nlpRes) {
        if (op.getResponses() != null && !op.getResponses().isEmpty()) return;

        ApiResponses rs = new ApiResponses();

        boolean isDelete = "DELETE".equalsIgnoreCase(String.valueOf(ep.http));
        boolean isVoid = ep.returns == null
                || ep.returns.type == null
                || ep.returns.type.trim().equals("void")
                || ep.returns.type.trim().equals("Void")
                || ep.returns.type.trim().startsWith("ResponseEntity<Void");

        if (isDelete || isVoid) {
            rs.addApiResponse("204", new ApiResponse().description("No Content"));
        } else {
            ApiResponse ok = new ApiResponse();
            String retDoc = asStr(nlpRes != null ? nlpRes.get("returnDoc") : null);
            ok.setDescription((retDoc != null && !retDoc.isBlank()) ? retDoc : "OK");
            ok.setContent(new Content().addMediaType(
                    MediaType.APPLICATION_JSON_VALUE,
                    new io.swagger.v3.oas.models.media.MediaType()
                            .schema(schemaForType(ep.returns != null ? ep.returns.type : null))
            ));
            rs.addApiResponse("200", ok);
        }
        op.setResponses(rs);
    }

    // PRZYKŁADY
    //Sprawdzamy, czy nie ma jeszcze przykładu (example) dla tego application/json
    //jesli jest-nic nie ruszmay
    //synthExampleNeutral(schema) Na podstawie schematu buduje neutralny przykład JSON
    //mt.setExample(...) Wstawiamy ten wygenerowany przykład jako example requestu.
    //"Przykład żądania" w PDF dla endpointów, które mają requestBody
    private void ensureRequestExample(OpenAPI api,
                                      io.swagger.v3.oas.models.Operation op) {
        if (op.getRequestBody() == null) return;
        Content c = op.getRequestBody().getContent();
        if (c == null) return;
        io.swagger.v3.oas.models.media.MediaType mt = c.get(MediaType.APPLICATION_JSON_VALUE);
        if (mt == null) return;

        if (mt.getSchema() == null) {
            mt.setSchema(new ObjectSchema());
        }

        if (mt.getExample() == null) {
            Schema<?> schema = resolveRefSchema(api, mt.getSchema());
            mt.setExample(synthExampleNeutral(schema));
        }
    }

    @SuppressWarnings("unchecked")
    private Object synthExampleNeutral(Schema<?> s) {
        if (s == null) return Map.of();

        if (s instanceof StringSchema ss) {
            String fmt = ss.getFormat() == null ? "" : ss.getFormat().toLowerCase(Locale.ROOT);
            if ("uuid".equals(fmt))      return "00000000-0000-0000-0000-000000000000";
            if ("email".equals(fmt))     return "user@example.com";
            if ("date-time".equals(fmt)) return "2025-01-01T12:00:00Z";
            if ("date".equals(fmt))      return "2025-01-01";
            if (ss.getEnum() != null && !ss.getEnum().isEmpty()) return ss.getEnum().get(0);
            return "string";
        }
        if (s instanceof IntegerSchema) return 123;
        if (s instanceof NumberSchema)  return 12.34;
        if (s instanceof BooleanSchema) return true;

        if (s instanceof ArraySchema arr) {
            return List.of(synthExampleNeutral(arr.getItems()));
        }

        if (s.get$ref() != null) {
            return Map.of();
        }

        if (s instanceof ObjectSchema || "object".equalsIgnoreCase(String.valueOf(s.getType()))) {
            Map<String, Object> out = new LinkedHashMap<>();
            Object props = s.getProperties();
            if (props instanceof Map<?, ?> m) {
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (e.getKey() != null && e.getValue() instanceof Schema<?> ps) {
                        out.put(String.valueOf(e.getKey()), synthExampleNeutral(ps));
                    }
                }
            }
            return out.isEmpty() ? Map.of() : out;
        }

        return Map.of();
    }

    //Ta metoda to „ostatnia linia obrony”, która pilnuje, 
    //żeby w OpenAPI (a potem w PDF) był sensowny przykład odpowiedzi sukcesu dla endpointu
    private void ensureHappyResponseExample(OpenAPI api,
                                            io.swagger.v3.oas.models.Operation op,
                                            EndpointIR ep) {
        if (op.getResponses() == null || op.getResponses().isEmpty()) return;
        boolean has2xxExample = op.getResponses().entrySet().stream()
        .anyMatch(e -> e.getKey().startsWith("2")
                && e.getValue() != null
                && e.getValue().getContent() != null
                && e.getValue().getContent().get("application/json") != null
                && e.getValue().getContent().get("application/json").getExample() != null);

        if (has2xxExample) {
            // AI albo ktoś inny już dał konkretny przykład – nic nie ruszamy
            return;
        }
        String pick = null;
        if (op.getResponses().get("200") != null) pick = "200";
        else if (op.getResponses().get("201") != null) pick = "201";
        else {
            for (String k : op.getResponses().keySet()) {
                if (k != null && k.startsWith("2")) {
                    pick = k;
                    break;
                }
            }
        }
        if (pick == null) return;

        ApiResponse r = op.getResponses().get(pick);
        if (r == null) return;
        Content rc = (r.getContent() == null) ? new Content() : r.getContent();

        if ("204".equals(pick)) {
            r.setContent(null);
            r.setDescription(firstNonBlank(r.getDescription(), "No Content"));
            op.getResponses().put("204", r);
            return;
        }

        io.swagger.v3.oas.models.media.MediaType mt =
                (rc.get(MediaType.APPLICATION_JSON_VALUE) == null)
                        ? new io.swagger.v3.oas.models.media.MediaType()
                        : rc.get(MediaType.APPLICATION_JSON_VALUE);

        if (mt.getSchema() == null) {
            mt.setSchema(schemaForType(ep.returns != null ? ep.returns.type : null));
        }

        if (mt.getExample() == null) {
            Schema<?> schema = resolveRefSchema(api, mt.getSchema());
            mt.setExample(synthExampleNeutral(schema));
        }

        rc.addMediaType(MediaType.APPLICATION_JSON_VALUE, mt);
        r.setContent(rc);
        op.getResponses().put(pick, r);
    }

    //"Przykłady wywołań" - fallback
    private void ensureCurlExample(io.swagger.v3.oas.models.Operation op, EndpointIR ep) {
        if (op.getExtensions() != null) {
            Object ex = op.getExtensions().get("x-request-examples");
            if (ex instanceof List<?> list && !list.isEmpty()) {
                return; // AI already provided examples
            }
        }

        String method = String.valueOf(ep.http).toUpperCase(Locale.ROOT);
        if (method.isBlank()) method = "GET";
        String url = "{{BASE_URL}}" + ep.path;

        StringBuilder sb = new StringBuilder();
        sb.append("curl -X ").append(method).append(" \"").append(url).append("\"");

        boolean hasBody = op.getRequestBody() != null;
        if (hasBody) {
            sb.append(" \\\n  -H \"Content-Type: application/json\"");
            Object bodyEx = null;
            Content c = op.getRequestBody().getContent();
            if (c != null) {
                io.swagger.v3.oas.models.media.MediaType mt = c.get(MediaType.APPLICATION_JSON_VALUE);
                if (mt != null) {
                    bodyEx = mt.getExample();
                    if (bodyEx == null && mt.getSchema() != null) {
                        bodyEx = synthExampleNeutral(mt.getSchema());
                    }
                }
            }
            if (bodyEx != null) {
                sb.append(" \\\n  --data-raw '").append(jsonMin(bodyEx)).append("'");
            }
        }

        List<String> curls = new ArrayList<>();
        curls.add(normalizeCurl(sb.toString()));

        Map<String, Object> ext = (op.getExtensions() == null)
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(op.getExtensions());
        ext.put("x-request-examples", curls);
        op.setExtensions(ext);
    }

    //do zbudowania zwięzłego body w cURL w ensureCurlExample
    private String jsonMin(Object o) {
        if (o == null) return "null";
        if (o instanceof String) return ((String) o).replace("\"", "\\\"");
        if (o instanceof Number || o instanceof Boolean) return String.valueOf(o);
        if (o instanceof Map<?, ?> m) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append('"').append(String.valueOf(e.getKey()).replace("\"", "\\\"")).append("\":");
                sb.append(jsonMin(e.getValue()));
            }
            sb.append('}');
            return sb.toString();
        }
        if (o instanceof Collection<?> c) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object it : c) {
                if (!first) sb.append(',');
                first = false;
                sb.append(jsonMin(it));
            }
            sb.append(']');
            return sb.toString();
        }
        return "\"\"";
    }
    //curl -X POST https://api/test -H "Authorization: ..." -H "Content-Type: ..." --data-raw '{"a":1}'
    //staje sie
    //curl -X POST https://api/test \
    //-H "Authorization: ..." \
    //-H "Content-Type: application/json" \
    //--data-raw '{"a":1}'
    private static String normalizeCurl(String s) {
        String t = s.replace("\\n", "\n").replace("\\\"", "\"").trim();
        if (!t.contains("\n")) {
            t = t.replaceAll("\\s+-X\\s+", " \\\n  -X ")
                 .replaceAll("\\s+-H\\s+", " \\\n  -H ")
                 .replaceAll("\\s+--data-raw\\s+", " \\\n  --data-raw ")
                 .replaceAll("\\s+-d\\s+", " \\\n  -d ");
        }
        if (!t.startsWith("curl ")) t = "curl " + t;
        return t;
    }


    //ERRORS
    //Dla KAŻDEGO endpointu:
    //Z automatu pełen zestaw standardowych błędów:
    // 400, 401, 403, 404, 429, 500,
    // plus 409 i 422 dla operacji zapisujących.
    //Każdy z nich:
    //ma opis po polsku,
    // ma spójne body ApiError,
    // ma przykładowy JSON,
    private Schema<?> apiErrorRef() {
        return new Schema<>().$ref("#/components/schemas/ApiError");
    }
    private void attachStandardErrors(io.swagger.v3.oas.models.Operation op, boolean isWrite) {
        if (op == null) return;

        ensureErrorResponse(op, "400", "Nieprawidłowe lub niekompletne dane żądania.",
                exErr("INVALID_REQUEST", "Parametry są niepoprawne lub brakuje wymaganych pól."), null);

        Map<String, Header> h401 = new LinkedHashMap<>();
        h401.put("WWW-Authenticate", hdrString("Schemat autoryzacji, np. Bearer."));
        ensureErrorResponse(op, "401", "Brak poprawnego tokenu uwierzytelniającego.",
                exErr("UNAUTHORIZED", "Użyj nagłówka Authorization: Bearer <token>."), h401);

        ensureErrorResponse(op, "403", "Zalogowany użytkownik nie ma uprawnień do tej operacji.",
                exErr("FORBIDDEN", "Token jest poprawny, ale brak wymaganych uprawnień."), null);

        ensureErrorResponse(op, "404", "Szukany zasób nie istnieje lub nie jest dostępny.",
                exErr("NOT_FOUND", "Podany zasób nie został znaleziony."), null);

        if (isWrite) {
            ensureErrorResponse(op, "409", "Konflikt danych (np. duplikat lub niezgodny stan).",
                    exErr("CONFLICT", "Operacja koliduje z istniejącymi danymi."), null);
            ensureErrorResponse(op, "422", "Dane są poprawne syntaktycznie, ale nie przechodzą walidacji biznesowej.",
                    exErr("VALIDATION_ERROR", "Sprawdź reguły walidacji domenowej."), null);
        }

        Map<String, Header> h429 = new LinkedHashMap<>();
        h429.put("Retry-After", hdrInteger("Liczba sekund do kolejnej próby."));
        ensureErrorResponse(op, "429", "Przekroczono limit zapytań (rate limit).",
                exErr("RATE_LIMITED", "Zwolnij tempo wywołań, spróbuj ponownie później."), h429);

        ensureErrorResponse(op, "500", "Nieoczekiwany błąd po stronie serwera.",
                exErr("INTERNAL_ERROR", "Spróbuj ponownie lub skontaktuj się z zespołem utrzymania."), null);
    }
    private void ensureErrorResponse(io.swagger.v3.oas.models.Operation op,
                                     String status,
                                     String description,
                                     Map<String, Object> example,
                                     Map<String, Header> headersIfAny) {
        if (op.getResponses() == null) {
            op.setResponses(new ApiResponses());
        }
        ApiResponses rs = op.getResponses();
        if (rs.get(status) != null) {
            return;
        }

        ApiResponse resp = new ApiResponse().description(description);

        Content c = new Content();
        io.swagger.v3.oas.models.media.MediaType mt = new io.swagger.v3.oas.models.media.MediaType();
        mt.setSchema(apiErrorRef());
        if (example != null && !example.isEmpty()) {
            mt.setExample(example);
        }
        c.addMediaType(MediaType.APPLICATION_JSON_VALUE, mt);
        resp.setContent(c);

        if (headersIfAny != null && !headersIfAny.isEmpty()) {
            resp.setHeaders(headersIfAny);
        }

        rs.addApiResponse(status, resp);
    }
    //POMOCNICZE do "attachStandardErrors"
    private Map<String, Object> exErr(String code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("message", message);
        m.put("traceId", "0000000000000000");
        m.put("timestamp", "2025-01-01T12:00:00Z");
        return m;
    }
    private Header hdrString(String desc) {
        Header h = new Header();
        h.setSchema(new StringSchema());
        h.setDescription(desc);
        return h;
    }
    private Header hdrInteger(String desc) {
        Header h = new Header();
        h.setSchema(new IntegerSchema());
        h.setDescription(desc);
        return h;
    }


    // SCHEMAS + EXAMPLE SYNTH
    private static final Set<String> PRIMITIVES = Set.of(
            "byte", "short", "int", "long", "float", "double", "boolean", "char"
    );
    private static final Set<String> BUILTINS = Set.of(
            "String", "Integer", "Long", "Float", "Double", "BigDecimal",
            "Boolean", "UUID", "Object", "Date", "LocalDate", "LocalDateTime", "OffsetDateTime"
    );

    //Ogólny cel: na podstawie nazwy typu z kodu (np. "List<User>", "Map<String, Order>", "ResponseEntity<User>") 
    //zwrócić obiekt Schema<?> odpowiadający strukturze w OpenAPI. 
    //To jest centralny element tworzenia schematów request/response.
    private Schema<?> schemaForType(String typeName) {
        if (typeName == null || typeName.isBlank()) return new ObjectSchema();
        String t = typeName.trim();

        if (t.startsWith("ResponseEntity<")
                || t.startsWith("Optional<")
                || t.startsWith("CompletableFuture<")
                || t.startsWith("Mono<")
                || t.startsWith("Flux<")) {
            return schemaForType(stripGenerics(t));
        }

        if (t.startsWith("Page<")) {
            Schema<?> inner = schemaForType(stripGenerics(t));
            ArraySchema content = new ArraySchema();
            content.setItems(inner);
            ObjectSchema page = new ObjectSchema();
            page.addProperties("content", content);
            page.addProperties("page", new IntegerSchema());
            page.addProperties("size", new IntegerSchema());
            page.addProperties("totalElements", new IntegerSchema());
            page.addProperties("totalPages", new IntegerSchema());
            return page;
        }

        if (t.endsWith("[]")) {
            ArraySchema arr = new ArraySchema();
            arr.setItems(schemaForType(t.substring(0, t.length() - 2)));
            return arr;
        }
        if (t.startsWith("List<") || t.startsWith("Set<")) {
            ArraySchema arr = new ArraySchema();
            arr.setItems(schemaForType(stripGenerics(t)));
            return arr;
        }
        if (t.startsWith("Map<")) {
            String[] kv = splitMapKV(t);
            MapSchema ms = new MapSchema();
            ms.setAdditionalProperties(schemaForType(kv[1]));
            return ms;
        }
        if (PRIMITIVES.contains(t)) return primitiveToSchema(t);

        String simple = simpleName(t);
        if (BUILTINS.contains(simple)) return builtinToSchema(simple);

        return new Schema<>().$ref("#/components/schemas/" + simple);
    }
    //co zwróci schemaForType: 
    //"String" → StringSchema (type: string)
    //"UUID" → StringSchema(format=uuid)
    //"int" → IntegerSchema 
    //"List<UserResponse>" → ArraySchema(items=$ref: "#/components/schemas/UserResponse")
    //"UserResponse[]" → ArraySchema(items=$ref: "#/components/schemas/UserResponse")
    //"Map<String, Order>" → MapSchema(additionalProperties=$ref: "#/components/schemas/Order")
    // "ResponseEntity<List<Product>>" → jak dla List<Product>
    //"Page<Order>" → obiekt z polami content, page, size, totalElements, totalPages (patrz sekcja 2)
    //"com.acme.api.dto.UserResponse" → $ref: "#/components/schemas/UserResponse"

    //wykorzystywane w schemaForType
    private static Schema<?> primitiveToSchema(String p) {
        return switch (p) {
            case "byte", "short", "int", "long" -> new IntegerSchema();
            case "float", "double"              -> new NumberSchema();
            case "boolean"                      -> new BooleanSchema();
            case "char"                         -> new StringSchema();
            default                             -> new ObjectSchema();
        };
    }
    private static Schema<?> builtinToSchema(String s) {
        return switch (s) {
            case "String"        -> new StringSchema();
            case "Integer", "Long" -> new IntegerSchema();
            case "Float", "Double", "BigDecimal" -> new NumberSchema();
            case "Boolean"       -> new BooleanSchema();
            case "UUID"          -> new StringSchema().format("uuid");
            case "LocalDate"     -> new StringSchema().format("date");
            case "LocalDateTime",
                 "OffsetDateTime",
                 "Date"          -> new StringSchema().format("date-time");
            default              -> new ObjectSchema();
        };
    }
    //"com.acme.User" → "User"
    //"java.util.List" → "List"
    //"User" → "User"
    private static String simpleName(String qname) {
        String s = qname;
        int gen = s.indexOf('<');
        if (gen >= 0) s = s.substring(0, gen);
        int dot = s.lastIndexOf('.');
        return (dot >= 0) ? s.substring(dot + 1) : s;
    }
    //"List<User>" → "User"
    //"Map<String, List<User>>" → "String, List<User>"
    private static String stripGenerics(String g) {
        int lt = g.indexOf('<');
        int gt = g.lastIndexOf('>');
        if (lt >= 0 && gt > lt) return g.substring(lt + 1, gt).trim();
        return g;
    }
    //"String, List<User>" → ["String", "List<User>"]
    private static String[] splitMapKV(String g) {
        String inner = stripGenerics(g);
        int depth = 0, commaPos = -1;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (c == ',' && depth == 0) {
                commaPos = i;
                break;
            }
        }
        if (commaPos < 0) return new String[]{"String", "Object"};
        String k = inner.substring(0, commaPos).trim();
        String v = inner.substring(commaPos + 1).trim();
        return new String[]{k, v};
    }
    //odwiązuje $ref do rzeczywistej schemy z components.schemas, jeśli istnieje
    private Schema<?> resolveRefSchema(OpenAPI api, Schema<?> s) {
        if (s == null) return null;
        String ref = s.get$ref();
        if (ref == null || ref.isBlank()) {
            return s;
        }
        int idx = ref.lastIndexOf('/');
        String name = (idx >= 0) ? ref.substring(idx + 1) : ref;
        if (api != null
                && api.getComponents() != null
                && api.getComponents().getSchemas() != null) {
            Schema<?> target = api.getComponents().getSchemas().get(name);
            if (target != null) {
                return target;
            }
        }
        return s;
    }


    //PLACEHOLDER usuniecie
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
        String p = (path == null) ? "/" : path.trim();
        if (p.equals("/")) return method + " /";
        if ("GET".equalsIgnoreCase(method)) {
            if (p.endsWith("}")) return "Pobierz zasób po identyfikatorze.";
            return "Pobierz listę zasobów.";
        }
        if ("POST".equalsIgnoreCase(method))  return "Utwórz nowy zasób.";
        if ("PUT".equalsIgnoreCase(method))   return "Całkowicie zaktualizuj zasób.";
        if ("PATCH".equalsIgnoreCase(method)) return "Częściowo zaktualizuj zasób.";
        if ("DELETE".equalsIgnoreCase(method))return "Usuń zasób.";
        return method + " " + path;
    }
    private void scrubPlaceholders(io.swagger.v3.oas.models.Operation op, String path, String method) {
        String s = safe(op.getSummary());
        String d = safe(op.getDescription());
        // Wytnij placeholdery
        if (isPlaceholder(s)) s = "";
        if (isPlaceholder(d)) d = "";
        // Jeśli po czyszczeniu oba są puste — zbuduj sensowny, krótki summary z path/metody
        if (s.isBlank() && d.isBlank()) {
            String base = humanizeFromPath(method, path);
            s = base;
            d = "";
        }

        // Zapisz tylko jeśli się zmieniło
        if (!Objects.equals(s, op.getSummary()))      op.setSummary(blankToNull(s));
        if (!Objects.equals(d, op.getDescription()))  op.setDescription(blankToNull(d));
    }

    // SANITY / UTILS
    private void sanitizeOpenApi(OpenAPI api) {
        if (api == null || api.getPaths() == null) return;

        api.getPaths().forEach((path, pi) -> {
            if (pi == null) return;
            for (io.swagger.v3.oas.models.Operation op : Arrays.asList(
                    pi.getGet(), pi.getPost(), pi.getPut(), pi.getPatch(), pi.getDelete()
            )) {
                if (op == null || op.getResponses() == null) continue;

                ApiResponses rs = op.getResponses();
                ApiResponse r204 = rs.get("204");
                if (r204 != null) {
                    r204.setContent(null);
                    if (r204.getDescription() == null || r204.getDescription().isBlank()) {
                        r204.setDescription("No Content");
                    }
                }

                String m = methodOf(pi, op);
                if ("DELETE".equalsIgnoreCase(m)) {
                    if (rs.get("204") == null) {
                        rs.addApiResponse("204", new ApiResponse().description("No Content"));
                    }
                    rs.keySet().removeIf(k -> k.startsWith("2") && !"204".equals(k));
                }
            }
        });
    }

    private static String methodOf(PathItem pi, io.swagger.v3.oas.models.Operation op) {
        if (pi == null || op == null) return null;
        if (op == pi.getGet())    return "GET";
        if (op == pi.getPost())   return "POST";
        if (op == pi.getPut())    return "PUT";
        if (op == pi.getPatch())  return "PATCH";
        if (op == pi.getDelete()) return "DELETE";
        return null;
    }

    private static boolean isWriteMethod(String http) {
        String h = nz(http).toUpperCase(Locale.ROOT);
        return h.equals("POST") || h.equals("PUT") || h.equals("PATCH");
    }

    private static String firstNonBlank(String... xs) {
        if (xs == null) return null;
        for (String x : xs) {
            if (x != null && !x.isBlank()) return x;
        }
        return null;
    }

    private static String asStr(Object o) {
        return (o == null) ? null : Objects.toString(o, null);
    }

    private static String trim(String s, int max) {
        if (s == null) return null;
        if (s.length() <= max) return s;
        return s.substring(0, max) + "…";
    }

    private static String nz(String s) {
        return (s == null) ? "" : s;
    }

    private static String firstSentenceOf(String text) {
        if (text == null) return "";
        String t = text.trim();
        if (t.isEmpty()) return "";
        int idx = t.indexOf('.');
        if (idx < 0) return t;
        return t.substring(0, idx).trim();
    }

    // PUBLIC: PODGLĄD DANYCH WEJŚCIOWYCH DLA MODELU NLP
    //Zwraca dokładnie to, co wysyłamy do NLP dla pojedynczego endpointu,
    //plus audience + mode (ollama) do podglądu w UI.
    public Map<String, Object> buildNlpInputForEndpoint(EndpointIR ep,
                                                        String level) {
        Map<String, Object> body = buildNlpBody(ep);
        body.put("audience", level);
        body.put("mode", "ollama");
        return body;
    }
    //Zwraca listę wejść NLP dla wszystkich endpointów danego projektu.
    public List<Map<String, Object>> buildNlpInputs(List<EndpointIR> endpoints,
                                                    String level) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (endpoints == null) {
            return out;
        }
        for (EndpointIR ep : endpoints) {
            out.add(buildNlpInputForEndpoint(ep, level));
        }
        return out;
    }

//----------------BEDZIE ZMIENIANE--------------------
    //SECURITY SCHEMA
    //definiuje jak wygląda bearer auth (komponent)
    private void ensureBearerAuth(OpenAPI api) {
        Components comps = api.getComponents();
        if (comps.getSecuritySchemes() == null) {
            comps.setSecuritySchemes(new LinkedHashMap<>());
        }
        if (!comps.getSecuritySchemes().containsKey("bearerAuth")) {
            SecurityScheme bearer = new SecurityScheme()
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT")
                    .description("JWT bearer token. Nagłówek: Authorization: Bearer <token>.");
            comps.addSecuritySchemes("bearerAuth", bearer);
        }
    }
    //mówi: całe API wymaga bearerAuth
    private void applyGlobalSecurity(OpenAPI api) {
        List<SecurityRequirement> sec = api.getSecurity();
        if (sec == null) sec = new ArrayList<>();
        boolean hasBearer = sec.stream().anyMatch(sr -> sr != null && sr.containsKey("bearerAuth"));
        if (!hasBearer) {
            sec.add(new SecurityRequirement().addList("bearerAuth", Collections.emptyList()));
        }
        api.setSecurity(sec);
    }

    //to heurystyka. Ona nie wie naprawdę, co robi Spring Security, tylko zgaduje po nazwach, ścieżkach i opisach.
    private boolean isPublicEndpoint(EndpointIR ep) {
        if (ep == null) return false;
        String p = nz(ep.path).toLowerCase(Locale.ROOT);
        String h = nz(ep.http).toUpperCase(Locale.ROOT);
        String opId = nz(ep.operationId).toLowerCase(Locale.ROOT);
        String desc = nz(ep.description).toLowerCase(Locale.ROOT);
        String jdoc = nz(ep.javadoc).toLowerCase(Locale.ROOT);

        if (p.startsWith("/auth") || p.startsWith("/public")) return true;
        if (h.equals("GET") && (p.startsWith("/docs") || p.startsWith("/health") || p.startsWith("/actuator")))
            return true;
        if (opId.contains("login") || opId.contains("register") || opId.contains("token") || opId.contains("refresh"))
            return true;
        if (desc.contains("login") || desc.contains("register") || desc.contains("token") || desc.contains("refresh"))
            return true;
        if (jdoc.contains("@public") || jdoc.contains("[public]")) return true;

        return false;
    }

    // API ERROR
    private void ensureApiErrorComponent(OpenAPI api) {
        Components comps = api.getComponents();
        if (comps.getSchemas() == null) comps.setSchemas(new LinkedHashMap<>());
        Map<String, Schema> schemas = comps.getSchemas();
        if (!schemas.containsKey("ApiError")) {
            ObjectSchema err = new ObjectSchema();
            err.addProperties("code", new StringSchema().description("Krótki kod błędu, np. USER_NOT_FOUND."));
            err.addProperties("message", new StringSchema().description("Opis błędu w czytelnym języku."));
            err.addProperties("details", new Schema<>().type("object").description("Dodatkowe szczegóły."));
            err.addProperties("traceId", new StringSchema().description("Identyfikator żądania."));
            err.addProperties("timestamp", new StringSchema().format("date-time").description("Znacznik czasu."));
            err.setRequired(Arrays.asList("code", "message"));
            schemas.put("ApiError", err);
        }
    }
}
