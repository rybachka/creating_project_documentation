package com.mariia.javaapi.controller;

import com.mariia.javaapi.uploads.UploadResult;
import com.mariia.javaapi.uploads.ZipUtils;
import com.mariia.javaapi.uploads.SpecDetector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/projects")
public class ProjectsController {
    @Value("${file.upload.base:/uploads}")
    private String baseDir;

    @PostMapping("/upload")
    public ResponseEntity<UploadResult> upload(@RequestPart("file") MultipartFile file){
        UploadResult res = new UploadResult();
        String id = UUID.randomUUID().toString().replace("-", "");
        res.id = id;

        try {
            if (file.isEmpty()) {
                res.status = "ERROR";
                res.message = "Plik jest pusty";
                return ResponseEntity.badRequest().body(res);
            }
            String original = StringUtils.hasText(file.getOriginalFilename())
                    ? file.getOriginalFilename() : "project.zip";
            if (!original.toLowerCase().endsWith(".zip")) {
                res.status = "ERROR";
                res.message = "Oczekiwany .zip";
                return ResponseEntity.badRequest().body(res);
            }
            Path base = Path.of(baseDir);
            Files.createDirectories(base);

            Path zipPath = base.resolve(id + ".zip");
            Files.copy(file.getInputStream(), zipPath, StandardCopyOption.REPLACE_EXISTING);

            Path projectDir = base.resolve(id);
            ZipUtils.unzip(zipPath, projectDir);

            String specRel = SpecDetector.findOpenApiRelative(projectDir);

            res.zipPath = zipPath.toString();
            res.projectDir = projectDir.toString();
            res.detectedSpec = specRel;
            res.status = (specRel != null) ? "READY" : "PENDING";
            res.message = (specRel != null)
                    ? "Znaleziono specyfikację: " + specRel
                    : "Nie wykryto pliku OpenAPI – możliwa analiza kodu w kolejnym kroku.";
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
            Path base = Path.of(baseDir);
            Path zip = base.resolve(id + ".zip");
            Path dir = base.resolve(id);
            UploadResult res = new UploadResult();
            res.id = id;
            res.zipPath = Files.exists(zip) ? zip.toString() : null;
            res.projectDir = Files.exists(dir) ? dir.toString() : null;
            res.detectedSpec = (Files.exists(dir) ? SpecDetector.findOpenApiRelative(dir) : null);
            if (res.projectDir == null) {
                res.status = "NOT_FOUND";
                res.message = "Brak projektu o podanym ID";
                return ResponseEntity.status(404).body(res);
            }
            res.status = (res.detectedSpec != null) ? "READY" : "PENDING";
            res.message = (res.detectedSpec != null) ? "Specyfikacja dostępna." : "Brak specyfikacji.";
            return ResponseEntity.ok(res);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }
    
}
