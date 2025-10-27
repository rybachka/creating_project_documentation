package com.mariia.javaapi.controller;

import com.mariia.javaapi.code.CodeToDocsService;
import com.mariia.javaapi.code.JavaSpringParser;
import com.mariia.javaapi.code.ir.EndpointIR;
import com.mariia.javaapi.uploads.UploadStorage;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.mariia.javaapi.docs.PdfDocService;


import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RestController
@RequestMapping("/api/projects")
public class ProjectDocsFromCodeController {


    private final UploadStorage storage;
    private final CodeToDocsService code2docs;
    private final JavaSpringParser parser = new JavaSpringParser();
    private final PdfDocService pdfDocService;



    public ProjectDocsFromCodeController(UploadStorage storage, CodeToDocsService code2docs, PdfDocService pdfDocService) {
        this.storage = storage;
        this.code2docs = code2docs;
        this.pdfDocService = pdfDocService;
    }

    /**
     * Generuje OpenAPI z kodu i zwraca:
     *  - mode=plain|rules|ai  -> pojedynczy YAML (attachment)
     *  - mode=all             -> ZIP z trzema plikami YAML (attachment)
     *
     * level: short|medium|long (dla opisów)
     */
    @PostMapping(value = "/{id}/docs/from-code")
    public ResponseEntity<byte[]> fromCode(
            @PathVariable String id,
            @RequestParam(defaultValue = "all") String mode,
            @RequestParam(defaultValue = "medium") String level
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
        Path plainPath = projectDir.resolve("openapi.plain.yaml");
        Path rulesPath = projectDir.resolve("openapi.rules.yaml");
        Path aiPath    = projectDir.resolve("openapi.ai.yaml");
        Path zipPath   = projectDir.resolve("openapi.all.zip");

        String m = (mode == null) ? "all" : mode.trim().toLowerCase(Locale.ROOT);

        switch (m) {
            case "plain": {
                code2docs.generateYamlFromCode(
                        endpoints, "Project " + id, "none",
                        plainPath, projectDir,
                        CodeToDocsService.DescribeMode.PLAIN
                );
                return asAttachment(plainPath, "openapi.plain.yaml", "text/yaml");
            }
            case "rules": {
                code2docs.generateYamlFromCode(
                        endpoints, "Project " + id, level,
                        rulesPath, projectDir,
                        CodeToDocsService.DescribeMode.RULES
                );
                return asAttachment(rulesPath, "openapi.rules.yaml", "text/yaml");
            }
            case "ai": {
                code2docs.generateYamlFromCode(
                        endpoints, "Project " + id, level,
                        aiPath, projectDir,
                        CodeToDocsService.DescribeMode.AI
                );
                return asAttachment(aiPath, "openapi.ai.yaml", "text/yaml");
            }
            case "all":
            default: {
                code2docs.generateYamlFromCode(
                        endpoints, "Project " + id, "none",
                        plainPath, projectDir,
                        CodeToDocsService.DescribeMode.PLAIN
                );
                code2docs.generateYamlFromCode(
                        endpoints, "Project " + id, level,
                        rulesPath, projectDir,
                        CodeToDocsService.DescribeMode.RULES
                );
                code2docs.generateYamlFromCode(
                        endpoints, "Project " + id, level,
                        aiPath, projectDir,
                        CodeToDocsService.DescribeMode.AI
                );

                zipFiles(zipPath,
                        new Path[]{plainPath, rulesPath, aiPath},
                        new String[]{"openapi.plain.yaml", "openapi.rules.yaml", "openapi.ai.yaml"});

                return asAttachment(zipPath, "openapi.all.zip", "application/zip");
            }
        }
    }

    // —— helpers ——
    private static ResponseEntity<byte[]> asAttachment(Path path, String filename, String contentType) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.parseMediaType(contentType))
                .body(bytes);
    }

    private static void zipFiles(Path targetZip, Path[] sources, String[] namesInZip) throws IOException {
        if (Files.exists(targetZip)) Files.delete(targetZip);
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(targetZip, StandardOpenOption.CREATE_NEW))) {
            for (int i = 0; i < sources.length; i++) {
                Path src = sources[i];
                if (!Files.exists(src)) continue;
                String entryName = (namesInZip != null && i < namesInZip.length && namesInZip[i] != null)
                        ? namesInZip[i]
                        : src.getFileName().toString();
                zos.putNextEntry(new ZipEntry(entryName));
                Files.copy(src, zos);
                zos.closeEntry();
            }
        }
    }

    @PostMapping(value = "/{id}/docs/pdf")
    public ResponseEntity<byte[]> pdfFromAi(
            @PathVariable String id,
            @RequestParam(defaultValue = "ai") String mode // na przyszłość
    ) throws Exception {
        Path projectDir = storage.resolveProjectDir(id);
        if (!Files.exists(projectDir)) {
            return ResponseEntity.status(404)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(("Project not found: " + id).getBytes());
        }
        Path aiYaml = projectDir.resolve("openapi.ai.yaml");
        if (!Files.exists(aiYaml)) {
            // Jeżeli nie ma jeszcze AI-YAML, spróbuj wygenerować:
            List<EndpointIR> endpoints = parser.parseProject(projectDir);
            if (endpoints.isEmpty()) {
                return ResponseEntity.badRequest()
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("No endpoints found in source code.".getBytes());
            }
            code2docs.generateYamlFromCode(
                    endpoints, "Project " + id, "medium",
                    aiYaml, projectDir, CodeToDocsService.DescribeMode.AI
            );
        }

        Path outPdf = projectDir.resolve("openapi.ai.pdf");
        pdfDocService.renderPdfFromYaml(aiYaml, outPdf);

        byte[] bytes = Files.readAllBytes(outPdf);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"openapi.ai.pdf\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.APPLICATION_PDF)
                .body(bytes);
    }

}
