package org.project.piireportproject.controller;

import org.project.piireportproject.model.PiiMatch;
import org.project.piireportproject.service.PiiDetector;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/detect")
public class DetectController {

    private final PiiDetector detector;

    public DetectController(PiiDetector detector) {
        this.detector = detector;
    }


    @PostMapping
    public List<PiiMatch> detect(@RequestBody Map<String, String> body) {
        return detector.detect(body.getOrDefault("text", ""));
    }

}
