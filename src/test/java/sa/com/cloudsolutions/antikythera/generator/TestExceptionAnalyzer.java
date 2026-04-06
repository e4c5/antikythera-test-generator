package sa.com.cloudsolutions.antikythera.generator;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.StaticJavaParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sa.com.cloudsolutions.antikythera.evaluator.ExceptionContext;
import sa.com.cloudsolutions.antikythera.evaluator.LoopContext;
import sa.com.cloudsolutions.antikythera.evaluator.Variable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ExceptionAnalyzer.
 */
public class TestExceptionAnalyzer {
    
    private ExceptionAnalyzer analyzer;
    
    @BeforeEach
    void setUp() {
        analyzer = new ExceptionAnalyzer();
    }
    
    @Test
    void testUnconditionalException() {
        // Exception with no context - should be UNCONDITIONAL
        ExceptionContext ctx = new ExceptionContext();
        ctx.setException(new UnsupportedOperationException("Not implemented"));
        
        ExceptionType type = analyzer.analyzeException(ctx, null);
        assertEquals(ExceptionType.UNCONDITIONAL, type, 
            "Exception with no path conditions should be UNCONDITIONAL");
    }
    
    @Test
    void testConditionalOnLoop() {
        // Exception inside loop with empty collection
        ExceptionContext ctx = new ExceptionContext();
        ctx.setException(new IllegalArgumentException("Invalid item"));
        ctx.setInsideLoop(true);
        
        LoopContext loopCtx = new LoopContext();
        loopCtx.setEmptyCollection(true);
        loopCtx.setIteratorVariable("item");
        ctx.setLoopContext(loopCtx);
        
        ExceptionType type = analyzer.analyzeException(ctx, null);
        assertEquals(ExceptionType.CONDITIONAL_ON_LOOP, type,
            "Exception in loop with empty collection should be CONDITIONAL_ON_LOOP");
    }
    
    @Test
    void testConditionalOnData() {
        // Exception with validation pattern
        ExceptionContext ctx = new ExceptionContext();
        ctx.setException(new IllegalArgumentException("Validation failed"));
        
        // Create an if statement that looks like validation
        String code = "if (error != null) { throw new IllegalArgumentException(); }";
        IfStmt ifStmt = StaticJavaParser.parseStatement(code).asIfStmt();
        ctx.setThrowLocation(ifStmt.getThenStmt());
        
        ExceptionType type = analyzer.analyzeException(ctx, null);
        assertEquals(ExceptionType.CONDITIONAL_ON_DATA, type,
            "Exception in validation pattern should be CONDITIONAL_ON_DATA");
    }
    
    @Test
    void testEmptyCollectionDetection() {
        // Test empty ArrayList detection
        Expression emptyList = StaticJavaParser.parseExpression("new ArrayList<>()");
        Map<String, Expression> args = new HashMap<>();
        args.put("items", emptyList);
        
        ExceptionContext ctx = new ExceptionContext();
        ctx.setException(new RuntimeException());
        ctx.setInsideLoop(true);
        
        LoopContext loopCtx = new LoopContext();
        loopCtx.setEmptyCollection(false);
        loopCtx.setCollectionVariable(new Variable(new ArrayList<>()));
        ctx.setLoopContext(loopCtx);
        
        boolean willTrigger = analyzer.willArgumentsTriggerException(ctx, args);
        assertFalse(willTrigger, 
            "Empty ArrayList argument should not trigger loop-based exception");
    }
    
    @Test
    void testNonEmptyCollectionTriggers() {
        // Test that non-empty collection would trigger
        Expression nonEmptyList = StaticJavaParser.parseExpression("List.of(item)");
        Map<String, Expression> args = new HashMap<>();
        args.put("items", nonEmptyList);
        
        ExceptionContext ctx = new ExceptionContext();
        ctx.setException(new RuntimeException());
        ctx.setInsideLoop(true);
        
        LoopContext loopCtx = new LoopContext();
        loopCtx.setCollectionVariable(new Variable(new ArrayList<>()));
        ctx.setLoopContext(loopCtx);
        
        boolean willTrigger = analyzer.willArgumentsTriggerException(ctx, args);
        assertTrue(willTrigger,
            "Non-empty collection argument should trigger loop-based exception");
    }
    
    @Test
    void testUnconditionalAlwaysTriggers() {
        ExceptionContext ctx = new ExceptionContext();
        ctx.setException(new UnsupportedOperationException());
        
        Map<String, Expression> args = new HashMap<>();
        
        boolean willTrigger = analyzer.willArgumentsTriggerException(ctx, args);
        assertTrue(willTrigger,
            "Unconditional exceptions should always trigger");
    }
    
    @Test
    void testNullContextHandling() {
        ExceptionType type = analyzer.analyzeException(null, null);
        assertEquals(ExceptionType.UNCONDITIONAL, type,
            "Null context should be treated as UNCONDITIONAL");
        
        boolean willTrigger = analyzer.willArgumentsTriggerException(null, new HashMap<>());
        assertFalse(willTrigger,
            "Null context should not trigger");
    }
    
    @Test
    void testListOfEmptyDetection() {
        Expression emptyList = StaticJavaParser.parseExpression("List.of()");
        Map<String, Expression> args = new HashMap<>();
        args.put("items", emptyList);
        
        ExceptionContext ctx = new ExceptionContext();
        ctx.setException(new RuntimeException());
        ctx.setInsideLoop(true);
        
        LoopContext loopCtx = new LoopContext();
        loopCtx.setCollectionVariable(new Variable(new ArrayList<>()));
        ctx.setLoopContext(loopCtx);
        
        boolean willTrigger = analyzer.willArgumentsTriggerException(ctx, args);
        assertFalse(willTrigger,
            "List.of() should be detected as empty collection");
    }
    
    @Test
    void testCollectionsEmptyListDetection() {
        Expression emptyList = StaticJavaParser.parseExpression("Collections.emptyList()");
        Map<String, Expression> args = new HashMap<>();
        args.put("items", emptyList);
        
        ExceptionContext ctx = new ExceptionContext();
        ctx.setException(new RuntimeException());
        ctx.setInsideLoop(true);
        
        LoopContext loopCtx = new LoopContext();
        loopCtx.setCollectionVariable(new Variable(new ArrayList<>()));
        ctx.setLoopContext(loopCtx);
        
        boolean willTrigger = analyzer.willArgumentsTriggerException(ctx, args);
        assertFalse(willTrigger,
            "Collections.emptyList() should be detected as empty collection");
    }
}
