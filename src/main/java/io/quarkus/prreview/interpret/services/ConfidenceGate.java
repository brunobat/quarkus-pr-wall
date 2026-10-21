package io.quarkus.prreview.interpret.services;



import io.quarkus.prreview.interpret.model.Band;
import io.quarkus.prreview.interpret.model.BandedAnswer;
import io.quarkus.prreview.interpret.model.Thresholds;
import io.quarkus.prreview.jev.model.Answer;
import io.quarkus.prreview.jev.model.JevResponse;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The one confidence gate every pipeline shares (plan §6). It bands a {@code jev} answer and is
 * deliberately <em>asymmetric</em>: clearing a concern to silence requires a CONFIRM-band "no issue";
 * any sub-CONFIRM answer on a risk question lands at ≥ FLAG (invariant I-5). This is a pure function of
 * {@code (answer, thresholds)} — no network, fully unit-testable.
 */
@ApplicationScoped
public class ConfidenceGate {

    /** Band every answer in a response. */
    public Map<String, BandedAnswer> band(JevResponse response, Thresholds t) {
        Map<String, BandedAnswer> out = new LinkedHashMap<>();
        if (response == null) {
            return out;
        }
        response.answers().forEach((id, a) -> out.put(id, band(id, a, t)));
        return out;
    }

    public BandedAnswer band(String id, Answer a, Thresholds t) {
        if (a == null) {
            return new BandedAnswer(id, Band.HUMAN, false, null, Double.NaN, null);
        }
        if (a.isNoul()) {
            return bandNoul(id, a, t);
        }
        double conf = a.derivedConfidence();
        Band band = conf >= t.confirmBand() ? Band.CONFIRM
                : conf >= t.flagBand() ? Band.FLAG
                : Band.HUMAN;
        double score = a.score() == null ? Double.NaN : a.score();
        return new BandedAnswer(id, band, false, a.choice(), score, a);
    }

    private BandedAnswer bandNoul(String id, Answer a, Thresholds t) {
        double p = a.noulValue();
        boolean yes = p >= 0.5;
        Band band;
        if (p >= t.noulConfirmYes() || p <= t.noulConfirmNo()) {
            band = Band.CONFIRM;
        } else if ((p >= t.noulFlagYesLow() && p < t.noulConfirmYes())
                || (p > t.noulConfirmNo() && p <= t.noulFlagNoHigh())) {
            band = Band.FLAG;
        } else {
            band = Band.HUMAN;
        }
        return new BandedAnswer(id, band, yes, null, Double.NaN, a);
    }
}
