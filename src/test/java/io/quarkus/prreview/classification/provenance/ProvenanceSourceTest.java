package io.quarkus.prreview.classification.provenance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.quarkus.prreview.classification.provenance.model.AiLabel;
import io.quarkus.prreview.classification.provenance.model.Person;
import io.quarkus.prreview.classification.provenance.model.Provenance;
import io.quarkus.prreview.classification.provenance.model.Who;
import io.quarkus.prreview.classification.provenance.who.WhoRules;
import io.quarkus.prreview.classification.source.model.SourceResult;
import io.quarkus.prreview.clients.gh.GhClient;
import io.quarkus.prreview.clients.gh.GhException;
import io.quarkus.prreview.clients.gh.model.GhAuthorship;

class ProvenanceSourceTest {

    final GhClient gh = mock(GhClient.class);
    final ProvenanceSource source = new ProvenanceSource(gh, new WhoRules(gh));

    @Test
    void labelsThePrAuthorAndStampsTheReadTime() {
        when(gh.authorship(7)).thenReturn(new GhAuthorship("alice", "Alice", false, "", "abc", List.of()));
        when(gh.association(7)).thenReturn("MEMBER");
        Instant before = Instant.now();

        SourceResult<Provenance> result = source.gather(7);

        Provenance provenance = ((SourceResult.Available<Provenance>) result).facts();
        assertEquals(Person.of("alice", Who.TEAM), provenance.author());
        assertEquals(List.of(), provenance.commitAuthors());
        assertEquals(AiLabel.NONE, provenance.ai());
        assertTrue(!provenance.readAt().isBefore(before), provenance.readAt().toString());
    }

    @Test
    void anAuthorshipThatCannotBeReadMakesTheSourceUnavailable() {
        when(gh.authorship(7)).thenThrow(new GhException("no pull requests\nfound"));

        assertEquals(SourceResult.unavailable("gh: no pull requests found"), source.gather(7));
    }

    @Test
    void aFailedAssociationLabelsOnlyTheAuthor() {
        when(gh.authorship(7)).thenReturn(new GhAuthorship("alice", "Alice", false, "", "abc", List.of()));
        when(gh.association(7)).thenThrow(new GhException("timed out"));

        Provenance provenance = ((SourceResult.Available<Provenance>) source.gather(7)).facts();
        assertEquals(Person.other("alice", "association lookup failed: timed out"), provenance.author());
    }
}
