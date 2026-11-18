package io.quarkus.prreview.report;

import java.time.temporal.ChronoUnit;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import io.quarkus.prreview.classification.provenance.model.AiLabel;
import io.quarkus.prreview.classification.provenance.model.Person;
import io.quarkus.prreview.classification.provenance.model.Provenance;

/** Wording shared by the terminal block and the markdown report for who wrote a PR. */
public final class ProvenanceFormat {

    public static final String UNAVAILABLE = "unavailable";

    private ProvenanceFormat() {
    }

    /**
     * {@code Author: <login> (<who>) · AI: <label>[ (<evidence>)][ · Commits: <login> (<who>), ...]}. Every value from
     * GitHub or a list goes through {@code escape}, which keeps it on one line and safe for the output.
     */
    public static String line(Provenance provenance, UnaryOperator<String> escape) {
        StringBuilder line = new StringBuilder("Author: ").append(person(provenance.author(), escape))
                .append(" · AI: ").append(ai(provenance.ai(), escape));
        if (!provenance.commitAuthors().isEmpty()) {
            line.append(" · Commits: ").append(provenance.commitAuthors().stream().map(p -> person(p, escape))
                    .collect(Collectors.joining(", ")));
        }
        return line.toString();
    }

    /** When GitHub was read, to the second, in UTC. */
    public static String readAt(Provenance provenance) {
        return provenance.readAt().truncatedTo(ChronoUnit.SECONDS).toString();
    }

    private static String person(Person person, UnaryOperator<String> escape) {
        String who = person.who().label() + (person.reason() == null ? "" : ": " + escape.apply(person.reason()));
        return escape.apply(person.login()) + " (" + who + ")";
    }

    private static String ai(AiLabel ai, UnaryOperator<String> escape) {
        return ai.kind().label() + (ai.evidence() == null ? "" : " (" + escape.apply(ai.evidence()) + ")");
    }
}
