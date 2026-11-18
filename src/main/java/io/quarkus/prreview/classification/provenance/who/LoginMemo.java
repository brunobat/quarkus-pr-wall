package io.quarkus.prreview.classification.provenance.who;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkus.prreview.classification.provenance.model.Person;

/**
 * The commit authors already labelled in this run, so a person who appears in several PRs is looked up once. Keyed by
 * login and the PR's base commit, since "has a commit before the base" depends on the base. Cleared when a run starts.
 */
@ApplicationScoped
public class LoginMemo {

    private final Map<String, Person> people = new ConcurrentHashMap<>();

    public void clear() {
        people.clear();
    }

    public Optional<Person> get(String login, String baseSha) {
        return Optional.ofNullable(people.get(key(login, baseSha)));
    }

    public void put(String login, String baseSha, Person person) {
        people.put(key(login, baseSha), person);
    }

    private static String key(String login, String baseSha) {
        return login.toLowerCase(Locale.ROOT) + "@" + baseSha;
    }
}
