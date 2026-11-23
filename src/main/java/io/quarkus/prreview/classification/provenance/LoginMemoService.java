package io.quarkus.prreview.classification.provenance;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkus.prreview.classification.provenance.model.Person;

/**
 * The commit authors already labelled in this run, so a person who appears in several PRs is looked up once,
 * whatever each PR's base commit. Cleared when a run starts.
 * <p>
 * {@code team}, {@code community} and {@code bot}, once found, hold for that login for the rest of the run. Labels
 * therefore follow the order the PRs are given: a person found {@code community} is {@code community} in every later
 * PR, even one whose base is older. {@code first-time} holds only on the same base, since the person's first PR may be
 * merged between two bases; on another base only their commit history is read again. GitHub's answer that a person is
 * not a member is kept as soon as it comes, so membership is asked at most once per login once answered. A failed
 * lookup is not kept: the next PR in which that person appears tries again only the part that failed.
 */
@ApplicationScoped
public class LoginMemoService {

    private final Map<String, Person> people = new ConcurrentHashMap<>();
    private final Map<String, Person> firstTimers = new ConcurrentHashMap<>();
    private final Set<String> nonMembers = ConcurrentHashMap.newKeySet();

    public void clear() {
        people.clear();
        firstTimers.clear();
        nonMembers.clear();
    }

    public Optional<Person> get(String login, String baseSha) {
        Person known = people.get(key(login));
        return known != null ? Optional.of(known) : Optional.ofNullable(firstTimers.get(key(login) + "@" + baseSha));
    }

    /** Whether this run already found {@code login} not to be a member of the org. */
    public boolean knownNonMember(String login) {
        return nonMembers.contains(key(login));
    }

    /** Keeps GitHub's answer that {@code login} is not a member, whatever the rest of their lookup gives. */
    public void markNonMember(String login) {
        nonMembers.add(key(login));
    }

    public void put(String login, String baseSha, Person person) {
        switch (person.who()) {
            case TEAM, COMMUNITY, BOT -> people.put(key(login), person);
            case FIRST_TIME -> firstTimers.put(key(login) + "@" + baseSha, person);
            case OTHER -> {
                // a failed lookup or a placeholder: not an answer to reuse
            }
        }
    }

    private static String key(String login) {
        return login.toLowerCase(Locale.ROOT);
    }
}
