package io.quarkus.prreview.classification.provenance.model;

/**
 * Whether the PR shows AI involvement that is visible without judging the code: a coding-agent account, or a
 * declaration in the PR or its commits. {@link Kind#NONE} means nothing was declared, not that no AI was used.
 *
 * @param kind the label
 * @param evidence what produced it, one line (the agent's login, or where the declaration is and what it says);
 *        {@code null} for {@link Kind#NONE}
 */
public record AiLabel(Kind kind, String evidence) {

    public static final AiLabel NONE = new AiLabel(Kind.NONE, null);

    public enum Kind {

        AGENT("agent"),
        DECLARED("declared"),
        NONE("none declared");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        /** The label shown in the report and the terminal. */
        public String label() {
            return label;
        }
    }
}
