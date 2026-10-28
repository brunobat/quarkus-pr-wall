package io.quarkus.prreview.clients.claude;

import jakarta.enterprise.context.ApplicationScoped;

import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;

/**
 * The only access to Claude: a short prose restatement of {@code jev}'s classification. It has no tools and no
 * memory, and it sees only the answers and the changed-file list, never the diff, title or body.
 * <p>
 * Application scoped because the command runs without a request context, which the default request scope needs.
 */
@ApplicationScoped
@RegisterAiService(tools = {}, chatMemoryProviderSupplier = RegisterAiService.NoChatMemoryProviderSupplier.class)
public interface ClassificationSummarizer {

    /** The system message, sent as is; part of the reported Claude submission size. */
    String SYSTEM = """
            You restate a classification of a GitHub pull request made by another model, jev.
            Write at most 3 sentences of plain prose.
            Only describe what jev's answers say. Give no verdict, recommendation or severity.
            Never contradict, change, round differently or reinterpret the given probabilities.
            """;

    /**
     * @param message the whole user message, built by the caller so that its size is known
     * @return the summary, with Claude's reported token usage
     */
    @SystemMessage(SYSTEM)
    @UserMessage("{message}")
    Result<String> summarize(String message);
}
