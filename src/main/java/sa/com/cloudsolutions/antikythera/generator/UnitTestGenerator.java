package sa.com.cloudsolutions.antikythera.generator;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.Name;
import com.github.javaparser.ast.expr.SimpleName;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.type.ArrayType;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.PrimitiveType;
import com.github.javaparser.ast.type.ReferenceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.depsolver.DepSolver;
import sa.com.cloudsolutions.antikythera.depsolver.Graph;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.evaluator.ArgumentGenerator;
import sa.com.cloudsolutions.antikythera.evaluator.Evaluator;
import sa.com.cloudsolutions.antikythera.evaluator.ExceptionContext;
import sa.com.cloudsolutions.antikythera.evaluator.Precondition;
import sa.com.cloudsolutions.antikythera.evaluator.Reflect;
import sa.com.cloudsolutions.antikythera.evaluator.TestSuiteEvaluator;
import sa.com.cloudsolutions.antikythera.evaluator.Variable;
import sa.com.cloudsolutions.antikythera.evaluator.logging.LogRecorder;
import sa.com.cloudsolutions.antikythera.evaluator.mock.MockingCall;
import sa.com.cloudsolutions.antikythera.evaluator.mock.MockingRegistry;
import sa.com.cloudsolutions.antikythera.exception.AntikytheraException;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;
import sa.com.cloudsolutions.antikythera.parser.Callable;
import sa.com.cloudsolutions.antikythera.parser.ImportWrapper;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * <p>Unit test generator.</p>
 *
 * <p>The responsibility of deciding what should be mocked and what should not be mocked lies here.
 * Each class that is marked as autowired will be considered a candidate for mocking. These will
 * be registered in the mocking registry.</p>
 *
 * <p>Then the evaluators will in turn add more mocking statements to the mocking registry. When the
 * tests are being generated, this class will check the registry to find out what additional mocks
 * need to be added.</p>
 */
public class UnitTestGenerator extends TestGenerator {
    static final Logger logger = LoggerFactory.getLogger(UnitTestGenerator.class);
    public static final String TEST_NAME_SUFFIX = "AKTest";
    public static final String MOCK_BEAN = TestGenerationConstants.MOCK_BEAN_SIMPLE_NAME;
    public static final String MOCK = TestGenerationConstants.MOCK_SIMPLE_NAME;
    public static final String AUTHOR_ANTIKYTHERA = TestGenerationConstants.GENERATED_COMMENT_AUTHOR_SPACED;
    private final String filePath;

    private boolean autoWired;
    private String instanceName;
    private CompilationUnit baseTestClass;
    private ClassOrInterfaceDeclaration testClass;
    /**
     * Keeps track of variables that have been mocked.
     * in the case of a variable created with mockito the value will be true. For non mockito
     * variables, it will be false.
     */
    private final Map<String, Boolean> variables = new HashMap<>();

    /**
     * Maps simple type name → field name for non-mock protected/public fields declared in the
     * base test class (e.g. "Tenant" → "tenant", "MetaData" → "metaData").
     * Populated when the base class is loaded; used by {@link #tryUseBaseClassField} to emit
     * {@code Type paramName = this.fieldName;} instead of constructing a new instance.
     */
    private final Map<String, String> baseClassFields = new HashMap<>();

    /**
     * Analyzer for determining if test arguments will trigger exceptions.
     * Used to avoid generating assertThrows when exceptions won't actually occur.
     */
    final ExceptionAnalyzer exceptionAnalyzer = new ExceptionAnalyzer();

    private final MockFieldSupport mockFieldSupport;

    public UnitTestGenerator(CompilationUnit cu) {
        this(cu, GeneratorSeams.defaults());
    }

    /**
     * @param generatorSeams injectable type-resolution and plain-mock naming (defaults use {@link AntikytheraRunTime} / {@link Settings}).
     */
    public UnitTestGenerator(CompilationUnit cu, GeneratorSeams generatorSeams) {
        super(cu);
        this.mockFieldSupport = new MockFieldSupport(this, Objects.requireNonNull(generatorSeams));
        String packageDecl = cu.getPackageDeclaration().map(PackageDeclaration::getNameAsString).orElse("");
        String basePath = Settings.getProperty(Settings.BASE_PATH, String.class).orElseThrow();
        String className = AbstractCompiler.getPublicType(cu).getNameAsString() + TEST_NAME_SUFFIX;

        // Navigate from .../src/main/java → .../src/test/java using the Path API
        // instead of fragile string replacement.
        Path testRoot = Paths.get(basePath).getParent().getParent().resolve(TestGenerationConstants.MAVEN_TEST_JAVA_UNDER_SRC);
        filePath = testRoot.resolve(packageDecl.replace(".", File.separator))
                .resolve(className + ".java")
                .toString();

        File file = new File(filePath);

        try {
            loadExisting(file);
        } catch (FileNotFoundException e) {
            logger.debug("Could not find file: {}", filePath);
            createTestClass(className, packageDecl);
        }
    }

    /**
     * Attempt to identify which fields have already been mocked.
     *
     * @param t the type declaration which holds the fields being mocked.
     */
    private static void identifyExistingMocks(TypeDeclaration<?> t) {
        for (FieldDeclaration fd : t.getFields()) {
            if (fd.getAnnotationByName(MOCK_BEAN).isPresent() ||
                    fd.getAnnotationByName(MOCK).isPresent()) {
                List<TypeWrapper> wrappers = AbstractCompiler.findTypesInVariable(fd.getVariable(0));
                if(!wrappers.isEmpty() && wrappers.getLast() != null){
                    MockingRegistry.markAsMocked(MockingRegistry.generateRegistryKey(wrappers));
                }
            }
        }

    }

    /**
     * Collects non-@Mock protected/public fields from a base test class type declaration
     * into {@link #baseClassFields} (simple type name → field name).
     * Called once per type when the base class is loaded.
     */
    private void extractBaseClassFields(TypeDeclaration<?> t) {
        for (FieldDeclaration fd : t.getFields()) {
            if (fd.getAnnotationByName(MOCK).isPresent() || fd.getAnnotationByName(MOCK_BEAN).isPresent()) {
                continue;
            }
            if (fd.hasModifier(Modifier.Keyword.PROTECTED) || fd.hasModifier(Modifier.Keyword.PUBLIC)) {
                String simpleType = fd.getElementType().asString();
                String fieldName = fd.getVariable(0).getNameAsString();
                baseClassFields.put(simpleType, fieldName);
            }
        }
    }

    /**
     * If the base test class declares a non-mock protected/public field whose simple type
     * matches {@code param}'s type, emits {@code Type paramName = this.fieldName;} and returns
     * {@code true}. The caller should then skip normal mock generation for this parameter.
     */
    private boolean tryUseBaseClassField(Parameter param) {
        if (baseClassFields.isEmpty()) {
            return false;
        }
        String simpleType = param.getType().isClassOrInterfaceType()
                ? param.getType().asClassOrInterfaceType().getNameAsString()
                : param.getType().asString();
        String fieldName = baseClassFields.get(simpleType);
        if (fieldName == null) {
            return false;
        }
        // Add import for the parameter type before using it
        addClassImports(param.getType());
        getBody(testMethod).addStatement(
                String.format("%s %s = this.%s;", simpleType, param.getNameAsString(), fieldName));
        return true;
    }

    /**
     * Loads any existing test class that has been generated previously.
     * This code is typically not available through the {@link AntikytheraRunTime} class because we are
     * processing only the src/main and mostly ignoring src/test
     *
     * @param file the file name
     * @throws FileNotFoundException if the source code cannot be found.
     */
    void loadExisting(File file) throws FileNotFoundException {
        gen = StaticJavaParser.parse(file);
        List<MethodDeclaration> remove = new ArrayList<>();
        for (TypeDeclaration<?> t : gen.getTypes()) {
            for (MethodDeclaration md : t.getMethods()) {
                md.getComment().ifPresent(c -> {
                    if (!c.getContent().contains(TestGenerationConstants.GENERATED_COMMENT_AUTHOR_COMPACT)) {
                        remove.add(md);
                    }
                });
            }
            for (MethodDeclaration md : remove) {
                gen.getType(0).remove(md);
            }

            if (t.isClassOrInterfaceDeclaration()) {
                testClass = t.asClassOrInterfaceDeclaration();
                loadPredefinedBaseClassForTest(testClass);
            }
            identifyExistingMocks(t);
        }
    }

    /**
     * Create a clas for the test suite
     *
     * @param className   the name of the class
     * @param packageDecl the package it should be placed in.
     */
    private void createTestClass(String className, String packageDecl) {
        gen = new CompilationUnit();
        if (packageDecl != null && !packageDecl.isEmpty()) {
            gen.setPackageDeclaration(packageDecl);
        }

        testClass = gen.addClass(className);
        loadPredefinedBaseClassForTest(testClass);
    }

