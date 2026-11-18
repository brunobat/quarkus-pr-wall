package io.quarkus.prreview.classification.provenance.who;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkus.prreview.classification.provenance.model.Person;

/**
 * The commit authors already labelled in this run, so a person who appears in several PRs is looked up once, whatever
 * each PR's base commit. Cleared when a run starts.
 * <p>
 * {@code team} does not depend on the base, and neither does {@code community} in practice: a commit that was in the
 * repository before one PR's base is still there for every later base. {@code first-time} is the exception: the
 * person's first PR may be merged between two bases, so it is kept per base and checked again for another one. A
 * failed lookup is not kept, so the next PR in which that person appears tries GitHub again.
 */
@ApplicationScoped
public class LoginMemo {

    private final Map<String, Person> people = new ConcurrentHashMap<>();
    private final Map<String, Person> firstTimers = new ConcurrentHashMap<>();

    public void clear() {
        people.clear();
        firstTimers.clear();
    }

    public Optional<Person> get(String login, String baseSha) {
        Person known = people.get(key(login));
        return known != null ? Optional.of(known) : Optional.ofNullable(firstTimers.get(key(login) + "@" + baseSha));
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
