package sa.com.cloudsolutions.antikythera.generator;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.PrimitiveType;
import com.github.javaparser.ast.type.Type;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.evaluator.ArgumentGenerator;
import sa.com.cloudsolutions.antikythera.evaluator.Branching;
import sa.com.cloudsolutions.antikythera.evaluator.DummyArgumentGenerator;
import sa.com.cloudsolutions.antikythera.evaluator.Evaluator;
import sa.com.cloudsolutions.antikythera.evaluator.EvaluatorFactory;
import sa.com.cloudsolutions.antikythera.evaluator.NullArgumentGenerator;
import sa.com.cloudsolutions.antikythera.evaluator.Precondition;
import sa.com.cloudsolutions.antikythera.evaluator.SpringEvaluator;
import sa.com.cloudsolutions.antikythera.evaluator.TestHelper;
import sa.com.cloudsolutions.antikythera.evaluator.Variable;
import sa.com.cloudsolutions.antikythera.evaluator.mock.MockingCall;
import sa.com.cloudsolutions.antikythera.evaluator.mock.MockingRegistry;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;
import sa.com.cloudsolutions.antikythera.parser.Callable;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class UnitTestGeneratorTest {

    private UnitTestGenerator unitTestGenerator;
    private ClassOrInterfaceDeclaration classUnderTest;
    private ArgumentGenerator argumentGenerator;
    private CompilationUnit cu;

    @BeforeAll
    static void beforeClass() throws IOException {
        Settings.loadConfigMap(new File("src/test/resources/generator.yml"));
        AbstractCompiler.reset();
        AbstractCompiler.preProcess();
    }

    @BeforeEach
    void setUp() {
        cu = AntikytheraRunTime.getCompilationUnit("sa.com.cloudsolutions.service.PersonService");
        assertNotNull(cu);
        classUnderTest = cu.getType(0).asClassOrInterfaceDeclaration();

        unitTestGenerator = new UnitTestGenerator(cu);
        argumentGenerator = Mockito.mock(NullArgumentGenerator.class);
        unitTestGenerator.setArgumentGenerator(argumentGenerator);
        unitTestGenerator.setPreConditions(new ArrayList<>());
        unitTestGenerator.setAsserter(new JunitAsserter());
    }

    /**
     * THis is an integration test.
     * It covers parts of TestSuiteEvaluator, UnitTestGenerator and MockingRegistry
     * @throws NoSuchMethodException if the method is not found
     */
    @Test
    void testSetUpBase() throws NoSuchMethodException {
        unitTestGenerator.loadPredefinedBaseClassForTest("sa.com.cloudsolutions.antikythera.evaluator.mock.Hello");

        Method m = Statement.class.getDeclaredMethod("execute", String.class);
        assertNotNull(m);
        Callable callable = new Callable(m, null);
        MockingCall result = MockingRegistry.getThen("java.sql.Statement", callable);
        assertNotNull(result);
        assertInstanceOf(Boolean.class, result.getVariable().getValue());
        assertEquals(true, result.getVariable().getValue());

        m = Statement.class.getDeclaredMethod("getMaxFieldSize");
        callable = new Callable(m, null);
        assertNull(MockingRegistry.getThen("java.sql.Statement", callable));
    }

    @Test
    void testInject() {
        classUnderTest.addAnnotation("Service");
        MethodDeclaration methodUnderTest = classUnderTest.findFirst(MethodDeclaration.class,
                md -> md.getNameAsString().equals("queries2")).orElseThrow();
        unitTestGenerator.addBeforeClass();
        unitTestGenerator.createTests(methodUnderTest, new MethodResponse());
        String sources = unitTestGenerator.getCompilationUnit().toString();
        assertTrue(sources.contains("queries2Test"));
        assertTrue(sources.contains("ReflectionTestUtils"));
    }

    @Test
    void testInjectComponentStereotypeUsesReflectionWiring() {
        var saved = new ArrayList<>(classUnderTest.getAnnotations());
        try {
            classUnderTest.getAnnotations().clear();
            classUnderTest.addAnnotation("Component");
            MethodDeclaration methodUnderTest = classUnderTest.findFirst(MethodDeclaration.class,
                    md -> md.getNameAsString().equals("queries2")).orElseThrow();
            unitTestGenerator.addBeforeClass();
            unitTestGenerator.createTests(methodUnderTest, new MethodResponse());
            String sources = unitTestGenerator.getCompilationUnit().toString();
            assertTrue(sources.contains("queries2Test"));
            assertTrue(sources.contains("ReflectionTestUtils"));
            assertTrue(UnitTestGenerator.isSpringStereotypeBean(classUnderTest));
        } finally {
            classUnderTest.getAnnotations().clear();
            classUnderTest.getAnnotations().addAll(saved);
        }
    }

    @Test
    void testInjectValueFieldsInSetUp() {
        FieldDeclaration valueField = StaticJavaParser.parseBodyDeclaration(
                "@Value(\"${x}\") private Integer valueInjectedInteger;").asFieldDeclaration();
        classUnderTest.addMember(valueField);
        try {
            classUnderTest.addAnnotation("Service");
            MethodDeclaration methodUnderTest = classUnderTest.findFirst(MethodDeclaration.class,
                    md -> md.getNameAsString().equals("queries2")).orElseThrow();
            unitTestGenerator.addBeforeClass();
            unitTestGenerator.createTests(methodUnderTest, new MethodResponse());
            String sources = unitTestGenerator.getCompilationUnit().toString();
            assertTrue(sources.contains("ReflectionTestUtils.setField(personService, \"valueInjectedInteger\", 0)"),
                    "Expected @Value field to receive a non-null default via ReflectionTestUtils");
        } finally {
            valueField.remove();
        }
    }

    @Test
    void testIsSpringStereotypeBean() {
        CompilationUnit probe = StaticJavaParser.parse("class Probe {}");
        ClassOrInterfaceDeclaration p = probe.getType(0).asClassOrInterfaceDeclaration();
        assertFalse(UnitTestGenerator.isSpringStereotypeBean(p));
        p.addAnnotation("org.springframework.stereotype.Component");
        assertTrue(UnitTestGenerator.isSpringStereotypeBean(p));
        p.getAnnotations().clear();
        p.addAnnotation("org.springframework.web.bind.annotation.RestController");
        assertTrue(UnitTestGenerator.isSpringStereotypeBean(p));
    }

    @Test
    void testIdentifyFieldsToBeMocked() {

        classUnderTest.addAnnotation("Service");
        unitTestGenerator.identifyFieldsToBeMocked();
        CompilationUnit testCu = unitTestGenerator.getCompilationUnit();
        TypeDeclaration<?> testClass = testCu.getType(0);

        Optional<FieldDeclaration> mockedField = testClass.getFieldByName("personRepository");
        assertTrue(mockedField.isPresent());
        assertTrue(mockedField.get().getAnnotationByName("Mock").isPresent(), "The field 'dummyRepository' should be annotated with @Mock.");

        assertFalse(testCu.getImports().stream().anyMatch(i -> i.getNameAsString().equals("org.mockito.Mock")));

        unitTestGenerator.addDependencies();

        assertTrue(testCu.getImports().stream().anyMatch(i -> i.getNameAsString().equals("org.mockito.Mock")),
                "The import for @Mock should be present.");
        assertTrue(testCu.getImports().stream().anyMatch(i -> i.getNameAsString().equals("org.mockito.Mockito")),
                "The import for Mockito should be present.");
    }

    @Test
    void testIdentifyFieldsToBeMockedPreservesBaseClassMocks() {
        MockingRegistry.reset();
        unitTestGenerator.loadPredefinedBaseClassForTest("sa.com.cloudsolutions.antikythera.evaluator.mock.Hello");

        classUnderTest.addAnnotation("Service");
        unitTestGenerator.identifyFieldsToBeMocked();

        assertTrue(MockingRegistry.isMockTarget("java.sql.Statement"));
    }

    @Test
    void testCreateInstanceA() {
        MethodDeclaration methodUnderTest = classUnderTest.findFirst(MethodDeclaration.class,
                md -> md.getNameAsString().equals("queries2")).orElseThrow();
        unitTestGenerator.createTests(methodUnderTest, new MethodResponse());
        assertTrue(unitTestGenerator.getCompilationUnit().toString().contains("queries2Test"));
        Mockito.verify(argumentGenerator, Mockito.never()).getArguments();
    }

    @Test
    void testCreateInstanceB() {
        MethodDeclaration methodUnderTest = classUnderTest.findFirst(MethodDeclaration.class,
                md -> md.getNameAsString().equals("queries3")).orElseThrow();
        unitTestGenerator.createTests(methodUnderTest, new MethodResponse());
        assertTrue(unitTestGenerator.getCompilationUnit().toString().contains("queries3Test"));
        Mockito.verify(argumentGenerator, Mockito.times(1)).getArguments();

    }

    @Test
    void testCreateInstanceC() {
        MethodDeclaration methodUnderTest = classUnderTest.findFirst(MethodDeclaration.class,
                md -> md.getNameAsString().equals("queries2")).orElseThrow();
        ConstructorDeclaration constructor = classUnderTest.addConstructor();
        constructor.addParameter("String", "param");

        unitTestGenerator.createTests(methodUnderTest, new MethodResponse());
        assertTrue(unitTestGenerator.getCompilationUnit().toString().contains("queries2Test"));
    }

    @ParameterizedTest
    @CsvSource({"queries4, long", "queries5, int"})
    void testCreateInstanceD(String name, String type) {
        MethodDeclaration methodUnderTest = classUnderTest.findFirst(MethodDeclaration.class,
                md -> md.getNameAsString().equals(name)).orElseThrow();
        Map<String, Variable> map = new HashMap<>();
        if (type.equals("long")) {
            map.put("id", new Variable(100L));
        }
        else {
            map.put("id", new Variable(100));
        }
        Mockito.when(argumentGenerator.getArguments()).thenReturn(map);
        unitTestGenerator.createTests(methodUnderTest, new MethodResponse());
        assertTrue(unitTestGenerator.getCompilationUnit().toString().contains(name + "Test"));
    }

    @Test
    void testLogger() throws ReflectiveOperationException {
        Settings.setProperty(Settings.LOG_APPENDER,"sa.com.cloudsolutions.antikythera.testhelper.generator.LogHandler");
        MethodDeclaration md = classUnderTest.getMethodsByName("queries5").getFirst();
        argumentGenerator = new DummyArgumentGenerator();
        unitTestGenerator.setArgumentGenerator(argumentGenerator);
        unitTestGenerator.setupAsserterImports();
        unitTestGenerator.addBeforeClass();

        SpringEvaluator evaluator = EvaluatorFactory.create("sa.com.cloudsolutions.service.PersonService", SpringEvaluator.class);
        evaluator.setOnTest(true);
        evaluator.addGenerator(unitTestGenerator);
        evaluator.setArgumentGenerator(argumentGenerator);
        evaluator.visit(md);
        CompilationUnit gen  = unitTestGenerator.getCompilationUnit();
        assertNotNull(gen);
        MethodDeclaration testMethod = gen.findFirst(MethodDeclaration.class, m -> m.getNameAsString().equals("queries5Test")).orElseThrow();
        assertTrue(testMethod.toString().contains("Query5 executed"),
                "The logger should be present in the test method.");
    }

    @Test
    void testLoadExisting() throws IOException {
        // Get the actual FactoryTest.java from source directory
        File testFile = new File("src/test/java/sa/com/cloudsolutions/antikythera/generator/FactoryTest.java");
        assertTrue(testFile.exists(), testFile.getAbsolutePath() + " does not exist");

        // Execute loadExisting
        unitTestGenerator.loadExisting(testFile);
        assertNotNull(unitTestGenerator.gen);
        assertFalse(unitTestGenerator.gen.toString().contains("Author : Antikythera"));

        assertTrue(MockingRegistry.isMockTarget("java.util.zip.Adler32"));
    }

    @Test
    void testApplyPreconditionsForOptionals() throws Exception {
        // Reset MockingRegistry to ensure clean state
        MockingRegistry.reset();
        TestGenerator.clearWhenThen();

        // Test case 1: Optional.empty()
        // Create a Variable with Optional.empty()
        Variable emptyOptionalVar = new Variable(Optional.empty());

        // Create a MockingCall with the empty Optional
        Method method = String.class.getDeclaredMethod("length");
        Callable callable = new Callable(method, null);
        MockingCall emptyOptionalCall = new MockingCall(callable, emptyOptionalVar);
        emptyOptionalCall.setVariableName("mockString");

        UnitTestGenerator ug = new UnitTestGenerator(cu);
        ug.applyPreconditionsForOptionals(emptyOptionalCall);

        // Verify that the whenThen list contains an expression for Optional.empty()
        assertFalse(TestGenerator.getWhenThen().isEmpty(), "whenThen list should not be empty after processing empty Optional");
        String whenThenString = TestGenerator.getWhenThen().getFirst().toString();
        assertTrue(whenThenString.contains("Optional.empty()"),
                "The whenThen expression should contain 'Optional.empty()' but was: " + whenThenString);

        // Clear the whenThen list for the next test
        TestGenerator.clearWhenThen();

        // Test case 2: Optional with Evaluator
        // Create a mock Evaluator
        sa.com.cloudsolutions.antikythera.evaluator.Evaluator mockEvaluator = Mockito.mock(sa.com.cloudsolutions.antikythera.evaluator.Evaluator.class);
        Mockito.when(mockEvaluator.getClassName()).thenReturn("TestClass");

        // Create a Variable with Optional containing the Evaluator
        Variable evaluatorOptionalVar = new Variable(Optional.of(mockEvaluator));

        // Create a MockingCall with the Optional containing Evaluator
        Method method2 = String.class.getDeclaredMethod("isEmpty");
        Callable callable2 = new Callable(method2, null);
        MockingCall evaluatorOptionalCall = new MockingCall(callable2, evaluatorOptionalVar);
        evaluatorOptionalCall.setVariableName("mockString2");

        // Apply preconditions for the Optional with Evaluator
        ug.applyPreconditionsForOptionals(evaluatorOptionalCall);

        // Verify that mockEvaluator.getClassName() was called
        Mockito.verify(mockEvaluator).getClassName();

        // Verify that the whenThen list contains an expression for Optional.of(new TestClass())
        assertFalse(TestGenerator.getWhenThen().isEmpty(), "whenThen list should not be empty after processing Optional with Evaluator");
        whenThenString = TestGenerator.getWhenThen().getFirst().toString();
        assertTrue(whenThenString.contains("Optional.of(new TestClass())"),
                "The whenThen expression should contain 'Optional.of(new TestClass())' but was: " + whenThenString);
    }

    @Test
    void testAddBeforeClassAddsOutputCaptureFields() {
        unitTestGenerator.addBeforeClass();
        CompilationUnit testCu = unitTestGenerator.getCompilationUnit();
        String sources = testCu.toString();

        assertTrue(sources.contains("private PrintStream originalOut;"));
        assertTrue(sources.contains("private final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();"));
        assertTrue(sources.contains("System.setOut(new PrintStream(outputStream));"));
    }

    @Test
    void testAddAssertsWithCapturedOutput() {
        MethodDeclaration methodUnderTest = classUnderTest.findFirst(MethodDeclaration.class,
                md -> md.getNameAsString().equals("queries2")).orElseThrow();

        MethodResponse response = new MethodResponse();
        response.setCapturedOutput("Expected Console Output");

        // Use reflection to access private addAsserts method or just call createTests which calls it
        unitTestGenerator.createTests(methodUnderTest, response);
        String sources = unitTestGenerator.getCompilationUnit().toString();

        assertTrue(sources.contains("assertEquals(\"Expected Console Output\", outputStream.toString().trim());"));
    }

    @Test
    void testCoerceInitializerBooleanToLongPrimitive() throws Exception {
        // Use reflection to access private coerceInitializer method
        java.lang.reflect.Method coerceMethod = UnitTestGenerator.class.getDeclaredMethod(
                "coerceInitializer", Expression.class, Type.class);
        coerceMethod.setAccessible(true);

        BooleanLiteralExpr trueExpr = new BooleanLiteralExpr(true);
        BooleanLiteralExpr falseExpr = new BooleanLiteralExpr(false);
        Type longPrimitiveType = new PrimitiveType(PrimitiveType.Primitive.LONG);

        Expression resultTrue = (Expression) coerceMethod.invoke(null, trueExpr, longPrimitiveType);
        Expression resultFalse = (Expression) coerceMethod.invoke(null, falseExpr, longPrimitiveType);

        assertInstanceOf(LongLiteralExpr.class, resultTrue,
                "BooleanLiteralExpr(true) should be coerced to LongLiteralExpr when targetType is 'long'");
        assertInstanceOf(LongLiteralExpr.class, resultFalse,
                "BooleanLiteralExpr(false) should be coerced to LongLiteralExpr when targetType is 'long'");

        assertEquals("1L", ((LongLiteralExpr) resultTrue).getValue(),
                "true should be coerced to 1L");
        assertEquals("0L", ((LongLiteralExpr) resultFalse).getValue(),
                "false should be coerced to 0L");
    }

    @Test
    void testCoerceInitializerBooleanToLongWrapper() throws Exception {
        // Use reflection to access private coerceInitializer method
        java.lang.reflect.Method coerceMethod = UnitTestGenerator.class.getDeclaredMethod(
                "coerceInitializer", Expression.class, Type.class);
        coerceMethod.setAccessible(true);

        BooleanLiteralExpr trueExpr = new BooleanLiteralExpr(true);
        BooleanLiteralExpr falseExpr = new BooleanLiteralExpr(false);
        Type longWrapperType = new ClassOrInterfaceType(null, "Long");

        Expression resultTrue = (Expression) coerceMethod.invoke(null, trueExpr, longWrapperType);
        Expression resultFalse = (Expression) coerceMethod.invoke(null, falseExpr, longWrapperType);

        assertInstanceOf(LongLiteralExpr.class, resultTrue,
                "BooleanLiteralExpr(true) should be coerced to LongLiteralExpr when targetType is 'Long'");
        assertInstanceOf(LongLiteralExpr.class, resultFalse,
                "BooleanLiteralExpr(false) should be coerced to LongLiteralExpr when targetType is 'Long'");

        assertEquals("1L", ((LongLiteralExpr) resultTrue).getValue(),
                "true should be coerced to 1L for Long wrapper type");
        assertEquals("0L", ((LongLiteralExpr) resultFalse).getValue(),
                "false should be coerced to 0L for Long wrapper type");
    }

    @Test
    void testSkipWhenUsageAddsCastingImportsWithoutBaseTestClass() throws Exception {
        TestGenerator.getImports().clear();
        MethodCallExpr expr = StaticJavaParser
                .parseExpression("Mockito.when(repo.find((List<Integer>) Mockito.any())).thenReturn(List.of())")
                .asMethodCallExpr();
        Method method = UnitTestGenerator.class.getDeclaredMethod("skipWhenUsage", MethodCallExpr.class);
        method.setAccessible(true);

        boolean skipped = (boolean) method.invoke(unitTestGenerator, expr);

        assertFalse(skipped);
        assertTrue(TestGenerator.getImports().stream()
                .anyMatch(i -> i.getNameAsString().equals("java.util.List")));
    }

    @Test
    void testCreateOptionalValueExpressionKeepsObjectType() throws Exception {
        Method method = UnitTestGenerator.class.getDeclaredMethod("createOptionalValueExpression", Object.class);
        method.setAccessible(true);

        Expression expr = (Expression) method.invoke(unitTestGenerator, new Object());

        assertFalse(expr.isStringLiteralExpr());
        assertEquals("org.mockito.Mockito.mock(java.lang.Object.class)", expr.toString());
    }

    @Test
    void testCreateFieldInitializerBuildsNestedObjectForNullPojoField() throws Exception {
        Method method = UnitTestGenerator.class.getDeclaredMethod(
                "createFieldInitializer", FieldDeclaration.class, Variable.class);
        method.setAccessible(true);

        FieldDeclaration field = StaticJavaParser.parseBodyDeclaration("private Person manager;").asFieldDeclaration();
        Variable variable = new Variable((Object) null);
        variable.setType(new ClassOrInterfaceType(null, "Person"));

        Expression expr = (Expression) method.invoke(unitTestGenerator, field, variable);

        assertNotNull(expr);
        assertEquals("new sa.com.cloudsolutions.model.Person()", expr.toString());
    }

    @Test
    void testCreateFieldInitializerCoercesGeneratedStringPlaceholder() throws Exception {
        Method method = UnitTestGenerator.class.getDeclaredMethod(
                "createFieldInitializer", FieldDeclaration.class, Variable.class);
        method.setAccessible(true);

        FieldDeclaration field = StaticJavaParser.parseBodyDeclaration("private String createdBy;").asFieldDeclaration();
        Variable variable = new Variable("Antikythera");

        Expression expr = (Expression) method.invoke(unitTestGenerator, field, variable);

        assertInstanceOf(StringLiteralExpr.class, expr);
        assertEquals("\"0\"", expr.toString());
    }

    @Test
    void testCreateFieldInitializerCoercesIntegerLiteralForLongField() throws Exception {
        Method method = UnitTestGenerator.class.getDeclaredMethod(
                "createFieldInitializer", FieldDeclaration.class, Variable.class);
        method.setAccessible(true);

        FieldDeclaration field = StaticJavaParser.parseBodyDeclaration("private Long clinicGroupId;").asFieldDeclaration();
        Variable variable = new Variable(0);

        Expression expr = (Expression) method.invoke(unitTestGenerator, field, variable);

        assertInstanceOf(LongLiteralExpr.class, expr);
        assertEquals("0L", expr.toString());
    }

    @Test
    void testCreateFieldInitializerCoercesInitializerLiteralForLongField() throws Exception {
        Method method = UnitTestGenerator.class.getDeclaredMethod(
                "createFieldInitializer", FieldDeclaration.class, Variable.class);
        method.setAccessible(true);

        FieldDeclaration field = StaticJavaParser.parseBodyDeclaration("private Long clinicGroupId;").asFieldDeclaration();
        Variable variable = new Variable((Object) null);
        variable.setInitializer(List.of(StaticJavaParser.parseExpression("0")));

        Expression expr = (Expression) method.invoke(unitTestGenerator, field, variable);

        assertInstanceOf(LongLiteralExpr.class, expr);
        assertEquals("0L", expr.toString());
    }

    @Test
    void testDefaultExpressionForSimpleTypeUsesLongLiteralSuffix() throws Exception {
        Method method = UnitTestGenerator.class.getDeclaredMethod(
                "defaultExpressionForSimpleType", Type.class);
        method.setAccessible(true);

        Expression expr = (Expression) method.invoke(unitTestGenerator, new ClassOrInterfaceType(null, "Long"));

        assertInstanceOf(LongLiteralExpr.class, expr);
        assertEquals("0L", expr.toString());
    }

    @Test
    void testNormalizeSetterPreconditionCoercesGeneratedStringPlaceholder() throws Exception {
        Method method = UnitTestGenerator.class.getDeclaredMethod(
                "normalizeSetterPrecondition", MethodCallExpr.class);
        method.setAccessible(true);

        MethodCallExpr expr = StaticJavaParser.parseExpression("patientProblem.setCreatedBy(\"Antikythera\")")
                .asMethodCallExpr();

        MethodCallExpr normalized = (MethodCallExpr) method.invoke(unitTestGenerator, expr);

        assertEquals("patientProblem.setCreatedBy(\"0\")", normalized.toString());
    }

    @Test
    void testSetterNameForFieldKeepsIsPrefixForBoxedBoolean() throws Exception {
        Method method = JavaBeansConventions.class.getDeclaredMethod("setterNameForField", TypeDeclaration.class, FieldDeclaration.class);
        method.setAccessible(true);

        TypeDeclaration<?> owner = StaticJavaParser.parseBodyDeclaration("""
                class Sample {
                    private Boolean isPreviousProblem;
                }
                """).asClassOrInterfaceDeclaration();
        FieldDeclaration field = owner.getFieldByName("isPreviousProblem").orElseThrow();

        String setterName = (String) method.invoke(null, owner, field);

        assertEquals("setIsPreviousProblem", setterName);
    }

    @Test
    void testSetterNameForFieldPrefersDeclaredBooleanStyleSetter() throws Exception {
        Method method = JavaBeansConventions.class.getDeclaredMethod("setterNameForField", TypeDeclaration.class, FieldDeclaration.class);
        method.setAccessible(true);

        TypeDeclaration<?> owner = StaticJavaParser.parseBodyDeclaration("""
                class Sample {
                    private Boolean isPreviousProblem;
                    public void setPreviousProblem(Boolean previousProblem) {}
                }
                """).asClassOrInterfaceDeclaration();
        FieldDeclaration field = owner.getFieldByName("isPreviousProblem").orElseThrow();

        String setterName = (String) method.invoke(null, owner, field);

        assertEquals("setPreviousProblem", setterName);
    }

    @Test
    void testNormalizeSetterPreconditionCoercesIntegerLiteralToLongParameter() throws Exception {
        Method method = UnitTestGenerator.class.getDeclaredMethod(
                "normalizeSetterPrecondition", MethodCallExpr.class);
        method.setAccessible(true);

        unitTestGenerator.createTests(classUnderTest.getMethodsByName("queries2").getFirst(), new MethodResponse());
        unitTestGenerator.testMethod.getBody().orElseThrow()
                .addStatement("sa.com.cloudsolutions.model.Person person = new sa.com.cloudsolutions.model.Person();");

        MethodCallExpr expr = StaticJavaParser.parseExpression("person.setId(0)").asMethodCallExpr();

        MethodCallExpr normalized = (MethodCallExpr) method.invoke(unitTestGenerator, expr);

        assertEquals("person.setId(0L)", normalized.toString());
    }

    @Test
    void testNormalizeSetterPreconditionSkipsMissingSetterForScopeType() throws Exception {
        Method method = UnitTestGenerator.class.getDeclaredMethod(
                "normalizeSetterPrecondition", MethodCallExpr.class);
        method.setAccessible(true);

        unitTestGenerator.createTests(classUnderTest.getMethodsByName("queries2").getFirst(), new MethodResponse());
        unitTestGenerator.testMethod.getBody().orElseThrow()
                .addStatement("sa.com.cloudsolutions.model.Person patientProblem = new sa.com.cloudsolutions.model.Person();");

        MethodCallExpr expr = StaticJavaParser.parseExpression("patientProblem.setPreviousProblem(false)")
                .asMethodCallExpr();

        Object normalized = method.invoke(unitTestGenerator, expr);

        assertNull(normalized);
    }

    @Test
    void testAssertValueWithNoSideEffectsBoxedLong() throws Exception {
        java.lang.reflect.Method m = UnitTestGenerator.class.getDeclaredMethod(
                "toScalarLiteralExpression", sa.com.cloudsolutions.antikythera.evaluator.Variable.class, Object.class);
        m.setAccessible(true);

        sa.com.cloudsolutions.antikythera.evaluator.Variable v = new sa.com.cloudsolutions.antikythera.evaluator.Variable(42L);
        Expression lit = (Expression) m.invoke(unitTestGenerator, v, 42L);
        assertEquals("42", lit.toString());
    }

    @Test
    void testAssertValueWithNoSideEffectsBoxedCharacter() throws Exception {
        java.lang.reflect.Method m = UnitTestGenerator.class.getDeclaredMethod(
                "toScalarLiteralExpression", sa.com.cloudsolutions.antikythera.evaluator.Variable.class, Object.class);
        m.setAccessible(true);

        sa.com.cloudsolutions.antikythera.evaluator.Variable v = new sa.com.cloudsolutions.antikythera.evaluator.Variable('z');
        Expression lit = (Expression) m.invoke(unitTestGenerator, v, 'z');
        assertEquals("'z'", lit.toString());
    }

    @Test
    void testAssertValueWithNoSideEffectsBoxedBoolean() throws Exception {
        java.lang.reflect.Method m = UnitTestGenerator.class.getDeclaredMethod(
                "toScalarLiteralExpression", sa.com.cloudsolutions.antikythera.evaluator.Variable.class, Object.class);
        m.setAccessible(true);

        sa.com.cloudsolutions.antikythera.evaluator.Variable v = new sa.com.cloudsolutions.antikythera.evaluator.Variable(true);
        Expression lit = (Expression) m.invoke(unitTestGenerator, v, true);
        assertEquals("true", lit.toString());
    }

    @Test
    void testAssertValueWithNoSideEffectsString() throws Exception {
        java.lang.reflect.Method m = UnitTestGenerator.class.getDeclaredMethod(
                "toScalarLiteralExpression", sa.com.cloudsolutions.antikythera.evaluator.Variable.class, Object.class);
        m.setAccessible(true);

        sa.com.cloudsolutions.antikythera.evaluator.Variable v = new sa.com.cloudsolutions.antikythera.evaluator.Variable("hello");
        Expression lit = (Expression) m.invoke(unitTestGenerator, v, "hello");
        assertEquals("\"hello\"", lit.toString());
    }

    @Test
    void testAssertValueWithNoSideEffectsBoxedShort() throws Exception {
        java.lang.reflect.Method m = UnitTestGenerator.class.getDeclaredMethod(
                "toScalarLiteralExpression", sa.com.cloudsolutions.antikythera.evaluator.Variable.class, Object.class);
        m.setAccessible(true);

        sa.com.cloudsolutions.antikythera.evaluator.Variable v = new sa.com.cloudsolutions.antikythera.evaluator.Variable((short) 7);
        Expression lit = (Expression) m.invoke(unitTestGenerator, v, (short) 7);
        assertEquals("(short) 7", lit.toString());
    }

    @Test
    void testAssertValueWithNoSideEffectsBoxedByte() throws Exception {
        java.lang.reflect.Method m = UnitTestGenerator.class.getDeclaredMethod(
                "toScalarLiteralExpression", sa.com.cloudsolutions.antikythera.evaluator.Variable.class, Object.class);
        m.setAccessible(true);

        sa.com.cloudsolutions.antikythera.evaluator.Variable v = new sa.com.cloudsolutions.antikythera.evaluator.Variable((byte) 5);
        Expression lit = (Expression) m.invoke(unitTestGenerator, v, (byte) 5);
        assertEquals("(byte) 5", lit.toString());
    }

    @Test
    void testShouldSuppressNoSuchElementAssertThrowsWithoutOptionalEmptyUsage() throws Exception {
        MethodDeclaration createMethod = classUnderTest.getMethodsByName("queries2").getFirst();
        unitTestGenerator.createTests(createMethod, new MethodResponse());

        Method method = UnitTestGenerator.class.getDeclaredMethod(
                "shouldSuppressNoSuchElementAssertThrows", sa.com.cloudsolutions.antikythera.evaluator.ExceptionContext.class);
        method.setAccessible(true);

        sa.com.cloudsolutions.antikythera.evaluator.ExceptionContext ctx =
                new sa.com.cloudsolutions.antikythera.evaluator.ExceptionContext();
        ctx.setException(new NoSuchElementException());

        boolean suppressed = (boolean) method.invoke(unitTestGenerator, ctx);

        assertTrue(suppressed);
    }

    @Test
    void testShouldNotSuppressNoSuchElementAssertThrowsWithOptionalEmptyUsage() throws Exception {
        MethodDeclaration createMethod = classUnderTest.getMethodsByName("queries2").getFirst();
        unitTestGenerator.createTests(createMethod, new MethodResponse());
        unitTestGenerator.testMethod.getBody().orElseThrow().addStatement("java.util.Optional.empty();");

        Method method = UnitTestGenerator.class.getDeclaredMethod(
                "shouldSuppressNoSuchElementAssertThrows", sa.com.cloudsolutions.antikythera.evaluator.ExceptionContext.class);
        method.setAccessible(true);

        sa.com.cloudsolutions.antikythera.evaluator.ExceptionContext ctx =
                new sa.com.cloudsolutions.antikythera.evaluator.ExceptionContext();
        ctx.setException(new NoSuchElementException());

        boolean suppressed = (boolean) method.invoke(unitTestGenerator, ctx);

        assertFalse(suppressed);
    }

    @Test
    void testSeedCollectionArgumentsForExceptionUsesNonEmptyElement() throws Exception {
        MethodDeclaration method = StaticJavaParser.parseBodyDeclaration("""
                void createChiefComplains(java.util.List<Person> chiefComplains, String userId) {}
                """).asMethodDeclaration();
        Method build = UnitTestGenerator.class.getDeclaredMethod("buildNonEmptyCollectionInitializer", Parameter.class);
        build.setAccessible(true);

        Expression replacement = (Expression) build.invoke(unitTestGenerator, method.getParameter(0));

        assertNotNull(replacement);
        assertTrue(replacement.toString().contains("java.util.List.of("));
    }

    @Test
    void testShouldSuppressIllegalArgumentAssertThrowsForEvaluatorOnlyPath() throws Exception {
        MethodDeclaration createMethod = classUnderTest.getMethodsByName("queries2").getFirst();
        unitTestGenerator.createTests(createMethod, new MethodResponse());
        unitTestGenerator.testMethod.getBody().orElseThrow().addStatement("Long patientId = 0L;");
        unitTestGenerator.testMethod.getBody().orElseThrow().addStatement(
                "Mockito.when(personRepository.findById(Mockito.anyLong())).thenReturn(java.util.Optional.of(new sa.com.cloudsolutions.model.Person()));");

        Method extract = UnitTestGenerator.class.getDeclaredMethod("extractTestArguments");
        extract.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Expression> currentArgs = (Map<String, Expression>) extract.invoke(unitTestGenerator);

        sa.com.cloudsolutions.antikythera.evaluator.ExceptionContext ctx =
                new sa.com.cloudsolutions.antikythera.evaluator.ExceptionContext();
        ctx.setException(new IllegalArgumentException("evaluator-only"));

        Method suppress = UnitTestGenerator.class.getDeclaredMethod(
                "shouldSuppressIllegalArgumentAssertThrows",
                sa.com.cloudsolutions.antikythera.evaluator.ExceptionContext.class,
                Map.class);
        suppress.setAccessible(true);

        boolean result = (boolean) suppress.invoke(unitTestGenerator, ctx, currentArgs);

        assertTrue(result);
    }

    @Test
    void testShouldSuppressIllegalArgumentAssertThrowsWithoutMockStubsWhenInputsAreConcrete() throws Exception {
        MethodDeclaration createMethod = classUnderTest.getMethodsByName("queries2").getFirst();
        unitTestGenerator.createTests(createMethod, new MethodResponse());
        unitTestGenerator.testMethod.getBody().orElseThrow().addStatement("Long patientId = 0L;");

        Method extract = UnitTestGenerator.class.getDeclaredMethod("extractTestArguments");
        extract.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Expression> currentArgs = (Map<String, Expression>) extract.invoke(unitTestGenerator);

        sa.com.cloudsolutions.antikythera.evaluator.ExceptionContext ctx =
                new sa.com.cloudsolutions.antikythera.evaluator.ExceptionContext();
        ctx.setException(new IllegalArgumentException("evaluator-only"));

        Method suppress = UnitTestGenerator.class.getDeclaredMethod(
                "shouldSuppressIllegalArgumentAssertThrows",
                sa.com.cloudsolutions.antikythera.evaluator.ExceptionContext.class,
                Map.class);
        suppress.setAccessible(true);

        boolean result = (boolean) suppress.invoke(unitTestGenerator, ctx, currentArgs);

        assertTrue(result);
    }

    @Test
    void testSetupLoggersUsesDebugLevel() throws Exception {
        Settings.setProperty(Settings.LOG_APPENDER, "com.example.LogAppender");
        MethodDeclaration createMethod = classUnderTest.getMethodsByName("queries2").getFirst();
        unitTestGenerator.createTests(createMethod, new MethodResponse());

        Method setupLoggers = UnitTestGenerator.class.getDeclaredMethod("setupLoggers");
        setupLoggers.setAccessible(true);
        setupLoggers.invoke(unitTestGenerator);

        assertTrue(unitTestGenerator.getCompilationUnit().toString().contains("appLogger.setLevel(Level.DEBUG);"));
    }

    @Test
    void testAssertLoggedWithLevelNormalizesSlf4jTemplate() {
        Expression assertion = UnitTestGenerator.assertLoggedWithLevel(
                "com.example.Service",
                "DEBUG",
                "Episode saved  ----> {}");

        assertTrue(assertion.toString().contains("\"Episode saved  ---->\""));
        assertFalse(assertion.toString().contains("{}"));
    }
}

