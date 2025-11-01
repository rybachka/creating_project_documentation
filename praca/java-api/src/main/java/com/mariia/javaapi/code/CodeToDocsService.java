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
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
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

        // === znacznik poziomu odbiorcy do INFO (badge w PDF) ===
        if (api.getInfo() != null) {
            Map<String, Object> ext = api.getInfo().getExtensions();
            if (ext == null) ext = new LinkedHashMap<>();
            ext.put("x-user-level", normalizeAudience(level));
            api.getInfo().setExtensions(ext);
        }

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

                // Wymagane pola dla CreateUserRequest (jeśli istnieje)
        if (api.getComponents() != null && api.getComponents().getSchemas() != null) {
            Schema<?> cur = api.getComponents().getSchemas().get("CreateUserRequest");
            if (cur instanceof ObjectSchema) {
                ObjectSchema os = (ObjectSchema) cur;
                // ustaw required, jeśli brak
                if (os.getRequired() == null || os.getRequired().isEmpty()) {
                    os.setRequired(Arrays.asList("name","email"));
                } else {
                    Set<String> req = new LinkedHashSet<>(os.getRequired());
                    req.add("name"); req.add("email");
                    os.setRequired(new ArrayList<>(req));
                }
            }
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
                // --- kontrakt NLP: czy mamy response example?
                boolean hasRespExample = false;
                Object exObj = nlpRes.get("examples");
                if (exObj instanceof Map<?,?> exMap) {
                    Object r = exMap.get("response");
                    if (r instanceof Map<?,?> rMap) {
                        hasRespExample = rMap.get("body") != null;
                    }
                }
                // możesz też zalogować:
                if (!hasRespExample) {
                    System.out.println("[NLP] Brak examples.response.body dla " + ep.http + " " + ep.path);
                }

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

            // === znacznik poziomu odbiorcy do operacji ===
            Map<String, Object> opExt = op.getExtensions();
            if (opExt == null) opExt = new LinkedHashMap<>();
            opExt.put("x-user-level", normalizeAudience(level));
            op.setExtensions(opExt);

            // —— summary/description zależnie od trybu ——
            if (mode == DescribeMode.PLAIN) {
                // brak opisów
            } else if (mode == DescribeMode.RULES) {
                String shortD = sanitizeNlpStrict(asStr(nlpRes.get("shortDescription")));
                String medD   = sanitizeNlpStrict(asStr(nlpRes.get("mediumDescription")));
                String longD  = sanitizeNlpStrict(asStr(nlpRes.get("longDescription")));

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
                String medD = asStr(nlpRes.get("mediumDescription"));
                if (medD != null && !medD.isBlank()) {
                    op.setDescription(medD.trim());
                    String firstSentence = firstSentenceOf(medD);
                    if (!firstSentence.isBlank()) op.setSummary(trim100(firstSentence + "."));
                }

                // notes
                Object notesObj = nlpRes.get("notes");
                if (notesObj instanceof List) {
                    List<String> implNotes = ((List<?>) notesObj).stream()
                            .map(x -> Objects.toString(x, ""))
                            .filter(s -> !s.isBlank())
                            .map(String::trim)
                            .limit(5)
                            .toList();
                    if (!implNotes.isEmpty()) op.addExtension("x-impl-notes", implNotes);
                }

                // examples
                Object examplesObj = nlpRes.get("examples");
                if (examplesObj instanceof Map) {
                    Map<String, Object> examples = (Map<String, Object>) examplesObj;

                    Object reqsObj = examples.get("requests");
                    if (reqsObj instanceof List && !((List<?>) reqsObj).isEmpty()) {
                        List<String> curlList = new ArrayList<>();
                        for (Object r : (List<?>) reqsObj) {
                            if (r instanceof Map) {
                                Object curl = ((Map<?, ?>) r).get("curl");
                                if (curl != null && !curl.toString().isBlank()) {
                                    curlList.add(curl.toString());
                                }
                            } else if (r instanceof String) {
                                String curl = ((String) r).trim();
                                if (!curl.isBlank()) curlList.add(curl);
                            }
                        }
                        if (!curlList.isEmpty()) op.addExtension("x-request-examples", curlList);
                    }

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

            // --- parametry -> (wstępnie) ---
            if (ep.params != null && !ep.params.isEmpty()) {
                List<Parameter> ps = new ArrayList<>();
                for (ParamIR p : ep.params) {
                    if ("body".equalsIgnoreCase(p.in)) {
                        RequestBody rb = buildJsonRequestBody(p);
                        op.setRequestBody(rb);
                    } else {
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

            // --- twardy post-processing (po NLP) ---
            aiPostProcess(op, ep, mode);

            // --- FINAL SANITY PASS (parametry + odpowiedzi) ---
            finalSanity(op, ep);

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

        // Globalnie po całym drzewie
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
            String audience = normalizeAudience(level);
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

    private static String normalizeAudience(String level) {
        String lv = safe(level);
        return switch (lv) {
            case "beginner", "short", "junior" -> "beginner";
            case "advanced", "long", "senior"  -> "advanced";
            default                            -> "intermediate";
        };
    }

    // ============= TWARDY POST-PROCESSING (jak wcześniej) =============
    private static final Pattern PLACEHOLDER_RX = Pattern.compile(
            "(string\\s*\\(\\s*1\\s*[–-]\\s*3\\s*zdani|1\\s*[–-]\\s*3\\s*zdani|<extra_id_|wstaw\\s+opis|uzupełnij\\s+opis)",
            Pattern.CASE_INSENSITIVE
    );

    private void aiPostProcess(io.swagger.v3.oas.models.Operation op, EndpointIR ep, DescribeMode mode) {
        if (op.getSummary() != null && PLACEHOLDER_RX.matcher(op.getSummary()).find()) op.setSummary(null);
        if (op.getDescription() != null && PLACEHOLDER_RX.matcher(op.getDescription()).find()) op.setDescription(null);

        if (nz(op.getSummary()).equals(nz(op.getDescription())) && op.getDescription() != null) {
            String fs = firstSentenceOf(op.getDescription());
            if (!fs.isBlank()) op.setSummary(trim100(fs + "."));
        }

        if (op.getRequestBody() != null && op.getParameters() != null && !op.getParameters().isEmpty()) {
            Set<String> dupNames = Set.of("request","payload","body","dto","file","avatar","avatarfile");
            List<Parameter> filtered = op.getParameters().stream()
                    .filter(p -> !("query".equalsIgnoreCase(p.getIn())
                            && dupNames.contains(nz(p.getName()).toLowerCase(Locale.ROOT))))
                    .collect(Collectors.toList());
            op.setParameters(filtered.isEmpty() ? null : filtered);
        }

        if (isWriteMethod(ep.http)) {
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

                    params.removeAll(toMove);
                    op.setParameters(params.isEmpty() ? null : params);
                }
            }
        }

        Object xr = op.getExtensions() != null ? op.getExtensions().get("x-request-examples") : null;
        if (xr instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> raw = (List<Object>) xr;
            List<String> norm = raw.stream()
                    .map(x -> Objects.toString(x, ""))
                    .filter(s -> !s.isBlank())
                    .map(CodeToDocsService::normalizeCurl)
                    .collect(Collectors.toList());
            if (!norm.isEmpty()) op.addExtension("x-request-examples", norm);
        }

        // >>> NOWOŚĆ: lekki validator cURL po normalizeCurl
        validateAndAnnotateCurlExamples(op, ep);

        if ("POST".equalsIgnoreCase(ep.http) && op.getResponses() != null && op.getResponses().containsKey("200")) {
            boolean hasBody = op.getRequestBody() != null;
            if (hasBody && op.getResponses().size() == 1) {
                ApiResponse r200 = op.getResponses().get("200");
                op.getResponses().remove("200");
                op.getResponses().addApiResponse("201", r200.description(r200.getDescription() == null ? "Created" : r200.getDescription()));
            }
        }

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

    // ============= FINAL SANITY PASS =============
    private void finalSanity(io.swagger.v3.oas.models.Operation op, EndpointIR ep) {
    // 1) Dedup parametrów po (in,name) – preferuj bogatsze
    if (op.getParameters() != null && !op.getParameters().isEmpty()) {
        Map<String, Parameter> best = new LinkedHashMap<>();
        for (Parameter p : op.getParameters()) {
            if (p == null) continue;
            String key = (nz(p.getIn()) + ":" + nz(p.getName())).toLowerCase(Locale.ROOT);
            Parameter cur = best.get(key);
            if (cur == null || isBetterParam(p, cur)) best.put(key, p);
        }
        List<Parameter> dedup = new ArrayList<>(best.values());
        op.setParameters(dedup.isEmpty() ? null : dedup);
    }

    // 2) Zapewnij obecność parametrów {path}
    Set<String> pathVars = findPathVars(ep.path);
    if (!pathVars.isEmpty()) {
        List<Parameter> params = op.getParameters() == null ? new ArrayList<>() : new ArrayList<>(op.getParameters());
        Set<String> have = params.stream()
                .filter(p -> "path".equalsIgnoreCase(nz(p.getIn())))
                .map(p -> nz(p.getName()).toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        for (String v : pathVars) {
            if (!have.contains(v.toLowerCase(Locale.ROOT))) {
                Parameter pp = new Parameter().in("path").name(v).required(true);
                pp.setSchema(new StringSchema());
                params.add(pp);
            }
        }
        // path params muszą być required=true
        for (Parameter p : params) {
            if ("path".equalsIgnoreCase(nz(p.getIn()))) p.setRequired(true);
        }
        op.setParameters(params.isEmpty() ? null : params);
    }

    // 3) Body a metoda
    boolean isGet = "GET".equalsIgnoreCase(ep.http);
    boolean isDelete = "DELETE".equalsIgnoreCase(ep.http);
    boolean isPost = "POST".equalsIgnoreCase(ep.http);
    boolean isPut = "PUT".equalsIgnoreCase(ep.http);
    boolean isPatch = "PATCH".equalsIgnoreCase(ep.http);

    if (isGet || isDelete) {
        // GET/DELETE nie powinny mieć body
        op.setRequestBody(null);
    } else if (isPost || isPut || isPatch) {
        RequestBody rb = op.getRequestBody();
        if (rb == null) {
            // utwórz minimalne body (JSON object), gdy brak
            ObjectSchema obj = new ObjectSchema();
            io.swagger.v3.oas.models.media.MediaType mt = new io.swagger.v3.oas.models.media.MediaType();
            mt.setSchema(obj);
            Content c = new Content();
            c.addMediaType(org.springframework.http.MediaType.APPLICATION_JSON_VALUE, mt);
            rb = new RequestBody().content(c);
            op.setRequestBody(rb);
        }
        // POST/PUT zwykle wymagają body
        if (isPost || isPut) op.getRequestBody().setRequired(true);

        // jeżeli w schemacie body są pola binarne → multipart/form-data
        Content c = op.getRequestBody().getContent();
        if (c == null || c.isEmpty()) {
            c = new Content().addMediaType(
                    org.springframework.http.MediaType.APPLICATION_JSON_VALUE,
                    new io.swagger.v3.oas.models.media.MediaType().schema(new ObjectSchema())
            );
            op.getRequestBody().setContent(c);
        } else {
            io.swagger.v3.oas.models.media.MediaType json = c.get(org.springframework.http.MediaType.APPLICATION_JSON_VALUE);
            if (json != null && containsBinary(json.getSchema())) {
                // przenieś do multipart
                io.swagger.v3.oas.models.media.MediaType mp =
                        new io.swagger.v3.oas.models.media.MediaType().schema(toMultipart(json.getSchema()));
                Content nc = new Content();
                nc.addMediaType(org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE, mp);
                op.getRequestBody().setContent(nc);
            }
        }
    }

    // === 3a) Specjalny kontrakt: POST /api/orders/{orderId}/items ===
    if ("POST".equalsIgnoreCase(ep.http) && ep.path != null && ep.path.matches(".*/api/orders/\\{[^}/]+}/items$")) {
        // 1) Usuń sku/qty z query
        if (op.getParameters() != null && !op.getParameters().isEmpty()) {
            List<Parameter> kept = new ArrayList<>();
            for (Parameter p : op.getParameters()) {
                if ("query".equalsIgnoreCase(nz(p.getIn()))) {
                    String n = nz(p.getName()).toLowerCase(Locale.ROOT);
                    if (n.equals("sku") || n.equals("qty")) continue;
                }
                kept.add(p);
            }
            op.setParameters(kept.isEmpty() ? null : kept);
        }
        // 2) Zdefiniuj jednoznaczne JSON body: { sku: string, qty: integer } (required: oba)
        ObjectSchema obj = new ObjectSchema();
        obj.addProperties("sku", new StringSchema().description("Kod SKU dodawanej pozycji."));
        obj.addProperties("qty", new IntegerSchema().description("Ilość sztuk."));
        obj.setRequired(Arrays.asList("sku","qty"));
        io.swagger.v3.oas.models.media.MediaType mt = new io.swagger.v3.oas.models.media.MediaType().schema(obj);
        Content cnt = new Content().addMediaType(org.springframework.http.MediaType.APPLICATION_JSON_VALUE, mt);
        RequestBody rb = new RequestBody().required(true).content(cnt);
        op.setRequestBody(rb);

        // 3) Odpowiedź 201 Created + prosty example
        ApiResponses rsX = (op.getResponses() == null) ? new ApiResponses() : op.getResponses();
        ApiResponse r201 = new ApiResponse().description("Created");
        io.swagger.v3.oas.models.media.MediaType rmt = new io.swagger.v3.oas.models.media.MediaType();
        ObjectSchema item = new ObjectSchema();
        item.addProperties("id", new StringSchema().format("uuid"));
        item.addProperties("sku", new StringSchema());
        item.addProperties("qty", new IntegerSchema());
        rmt.setSchema(item);
        Map<String,Object> ex = new LinkedHashMap<>();
        ex.put("id", "7b2f3c4e-2a1b-4c5d-9e8f-001122334455");
        ex.put("sku", "ABC-123");
        ex.put("qty", 2);
        rmt.setExample(ex);
        r201.setContent(new Content().addMediaType("application/json", rmt));
        // usuń inne 2xx i wstaw 201
        rsX.keySet().removeIf(k -> k.startsWith("2"));
        rsX.addApiResponse("201", r201);
        op.setResponses(rsX);
    }

    // 4) Odpowiedzi 2xx — porządki
    ApiResponses rs = op.getResponses();
    if (rs == null) {
        rs = new ApiResponses();
        op.setResponses(rs);
    }

    // Usuń treść z 204
    ApiResponse r204 = rs.get("204");
    if (r204 != null && r204.getContent() != null && !r204.getContent().isEmpty()) {
        r204.setContent(null);
        if (safe(r204.getDescription()).isBlank()) r204.setDescription("No Content");
    }

    // Jeśli DELETE → preferuj 204 i usuń inne 2xx
    if (isDelete) {
        ApiResponse nc = rs.get("204");
        if (nc == null) {
            nc = new ApiResponse().description("No Content");
            rs.addApiResponse("204", nc);
        }
        rs.keySet().removeIf(k -> k.startsWith("2") && !"204".equals(k));
    }

    // Jeżeli nie ma żadnej 2xx → dodaj sensowną
    if (rs.keySet().stream().noneMatch(k -> k.startsWith("2"))) {
        if (isDelete || returnsVoid(ep)) {
            rs.addApiResponse("204", new ApiResponse().description("No Content"));
        } else if (isPost) {
            rs.addApiResponse("201", new ApiResponse().description("Created"));
        } else {
            ApiResponse ok = new ApiResponse().description("OK");
            ok.setContent(new Content().addMediaType(
                    "application/json",
                    new io.swagger.v3.oas.models.media.MediaType()
                            .schema(schemaForType(ep.returns != null ? ep.returns.type : null))
            ));
            rs.addApiResponse("200", ok);
        }
    }

    // POST: promuj „puste 200” do 201
    if (isPost && rs.get("200") != null && isEffectivelyEmpty(rs.get("200"))) {
        ApiResponse created = copyResponse(rs.get("200"));
        if (safe(created.getDescription()).isBlank()) created.setDescription("Created");
        rs.remove("200"); rs.addApiResponse("201", created);
    }

    // „puste 200” dla void/DELETE → 204
    if ((isDelete || returnsVoid(ep)) && rs.get("200") != null && isEffectivelyEmpty(rs.get("200"))) {
        rs.remove("200");
        rs.addApiResponse("204", new ApiResponse().description("No Content"));
    }

    // Napraw brak application/json w 2xx z treścią + brak schematu
    for (String code : new ArrayList<>(rs.keySet())) {
        if (!code.startsWith("2")) continue;
        ApiResponse r = rs.get(code);
        if (r == null) continue;
        Content c = r.getContent();
        if (c == null || c.isEmpty()) continue;
        io.swagger.v3.oas.models.media.MediaType mt = c.get("application/json");
        if (mt == null) {
            mt = new io.swagger.v3.oas.models.media.MediaType().schema(new ObjectSchema());
            c.addMediaType("application/json", mt);
            r.setContent(c);
        }
        if (mt.getSchema() == null) {
            mt.setSchema(schemaForType(ep.returns != null ? ep.returns.type : null));
        }
    }

    // Jeśli mamy 200 i 201 z identyczną „pustką” → zostaw preferowaną
    if (rs.get("201") != null && rs.get("200") != null && isEffectivelyEmpty(rs.get("200"))) {
        rs.remove("200");
    }

    // === 4a) Specjalne przykłady: POST /api/users ===
    if ("POST".equalsIgnoreCase(ep.http) && ep.path != null && ep.path.matches(".*/api/users$")) {
        // Request example, jeśli mamy JSON body
        if (op.getRequestBody() != null && op.getRequestBody().getContent() != null) {
            io.swagger.v3.oas.models.media.MediaType reqMt =
                    op.getRequestBody().getContent().get(org.springframework.http.MediaType.APPLICATION_JSON_VALUE);
            if (reqMt != null && reqMt.getExample() == null) {
                Map<String,Object> reqEx = new LinkedHashMap<>();
                reqEx.put("name", "Alice Example");
                reqEx.put("email", "alice@example.com");
                reqMt.setExample(reqEx);
            }
        }
        // Response example w 201 (albo 200, jeśli brak 201)
        ApiResponse r = rs.get("201");
        if (r == null) r = rs.get("200");
        if (r != null && r.getContent() != null) {
            io.swagger.v3.oas.models.media.MediaType mt = r.getContent().get("application/json");
            if (mt != null && mt.getExample() == null) {
                Map<String,Object> respEx = new LinkedHashMap<>();
                respEx.put("id", "9f1a2b3c-1111-2222-3333-444455556666");
                respEx.put("name", "Alice Example");
                respEx.put("email", "alice@example.com");
                mt.setExample(respEx);
            }
        }

        // Napraw brak application/json w 2xx z treścią
        for (String code : new ArrayList<>(rs.keySet())) {
            if (!code.startsWith("2")) continue;
            ApiResponse resp = rs.get(code);
            if (resp == null) continue;

            Content cnt = resp.getContent();
            if (cnt == null || cnt.isEmpty()) continue;

            io.swagger.v3.oas.models.media.MediaType mt = cnt.get("application/json");
            if (mt == null) {
                mt = new io.swagger.v3.oas.models.media.MediaType().schema(new ObjectSchema());
                cnt.addMediaType("application/json", mt);
                resp.setContent(cnt);
            }
            if (mt.getSchema() == null) {
                mt.setSchema(schemaForType(ep.returns != null ? ep.returns.type : null));
            }
        }


    }
}

    private boolean isBetterParam(Parameter a, Parameter b) {
        int as = (a.getSchema() != null ? 1 : 0) + (safe(a.getDescription()).length() > safe(b.getDescription()).length() ? 1 : 0);
        int bs = (b.getSchema() != null ? 1 : 0);
        return as >= bs;
    }

    private Set<String> findPathVars(String path) {
        Set<String> s = new LinkedHashSet<>();
        if (path == null) return s;
        Matcher m = Pattern.compile("\\{([^}/]+)}").matcher(path);
        while (m.find()) s.add(m.group(1));
        return s;
    }

    private boolean containsBinary(Schema<?> schema) {
        if (schema == null) return false;
        if (schema instanceof StringSchema) {
            StringSchema ss = (StringSchema) schema;
            return "binary".equalsIgnoreCase(ss.getFormat()) || "base64".equalsIgnoreCase(ss.getFormat());
        }
        if (schema instanceof ArraySchema) {
            return containsBinary(((ArraySchema) schema).getItems());
        }
        if (schema instanceof ObjectSchema && schema.getProperties() != null) {
            for (Object v : schema.getProperties().values()) {
                if (containsBinary((Schema<?>) v)) return true;
            }
        }
        return false;
    }

    private Schema<?> toMultipart(Schema<?> json) {
        // konwersja: wszystkie string/binary zostają; inne typy jako string (uproszczenie)
        ObjectSchema mp = new ObjectSchema();
        if (json instanceof ObjectSchema && json.getProperties() != null) {
            for (Map.Entry<String, Schema> e : (Set<Map.Entry<String, Schema>>) json.getProperties().entrySet()) {
                Schema<?> v = e.getValue();
                if (v instanceof StringSchema) {
                    mp.addProperties(e.getKey(), v);
                } else if (v instanceof ArraySchema && containsBinary(((ArraySchema) v).getItems())) {
                    ArraySchema arr = new ArraySchema();
                    arr.setItems(new StringSchema().format("binary"));
                    mp.addProperties(e.getKey(), arr);
                } else if (containsBinary(v)) {
                    mp.addProperties(e.getKey(), new StringSchema().format("binary"));
                } else {
                    mp.addProperties(e.getKey(), new StringSchema());
                }
            }
        } else {
            // fallback – pojedynczy plik
            mp.addProperties("file", new StringSchema().format("binary"));
        }
        return mp;
    }

    private boolean returnsVoid(EndpointIR ep) {
        if (ep == null || ep.returns == null || ep.returns.type == null) return true;
        String t = ep.returns.type.trim();
        return t.equals("void") || t.equals("Void") || t.startsWith("ResponseEntity<Void");
    }

    private boolean isEffectivelyEmpty(ApiResponse r) {
        if (r == null) return true;
        Content c = r.getContent();
        if (c == null || c.isEmpty()) return true;
        io.swagger.v3.oas.models.media.MediaType mt = c.get("application/json");
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
            for (Map.Entry<String, io.swagger.v3.oas.models.media.MediaType> e : src.getContent().entrySet()) {
                io.swagger.v3.oas.models.media.MediaType nv = new io.swagger.v3.oas.models.media.MediaType();
                io.swagger.v3.oas.models.media.MediaType v = e.getValue();
                nv.setSchema(v.getSchema());
                nv.setExample(v.getExample());
                nv.setExamples(v.getExamples());
                nv.setEncoding(v.getEncoding());
                nv.setExtensions(v.getExtensions());
                dstC.addMediaType(e.getKey(), nv);
            }
            dst.setContent(dstC);
        }
        dst.setHeaders(src.getHeaders());
        dst.setLinks(src.getLinks());
        dst.setExtensions(src.getExtensions());
        return dst;
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

    private static String sanitizeNlpStrict(String s) {
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

    private static String firstSentenceOf(String text) {
        if (text == null) return "";
        String t = text.trim();
        int idx = t.indexOf('.');
        if (idx < 0) return t;
        return t.substring(0, idx).trim();
    }

    // ============= Mapowanie typów =============
    private static final Set<String> PRIMITIVES = Set.of(
            "byte","short","int","long","float","double","boolean","char"
    );
    private static final Set<String> BUILTINS = Set.of(
            "String","Integer","Long","Float","Double","BigDecimal",
            "Boolean","UUID","Object","Date","LocalDate","LocalDateTime","OffsetDateTime"
    );

    private Schema<?> schemaForType(String typeName) {
        if (typeName == null || typeName.isBlank()) return new ObjectSchema();
        String t = typeName.trim();

        if (t.startsWith("ResponseEntity<") ||
            t.startsWith("Optional<") ||
            t.startsWith("CompletableFuture<") ||
            t.startsWith("Mono<") ||
            t.startsWith("Flux<")) {
            return schemaForType(stripGenerics(t));
        }

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

    private static Schema<?> schemaOrString(Schema<?> s) { return (s == null) ? new StringSchema() : s; }

    private static String normalizeCurl(String s) {
        String t = s.replace("\\n", "\n").replace("\\\"", "\"").trim();
        if (!t.contains("\n")) {
            t = t.replaceAll("\\s+-X\\s+", " \\\n  -X ")
                 .replaceAll("\\s+-H\\s+", " \\\n  -H ")
                 .replaceAll("\\s+-d\\s+", " \\\n  -d ")
                 .replaceAll("\\s+--data-raw\\s+", " \\\n  --data-raw ")
                 .replaceAll("\\s+--data\\s+", " \\\n  --data ")
                 .replaceAll("\\s+--form\\s+", " \\\n  --form ");
        }
        if (!t.startsWith("curl ")) t = "curl " + t;
        return t;
    }

    // ============= Lekki validator cURL =============

    private static final Set<String> DATA_FLAGS = Set.of("-d", "--data", "--data-raw", "--data-binary", "--form", "-F");

    private void validateAndAnnotateCurlExamples(io.swagger.v3.oas.models.Operation op, EndpointIR ep) {
        if (op.getExtensions() == null) return;
        Object xr = op.getExtensions().get("x-request-examples");
        if (!(xr instanceof List)) return;

        @SuppressWarnings("unchecked")
        List<Object> raw = (List<Object>) xr;
        if (raw.isEmpty()) return;

        String pathRegex = templateToRegex(ep.path);
        List<Map<String, Object>> annotated = new ArrayList<>();
        List<String> cleaned = new ArrayList<>();

        for (Object o : raw) {
            String curl = Objects.toString(o, "").trim();
            if (curl.isBlank()) continue;

            CurlInfo info = parseCurl(curl);
            List<String> issues = new ArrayList<>();

            if (info.url == null) issues.add("Brak URL w komendzie cURL.");
            if (info.method == null) issues.add("Brak metody HTTP (ani -X, ani flag danych).");

            // Porównanie metody
            if (info.method != null && ep.http != null) {
                if (!info.method.equalsIgnoreCase(ep.http)) {
                    issues.add("Metoda w cURL (" + info.method + ") ≠ definicja (" + ep.http + ").");
                }
            }

            // Porównanie ścieżki względem szablonu ep.path
            String urlPath = null;
            if (info.url != null) {
                try {
                    URI u = new URI(info.url);
                    urlPath = u.getPath();
                    if (urlPath == null || urlPath.isEmpty()) {
                        issues.add("URL bez ścieżki.");
                    } else {
                        if (!urlPath.matches(pathRegex)) {
                            issues.add("Ścieżka URL nie pasuje do wzorca " + ep.path + ".");
                        }
                    }
                } catch (URISyntaxException e) {
                    issues.add("Nieprawidłowy URL: " + e.getMessage());
                }
            }

            boolean valid = issues.isEmpty();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("curl", curl);
            row.put("method", info.method);
            row.put("url", info.url);
            row.put("path", urlPath);
            row.put("valid", valid);
            if (!issues.isEmpty()) row.put("issues", issues);
            annotated.add(row);

            // Zachowujemy wszystkie (PDF pokaże issues). Jeśli chcesz – filtruj tylko valid.
            cleaned.add(curl);
        }

        // Zastąp listę cURLów wersją po przejściu walidatora (ew. przefiltrowaną)
        if (!cleaned.isEmpty()) {
            op.addExtension("x-request-examples", cleaned);
        }
        // Dodaj szczegóły walidacji
        op.addExtension("x-curl-validated", annotated);
    }

    private static class CurlInfo {
        String method; // GET/POST/PUT/DELETE/PATCH/...
        String url;
    }

    private static CurlInfo parseCurl(String normalizedCurl) {
        CurlInfo out = new CurlInfo();
        String s = normalizedCurl.trim();

        s = s.replace("\r", " ").replace("\n", " ");
        if (s.startsWith("curl ")) s = s.substring(5).trim();

        String[] tokens = s.split("\\s+");
        String method = null;
        String url = null;
        boolean sawDataFlag = false;

        for (int i = 0; i < tokens.length; i++) {
            String t = tokens[i];

            if ("-X".equals(t) && i + 1 < tokens.length) {
                String m = tokens[i + 1].replaceAll("[\"']", "");
                if (!m.isBlank()) method = m.toUpperCase(Locale.ROOT);
                i++;
                continue;
            }

            if (DATA_FLAGS.contains(t)) {
                sawDataFlag = true;
                if (i + 1 < tokens.length) {
                    String next = tokens[i + 1];
                    if (!next.startsWith("-")) i++;
                }
                continue;
            }

            if (t.startsWith("http://") || t.startsWith("https://")) {
                url = stripQuotes(t);
                continue;
            }
            if ((t.startsWith("\"http://") || t.startsWith("\"https://") ||
                 t.startsWith("'http://") || t.startsWith("'https://"))) {
                url = stripQuotes(t);
            }
        }

        if (method == null) method = sawDataFlag ? "POST" : "GET";
        out.method = method;
        out.url = url;
        return out;
    }

    private static String stripQuotes(String s) {
        String t = s.trim();
        if ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("'") && t.endsWith("'"))) {
            return t.substring(1, t.length() - 1);
        }
        return t;
    }

    /** /users/{id}/files/{name} → regex ^/users/[^/]+/files/[^/]+/?$ */
    private static String templateToRegex(String template) {
        if (template == null || template.isBlank()) return ".*";
        String t = template.trim();
        String esc = t.replaceAll("([.\\\\+*?\\[^\\]$(){}=!<>|:-])", "\\\\$1");
        String rx = esc.replaceAll("\\\\\\{[^}]+\\\\\\}", "[^/]+");
        return "^" + rx + "/?$";

    }

        // --- helper: budowa prostego JSON request body z typu parametru ---
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
            // Nie zgadujemy pola — dla DTO zwróć pusty obiekt (lub 1-poziomowy placeholder)
            return Map.of(); // neutralnie
        }

        if (s instanceof ObjectSchema || "object".equalsIgnoreCase(Objects.toString(s.getType(),""))) {
            Map<String, Object> out = new LinkedHashMap<>();
            Object props = s.getProperties();
            if (props instanceof Map) {
                ((Map<String, Schema<?>>) props).forEach((k, v) -> out.put(k, synthExampleNeutral(v)));
            }
            return out.isEmpty() ? Map.of() : out;
        }
        return Map.of();
    }


}
