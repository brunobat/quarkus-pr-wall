package io.quarkus.prreview.clients.claude;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;

/**
 * The only access to Claude: a short prose restatement of {@code jev}'s classification. It has no tools and no
 * memory, and it sees only the answers and the changed-file list, never the diff, title or body.
 */
@RegisterAiService(tools = {}, chatMemoryProviderSupplier = RegisterAiService.NoChatMemoryProviderSupplier.class)
public interface ClassificationSummarizer {

    @SystemMessage("""
            You restate a classification of a GitHub pull request made by another model, jev.
            Write at most 3 sentences of plain prose.
            Only describe what jev's answers say. Give no verdict, recommendation or severity.
            Never contradict, change, round differently or reinterpret the given probabilities.
            """)
    @UserMessage("""
            jev's answers:
            {answers}

            Changed files:
            {changedFiles}
            """)
    String summarize(String answers, String changedFiles);
}
