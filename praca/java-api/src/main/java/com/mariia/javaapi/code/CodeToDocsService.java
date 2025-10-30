package com.mariia.javaapi.code;

import com.mariia.javaapi.code.ir.EndpointIR;
import com.mariia.javaapi.code.ir.ParamIR;
import com.mariia.javaapi.docs.AiPostProcessor;
import io.swagger.v3.core.util.Yaml;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class CodeToDocsService {

    public enum DescribeMode { PLAIN, RULES, AI }

    private final WebClient nlp;
    private final Duration timeout = Duration.ofSeconds(90);

    public CodeToDocsService(@Qualifier("nlpClient") WebClient nlp) {
        this.nlp = nlp;
    }

    /** Główne wejście – generuje YAML z IR + NLP (zależnie od trybu). */
    public Path generateYamlFromCode(
            List<EndpointIR> eps,
            String projectName,
            String level,
            Path outFile,
            Path projectRoot,
            DescribeMode mode
    ) throws Exception {

        System.out.println("[from-code] level=" + level + " mode=" + mode);
        OpenAPI api = new OpenAPI().info(new Info().title(projectName + "-API").version("1.0.0"));
        api.setPaths(new Paths());

        // ==== DTO (components/schemas) ====
        Path projectPath = resolveProjectPath(projectRoot);
        System.out.println("[DTO] użyty katalog do skanowania: " + projectPath);
        JavaDtoParser dtoParser = new JavaDtoParser();
        Map<String, Schema> dtoSchemas = Collections.emptyMap();
        try {
            dtoSchemas = dtoParser.parseDtos(projectPath);
        } catch (Exception e) {
            System.err.println("[WARN] Pomijam parsowanie DTO: " + e.getMessage());
        }
        if (!dtoSchemas.isEmpty()) {
            if (api.getComponents() == null) api.setComponents(new io.swagger.v3.oas.models.Components());
            api.getComponents().setSchemas(dtoSchemas);
            System.out.println("[DTO] dodano do components/schemas: " + dtoSchemas.keySet());
        } else {
            System.out.println("[DTO] nie znaleziono żadnych DTO – components/schemas będzie puste.");
        }

        // ==== Operacje ====
        for (EndpointIR ep : eps) {
            PathItem pi = api.getPaths().get(ep.path);
            if (pi == null) { pi = new PathItem(); api.getPaths().addPathItem(ep.path, pi); }

            var op = new io.swagger.v3.oas.models.Operation();
            op.setOperationId(ep.operationId);

            // --- wejście do NLP ---
            Map<String, Object> nlpRes = Collections.emptyMap();
            if (mode != DescribeMode.PLAIN) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("symbol", ep.operationId);
                body.put("kind", "endpoint");
                body.put("signature", ep.http + " " + ep.path);
                body.put("comment", ep.description == null ? "" : ep.description);

                // dodatkowy kontekst
                body.put("http", ep.http);
                body.put("pathTemplate", ep.path);
                body.put("javadoc", ep.javadoc == null ? "" : ep.javadoc);
                body.put("notes", ep.notes == null ? List.of() : ep.notes);
                body.put("todos", ep.todos == null ? List.of() : ep.todos);
                body.put("language", "pl");

                List<Map<String, Object>> nlpParams = new ArrayList<>();
                for (ParamIR p : ep.params) {
                    nlpParams.add(Map.of(
                            "name", p.name,
                            "in", p.in,
                            "type", p.type,
                            "required", p.required,
                            "description", p.description == null ? "" : p.description
                    ));
                }
                body.put("params", nlpParams);
                body.put("returns", Map.of(
                        "type", ep.returns != null ? ep.returns.type : "void",
                        "description", ep.returns != null ? (ep.returns.description == null ? "" : ep.returns.description) : ""
                ));

                nlpRes = callNlp(body, mode, level);
            } else {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("symbol", ep.operationId);
                body.put("kind", "endpoint");
                body.put("signature", ep.http + " " + ep.path);
                body.put("comment", "");
                List<Map<String, Object>> nlpParams = new ArrayList<>();
                for (ParamIR p : ep.params) {
                    nlpParams.add(Map.of(
                            "name", p.name,
                            "type", p.type,
                            "description", p.description == null ? "" : p.description
                    ));
                }
                body.put("params", nlpParams);
                body.put("returns", Map.of("type", ep.returns != null ? ep.returns.type : "void"));
                nlpRes = callNlp(body, DescribeMode.PLAIN, level);
            }

            // —— summary/description zależnie od trybu ——
            if (mode == DescribeMode.PLAIN) {
                // brak opisów
            } else if (mode == DescribeMode.RULES) {
                String shortD = sanitizeNlpStrict(asStr(nlpRes.get("shortDescription")));
                String medD   = sanitizeNlpStrict(asStr(nlpRes.get("mediumDescription")));
                String longD  = sanitizeNlpStrict(asStr(nlpRes.get("longDescription")));
                String retDoc = sanitizeNlpStrict(asStr(nlpRes.get("returnDoc")));

                String summary = firstNonBlank(shortD, medD, longD, ep.description, ep.operationId);
                if (summary != null && !summary.isBlank()) op.setSummary(trim100(summary));
                String descr = pickByLevel(shortD, medD, longD, level);
                if (descr == null) descr = firstNonBlank(longD, medD, shortD, ep.description);
                if (descr != null && !descr.isBlank()) op.setDescription(descr);

                if (ep.notes != null && !ep.notes.isEmpty()) op.addExtension("x-impl-notes", ep.notes);
                if (ep.todos != null && !ep.todos.isEmpty()) op.addExtension("x-todos", ep.todos);

                if (ep.notes != null && !ep.notes.isEmpty()) {
                    String base = (op.getDescription() == null || op.getDescription().isBlank())
                            ? (ep.description == null ? "" : ep.description.trim())
                            : op.getDescription().trim();
                    String joined = String.join("; ", ep.notes);
                    if (joined.length() > 220) joined = joined.substring(0, 220).trim() + "…";
                    String finalDesc = base.isBlank() ? ("Notes: " + joined) : (base + "\n\nNotes: " + joined);
                    op.setDescription(finalDesc);
                }

            } else { // AI (Ollama)
                // Bez fallbacku: pokazujemy dokładny efekt LLM
                String medD = asStr(nlpRes.get("mediumDescription"));
                if (medD != null && !medD.isBlank()) {
                    op.setDescription(medD.trim());
                    // summary = pierwsze zdanie z opisu
                    String firstSentence = firstSentenceOf(medD);
                    if (!firstSentence.isBlank()) op.setSummary(trim100(firstSentence + "."));
                }

                // ---- notes (x-impl-notes) ----
                Object notesObj = nlpRes.get("notes");
                if (notesObj instanceof List) {
                    List<String> implNotes = ((List<?>) notesObj).stream()
                            .map(x -> Objects.toString(x, ""))
                            .filter(s -> !s.isBlank())
                            .map(String::trim)
                            .limit(3)
                            .toList();
                    if (!implNotes.isEmpty()) op.addExtension("x-impl-notes", implNotes);
                }

                // ---- examples ----
                Object examplesObj = nlpRes.get("examples");
                if (examplesObj instanceof Map) {
                    Map<String, Object> examples = (Map<String, Object>) examplesObj;

                    // a) request examples -> lista cURL w x-request-examples
                    Object reqsObj = examples.get("requests");
                    if (reqsObj instanceof List && !((List<?>) reqsObj).isEmpty()) {
                        List<String> curlList = new ArrayList<>();
                        for (Object r : (List<?>) reqsObj) {
                            if (r instanceof Map) {
                                Object curl = ((Map<?, ?>) r).get("curl");
                                if (curl != null && !curl.toString().isBlank()) {
                                    curlList.add(curl.toString());
                                }
                            }
                        }
                        if (!curlList.isEmpty()) op.addExtension("x-request-examples", curlList);
                    }

                    // b) response example -> responses
                    Object respObj = examples.get("response");
                    if (respObj instanceof Map) {
                        Map<String, Object> resp = (Map<String, Object>) respObj;
                        int status = 200;
                        try { status = Integer.parseInt(Objects.toString(resp.get("status"), "200")); } catch (Exception ignore) {}
                        Object bodyEx = resp.get("body");

                        ApiResponses rs = (op.getResponses() == null) ? new ApiResponses() : op.getResponses();
                        ApiResponse r = (rs.get(String.valueOf(status)) != null)
                                ? rs.get(String.valueOf(status))
                                : new ApiResponse().description("OK");

                        Content rc = (r.getContent() == null) ? new Content() : r.getContent();
                        io.swagger.v3.oas.models.media.MediaType mt = (rc.get("application/json") == null)
                                ? new io.swagger.v3.oas.models.media.MediaType()
                                : rc.get("application/json");

                        if (bodyEx != null) mt.setExample(bodyEx);
                        if (mt.getSchema() == null) mt.setSchema(schemaForType(ep.returns != null ? ep.returns.type : null));

                        rc.addMediaType("application/json", mt);
                        r.setContent(rc);
                        rs.addApiResponse(String.valueOf(status), r);
                        op.setResponses(rs);
                    }
                }
            }

            // --- parametry -> OpenAPI (wstępnie) ---
            if (ep.params != null && !ep.params.isEmpty()) {
                List<Parameter> ps = new ArrayList<>();
                for (ParamIR p : ep.params) {
                    if ("body".equalsIgnoreCase(p.in)) {
                        // Body
                        RequestBody rb = buildJsonRequestBody(p);
                        op.setRequestBody(rb);
                    } else {
                        // Inne (path, query, header)
                        Parameter par = new Parameter()
                                .name(p.name)
                                .in(p.in)
                                .required(p.required)
                                .description(p.description);
                        par.setSchema(schemaForType(p.type));
                        ps.add(par);
                    }
                }
                if (!ps.isEmpty()) op.setParameters(ps);
            }

            // --- domyślna odpowiedź gdy brak ---
            if (op.getResponses() == null || op.getResponses().isEmpty()) {
                ApiResponses rs = new ApiResponses();

                boolean isDelete = "DELETE".equalsIgnoreCase(ep.http);
                boolean isVoid = ep.returns == null
                        || ep.returns.type == null
                        || ep.returns.type.trim().equals("void")
                        || ep.returns.type.trim().equals("Void")
                        || ep.returns.type.trim().startsWith("ResponseEntity<Void");

                if (isDelete || isVoid) {
                    rs.addApiResponse("204", new ApiResponse().description("No Content"));
                } else {
                    ApiResponse ok = new ApiResponse();
                    String retDoc = asStr(nlpRes.get("returnDoc"));
                    ok.setDescription((retDoc != null && !retDoc.isBlank()) ? retDoc : "OK");
                    ok.setContent(new Content().addMediaType(
                            "application/json",
                            new io.swagger.v3.oas.models.media.MediaType()
                                    .schema(schemaForType(ep.returns != null ? ep.returns.type : null))
                    ));
                    rs.addApiResponse("200", ok);
                }
                op.setResponses(rs);
            }

            // --- TWARDY POST-PROCESSING (po zbudowaniu opisu/paramów/response) ---
            aiPostProcess(op, ep, mode);

            // --- wstaw operację ---
            switch (ep.http) {
                case "GET":    pi.setGet(op); break;
                case "POST":   pi.setPost(op); break;
                case "PUT":    pi.setPut(op); break;
                case "DELETE": pi.setDelete(op); break;
                case "PATCH":  pi.setPatch(op); break;
                default:       pi.setGet(op); break;
            }
        }

        // Globalne poprawki po całym drzewie (np. securitySchemes itp.)
        AiPostProcessor post = new AiPostProcessor();
        post.apply(api);

        Files.createDirectories(outFile.getParent());
        String yaml = Yaml.mapper().writeValueAsString(api);
        Files.writeString(outFile, yaml);
        return outFile;
    }

    /** Zachowujemy starą sygnaturę dla kompatybilności. */
    public Path generateYamlFromCode(List<EndpointIR> eps, String projectName, String level, Path outFile, Path projectRoot) throws Exception {
        return generateYamlFromCode(eps, projectName, level, outFile, projectRoot, DescribeMode.RULES);
    }

    // ============= NLP I/O =============

    @SuppressWarnings("unchecked")
    private Map<String, Object> callNlp(Map<String, Object> body, DescribeMode mode, String level) {
        try {
            String modeParam = (mode == DescribeMode.PLAIN) ? "plain"
                    : (mode == DescribeMode.RULES) ? "rule"
                    : "ollama";

            // level -> audience (beginner|intermediate|advanced)
            String audience = switch (safe(level)) {
                case "short" -> "beginner";
                case "long"  -> "advanced";
                default      -> "intermediate";
            };

            // Przy AI włączamy "strict"
            String uri = "/describe?mode=" + modeParam + "&audience=" + audience + ((mode == DescribeMode.AI) ? "&strict=true" : "");

            return nlp.post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(timeout);
        } catch (Exception e) {
            System.err.println("Błąd połączenia z NLP: " + e.getMessage());
            return Collections.emptyMap();
        }
    }

    // ============= Post-processing AI i heurystyki =============

    private static final Pattern PLACEHOLDER_RX = Pattern.compile(
            "(string\\s*\\(\\s*1\\s*[–-]\\s*3\\s*zdani|1\\s*[–-]\\s*3\\s*zdani|<extra_id_|wstaw\\s+opis|uzupełnij\\s+opis)",
            Pattern.CASE_INSENSITIVE
    );

    /** Twarde poprawki pojedynczej operacji. */
    private void aiPostProcess(io.swagger.v3.oas.models.Operation op, EndpointIR ep, DescribeMode mode) {
        // 1) Placeholdery -> fallback / wyczyszczenie
        if (op.getSummary() != null && PLACEHOLDER_RX.matcher(op.getSummary()).find()) {
            op.setSummary(null); // summary zostanie ewentualnie wyciągnięte z description poniżej
        }
        if (op.getDescription() != null && PLACEHOLDER_RX.matcher(op.getDescription()).find()) {
            // Jeśli AI „zawalił” opis – zostaw pusty, a UI pokaże IR/javadoc lub rules
            op.setDescription(null);
        }

        // 2) Jeśli summary == description → skróć summary do pierwszego zdania
        if (nz(op.getSummary()).equals(nz(op.getDescription())) && op.getDescription() != null) {
            String fs = firstSentenceOf(op.getDescription());
            if (!fs.isBlank()) op.setSummary(trim100(fs + "."));
        }

        // 3) Jeżeli mamy body, usuń dublujące query: request/payload/body/dto/file/avatar
        if (op.getRequestBody() != null && op.getParameters() != null && !op.getParameters().isEmpty()) {
            Set<String> dupNames = Set.of("request","payload","body","dto","file","avatar","avatarfile");
            List<Parameter> filtered = op.getParameters().stream()
                    .filter(p -> !("query".equalsIgnoreCase(p.getIn())
                            && dupNames.contains(nz(p.getName()).toLowerCase(Locale.ROOT))))
                    .collect(Collectors.toList());
            op.setParameters(filtered.isEmpty() ? null : filtered);
        }

        // 4) Wymuś spójność miejsca parametrów dla metod modyfikujących (POST/PUT/PATCH)
        if (isWriteMethod(ep.http)) {
            // Heurystyka: jeśli brak requestBody, a w parametrach występują nazwy „request|payload|body|dto|file|avatar”
            if (op.getRequestBody() == null && op.getParameters() != null) {
                List<Parameter> params = new ArrayList<>(op.getParameters());
                List<Parameter> toMove = params.stream()
                        .filter(p -> "query".equalsIgnoreCase(p.getIn()))
                        .filter(p -> {
                            String n = nz(p.getName()).toLowerCase(Locale.ROOT);
                            return n.equals("request") || n.equals("payload") || n.equals("body") || n.equals("dto")
                                    || n.equals("file") || n.equals("avatar") || n.equals("avatarfile");
                        })
                        .collect(Collectors.toList());

                if (!toMove.isEmpty()) {
                    boolean fileLike = toMove.stream().anyMatch(p ->
                            schemaName(p.getSchema()).toLowerCase(Locale.ROOT).contains("multipart")
                                    || schemaName(p.getSchema()).toLowerCase(Locale.ROOT).contains("file"));

                    RequestBody rb = new RequestBody().required(true);
                    Content content = new Content();
                    io.swagger.v3.oas.models.media.MediaType mt;
                    if (fileLike) {
                        // multipart/form-data z polem pliku
                        mt = new io.swagger.v3.oas.models.media.MediaType();
                        Schema<?> sch = new ObjectSchema();
                        for (Parameter qp : toMove) {
                            String n = nz(qp.getName());
                            if (n.equalsIgnoreCase("file") || n.equalsIgnoreCase("avatar") || n.equalsIgnoreCase("avatarfile")) {
                                sch.addProperties(n, new StringSchema().format("binary"));
                            } else {
                                sch.addProperties(n, schemaOrString(qp.getSchema()));
                            }
                        }
                        mt.setSchema(sch);
                        content.addMediaType(org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE, mt);
                    } else {
                        // application/json: zbuduj obiekt z właściwościami przenoszonych parametrów
                        mt = new io.swagger.v3.oas.models.media.MediaType();
                        Schema<?> sch = new ObjectSchema();
                        for (Parameter qp : toMove) {
                            String n = nz(qp.getName());
                            sch.addProperties(n, schemaOrString(qp.getSchema()));
                        }
                        mt.setSchema(sch);
                        content.addMediaType(org.springframework.http.MediaType.APPLICATION_JSON_VALUE, mt);
                    }
                    rb.setContent(content);
                    op.setRequestBody(rb);

                    // usuń przeniesione parametry z query
                    params.removeAll(toMove);
                    op.setParameters(params.isEmpty() ? null : params);
                }
            }
        }

        // 5) Normalizacja przykładów cURL (x-request-examples)
        Object xr = op.getExtensions() != null ? op.getExtensions().get("x-request-examples") : null;
        if (xr instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> raw = (List<Object>) xr;
            List<String> norm = raw.stream()
                    .map(x -> Objects.toString(x, ""))
                    .filter(s -> !s.isBlank())
                    .map(CodeToDocsService::normalizeCurl)
                    .collect(Collectors.toList());
            if (!norm.isEmpty()) {
                op.addExtension("x-request-examples", norm);
            }
        }

        // 6) POST → preferuj 201 (gdy mamy body i tylko 200)
        if ("POST".equalsIgnoreCase(ep.http) && op.getResponses() != null && op.getResponses().containsKey("200")) {
            boolean hasBody = op.getRequestBody() != null;
            if (hasBody && op.getResponses().size() == 1) {
                ApiResponse r200 = op.getResponses().get("200");
                op.getResponses().remove("200");
                op.getResponses().addApiResponse("201", r200.description(r200.getDescription() == null ? "Created" : r200.getDescription()));
            }
        }

        // 7) Drobne: jeśli nie ma summary, spróbuj wyciągnąć z description
        if ((op.getSummary() == null || op.getSummary().isBlank()) && op.getDescription() != null) {
            String fs = firstSentenceOf(op.getDescription());
            if (!fs.isBlank()) op.setSummary(trim100(fs + "."));
        }
    }

    private static boolean isWriteMethod(String http) {
        String h = nz(http).toUpperCase(Locale.ROOT);
        return h.equals("POST") || h.equals("PUT") || h.equals("PATCH");
    }

    private static String schemaName(Schema<?> s) {
        if (s == null) return "";
        if (s.get$ref() != null) return s.get$ref();
        return s.getType() == null ? s.getClass().getSimpleName() : s.getType();
    }

    private RequestBody buildJsonRequestBody(ParamIR p) {
        return new RequestBody()
                .description(p.description)
                .required(p.required)
                .content(new Content().addMediaType(
                        "application/json",
                        new io.swagger.v3.oas.models.media.MediaType()
                                .schema(schemaForType(p.type))
                ));
    }

    private static String normalizeCurl(String s) {
        String t = s.replace("\\n", "\n").replace("\\\"", "\"").trim();

        // Jeśli to jednowierszowe, spróbuj ładnie złamać
        if (!t.contains("\n")) {
            // Wstaw łamania linii przed typowymi segmentami
            t = t.replaceAll("\\s+-X\\s+", " \\\n  -X ")
                 .replaceAll("\\s+-H\\s+", " \\\n  -H ")
                 .replaceAll("\\s+-d\\s+", " \\\n  -d ")
                 .replaceAll("\\s+--data-raw\\s+", " \\\n  --data-raw ")
                 .replaceAll("\\s+--data\\s+", " \\\n  --data ")
                 .replaceAll("\\s+--form\\s+", " \\\n  --form ");
        }
        // Upewnij się, że zaczyna się od 'curl '
        if (!t.startsWith("curl ")) t = "curl " + t;
        return t;
    }

    private static String firstSentenceOf(String text) {
        if (text == null) return "";
        String t = text.trim();
        int idx = t.indexOf('.');
        if (idx < 0) return t;
        return t.substring(0, idx).trim();
    }

    // ============= Ścieżki projektu =============

    private Path resolveProjectPath(Path projectRoot) {
        try {
            Path base = (projectRoot != null) ? projectRoot : Path.of(System.getProperty("user.dir"));
            Path src = base.resolve("src/main/java");
            if (Files.exists(src)) return src;
            Path classes = base.resolve("target/classes");
            if (Files.exists(classes)) return classes;

            try (Stream<Path> s = Files.find(base, 4, (p, a) -> a.isDirectory() && p.endsWith("src/main/java"))) {
                Optional<Path> hit = s.findFirst();
                if (hit.isPresent()) return hit.get();
            }
            try (Stream<Path> s2 = Files.find(base, 4, (p, a) -> a.isRegularFile() && p.toString().endsWith(".java"))) {
                Optional<Path> anyJava = s2.findFirst();
                if (anyJava.isPresent()) return anyJava.get().getParent();
            }
            return base;
        } catch (IOException e) {
            System.err.println("[WARN] resolveProjectPath error: " + e.getMessage());
            return (projectRoot != null) ? projectRoot : Path.of(System.getProperty("user.dir"));
        }
    }

    // ============= Teksty i wybór długości =============

    private static String pickByLevel(String s, String m, String l, String level) {
        String lvl = (level == null || level.isBlank()) ? "medium" : level;
        return switch (lvl) {
            case "short" -> firstNonBlank(s, m, l);
            case "long"  -> firstNonBlank(l, m, s);
            default      -> firstNonBlank(m, s, l);
        };
    }

    private static String sanitizeNlpStrict(String s) { // dla RULES/PLAIN
        if (s == null) return null;
        String t = s.trim();
        String lower = t.toLowerCase(Locale.ROOT);
        if (t.startsWith("規") || lower.startsWith("zadanie")
                || lower.contains("instrukcja") || lower.contains("<extra_id_")) {
            return null;
        }
        if (t.equals(".") || t.length() < 8) return null;
        if (!t.endsWith(".")) t = t + ".";
        return t;
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

    private static String trim100(String s) {
        if (s == null) return null;
        return (s.length() > 100) ? (s.substring(0, 100) + "…") : s;
    }

    private static String nz(String s) { return (s == null) ? "" : s; }

    private static String safe(String s) { return (s == null) ? "" : s.trim().toLowerCase(Locale.ROOT); }

    // ============= Mapowanie typów =============

    private static final Set<String> PRIMITIVES = Set.of(
            "byte","short","int","long","float","double","boolean","char"
    );
    private static final Set<String> BUILTINS = Set.of(
            "String","Integer","Long","Float","Double","BigDecimal",
            "Boolean","UUID","Object","Date","LocalDate","LocalDateTime","OffsetDateTime"
    );

    /** Mapowanie typu na Schema. $ref tylko dla własnych DTO. */
    private Schema<?> schemaForType(String typeName) {
        if (typeName == null || typeName.isBlank()) return new ObjectSchema();
        String t = typeName.trim();


            // Unwrap popularnych wrapperów
        if (t.startsWith("ResponseEntity<") ||
            t.startsWith("Optional<") ||
            t.startsWith("CompletableFuture<") ||
            t.startsWith("Mono<") ||
            t.startsWith("Flux<")) {
            return schemaForType(stripGenerics(t));
        }

        // Page<T> -> obiekt z content[] + metadanymi (jak w JavaDtoParser)
        if (t.startsWith("Page<")) {
            Schema<?> inner = schemaForType(stripGenerics(t));
            ArraySchema content = new ArraySchema(); content.setItems(inner);
            ObjectSchema page = new ObjectSchema();
            Map<String, Schema> props = new LinkedHashMap<>();
            props.put("content", content);
            props.put("page", new IntegerSchema());
            props.put("size", new IntegerSchema());
            props.put("totalElements", new IntegerSchema());
            props.put("totalPages", new IntegerSchema());
            props.put("last", new BooleanSchema());
            page.setProperties(props);
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
            String[] kv = splitMapKV(t); // [K,V]
            MapSchema ms = new MapSchema();
            ms.setAdditionalProperties(schemaForType(kv[1]));
            return ms;
        }
        if (PRIMITIVES.contains(t)) return primitiveToSchema(t);

        String simple = simpleName(t);
        if (BUILTINS.contains(simple)) return builtinToSchema(simple);

        return new Schema<>().$ref("#/components/schemas/" + simple);
    }

    private static Schema<?> primitiveToSchema(String p) {
        switch (p) {
            case "byte":
            case "short":
            case "int":
            case "long": return new IntegerSchema();
            case "float":
            case "double": return new NumberSchema();
            case "boolean": return new BooleanSchema();
            case "char": return new StringSchema();
            default: return new ObjectSchema();
        }
    }

    private static Schema<?> builtinToSchema(String s) {
        switch (s) {
            case "String": return new StringSchema();
            case "Integer":
            case "Long":   return new IntegerSchema();
            case "Float":
            case "Double":
            case "BigDecimal": return new NumberSchema();
            case "Boolean": return new BooleanSchema();
            case "UUID": return new StringSchema().format("uuid");
            case "LocalDate": return new StringSchema().format("date");
            case "LocalDateTime":
            case "OffsetDateTime":
            case "Date": return new StringSchema().format("date-time");
            case "Object":
            default: return new ObjectSchema();
        }
    }

    private static String simpleName(String qname) {
        String s = qname;
        int gen = s.indexOf('<');
        if (gen >= 0) s = s.substring(0, gen);
        int dot = s.lastIndexOf('.');
        return (dot >= 0) ? s.substring(dot + 1) : s;
    }

    private static String stripGenerics(String g) {
        int lt = g.indexOf('<');
        int gt = g.lastIndexOf('>');
        if (lt >= 0 && gt > lt) return g.substring(lt + 1, gt).trim();
        return g;
    }

    private static String[] splitMapKV(String g) {
        String inner = stripGenerics(g);
        int depth = 0, commaPos = -1;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (c == ',' && depth == 0) { commaPos = i; break; }
        }
        if (commaPos < 0) return new String[]{"String","Object"};
        String k = inner.substring(0, commaPos).trim();
        String v = inner.substring(commaPos + 1).trim();
        return new String[]{k, v};
    }

    private static Schema<?> schemaOrString(Schema<?> s) {
        return (s == null) ? new StringSchema() : s;
    }
}
