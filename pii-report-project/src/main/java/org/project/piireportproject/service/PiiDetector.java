package org.project.piireportproject.service;

import org.project.piireportproject.model.PiiMatch;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Rule-based PII detector.
 * Structured PII (email, phone, Aadhaar, PAN, card, IFSC) is found by format.
 * Names, locations, addresses, PIN codes and dates of birth are found by context
 * ("my name is ...", "I live in ...", "Address: ...") and by a list of Indian
 * cities/states (resources/pii/locations.txt).
 */
@Service
public class PiiDetector {

    /** A regex rule; {@code group} is the capture group holding the PII value (0 = whole match). */
    private record Rule(String type, Pattern pattern, int group) {}

    private static final String WORD = "[A-Za-z][A-Za-z'.]*";
    private static final String CAP_WORD = "[A-Z][a-zA-Z'.]*";

    /** Words that can never be part of a name/place captured after a cue. */
    private static final Set<String> STOP_WORDS = Set.of(
            "and", "or", "but", "i", "am", "is", "are", "was", "were", "the", "a", "an", "in", "at", "on", "of",
            "from", "to", "with", "my", "me", "live", "lives", "living", "work", "working", "study",
            "studying", "here", "there", "this", "that", "not", "also", "very", "so", "currently", "now",
            "years", "year", "old", "happy", "glad", "sorry", "fine", "good", "writing", "going", "doing",
            "student", "engineer", "developer", "manager", "your", "his", "her", "their", "our", "sir", "madam");

    private final List<Rule> rules = new ArrayList<>();
    private final Pattern locationGazetteer;

    public PiiDetector() {
        // structured PII
        rules.add(new Rule("EMAIL", Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), 0));
        rules.add(new Rule("PAN", Pattern.compile("\\b[A-Z]{5}[0-9]{4}[A-Z]\\b"), 0));
        rules.add(new Rule("AADHAAR", Pattern.compile("\\b[2-9]\\d{3}\\s?\\d{4}\\s?\\d{4}\\b"), 0));
        rules.add(new Rule("PHONE", Pattern.compile("(?<!\\d)(?:\\+91[- ]?)?[6-9]\\d{4}[ -]?\\d{5}(?!\\d)"), 0));
        rules.add(new Rule("CARD", Pattern.compile("(?<!\\d)(?:\\d[ -]?){12,18}\\d(?!\\d)"), 0));
        rules.add(new Rule("IFSC", Pattern.compile("\\b[A-Z]{4}0[A-Z0-9]{6}\\b"), 0));

        // context-based PII
        rules.add(new Rule("PINCODE", Pattern.compile(
                "(?i:\\b(?:pin\\s*code|pincode|pin|postal\\s*code|zip(?:\\s*code)?))\\s*(?:no\\.?)?\\s*[:\\-]?\\s*([1-9]\\d{2}\\s?\\d{3})\\b"), 1));
        rules.add(new Rule("DOB", Pattern.compile(
                "(?i:\\b(?:dob|d\\.o\\.b\\.?|date\\s+of\\s+birth|born\\s+on|birth\\s*date))\\s*[:\\-]?\\s*("
                        + "\\d{1,2}[/.\\-]\\d{1,2}[/.\\-]\\d{2,4}"
                        + "|\\d{1,2}(?:st|nd|rd|th)?\\s+[A-Za-z]{3,9},?\\s+\\d{4}"
                        + "|[A-Za-z]{3,9}\\s+\\d{1,2}(?:st|nd|rd|th)?,?\\s+\\d{4})"), 1));
        rules.add(new Rule("ADDRESS", Pattern.compile(
                "(?i:\\b(?:address|addr\\.?|residential\\s+address|permanent\\s+address|correspondence\\s+address))\\s*[:\\-]\\s*([^\\n]{5,200})"), 1));

        // strong name cues: the name may be written in any case
        rules.add(new Rule("NAME", Pattern.compile(
                "(?i:\\b(?:my\\s+name\\s+is|my\\s+name's|name\\s*[:\\-]|full\\s+name\\s*[:\\-]?|"
                        + "father'?s\\s+name\\s*[:\\-]?|mother'?s\\s+name\\s*[:\\-]?|applicant\\s*[:\\-]|customer\\s*[:\\-]|"
                        + "s/o|d/o|w/o|c/o|son\\s+of|daughter\\s+of|wife\\s+of|husband\\s+of|called|named))(?:\\s+|(?<=[:\\-])\\s*)"
                        + "(" + WORD + "(?:[ \\t]+" + WORD + "){0,3})"), 1));
        // weak name cues / titles: the name must be Capitalised
        rules.add(new Rule("NAME", Pattern.compile(
                "(?i:\\b(?:i\\s+am|i'm|this\\s+is|dear|hi|hello|regards,?|sincerely,?|thanks,?|"
                        + "mr|mrs|ms|miss|dr|prof|shri|smt|kumari|sri)\\.?)\\s+"
                        + "(" + CAP_WORD + "(?:[ \\t]+" + CAP_WORD + "){0,3})"), 1));

