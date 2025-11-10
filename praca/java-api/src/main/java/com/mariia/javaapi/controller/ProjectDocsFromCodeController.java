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

    // =========================================================
    //  YAML z kodu (AI)
    // =========================================================

    @PostMapping(value = "/{id}/docs/from-code")
    public ResponseEntity<byte[]> fromCode(
            @PathVariable String id,
            @RequestParam(defaultValue = "intermediate") String level
    ) throws Exception {

        Path projectDir = storage.resolveProjectDir(id);
        if (!Files.exists(projectDir)) {
            return notFound("Project not found: " + id);
        }

        List<EndpointIR> endpoints = parser.parseProject(projectDir);
        if (endpoints.isEmpty()) {
            return badRequest("No endpoints found in source code.");
        }

        Files.createDirectories(projectDir);

        String audience = normalizeAudience(level);
        String projectName = resolveProjectName(id);
        Path aiYaml = projectDir.resolve("openapi_" + audience + ".yaml");

        code2docs.generateYamlFromCode(
                endpoints,
                projectName,
                audience,
                aiYaml,
                projectDir,
                CodeToDocsService.DescribeMode.AI
        );

        String fileName = "openapi_" + audience + ".yaml";
        return asAttachment(aiYaml, fileName, "text/yaml");
    }

    // =========================================================
    //  PDF (download)
    // =========================================================

    @PostMapping(value = "/{id}/docs/pdf")
    public ResponseEntity<byte[]> pdfFrom(
            @PathVariable String id,
            @RequestParam(defaultValue = "intermediate") String level
    ) throws Exception {

        Path projectDir = storage.resolveProjectDir(id);
        if (!Files.exists(projectDir)) {
            return notFound("Project not found: " + id);
        }

        List<EndpointIR> endpoints = parser.parseProject(projectDir);
        if (endpoints.isEmpty()) {
            return badRequest("No endpoints found in source code.");
        }

        Files.createDirectories(projectDir);

        String audience = normalizeAudience(level);
        String projectName = resolveProjectName(id);
        Path aiYaml = projectDir.resolve("openapi_" + audience + ".yaml");
        Path aiPdf  = projectDir.resolve("openapi_" + audience + ".pdf");

        code2docs.generateYamlFromCode(
                endpoints,
                projectName,
                audience,
                aiYaml,
                projectDir,
                CodeToDocsService.DescribeMode.AI
        );

        pdfDocService.renderPdfFromYaml(aiYaml, aiPdf);

        String fileName = "openapi_" + audience + ".pdf";
        return asAttachment(aiPdf, fileName, MediaType.APPLICATION_PDF_VALUE);
    }

    // =========================================================
    //  PDF (inline preview)
    // =========================================================

    @GetMapping(value = "/{id}/docs/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> viewPdfInline(
            @PathVariable String id,
            @RequestParam(defaultValue = "intermediate") String level
    ) throws Exception {

        Path projectDir = storage.resolveProjectDir(id);
        if (!Files.exists(projectDir)) {
            return notFound("Project not found: " + id);
        }

        List<EndpointIR> endpoints = parser.parseProject(projectDir);
        if (endpoints.isEmpty()) {
            return badRequest("No endpoints found in source code.");
        }

        Files.createDirectories(projectDir);

        String audience = normalizeAudience(level);
        String projectName = resolveProjectName(id);
        Path aiYaml = projectDir.resolve("openapi_" + audience + ".yaml");
        Path aiPdf  = projectDir.resolve("openapi_" + audience + ".pdf");

        code2docs.generateYamlFromCode(
                endpoints,
                projectName,
                audience,
                aiYaml,
                projectDir,
                CodeToDocsService.DescribeMode.AI
        );

        pdfDocService.renderPdfFromYaml(aiYaml, aiPdf);

        String fileName = "openapi_" + audience + ".pdf";
        return asInline(aiPdf, fileName, MediaType.APPLICATION_PDF_VALUE);
    }

    // =========================================================
    //  YAML (inline + download)
    // =========================================================

    @GetMapping(value = "/{id}/docs/yaml", produces = "text/yaml")
    public ResponseEntity<byte[]> viewYamlInline(
            @PathVariable String id,
            @RequestParam(defaultValue = "intermediate") String level
    ) throws Exception {

        Path projectDir = storage.resolveProjectDir(id);
        if (!Files.exists(projectDir)) {
            return notFound("Project not found: " + id);
        }

        List<EndpointIR> endpoints = parser.parseProject(projectDir);
        if (endpoints.isEmpty()) {
            return badRequest("No endpoints found in source code.");
        }

        Files.createDirectories(projectDir);

        String audience = normalizeAudience(level);
        String projectName = resolveProjectName(id);
        Path aiYaml = projectDir.resolve("openapi_" + audience + ".yaml");

        code2docs.generateYamlFromCode(
                endpoints,
                projectName,
                audience,
                aiYaml,
                projectDir,
                CodeToDocsService.DescribeMode.AI
        );

        String fileName = "openapi_" + audience + ".yaml";
        return asInline(aiYaml, fileName, "text/yaml");
    }

    @GetMapping(value = "/{id}/docs/yaml/download")
    public ResponseEntity<byte[]> downloadYaml(
            @PathVariable String id,
            @RequestParam(defaultValue = "intermediate") String level
    ) throws Exception {

        Path projectDir = storage.resolveProjectDir(id);
        if (!Files.exists(projectDir)) {
            return notFound("Project not found: " + id);
        }

        List<EndpointIR> endpoints = parser.parseProject(projectDir);
        if (endpoints.isEmpty()) {
            return badRequest("No endpoints found in source code.");
        }

        Files.createDirectories(projectDir);

        String audience = normalizeAudience(level);
        String projectName = resolveProjectName(id);
        Path aiYaml = projectDir.resolve("openapi_" + audience + ".yaml");

        code2docs.generateYamlFromCode(
                endpoints,
                projectName,
                audience,
                aiYaml,
                projectDir,
                CodeToDocsService.DescribeMode.AI
        );

        String fileName = "openapi_" + audience + ".yaml";
        return asAttachment(aiYaml, fileName, "text/yaml");
    }

    // =========================================================
    //  Helpers
    // =========================================================

    private String resolveProjectName(String id) {
        // TODO: zaimplementuj w UploadStorage realne mapowanie id -> bazowa nazwa zipa.
        // Tymczasowo: jeśli masz taką metodę, użyj jej tutaj.
        String fromStorage = storage.getProjectName(id); // dodaj tę metodę w UploadStorage
        if (fromStorage != null && !fromStorage.isBlank()) {
            return fromStorage;
        }
        return id; // fallback awaryjny
    }

    private static String normalizeAudience(String s) {
        String v = (s == null) ? "intermediate" : s.trim().toLowerCase(Locale.ROOT);
        switch (v) {
            case "short":
            case "junior":
            case "beginner":
                return "beginner";
            case "long":
            case "senior":
            case "advanced":
                return "advanced";
            default:
                return "intermediate";
        }
    }

    private static ResponseEntity<byte[]> notFound(String msg) {
        return ResponseEntity.status(404)
                .contentType(MediaType.TEXT_PLAIN)
                .body(msg.getBytes());
    }

    private static ResponseEntity<byte[]> badRequest(String msg) {
        return ResponseEntity.badRequest()
                .contentType(MediaType.TEXT_PLAIN)
                .body(msg.getBytes());
    }

    private static ResponseEntity<byte[]> asAttachment(Path path, String filename, String contentType) throws Exception {
        byte[] bytes = Files.readAllBytes(path);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.parseMediaType(contentType))
                .body(bytes);
    }

    private static ResponseEntity<byte[]> asInline(Path path, String filename, String contentType) throws Exception {
        byte[] bytes = Files.readAllBytes(path);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.parseMediaType(contentType))
                .body(bytes);
    }
}
