package org.project.piireportproject.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.UUID;

@Service
public class FileStorageService {

    @Value("${app.storage.dir}")
    private String directory;

    private Path root;

    @PostConstruct
    public void init() throws IOException {
        root = Paths.get(directory).toAbsolutePath().normalize();
        Files.createDirectories(root);
    }

    public String save(MultipartFile file) throws IOException{
        String fileId = UUID.randomUUID().toString();
        Path target = root.resolve(fileId + ".original");
        Files.copy(file.getInputStream() , target , StandardCopyOption.REPLACE_EXISTING);
        return fileId;
    }

    public record StoredFile(String name, byte[] bytes) {}

    public String saveTokenized(byte[] pdf, String downloadName) throws IOException {
        String fileId = UUID.randomUUID().toString();
        Files.write(root.resolve(fileId + ".tokenized.pdf"), pdf);
        Files.writeString(root.resolve(fileId + ".name"), downloadName);
        return fileId;
    }

    public Optional<StoredFile> loadTokenized(String fileId) throws IOException {
        // only accept real UUIDs so the id can never escape the storage folder
        try {
            UUID.fromString(fileId);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        Path pdf = root.resolve(fileId + ".tokenized.pdf");
        if (!Files.exists(pdf)) {
            return Optional.empty();
        }
        Path namePath = root.resolve(fileId + ".name");
        String name = Files.exists(namePath) ? Files.readString(namePath) : "tokenized.pdf";
        return Optional.of(new StoredFile(name, Files.readAllBytes(pdf)));
    }


}
