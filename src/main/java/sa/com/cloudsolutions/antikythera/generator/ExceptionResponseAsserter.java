package sa.com.cloudsolutions.antikythera.generator;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import sa.com.cloudsolutions.antikythera.exception.EvaluatorException;
import sa.com.cloudsolutions.antikythera.evaluator.ExceptionContext;

import java.util.Map;

/**
 * Decides how to emit tests when symbolic evaluation ended in an exception ({@code assertThrows}
 * vs ordinary success-path assertions).
 *
 * <p><b>Side effects (call order matters):</b>
 * <ol>
 *   <li>{@link UnitTestGenerator#seedCollectionArgumentsForException} mutates the generated
 *       {@code @Test} method AST by replacing empty collection variable initializers so exception
 *       paths remain reachable.</li>
 *   <li>This class invokes it, then immediately re-reads bindings via
 *       {@link UnitTestGenerator#extractTestArguments()}; that re-extraction must stay after
 *       seeding.</li>
 *   <li>When IAE suppression combines with NPE reinstatement (no stubs / null-only stubs),
 *       {@link MethodResponse#setException(EvaluatorException)} may be called to align the
 *       exception type expected by {@link JunitAsserter}.</li>
 * </ol>
 */
final class ExceptionResponseAsserter {

    private final UnitTestGenerator utg;

    ExceptionResponseAsserter(UnitTestGenerator utg) {
        this.utg = utg;
    }

    void handle(MethodResponse response, String invocation) {
        ExceptionContext ctx = response.getExceptionContext();

        UnitTestGenerator.logger.info("handleExceptionResponse: ctx={}, exception={}, insideLoop={}, loopContext={}",
                ctx != null, ctx != null ? ctx.getException() : null,
                ctx != null ? ctx.isInsideLoop() : "N/A",
                ctx != null && ctx.getLoopContext() != null ? "YES" : "NO");

        if (ctx == null || ctx.getException() == null) {
            UnitTestGenerator.logger.warn("No exception context available, using legacy assertThrows behavior");
            String[] parts = invocation.split("=");
            utg.assertThrows(parts.length == 2 ? parts[1] : parts[0], response);
            return;
        }

        ExceptionType type = utg.exceptionAnalyzer.analyzeException(ctx,
                utg.methodUnderTest instanceof MethodDeclaration ? (MethodDeclaration) utg.methodUnderTest : null);

        UnitTestGenerator.logger.info("Exception type analyzed as: {} (insideLoop={}, hasLoopContext={})",
                type, ctx.isInsideLoop(), ctx.getLoopContext() != null);

        Map<String, Expression> currentArgs = utg.extractTestArguments();

        utg.seedCollectionArgumentsForException(ctx, currentArgs);
        currentArgs = utg.extractTestArguments();

        UnitTestGenerator.logger.debug("Extracted {} test arguments", currentArgs.size());

        boolean willTrigger = utg.exceptionAnalyzer.willArgumentsTriggerException(ctx, currentArgs);

        boolean illegalArgumentSuppressionApplied = false;
        if (utg.shouldSuppressIllegalArgumentAssertThrows(ctx, currentArgs)) {
            UnitTestGenerator.logger.info(
                    "Suppressing assertThrows(IllegalArgumentException) for {} — generated setup does not recreate the evaluator-only exception path",
                    utg.methodUnderTest.getNameAsString());
            willTrigger = false;
            illegalArgumentSuppressionApplied = true;
        }

        if (willTrigger && utg.shouldSuppressNoSuchElementAssertThrows(ctx)) {
            UnitTestGenerator.logger.info(
                    "Suppressing assertThrows(NoSuchElementException) for {} — no explicit Optional.empty() trigger exists in generated setup",
                    utg.methodUnderTest.getNameAsString());
            willTrigger = false;
        }

        UnitTestGenerator.logger.info("Exception analysis for {}: type={}, willTrigger={}",
                utg.methodUnderTest.getNameAsString(), type, willTrigger);

        boolean reinstatedNpe = false;
        if (!willTrigger) {
            if (!utg.hasWhenStubs()) {
                UnitTestGenerator.logger.info("Reinstating assertThrows(NPE) for {} — test has no stubs; plain @Mock returns null at runtime",
                        utg.methodUnderTest.getNameAsString());
                willTrigger = true;
                reinstatedNpe = true;
            } else if (utg.hasNullThenReturnStubs()) {
                UnitTestGenerator.logger.info("Reinstating assertThrows(NPE) for {} — test has explicit thenReturn(null) stub; null is real",
                        utg.methodUnderTest.getNameAsString());
                willTrigger = true;
                reinstatedNpe = true;
            }
        }

        if (!willTrigger) {
            UnitTestGenerator.logger.info("Skipping assertThrows for {} - arguments won't trigger {} exception",
                    utg.methodUnderTest.getNameAsString(), type);
            utg.addAsserts(response, invocation);
        } else {
            UnitTestGenerator.logger.debug("Generating assertThrows for {} - exception will be triggered",
                    utg.methodUnderTest.getNameAsString());
            if (reinstatedNpe && illegalArgumentSuppressionApplied) {
                response.setException(new EvaluatorException("Reinstated assertThrows for runtime NPE", new NullPointerException()));
            }
            String[] parts = invocation.split("=");
            utg.assertThrows(parts.length == 2 ? parts[1] : parts[0], response);
        }
    }
}
