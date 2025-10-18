package com.mariia.javaapi.docs;

import io.swagger.v3.core.util.Yaml;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/**
 * Wzbogaca specyfikację OpenAPI o opisy wygenerowane przez mikroserwis NLP.
 */
@Service
public class EnrichmentService {

    private final WebClient nlpClient;
    private final Duration timeout;

    public EnrichmentService(@Value("${nlp.url:http://python-nlp:8000}") String baseUrl) {
        this.nlpClient = WebClient.builder().baseUrl(baseUrl).build();
        this.timeout = Duration.ofSeconds(10);
    }

    /**
     * Wczytaj OpenAPI z pliku, wywołaj NLP dla każdej operacji i zapisz YAML
     * do outFile (np. .../openapi.enriched.yaml). Zwraca ścieżkę do zapisanego pliku.
     */
    public Path enrich(Path openapiFile, String level, Path outFile) throws IOException {
        OpenAPI openAPI = readOpenApi(openapiFile);
        if (openAPI == null) {
            throw new IllegalStateException("Cannot parse OpenAPI: " + openapiFile);
        }

        if (openAPI.getPaths() != null) {
            openAPI.getPaths().forEach((path, item) -> {
                // dla każdej operacji HTTP na danej ścieżce
                item.readOperationsMap().forEach((httpMethod, operation) -> {
                    final String method = httpMethod.name(); // GET/POST/...
                    final String operationId = (operation.getOperationId() != null && !operation.getOperationId().isBlank())
                            ? operation.getOperationId()
                            : (method + " " + path);

                    final String signature = method + " " + path; // np. "GET /users/{id}"
                    final String comment = coalesce(operation.getDescription(), operation.getSummary());

                    // parametry do NLP
                    List<Map<String, Object>> nlpParams = new ArrayList<>();
                    if (operation.getParameters() != null) {
                        for (Parameter p : operation.getParameters()) {
                            String pType = (p.getSchema() != null) ? Objects.toString(p.getSchema().getType(), null) : null;
                            nlpParams.add(Map.of(
                                    "name", Objects.toString(p.getName(), ""),
                                    "type", pType,
                                    "description", Objects.toString(p.getDescription(), "")
                            ));
                        }
                    }

                    // minimalne "returns" – można rozbudować o schema z 200/201
                    Map<String, Object> returns = Map.of("type", "object");

                    // request do NLP
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("symbol", operationId);
                    body.put("kind", "endpoint");
                    body.put("signature", signature);
                    body.put("comment", comment == null ? "" : comment);
                    body.put("params", nlpParams);
                    body.put("returns", returns);

                    // odpowiedź z NLP (UWAGA: tu wołamy BEZ prefiksu /nlp – to jest baza python-nlp)
                    Map<?, ?> result = callNlp(body);

                    if (result != null) {
                        String shortDesc  = toStr(result.get("shortDescription"));
                        String mediumDesc = toStr(result.get("mediumDescription"));
                        String longDesc   = toStr(result.get("longDescription"));

                        String chosen = pick(shortDesc, mediumDesc, longDesc, level);

                        // wstaw opis (description/summary – do wyboru; tutaj description)
                        if (chosen != null && !chosen.isBlank()) {
                            operation.setDescription(chosen);
                        }

                        // podmień opisy parametrów (jeśli NLP je zwróciło)
                        Object pd = result.get("paramDocs");
                        if (pd instanceof List<?> list && operation.getParameters() != null) {
                            Map<String, String> byName = new HashMap<>();
                            for (Object o : list) {
                                if (o instanceof Map<?, ?> m) {
                                    String n = toStr(m.get("name"));
                                    String d = toStr(m.get("doc"));
                                    if (n != null && !n.isBlank() && d != null && !d.isBlank()) {
                                        byName.put(n, d);
                                    }
                                }
                            }
                            for (Parameter p : operation.getParameters()) {
                                String n = Objects.toString(p.getName(), "");
                                if (byName.containsKey(n)) {
                                    p.setDescription(byName.get(n));
                                }
                            }
                        }

                        // przykład: opis odpowiedzi 200 (jeśli istnieje i NLP zwróciło returnDoc)
                        ApiResponses rs = operation.getResponses();
                        String retDoc = toStr(result.get("returnDoc"));
                        if (rs != null && rs.get("200") != null && retDoc != null && !retDoc.isBlank()) {
                            rs.get("200").setDescription(retDoc);
                        }
                    }
                });
            });
        }

        // zapis do YAML
        String yaml = Yaml.mapper().writeValueAsString(openAPI);
        Files.createDirectories(outFile.getParent());
        Files.writeString(outFile, yaml);
        return outFile;
    }

