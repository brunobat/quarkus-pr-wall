package io.quarkus.prreview.config;

import java.util.regex.Pattern;

import jakarta.annotation.Priority;

import io.smallrye.config.ConfigSourceInterceptor;
import io.smallrye.config.ConfigSourceInterceptorContext;
import io.smallrye.config.ConfigValue;
import io.smallrye.config.Priorities;

/**
 * An exported but empty environment variable counts as unset, so {@code ${TYPESAFE_API_KEY:not-set}} falls back to
 * its default. Without this, an empty {@code TYPESAFE_API_KEY} or {@code JEV_PROJECT_CONTEXT} makes startup fail with
 * a message that names only an internal property, even for {@code --help}.
 * <p>
 * A blank API-key property, e.g. from an empty {@code QUARKUS_LANGCHAIN4J_TYPESAFE_API_KEY}, becomes
 * {@link ApiKey#NOT_SET}: unsetting it would hide {@code application.yml}'s value too. Other empty variables under
 * their property form ({@code PR_REVIEW_*}) are not covered, for the same reason: unsetting one hides its default.
 */
// Below SmallRye's ExpressionConfigSourceInterceptor (LIBRARY + 300): it looks up ${...} variables further down the
// chain, so this interceptor must sit there to see them.
@Priority(Priorities.LIBRARY + 200)
public class BlankEnvironmentIsUnset implements ConfigSourceInterceptor {

    private static final Pattern ENVIRONMENT_NAME = Pattern.compile("[A-Z][A-Z0-9_]*");

    @Override
    public ConfigValue getValue(ConfigSourceInterceptorContext context, String name) {
        ConfigValue value = context.proceed(name);
        if (value == null || (value.getValue() != null && !value.getValue().isBlank())) {
            return value;
        }
        if (ApiKey.PROPERTIES.contains(name)) {
            return value.withValue(ApiKey.NOT_SET);
        }
        return ENVIRONMENT_NAME.matcher(name).matches() ? null : value;
    }
}
