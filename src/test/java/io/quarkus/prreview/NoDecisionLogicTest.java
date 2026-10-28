package io.quarkus.prreview;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Classify, don't decide (Clarifications #26, #32): no code compares a {@code jev} probability with a number, and
 * no code is about thresholds, cut-offs or verdicts.
 * <p>
 * A heuristic guard, not a proof. It scans {@code src/main/java} with comments and string literals removed (so
 * documentation and prompts may say that there are no verdicts) for a comparison operator next to a
 * {@code probability()} or {@code probabilities().get(...)} call, a {@code compare}/{@code compareTo} on one, and
 * the words themselves. A probability first stored in a variable and compared later is not caught. Formatting, such
 * as {@code %.2f}, is allowed.
 */
class NoDecisionLogicTest {

    static final Path MAIN = Path.of("src/main/java");

    /** A probability read, as produced by {@code QuestionResult} and LangChain4j's answers. */
    private static final String PROBABILITY = "(?:probability\\(\\)|probabilities\\(\\)\\.get\\([^)]*\\))";
    /** {@code <}, {@code >}, {@code <=}, {@code >=}, but not the {@code >} of a lambda arrow. */
    private static final String COMPARISON = "(?<!-)[<>]=?";

    private static final List<Pattern> RULES = List.of(
            Pattern.compile(PROBABILITY + "\\s*" + COMPARISON),
            Pattern.compile(COMPARISON + "\\s*[\\w.()]*" + PROBABILITY),
            Pattern.compile("compare(?:To)?\\([^;]*probabilit"),
            Pattern.compile("(?i)threshold|cut-?off|verdict"));

    @Test
    void mainCodeHasNoDecisionLogic() throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                violations(Files.readString(file)).forEach(v -> found.add(MAIN.relativize(file) + ":" + v));
            }
        }
        assertEquals(List.of(), found, "jev's probabilities are reported, never compared or turned into decisions");
    }

    @Test
    void theGuardCatchesComparisonsAndDecisionWords() {
        assertEquals(1, violations("if (p.probability() > 0.5) {").size());
        assertEquals(1, violations("boolean b = 0.5 <= answer.probability();").size());
        assertEquals(1, violations("if (c.probabilities().get(\"undetermined\") >= 0.3) {").size());
        assertEquals(1, violations("return Double.compare(a.probability(), 0.7);").size());
        assertEquals(1, violations("double threshold = 0.5;").size());
        assertEquals(1, violations("enum Verdict { NEEDS_TESTS }").size());
        assertEquals(1, violations("int cutOff;").size());
    }

    @Test
    void theGuardAllowsFormattingGenericsLambdasCommentsAndPrompts() {
        assertEquals(List.of(), violations("""
                String s = String.format(Locale.ROOT, "%.2f", yesNo.probability());
                Map<String, Double> probabilities = new LinkedHashMap<>();
                case QuestionResult.YesNo yesNo -> rounded(yesNo.probability());
                Double p = choice.probabilities().get(choice.value());
                // no threshold here
                /** No verdicts, no cut-offs. */
                @SystemMessage(\"""
                        Give no verdict, recommendation or severity.
                        \""")
                String prompt = "never apply a threshold > 0.5";
                char c = '>';
                """));
    }

    /** {@code <line>: <code>} for each line, with comments and literals removed, that breaks a rule. */
    static List<String> violations(String source) {
        String[] lines = codeOnly(source).split("\n", -1);
        List<String> found = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (RULES.stream().anyMatch(rule -> rule.matcher(line).find())) {
                found.add((i + 1) + ": " + line.strip());
            }
        }
        return found;
    }

    /** The source with comments, string literals, text blocks and char literals blanked out; line breaks kept. */
    static String codeOnly(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            if (source.startsWith("//", i)) {
                i = blank(source, i, source.indexOf('\n', i), out);
            } else if (source.startsWith("/*", i)) {
                i = blank(source, i, end(source, "*/", i + 2), out);
            } else if (source.startsWith("\"\"\"", i)) {
                i = blank(source, i, end(source, "\"\"\"", i + 3), out);
            } else if (source.charAt(i) == '"' || source.charAt(i) == '\'') {
                i = blank(source, i, literalEnd(source, i), out);
            } else {
                out.append(source.charAt(i++));
            }
        }
        return out.toString();
    }

    /** Where {@code close} ends, searching from {@code from}, or the end of the source. */
    private static int end(String source, String close, int from) {
        int at = source.indexOf(close, from);
        return at < 0 ? source.length() : at + close.length();
    }

    /** The end of a {@code "..."} or {@code '...'} literal starting at {@code start}, honouring escapes. */
    private static int literalEnd(String source, int start) {
        char quote = source.charAt(start);
        int i = start + 1;
        while (i < source.length() && source.charAt(i) != quote && source.charAt(i) != '\n') {
            i += source.charAt(i) == '\\' ? 2 : 1;
        }
        return Math.min(i + 1, source.length());
    }

    /** Appends spaces for {@code [from, to)}, keeping line breaks, and returns {@code to}. */
    private static int blank(String source, int from, int to, StringBuilder out) {
        int end = to < 0 ? source.length() : to;
        for (int i = from; i < end; i++) {
            out.append(source.charAt(i) == '\n' ? '\n' : ' ');
        }
        return end;
    }
}
