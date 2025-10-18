// java-api/src/main/java/com/mariia/javaapi/controller/ProjectSpecController.java
package com.mariia.javaapi.controller;

import com.mariia.javaapi.docs.EnrichmentService;
import com.mariia.javaapi.uploads.UploadStorage;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;

@RestController
@RequestMapping("/api/projects")
public class ProjectSpecController {

    private final UploadStorage storage;
    private final EnrichmentService enrichment;

    public ProjectSpecController(UploadStorage storage, EnrichmentService enrichment) {
        this.storage = storage;
        this.enrichment = enrichment;
    }

    /** Oryginalny plik specyfikacji (openapi.yaml/yml) */
    @GetMapping(value = "/{id}/spec", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<?> getOriginalSpec(@PathVariable String id,
                                             @RequestParam(defaultValue = "false") boolean download) {
        try {
            Path spec = storage.resolveOpenApiYamlPath(id);
            FileSystemResource res = new FileSystemResource(spec);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.TEXT_PLAIN);
            if (download) {
                headers.setContentDisposition(ContentDisposition.attachment()
                        .filename(spec.getFileName().toString())
                        .build());
            }
            return new ResponseEntity<>(res, headers, HttpStatus.OK);
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(ex.getMessage());
        }
    }

    /** Wersja wzbogacona NLP – zapisujemy obok oryginału i odsyłamy jako tekst YAML */
    @GetMapping(value = "/{id}/spec/enriched", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<?> getEnriched(@PathVariable String id,
                                         @RequestParam(defaultValue = "medium") String level) {
        try {
            Path in  = storage.resolveOpenApiYamlPath(id);
            Path out = storage.resolveProjectDir(id).resolve("openapi.enriched.yaml");
            Path saved = enrichment.enrich(in, level, out);
            String yaml = Files.readString(saved);
            return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(yaml);
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(ex.getMessage());
        }
    }
}