class UnitTestGeneratorMoreTests extends TestHelper {
    public static final String PERSON = "sa.com.cloudsolutions.antikythera.testhelper.evaluator.Person";
    public static final String CONDITIONAL = "sa.com.cloudsolutions.antikythera.testhelper.evaluator.Conditional";
    CompilationUnit cu;
    UnitTestGenerator unitTestGenerator;

    @BeforeEach
    void setup() {
        System.setOut(new PrintStream(outContent));
    }

    @AfterEach
    void after() {
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        loggerContext.getLogger(Logger.ROOT_LOGGER_NAME).setLevel(Level.DEBUG);

    }

    @BeforeAll
    static void beforeClass() throws IOException {
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        loggerContext.getLogger(Logger.ROOT_LOGGER_NAME).setLevel(Level.OFF);

        Settings.loadConfigMap(new File("src/test/resources/generator-field-tests.yml"));
        AbstractCompiler.reset();
        AbstractCompiler.preProcess();
        AntikytheraRunTime.reset();
        Branching.clear();
        MockingRegistry.reset();
        TestGenerator.clearWhenThen();
    }

    private MethodDeclaration setupMethod(String className, String name) {
        cu = AntikytheraRunTime.getCompilationUnit(className);
        unitTestGenerator = new UnitTestGenerator(cu);
        unitTestGenerator.setArgumentGenerator(new DummyArgumentGenerator());
        MethodDeclaration md = cu.findFirst(MethodDeclaration.class,
                m -> m.getNameAsString().equals(name)).orElseThrow();
        unitTestGenerator.methodUnderTest = md;
        unitTestGenerator.testMethod = unitTestGenerator.buildTestMethod(md);
        unitTestGenerator.setAsserter(new JunitAsserter());
        return md;
    }

