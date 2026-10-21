package io.quarkus.prreview.facts.services;


import io.quarkus.prreview.facts.model.AuthorHistory;
import io.quarkus.prreview.facts.model.AuthorshipProvenance;
import io.quarkus.prreview.facts.model.ChangeClassification;
import io.quarkus.prreview.facts.model.ChangedFile;
import io.quarkus.prreview.facts.model.CommitterStatus;
import io.quarkus.prreview.facts.model.Facts;
import io.quarkus.prreview.facts.model.ParsedFile;
import io.quarkus.prreview.facts.model.PullRequest;
import io.quarkus.prreview.facts.model.TestFacts;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Assembles the full {@link Facts} for one PR from the deterministic facts layer (design §3.1). This is
 * the single ground-truth source every pipeline draws its slice from; trust facts are pinned here and
 * only ever consumed by the interpretation layer, never placed in a {@code jev} state (invariants
 * I-2, I-3).
 */
@ApplicationScoped
public class FactsService {

    @Inject FetchPr fetchPr;
    @Inject DiffParser diffParser;
    @Inject ClassifyFiles classifyFiles;
    @Inject ClassifyChanges classifyChanges;
    @Inject MapTests mapTests;
    @Inject CommitterStatusService committerStatusService;
    @Inject AuthorHistoryService authorHistoryService;
    @Inject AuthorshipProvenanceService authorshipProvenanceService;

    @ConfigProperty(name = "backport.branch-regex", defaultValue = "^(?!main$|master$|develop$|\\d+\\.x$).*(\\d+\\.\\d+.*|\\.x)$")
    String backportRegex;

    public Facts gather(int prNumber) {
        PullRequest pr = fetchPr.fetch(prNumber);

        List<ParsedFile> parsed = diffParser.parse(pr.rawDiff());
        List<ChangedFile> files = classifyFiles.classify(parsed);
        ChangeClassification changes = classifyChanges.classify(files);
        List<TestFacts> tests = mapTests.map(files);

        AuthorHistory history = authorHistoryService.resolve(pr.authorLogin());
        CommitterStatus committer = committerStatusService.resolve(pr.authorLogin(), pr.baseSha(), history);

        boolean backport = isBackport(pr.baseBranch());
        AuthorshipProvenance provenance = authorshipProvenanceService.resolve(pr, files);

        return new Facts(pr, files, changes, tests, committer, history, backport, provenance);
    }

    boolean isBackport(String baseBranch) {
        if (baseBranch == null || baseBranch.isBlank()) {
            return false;
        }
        String b = baseBranch.toLowerCase(Locale.ROOT);
        if (b.equals("main") || b.equals("master") || b.equals("develop")) {
            return false;
        }
        try {
            return Pattern.compile(backportRegex).matcher(baseBranch).find()
                    || baseBranch.endsWith(".x")
                    || baseBranch.matches(".*\\d+\\.\\d+.*");
        } catch (RuntimeException e) {
            return baseBranch.endsWith(".x") || baseBranch.matches(".*\\d+\\.\\d+.*");
        }
    }
}
