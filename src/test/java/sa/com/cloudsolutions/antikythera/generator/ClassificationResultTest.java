package sa.com.cloudsolutions.antikythera.generator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClassificationResultTest {

    @Test
    void unitTargetFactory_createsValidTargetResult() {
        ClassificationResult result = ClassificationResult.unitTarget("logic-bearing component");

        assertEquals(ClassificationDecision.UNIT_TARGET, result.decision());
        assertEquals("logic-bearing component", result.explanation());
        assertTrue(result.isUnitTarget());
        assertFalse(result.isSkip());
    }

    @Test
    void skipFactory_createsValidSkipResult() {
        ClassificationResult result = ClassificationResult.skip(SkipReason.INTERFACE, "repository interface");

        assertEquals(ClassificationDecision.SKIP, result.decision());
        assertEquals(SkipReason.INTERFACE, result.reason());
        assertTrue(result.isSkip());
        assertFalse(result.isUnitTarget());
    }

    @Test
    void constructor_rejectsUnitTargetWithReason() {
        assertThrows(IllegalArgumentException.class,
                () -> new ClassificationResult(ClassificationDecision.UNIT_TARGET,
                        SkipReason.INTERFACE, "invalid"));
    }

    @Test
    void constructor_rejectsSkipWithoutReason() {
        assertThrows(IllegalArgumentException.class,
                () -> new ClassificationResult(ClassificationDecision.SKIP, null, "invalid"));
    }
}