    @Test
    void integrationTestCasting() throws ReflectiveOperationException {
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        loggerContext.getLogger(Logger.ROOT_LOGGER_NAME).setLevel(Level.OFF);


        MethodDeclaration md = setupMethod(FAKE_SERVICE,"castingHelper");
        DummyArgumentGenerator argumentGenerator = new DummyArgumentGenerator();
        unitTestGenerator.setArgumentGenerator(argumentGenerator);
        unitTestGenerator.setupAsserterImports();
        unitTestGenerator.addBeforeClass();

        SpringEvaluator evaluator = EvaluatorFactory.create(FAKE_SERVICE, SpringEvaluator.class);
        evaluator.setOnTest(true);
        evaluator.addGenerator(unitTestGenerator);
        evaluator.setArgumentGenerator(argumentGenerator);
        evaluator.visit(md);
        assertTrue(outContent.toString().contains("Found!Not found!"));
        String s = unitTestGenerator.gen.toString();
        assertTrue(s.contains("(List<Integer>)"));
        assertTrue(s.contains("(Set<Integer>)"));
        assertFalse(s.contains("Bada"));
        assertFalse(s.contains(" = Mockito.mock(Set.class);"));
    }

    @Test
    void testProblemFeignClientUsesPlainMocksWhenListedInConfig() throws Exception {
        Method method = UnitTestGenerator.class.getDeclaredMethod(
                "applyMockAnnotationForDependencyType", FieldDeclaration.class, Type.class);
        method.setAccessible(true);

        ClassOrInterfaceDeclaration testSuite = new ClassOrInterfaceDeclaration().setName("SampleTest");
        FieldDeclaration field = testSuite.addField("ProblemFeignClient", "problemFeignClient");

        method.invoke(null, field, field.getElementType());

        assertTrue(field.getAnnotationByName("Mock").isPresent());
        assertFalse(field.toString().contains("RETURNS_DEEP_STUBS"));
    }

