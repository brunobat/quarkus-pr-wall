package io.quarkus.prreview.facts.services;



import io.quarkus.prreview.facts.model.Hunk;
import io.quarkus.prreview.facts.model.ChangedFile;
import io.quarkus.prreview.facts.model.TestFacts;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Locates the tests added/modified in the diff and reports the two load-bearing syntactic facts per
 * test — {@code disabled} and {@code has_assertion} — plus the test method bodies (design §3.1
 * {@code map-tests}). Existence alone never clears the §4 Blocker; these facts are what let the
 * reasoning layer tell a real test from a no-op one.
 *
 * <p>v1 limitation: only tests present in the diff are mapped. Confirming coverage by a pre-existing
 * in-tree test the PR does not modify requires a repo tree walk and is deferred — pipeline B's
 * existing-coverage class therefore downgrades rather than clears (design §4.0).
 */
@ApplicationScoped
public class MapTests {

    private static final Pattern DISABLED = Pattern.compile(
            "@Disabled|@Ignore|enabled\\s*=\\s*false|Assumptions?\\.|assumeTrue");
    private static final Pattern ASSERTION = Pattern.compile(
            "\\bassertThat\\s*\\(|\\bassert[A-Z]\\w*\\s*\\(|\\bfail\\s*\\(|\\bverify\\s*\\(|\\bexpect\\w*\\s*\\(");

    public List<TestFacts> map(List<ChangedFile> files) {
        List<ChangedFile> runtime = files.stream().filter(ChangedFile::runtimeScope).toList();
        List<TestFacts> out = new ArrayList<>();

        for (ChangedFile f : files) {
            if (!f.isTest()) {
                continue;
            }
            List<String> body = new ArrayList<>();
            boolean disabled = false;
            boolean assertion = false;
            for (Hunk h : f.hunks()) {
                for (String line : h.addedLines()) {
                    body.add(line);
                    if (DISABLED.matcher(line).find()) {
                        disabled = true;
                    }
                    if (ASSERTION.matcher(line).find()) {
                        assertion = true;
                    }
                }
            }
            out.add(new TestFacts(f.path(), guessTarget(f.path(), runtime), true, disabled, assertion, body));
        }
        return out;
    }

    /** Heuristic: FooTest/FooTests/FooIT ↔ Foo production class in the same PR. */
    private static String guessTarget(String testPath, List<ChangedFile> runtime) {
        String base = ClassifyFiles.fileName(testPath)
                .replaceFirst("\\.(java|kt|groovy)$", "")
                .replaceFirst("(Test|Tests|IT|TestCase)$", "")
                .toLowerCase(Locale.ROOT);
        if (base.isEmpty()) {
            return null;
        }
        for (ChangedFile r : runtime) {
            String rn = ClassifyFiles.fileName(r.path())
                    .replaceFirst("\\.(java|kt|groovy)$", "")
                    .toLowerCase(Locale.ROOT);
            if (rn.equals(base)) {
                return r.path();
            }
        }
        return null;
    }
}
