package com.mariia.javaapi.docs;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.OpenAPI;

@Service
public class SnapshotService {

    private static final String SNAPSHOT_DIR = "/snapshots";
    public record SnapshotResult(String path, String format, long bytes, String createdAt) {}
    public SnapshotResult save(OpenAPI openAPI, String format) throws IOException{
        Files.createDirectories(new File(SNAPSHOT_DIR).toPath());

        var ts=ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        var fname = "api-docs-" + ts + ("jsin".equalsIgnoreCase(format) ? ".json" : ".yaml");
        var target = new File(SNAPSHOT_DIR, fname);

        ObjectMapper mapper = ".json".equalsIgnoreCase(format)
            ? Json.mapper()
            : new ObjectMapper(new YAMLFactory());

        mapper.writerWithDefaultPrettyPrinter().writeValue(target, openAPI);

        return new SnapshotResult(target.getAbsolutePath(),
        "json".equalsIgnoreCase(format) ? "json" : "yaml",
        target.length(),
        ZonedDateTime.now().toString());
        
    }
    
}