    @Test
    void testClientTypesUseDeepStubsWhenPlainMockListUnset() throws Exception {
        Method method = UnitTestGenerator.class.getDeclaredMethod(
                "applyMockAnnotationForDependencyType", FieldDeclaration.class, Type.class);
        method.setAccessible(true);

        try {
            Settings.loadConfigMap(new File("src/test/resources/generator-no-plain-mock-clients.yml"));
            ClassOrInterfaceDeclaration testSuite = new ClassOrInterfaceDeclaration().setName("SampleTest");
            FieldDeclaration field = testSuite.addField("ProblemFeignClient", "problemFeignClient");

            method.invoke(null, field, field.getElementType());

            assertTrue(field.getAnnotationByName("Mock").isPresent());
            assertTrue(field.toString().contains("RETURNS_DEEP_STUBS"));
        } finally {
            Settings.loadConfigMap(new File("src/test/resources/generator-field-tests.yml"));
        }
    }

    @Test
    void testClientDependenciesUseDeepStubs() throws Exception {
        Method method = UnitTestGenerator.class.getDeclaredMethod(
                "applyMockAnnotationForDependencyType", FieldDeclaration.class, Type.class);
        method.setAccessible(true);

        ClassOrInterfaceDeclaration testSuite = new ClassOrInterfaceDeclaration().setName("SampleTest");
        FieldDeclaration field = testSuite.addField("ErFeignClient", "erFeignClient");

        method.invoke(null, field, field.getElementType());

        assertTrue(field.getAnnotationByName("Mock").isPresent());
        assertTrue(field.toString().contains("RETURNS_DEEP_STUBS"));
    }

