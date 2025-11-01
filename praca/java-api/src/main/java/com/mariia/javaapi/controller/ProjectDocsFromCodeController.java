package com.mariia.javaapi.controller;

import com.mariia.javaapi.code.CodeToDocsService;
import com.mariia.javaapi.code.JavaSpringParser;
import com.mariia.javaapi.code.ir.EndpointIR;
import com.mariia.javaapi.docs.PdfDocService;
import com.mariia.javaapi.uploads.UploadStorage;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/api/projects")
public class ProjectDocsFromCodeController {

    private final UploadStorage storage;
    private final CodeToDocsService code2docs;
    private final JavaSpringParser parser = new JavaSpringParser();
    private final PdfDocService pdfDocService;

    public ProjectDocsFromCodeController(
            UploadStorage storage,
            CodeToDocsService code2docs,
            PdfDocService pdfDocService
    ) {
        this.storage = storage;
        this.code2docs = code2docs;
        this.pdfDocService = pdfDocService;
    }

    /**
     * Generuje OpenAPI (AI) z kodu i zwraca pojedynczy plik: openapi.ai.yaml
     * Parametr level: beginner | intermediate | advanced (domyślnie: intermediate)
     */
    @PostMapping(value = "/{id}/docs/from-code")
    public ResponseEntity<byte[]> fromCode(
            @PathVariable String id,
            @RequestParam(defaultValue = "intermediate") String level
    ) throws Exception {
        Path projectDir = storage.resolveProjectDir(id);
        if (!Files.exists(projectDir)) {
            return ResponseEntity.status(404)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(("Project not found: " + id).getBytes());
        }

        List<EndpointIR> endpoints = parser.parseProject(projectDir);
        if (endpoints.isEmpty()) {
            return ResponseEntity.badRequest()
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("No endpoints found in source code.".getBytes());
        }

        Files.createDirectories(projectDir);
        Path aiYaml = projectDir.resolve("openapi.ai.yaml");

        // Zawsze generujemy świeży YAML w trybie AI dla zadanego poziomu
        code2docs.generateYamlFromCode(
                endpoints, "Project " + id, normalizeAudience(level),
                aiYaml, projectDir,
                CodeToDocsService.DescribeMode.AI
        );

        return asAttachment(aiYaml, "openapi.ai.yaml", "text/yaml");
    }

    /**
     * Renderuje i zwraca PDF na podstawie AI YAML: openapi.ai.pdf
     * Jeżeli YAML nie istnieje – najpierw go generuje (AI).
     * Parametr level: beginner | intermediate | advanced (domyślnie: intermediate)
     */
    @PostMapping(value = "/{id}/docs/pdf")
    public ResponseEntity<byte[]> pdfFrom(
            @PathVariable String id,
            @RequestParam(defaultValue = "intermediate") String level
    ) throws Exception {
        Path projectDir = storage.resolveProjectDir(id);
        if (!Files.exists(projectDir)) {
            return ResponseEntity.status(404)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(("Project not found: " + id).getBytes());
        }

        Path aiYaml = projectDir.resolve("openapi.ai.yaml");
        Path aiPdf  = projectDir.resolve("openapi.ai.pdf");

        // Jeśli YAML nie istnieje – wygeneruj go w AI dla wybranego poziomu
        if (!Files.exists(aiYaml)) {
            List<EndpointIR> endpoints = parser.parseProject(projectDir);
            if (endpoints.isEmpty()) {
                return ResponseEntity.badRequest()
                        .contentType(MediaType.TEXT_PLAIN)
                        .body("No endpoints found in source code.".getBytes());
            }
            code2docs.generateYamlFromCode(
                    endpoints, "Project " + id, normalizeAudience(level),
                    aiYaml, projectDir,
                    CodeToDocsService.DescribeMode.AI
            );
        }

        // Render PDF z YAML
        pdfDocService.renderPdfFromYaml(aiYaml, aiPdf);
        return asAttachment(aiPdf, "openapi.ai.pdf", MediaType.APPLICATION_PDF_VALUE);
    }

    // —— helpers ——
    private static String normalizeAudience(String s) {
        String v = (s == null) ? "intermediate" : s.trim().toLowerCase(Locale.ROOT);
        switch (v) {
            case "short":
            case "junior":
            case "beginner": return "beginner";
            case "long":
            case "senior":
            case "advanced": return "advanced";
            default: return "intermediate";
        }
    }

    private static ResponseEntity<byte[]> asAttachment(Path path, String filename, String contentType) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.parseMediaType(contentType))
                .body(bytes);
    }
}
