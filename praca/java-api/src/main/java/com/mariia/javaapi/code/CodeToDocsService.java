package com.mariia.javaapi.code;

import com.mariia.javaapi.code.ir.EndpointIR;
import com.mariia.javaapi.code.ir.ParamIR;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.BooleanSchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.NumberSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.core.util.Yaml;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;                  // <— Spring MediaType dla WebClient
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

@Service
public class CodeToDocsService {

    private final WebClient nlp;
    private final Duration timeout = Duration.ofSeconds(15);

    public CodeToDocsService(@Qualifier("nlpClient") WebClient nlp) {
        this.nlp = nlp;
    }

    public Path generateYamlFromCode(List<EndpointIR> eps, String projectName, String level, Path outFile) throws Exception {
        OpenAPI api = new OpenAPI().info(new Info().title(projectName + "-API").version("1.0.0"));
        api.setPaths(new Paths());

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
                                        new io.swagger.v3.oas.models.media.MediaType()   // <— w pełni kwalifikowana
                                                .schema(new ObjectSchema())
                                ));
                        op.setRequestBody(rb);
                    } else {
                        Parameter par = new Parameter()
                                .name(p.name)
                                .in(p.in)
                                .required(p.required)
                                .description(p.description);
                        par.setSchema(guessSchema(p.type));
                        ps.add(par);
                    }
                }
                if (!ps.isEmpty()) op.setParameters(ps);
            }

            // --- responses (MVP) ---
            ApiResponses rs = new ApiResponses();
            ApiResponse ok = new ApiResponse();
            String retDoc = res != null ? Objects.toString(res.get("returnDoc"), null) : null;
            ok.setDescription(retDoc != null ? retDoc : "OK");
            ok.setContent(new Content().addMediaType(
                    "application/json",
                    new io.swagger.v3.oas.models.media.MediaType()       // <— w pełni kwalifikowana
                            .schema(new ObjectSchema())
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> callNlp(Map<String, Object> body) {
        try {
            return nlp.post()
                    .uri("/describe")
                    .contentType(MediaType.APPLICATION_JSON)   // <— to jest Spring MediaType
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
            case "long"  -> (l != null ? l : (m != null ? m : s));
            default      -> (m != null ? m : (s != null ? s : l));
        };
    }

    private static String asStr(Object o) { return (o == null) ? null : Objects.toString(o, null); }

    private static Schema<?> guessSchema(String t) {
        String low = t == null ? "" : t.toLowerCase(Locale.ROOT);
        if (low.contains("string")) return new StringSchema();
        if (low.contains("int") || low.contains("long")) return new IntegerSchema();
        if (low.contains("double") || low.contains("float") || low.contains("bigdec")) return new NumberSchema();
        if (low.contains("bool")) return new BooleanSchema();
        return new ObjectSchema();
    }
}