    @Test
    void integrationTestFindAll() throws ReflectiveOperationException {
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        loggerContext.getLogger(Logger.ROOT_LOGGER_NAME).setLevel(Level.OFF);


        MethodDeclaration md = setupMethod(FAKE_SERVICE,"findAll");
        DummyArgumentGenerator argumentGenerator = new DummyArgumentGenerator();
        unitTestGenerator.setArgumentGenerator(argumentGenerator);
        unitTestGenerator.setupAsserterImports();
        unitTestGenerator.addBeforeClass();

        SpringEvaluator evaluator = EvaluatorFactory.create(FAKE_SERVICE, SpringEvaluator.class);
        evaluator.setOnTest(true);
        evaluator.addGenerator(unitTestGenerator);
        evaluator.setArgumentGenerator(argumentGenerator);
        evaluator.visit(md);
        assertTrue(outContent.toString().contains("1!0!"));
        String s = unitTestGenerator.gen.toString();
        assertTrue(s.contains("List.of(fakeEntity"));
        assertTrue(s.contains("new ArrayList<>()"));

    }

    @Test
    void testAutowiredCollection() throws ReflectiveOperationException {
        MethodDeclaration md = setupMethod(FAKE_SERVICE,"autoList");

        unitTestGenerator.setupAsserterImports();
        unitTestGenerator.addBeforeClass();

        Evaluator evaluator = EvaluatorFactory.create(FAKE_SERVICE, SpringEvaluator.class);
        evaluator.visit(md);
        Variable persons = evaluator.getField("persons");
        assertNotNull(persons);
        assertInstanceOf(Collection.class, persons.getValue());
        Collection<?> mockedPersons = (Collection<?>) persons.getValue();
        assertFalse(mockedPersons.isEmpty());
        assertInstanceOf(sa.com.cloudsolutions.antikythera.evaluator.MockingEvaluator.class,
                mockedPersons.iterator().next());
        assertTrue(unitTestGenerator.gen.toString().contains("@Mock()\n" +
                "    List<IPerson> persons;"));
    }

