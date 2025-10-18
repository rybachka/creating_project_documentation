package com.mariia.javaapi.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mariia.javaapi.docs.SnapshotService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.models.OpenAPI;

@RestController
@RequestMapping("/api/docs")
public class DocsController {

    private final OpenAPI openAPI;
    private final SnapshotService snapshotService;

    public DocsController(OpenAPI openAPI, SnapshotService snapshotService){
        this.openAPI=openAPI;
        this.snapshotService=snapshotService;
    }

    @Operation(summary="Zapisz snapshot specyfikacji OpenAPI na dysk",
    description = "Zwraca sci´zkie pliku w wolume /snapshots(zmapowany na hosta).")
    @PostMapping("/snapshot")
    public ResponseEntity<?> snapshot(@RequestParam(defaultValue = "yaml") String format){
        try{
            var res = snapshotService.save(openAPI, format);
            return ResponseEntity.ok(res);

        }
        catch (Exception e){
            return ResponseEntity.internalServerError()
                .body(java.util.Map.of("error", e.getMessage()));
        }
    }

    
}
