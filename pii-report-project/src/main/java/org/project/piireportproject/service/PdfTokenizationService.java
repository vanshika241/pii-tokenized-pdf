package org.project.piireportproject.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.project.piireportproject.model.PiiMatch;
import org.project.piireportproject.model.TokenizationResult;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a PDF, replaces every detected PII value with a token such as [EMAIL_1],
 * and writes a brand-new PDF that contains only the tokenized text.
 * The same value always gets the same token within one document.
 */
@Service
public class PdfTokenizationService {

    private static final float FONT_SIZE = 10.5f;
    private static final float LEADING = 14f;
    private static final float MARGIN = 50f;

    private final PiiDetector detector;

    public PdfTokenizationService(PiiDetector detector) {
        this.detector = detector;
    }

    public TokenizationResult tokenize(byte[] pdfBytes) throws IOException {
        List<String> pageTexts = extractPages(pdfBytes);

        Map<String, String> tokenByValue = new HashMap<>();
        Map<String, Integer> nextIndexByType = new HashMap<>();
        Map<String, Integer> countsByType = new LinkedHashMap<>();
        int total = 0;

        List<String> tokenizedPages = new ArrayList<>();
        for (String text : pageTexts) {
            List<PiiMatch> matches = detector.detect(text);
            StringBuilder out = new StringBuilder();
            int cursor = 0;
            for (PiiMatch m : matches) {
                out.append(text, cursor, m.start());
                String key = m.type() + "|" + normalize(m.value());
                String token = tokenByValue.computeIfAbsent(key, k -> {
                    int idx = nextIndexByType.merge(m.type(), 1, Integer::sum);
                    return "[" + m.type() + "_" + idx + "]";
                });
                out.append(token);
                cursor = m.end();
                countsByType.merge(m.type(), 1, Integer::sum);
                total++;
            }
            out.append(text.substring(cursor));
            tokenizedPages.add(out.toString());
        }

        byte[] outPdf = writePdf(tokenizedPages);
        return new TokenizationResult(outPdf, pageTexts.size(), total, tokenByValue.size(), countsByType);
    }

    private static String normalize(String value) {
        return value.replaceAll("[\\s-]", "").toLowerCase();
    }

    private List<String> extractPages(byte[] pdfBytes) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            if (doc.isEncrypted()) {
                doc.setAllSecurityToBeRemoved(true);
            }
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            List<String> pages = new ArrayList<>();
            for (int i = 1; i <= doc.getNumberOfPages(); i++) {
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                pages.add(cleanInvisible(stripper.getText(doc)));
            }
            return pages;
        }
    }

    private byte[] writePdf(List<String> pages) throws IOException {
        PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        PDRectangle size = PDRectangle.A4;
        float maxWidth = size.getWidth() - 2 * MARGIN;

        try (PDDocument out = new PDDocument(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            for (String pageText : pages) {
                List<String> lines = new ArrayList<>();
                for (String raw : pageText.split("\\r?\\n", -1)) {
                    lines.addAll(wrap(sanitize(raw, font), font, maxWidth));
                }
                // drop trailing empty lines
                while (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) {
                    lines.remove(lines.size() - 1);
                }

                int i = 0;
                do {
                    PDPage page = new PDPage(size);
                    out.addPage(page);
                    try (PDPageContentStream cs = new PDPageContentStream(out, page)) {
                        cs.beginText();
                        cs.setFont(font, FONT_SIZE);
                        cs.setLeading(LEADING);
                        cs.newLineAtOffset(MARGIN, size.getHeight() - MARGIN);
                        float y = size.getHeight() - MARGIN;
                        while (i < lines.size() && y > MARGIN) {
                            cs.showText(lines.get(i++));
                            cs.newLine();
                            y -= LEADING;
                        }
                        cs.endText();
                    }
                } while (i < lines.size());
            }
            if (out.getNumberOfPages() == 0) {
                out.addPage(new PDPage(size));
            }
            out.save(bos);
            return bos.toByteArray();
        }
    }

    /**
     * Remove zero-width / formatting characters (Google Docs adds U+200B etc.) and turn
     * non-breaking spaces into normal spaces, so they neither break detection nor print as "?".
     */
    private static String cleanInvisible(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        s.codePoints().forEach(cp -> {
            if (cp == ' ' || cp == ' ' || cp == ' ') {
                sb.append(' ');
            } else if (Character.getType(cp) != Character.FORMAT) {
                sb.appendCodePoint(cp);
            }
        });
        return sb.toString();
    }

    /** Replace characters the standard font cannot draw, so writing never fails. */
    private static String sanitize(String s, PDType1Font font) {
        StringBuilder sb = new StringBuilder(s.length());
        s.codePoints().forEach(cp -> {
            String ch = new String(Character.toChars(cp));
            if (cp == '\t') {
                sb.append("    ");
                return;
            }
            try {
                font.encode(ch);
                sb.append(ch);
            } catch (IllegalArgumentException | IOException e) {
                sb.append('?');
            }
        });
        return sb.toString();
    }

    private static List<String> wrap(String line, PDType1Font font, float maxWidth) throws IOException {
        List<String> result = new ArrayList<>();
        if (width(line, font) <= maxWidth) {
            result.add(line);
            return result;
        }
        StringBuilder current = new StringBuilder();
        for (String word : line.split(" ")) {
            String candidate = current.isEmpty() ? word : current + " " + word;
            if (width(candidate, font) <= maxWidth) {
                current.setLength(0);
                current.append(candidate);
                continue;
            }
            if (!current.isEmpty()) {
                result.add(current.toString());
                current.setLength(0);
            }
            // a single word longer than the line: hard-split it
            while (width(word, font) > maxWidth) {
                int cut = word.length() - 1;
                while (cut > 1 && width(word.substring(0, cut), font) > maxWidth) cut--;
                result.add(word.substring(0, cut));
                word = word.substring(cut);
            }
            current.append(word);
        }
        if (!current.isEmpty()) result.add(current.toString());
        return result;
    }

    private static float width(String s, PDType1Font font) throws IOException {
        return font.getStringWidth(s) / 1000f * FONT_SIZE;
    }
}