    @Test
    void testMockWithMockito1() {
        MethodDeclaration md = setupMethod(CONDITIONAL,"printMap");
        Parameter param = md.getParameter(0);
        unitTestGenerator.mockWithMockito(param, new Variable("hello"));

        assertTrue(unitTestGenerator.testMethod.toString().contains("Mockito"));
    }

    @Test
    void mockParameterFields1() {
        setupMethod(CONDITIONAL,"main");
        assertFalse(unitTestGenerator.testMethod.toString().contains("Mockito"));
        Evaluator eval = EvaluatorFactory.create(PERSON, Evaluator.class);
        unitTestGenerator.mockParameterFields(new Variable(eval),  "bada");
        assertFalse(unitTestGenerator.testMethod.toString().contains("Mockito.when(bada.getId()).thenReturn(0);"));
        assertFalse(unitTestGenerator.testMethod.toString().contains("Mockito.when(bada.getAge()).thenReturn(0);"));
    }

    @Test
    void mockParameterFields2() {
        setupMethod(CONDITIONAL,"main");
        assertFalse(unitTestGenerator.testMethod.toString().contains("Mockito"));
        Evaluator eval = EvaluatorFactory.create(PERSON, Evaluator.class);
        Variable v = mockParameterFieldsHelper(eval);
        v.setInitializer(List.of());
        unitTestGenerator.mockParameterFields(v,  "bada");
        assertFalse(unitTestGenerator.testMethod.toString().contains("Mockito.when(bada.getId()).thenReturn(0);"));
        assertFalse(unitTestGenerator.testMethod.toString().contains("Mockito.when(bada.getAge()).thenReturn(0);"));
        assertTrue(unitTestGenerator.testMethod.toString().contains("Mockito.when(bada.getName()).thenReturn(\"Shagrat\")"));
    }

