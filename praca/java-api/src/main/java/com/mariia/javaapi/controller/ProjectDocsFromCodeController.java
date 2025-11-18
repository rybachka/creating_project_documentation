package com.mariia.javaapi.controller;

import com.mariia.javaapi.code.CodeToDocsService;
import com.mariia.javaapi.code.JavaSpringParser;
import com.mariia.javaapi.code.ir.EndpointIR;
import com.mariia.javaapi.docs.EditableDocsService;
import com.mariia.javaapi.docs.PdfDocService;
import com.mariia.javaapi.uploads.UploadStorage;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@RestController
@RequestMapping("/api/projects")
public class ProjectDocsFromCodeController {

    private final UploadStorage storage;
    private final CodeToDocsService code2docs;
    private final JavaSpringParser parser = new JavaSpringParser();
    private final PdfDocService pdfDocService;
    private final EditableDocsService editableDocsService;

    public ProjectDocsFromCodeController(
            UploadStorage storage,
            CodeToDocsService code2docs,
            PdfDocService pdfDocService,
            EditableDocsService editableDocsService
    ) {
        this.storage = storage;
        this.code2docs = code2docs;
        this.pdfDocService = pdfDocService;
        this.editableDocsService = editableDocsService;
    }

    // =========================================================
    //  YAML z kodu (AI) – download
    // =========================================================

    @PostMapping(value = "/{id}/docs/from-code")
    public ResponseEntity<byte[]> fromCode(
            @PathVariable String id,
            @RequestParam(defaultValue = "advanced") String level
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

        String audience = level; // beginner / advanced
        String projectName = resolveProjectName(id);
        Path aiYaml = projectDir.resolve("openapi_" + audience + ".yaml");

        code2docs.generateYamlFromCode(
                endpoints,
                projectName,
                audience,
                aiYaml,
                projectDir
        );

        String fileName = buildFileName(projectName, audience, ".yaml");
        return asAttachment(aiYaml, fileName, "text/yaml");
    }

    // =========================================================
    //  PDF (download – z kodu)
    // =========================================================

    @PostMapping(value = "/{id}/docs/pdf")
    public ResponseEntity<byte[]> pdfFrom(
            @PathVariable String id,
            @RequestParam(defaultValue = "advanced") String level
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

        String audience = level;
        String projectName = resolveProjectName(id);
        Path aiYaml = projectDir.resolve("openapi_" + audience + ".yaml");
        Path aiPdf  = projectDir.resolve("openapi_" + audience + ".pdf");

        code2docs.generateYamlFromCode(
                endpoints,
                projectName,
                audience,
                aiYaml,
                projectDir
        );

        pdfDocService.renderPdfFromYaml(aiYaml, aiPdf);

        String fileName = buildFileName(projectName, audience, ".pdf");
        return asAttachment(aiPdf, fileName, MediaType.APPLICATION_PDF_VALUE);
    }

    // =========================================================
    //  PDF (inline preview – z kodu)
    // =========================================================

    @GetMapping(value = "/{id}/docs/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> viewPdfInline(
            @PathVariable String id,
            @RequestParam(defaultValue = "advanced") String level
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

        String audience = level;
        String projectName = resolveProjectName(id);
        Path aiYaml = projectDir.resolve("openapi_" + audience + ".yaml");
        Path aiPdf  = projectDir.resolve("openapi_" + audience + ".pdf");

        code2docs.generateYamlFromCode(
                endpoints,
                projectName,
                audience,
                aiYaml,
                projectDir
        );

        pdfDocService.renderPdfFromYaml(aiYaml, aiPdf);

        String fileName = buildFileName(projectName, audience, ".pdf");
        return asInline(aiPdf, fileName, MediaType.APPLICATION_PDF_VALUE);
    }

    // =========================================================
    //  YAML (inline + download)
    // =========================================================

    @GetMapping(value = "/{id}/docs/yaml", produces = "text/yaml")
    public ResponseEntity<byte[]> viewYamlInline(
            @PathVariable String id,
            @RequestParam(defaultValue = "advanced") String level
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

        String audience = level;
        String projectName = resolveProjectName(id);
        Path aiYaml = projectDir.resolve("openapi_" + audience + ".yaml");

        code2docs.generateYamlFromCode(
                endpoints,
                projectName,
                audience,
                aiYaml,
                projectDir
        );

        String fileName = buildFileName(projectName, audience, ".yaml");
        return asInline(aiYaml, fileName, "text/yaml");
    }

    @GetMapping(value = "/{id}/docs/yaml/download")
    public ResponseEntity<byte[]> downloadYaml(
            @PathVariable String id,
            @RequestParam(defaultValue = "advanced") String level
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

        String audience = level;
        String projectName = resolveProjectName(id);
        Path aiYaml = projectDir.resolve("openapi_" + audience + ".yaml");

        code2docs.generateYamlFromCode(
                endpoints,
                projectName,
                audience,
                aiYaml,
                projectDir
        );

        String fileName = buildFileName(projectName, audience, ".yaml");
        return asAttachment(aiYaml, fileName, "text/yaml");
    }

