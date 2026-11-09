package org.project.piireportproject.controller;

import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.project.piireportproject.model.TokenizationResult;
import org.project.piireportproject.service.FileStorageService;
import org.project.piireportproject.service.PdfTokenizationService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/tokenize")
public class TokenizeController {

    private final PdfTokenizationService tokenizationService;
    private final FileStorageService storage;

    public TokenizeController(PdfTokenizationService tokenizationService, FileStorageService storage) {
        this.tokenizationService = tokenizationService;
        this.storage = storage;
    }

    @PostMapping
    public ResponseEntity<?> tokenize(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "File is empty"));
        }
        byte[] bytes = file.getBytes();
        if (!isPdf(bytes)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Please upload a PDF file"));
        }

        TokenizationResult result;
        try {
            result = tokenizationService.tokenize(bytes);
        } catch (InvalidPasswordException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "This PDF is password protected"));
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Could not read this PDF"));
        }

        String originalName = Optional.ofNullable(file.getOriginalFilename()).orElse("document.pdf");
        String downloadName = originalName.replaceAll("(?i)\\.pdf$", "") + "_tokenized.pdf";
        String fileId = storage.saveTokenized(result.pdf(), downloadName);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fileId", fileId);
        body.put("originalName", originalName);
        body.put("downloadName", downloadName);
        body.put("pages", result.pages());
        body.put("totalReplaced", result.totalReplaced());
        body.put("uniqueValues", result.uniqueValues());
        body.put("countsByType", result.countsByType());
        body.put("downloadUrl", "/api/tokenize/" + fileId + "/download");
        return ResponseEntity.ok(body);
    }

    @GetMapping("/{fileId}/download")
    public ResponseEntity<byte[]> download(@PathVariable String fileId) throws IOException {
        Optional<FileStorageService.StoredFile> stored = storage.loadTokenized(fileId);
        if (stored.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        ContentDisposition cd = ContentDisposition.attachment()
                .filename(stored.get().name(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .body(stored.get().bytes());
    }

    private static boolean isPdf(byte[] b) {
        return b.length > 4 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F';
    }
}
