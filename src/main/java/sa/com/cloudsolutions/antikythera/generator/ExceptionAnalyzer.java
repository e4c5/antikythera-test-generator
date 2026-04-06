package sa.com.cloudsolutions.antikythera.generator;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sa.com.cloudsolutions.antikythera.evaluator.ExceptionContext;
import sa.com.cloudsolutions.antikythera.evaluator.LoopContext;
import sa.com.cloudsolutions.antikythera.evaluator.Variable;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Analyzes exception contexts to determine exception types and validate test arguments.
 * This enables intelligent test generation by understanding when exceptions will actually occur.
 */
public class ExceptionAnalyzer {
    private static final Logger logger = LoggerFactory.getLogger(ExceptionAnalyzer.class);

    /**
     * Analyze an exception context to determine what type of exception it is.
     * 
     * @param ctx The exception context captured during evaluation
     * @param md The method declaration being tested (optional, for additional analysis)
     * @return The classification of the exception type
     */
    public ExceptionType analyzeException(ExceptionContext ctx, MethodDeclaration md) {
        if (ctx == null || ctx.getException() == null) {
            return ExceptionType.UNCONDITIONAL;
        }

        // Check if exception occurred inside a loop
        if (ctx.isInsideLoop()) {
            LoopContext loopCtx = ctx.getLoopContext();
            if (loopCtx != null) {
                // Exception occurred during loop - always CONDITIONAL_ON_LOOP
                // because it depends on collection having elements to iterate
                logger.debug("Exception classified as CONDITIONAL_ON_LOOP - occurred during loop iteration");
                return ExceptionType.CONDITIONAL_ON_LOOP;
            }
        }

        // Check if exception is inside validation pattern
        if (isValidationPattern(ctx)) {
            logger.debug("Exception classified as CONDITIONAL_ON_DATA - validation pattern detected");
            return ExceptionType.CONDITIONAL_ON_DATA;
        }

        // Check path conditions - if no branching conditions, likely unconditional
        if (ctx.getPathConditions().isEmpty()) {
            logger.debug("Exception classified as UNCONDITIONAL - no path conditions");
            return ExceptionType.UNCONDITIONAL;
        }

        // Default to state-dependent
        logger.debug("Exception classified as CONDITIONAL_ON_STATE - has path conditions");
        return ExceptionType.CONDITIONAL_ON_STATE;
    }

    /**
     * Check if the exception throw location matches a validation pattern.
     * Common patterns:
     * - if (error != null) throw exception
     * - if (isEmpty(x)) throw exception
     * - if (x == null) throw exception
     * 
     * @param ctx The exception context
     * @return true if exception is inside validation-like code
     */
    private boolean isValidationPattern(ExceptionContext ctx) {
        Statement throwStmt = ctx.getThrowLocation();
        if (throwStmt == null) {
            return false;
        }

        // Look for ancestor IfStmt
        Optional<IfStmt> ifStmt = throwStmt.findAncestor(IfStmt.class);
        return ifStmt.map(this::looksLikeValidationCheck).orElse(false);
    }

    /**
     * Heuristic check if an if-statement looks like validation.
     * This checks for common validation patterns in the condition.
     * 
     * @param ifStmt The if statement to check
     * @return true if it looks like validation code
     */
    private boolean looksLikeValidationCheck(IfStmt ifStmt) {
        String condition = ifStmt.getCondition().toString().toLowerCase();
        
        // Common validation patterns
        return condition.contains("== null") 
            || condition.contains("!= null")
            || condition.contains("isempty")
            || condition.contains("isblank")
            || condition.contains("error")
            || condition.contains("invalid")
            || condition.contains("missing")
            || condition.contains(".equals(");
    }

    /**
     * Check if the generated test arguments will actually trigger the exception.
     * 
     * @param ctx The exception context from evaluation
     * @param testArgs Map of parameter names to their expression in the test
     * @return true if the arguments will trigger the exception, false otherwise
     */
    public boolean willArgumentsTriggerException(ExceptionContext ctx, Map<String, Expression> testArgs) {
        if (ctx == null) {
            return false;
        }

        // NullPointerException is often an evaluator artefact caused by Reflect.getDefault()
        // returning null for object types during symbolic tracing.  At runtime the Mockito mocks
        // return non-null values, so the NPE never materialises.  Only assert assertThrows(NPE)
        // when the generated test explicitly passes a null literal as an argument.
        if (isNullPointerException(ctx) && !testArgsContainNullLiteral(testArgs)) {
            logger.debug("Suppressing assertThrows(NPE) — no explicit null literal in test arguments (likely evaluator artefact)");
            return false;
        }

        ExceptionType type = analyzeException(ctx, null);

        // Unconditional exceptions always trigger
        if (type == ExceptionType.UNCONDITIONAL) {
            return true;
        }

        // Check loop-conditional exceptions
        if (type == ExceptionType.CONDITIONAL_ON_LOOP) {
            return checkLoopArgumentsValid(ctx, testArgs);
        }

        // For data and state conditionals, we're conservative - assume they will trigger
        // unless we can definitively prove they won't
        return true;
    }

