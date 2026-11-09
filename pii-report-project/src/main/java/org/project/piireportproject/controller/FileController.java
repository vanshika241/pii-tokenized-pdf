package org.project.piireportproject.controller;

import org.project.piireportproject.service.FileStorageService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/files")
public class FileController {

    private final FileStorageService fileStorageService;

    public FileController(FileStorageService fileStorageService){
        this.fileStorageService = fileStorageService;
    }

    @PostMapping("/upload")
    public ResponseEntity<?> upload(@RequestParam("file")MultipartFile file) throws IOException{
        if (file.isEmpty()){
            return ResponseEntity.badRequest().body(Map.of("error" , "File is empty"));
        }
        String fileid = fileStorageService.save(file);
        return ResponseEntity.ok(Map.of("fileId" , fileid , "originalName" , String.valueOf(file.getOriginalFilename()) , "size" , file.getSize()
                ));
    }
}
