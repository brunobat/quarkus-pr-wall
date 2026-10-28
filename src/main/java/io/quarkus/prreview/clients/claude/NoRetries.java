package io.quarkus.prreview.clients.claude;

import jakarta.enterprise.context.ApplicationScoped;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import io.quarkiverse.langchain4j.ModelBuilderCustomizer;

/**
 * One attempt per Claude call. The extension only accepts {@code max-retries} of 1 or more, and LangChain4j retries
 * even an attempt that was interrupted because the summary timed out, so the call would go on in the background.
 */
@ApplicationScoped
public class NoRetries implements ModelBuilderCustomizer<AnthropicChatModel.AnthropicChatModelBuilder> {

    @Override
    public void customize(AnthropicChatModel.AnthropicChatModelBuilder builder) {
        builder.maxRetries(0);
    }
}
