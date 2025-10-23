package com.mariia.javaapi.code;

import com.mariia.javaapi.code.ir.EndpointIR;
import com.mariia.javaapi.code.ir.ParamIR;
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
import java.util.stream.Stream;

@Service
public class CodeToDocsService {

    public enum DescribeMode { PLAIN, RULES, MT5 }

    private final WebClient nlp;
    private final Duration timeout = Duration.ofSeconds(90);

    public CodeToDocsService(@Qualifier("nlpClient") WebClient nlp) {
        this.nlp = nlp;
    }

    /** Wersja główna – pozwala wskazać tryb generowania opisów. */
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

        // DTO – wykryj realny katalog źródeł projektu
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

            // --- wejście do NLP (tylko dla RULES/MT5; w PLAIN dociągamy paramDocs przez /plain) ---
            Map<String, Object> nlpRes = Collections.emptyMap();
            if (mode != DescribeMode.PLAIN) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("symbol", ep.operationId);
                body.put("kind", "endpoint");
                body.put("signature", ep.http + " " + ep.path);
                body.put("comment", ep.description == null ? "" : ep.description);

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

                nlpRes = callNlp(body, mode);

                if (mode == DescribeMode.MT5) {
                    System.out.println("[MT5 raw map] " + nlpRes);
                    boolean empty = true;
                    for (String k : List.of("shortDescription","mediumDescription","longDescription","returnDoc")) {
                        Object v = nlpRes.get(k);
                        if (v != null && !v.toString().isBlank()) { empty = false; break; }
                    }
                    if (empty) {
                        System.out.println("[MT5 WARN] NLP zwróciło puste pola dla " + ep.operationId);
                    }
                }
            } else {
                // PLAIN: do paramDocs używamy /describe?mode=plain (żeby dostać sensowne opisy parametrów)
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
                nlpRes = callNlp(body, DescribeMode.PLAIN);
            }

            // ➜ odczyt i sanitizacja tekstów z NLP
            String shortD, medD, longD, retDoc;
            if (mode == DescribeMode.MT5) {
                shortD = sanitizeNlpGentle(asStr(nlpRes.get("shortDescription")));
                medD   = sanitizeNlpGentle(asStr(nlpRes.get("mediumDescription")));
                longD  = sanitizeNlpGentle(asStr(nlpRes.get("longDescription")));
                retDoc = sanitizeNlpGentle(asStr(nlpRes.get("returnDoc")));
            } else {
                shortD = sanitizeNlpStrict(asStr(nlpRes.get("shortDescription")));
                medD   = sanitizeNlpStrict(asStr(nlpRes.get("mediumDescription")));
                longD  = sanitizeNlpStrict(asStr(nlpRes.get("longDescription")));
                retDoc = sanitizeNlpStrict(asStr(nlpRes.get("returnDoc")));
            }

            // —— summary/description zależnie od trybu ——
            if (mode == DescribeMode.PLAIN) {
                // „bez opisów” – nic nie ustawiamy
            } else if (mode == DescribeMode.RULES) {
                String summary = firstNonBlank(shortD, medD, longD, ep.description, ep.operationId);
                if (summary != null && !summary.isBlank()) {
                    op.setSummary(trim100(summary));
                }
                String descr = pickByLevel(shortD, medD, longD, level);
                if (descr == null) descr = firstNonBlank(longD, medD, shortD, ep.description);
                if (descr != null && !descr.isBlank()) {
                    op.setDescription(descr);
                }
            } else { // MT5
                // bez fallbacku do ep.description – różnica musi być widoczna
                String summary = firstNonBlank(shortD, medD, longD);
                if (summary != null && !summary.isBlank()) {
                    op.setSummary(trim100(summary));
                }
                String descr = pickByLevel(shortD, medD, longD, level);
                if (descr != null && !descr.isBlank()) {
                    op.setDescription(descr);
                }
            }

            // --- parametry -> OpenAPI ---
            if (ep.params != null && !ep.params.isEmpty()) {
                List<Parameter> ps = new ArrayList<>();
                for (ParamIR p : ep.params) {
                    if ("body".equals(p.in)) {
                        RequestBody rb = new RequestBody()
                                .description(p.description)
                                .required(p.required)
                                .content(new Content().addMediaType(
                                        "application/json",
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
                        ps.add(par);
                    }
                }
                if (!ps.isEmpty()) op.setParameters(ps);
            }

            // --- responses (200) ---
            ApiResponses rs = new ApiResponses();
            ApiResponse ok = new ApiResponse();
            ok.setDescription(retDoc != null ? retDoc : "OK");
            ok.setContent(new Content().addMediaType(
                    "application/json",
                    new io.swagger.v3.oas.models.media.MediaType()
                            .schema(schemaForType(ep.returns != null ? ep.returns.type : null))
            ));
            rs.addApiResponse("200", ok);
            op.setResponses(rs);

            switch (ep.http) {
                case "GET":    pi.setGet(op); break;
                case "POST":   pi.setPost(op); break;
                case "PUT":    pi.setPut(op); break;
                case "DELETE": pi.setDelete(op); break;
                case "PATCH":  pi.setPatch(op); break;
                default:       pi.setGet(op); break;
            }
        }

        Files.createDirectories(outFile.getParent());
        String yaml = Yaml.mapper().writeValueAsString(api);
        Files.writeString(outFile, yaml);
        return outFile;
    }

    /** Zachowujemy starą sygnaturę dla kompatybilności. */
    public Path generateYamlFromCode(List<EndpointIR> eps, String projectName, String level, Path outFile, Path projectRoot) throws Exception {
        return generateYamlFromCode(eps, projectName, level, outFile, projectRoot, DescribeMode.RULES);
    }

    // ============= Pomocnicze (I/O, NLP, DTO-path) =============

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

    @SuppressWarnings("unchecked")
    private Map<String, Object> callNlp(Map<String, Object> body, DescribeMode mode) {
        try {
            // Poprawne mapowanie: plain|rule|mt5
            String modeParam = "rule";
            if (mode == DescribeMode.PLAIN) modeParam = "plain";
            else if (mode == DescribeMode.MT5) modeParam = "mt5";

            return nlp.post()
                    .uri("/describe?mode=" + modeParam)
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

    // ============= Teksty i wybór długości =============

    private static String pickByLevel(String s, String m, String l, String level) {
        String lvl = (level == null || level.isBlank()) ? "medium" : level;
        return switch (lvl) {
            case "short" -> firstNonBlank(s, m, l);
            case "long"  -> firstNonBlank(l, m, s);
            default      -> firstNonBlank(m, s, l);
        };
    }

    // --- DWA warianty sanitizacji: surowszy (RULES/PLAIN) i łagodniejszy (MT5) ---

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

    private static String sanitizeNlpGentle(String s) { // łagodnie dla MT5
        if (s == null) return null;
        String t = s.trim();
        String lower = t.toLowerCase(Locale.ROOT);
        if (t.startsWith("規") || lower.contains("<extra_id_")) {
            return null;
        }
        if (!t.isEmpty() && !t.endsWith(".")) t = t + ".";
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
}
