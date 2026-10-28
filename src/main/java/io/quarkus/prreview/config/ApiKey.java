package io.quarkus.prreview.config;

import java.util.Set;

/** The API-key properties of the two LangChain4j extensions, and the value that means no key is set. */
public final class ApiKey {

    /**
     * The key's value when its variable is not set or empty, see {@code application.yml}. Not the extensions' own
     * {@code dummy}, which makes startup fail once an AI service exists.
     */
    public static final String NOT_SET = "not-set";

    /** The properties a blank value is replaced in, see {@link BlankEnvironmentIsUnset}. */
    static final Set<String> PROPERTIES = Set.of(
            "quarkus.langchain4j.typesafe.api-key",
            "quarkus.langchain4j.anthropic.api-key");

    private ApiKey() {
    }
}
