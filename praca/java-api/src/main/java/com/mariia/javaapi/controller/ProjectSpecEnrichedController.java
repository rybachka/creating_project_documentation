/**package com.mariia.javaapi.controller;

import com.mariia.javaapi.docs.EnrichmentService;
import com.mariia.javaapi.uploads.SpecDetector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;

/
@RestController
@RequestMapping("/api/projects/{id}/spec")
public class ProjectSpecEnrichedController {

    private final EnrichmentService enrichmentService;

    @Value("${file.upload.base:/uploads}")
    private String baseDir;

    public ProjectSpecEnrichedController(EnrichmentService enrichmentService) {
        this.enrichmentService = enrichmentService;
    }

  
    @GetMapping
    public ResponseEntity<Resource> getOriginal(@PathVariable String id) throws Exception {
        Path projectDir = Path.of(baseDir).resolve(id);
        if (!Files.exists(projectDir)) {
            return ResponseEntity.status(404).body(null);
        }
        String rel = SpecDetector.findOpenApiRelative(projectDir);
        if (rel == null) {
            return ResponseEntity.status(404)
                    .body(null);
        }
        Path spec = projectDir.resolve(rel).normalize();
        if (!Files.exists(spec)) {
            return ResponseEntity.status(404).body(null);
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/yaml"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + spec.getFileName() + "\"")
                .body(new FileSystemResource(spec));
    }

 
    @GetMapping("/enriched")
    public ResponseEntity<Resource> getEnriched(
            @PathVariable String id,
            @RequestParam(defaultValue = "medium") String level) throws Exception {

        Path projectDir = Path.of(baseDir).resolve(id);
        if (!Files.exists(projectDir)) {
            return ResponseEntity.status(404).body(null);
        }
        String rel = SpecDetector.findOpenApiRelative(projectDir);
        if (rel == null) {
            return ResponseEntity.status(404).body(null);
        }
        Path input = projectDir.resolve(rel).normalize();
        if (!Files.exists(input)) {
            return ResponseEntity.status(404).body(null);
        }

        Path out = projectDir.resolve("openapi.enriched.yaml");
        enrichmentService.enrich(input, level, out);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/yaml"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"openapi.enriched.yaml\"")
                .body(new FileSystemResource(out));
    }
}
**/