    // =========================================================
    //  Wersja do edycji (tekst YAML)
    // =========================================================

    @GetMapping(
            value = "/{id}/docs/editable",
            produces = MediaType.TEXT_PLAIN_VALUE
    )
    public ResponseEntity<String> generateEditableHtml(
            @PathVariable String id,
            @RequestParam(defaultValue = "advanced") String level
    ) throws Exception {

        Path projectDir = storage.resolveProjectDir(id);
        if (!Files.exists(projectDir)) {
            // tutaj zwracamy ResponseEntity<String>, NIE używamy helperów byte[]
            return ResponseEntity.status(404)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("Project not found: " + id);
        }

        List<EndpointIR> endpoints = parser.parseProject(projectDir);
        if (endpoints.isEmpty()) {
            return ResponseEntity.badRequest()
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("No endpoints found in source code.");
        }

        Files.createDirectories(projectDir);

        String audience = level;
        String projectName = resolveProjectName(id);
        Path aiYaml = projectDir.resolve("openapi_" + audience + ".yaml");

        // jeśli jeszcze nie ma pliku – wygeneruj z kodu
        if (!Files.exists(aiYaml)) {
            code2docs.generateYamlFromCode(
                    endpoints,
                    projectName,
                    audience,
                    aiYaml,
                    projectDir
            );
        }

        // a jeśli jest, po prostu go czytamy
        String yaml = Files.readString(aiYaml);

        return ResponseEntity
                .ok()
                .contentType(MediaType.TEXT_PLAIN)
                .body(yaml);
    }

    // =========================================================
    //  PDF z edytowanego YAML
    // =========================================================

    @PostMapping(
            value = "/{id}/docs/edited/pdf",
            consumes = MediaType.TEXT_PLAIN_VALUE
    )
    public ResponseEntity<byte[]> generatePdfFromEditedHtml(
            @PathVariable String id,
            @RequestParam(defaultValue = "advanced") String level,
            @RequestBody String editedYaml
    ) throws Exception {

        Path projectDir = storage.resolveProjectDir(id);
        if (!Files.exists(projectDir)) {
            return notFound("Project not found: " + id);
        }

        Files.createDirectories(projectDir);

        String audience = level;
        String projectName = resolveProjectName(id);

        // zapisujemy edytowany YAML
        Path aiYamlEdited = projectDir.resolve("openapi_" + audience + "_edited.yaml");
        Files.writeString(aiYamlEdited, editedYaml);

        // generujemy PDF z tego YAML
        Path aiPdfEdited = projectDir.resolve("openapi_" + audience + "_edited.pdf");
        pdfDocService.renderPdfFromYaml(aiYamlEdited, aiPdfEdited);

        String fileName = buildFileName(projectName, audience, "_edited.pdf");
        return asAttachment(aiPdfEdited, fileName, MediaType.APPLICATION_PDF_VALUE);
    }

    // =========================================================
    //  NLP INPUT PREVIEW (AI / ollama)
    // =========================================================

    @GetMapping(
            value = "/{id}/docs/nlp-input",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<?> getNlpInputs(
            @PathVariable String id,
            @RequestParam(defaultValue = "advanced") String level,
            @RequestParam(required = false) String mode
    ) throws Exception {

        Path projectDir = storage.resolveProjectDir(id);
        if (!Files.exists(projectDir)) {
            return notFound("Project not found: " + id);
        }

        List<EndpointIR> endpoints = parser.parseProject(projectDir);
        if (endpoints.isEmpty()) {
            return badRequest("No endpoints found in source code.");
        }

        var inputs = code2docs.buildNlpInputs(endpoints, level);
        return ResponseEntity.ok(inputs);
    }

    // =========================================================
    //  Helpers
    // =========================================================

    private String resolveProjectName(String id) {
        String fromStorage = storage.getProjectName(id);
        if (fromStorage != null && !fromStorage.isBlank()) {
            return fromStorage;
        }
        return id;
    }

    /**
     * Buduje bezpieczną nazwę pliku:
     * <nazwa_projektu>_<poziom><ext>
     */
    private static String buildFileName(String projectName, String audience, String ext) {
        String base = (projectName == null || projectName.isBlank())
                ? "openapi"
                : projectName.trim();
        String safeBase = base.replaceAll("[^a-zA-Z0-9._-]+", "_");
        String suffix = (audience == null || audience.isBlank())
                ? ""
                : "_" + audience;
        String extension = (ext == null || ext.isBlank())
                ? ""
                : (ext.startsWith(".") ? ext : "." + ext);
        return safeBase + suffix + extension;
    }

    private static ResponseEntity<byte[]> notFound(String msg) {
        return ResponseEntity.status(404)
                .contentType(MediaType.TEXT_PLAIN)
                .body(msg.getBytes(StandardCharsets.UTF_8));
    }

    private static ResponseEntity<byte[]> badRequest(String msg) {
        return ResponseEntity.badRequest()
                .contentType(MediaType.TEXT_PLAIN)
                .body(msg.getBytes(StandardCharsets.UTF_8));
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