    @Test
    void mockParameterFields3() {
        setupMethod(CONDITIONAL,"main");
        assertFalse(unitTestGenerator.testMethod.toString().contains("Mockito"));
        Evaluator eval = EvaluatorFactory.create(PERSON, Evaluator.class);
        Variable v = new Variable(eval);
        ObjectCreationExpr oce = new ObjectCreationExpr();
        v.setInitializer(List.of(oce));
        unitTestGenerator.mockParameterFields(v,  "bada");
        assertTrue(unitTestGenerator.testMethod.getBody().orElseThrow().isEmpty());
    }

    @Test
    void mockParameterFields4() {
        setupMethod(CONDITIONAL,"main");
        assertFalse(unitTestGenerator.testMethod.toString().contains("Mockito"));
        Evaluator eval = EvaluatorFactory.create(PERSON, Evaluator.class);
        Variable v = mockParameterFieldsHelper(eval);
        unitTestGenerator.mockParameterFields(v,  "bada");
        assertFalse(unitTestGenerator.testMethod.getBody().orElseThrow().isEmpty());
    }

    private static Variable mockParameterFieldsHelper(Evaluator eval) {
        Variable v = new Variable(eval);
        Variable shagrat = new Variable("Shagrat");
        MethodCallExpr setter = new MethodCallExpr("setName", new StringLiteralExpr("Shagrat"));
        shagrat.setInitializer(List.of(setter));
        eval.setField("name", shagrat);
        ObjectCreationExpr oce = new ObjectCreationExpr();
        v.setInitializer(List.of(oce));
        return v;
    }

