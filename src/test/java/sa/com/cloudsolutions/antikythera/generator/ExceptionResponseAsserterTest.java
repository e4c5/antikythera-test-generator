package sa.com.cloudsolutions.antikythera.generator;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.evaluator.NullArgumentGenerator;
import sa.com.cloudsolutions.antikythera.exception.EvaluatorException;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Targets {@link ExceptionResponseAsserter} outcomes (legacy assertThrows, unconditional assertThrows).
 */
class ExceptionResponseAsserterTest {

    private UnitTestGenerator utg;
    private ClassOrInterfaceDeclaration classUnderTest;

    @BeforeAll
    static void beforeClass() throws IOException {
        Settings.loadConfigMap(new File("src/test/resources/generator.yml"));
        AbstractCompiler.reset();
        AbstractCompiler.preProcess();
    }

    @BeforeEach
    void setUp() {
        CompilationUnit cu = AntikytheraRunTime.getCompilationUnit("sa.com.cloudsolutions.service.PersonService");
        classUnderTest = cu.getType(0).asClassOrInterfaceDeclaration();
        utg = new UnitTestGenerator(cu);
        utg.setArgumentGenerator(Mockito.mock(NullArgumentGenerator.class));
        utg.setPreConditions(new ArrayList<>());
        utg.setAsserter(new JunitAsserter());
    }

    @Test
    void legacyAssertThrowsWhenExceptionContextMissing() {
        MethodDeclaration md = classUnderTest.getMethodsByName("queries2").getFirst();
        utg.createTests(md, new MethodResponse());

        MethodResponse emptyContext = new MethodResponse();
        new ExceptionResponseAsserter(utg).handle(emptyContext, "personService.queries2();");

        String body = utg.testMethod.getBody().orElseThrow().toString();
        assertTrue(body.contains("assertThrows"), body);
    }

    @Test
    void assertThrowsEmittedForUnconditionalRuntimeException() {
        classUnderTest.addAnnotation("Service");
        utg.addBeforeClass();
        MethodDeclaration md = classUnderTest.getMethodsByName("queries2").getFirst();
        MethodResponse response = new MethodResponse();
        response.setException(new EvaluatorException("sym", new RuntimeException("boom")));

        utg.createTests(md, response);

        assertTrue(utg.getCompilationUnit().toString().contains("assertThrows"),
                "Unconditional non-NPE exception should yield assertThrows in generated source");
    }
}