    /**
     * Check if loop arguments will trigger the exception.
     * Returns false if the collection is empty (won't trigger loop-based exceptions).
     * 
     * @param ctx Exception context with loop information
     * @param testArgs Test arguments
     * @return true if arguments will trigger exception
     */
    private boolean checkLoopArgumentsValid(ExceptionContext ctx, Map<String, Expression> testArgs) {
        LoopContext loopCtx = ctx.getLoopContext();
        if (loopCtx == null) {
            return true; // Conservative: assume it will trigger
        }

        Variable collectionVar = loopCtx.getCollectionVariable();
        if (collectionVar == null) {
            return true;
        }

        // Try to find the collection parameter in test arguments
        String collectionParamName = findCollectionParameterName(collectionVar, testArgs);
        if (collectionParamName == null) {
            return true; // Can't determine - assume it will trigger
        }

        Expression arg = testArgs.get(collectionParamName);
        if (arg == null) {
            return true;
        }

        // Check if the argument creates an empty collection
        if (CollectionExpressionAnalyzer.isDefinitelyEmptyCollection(arg)) {
            logger.info("Test argument '{}' creates empty collection - exception won't trigger", collectionParamName);
            return false;
        }

        return true;
    }

    /**
     * Try to find the parameter name for a collection variable.
     * This is a best-effort heuristic.
     */
    private String findCollectionParameterName(Variable collectionVar, Map<String, Expression> testArgs) {
        // Simple heuristic: if collection value is a List/Set/Collection type parameter
        // Try to match by type
        for (Map.Entry<String, Expression> entry : testArgs.entrySet()) {
            String paramName = entry.getKey();
            String paramLower = paramName.toLowerCase();
            // Check if parameter name suggests it's a collection
            if (paramLower.contains("list") 
                || paramLower.contains("collection")
                || paramLower.contains("item")  // Changed from "items" to match both "item" and "items"
                || paramLower.contains("set")
                || paramLower.contains("array")
                || paramLower.endsWith("s")) {  // Plural names often indicate collections
                return paramName;
            }
        }
        
        // If only one parameter and it's collection-like, use it
        if (testArgs.size() == 1) {
            return testArgs.keySet().iterator().next();
        }
        
        return null;
    }

    /**
     * Suggest fixes to test arguments to make them trigger the exception.
     * This is used when we detect that current arguments won't trigger the exception.
     * 
     * @param ctx Exception context
     * @param currentArgs Current test arguments
     * @return Modified arguments that should trigger the exception, or null if can't fix
     */
    public Map<String, Expression> fixArgumentsForException(
            ExceptionContext ctx, 
            Map<String, Expression> currentArgs) {
        
        if (ctx == null || !ctx.isInsideLoop()) {
            return null; // Can only fix loop-related issues for now
        }

        LoopContext loopCtx = ctx.getLoopContext();
        if (loopCtx == null) {
            return null;
        }

        Variable collectionVar = loopCtx.getCollectionVariable();
        String collectionParamName = findCollectionParameterName(collectionVar, currentArgs);
        
        if (collectionParamName == null) {
            logger.warn("Cannot fix arguments - unable to identify collection parameter");
            return null;
        }

        // For now, we just identify the problem - actual fixing will be done
        // in Phase 3 when we integrate with test generation
        logger.info("Collection parameter '{}' needs non-empty invalid data to trigger exception",
                   collectionParamName);

        return null; // Phase 3 will implement actual fixing
    }

    /**
     * Returns true if the exception stored in ctx (after unwrapping wrapper types) is
     * a NullPointerException.
     */
    private boolean isNullPointerException(ExceptionContext ctx) {
        Exception ex = ctx.getException();
        if (ex == null) {
            return false;
        }
        Throwable t = ex;
        while (t != null) {
            if (t instanceof NullPointerException) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    /**
     * Returns true if any of the test-method argument expressions is a null literal
     * (e.g. {@code Long id = null;}).  Such explicit nulls indicate that the developer
     * intentionally passes null; the NPE is therefore genuine, not an evaluator artefact.
     */
    private boolean testArgsContainNullLiteral(Map<String, Expression> testArgs) {
        for (Expression expr : testArgs.values()) {
            if (expr instanceof NullLiteralExpr) {
                return true;
            }
        }
        return false;
    }
}
