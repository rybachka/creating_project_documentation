// ProjectDocsController.java
package com.mariia.javaapi.docs;

import com.mariia.javaapi.uploads.UploadStorage;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.Map;

@RestController
@RequestMapping("/api/projects")
public class ProjectDocsController {

    private final EnrichmentService enrichment;
    private final UploadStorage storage;

    public ProjectDocsController(EnrichmentService enrichment, UploadStorage storage) {
        this.enrichment = enrichment;
        this.storage = storage;
    }

    @GetMapping(value = "/{id}/docs", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String,Object>> docs(
            @PathVariable String id,
            @RequestParam(defaultValue = "medium") String level) throws Exception {
        Path specYaml = storage.resolveOpenApiYamlPath(id);
        Map<String,Object> enriched = enrichment.enrichToMap(specYaml, level);
        return ResponseEntity.ok(enriched);
    }
}
