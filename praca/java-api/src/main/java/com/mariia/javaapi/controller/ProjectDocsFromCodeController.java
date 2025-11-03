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
     * Zwraca jako ZAŁĄCZNIK (download).
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
     * Renderuje i ZWRACA PDF jako ZAŁĄCZNIK (download).
     * Jeżeli YAML nie istnieje – najpierw go generuje (AI).
     * Parametr level: beginner | intermediate | advanced
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

    /* =========================
       NOWE endpointy do PODGLĄDU
       ========================= */

    /**
     * Web-podgląd PDF (Content-Disposition: inline).
     * Jeśli PDF/YAML nie istnieją – generuje jak w /docs/pdf.
     */
    @GetMapping(value = "/{id}/docs/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> viewPdfInline(
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

        if (!Files.exists(aiPdf)) {
            pdfDocService.renderPdfFromYaml(aiYaml, aiPdf);
        }

        return asInline(aiPdf, "openapi.ai.pdf", MediaType.APPLICATION_PDF_VALUE);
    }

    /**
     * Web-podgląd YAML (inline).
     */
    @GetMapping(value = "/{id}/docs/yaml", produces = "text/yaml")
    public ResponseEntity<byte[]> viewYamlInline(@PathVariable String id) throws IOException {
        Path projectDir = storage.resolveProjectDir(id);
        if (!Files.exists(projectDir)) {
            return ResponseEntity.status(404)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(("Project not found: " + id).getBytes());
        }
        Path aiYaml = projectDir.resolve("openapi.ai.yaml");
        if (!Files.exists(aiYaml)) {
            return ResponseEntity.status(404)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("YAML not found. Generate it first.".getBytes());
        }
        return asInline(aiYaml, "openapi.ai.yaml", "text/yaml");
    }

    /**
     * Pobierz YAML (download) – wygodny bliźniaczy endpoint.
     */
    @GetMapping(value = "/{id}/docs/yaml/download")
    public ResponseEntity<byte[]> downloadYaml(@PathVariable String id) throws IOException {
        Path projectDir = storage.resolveProjectDir(id);
        if (!Files.exists(projectDir)) {
            return ResponseEntity.status(404)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(("Project not found: " + id).getBytes());
        }
        Path aiYaml = projectDir.resolve("openapi.ai.yaml");
        if (!Files.exists(aiYaml)) {
            return ResponseEntity.status(404)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("YAML not found. Generate it first.".getBytes());
        }
        return asAttachment(aiYaml, "openapi.ai.yaml", "text/yaml");
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

    private static ResponseEntity<byte[]> asInline(Path path, String filename, String contentType) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.parseMediaType(contentType))
                .body(bytes);
    }
}
