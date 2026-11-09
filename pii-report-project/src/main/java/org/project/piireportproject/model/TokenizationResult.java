package org.project.piireportproject.model;

import java.util.Map;

public record TokenizationResult(byte[] pdf, int pages, int totalReplaced, int uniqueValues,
                                 Map<String, Integer> countsByType) {
}
