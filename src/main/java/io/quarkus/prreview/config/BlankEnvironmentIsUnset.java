package io.quarkus.prreview.config;

import java.util.regex.Pattern;

import jakarta.annotation.Priority;

import io.smallrye.config.ConfigSourceInterceptor;
import io.smallrye.config.ConfigSourceInterceptorContext;
import io.smallrye.config.ConfigValue;
import io.smallrye.config.Priorities;

/**
 * An exported but empty environment variable counts as unset, so {@code ${TYPESAFE_API_KEY:not-set}} falls back to
 * its default. Without this, an empty {@code TYPESAFE_API_KEY} makes startup fail with a message that names only an
 * internal property, even for {@code --help}.
 */
// Below SmallRye's ExpressionConfigSourceInterceptor (LIBRARY + 300): it looks up ${...} variables further down the
// chain, so this interceptor must sit there to see them.
@Priority(Priorities.LIBRARY + 200)
public class BlankEnvironmentIsUnset implements ConfigSourceInterceptor {

    private static final Pattern ENVIRONMENT_NAME = Pattern.compile("[A-Z][A-Z0-9_]*");

    @Override
    public ConfigValue getValue(ConfigSourceInterceptorContext context, String name) {
        ConfigValue value = context.proceed(name);
        if (value != null && (value.getValue() == null || value.getValue().isBlank())
                && ENVIRONMENT_NAME.matcher(name).matches()) {
            return null;
        }
        return value;
    }
}
