package io.quarkus.prreview.classification.provenance;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.quarkus.prreview.classification.provenance.model.AiLabel;
import io.quarkus.prreview.classification.provenance.model.Person;
import io.quarkus.prreview.classification.provenance.model.Who;
import io.quarkus.prreview.clients.gh.model.GhAuthorship;
import io.quarkus.prreview.clients.gh.model.GhCommit;
import io.quarkus.prreview.config.ReviewConfig;

/**
 * A PR's AI label, from what is visible without judging the code, first match wins:
 * <ol>
 * <li>{@code agent}: the PR author or a commit author is a bot that is not routine automation;</li>
 * <li>{@code declared}: an AI-declaration marker occurs in the PR body or a commit message (a case-insensitive
 * substring, with whitespace runs collapsed on both sides);</li>
 * <li>{@code none declared}: otherwise. That means nothing was declared, not that no AI was used.</li>
 * </ol>
 * Declaring AI assistance is reported, never held against a PR. The diff and the writing style are not inspected.
 */
@ApplicationScoped
public class AiService {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final List<String> signals;
    private final List<String> automation;

    @Inject
    public AiService(ReviewConfig config) {
        this(SignalList.load(config.provenance().aiSignals(), "PROVENANCE_AI_SIGNALS"),
                SignalList.load(config.provenance().automationAccounts(), "PROVENANCE_AUTOMATION_ACCOUNTS"));
    }

    public AiService(SignalList signals, SignalList automation) {
        this.signals = signals.entries().stream().map(AiService::normalize).filter(s -> !s.isEmpty()).toList();
        this.automation = automation.entries().stream().map(WhoService::account).toList();
    }

    /**
     * @param prAuthor the PR author as labelled
     * @param commitAuthors the commit authors as labelled
     */
    public AiLabel label(GhAuthorship authorship, Person prAuthor, List<Person> commitAuthors) {
        for (Person person : Stream.concat(Stream.of(prAuthor), commitAuthors.stream()).toList()) {
            if (person.who() == Who.BOT && !automation.contains(WhoService.account(person.login()))) {
                return new AiLabel(AiLabel.Kind.AGENT, person.login());
            }
        }
        String declared = declaration(authorship.body());
        if (declared != null) {
            return new AiLabel(AiLabel.Kind.DECLARED, "PR body: \"" + declared + "\"");
        }
        for (GhCommit commit : authorship.commits()) {
            declared = declaration(commit.headline() + "\n" + commit.body());
            if (declared != null) {
                String sha = commit.oid().length() > 7 ? commit.oid().substring(0, 7) : commit.oid();
                return new AiLabel(AiLabel.Kind.DECLARED, "commit " + sha + ": \"" + declared + "\"");
            }
        }
        return AiLabel.NONE;
    }

    /** The first signal found in {@code text}, or {@code null}. */
    private String declaration(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String haystack = normalize(text);
        return signals.stream().filter(haystack::contains).findFirst().orElse(null);
    }

    /** Lower-cased, with every whitespace run as one space, so a double space or a line break still matches. */
    static String normalize(String text) {
        return WHITESPACE.matcher(text.toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
    }
}
