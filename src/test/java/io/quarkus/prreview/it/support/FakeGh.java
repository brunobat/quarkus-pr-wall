package io.quarkus.prreview.it.support;

import java.nio.file.Path;
import java.util.Map;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

/** Points the app at the fake {@code gh} script in {@code src/test/resources/fake-gh}. */
public class FakeGh implements QuarkusTestResourceLifecycleManager {

    public static final Path BINARY = Path.of("src/test/resources/fake-gh/gh").toAbsolutePath();
    /** Every invocation of the fake {@code gh}, one per line, appended across the build. */
    public static final Path CALLS_LOG = Path.of("target/fake-gh-calls.log").toAbsolutePath();

    @Override
    public Map<String, String> start() {
        return Map.of("pr-review.gh.binary", BINARY.toString());
    }

    @Override
    public void stop() {
    }
}
