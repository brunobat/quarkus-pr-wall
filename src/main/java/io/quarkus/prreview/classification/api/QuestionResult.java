package io.quarkus.prreview.classification.api;

/**
 * One question and {@code jev}'s answer to it, unchanged.
 *
 * @param probability {@code jev}'s probability of "yes", as returned
 */
public record QuestionResult(String checkId, String questionId, String label, String text, double probability) {
}
