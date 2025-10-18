// java-api/src/main/java/com/mariia/javaapi/uploads/UploadStorage.java
package com.mariia.javaapi.uploads;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class UploadStorage {

    private final Path base;

    public UploadStorage(@Value("${file.upload.base:/uploads}") String baseDir) {
        this.base = Path.of(baseDir);
    }

    public Path resolveProjectDir(String id) {
        return base.resolve(id);
    }

    public Path resolveZipPath(String id) {
        return base.resolve(id + ".zip");
    }

    /** Zwraca pełną ścieżkę do openapi.{yaml|yml} w projekcie, albo rzuca IllegalStateException z czytelnym komunikatem. */
// java-api/src/main/java/com/mariia/javaapi/uploads/UploadStorage.java

/** Zwraca pełną ścieżkę do openapi.{yaml|yml} w projekcie, albo rzuca IllegalStateException z czytelnym komunikatem. */
public Path resolveOpenApiYamlPath(String id) {
    Path dir = resolveProjectDir(id);
    if (!Files.exists(dir)) {
        throw new IllegalStateException("Project not found: " + id);
    }

    // 1) spróbuj użyć wykrytej ścieżki (SpecDetector)
    try {
        String rel = SpecDetector.findOpenApiRelative(dir); // <- rzuca IOException
        if (rel != null && !rel.isBlank()) {
            Path candidate = dir.resolve(rel).normalize();
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
    } catch (IOException io) {
        throw new IllegalStateException("Error scanning project " + id + " for OpenAPI file", io);
    }

    // 2) fallback – standardowe nazwy w katalogu głównym
    Path yml  = dir.resolve("openapi.yml");
    Path yaml = dir.resolve("openapi.yaml");
    if (Files.exists(yml))  return yml;
    if (Files.exists(yaml)) return yaml;

    throw new IllegalStateException(
        "Spec file not found in project " + id + " (expected openapi.yml/.yaml or a detected spec)"
    );
}

}