    /**
     * <p>Loads a base class that is common to all generated test classes.</p>
     * <p>
     * Provided that an entry called base_test_class exists in the settings file and the source for
     * that class can be found, it will be loaded. If such an entry does not exist and the test suite
     * had previously been generated, we will check the extended types of the test class.
     * example setting
     *    base_test_class: com.raditha.test.TestHelper
     *
     * @param testClass the declaration of the test suite being built
     */
    @SuppressWarnings("unchecked")
    private void loadPredefinedBaseClassForTest(ClassOrInterfaceDeclaration testClass) {
        String base = Settings.getProperty(Settings.BASE_TEST_CLASS, String.class).orElse(null);
        if (base != null && testClass.getExtendedTypes().isEmpty()) {
            testClass.addExtendedType(base);
            loadPredefinedBaseClassForTest(base);
        } else if (!testClass.getExtendedTypes().isEmpty()) {
            SimpleName n = testClass.getExtendedTypes().get(0).getName();
            String baseClassName = n.toString();
            String fqn = AbstractCompiler.findFullyQualifiedName(testClass.findAncestor(CompilationUnit.class).orElseThrow(),
                    baseClassName);
            if (fqn != null) {
                loadPredefinedBaseClassForTest(testClass);
            } else {
                n.getParentNode().ifPresent(p ->
                        loadPredefinedBaseClassForTest(p.toString())
                );
            }
        }
    }

    /**
     * Loads the base class for the tests if such a file exists.
     *
     * @param baseClassName the name of the base class.
     */
    void loadPredefinedBaseClassForTest(String baseClassName) {
        String basePath = Settings.getProperty(Settings.BASE_PATH, String.class).orElseThrow();
        // Navigate from .../src/main/java → .../src/test/java using the Path API.
        Path testRoot = Paths.get(basePath).getParent().getParent().resolve(TestGenerationConstants.MAVEN_TEST_JAVA_UNDER_SRC);
        String helperPath = testRoot.resolve(AbstractCompiler.classToPath(baseClassName)).toString();
        try {
            baseTestClass = StaticJavaParser.parse(new File(helperPath));
            for (TypeDeclaration<?> t : baseTestClass.getTypes()) {
                identifyExistingMocks(t);
                extractBaseClassFields(t);
            }

            baseTestClass.findFirst(MethodDeclaration.class,
                            md -> md.getNameAsString().equals("setUpBase"))
                    .ifPresent(md -> {
                        TestSuiteEvaluator eval = new TestSuiteEvaluator(baseTestClass, baseClassName);
                        try {
                            eval.setupFields();
                            eval.initializeFields();
                            eval.executeMethod(md);
                        } catch (ReflectiveOperationException e) {
                            throw new AntikytheraException(e);
                        }
                    });

        } catch (FileNotFoundException e) {
            throw new AntikytheraException("Base class could not be loaded for tests.", e);
        }
    }

    @Override
    public void createTests(CallableDeclaration<?> md, MethodResponse response) {
        methodUnderTest = md;
        testMethod = buildTestMethod(md);
        gen.getType(0).addMember(testMethod);
        if (md instanceof MethodDeclaration) {
            createInstance();
        } else {
            md.findAncestor(ClassOrInterfaceDeclaration.class).ifPresent(c ->
                    instanceName = AbstractCompiler.classToInstanceName(c.getNameAsString())
            );
        }
        mockArguments();
        identifyVariables();
        applyPreconditions();
        addWhens();
        String invocation = invokeMethod();

        addDependencies();
        setupAsserterImports();

        if (response.getException() == null) {
            addAsserts(response, invocation);
            for (ReferenceType t : md.getThrownExceptions()) {
                testMethod.addThrownException(t);
                for (ImportWrapper wrapper : AbstractCompiler.findImport(compilationUnitUnderTest, t)) {
                    gen.addImport(wrapper.getImport());
                }
            }
        } else {
            handleExceptionResponse(response, invocation);
        }
    }

    /**
     * Delegates to {@link ExceptionResponseAsserter} for assertThrows vs success-path logic.
     */
    private void handleExceptionResponse(MethodResponse response, String invocation) {
        new ExceptionResponseAsserter(this).handle(response, invocation);
    }
    
    /**
     * Extract test arguments from the current test method.
     * Maps parameter names to their initialization expressions.
     * 
     * @return Map of parameter name to initialization expression
     */
    Map<String, Expression> extractTestArguments() {
        Map<String, Expression> args = new HashMap<>();
        
        // Find variable declarations in the test method
        testMethod.findAll(VariableDeclarationExpr.class).forEach(varDecl -> {
            for (VariableDeclarator var : varDecl.getVariables()) {
                var.getInitializer().ifPresent(init -> {
                    args.put(var.getNameAsString(), init);
                });
            }
        });
        
        return args;
    }

    void seedCollectionArgumentsForException(ExceptionContext ctx, Map<String, Expression> currentArgs) {
        if (ctx == null || currentArgs.isEmpty()) {
            return;
        }
        if (containsCause(ctx.getException(), NullPointerException.class)
                || containsCause(ctx.getException(), NoSuchElementException.class)) {
            return;
        }

        for (Parameter param : methodUnderTest.getParameters()) {
            if (!TypeInspector.isCollectionParameterType(param.getType())) {
                continue;
            }
            String name = param.getNameAsString();
            Expression initializer = currentArgs.get(name);
            if (initializer == null || !CollectionExpressionAnalyzer.isDefinitelyEmptyCollection(initializer)) {
                continue;
            }

            Expression replacement = buildNonEmptyCollectionInitializer(param);
            if (replacement == null) {
                continue;
            }

            replaceInitializer(testMethod, name, replacement);
            logger.info("Replaced empty collection argument '{}' with a non-empty invalid sample to preserve exception-path coverage",
                    name);
        }
    }

    private Expression buildNonEmptyCollectionInitializer(Parameter param) {
        if (!param.getType().isClassOrInterfaceType()) {
            return null;
        }
        Optional<Type> typeArgOpt = param.getType().asClassOrInterfaceType().getTypeArguments()
                .flatMap(args -> args.getFirst());
        if (typeArgOpt.isEmpty()) {
            return null;
        }

        Type elementType = typeArgOpt.orElseThrow();
        String rawElementType = elementType.asString().replaceAll("<.*>", "").trim();
        String fqcn = AbstractCompiler.findFullyQualifiedName(compilationUnitUnderTest, rawElementType);
        String elementInitializer;
        if (fqcn != null) {
            elementInitializer = "new " + fqcn + "()";
        } else if (canInstantiateFieldType(elementType)) {
            elementInitializer = "new " + rawElementType + "()";
        } else {
            return null;
        }

        String rawCollectionType = TypeInspector.rawSimpleName(param.getType());
        String collectionInitializer = switch (rawCollectionType) {
            case "Set", "HashSet" -> "new java.util.HashSet<>(java.util.List.of(" + elementInitializer + "))";
            default -> "new java.util.ArrayList<>(java.util.List.of(" + elementInitializer + "))";
        };
        return StaticJavaParser.parseExpression(collectionInitializer);
    }

    private boolean canInstantiateFieldType(Type type) {
        if (type == null || type.isPrimitiveType() || TypeInspector.isCollectionOrMapFieldType(type)) {
            return false;
        }
        String raw = type.asString().replaceAll("<.*>", "").trim();
        if (raw.equals("String") || raw.startsWith("java.lang.")) {
            return false;
        }
        Optional<TypeDeclaration<?>> typeDeclarationOpt = AntikytheraRunTime.getTypeDeclaration(resolveFieldTypeName(type));
        if (typeDeclarationOpt.isPresent() && typeDeclarationOpt.get() instanceof ClassOrInterfaceDeclaration coid) {
            return !coid.isInterface() && !coid.isAbstract();
        }
        return false;
    }

    /**
     * Returns true if the current test method already contains at least one Mockito.when(...)
     * call.  If there are no stubs, the service's dependencies are plain @Mock fields that
     * return null for any unstubbed invocation — any resulting NPE is real, not an artefact.
     */
    boolean hasWhenStubs() {
        return !testMethod.findAll(MethodCallExpr.class,
            m -> m.getNameAsString().equals("when")).isEmpty();
    }

    /**
     * Returns true if the current test method contains at least one
     * {@code when(...).thenReturn(null)} stub AND contains NO non-null
     * {@code thenReturn(...)} or {@code thenAnswer(...)} stubs.
     *
     * <p>A test where every stub is a null return is deliberately setting up a
     * null-return path for the service; if the service dereferences that null the
     * resulting NPE is intentional and real — not an evaluator artefact.
     *
     * <p>Tests that mix null and non-null stubs (e.g. a count returns 0L while a
     * find returns null) use the null stub for a branch that may never be reached,
     * so the NPE suppression should still apply for those tests.
     */
    boolean hasNullThenReturnStubs() {
        boolean hasNullReturn = false;
        boolean hasNonNullReturn = false;
        for (MethodCallExpr mce : testMethod.findAll(MethodCallExpr.class)) {
            String name = mce.getNameAsString();
            if (name.equals("thenReturn") && mce.getArguments().size() == 1) {
                if (mce.getArgument(0) instanceof NullLiteralExpr) {
                    hasNullReturn = true;
                } else {
                    hasNonNullReturn = true;
                }
            } else if (name.equals("thenAnswer")) {
                hasNonNullReturn = true;
            }
        }
        return hasNullReturn && !hasNonNullReturn;
    }

    private boolean hasThenThrowStubs() {
        return !testMethod.findAll(MethodCallExpr.class,
                m -> m.getNameAsString().equals("thenThrow")).isEmpty();
    }

