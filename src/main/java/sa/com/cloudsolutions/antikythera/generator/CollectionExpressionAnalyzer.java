package sa.com.cloudsolutions.antikythera.generator;

import com.github.javaparser.ast.expr.Expression;

/**
 * Heuristics for whether an expression denotes an empty collection, shared by exception
 * seeding in {@link UnitTestGenerator} and loop analysis in {@link ExceptionAnalyzer}.
 */
public final class CollectionExpressionAnalyzer {

    private CollectionExpressionAnalyzer() {
    }

    /**
     * @return true when the expression is reliably an empty list/set (including Guava helpers
     *         and {@code new ArrayList<>()} with no arguments).
     */
    public static boolean isDefinitelyEmptyCollection(Expression expr) {
        String text = expr.toString().replace(" ", "");
        if (text.equals("newArrayList<>()") || text.equals("newArrayList()")
                || text.equals("newHashSet<>()") || text.equals("newHashSet()")
                || text.equals("List.of()") || text.equals("Set.of()")
                || text.equals("Collections.emptyList()") || text.equals("Collections.emptySet()")) {
            return true;
        }
        return expr.isObjectCreationExpr() && expr.asObjectCreationExpr().getArguments().isEmpty()
                && TypeInspector.isLikelyConcreteEmptyCollectionType(expr.asObjectCreationExpr().getType());
    }
}
