package com.mariia.javaapi.controller;

import com.mariia.javaapi.uploads.UploadResult;
import com.mariia.javaapi.uploads.UploadStorage;
import com.mariia.javaapi.uploads.ZipUtils;
import com.mariia.javaapi.uploads.SpecDetector;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

@RestController
@RequestMapping("/api/projects")
public class ProjectsController {

    private final UploadStorage storage;

    public ProjectsController(UploadStorage storage) {
        this.storage = storage;
    }

    @PostMapping("/upload")
    public ResponseEntity<UploadResult> upload(@RequestPart("file") MultipartFile file) {
        UploadResult res = new UploadResult();
        String id = UUID.randomUUID().toString().replace("-", "");
        res.id = id;

        try {
            if (file.isEmpty()) {
                res.status = "ERROR";
                res.message = "Plik jest pusty.";
                return ResponseEntity.badRequest().body(res);
            }

            String original = StringUtils.hasText(file.getOriginalFilename())
                    ? file.getOriginalFilename() : "project.zip";
            if (!original.toLowerCase().endsWith(".zip")) {
                res.status = "ERROR";
                res.message = "Oczekiwany plik .zip.";
                return ResponseEntity.badRequest().body(res);
            }

            // Utwórz katalog bazowy i zapisz ZIP
            Path zipPath = storage.resolveZipPath(id);
            Files.createDirectories(zipPath.getParent());
            Files.copy(file.getInputStream(), zipPath, StandardCopyOption.REPLACE_EXISTING);

            // Rozpakuj do katalogu projektu
            Path projectDir = storage.resolveProjectDir(id);
            Files.createDirectories(projectDir);
            ZipUtils.unzip(zipPath, projectDir);

            // Spróbuj wykryć plik OpenAPI w projekcie
            String specRel = null;
            try {
                specRel = SpecDetector.findOpenApiRelative(projectDir);
            } catch (Exception scanErr) {
                // nie blokujemy uploadu – tylko komunikat
                specRel = null;
            }

            res.zipPath = zipPath.toString();
            res.projectDir = projectDir.toString();
            res.detectedSpec = specRel;
            res.status = (specRel != null) ? "READY" : "PENDING";
            res.message = (specRel != null)
                    ? "Znaleziono specyfikację: " + specRel
                    : "Nie wykryto pliku OpenAPI – można wygenerować dokumentację z kodu.";

            return ResponseEntity.ok(res);

        } catch (Exception ex) {
            res.status = "ERROR";
            res.message = ex.getClass().getSimpleName() + ": " + ex.getMessage();
            return ResponseEntity.internalServerError().body(res);
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<UploadResult> get(@PathVariable String id) {
        try {
            Path zip = storage.resolveZipPath(id);
            Path dir = storage.resolveProjectDir(id);

            UploadResult res = new UploadResult();
            res.id = id;
            res.zipPath = Files.exists(zip) ? zip.toString() : null;
            res.projectDir = Files.exists(dir) ? dir.toString() : null;

            if (!Files.exists(dir)) {
                res.status = "NOT_FOUND";
                res.message = "Brak projektu o podanym ID.";
                return ResponseEntity.status(404).body(res);
            }

            String specRel = null;
            try {
                specRel = SpecDetector.findOpenApiRelative(dir);
            } catch (Exception ignored) {
                specRel = null;
            }

            res.detectedSpec = specRel;
            res.status = (specRel != null) ? "READY" : "PENDING";
            res.message = (specRel != null) ? "Specyfikacja dostępna." : "Brak specyfikacji (można wygenerować z kodu).";
            return ResponseEntity.ok(res);

        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }
}