    @Test
    void mockWithEvaluator() {
        MethodDeclaration md = setupMethod(CONDITIONAL,"switchCase1");
        Type t = new ClassOrInterfaceType().setName("Person");
        Parameter p = new Parameter(t, "person");
        md.getParameters().add(p);

        Variable v = new Variable("bada");
        unitTestGenerator.mockWithMockito(p, v);
        assertTrue(unitTestGenerator.testMethod.toString().contains("Person"));
    }

    @Test
    void testMockWithMockito2() {
        MethodDeclaration md = setupMethod(CONDITIONAL,"main");
        Parameter param = md.getParameter(0);
        unitTestGenerator.mockWithMockito(param, new Variable("hello"));

        assertFalse(unitTestGenerator.testMethod.toString().contains("Mockito"));
        assertTrue(unitTestGenerator.testMethod.toString().contains("String[] args = new String[] { \"Antikythera\" };"));
    }

    @Test
    void testMockWithMockito3() {
        MethodDeclaration md = setupMethod(CONDITIONAL,"main");
        Parameter param = md.getParameter(0);
        unitTestGenerator.mockWithMockito(param, new Variable("hello"));
        MethodCallExpr mce = new MethodCallExpr(new NameExpr("Bean"), "setName");
        mce.addArgument("Shagrat");
        unitTestGenerator.setPreConditions(List.of(new Precondition(mce)));

        assertFalse(unitTestGenerator.testMethod.toString().contains("Shagrat"));
        unitTestGenerator.applyPreconditions();
        assertTrue(unitTestGenerator.testMethod.toString().contains("Shagrat"));

    }

    /**
     * When a method parameter's type matches a non-@Mock protected field in the base test
     * class, mockArgument should emit {@code Type paramName = this.fieldName;} rather than
     * constructing a new instance.
     */
    @Test
    void testMockArgumentUsesBaseClassField() {
        MethodDeclaration md = setupMethod(CONDITIONAL, "conditional1");
        unitTestGenerator.mockArguments();

        String body = unitTestGenerator.testMethod.getBody().orElseThrow().toString();
        assertTrue(body.contains("Person person = this.person"),
                "Expected 'Person person = this.person' but was:\n" + body);
    }

    /**
     * The base class should be added to the class under test.
     */
    @Test
    void testAddingBaseClassToTestClass() {
        CompilationUnit base = AntikytheraRunTime.getCompilationUnit("sa.com.cloudsolutions.antikythera.testhelper.generator.DummyBase");
        assertNull(base);
        CompilationUnit compilationUnit = AntikytheraRunTime.getCompilationUnit("sa.com.cloudsolutions.antikythera.testhelper.evaluator.Overlord");
        assertNotNull(compilationUnit);

        ClassOrInterfaceDeclaration classUnderTest = compilationUnit.getType(0).asClassOrInterfaceDeclaration();
        UnitTestGenerator utg = new UnitTestGenerator(compilationUnit);

        assertTrue(classUnderTest.getExtendedTypes().isEmpty());
        CompilationUnit testCu = utg.getCompilationUnit();
        assertNotNull(testCu);
        TypeDeclaration<?> publicType = AbstractCompiler.getPublicType(testCu);
        assertNotNull(publicType);
        assertEquals("OverlordAKTest", publicType.getNameAsString());
        assertTrue(publicType.asClassOrInterfaceDeclaration().getExtendedTypes()
                .stream()
                .anyMatch(t -> t.asString().equals("sa.com.cloudsolutions.antikythera.testhelper.generator.DummyBase")));
    }
}


class VariableInitializationModifierTest {

    @Test
    void shouldModifySimpleVariableInitialization() {
        String code = """
            public void testMethod() {
                String test = "old";
                int other = 5;
            }
            """;
        MethodDeclaration method = StaticJavaParser.parseMethodDeclaration(code);
        StringLiteralExpr newValue = new StringLiteralExpr("new");
        UnitTestGenerator.replaceInitializer(method, "test", newValue);

        assertTrue(method.toString().contains("String test = \"new\""));
        assertTrue(method.toString().contains("int other = 5"));
    }

    @Test
    void shouldModifyConstructors() {
        String code = """
            public void testMethod() {
                int target = 1;
                String other = "middle";
                Person p = new Person("Hornblower");
            }
            """;

        MethodDeclaration method = StaticJavaParser.parseMethodDeclaration(code);
        Expression newValue = StaticJavaParser.parseExpression("Person.createPerson(\"Horatio\")");

        UnitTestGenerator.replaceInitializer(method, "p", newValue);

        String modifiedCode = method.toString();
        assertTrue(modifiedCode.contains("Person p = Person.createPerson(\"Horatio\")"));
    }
}
