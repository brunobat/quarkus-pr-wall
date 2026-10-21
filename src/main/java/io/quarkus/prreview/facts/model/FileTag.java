package io.quarkus.prreview.facts.model;

/**
 * Classification tag for a changed file (design §3.1 {@code classify-files}).
 *
 * <p>{@code RUNTIME} means <em>all production code and shipped dependencies</em> in any module
 * (runtime/, deployment/, spi/, core/, extensions/, devtools/, independent-projects/ …), not only
 * files under a {@code runtime/} path segment. A file that is both a dependency change and
 * security-sensitive (e.g. {@code pom.xml}) is tagged {@link #BUILD_CI_SECURITY} for the §5 floor
 * but is still in runtime scope for §4's test check — the two axes are independent, so the test
 * scope of a file is tracked separately on {@link ChangedFile#runtimeScope()}.
 *
 * <p>Fail-safe: a file that cannot be classified cleanly defaults to {@link #BUILD_CI_SECURITY}
 * (never {@link #OTHER}).
 */
public enum FileTag {
    RUNTIME,
    TEST,
    BUILD_CI_SECURITY,
    DOCS,
    OTHER
}
