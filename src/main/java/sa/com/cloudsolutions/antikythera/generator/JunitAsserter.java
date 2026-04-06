package sa.com.cloudsolutions.antikythera.generator;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;

import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

public class JunitAsserter extends Asserter {
    @Override
    public Expression assertNotNull(String variable) {
        MethodCallExpr aNotNull = new MethodCallExpr("assertNotNull");
        aNotNull.addArgument(variable);
        return aNotNull;
    }

    @Override
    public Expression assertNull(String variable) {
        MethodCallExpr aNotNull = new MethodCallExpr("assertNull");
        aNotNull.addArgument(variable);
        return aNotNull;
    }

    @Override
    public void setupImports(CompilationUnit gen) {
        gen.addImport("org.junit.jupiter.api.Test");
        gen.addImport("org.junit.jupiter.api.Assertions", true, true);
    }

    @Override
    public Expression assertEquals(String lhs, String rhs) {
        MethodCallExpr assertEquals = new MethodCallExpr( "assertEquals");
        assertEquals.addArgument(lhs);
        assertEquals.addArgument(rhs);
        return assertEquals;
    }

    @Override
    public Expression assertThrows(String invocation, MethodResponse response) {
        MethodCallExpr assertThrows = new MethodCallExpr("assertThrows");
        Throwable ex = response.getException();
        String exceptionClass;
        if (ex == null) {
            exceptionClass = RuntimeException.class.getName();
        } else if (ex.getCause() != null) {
            Throwable cause = ex.getCause();
            while (isWrapperException(cause) && cause.getCause() != null) {
                cause = cause.getCause();
            }
            exceptionClass = isWrapperException(cause) ? Exception.class.getName() : cause.getClass().getName();
        } else {
            Throwable actual = ex;
            while (isWrapperException(actual) && actual.getCause() != null) {
                actual = actual.getCause();
            }
            exceptionClass = isWrapperException(actual) ? Exception.class.getName() : actual.getClass().getName();
        }
        /*
         * NumericComparator used to throw IllegalArgumentException("Cannot compare X and null") while the
         * JVM throws NullPointerException from Comparable.compareTo(null) or from null Date.toInstant().
         * The cause may also be a bare IllegalArgumentException (no message) under EvaluatorException.
         * Generated Mockito tests often hit NPE at runtime; align the expected type.
         */
        Throwable deepest = deepestNonWrapperCause(ex);
        if (deepest instanceof IllegalArgumentException iae) {
            String msg = iae.getMessage();
            boolean numericComparatorNullOperand = msg != null && msg.contains("Cannot compare") && msg.contains("null");
            boolean bareFromEvaluator = (msg == null || msg.isEmpty()) && ex instanceof sa.com.cloudsolutions.antikythera.exception.EvaluatorException;
            if (numericComparatorNullOperand || bareFromEvaluator) {
                exceptionClass = NullPointerException.class.getName();
            }
        }
        /*
         * Gson failures are sensitive to mock depth; symbolic evaluation may predict JsonIOException
         * while a well-stubbed test runs cleanly. Prefer assertDoesNotThrow for Gson-related types.
         */
        if (exceptionClass.contains("JsonIOException") || exceptionClass.contains("gson.JsonSyntaxException")) {
            return assertDoesNotThrow(invocation.replace(';', ' '));
        }
        assertThrows.addArgument(exceptionClass + ".class");
        assertThrows.addArgument(String.format("() -> %s", invocation.replace(';', ' ')));
        return assertThrows;
    }

    private static boolean isWrapperException(Throwable throwable) {
        return throwable instanceof InvocationTargetException
                || throwable instanceof CompletionException
                || throwable instanceof ExecutionException
                || throwable instanceof sa.com.cloudsolutions.antikythera.exception.EvaluatorException
                || throwable instanceof sa.com.cloudsolutions.antikythera.exception.AUTException;
    }

    private static Throwable deepestNonWrapperCause(Throwable ex) {
        if (ex == null) {
            return null;
        }
        Throwable t = ex;
        while (isWrapperException(t) && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }


    @Override
    public Expression assertDoesNotThrow(String invocation) {
        MethodCallExpr assertDoesNotThrow = new MethodCallExpr("assertDoesNotThrow");
        assertDoesNotThrow.addArgument(String.format("() -> %s", invocation.replace(';', ' ')));
        return assertDoesNotThrow;
    }

    @Override
    public Expression assertOutput(String expected) {
        MethodCallExpr assertEquals = new MethodCallExpr("assertEquals");
        assertEquals.addArgument("\"" + expected.replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\"");
        assertEquals.addArgument("outputStream.toString().trim()");
        return assertEquals;
    }
}
