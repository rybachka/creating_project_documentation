package com.mariia.javaapi.uploads;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
public class SpecDetector {
    private static final List<String> CANDIDATES = List.of(
        "openapi.yaml", "openapi.yml", "openapi.json",
        "swagger.yaml", "swagger.yml", "swagger.json"
    );

    private SpecDetector(){}
     public static String findOpenApiRelative(Path root) throws IOException {
        try (var stream = Files.walk(root)) {
            return stream
                .filter(Files::isRegularFile)
                .filter(p -> {
                    String name = p.getFileName().toString().toLowerCase();
                    return CANDIDATES.contains(name);
                })
                .findFirst()
                .map(p -> root.relativize(p).toString().replace('\\','/'))
                .orElse(null);
        }
    }
}