    // ---------- helpers ----------

    @Nullable
    private OpenAPI readOpenApi(Path file) {
        ParseOptions opts = new ParseOptions();
        opts.setResolve(true);
        opts.setFlatten(true);
        SwaggerParseResult r = new OpenAPIV3Parser().readLocation(file.toString(), null, opts);
        if (r == null || r.getOpenAPI() == null) {
            throw new IllegalStateException("Cannot parse OpenAPI: " + file);
        }
        if (r.getMessages() != null && !r.getMessages().isEmpty()) {
            // wyrzuć komunikat z listą błędów – zobaczysz je w logach i na stronie 500
            throw new IllegalStateException("Cannot parse OpenAPI: " + file + " ; messages=" + r.getMessages());
        }
        return r.getOpenAPI();
    }


    @SuppressWarnings("unchecked")
    private Map<?, ?> callNlp(Map<String, Object> body) {
        return nlpClient.post()
                .uri("/describe") // <— WAŻNE: bez /nlp, bo to bezpośrednio do python-nlp
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .block(timeout);
    }

    private static String pick(String s, String m, String l, String level) {
        String lvl = (level == null || level.isBlank()) ? "medium" : level;
        return switch (lvl) {
            case "short" -> (s != null) ? s : (m != null ? m : l);
            case "long"  -> (l != null) ? l : (m != null ? m : s);
            default      -> (m != null) ? m : (s != null ? s : l);
        };
    }

    private static String toStr(Object o) { return (o == null) ? null : Objects.toString(o, null); }

    private static String coalesce(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }


// EnrichmentService.java – DOPISZ tę metodę obok istniejącej enrich(...)


public Map<String, Object> enrichToMap(Path openapiFile, String level) throws IOException {
    OpenAPI openAPI = readOpenApi(openapiFile);
    if (openAPI == null) {
        throw new IllegalStateException("Cannot parse OpenAPI: " + openapiFile);
    }

    if (openAPI.getPaths() != null) {
        openAPI.getPaths().forEach((path, item) ->
            item.readOperationsMap().forEach((httpMethod, operation) -> {
                final String method = httpMethod.name();
                final String operationId =
                    (operation.getOperationId() != null && !operation.getOperationId().isBlank())
                    ? operation.getOperationId() : (method + " " + path);

                final String signature = method + " " + path;
                final String comment   = coalesce(operation.getDescription(), operation.getSummary());

                var nlpParams = new ArrayList<Map<String,Object>>();
                if (operation.getParameters() != null) {
                    for (Parameter p : operation.getParameters()) {
                        String pType = (p.getSchema() != null) ? Objects.toString(p.getSchema().getType(), null) : null;
                        nlpParams.add(Map.of(
                            "name", Objects.toString(p.getName(), ""),
                            "type", pType,
                            "description", Objects.toString(p.getDescription(), "")
                        ));
                    }
                }

                Map<String,Object> body = new LinkedHashMap<>();
                body.put("symbol", operationId);
                body.put("kind", "endpoint");
                body.put("signature", signature);
                body.put("comment", comment == null ? "" : comment);
                body.put("params", nlpParams);
                body.put("returns", Map.of("type", "object"));

                Map<?,?> result = callNlp(body);
                if (result != null) {
                    String chosen = pick(
                        toStr(result.get("shortDescription")),
                        toStr(result.get("mediumDescription")),
                        toStr(result.get("longDescription")),
                        level
                    );
                    if (chosen != null && !chosen.isBlank()) {
                        operation.setDescription(chosen);
                    }

                    Object pd = result.get("paramDocs");
                    if (pd instanceof List<?> list && operation.getParameters() != null) {
                        var byName = new HashMap<String,String>();
                        for (Object o : list) {
                            if (o instanceof Map<?,?> m) {
                                String n = toStr(m.get("name"));
                                String d = toStr(m.get("doc"));
                                if (n != null && !n.isBlank() && d != null && !d.isBlank()) byName.put(n, d);
                            }
                        }
                        for (Parameter p : operation.getParameters()) {
                            String n = Objects.toString(p.getName(), "");
                            if (byName.containsKey(n)) p.setDescription(byName.get(n));
                        }
                    }

                    var rs = operation.getResponses();
                    String retDoc = toStr(result.get("returnDoc"));
                    if (rs != null && rs.get("200") != null && retDoc != null && !retDoc.isBlank()) {
                        rs.get("200").setDescription(retDoc);
                    }
                }
            })
        );
    }

    // Zamiana OpenAPI -> Map, aby Spring zwrócił JSON
    return Yaml.mapper().convertValue(openAPI, Map.class);
}
}