    boolean shouldSuppressIllegalArgumentAssertThrows(ExceptionContext ctx, Map<String, Expression> currentArgs) {
        if (ctx == null || !containsCause(ctx.getException(), IllegalArgumentException.class)) {
            return false;
        }
        if (hasThenThrowStubs()) {
            return false;
        }
        /*
         * extractTestArguments() only sees variable initializers, not request.setFoo(null) style setup.
         * When the test explicitly assigns null via setters, the CUT often throws a real
         * IllegalArgumentException (validation) at runtime — do not suppress IAE assertThrows.
         */
        if (testMethodHasSetterCallWithNullArgument()) {
            return false;
        }
        for (Expression expr : currentArgs.values()) {
            if (expr instanceof NullLiteralExpr || CollectionExpressionAnalyzer.isDefinitelyEmptyCollection(expr)) {
                return false;
            }
        }
        return true;
    }

    /**
     * True if the generated @Test method calls a JavaBean-style setter with a null literal.
     * Excludes Mockito helpers like {@code thenReturn(null)} (not a {@code set*} name).
     */
    private boolean testMethodHasSetterCallWithNullArgument() {
        for (MethodCallExpr mce : testMethod.findAll(MethodCallExpr.class)) {
            if (mce.getNameAsString().startsWith("set")
                    && mce.getArguments().stream().anyMatch(Expression::isNullLiteralExpr)) {
                return true;
            }
        }
        return false;
    }

    boolean shouldSuppressNoSuchElementAssertThrows(ExceptionContext ctx) {
        return ctx != null
                && containsCause(ctx.getException(), NoSuchElementException.class)
                && !hasOptionalEmptyUsage();
    }

    private boolean hasOptionalEmptyUsage() {
        return !testMethod.findAll(MethodCallExpr.class, m ->
                m.getScope().map(Expression::toString).orElse("").endsWith("Optional")
                        && m.getNameAsString().equals("empty")).isEmpty();
    }

