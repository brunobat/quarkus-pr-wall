package io.quarkus.prreview.questions.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A loaded, versioned concern module (plan §5.6): the {@code jev} questions plus the declarative rules
 * that read their answers. {@link #questions()} is the raw {@code questions} object embedded verbatim
 * into a {@link io.quarkus.prreview.jev.model.JevRequest}; {@link #rules()} is the raw {@code rules} array,
 * left unparsed here and turned into records by
 * {@link io.quarkus.prreview.interpret.rules.services.RulesetLoader} — this package stays ignorant of the
 * rule model, as it already is of the question model.
 *
 * <p>The two versions are separate on purpose. {@link #version()} is <em>wire-visible</em>: it versions the
 * questions, which go to {@code jev}, so only a prompt change bumps it. {@link #rulesVersion()} versions the
 * local interpretation of those answers. Both are recorded in report provenance (plan §11), so a verdict is
 * bisectable to either a re-worded question or a re-tuned rule — merging the files must not merge the two.
 *
 * <p>{@code rules} and {@code rulesVersion} are null for a set with no rules: only triage has them, because
 * only triage runs the {@link io.quarkus.prreview.interpret.rules.services.RuleEngine}.
 */
public record QuestionSet(String name, String version, JsonNode questions, String rulesVersion, JsonNode rules) {
}
