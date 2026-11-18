package io.quarkus.prreview.classification.provenance.model;

/**
 * One person who wrote part of a PR, with who they are to the repository.
 *
 * @param login the GitHub login; {@code ghost} for a deleted account, as GitHub shows it
 * @param who who the person is
 * @param reason why {@code who} is {@link Who#OTHER}, one line; {@code null} otherwise
 */
public record Person(String login, Who who, String reason) {

    public static Person of(String login, Who who) {
        return new Person(login, who, null);
    }

    public static Person other(String login, String reason) {
        return new Person(login, Who.OTHER, reason);
    }
}
