package io.quarkus.prreview.classification.provenance.model;

/** Who a person is to the repository, as GitHub reports it. Reported, never judged. */
public enum Who {

    /** A member of the repository's GitHub org. */
    TEAM("team"),
    /** Not a member, with work already in the repository. */
    COMMUNITY("community"),
    /** Not a member, with nothing in the repository yet. */
    FIRST_TIME("first-time"),
    /** A bot or GitHub App account. */
    BOT("bot"),
    /** None of the above could be established; the person's reason says why. */
    OTHER("other");

    private final String label;

    Who(String label) {
        this.label = label;
    }

    /** The label shown in the report and the terminal. */
    public String label() {
        return label;
    }
}
