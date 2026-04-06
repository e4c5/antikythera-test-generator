package sa.com.cloudsolutions.antikythera.generator;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.expr.Expression;
import org.junit.jupiter.api.Test;
import sa.com.cloudsolutions.antikythera.evaluator.ExceptionContext;
import sa.com.cloudsolutions.antikythera.evaluator.LoopContext;
import sa.com.cloudsolutions.antikythera.evaluator.Variable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Debug test for ExceptionAnalyzer to understand failures.
 */
public class TestExceptionAnalyzerDebug {
    
    @Test
    void debugEmptyCollectionDetection() {
        ExceptionAnalyzer analyzer = new ExceptionAnalyzer();
        
        // Create context
        ExceptionContext ctx = new ExceptionContext();
        ctx.setException(new RuntimeException());
        ctx.setInsideLoop(true);
        
        LoopContext loopCtx = new LoopContext();
        loopCtx.setEmptyCollection(false);
        loopCtx.setCollectionVariable(new Variable(new ArrayList<>()));
        ctx.setLoopContext(loopCtx);
        
        // Create arguments
        Expression emptyList = StaticJavaParser.parseExpression("new ArrayList<>()");
        Map<String, Expression> args = new HashMap<>();
        args.put("items", emptyList);
        
        // Debug: check exception type
        ExceptionType type = analyzer.analyzeException(ctx, null);
        System.out.println("Exception type: " + type);
        assertEquals(ExceptionType.CONDITIONAL_ON_LOOP, type, "Should be CONDITIONAL_ON_LOOP");
        
        // Debug: check if arguments will trigger
        boolean willTrigger = analyzer.willArgumentsTriggerException(ctx, args);
        System.out.println("Will trigger: " + willTrigger);
        System.out.println("Argument 'items' expression: " + emptyList);
        
        // This should be false because emptyList won't iterate
        assertFalse(willTrigger, 
            "Empty ArrayList argument should not trigger loop-based exception");
    }
}
