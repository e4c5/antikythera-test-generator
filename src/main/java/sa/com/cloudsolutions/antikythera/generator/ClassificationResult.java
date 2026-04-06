package sa.com.cloudsolutions.antikythera.generator;

import java.util.Objects;

/**
 * Result of classifying a discovered type for fallback unit-test generation.
 *
 * @param decision classification outcome
 * @param reason skip reason, or {@code null} when the type is selected as a unit target
 * @param explanation human-readable explanation for logs/debugging
 */
public record ClassificationResult(ClassificationDecision decision, SkipReason reason, String explanation) {

    public ClassificationResult {
        Objects.requireNonNull(decision, "decision");
        if (decision == ClassificationDecision.UNIT_TARGET && reason != null) {
            throw new IllegalArgumentException("UNIT_TARGET must not carry a skip reason");
        }
        if (decision == ClassificationDecision.SKIP && reason == null) {
            throw new IllegalArgumentException("SKIP must carry a skip reason");
        }
    }

    public static ClassificationResult unitTarget(String explanation) {
        return new ClassificationResult(ClassificationDecision.UNIT_TARGET, null, explanation);
    }

    public static ClassificationResult skip(SkipReason reason, String explanation) {
        return new ClassificationResult(ClassificationDecision.SKIP, reason, explanation);
    }

    public boolean isUnitTarget() {
        return decision == ClassificationDecision.UNIT_TARGET;
    }

    public boolean isSkip() {
        return decision == ClassificationDecision.SKIP;
    }
}
