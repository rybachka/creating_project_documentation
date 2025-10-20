package com.mariia.javaapi.code;

import com.mariia.javaapi.code.ir.EndpointIR;
import com.mariia.javaapi.code.ir.ParamIR;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.core.util.Yaml;
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

    private final WebClient nlp;
    private final Duration timeout = Duration.ofSeconds(15);

    public CodeToDocsService(@Qualifier("nlpClient") WebClient nlp) {
        this.nlp = nlp;
    }

    /** Rekomendowana wersja — z przekazanym katalogiem projektu. */
    public Path generateYamlFromCode(List<EndpointIR> eps, String projectName, String level, Path outFile, Path projectRoot) throws Exception {
        OpenAPI api = new OpenAPI().info(new Info().title(projectName + "-API").version("1.0.0"));
        api.setPaths(new Paths());

        // 🔹 DTO – wykryj realny katalog źródeł projektu (uwzględnij zagnieżdżone foldery po uploadzie)
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

            Map<String, Object> res = callNlp(body);
            String chosen = pickLevel(res, level);
            if (chosen != null && !chosen.isBlank()) {
                op.setDescription(chosen);
                op.setSummary(chosen.length() > 100 ? chosen.substring(0, 100) + "…" : chosen);
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

            // --- responses ---
            ApiResponses rs = new ApiResponses();
            ApiResponse ok = new ApiResponse();
            String retDoc = res != null ? Objects.toString(res.get("returnDoc"), null) : null;
            ok.setDescription(retDoc != null ? retDoc : "OK");
            ok.setContent(new Content().addMediaType(
                    "application/json",
                    new io.swagger.v3.oas.models.media.MediaType()
                            .schema(schemaForType(ep.returns != null ? ep.returns.type : null))
            ));
            rs.addApiResponse("200", ok);
            op.setResponses(rs);

            switch (ep.http) {
                case "GET" -> pi.setGet(op);
                case "POST" -> pi.setPost(op);
                case "PUT" -> pi.setPut(op);
                case "DELETE" -> pi.setDelete(op);
                case "PATCH" -> pi.setPatch(op);
                default -> pi.setGet(op);
            }
        }

        Files.createDirectories(outFile.getParent());
        String yaml = Yaml.mapper().writeValueAsString(api);
        Files.writeString(outFile, yaml);
        return outFile;
    }

    /** Zachowujemy starą sygnaturę dla kompatybilności. */
    public Path generateYamlFromCode(List<EndpointIR> eps, String projectName, String level, Path outFile) throws Exception {
        return generateYamlFromCode(eps, projectName, level, outFile, null);
    }

    /** Znajdź realny katalog źródeł projektu.
     *  1) jeśli podano projectRoot: spróbuj projectRoot/src/main/java, potem projectRoot/target/classes;
     *  2) jeśli nie istnieje – rekurencyjnie znajdź pierwszy katalog zawierający 'src/main/java';
     *  3) jeśli i to się nie uda – znajdź pierwszy katalog zawierający jakiekolwiek pliki .java;
     *  4) ostatecznie zwróć baseDir. */
    private Path resolveProjectPath(Path projectRoot) {
        try {
            Path base = (projectRoot != null) ? projectRoot : Path.of(System.getProperty("user.dir"));
            // 1) typowe miejsca
            Path src = base.resolve("src/main/java");
            if (Files.exists(src)) return src;
            Path classes = base.resolve("target/classes");
            if (Files.exists(classes)) return classes;

            // 2) szukaj 'src/main/java' w podkatalogach (do głębokości 4)
            try (Stream<Path> s = Files.find(base, 4, (p, a) ->
                    a.isDirectory() && p.endsWith("src/main/java"))) {
                Optional<Path> hit = s.findFirst();
                if (hit.isPresent()) return hit.get();
            }

            // 3) znajdź jakikolwiek katalog z plikami .java
            try (Stream<Path> s2 = Files.find(base, 4, (p, a) ->
                    a.isRegularFile() && p.toString().endsWith(".java"))) {
                Optional<Path> anyJava = s2.findFirst();
                if (anyJava.isPresent()) return anyJava.get().getParent();
            }

            // 4) fallback
            return base;
        } catch (IOException e) {
            System.err.println("[WARN] resolveProjectPath error: " + e.getMessage());
            return (projectRoot != null) ? projectRoot : Path.of(System.getProperty("user.dir"));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> callNlp(Map<String, Object> body) {
        try {
            return nlp.post()
                    .uri("/describe")
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

    private String pickLevel(Map<String, Object> res, String level) {
        if (res == null || res.isEmpty()) return null;
        String s = asStr(res.get("shortDescription"));
        String m = asStr(res.get("mediumDescription"));
        String l = asStr(res.get("longDescription"));
        String lvl = (level == null || level.isBlank()) ? "medium" : level;

        return switch (lvl) {
            case "short" -> (s != null ? s : (m != null ? m : l));
            case "long" -> (l != null ? l : (m != null ? m : s));
            default -> (m != null ? m : (s != null ? s : l));
        };
    }

    private static String asStr(Object o) { return (o == null) ? null : Objects.toString(o, null); }

    // ===== mapowanie typów =====

    private static final Set<String> PRIMITIVES = Set.of(
            "byte","short","int","long","float","double","boolean","char"
    );
    private static final Set<String> BUILTINS = Set.of(
            "String","Integer","Long","Float","Double","BigDecimal",
            "Boolean","UUID","Object","Date","LocalDate","LocalDateTime","OffsetDateTime"
    );

    /** Mapowanie typu na Schema. $ref tylko dla własnych DTO. */
    private Schema schemaForType(String typeName) {
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

        return new Schema().$ref("#/components/schemas/" + simple);
    }

    private static Schema primitiveToSchema(String p) {
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

    private static Schema builtinToSchema(String s) {
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
