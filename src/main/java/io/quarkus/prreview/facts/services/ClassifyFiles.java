package io.quarkus.prreview.facts.services;



import io.quarkus.prreview.facts.model.Hunk;
import io.quarkus.prreview.facts.model.ChangedFile;
import io.quarkus.prreview.facts.model.FileTag;
import io.quarkus.prreview.facts.model.ParsedFile;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Tags each changed file (design §3.1 {@code classify-files}). Two axes are independent:
 * <ul>
 *   <li>{@link ChangedFile#tag()} — drives the §5 security floor (invariant I-1).</li>
 *   <li>{@link ChangedFile#runtimeScope()} — drives §4's test requirement; true for all production
 *       code in any module <em>and</em> for shipped-dependency manifests.</li>
 * </ul>
 *
 * <p>Paths are NFKC-normalized and zero-width-stripped before matching; a path that only matches after
 * normalization is flagged {@link ChangedFile#pathSuspicious()}. A file matching the private
 * security-signal list (by changed content) is floored to {@link FileTag#BUILD_CI_SECURITY} regardless
 * of path. A file that cannot be classified cleanly defaults to {@link FileTag#BUILD_CI_SECURITY}
 * (fail safe, never {@link FileTag#OTHER}).
 */
@ApplicationScoped
public class ClassifyFiles {

    private static final Pattern ZERO_WIDTH = Pattern.compile("[\\u200B-\\u200F\\u202A-\\u202E\\uFEFF]");

    private static final List<String> GOVERNANCE = List.of(
            "COMMITTERS.adoc", "CONTRIBUTING.md", "AI_POLICY.md");

    private List<Pattern> securitySignals = List.of();

    /**
     * Optional path to a private signal list kept outside the repo (plan §11, design §3.1
     * rules-private/engine-public split). When set it <em>replaces</em> the bundled list, so the
     * patterns an adversary would most want to read never have to ship in the public source tree.
     */
    @ConfigProperty(name = "security.signals-file")
    Optional<String> signalsFile;

    @PostConstruct
    void loadSignals() {
        List<Pattern> patterns = new ArrayList<>();
        try (InputStream in = openSignals()) {
            if (in != null) {
                BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                String line;
                while ((line = r.readLine()) != null) {
                    String s = line.strip();
                    if (s.isEmpty() || s.startsWith("#")) {
                        continue;
                    }
                    String regex = looksLikeRegex(s) ? s : Pattern.quote(s);
                    patterns.add(Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
                }
            }
        } catch (Exception e) {
            // Fail safe: with no signal list we still classify by path; content-floor just won't fire.
            patterns = new ArrayList<>();
        }
        this.securitySignals = List.copyOf(patterns);
    }

    /** The private external list if one is configured and readable, else the bundled public default. */
    private InputStream openSignals() throws IOException {
        if (signalsFile != null && signalsFile.isPresent() && !signalsFile.get().isBlank()) {
            Path path = Path.of(signalsFile.get());
            if (Files.isReadable(path)) {
                return Files.newInputStream(path);
            }
            throw new IOException("security.signals-file is set but not readable: " + path);
        }
        return Thread.currentThread().getContextClassLoader().getResourceAsStream("security-signals.txt");
    }

    private static boolean looksLikeRegex(String s) {
        return s.contains("[") || s.contains("?") || s.contains("(") || s.contains("\\");
    }

    public List<ChangedFile> classify(List<ParsedFile> parsed) {
        List<ChangedFile> out = new ArrayList<>(parsed.size());
        for (ParsedFile pf : parsed) {
            String raw = pf.path();
            String normalized = normalize(raw);
            boolean suspicious = !normalized.equals(raw);

            FileTag byPath = tagByPath(normalized);
            boolean contentSecurity = matchesSecuritySignal(pf);

            FileTag tag = contentSecurity ? FileTag.BUILD_CI_SECURITY : byPath;
            boolean runtimeScope = isRuntimeScope(normalized);

            out.add(new ChangedFile(
                    normalized,
                    tag,
                    runtimeScope,
                    pf.addedLineCount(),
                    pf.removedLineCount(),
                    module(normalized),
                    pf.hunks(),
                    suspicious));
        }
        return out;
    }

    static String normalize(String path) {
        String stripped = ZERO_WIDTH.matcher(path).replaceAll("");
        return Normalizer.normalize(stripped, Normalizer.Form.NFKC);
    }

    private FileTag tagByPath(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        String fileName = fileName(path);

        if (GOVERNANCE.stream().anyMatch(g -> path.endsWith(g))) {
            return FileTag.BUILD_CI_SECURITY;
        }
        if (lower.startsWith(".github/") || lower.contains("/.github/")
                || fileName.equals("pom.xml") || fileName.endsWith(".gradle") || fileName.endsWith(".gradle.kts")
                || lower.contains("bom/") || fileName.contains("bom")
                || lower.contains("/workflows/")) {
            return FileTag.BUILD_CI_SECURITY;
        }
        if (isTestPath(path)) {
            return FileTag.TEST;
        }
        if (lower.endsWith(".adoc") || lower.endsWith(".md") || lower.contains("/docs/") || lower.startsWith("docs/")) {
            return FileTag.DOCS;
        }
        if (isProductionCode(path)) {
            return FileTag.RUNTIME;
        }
        if (fileName.equals("application.properties") || fileName.equals("application.yml")
                || fileName.equals("application.yaml")) {
            return FileTag.RUNTIME;
        }
        // Fail safe: unknown classification defaults to build-ci-security, never other.
        return FileTag.BUILD_CI_SECURITY;
    }

    private static boolean isTestPath(String path) {
        if (path.contains("/src/test/") || path.startsWith("src/test/")) {
            return true;
        }
        String name = fileName(path);
        return name.endsWith("Test.java") || name.endsWith("Tests.java") || name.endsWith("IT.java")
                || name.endsWith("TestCase.java");
    }

    private static boolean isProductionCode(String path) {
        boolean code = path.endsWith(".java") || path.endsWith(".kt") || path.endsWith(".groovy");
        return code && (path.contains("/src/main/") || path.startsWith("src/main/"));
    }

    private boolean isRuntimeScope(String path) {
        String name = fileName(path);
        boolean dependencyManifest = name.equals("pom.xml") || name.endsWith(".gradle")
                || name.endsWith(".gradle.kts") || name.contains("bom");
        return isProductionCode(path) || dependencyManifest;
    }

    private boolean matchesSecuritySignal(ParsedFile pf) {
        for (Hunk h : pf.hunks()) {
            for (String line : h.addedLines()) {
                for (Pattern p : securitySignals) {
                    if (p.matcher(line).find()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    static String fileName(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(i + 1);
    }

    static String module(String path) {
        int src = path.indexOf("/src/");
        if (src > 0) {
            return path.substring(0, src);
        }
        int slash = path.indexOf('/');
        return slash < 0 ? "(root)" : path.substring(0, slash);
    }
}
