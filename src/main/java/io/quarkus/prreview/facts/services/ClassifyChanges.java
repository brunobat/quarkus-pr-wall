package io.quarkus.prreview.facts.services;



import io.quarkus.prreview.facts.model.Hunk;
import io.quarkus.prreview.facts.model.ChangeClassification;
import io.quarkus.prreview.facts.model.ChangedFile;
import io.quarkus.prreview.facts.model.ExemptClass;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Computes the deterministically-detectable test-exempt signal per runtime-scoped file (design §3.1
 * {@code classify-changes}, §4.0) — from the diff hunks themselves, <strong>never</strong> from the PR
 * title/label/body (those are untrusted, invariant I-4).
 *
 * <p>A file with any non-exempt behavioral hunk is {@link ExemptClass#NOT_EXEMPT}; mixed changes fail
 * toward requiring a test. The judgment-based classes (revert, message-only, deprecation, existing
 * coverage) are deferred to deep pipeline B.
 */
@ApplicationScoped
public class ClassifyChanges {

    public ChangeClassification classify(List<ChangedFile> files) {
        Map<String, ExemptClass> perFile = new LinkedHashMap<>();
        for (ChangedFile f : files) {
            if (!f.runtimeScope()) {
                continue;
            }
            perFile.put(f.path(), classifyOne(f));
        }
        return new ChangeClassification(perFile);
    }

    private ExemptClass classifyOne(ChangedFile f) {
        String name = ClassifyFiles.fileName(f.path()).toLowerCase(Locale.ROOT);

        if (isGenerated(f.path())) {
            return ExemptClass.GENERATED_FILE;
        }
        if (f.addedLineCount() == 0 && f.removedLineCount() > 0) {
            return ExemptClass.DELETION_ONLY;
        }
        boolean isPom = name.equals("pom.xml") || name.endsWith(".gradle") || name.endsWith(".gradle.kts");
        if (isPom && isDependencyVersionOnly(f)) {
            return ExemptClass.DEPENDENCY_VERSION_ONLY;
        }
        if (isCommentOnly(f)) {
            return ExemptClass.COMMENT_ONLY;
        }
        return ExemptClass.NOT_EXEMPT;
    }

    private static boolean isGenerated(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        return p.contains("/target/") || p.contains("/generated/") || p.contains("/generated-sources/")
                || p.contains("/build/generated") || p.contains(".generated.");
    }

    private boolean isCommentOnly(ChangedFile f) {
        boolean any = false;
        for (Hunk h : f.hunks()) {
            for (String line : h.addedLines()) {
                if (!isCommentLine(line)) {
                    return false;
                }
                any = true;
            }
            for (String line : h.removedLines()) {
                if (!isCommentLine(line)) {
                    return false;
                }
                any = true;
            }
        }
        return any;
    }

    private static boolean isCommentLine(String line) {
        String t = line.strip();
        return t.isEmpty() || t.startsWith("//") || t.startsWith("/*") || t.startsWith("*") || t.startsWith("*/");
    }

    private boolean isDependencyVersionOnly(ChangedFile f) {
        boolean any = false;
        for (Hunk h : f.hunks()) {
            for (String line : concat(h.addedLines(), h.removedLines())) {
                String t = line.strip();
                if (t.isEmpty() || isCommentLine(t)) {
                    continue;
                }
                if (!(t.contains("<version>") || t.contains("<artifactId>") || t.contains("<groupId>")
                        || t.matches(".*\\bversion\\b.*=.*") /* gradle */)) {
                    return false;
                }
                any = true;
            }
        }
        return any;
    }

    private static Iterable<String> concat(List<String> a, List<String> b) {
        return () -> new java.util.Iterator<>() {
            final java.util.Iterator<String> ia = a.iterator();
            final java.util.Iterator<String> ib = b.iterator();

            @Override
            public boolean hasNext() {
                return ia.hasNext() || ib.hasNext();
            }

            @Override
            public String next() {
                return ia.hasNext() ? ia.next() : ib.next();
            }
        };
    }
}
