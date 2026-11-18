package io.quarkus.prreview.classification.provenance.who;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import io.quarkus.prreview.classification.provenance.model.Person;
import io.quarkus.prreview.classification.provenance.model.Who;
import io.quarkus.prreview.clients.gh.GhClient;
import io.quarkus.prreview.clients.gh.GhException;
import io.quarkus.prreview.clients.gh.model.GhAuthorship;

class WhoRulesTest {

    static final String BASE = "3cde8c894156969db35001fe9f26051c3c81fd22";

    final GhClient gh = mock(GhClient.class);
    final WhoRules rules = new WhoRules(gh);

    static GhAuthorship authorship(String login, boolean bot) {
        return new GhAuthorship(login, "Name", bot, "", BASE, List.of());
    }

    @ParameterizedTest
    @CsvSource({
            "MEMBER, TEAM,",
            "OWNER, TEAM,",
            "FIRST_TIME_CONTRIBUTOR, FIRST_TIME,",
            "FIRST_TIMER, FIRST_TIME,",
            "CONTRIBUTOR, COMMUNITY,",
            "NONE, OTHER, no association",
            "MANNEQUIN, OTHER, placeholder account",
            "SOMETHING_NEW, OTHER, unknown association SOMETHING_NEW",
    })
    void thePrAuthorIsLabelledFromTheAssociation(String association, Who who, String reason) {
        when(gh.association(7)).thenReturn(association);

        assertEquals(new Person("alice", who, reason), rules.prAuthor(7, authorship("alice", false)));
    }

    @Test
    void aDeletedAuthorIsOtherWithoutALookup() {
        assertEquals(Person.other("ghost", "deleted account"), rules.prAuthor(7, authorship(null, false)));
        assertEquals(Person.other("ghost", "deleted account"), rules.prAuthor(7, authorship("ghost", false)));
        verify(gh, never()).association(anyInt());
    }

    @Test
    void aBotAuthorIsBotWithoutALookup() {
        assertEquals(Person.of("app/copilot-swe-agent", Who.BOT),
                rules.prAuthor(7, authorship("app/copilot-swe-agent", true)));
        verify(gh, never()).association(anyInt());
    }

    @Test
    void aFailedLookupIsOtherWithTheReason() {
        when(gh.association(7)).thenThrow(new GhException("gh: Server Error\n(HTTP 502)"));

        assertEquals(Person.other("alice", "association lookup failed: gh: Server Error (HTTP 502)"),
                rules.prAuthor(7, authorship("alice", false)));
    }
}
