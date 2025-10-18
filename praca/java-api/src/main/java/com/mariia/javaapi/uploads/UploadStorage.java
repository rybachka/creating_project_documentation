// java-api/src/main/java/com/mariia/javaapi/uploads/UploadStorage.java
package com.mariia.javaapi.uploads;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Trzyma i rozwiązuje ścieżki do plików przesłanych projektów:
 *  - katalog projektu: /uploads/{id}
 *  - ZIP projektu:     /uploads/{id}.zip
 *  - oryginalna spec:  openapi.yaml / openapi.yml (jeśli istnieje)
 *  - wygenerowana spec: openapi.generated.yaml (gdy tworzymy ją z kodu)
 */
@Component
public class UploadStorage {

    private final Path base;

    public UploadStorage(@Value("${file.upload.base:/uploads}") String baseDir) {
        this.base = Path.of(baseDir);
    }

    /** Katalog projektu: /uploads/{id} */
    public Path resolveProjectDir(String id) {
        return base.resolve(id);
    }

    /** Ścieżka do ZIP-a: /uploads/{id}.zip */
    public Path resolveZipPath(String id) {
        return base.resolve(id + ".zip");
    }

    /** Ścieżka do wygenerowanej specyfikacji: /uploads/{id}/openapi.generated.yaml */
    public Path resolveGeneratedSpecPath(String id) {
        return resolveProjectDir(id).resolve("openapi.generated.yaml");
    }

    /**
     * Zwraca pełną ścieżkę do openapi.{yaml|yml} w projekcie.
     * Jeśli nie znajdzie pliku – rzuca IllegalStateException z czytelnym komunikatem.
     */
    public Path resolveOpenApiYamlPath(String id) {
        Path dir = resolveProjectDir(id);
        if (!Files.exists(dir)) {
            throw new IllegalStateException("Project not found: " + id);
        }

        // 1) spróbuj wykryć względną ścieżkę (np. sample-project/openapi.yaml)
        try {
            String rel = SpecDetector.findOpenApiRelative(dir); // może rzucić IOException
            if (rel != null && !rel.isBlank()) {
                Path candidate = dir.resolve(rel).normalize();
                if (Files.exists(candidate)) {
                    return candidate;
                }
            }
        } catch (IOException io) {
            throw new IllegalStateException("Error scanning project " + id + " for OpenAPI file", io);
        }

        // 2) fallback – standardowe nazwy w katalogu głównym projektu
        Path yml  = dir.resolve("openapi.yml");
        Path yaml = dir.resolve("openapi.yaml");
        if (Files.exists(yml))  return yml;
        if (Files.exists(yaml)) return yaml;

        throw new IllegalStateException(
            "Spec file not found in project " + id + " (expected openapi.yml/.yaml or a detected spec)"
        );
    }

    // --- pomocnicze, opcjonalne ---

    /** Upewnia się, że katalog projektu istnieje. */
    public void ensureProjectDir(String id) throws IOException {
        Files.createDirectories(resolveProjectDir(id));
    }

    /** Zapisuje podaną treść do pliku (tworzy katalogi, jeśli trzeba). */
    public void writeString(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
