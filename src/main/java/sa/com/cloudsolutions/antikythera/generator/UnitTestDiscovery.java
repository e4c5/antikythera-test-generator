package sa.com.cloudsolutions.antikythera.generator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Discovers unit-test generation targets when {@code generator.yml} has no explicit {@code services}
 * list (full-project fallback mode).
 */
public final class UnitTestDiscovery {

    private static final Logger logger = LoggerFactory.getLogger(UnitTestDiscovery.class);

    private UnitTestDiscovery() {
    }

    /**
     * Returns fully qualified names of types under {@link Settings#getBasePackage()} that should
     * receive generated unit tests, using {@link TargetClassifier#classifyForFallback} on each
     * resolved type from {@link AntikytheraRunTime#getResolvedTypes()}.
     *
     * @return sorted list of target class names (may be empty)
     */
    public static List<String> discoverFallbackUnitTargets() {
        String basePkg = Settings.getBasePackage();
        if (basePkg == null || basePkg.isBlank()) {
            logger.warn("base_package is not set; fallback discovery returns no targets");
            return List.of();
        }

        Map<String, TypeWrapper> resolved = AntikytheraRunTime.getResolvedTypes();
        List<String> selected = new ArrayList<>();
        EnumMap<SkipReason, Integer> skipCounts = new EnumMap<>(SkipReason.class);
        int scanned = 0;

        for (Map.Entry<String, TypeWrapper> e : resolved.entrySet()) {
            String fqn = e.getKey();
            if (!fqn.startsWith(basePkg)) {
                continue;
            }
            scanned++;
            ClassificationResult r = TargetClassifier.classifyForFallback(e.getValue());
            if (r.isUnitTarget()) {
                selected.add(fqn);
                logger.debug("Fallback UNIT_TARGET {} — {}", fqn, r.explanation());
            } else {
                SkipReason reason = r.reason();
                skipCounts.merge(reason, 1, Integer::sum);
                logger.debug("Fallback SKIP {} — {} ({})", fqn, reason, r.explanation());
            }
        }

        selected.sort(Comparator.naturalOrder());
        logger.info(
                "Fallback discovery under base_package '{}': {} type(s) in scope, {} unit target(s), skip counts: {}",
                basePkg, scanned, selected.size(), skipCounts);
        return selected;
    }
}
