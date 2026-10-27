package io.quarkus.prreview.review.model;

/**
 * Who or what opened a PR, as far as the attested facts go: the four-way split a reviewer actually
 * wants when scanning a sweep — bot, agent, human-with-declared-AI-help, human.
 *
 * <p>This is a <em>derivation</em>, not a detection. Every input is something GitHub or the author
 * stated outright: the account's {@code __typename}, the {@code benign-bots.txt} allowlist, and an
 * AI-assistance marker the author chose to leave. Nothing here looks at the diff, and no amount of
 * code style moves a PR between buckets — that would be the stylometry the design rules out (I-7, and
 * authorship-provenance design §9).
 *
 * <p>The consequence worth stating plainly, because the column invites the opposite reading:
 * <strong>{@link #HUMAN} is a residual, not a finding.</strong> It means "a human account, and no
 * declaration" — which is exactly what an undeclared AI-assisted PR looks like, since almost all of
 * them are undeclared. Read it as "nothing was declared", never as "no AI was involved".
 *
 * <p>Reporting only. No rule may read this, and none can: it is assembled in the review layer,
 * downstream of every gate, and the ruleset addresses facts by name (I-7 bars
 * {@code declares_ai_assistance} outright).
 */
public enum AuthorKind {

    /** A GitHub-attested bot account on the routine-automation allowlist — dependabot and friends. */
    BOT("bot"),

    /**
     * A GitHub-attested bot account that is <em>not</em> routine automation. In practice that is a
     * coding agent driving its own account (copilot-swe-agent and the like), which is why it is split
     * from {@link #BOT}: the two want different reviewer attention. Strictly it means "automation this
     * agent does not recognise", so a new dependency bot lands here until it is allowlisted.
     */
    AGENT("agent"),

    /** A human account that left an AI-assistance marker. The only positive AI signal that exists. */
    HUMAN_WITH_AI("human+ai"),

    /** A human account with no declaration. The residual — see the class note above. */
    HUMAN("human");

    private final String label;

    AuthorKind(String label) {
        this.label = label;
    }

    /** The short form used in report tables. */
    public String label() {
        return label;
    }

    /**
     * Account type decides first, declaration second. A bot that also declares assistance stays a bot:
     * the account is the stronger and less gameable signal, and "bot" already tells the reviewer what
     * the declaration would have.
     */
    public static AuthorKind of(boolean authorIsBot, boolean authorIsAllowlistedBot,
            boolean declaresAiAssistance) {
        if (authorIsAllowlistedBot) {
            return BOT;
        }
        if (authorIsBot) {
            return AGENT;
        }
        return declaresAiAssistance ? HUMAN_WITH_AI : HUMAN;
    }
}