    private boolean containsCause(Throwable throwable, Class<? extends Throwable> type) {
        Throwable current = throwable;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    void addDependencies() {
        for (ImportDeclaration imp : TestGenerator.getImports()) {
            gen.addImport(imp);
        }
    }

    /**
     * Deals with adding Mockito.when().then() type expressions to the generated tests.
     */
    private void addWhens() {
        for (Expression expr : getWhenThen()) {
            if (expr instanceof MethodCallExpr mce && skipWhenUsage(mce)) {
                continue;
            }
            normalizeInlineObjectCreationNulls(expr);
            expandInlineDtoCollectionFields(expr);
            getBody(testMethod).addStatement(expr);
        }
        clearWhenThen();
    }

    /**
     * Repository/query evaluation can synthesize inline constructor calls containing nulls for
     * simple boxed/String parameters. That tends to create test stubs that fail before the
     * intended branch is reached. When we can infer a simple constructor signature from source,
     * replace those null arguments with generic non-null defaults.
     */
    private void replaceNullArgumentsWithDefaults(ObjectCreationExpr oce, List<Type> parameterTypes) {
        NodeList<Expression> normalizedArgs = new NodeList<>();
        for (int i = 0; i < oce.getArguments().size(); i++) {
            Expression argument = oce.getArgument(i);
            if (argument.isNullLiteralExpr()) {
                Expression defaultExpression = defaultExpressionForSimpleType(parameterTypes.get(i));
                if (defaultExpression != null) {
                    normalizedArgs.add(defaultExpression);
                    continue;
                }
            }
            normalizedArgs.add(argument);
        }
        oce.setArguments(normalizedArgs);
    }

    private void normalizeInlineObjectCreationNulls(Expression expr) {
        Optional<CompilationUnit> cuOpt = methodUnderTest.findCompilationUnit();
        if (cuOpt.isEmpty()) {
            return;
        }
        CompilationUnit cu = cuOpt.orElseThrow();
        for (ObjectCreationExpr oce : expr.findAll(ObjectCreationExpr.class)) {
            if (oce.getArguments().isEmpty() || oce.getArguments().stream().noneMatch(Expression::isNullLiteralExpr)) {
                continue;
            }
            List<Type> parameterTypes = resolveSimpleConstructorParameterTypes(cu, oce);
            if (parameterTypes == null) {
                continue;
            }
            replaceNullArgumentsWithDefaults(oce, parameterTypes);
        }
    }

    private List<Type> resolveSimpleConstructorParameterTypes(CompilationUnit cu, ObjectCreationExpr oce) {
        String typeName = oce.getType().getNameAsString();
        String fqn = oce.getType().getScope()
                .map(scope -> scope + "." + typeName)
                .orElseGet(() -> AbstractCompiler.findFullyQualifiedName(cu, typeName));
        if (fqn == null) {
            return null;
        }

        Optional<TypeDeclaration<?>> typeDeclaration = AntikytheraRunTime.getTypeDeclaration(fqn);
        if (typeDeclaration.isPresent() && typeDeclaration.orElseThrow() instanceof ClassOrInterfaceDeclaration coid) {
            for (ConstructorDeclaration constructor : coid.getConstructors()) {
                if (constructor.getParameters().size() != oce.getArguments().size()) {
                    continue;
                }
                List<Type> parameterTypes = new ArrayList<>();
                boolean allSimple = true;
                for (Parameter parameter : constructor.getParameters()) {
                    if (!isSimpleConstructorType(parameter.getType())) {
                        allSimple = false;
                        break;
                    }
                    parameterTypes.add(parameter.getType());
                }
                if (allSimple) {
                    return parameterTypes;
                }
            }
        }
        return null;
    }

    private boolean isSimpleConstructorType(Type type) {
        if (type.isPrimitiveType()) {
            return true;
        }
        if (!type.isClassOrInterfaceType()) {
            return false;
        }
        String simpleName = type.asClassOrInterfaceType().getNameAsString();
        return switch (simpleName) {
            case "String", "Integer", "Long", "Boolean", "Double", "Float", "Byte", "Short", "Character" -> true;
            default -> false;
        };
    }

    private Expression defaultExpressionForSimpleType(Type type) {
        String simpleName = type.isClassOrInterfaceType()
                ? type.asClassOrInterfaceType().getNameAsString()
                : type.asString();
        return switch (simpleName) {
            case "String" -> new StringLiteralExpr("0");
            case "Long", "long" -> new LongLiteralExpr("0L");
            case "Integer", "int" -> new IntegerLiteralExpr("0");
            case "Boolean", "boolean" -> new BooleanLiteralExpr(false);
            case "Double", "double", "Float", "float" -> StaticJavaParser.parseExpression("0.0");
            case "Short", "short", "Byte", "byte", "Character", "char" -> new IntegerLiteralExpr("0");
            default -> {
                Object defaultValue = Reflect.getDefault(simpleName);
                yield defaultValue != null ? Reflect.createLiteralExpression(defaultValue) : null;
            }
        };
    }

    /**
     * When a when().thenReturn(new XxxDto()) expression contains an inline object-creation
     * whose class has declared Collection/List/Set/Map fields, extract the object into a local
     * variable so that those fields can be initialised to empty collections before the when()
     * statement is added to the test body.  This prevents NullPointerExceptions when the
     * service under test calls .size() or iterates the list.
     */
    private void expandInlineDtoCollectionFields(Expression expr) {
        if (!(expr instanceof MethodCallExpr thenReturn)
                || !thenReturn.getNameAsString().equals("thenReturn")
                || thenReturn.getArguments().isEmpty()
                || !(thenReturn.getArgument(0) instanceof ObjectCreationExpr oce)) {
            return;
        }

        String typeName = oce.getType().asString();
        Optional<TypeDeclaration<?>> typeDecOpt = AntikytheraRunTime.getTypeDeclaration(typeName);
        if (typeDecOpt.isEmpty()) {
            return;
        }

        List<FieldDeclaration> collectionFields = typeDecOpt.get().getFields().stream()
                .filter(f -> TypeInspector.isCollectionOrMapFieldType(f.getElementType()))
                .toList();

        if (collectionFields.isEmpty()) {
            return;
        }

        String varName = Variable.generateVariableName(typeName);
        BlockStmt body = getBody(testMethod);
        body.addStatement(typeName + " " + varName + " = new " + typeName + "();");
        for (FieldDeclaration field : collectionFields) {
            String fieldName = field.getVariable(0).getNameAsString();
            TestGenerator.addImport(new ImportDeclaration(Reflect.JAVA_UTIL_ARRAY_LIST, false, false));
            body.addStatement(String.format("%s.set%s(new ArrayList());",
                    varName, AbstractCompiler.setterSuffixFromFieldName(fieldName)));
        }
        thenReturn.setArgument(0, new NameExpr(varName));
    }
    
    private void identifyVariables() {
        variables.clear();
        testMethod.accept(new VoidVisitorAdapter<Map<String, Boolean>>() {
            @Override
            public void visit(VariableDeclarationExpr decl, Map<String, Boolean> vars) {
                for (VariableDeclarator v : decl.getVariables()) {
                    boolean isMockito = !(v.getInitializer().isPresent() && v.getInitializer().orElseThrow().isObjectCreationExpr());
                    variables.put(v.getNameAsString(), isMockito);
                }
                super.visit(decl, vars);
            }
        }, variables);

        testClass.accept(new VoidVisitorAdapter<Map<String, Boolean>>() {
            @Override
            public void visit(FieldDeclaration fd, Map<String, Boolean> vars) {
                for (VariableDeclarator v : fd.getVariables()) {
                    boolean isMockito = fd.getAnnotationByName(MOCK).isPresent() ||
                                        fd.getAnnotationByName(MOCK_BEAN).isPresent();
                    variables.put(v.getNameAsString(), isMockito);
                }
                super.visit(fd, vars);
            }
        }, variables);
    }

    private boolean skipWhenUsage(MethodCallExpr mce) {
        Optional<MethodCallExpr> argMethod = extractWhenArgumentMethodCall(mce);
        if (argMethod.isEmpty()) {
            return false;
        }

        addImportsForCasting(argMethod.get());
        return baseTestClass != null && skipWhenArgumentUsage(argMethod.get());
    }

    private boolean skipWhenArgumentUsage(MethodCallExpr argMethod) {
        return argMethod.getScope().filter(this::shouldSkipWhenScope).isPresent();
    }


    private static Optional<MethodCallExpr> extractWhenArgumentMethodCall(MethodCallExpr mce) {
        if (!(mce.getScope().orElse(null) instanceof MethodCallExpr scopedCall) || scopedCall.getArguments().isEmpty()) {
            return Optional.empty();
        }

        Expression firstArg = scopedCall.getArgument(0);
        return firstArg instanceof MethodCallExpr argMethod ? Optional.of(argMethod) : Optional.empty();
    }

    private boolean shouldSkipWhenScope(Expression scopeExpr) {
        return mockedByBaseTestClass(scopeExpr) || !variables.containsKey(scopeExpr.toString());
    }

    /**
     * Mockito.any() calls often need casting to avoid ambiguity
     * @param argMethod a method call that may contain mocks
     */
    private void addImportsForCasting(MethodCallExpr argMethod) {
        for (Expression e : argMethod.getArguments()) {
            if (e instanceof CastExpr cast && cast.getType() instanceof ClassOrInterfaceType ct) {
                List<ImportWrapper> imports = AbstractCompiler.findImport(compilationUnitUnderTest, ct);
                if (imports.isEmpty()) {
                    solveCastingProblems(ct);
                }
                else {
                    for (ImportWrapper iw : imports) {
                        TestGenerator.addImport(iw.getImport());
                    }
                }
            }
        }
    }

    private static void solveCastingProblems(ClassOrInterfaceType ct) {
        /* We are mocking a variable, but the code may not have been written with an
         * interface as the type of the variable. For example, Antikythera might be
         * using Set<Long> while in the application under test, they may have used
         * LinkedHashSet<Long> instead. So we will be unable to find the required
         * imports from the CompilationUnit.
         */
        String typeName = ct.getNameAsString();
        if (typeName.startsWith("Set") || typeName.startsWith("java.util.Set")) {
            TestGenerator.addImport(new ImportDeclaration("java.util.Set", false, false));
        }
        else if (typeName.startsWith("List") || typeName.startsWith(Reflect.JAVA_UTIL_LIST)) {
            TestGenerator.addImport(new ImportDeclaration(Reflect.JAVA_UTIL_LIST, false, false));
        }
        else if (typeName.startsWith("Map") || typeName.startsWith("java.util.Map")) {
            TestGenerator.addImport(new ImportDeclaration("java.util.Map", false, false));
        }
        else {
            logger.debug("Unable to find import for: {}", ct.getNameAsString());
        }
    }

    private boolean mockedByBaseTestClass(Expression arg) {
        for (TypeDeclaration<?> t : baseTestClass.getTypes()) {
            for (FieldDeclaration fd : t.getFields()) {
                if ((fd.getAnnotationByName(MOCK_BEAN).isPresent() ||
                        fd.getAnnotationByName(MOCK).isPresent()) &&
                        fd.getVariable(0).getNameAsString().equals(arg.toString())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Spring stereotype beans receive the same mock wiring as {@code @Service}: a shared field on the
     * test class plus {@code ReflectionTestUtils.setField} in {@code @BeforeEach}.
     */
    static boolean isSpringStereotypeBean(ClassOrInterfaceDeclaration decl) {
        if (decl == null) {
            return false;
        }
        return decl.getAnnotations().stream().anyMatch(UnitTestGenerator::isSpringStereotypeAnnotation);
    }

    private static boolean isSpringStereotypeAnnotation(AnnotationExpr ann) {
        String name = ann.getNameAsString();
        if (name.equals("Service") || name.equals("Component") || name.equals("Repository")
                || name.equals("Controller") || name.equals("RestController")) {
            return true;
        }
        if (name.equals("org.springframework.web.bind.annotation.RestController")) {
            return true;
        }
        return name.startsWith("org.springframework.stereotype.")
                && (name.endsWith(".Service") || name.endsWith(".Component") || name.endsWith(".Repository")
                || name.endsWith(".Controller"));
    }

    /**
     * Creates an instance of the class under test
     */
    @SuppressWarnings("unchecked")
    void createInstance() {
        methodUnderTest.findAncestor(ClassOrInterfaceDeclaration.class).ifPresent(c -> {
            if (isSpringStereotypeBean(c)) {
                injectMocks(c);
            } else {
                instanceName = AbstractCompiler.classToInstanceName(c.getNameAsString());
                getBody(testMethod).addStatement(ArgumentGenerator.instantiateClass(c, instanceName));
            }
        });
    }

    @SuppressWarnings("unchecked")
    private void injectMocks(ClassOrInterfaceDeclaration classUnderTest) {
        if (testClass == null) {
            testClass = testMethod.findAncestor(ClassOrInterfaceDeclaration.class).orElseThrow();
        }

        if (!autoWired) {
            for (FieldDeclaration fd : testClass.getFields()) {
                if (fd.getElementType().asString().equals(classUnderTest.getNameAsString())) {
                    autoWired = true;
                    instanceName = fd.getVariable(0).getNameAsString();
                    break;
                }
            }
        }
        if (!autoWired) {
            instanceName = AbstractCompiler.classToInstanceName(classUnderTest.getNameAsString());

            if (testClass.getFieldByName(classUnderTest.getNameAsString()).isEmpty()) {
                testClass.addField(classUnderTest.getNameAsString(), instanceName);
            }
            autoWired = true;
        }
    }

    void mockArguments() {
        for (var param : methodUnderTest.getParameters()) {
            mockArgument(param);
        }
    }

    private void mockArgument(Parameter param) {
        Type paramType = param.getType();
        String nameAsString = param.getNameAsString();
        if (paramType.isPrimitiveType() ||
                (paramType.isClassOrInterfaceType() && paramType.asClassOrInterfaceType().isBoxedType())) {
            mockSimpleArgument(param, nameAsString, paramType);
        } else {
            if (tryUseBaseClassField(param)) {
                return;
            }
            addClassImports(paramType);
            Variable v = argumentGenerator.getArguments().get(nameAsString);
            if (v != null) {
                if (v.getValue() != null && v.getValue().getClass().getName().startsWith("java.util")) {
                    mockWithoutMockito(param, v);
                }
                else {
                    mockWithMockito(param, v);
                }
            } else {
                // Argument was not generated; fall back to a Mockito mock declaration
                getBody(testMethod).addStatement(buildMockDeclaration(getTypeName(paramType), nameAsString));
            }
        }
    }

    private void mockSimpleArgument(Parameter param, String nameAsString, Type paramType) {
        VariableDeclarationExpr varDecl = new VariableDeclarationExpr();
        VariableDeclarator v = new VariableDeclarator();
        v.setType(param.getType());
        v.setName(nameAsString);

        String typeName = paramType.asString();

        switch (typeName) {
            case "int", "Integer" -> v.setInitializer("0");
            case "long", "Long" -> v.setInitializer("0L");
            case "double", "Double" -> v.setInitializer("0.0");
            case "float", "Float" -> v.setInitializer("0.0f");
            case "boolean", "Boolean" -> v.setInitializer("false");
            case "char", "Character" -> v.setInitializer("'\\0'");
            case "byte", "Byte" -> v.setInitializer("(byte)0");
            case "short", "Short" -> v.setInitializer("(short)0");
            default -> v.setInitializer("null");
        }


        varDecl.addVariable(v);
        getBody(testMethod).addStatement(varDecl);
    }

    void mockWithMockito(Parameter param, Variable v) {
        String nameAsString = param.getNameAsString();
        if (!v.getInitializer().isEmpty()) {
            mockWhenInitializerIsPresent(param, v);
        }
        else {
            // If initializer is empty but value is a Mockito mock, generate Mockito.mock()
            if (isMockitoMock(v.getValue())) {
                BlockStmt body = getBody(testMethod);
                String type = getTypeName(param.getType());
                body.addStatement(buildMockDeclaration(type, nameAsString));
                return;
            }
            if (mockWhenInitializerIsAbsent(param, v)) return;
        }
        mockParameterFields(v, nameAsString);
    }

    private boolean mockWhenInitializerIsAbsent(Parameter param, Variable v) {
        BlockStmt body = getBody(testMethod);
        String nameAsString = param.getNameAsString();
        Type t = param.getType();

        if (param.findCompilationUnit().isPresent()) {
            CompilationUnit cu = param.findCompilationUnit().orElseThrow();
            if (t instanceof ArrayType) {
                Variable mocked = Reflect.variableFactory(t.asString());
                String type = getTypeName(t);
                body.addStatement(type + " " + nameAsString + " = " + mocked.getInitializer().getFirst() + ";");
                mockParameterFields(v, nameAsString);
                return true;
            }
            // Check if it's an interface - always use Mockito.mock() for interfaces
            if (t.isClassOrInterfaceType()) {
                TypeWrapper wrapper = AbstractCompiler.findType(cu, t);
                if (wrapper != null && wrapper.isInterface()) {
                    body.addStatement(buildMockDeclaration(getTypeName(t), nameAsString));
                    return false;
                }
            }
            if (AbstractCompiler.isFinalClass(param.getType(), cu)) {
                cantMockFinalClass(param, v, cu);
                return true;
            }
        }
        if (t != null && t.isClassOrInterfaceType() && t.asClassOrInterfaceType().getTypeArguments().isPresent()) {
            String rawType = t.asClassOrInterfaceType().getNameAsString();
            body.addStatement(String.format("%s %s = Mockito.mock(%s.class);", getTypeName(t), nameAsString, rawType));
        } else {
            body.addStatement(buildMockDeclaration(getTypeName(t), nameAsString));
        }
        return false;
    }

    private void mockWhenInitializerIsPresent(Parameter param, Variable v) {
        String nameAsString = param.getNameAsString();
        BlockStmt body = getBody(testMethod);
        Type t = param.getType();

        Expression firstInitializer = v.getInitializer().getFirst();
        // If the initializer is already a Mockito.mock() call, use it directly
        if (v.getInitializer().size() == 1 && firstInitializer.isMethodCallExpr()) {
            MethodCallExpr mce = firstInitializer.asMethodCallExpr();
            // Check if it's Mockito.mock() - name is "mock" and (scope is "Mockito" OR has ClassExpr argument)
            if (mce.getNameAsString().equals("mock")) {
                Optional<Expression> scope = mce.getScope();
                boolean hasMockitoScope = scope.isPresent() &&
                    (scope.get().toString().equals("Mockito") || scope.get().toString().contains("Mockito"));
                boolean hasClassExprArg = mce.getArguments().size() == 1 && 
                    mce.getArgument(0).isClassExpr();
                if (hasMockitoScope || hasClassExprArg) {
                    // Already a Mockito.mock() call, use it directly
                    body.addStatement(param.getTypeAsString() + " " + nameAsString + " = " + firstInitializer + ";");
                    return;
                }
            }
        }
        
        if (v.getInitializer().size() == 1 && v.getInitializer().getFirst().isObjectCreationExpr() &&
                    isMockitoMock(v.getValue())) {
            body.addStatement(buildMockDeclaration(t.asClassOrInterfaceType().getNameAsString(), nameAsString));
            return;
        }

        mockWithoutMockito(param, v);

        for (int i = 1; i < v.getInitializer().size() ; i++) {
            body.addStatement(v.getInitializer().get(i));
        }
    }

    public static boolean isMockitoMock(Object object) {
        if (object == null) {
            return false;
        }
        try {
            return Mockito.mockingDetails(object).isMock();
        } catch (Exception e) {
            return false;
        }
    }

    private void mockWithoutMockito(Parameter param, Variable v) {
        BlockStmt body = getBody(testMethod);
        String nameAsString = param.getNameAsString();
        body.addStatement(param.getTypeAsString() + " " + nameAsString + " = " +
                v.getInitializer().getFirst() + ";");
    }

    private static String buildMockDeclaration(String type, String variableName) {
        return String.format("%s %s = Mockito.mock(%s.class);",
                type, variableName, getRawTypeName(type));
    }

    private static String getRawTypeName(String type) {
        int genericStart = type.indexOf('<');
        return genericStart >= 0 ? type.substring(0, genericStart).trim() : type.trim();
    }

    private void cantMockFinalClass(Parameter param, Variable v, CompilationUnit cu) {
        String nameAsString = param.getNameAsString();
        BlockStmt body = getBody(testMethod);
        Type t = param.getType();

        String fullClassName = AbstractCompiler.findFullyQualifiedName(cu, t);
        Variable mocked = Reflect.variableFactory(fullClassName);
        if (v.getValue() instanceof Optional<?> value) {

            if (value.isPresent()) {
                body.addStatement(param.getTypeAsString() + " " + nameAsString + " = " +
                        "Optional.of(" + mocked.getInitializer().getFirst() + ");");
            } else {
                body.addStatement(param.getTypeAsString() + " " + nameAsString + " = Optional.empty();");
            }
        }
        else {
            if (mocked.getInitializer().isEmpty()) {
                body.addStatement(param.getTypeAsString() + " " + nameAsString + " = null;");
            }
            else {
                body.addStatement(param.getTypeAsString() + " " + nameAsString + " = " + mocked.getInitializer() + ";");
                mockParameterFields(v, nameAsString);
            }
        }
    }

    void mockParameterFields(Variable v, String nameAsString) {
        mockFieldSupport.wireParameterObjectFields(v, nameAsString);
    }

    /**
     * @see MockFieldSupport#applyMockAnnotationForDependencyTypeStatic — used from tests via {@code invoke(null, ...)}.
     */
    static void applyMockAnnotationForDependencyType(FieldDeclaration field, Type elementType) {
        MockFieldSupport.applyMockAnnotationForDependencyTypeStatic(field, elementType, GeneratorSeams.defaults());
    }

    /**
     * Test seam: delegates to {@link MockFieldSupport#createFieldInitializer}.
     */
    Expression createFieldInitializer(FieldDeclaration field, Variable fieldVar) {
        return mockFieldSupport.createFieldInitializer(field, fieldVar);
    }

    ClassOrInterfaceDeclaration testSuiteClass() {
        return testClass;
    }

    String resolveFieldTypeName(Type type) {
        String fullClassName = AbstractCompiler.findFullyQualifiedName(compilationUnitUnderTest, type);
        return fullClassName != null ? fullClassName : type.asString();
    }

    Expression coerceInitializerForFieldType(Type type, Expression initializer) {
        if (initializer == null) {
            return null;
        }
        String rawType = TypeInspector.rawSimpleName(type);
        if (rawType.equals("Long") || rawType.equals("long")) {
            if (initializer.isIntegerLiteralExpr()) {
                return new LongLiteralExpr(initializer.asIntegerLiteralExpr().getValue() + "L");
            }
            String text = initializer.toString().trim();
            if (text.matches("-?\\d+")) {
                return new LongLiteralExpr(text + "L");
            }
        }
        return initializer;
    }

    String coerceGeneratedStringPlaceholder(String value) {
        if (TestGenerationConstants.EVALUATOR_STRING_PLACEHOLDER.equals(value)) {
            return TestGenerationConstants.NUMERIC_PLACEHOLDER;
        }
        return value;
    }

    void applyPreconditions() {
        for (MockingCall  result : MockingRegistry.getAllMocks()) {
            if (! result.isFromSetup()) {
                applyRegistryCondition(result);
            }
        }

        for (Precondition expr : preConditions) {
            applyPreconditionWithMockito(expr.getExpression());
        }
    }

    void applyRegistryCondition(MockingCall result) {
        if (result.getVariable().getValue() instanceof Optional<?>) {
            applyPreconditionsForOptionals(result);
        }
        else {
            if (result.getExpression() != null) {
                for (Expression e : result.getExpression()) {
                    addWhenThen(e);
                }
            }
        }
    }

    void applyPreconditionsForOptionals(MockingCall result) {
        if (result.getVariable().getValue() instanceof Optional<?> value) {
            Callable callable = result.getCallable();
            MethodCallExpr methodCall;
            if (value.isPresent()) {
                methodCall = applyPreconditionForOptionalPresent(result, value.get(), callable);
            }
            else {
                // create an expression that represents Optional.empty()
                Expression empty = StaticJavaParser.parseExpression("Optional.empty()");
                methodCall = MockingRegistry.buildMockitoWhen(
                        callable.getNameAsString(), empty, result.getVariableName());
            }
            if (callable.isMethodDeclaration()) {
                methodCall.setArguments(MockingRegistry.fakeArguments(callable.asMethodDeclaration()));
            } else {
                methodCall.setArguments(MockingRegistry.generateArgumentsForWhen(callable.getMethod()));
            }
        }
    }

    private MethodCallExpr applyPreconditionForOptionalPresent(MockingCall result, Object value, Callable callable) {
        MethodCallExpr methodCall;
        if (value instanceof Evaluator eval) {
            Expression optionalValue = buildOptionalPresentValue(result, eval);
            Expression opt = StaticJavaParser.parseExpression("Optional.of(" + optionalValue + ")");
            methodCall = MockingRegistry.buildMockitoWhen(
                    callable.getNameAsString(), opt, result.getVariableName());
        }
        else {
            MethodCallExpr opt = new MethodCallExpr(new NameExpr("Optional"), "of")
                    .setArguments(new NodeList<>(createOptionalValueExpression(value)));
            methodCall = MockingRegistry.buildMockitoWhen(
                    callable.getNameAsString(), opt, result.getVariableName());
        }
        return methodCall;
    }

    /**
     * Prefer the evaluator-recorded initializer when we already know what concrete object shape
     * the symbolic execution used for an Optional-present path. Falling back to Mockito.mock()
     * here loses that information and can diverge from runtime behaviour, especially for JPA
     * entities that are later serialized or converted by ObjectMapper.
     */
    private Expression buildOptionalPresentValue(MockingCall result, Evaluator eval) {
        List<Expression> initializers = result.getVariable().getInitializer();
        if (!initializers.isEmpty() && initializers.getFirst().isObjectCreationExpr()) {
            return initializers.getFirst().clone();
        }
        String className = eval.getClassName();
        if (isJpaEntity(className)) {
            return StaticJavaParser.parseExpression("new " + className + "()");
        }
        if (baseTestClass != null) {
            return StaticJavaParser.parseExpression(String.format("Mockito.mock(%s.class)", className));
        }
        return StaticJavaParser.parseExpression("new " + className + "()");
    }

    private boolean isJpaEntity(String className) {
        return AntikytheraRunTime.getTypeDeclaration(className)
                .map(type -> type.getAnnotationByName("Entity").isPresent())
                .orElse(false);
    }

    Expression createOptionalValueExpression(Object value) {
        if (value instanceof String s) {
            return new StringLiteralExpr(s);
        }
        if (value instanceof Byte b) {
            return StaticJavaParser.parseExpression("(byte) " + b);
        }
        if (value instanceof Short s) {
            return StaticJavaParser.parseExpression("(short) " + s);
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Double
                || value instanceof Float || value instanceof Boolean || value instanceof Character) {
            return Reflect.createLiteralExpression(value);
        }
        if (value instanceof Enum<?> e) {
            return StaticJavaParser.parseExpression(e.getDeclaringClass().getName() + "." + e.name());
        }
        return StaticJavaParser.parseExpression("org.mockito.Mockito.mock(" + value.getClass().getName() + ".class)");
    }

    @SuppressWarnings("java:S5411")
    private void applyPreconditionWithMockito(Expression expr) {
        BlockStmt body = getBody(testMethod);
        if (expr.isMethodCallExpr()) {
            MethodCallExpr mce = normalizeSetterPrecondition(expr.asMethodCallExpr().clone());
            if (mce == null) {
                return;
            }
            mce.getScope().ifPresent(scope -> {
                String name = mce.getNameAsString();

                if (expr.toString().contains("set")) {
                    if (variables.getOrDefault(scope.toString(), false)) {
                        body.addStatement("Mockito.when(%s.%s()).thenReturn(%s);".formatted(
                                scope.toString(),
                                name.replace("set", "get"),
                                mce.getArguments().get(0).toString()
                        ));
                    }
                    else {
                        body.addStatement(mce);
                    }
                }
            });
        }
        else if (expr instanceof AssignExpr assignExpr) {
            Expression target = assignExpr.getTarget();
            Expression value = assignExpr.getValue();
            if (target instanceof NameExpr nameExpr) {
                replaceInitializer(testMethod, nameExpr.getNameAsString(), value);
            }
        }
    }

    private MethodCallExpr normalizeSetterPrecondition(MethodCallExpr mce) {
        if (!mce.getNameAsString().startsWith("set") || mce.getArguments().size() != 1) {
            return mce;
        }
        if (mce.getScope().isPresent() && !setterExistsOnScopeType(mce.getScope().get(), mce.getNameAsString())) {
            return null;
        }
        Expression arg = mce.getArgument(0);
        if (arg instanceof StringLiteralExpr stringLiteral) {
            mce.setArgument(0, new StringLiteralExpr(coerceGeneratedStringPlaceholder(stringLiteral.getValue())));
        }
        if (mce.getScope().isPresent()) {
            resolveSetterParameterType(mce.getScope().get(), mce.getNameAsString()).ifPresent(parameterType ->
                    mce.setArgument(0, coerceInitializerForFieldType(parameterType, mce.getArgument(0))));
        }
        return mce;
    }

    private boolean setterExistsOnScopeType(Expression scope, String setterName) {
        return resolveExpressionType(scope)
                .map(typeName -> AntikytheraRunTime.getTypeDeclaration(typeName)
                        .map(type -> hasSetterOrCompatibleField(type, setterName))
                        .orElse(true))
                .orElse(true);
    }

    private boolean hasSetterOrCompatibleField(TypeDeclaration<?> type, String setterName) {
        if (type.getMethodsByName(setterName).stream().anyMatch(md -> md.getParameters().size() == 1)) {
            return true;
        }
        String propertyName = AbstractCompiler.classToInstanceName(setterName.substring(3));
        return type.getFields().stream().anyMatch(field -> {
            String fieldName = field.getVariable(0).getNameAsString();
            if (fieldName.equals(propertyName)) {
                return true;
            }
            String booleanFieldName = "is" + AbstractCompiler.instanceToClassName(propertyName);
            return fieldName.equals(booleanFieldName);
        });
    }

    private Optional<Type> resolveSetterParameterType(Expression scope, String setterName) {
        return resolveExpressionType(scope)
                .flatMap(AntikytheraRunTime::getTypeDeclaration)
                .flatMap(type -> resolveSetterParameterType(type, setterName));
    }

    Optional<Type> resolveSetterParameterType(TypeDeclaration<?> type, String setterName) {
        return type.getMethodsByName(setterName).stream()
                .filter(md -> md.getParameters().size() == 1)
                .map(md -> md.getParameter(0).getType())
                .findFirst();
    }

    private Optional<String> resolveExpressionType(Expression scope) {
        if (scope.isThisExpr()) {
            return Optional.of(compilationUnitUnderTest.getPrimaryTypeName()
                    .map(name -> compilationUnitUnderTest.getPackageDeclaration()
                            .map(pd -> pd.getNameAsString() + "." + name)
                            .orElse(name))
                    .orElse(""));
        }
        if (scope.isNameExpr()) {
            if (testMethod == null) {
                return Optional.empty();
            }
            String variableName = scope.asNameExpr().getNameAsString();
            return testMethod.findAll(VariableDeclarator.class).stream()
                    .filter(vd -> vd.getNameAsString().equals(variableName))
                    .map(vd -> resolveFieldTypeName(vd.getType()))
                    .findFirst()
                    .filter(typeName -> !typeName.isBlank());
        }
        return Optional.empty();
    }

    private void addClassImports(Type t) {
        for (ImportWrapper wrapper : AbstractCompiler.findImport(compilationUnitUnderTest, t)) {
            addImport(wrapper.getImport());
        }
    }

    String invokeMethod() {
        StringBuilder b = new StringBuilder();

        if (methodUnderTest instanceof MethodDeclaration md) {
            Type t = md.getType();
            if (t != null && !t.toString().equals("void")) {
                b.append(getTypeName(t)).append(" resp = ");
                for (ImportWrapper imp : AbstractCompiler.findImport(compilationUnitUnderTest, t)) {
                    addImport(imp.getImport());
                }
            }

            b.append(instanceName).append(".").append(md.getNameAsString()).append("(");
        } else if (methodUnderTest instanceof ConstructorDeclaration cd) {
            String typeName = cd.getNameAsString();
            b.append(typeName).append(" ").append(instanceName).append(" = new ").append(typeName).append("(");
        }

        for (int i = 0; i < methodUnderTest.getParameters().size(); i++) {
            b.append(methodUnderTest.getParameter(i).getNameAsString());
            if (i < methodUnderTest.getParameters().size() - 1) {
                b.append(", ");
            }
        }
        b.append(");");
        return b.toString();
    }

    void addAsserts(MethodResponse response, String invocation) {
        Type t = null;
        if (methodUnderTest instanceof MethodDeclaration md) {
            t = md.getType();
        }

        if (t != null && !t.isVoidType()) {
            addClassImports(t);

            if (response.getBody() != null) {
                getBody(testMethod).addStatement(invocation);
                noSideEffectAsserts(response);
            } else {
                sideEffects(response, invocation);
            }
        } else {
            sideEffects(response, invocation);
        }
    }

    private void sideEffects(MethodResponse response, String invocation) {
        BlockStmt body = getBody(testMethod);
        int statementsBefore = body.getStatements().size();
        body.addStatement(invocation);
        int statementsBeforeAsserts = body.getStatements().size();

        sideEffectAsserts();
        if (response.getCapturedOutput() != null && !response.getCapturedOutput().trim().isEmpty()) {
            body.addStatement(asserter.assertOutput(response.getCapturedOutput().trim()));
        }

        if (body.getStatements().size() == statementsBeforeAsserts) {
            body.getStatements().remove(statementsBefore);
            body.addStatement(asserter.assertDoesNotThrow(invocation));
        }
    }

    @SuppressWarnings("unchecked")
    private void sideEffectAsserts() {
        BlockStmt body = getBody(testMethod);
        TypeDeclaration<?> type = methodUnderTest.findAncestor(TypeDeclaration.class).orElseThrow();
        String className = type.getFullyQualifiedName().orElseThrow();
        List<LogRecorder.LogEntry> logs = LogRecorder.getLogEntries(className);

        if (Settings.getProperty(Settings.LOG_APPENDER, String.class).isPresent()) {
            if (testClass.getMethodsByName("setupLoggers").isEmpty()) {
                setupLoggers();
            }
            if (logs.isEmpty()) {
                MethodCallExpr assertion = new MethodCallExpr("assertTrue");
                MethodCallExpr condition = new MethodCallExpr("LogAppender.events.isEmpty");
                assertion.addArgument(condition);
                body.addStatement(assertion);
            }
            else {
                for (int i = 0, j = Math.min(5, logs.size()); i < j; i++) {
                    LogRecorder.LogEntry entry = logs.get(i);
                    String level = entry.level();
                    String message = entry.message();
                    body.addStatement(assertLoggedWithLevel(className, level, message));
                }
            }
        }
    }

    private void noSideEffectAsserts(MethodResponse response) {
        Variable result = response.getBody();
        BlockStmt body = getBody(testMethod);
        Object value = result.getValue();

        if (value == null) {
            body.addStatement(asserter.assertNull("resp"));
            addCapturedOutputAssert(response, body);
            return;
        }

        assertValueWithNoSideEffects(result, body, value);
        asserter.addFieldAsserts(response, body);
        addCapturedOutputAssert(response, body);
    }

    private void assertValueWithNoSideEffects(Variable result, BlockStmt body, Object value) {
        Expression scalarLiteral = toScalarLiteralExpression(result, value);
        if (scalarLiteral != null) {
            body.addStatement(asserter.assertEquals(scalarLiteral.toString(), "resp"));
            return;
        }

        body.addStatement(asserter.assertNotNull("resp"));
        if (value instanceof Collection<?> c) {
            if (responseIsLowConfidenceCollection(result)) {
                return;
            }
            body.addStatement(c.isEmpty() ? asserter.assertEmpty("resp") : asserter.assertNotEmpty("resp"));
        }
    }

    private boolean responseIsLowConfidenceCollection(Variable result) {
        return result != null
                && result.getValue() instanceof Collection<?>
                && methodUnderTest instanceof MethodDeclaration methodDeclaration
                && methodDeclaration.findCompilationUnit().isPresent()
                && MethodResponse.confidenceForDeclaredReturnType(
                        methodDeclaration.findCompilationUnit().orElseThrow(),
                        methodDeclaration.getType()
                ) == sa.com.cloudsolutions.antikythera.generator.AssertionConfidence.LOW;
    }

    /**
     * Returns a literal expression for scalar values so asserter.assertEquals receives
     * properly rendered Java literals (quotes for strings/chars, L suffix for longs, etc.).
     */
    private Expression toScalarLiteralExpression(Variable result, Object value) {
        if (value instanceof String s) {
            return new StringLiteralExpr(s);
        }
        if (isBoxedCoreScalar(value)) {
            return Reflect.createLiteralExpression(value);
        }
        if (value instanceof Short || value instanceof Byte) {
            // Keep byte/short cast formatting consistent with createOptionalValueExpression.
            return createOptionalValueExpression(value);
        }
        if (result.getType() != null && result.getType().isPrimitiveType()) {
            return Reflect.createLiteralExpression(value);
        }
        return null;
    }

    private static boolean isBoxedCoreScalar(Object value) {
        return value instanceof Integer
                || value instanceof Long
                || value instanceof Double
                || value instanceof Float
                || value instanceof Boolean
                || value instanceof Character;
    }

    private void addCapturedOutputAssert(MethodResponse response, BlockStmt body) {
        if (response.getCapturedOutput() != null && !response.getCapturedOutput().trim().isEmpty()) {
            body.addStatement(asserter.assertOutput(response.getCapturedOutput().trim()));
        }
    }

    @SuppressWarnings("unchecked")
    private void setupLoggers() {
        methodUnderTest.findAncestor(ClassOrInterfaceDeclaration.class).ifPresent(classDeclaration -> {
            BlockStmt body = new BlockStmt();
            MethodDeclaration md = new MethodDeclaration().setName("setupLoggers")
                    .setType(void.class)
                    .addAnnotation("BeforeEach")
                    .setJavadocComment(AUTHOR_ANTIKYTHERA)
                    .setBody(body);

            body.addStatement(String.format("appLogger = (Logger) LoggerFactory.getLogger(%s.class);",
                    classDeclaration.getFullyQualifiedName().orElseThrow()));
            body.addStatement("appLogger.setAdditive(false);");
            body.addStatement("logAppender = new LogAppender();");
            body.addStatement("logAppender.start();");
            body.addStatement("appLogger.addAppender(logAppender);");
            body.addStatement("appLogger.setLevel(Level.DEBUG);");
            body.addStatement("LogAppender.events.clear(); // Clear static list from previous tests");

            if (testClass.getFieldByName("appLogger").isEmpty()) {
                testClass.addPrivateField("Logger", "appLogger");
                testClass.addPrivateField("LogAppender", "logAppender");
            }

            testClass.addMember(md);
            gen.addImport("ch.qos.logback.classic.Logger");
            gen.addImport("ch.qos.logback.classic.Level");
            gen.addImport("org.slf4j.LoggerFactory");
            gen.addImport(Settings.getProperty(Settings.LOG_APPENDER, String.class).orElseThrow());
        });
    }

    @Override
    public void setCommonPath(String commonPath) {
        throw new UnsupportedOperationException("Not needed here");
    }

    /**
     * Generate service instance creation and ReflectionTestUtils.setField() calls to inject all mocked fields.
     * This approach guarantees field injection works even with duplicate field types (e.g., two PatientPOMRDao fields).
     */
    private record StereotypeServiceTarget(String serviceClassName, ClassOrInterfaceDeclaration bean) {
    }

    private Optional<StereotypeServiceTarget> findStereotypeServiceTarget() {
        for (TypeDeclaration<?> type : compilationUnitUnderTest.getTypes()) {
            if (type instanceof ClassOrInterfaceDeclaration c && isSpringStereotypeBean(c)) {
                return Optional.of(new StereotypeServiceTarget(type.getNameAsString(), c));
            }
        }
        return Optional.empty();
    }

    private List<String> collectMockedFieldNamesFromGeneratedTestClass() {
        List<String> mockedFieldNames = new ArrayList<>();
        for (TypeDeclaration<?> testType : gen.getTypes()) {
            for (FieldDeclaration field : testType.getFields()) {
                boolean hasMockAnnotation = field.getAnnotations().stream()
                        .anyMatch(ann -> ann.getNameAsString().equals(TestGenerationConstants.MOCK_SIMPLE_NAME)
                                || ann.getNameAsString().equals(TestGenerationConstants.MOCK_BEAN_SIMPLE_NAME));
                if (hasMockAnnotation) {
                    mockedFieldNames.add(field.getVariable(0).getNameAsString());
                }
            }
        }
        return mockedFieldNames;
    }

    private static void appendServiceInstantiationStatement(BlockStmt beforeBody, String serviceInstanceName, String serviceClassName) {
        ObjectCreationExpr newInstance = new ObjectCreationExpr(null, new ClassOrInterfaceType(serviceClassName), new NodeList<>());
        AssignExpr instantiation = new AssignExpr(new NameExpr(serviceInstanceName), newInstance, AssignExpr.Operator.ASSIGN);
        beforeBody.addStatement(instantiation);
    }

    private static void appendMockReflectionSetFieldCalls(BlockStmt beforeBody, String serviceInstanceName, List<String> mockedFieldNames) {
        for (String fieldName : mockedFieldNames) {
            MethodCallExpr setFieldCall = new MethodCallExpr(
                    new NameExpr(TestGenerationConstants.REFLECTION_TEST_UTILS_SIMPLE_NAME),
                    "setField",
                    new NodeList<>(
                            new NameExpr(serviceInstanceName),
                            new StringLiteralExpr(fieldName),
                            new NameExpr(fieldName)
                    )
            );
            beforeBody.addStatement(setFieldCall);
        }
    }

    private void injectMockedFieldsViaReflection(BlockStmt beforeBody) {
        Optional<StereotypeServiceTarget> target = findStereotypeServiceTarget();
        if (target.isEmpty()) {
            return;
        }
        String serviceClassName = target.get().serviceClassName();
        ClassOrInterfaceDeclaration bean = target.get().bean();
        String serviceInstanceName = AbstractCompiler.classToInstanceName(serviceClassName);
        List<String> mockedFieldNames = collectMockedFieldNamesFromGeneratedTestClass();

        appendServiceInstantiationStatement(beforeBody, serviceInstanceName, serviceClassName);
        appendMockReflectionSetFieldCalls(beforeBody, serviceInstanceName, mockedFieldNames);
        injectValueFieldsViaReflection(beforeBody, bean, serviceInstanceName);
    }

    /**
     * Plain {@code new} stereotype tests do not run in a Spring context, so {@code @Value} fields stay null and
     * unboxing throws {@link NullPointerException}. Mirror Spring defaults with small literals (same idea as
     * {@link #mockSimpleArgument(Parameter, String, Type)} for parameters).
     */
    private void injectValueFieldsViaReflection(BlockStmt beforeBody, ClassOrInterfaceDeclaration bean,
                                                String serviceInstanceName) {
        for (var member : bean.getMembers()) {
            if (!(member instanceof FieldDeclaration fd)) {
                continue;
            }
            if (fd.isStatic() || fd.hasModifier(Modifier.Keyword.FINAL)) {
                continue;
            }
            if (!isSpringFieldValueAnnotation(fd)) {
                continue;
            }
            for (VariableDeclarator var : fd.getVariables()) {
                valueFieldInitializerLiteral(fd.getElementType()).ifPresent(init ->
                        beforeBody.addStatement(String.format(
                                TestGenerationConstants.REFLECTION_TEST_UTILS_SIMPLE_NAME + ".setField(%s, \"%s\", %s);",
                                serviceInstanceName, var.getNameAsString(), init)));
            }
        }
    }

    private static boolean isSpringFieldValueAnnotation(FieldDeclaration fd) {
        return fd.getAnnotations().stream().anyMatch(a -> {
            String n = a.getNameAsString();
            return n.equals("Value") || n.endsWith(".Value");
        });
    }

    /**
     * @return a literal suitable for {@code ReflectionTestUtils.setField} for supported {@code @Value} types only.
     */
    private static Optional<String> valueFieldInitializerLiteral(Type type) {
        if (type instanceof PrimitiveType p) {
            return Optional.of(switch (p.getType()) {
                case BOOLEAN -> "false";
                case BYTE -> "(byte)0";
                case SHORT -> "(short)0";
                case INT -> "0";
                case LONG -> "0L";
                case FLOAT -> "0.0f";
                case DOUBLE -> "0.0";
                case CHAR -> "'\\0'";
            });
        }
        if (type.isClassOrInterfaceType()) {
            String name = type.asClassOrInterfaceType().getNameAsString();
            return Optional.ofNullable(switch (name) {
                case "Integer", "Byte", "Short" -> "0";
                case "Long" -> "0L";
                case "Boolean" -> "false";
                case "Character" -> "'\\0'";
                case "Double" -> "0.0";
                case "Float" -> "0.0f";
                case "String" -> "\"\"";
                default -> null;
            });
        }
        return Optional.empty();
    }

    @Override
    public void addBeforeClass() {
        identifyFieldsToBeMocked();
        addOutputCaptureFields();

        MethodDeclaration before = new MethodDeclaration();
        before.setType(void.class);
        before.addAnnotation("BeforeEach");
        before.setName("setUp");
        BlockStmt beforeBody = new BlockStmt();
        before.setBody(beforeBody);
        beforeBody.addStatement("MockitoAnnotations.openMocks(this);");
        
        // Inject all mocked fields using ReflectionTestUtils for guaranteed injection
        injectMockedFieldsViaReflection(beforeBody);
        
        beforeBody.addStatement("originalOut = System.out;");
        beforeBody.addStatement("System.setOut(new PrintStream(outputStream));");
        before.setJavadocComment(AUTHOR_ANTIKYTHERA);

        if (baseTestClass != null) {
            baseTestClass.findFirst(MethodDeclaration.class,
                            md -> md.getNameAsString().equals("setUpBase"))
                    .ifPresent(md -> beforeBody.addStatement("setUpBase();"));
        }

        for (TypeDeclaration<?> t : gen.getTypes()) {
            if(t.findFirst(MethodDeclaration.class, m -> m.getNameAsString().equals("setUp")).isEmpty()) {
                t.addMember(before);
            }
        }
        addAfterEach();
    }

    private void addOutputCaptureFields() {
        for (TypeDeclaration<?> t : gen.getTypes()) {
            if (t.getFieldByName("outputStream").isEmpty()) {
                t.addField("ByteArrayOutputStream", "outputStream", Modifier.Keyword.PRIVATE, Modifier.Keyword.FINAL)
                        .getVariable(0).setInitializer("new ByteArrayOutputStream()");
                t.addField("PrintStream", "originalOut", Modifier.Keyword.PRIVATE);
            }
        }
    }

    private void addAfterEach() {
        MethodDeclaration after = new MethodDeclaration();
        after.setType(void.class);
        after.addAnnotation("AfterEach");
        after.setName("tearDown");
        BlockStmt afterBody = new BlockStmt();
        after.setBody(afterBody);
        afterBody.addStatement("System.setOut(originalOut);");
        afterBody.addStatement("outputStream.reset();");
        after.setJavadocComment(AUTHOR_ANTIKYTHERA);

        addImport(new ImportDeclaration("org.junit.jupiter.api.AfterEach", false, false));
        addImport(new ImportDeclaration("java.io.PrintStream", false, false));
        addImport(new ImportDeclaration("java.io.ByteArrayOutputStream", false, false));

        for (TypeDeclaration<?> t : gen.getTypes()) {
            if(t.findFirst(MethodDeclaration.class, m -> m.getNameAsString().equals("tearDown")).isEmpty()) {
                t.addMember(after);
            }
        }
    }

    @Override
    public void identifyFieldsToBeMocked() {
        mockFieldSupport.registerExistingMocksFromGeneratedTestSuite();

        addImport(new ImportDeclaration("org.mockito.MockitoAnnotations", false, false));
        addImport(new ImportDeclaration("org.junit.jupiter.api.BeforeEach", false, false));
        addImport(new ImportDeclaration("org.mockito.Mock", false, false));
        addImport(new ImportDeclaration("org.mockito.Mockito", false, false));
        addImport(new ImportDeclaration(TestGenerationConstants.REFLECTION_TEST_UTILS_FQN, false, false));

        for (Map.Entry<String, CompilationUnit> entry : Graph.getDependencies().entrySet()) {
            mockFieldSupport.discoverFieldsToMock(entry.getValue());
        }

        mockFieldSupport.discoverFieldsToMock(compilationUnitUnderTest);
    }

    Optional<ClassOrInterfaceDeclaration> findSuite(TypeDeclaration<?> decl) {
        return gen.findFirst(ClassOrInterfaceDeclaration.class,
                t -> t.getNameAsString().equals(decl.getNameAsString() + TEST_NAME_SUFFIX));

    }

    @Override
    public void save() throws IOException {
        DepSolver.sortClass(testClass);
        // Remove duplicate tests before saving
        boolean removedDuplicates = removeDuplicateTests();
        if (removedDuplicates) {
            logger.info("Removed duplicate test methods from {}", filePath);
        }
        String content = gen.toString();
        Antikythera.getInstance().writeFile(filePath, content);
    }

    static void replaceInitializer(MethodDeclaration method, String name, Expression initialization) {
        method.getBody().ifPresent(body ->{
            NodeList<Statement> statements = body.getStatements();
            for (Statement statement : statements) {
                replaceInitializer(name, initialization, statement);
            }
        });
    }

    private static void replaceInitializer(String name, Expression initialization, Statement statement) {
        if (statement instanceof ExpressionStmt exprStmt && exprStmt.getExpression() instanceof VariableDeclarationExpr varDeclExpr) {
            for (VariableDeclarator varDeclarator : varDeclExpr.getVariables()) {
                if (varDeclarator.getName().getIdentifier().equals(name)) {
                    Expression coerced = coerceInitializer(initialization, varDeclarator.getType());
                    if (coerced != null) {
                        varDeclarator.setInitializer(coerced);
                    }
                }
            }
        }
    }

    private static Expression coerceInitializer(Expression value, Type targetType) {
        // An AssignExpr should never be used as a variable initializer
        if (value instanceof AssignExpr) {
            return null;
        }
        String typeName = targetType.asString();
        // If the new value is any string literal and the target is not a String type,
        // don't replace — the string cannot be assigned to a non-String variable.
        if (value instanceof StringLiteralExpr
                && !typeName.equals("String") && !typeName.equals("java.lang.String")) {
            return null;
        }
        if (typeName.equals("Long") || typeName.equals("long")) {
            if (value instanceof IntegerLiteralExpr ile) {
                return new LongLiteralExpr(ile.getValue() + "L");
            }
            // Coerce boolean literals to long: true becomes 1L, false becomes 0L.
            // This is deliberate test-data widening to support numeric contexts that accept boolean inputs.
            if (value instanceof BooleanLiteralExpr ble) {
                return new LongLiteralExpr(ble.getValue() ? "1L" : "0L");
            }
        }
        // Convert immutable List.of()/Set.of() to mutable equivalents so generated tests
        // can pass the collection to methods that mutate it (e.g. errors.add(...)).
        if (value instanceof MethodCallExpr mce) {
            String name = mce.getNameAsString();
            if (name.equals("of") && mce.getScope().isPresent()) {
                String scope = mce.getScope().get().toString();
                if (scope.equals(TestGenerationConstants.LIST_FACTORY_SCOPE)) {
                    if (mce.getArguments().isEmpty()) {
                        return StaticJavaParser.parseExpression("new java.util.ArrayList<>()");
                    } else {
                        return StaticJavaParser.parseExpression(
                                String.format("new java.util.ArrayList<>(java.util.Arrays.asList(%s))", mce.getArgument(0)));
                    }
                }
                if (scope.equals(TestGenerationConstants.SET_FACTORY_SCOPE)) {
                    if (mce.getArguments().isEmpty()) {
                        return StaticJavaParser.parseExpression("new java.util.HashSet<>()");
                    } else {
                        return StaticJavaParser.parseExpression(
                                String.format("new java.util.HashSet<>(java.util.Arrays.asList(%s))", mce.getArgument(0)));
                    }
                }
            }
        }
        return value;
    }

    public static Expression assertLoggedWithLevel(String className, String level, String expectedMessage) {
        String normalizedMessage = expectedMessage == null ? null : expectedMessage.replace("{}", "").trim();
        MethodCallExpr assertion = new MethodCallExpr("assertTrue");
        MethodCallExpr condition = new MethodCallExpr("LogAppender.hasMessage")
                .addArgument(new FieldAccessExpr(new NameExpr("Level"), level))
                .addArgument(new StringLiteralExpr(className))
                .addArgument(new StringLiteralExpr(normalizedMessage));

        assertion.addArgument(condition);
        return assertion;
    }

    private String getTypeName(Type t) {
        if (t.isClassOrInterfaceType()) {
            ClassOrInterfaceType ct = t.asClassOrInterfaceType();
            String typeName = ct.getNameAsString();
            TypeWrapper wrapper = AbstractCompiler.findType(compilationUnitUnderTest, typeName);
            if (wrapper != null && wrapper.getType() != null) {
                TypeDeclaration<?> typeDecl = wrapper.getType();
                if (typeDecl.isNestedType() && typeDecl.getParentNode().isPresent() && typeDecl.getParentNode().get() instanceof TypeDeclaration<?> parent) {
                    return parent.getNameAsString() + "." + typeDecl.getNameAsString();
                }
            }
        }
        return t.asString();
    }

}