        // places after a living/location cue (any case)
        rules.add(new Rule("LOCATION", Pattern.compile(
                "(?i:\\b(?:i\\s+live\\s+in|lives?\\s+in|living\\s+in|i\\s+stay\\s+in|stays?\\s+in|staying\\s+in|"
                        + "resident\\s+of|residing\\s+(?:at|in)|reside\\s+in|based\\s+in|born\\s+in|hometown\\s+is|"
                        + "i\\s+am\\s+from|i'm\\s+from|native\\s+of|city\\s*[:\\-]|state\\s*[:\\-]|village\\s*[:\\-]|"
                        + "district\\s*[:\\-]|moved\\s+to|located\\s+in))(?:\\s+|(?<=[:\\-])\\s*)"
                        + "(" + WORD + "(?:[ \\t]+" + WORD + "){0,2})"), 1));

        locationGazetteer = buildGazetteer();
    }

    public List<PiiMatch> detect(String text) {
        List<PiiMatch> all = new ArrayList<>();

        for (Rule rule : rules) {
            Matcher m = rule.pattern().matcher(text);
            while (m.find()) {
                int start = m.start(rule.group());
                int end = m.end(rule.group());
                if (start < 0) continue;
                String value = m.group(rule.group());

                if (rule.type().equals("CARD") && !passesLuhn(value)) continue;

                if (rule.type().equals("NAME") || rule.type().equals("LOCATION")) {
                    int[] span = keptSpan(value);
                    if (span == null) continue;
                    end = start + span[1];
                    start = start + span[0];
                    value = text.substring(start, end);
                }
                if (rule.type().equals("ADDRESS")) {
                    String trimmed = value.stripTrailing();
                    end = start + trimmed.length();
                    value = trimmed;
                }
                all.add(new PiiMatch(rule.type(), value, start, end));
            }
        }

        // known city / state names anywhere in the text
        Matcher g = locationGazetteer.matcher(text);
        while (g.find()) {
            all.add(new PiiMatch("LOCATION", g.group(), g.start(), g.end()));
        }

        // a name found once is PII everywhere: also catch "Rahul" when "Rahul Sharma" was found
        all.addAll(findRepeats(text, all, "NAME"));

        return removeOverlaps(all);
    }

    private static final Pattern TOKEN = Pattern.compile("\\S+");

    private static final Set<String> TITLES = Set.of(
            "mr", "mrs", "ms", "miss", "dr", "prof", "shri", "smt", "kumari", "sri");

    /**
     * The part of a captured phrase that is really a name/place, as [from, to) offsets.
     * Skips leading titles ("Mr. Amit" -> "Amit"), stops at the first stop word
     * ("Rahul and I live" -> "Rahul") and at a sentence end ("delhi. Rahul" -> "delhi").
     * Returns null when nothing is left.
     */
    private static int[] keptSpan(String phrase) {
        int from = -1, to = -1;
        Matcher w = TOKEN.matcher(phrase);
        while (w.find()) {
            String raw = w.group();
            String clean = raw.replaceAll("[.']+$", "");
            String lower = clean.toLowerCase(Locale.ROOT);
            if (from < 0 && TITLES.contains(lower)) continue;
            if (clean.isEmpty() || STOP_WORDS.contains(lower)) break;
            if (from < 0) from = w.start();
            to = w.start() + clean.length();
            // a word ending in "." closes the sentence, unless it is an initial like "K."
            if (raw.endsWith(".") && clean.length() > 1) break;
        }
        return from < 0 ? null : new int[]{from, to};
    }

    private static List<PiiMatch> findRepeats(String text, List<PiiMatch> found, String type) {
        Set<String> terms = new HashSet<>();
        for (PiiMatch m : found) {
            if (!m.type().equals(type)) continue;
            terms.add(m.value());
            for (String part : m.value().split("\\s+")) {
                if (part.length() >= 3) terms.add(part);
            }
        }
        List<PiiMatch> extra = new ArrayList<>();
        for (String term : terms) {
            Matcher m = Pattern.compile("(?i)\\b" + Pattern.quote(term) + "\\b").matcher(text);
            while (m.find()) {
                extra.add(new PiiMatch(type, m.group(), m.start(), m.end()));
            }
        }
        return extra;
    }

    private static Pattern buildGazetteer() {
        try (InputStream in = PiiDetector.class.getResourceAsStream("/pii/locations.txt")) {
            if (in == null) return Pattern.compile("(?!)");
            List<String> places = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .lines().map(String::trim)
                    .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                    .sorted(Comparator.comparingInt(String::length).reversed())
                    .map(p -> Pattern.quote(p).replace(" ", "\\E\\s+\\Q"))
                    .collect(Collectors.toList());
            return Pattern.compile("(?i)\\b(?:" + String.join("|", places) + ")\\b");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean passesLuhn(String raw) {
        String digits = raw.replaceAll("\\D", "");
        if (digits.length() < 13 || digits.length() > 19) return false;
        int sum = 0;
        boolean dbl = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (dbl) { d *= 2; if (d > 9) d -= 9; }
            sum += d;
            dbl = !dbl;
        }
        return sum % 10 == 0;
    }

    private List<PiiMatch> removeOverlaps(List<PiiMatch> matches) {
        matches.sort(Comparator.comparingInt(PiiMatch::start).thenComparing(m -> -(m.end() - m.start())));
        List<PiiMatch> res = new ArrayList<>();
        int lastEnd = -1;
        for (PiiMatch m : matches) {
            if (m.start() >= lastEnd) {
                res.add(m);
                lastEnd = m.end();
            }
        }
        return res;
    }
}
