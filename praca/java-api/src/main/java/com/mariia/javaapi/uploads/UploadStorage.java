package com.mariia.javaapi.uploads;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Trzyma i rozwiązuje ścieżki do plików przesłanych projektów:
 *  - katalog projektu:   /uploads/{id}
 *  - ZIP projektu:       /uploads/{id}.zip
 *  - metadata nazwy:     /uploads/{id}.name  (oryginalna nazwa zipa, bez ścieżki)
 *  - wygenerowana spec:  /uploads/{id}/openapi.generated.yaml (gdy tworzymy ją z kodu)
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
     * Zwraca nazwę projektu do użycia w dokumentacji.
     *
     * Priorytet:
     *  1) plik /uploads/{id}.name (pierwotna nazwa zipa zapisana przy uploadzie),
     *  2) nazwa pliku ZIP (bez .zip), jeśli istnieje,
     *  3) samo id (fallback awaryjny).
     */
    public String getProjectName(String id) {
        // 1) metadata z uploadu
        Path nameMeta = base.resolve(id + ".name");
        if (Files.exists(nameMeta)) {
            try {
                String raw = Files.readString(nameMeta, StandardCharsets.UTF_8).trim();
                if (!raw.isBlank()) {
                    // jeśli ktoś zapisał pełną nazwę z .zip, utnij rozszerzenie
                    if (raw.toLowerCase().endsWith(".zip") && raw.length() > 4) {
                        raw = raw.substring(0, raw.length() - 4);
                    }
                    return raw;
                }
            } catch (IOException ignored) {
                // w razie problemu lecimy dalej do fallbacków
            }
        }

        // 2) z nazwy pliku ZIP (jeśli istnieje)
        Path zip = resolveZipPath(id);
        if (Files.exists(zip)) {
            String fileName = zip.getFileName().toString();
            if (fileName.toLowerCase().endsWith(".zip") && fileName.length() > 4) {
                return fileName.substring(0, fileName.length() - 4);
            }
        }

        // 3) fallback
        return id;
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

    /**
     * Zapisuje oryginalną nazwę projektu (na podstawie nazwy przesłanego ZIP-a),
     * żeby później użyć jej np. w nazwach wygenerowanych plików.
     */
    public void saveOriginalProjectName(String id, String originalFilename) throws IOException {
        if (originalFilename == null || originalFilename.isBlank()) {
            return;
        }

        // tylko nazwa pliku (bez ścieżek typu C:\...)
        String baseName = Path.of(originalFilename).getFileName().toString();

        // utnij .zip, jeśli jest
        String lower = baseName.toLowerCase();
        if (lower.endsWith(".zip") && baseName.length() > 4) {
            baseName = baseName.substring(0, baseName.length() - 4);
        }

        if (baseName.isBlank()) {
            return;
        }

        Path nameMeta = base.resolve(id + ".name");
        Files.createDirectories(nameMeta.getParent());
        Files.writeString(nameMeta, baseName, StandardCharsets.UTF_8);
    }
}
