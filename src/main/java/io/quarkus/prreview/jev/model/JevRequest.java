package io.quarkus.prreview.jev.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A {@code jev} evaluation request (plan §2):
 * {@code POST https://api.typesafe.ai/v1/systemone}.
 *
 * <p>{@code state} is a named JSON object (text only) — one per pipeline so each fact is addressable,
 * with all PR-authored text nested under an explicitly-delimited {@code untrusted} key (invariant I-4).
 * {@code questions} is the externalized, versioned question block loaded verbatim from a
 * {@code questions/*.json} resource and embedded as-is, so the request shape exactly matches the API
 * contract without re-modeling per-type criteria.
 */
public record JevRequest(Object state, String model, JsonNode questions) {
}
