package com.mariia.javaapi.controller;

import com.mariia.javaapi.code.CodeToDocsService;
import com.mariia.javaapi.code.JavaSpringParser;
import com.mariia.javaapi.code.ir.EndpointIR;
import com.mariia.javaapi.uploads.UploadStorage;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@RestController
@RequestMapping("/api/projects")
public class ProjectDocsFromCodeController {

    private final UploadStorage storage;
    private final CodeToDocsService code2docs;
    private final JavaSpringParser parser = new JavaSpringParser();

    public ProjectDocsFromCodeController(UploadStorage storage, CodeToDocsService code2docs) {
        this.storage = storage;
        this.code2docs = code2docs;
    }

    /** Generuje openapi.generated.yaml z kodu źródłowego + NLP i zwraca treść YAML. */
    @PostMapping(value = "/{id}/docs/from-code", produces = "text/yaml")
    public ResponseEntity<String> fromCode(
            @PathVariable String id,
            @RequestParam(defaultValue = "medium") String level
    ) throws Exception {

        Path projectDir = storage.resolveProjectDir(id);
        if (!Files.exists(projectDir)) {
            return ResponseEntity.status(404)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("Project not found: " + id);
        }

        // 1) Parsowanie kodu (Java/Spring) -> IR endpointów
        List<EndpointIR> endpoints = parser.parseProject(projectDir);
        if (endpoints.isEmpty()) {
            return ResponseEntity.badRequest()
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("No endpoints found in source code.");
        }

        // 2) Generacja OpenAPI + wzbogacenie NLP (przekazujemy projectDir!)
        Path out = storage.resolveGeneratedSpecPath(id);
        Files.createDirectories(out.getParent());
        code2docs.generateYamlFromCode(endpoints, "Project " + id, level, out, projectDir);

        // 3) Zwróć YAML
        String yaml = Files.readString(out);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/yaml"))
                .body(yaml);
    }
}
