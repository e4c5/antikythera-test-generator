package sa.com.cloudsolutions.antikythera.generator;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.Name;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.type.Type;
import sa.com.cloudsolutions.antikythera.evaluator.Evaluator;
import sa.com.cloudsolutions.antikythera.evaluator.Reflect;
import sa.com.cloudsolutions.antikythera.evaluator.Variable;
import sa.com.cloudsolutions.antikythera.evaluator.mock.MockingRegistry;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;
import sa.com.cloudsolutions.antikythera.parser.ImportWrapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Mock field discovery ({@code @Autowired} / constructor injection) and wiring of nested field
 * values on parameters (setter vs Mockito.when), extracted from {@link UnitTestGenerator}.
 */
final class MockFieldSupport {

    private final UnitTestGenerator owner;
    private final GeneratorSeams seams;

    MockFieldSupport(UnitTestGenerator owner, GeneratorSeams seams) {
        this.owner = owner;
        this.seams = seams;
    }

    /**
     * Static entry for tests that invoked the former static {@code UnitTestGenerator} method with {@code invoke(null, ...)}.
     */
    static void applyMockAnnotationForDependencyTypeStatic(FieldDeclaration field, Type elementType, GeneratorSeams seams) {
        new MockFieldSupport(null, seams).applyMockAnnotationForDependencyType(field, elementType);
    }

    void registerExistingMocksFromGeneratedTestSuite() {
        for (TypeDeclaration<?> t : owner.gen.getTypes()) {
            for (FieldDeclaration fd : t.getFields()) {
                List<TypeWrapper> wrappers = AbstractCompiler.findTypesInVariable(fd.getVariable(0));
                if (!wrappers.isEmpty() && wrappers.getLast() != null) {
                    MockingRegistry.markAsMocked(MockingRegistry.generateRegistryKey(wrappers));
                }
            }
        }
    }

    void discoverFieldsToMock(CompilationUnit cu) {
        for (TypeDeclaration<?> decl : cu.getTypes()) {
            if (decl instanceof ClassOrInterfaceDeclaration c && UnitTestGenerator.isSpringStereotypeBean(c)) {
                detectConstructorInjection(cu, decl);
            }
            identifyAutoWiring(cu, decl);
        }
    }

    private void identifyAutoWiring(CompilationUnit cu, TypeDeclaration<?> decl) {
        Optional<ClassOrInterfaceDeclaration> suite = owner.findSuite(decl);
        if (suite.isEmpty()) {
            return;
        }
        detectAutoWiringHelper(cu, decl, suite.orElseThrow());
    }

    private void detectAutoWiringHelper(CompilationUnit cu, TypeDeclaration<?> classUnderTest,
                                        ClassOrInterfaceDeclaration testSuite) {
        for (FieldDeclaration fd : classUnderTest.getFields()) {
            List<TypeWrapper> wrappers = AbstractCompiler.findTypesInVariable(fd.getVariable(0));
            if (wrappers.isEmpty()) {
                continue;
            }
            String registryKey = MockingRegistry.generateRegistryKey(wrappers);
            if (fd.getAnnotationByName("Autowired").isPresent()
                    && testSuite.getFieldByName(fd.getVariable(0).getNameAsString()).isEmpty()) {
                addMockedField(cu, testSuite, fd, registryKey);
            }
        }
    }

    private void addMockedField(CompilationUnit cu, ClassOrInterfaceDeclaration testSuite, FieldDeclaration fd, String registryKey) {
        if (!MockingRegistry.isMockTarget(registryKey)) {
            MockingRegistry.markAsMocked(registryKey);
        }
        FieldDeclaration field = testSuite.addField(fd.getElementType(), fd.getVariable(0).getNameAsString());
        applyMockAnnotationForDependencyType(field, fd.getElementType());
        ImportWrapper wrapper = AbstractCompiler.findImport(cu, field.getElementType().asString());
        if (wrapper != null) {
            TestGenerator.addImport(wrapper.getImport());
        }
    }

    /**
     * DAOs and repositories generally benefit from deep stubs. Dependencies whose simple name ends in
     * {@code Client} do too, unless listed via {@link GeneratorSeams#plainMockDependencySimpleNames()}.
     */
    void applyMockAnnotationForDependencyType(FieldDeclaration field, Type elementType) {
        if (elementType.isClassOrInterfaceType()) {
            String simple = elementType.asClassOrInterfaceType().getNameAsString();
            if (simple.endsWith("Dao") || simple.endsWith("Repository")
                    || (simple.endsWith("Client") && !seams.plainMockDependencySimpleNames().test(simple))) {
                field.addAnnotation(new NormalAnnotationExpr(new Name(TestGenerationConstants.MOCK_SIMPLE_NAME), new NodeList<>(
                        new MemberValuePair("answer", new FieldAccessExpr(new NameExpr("Answers"), "RETURNS_DEEP_STUBS"))
                )));
                TestGenerator.addImport(new ImportDeclaration("org.mockito.Answers", false, false));
                return;
            }
        }
        field.addAnnotation(UnitTestGenerator.MOCK);
    }

    private void detectConstructorInjection(CompilationUnit cu, TypeDeclaration<?> decl) {
        for (ConstructorDeclaration constructor : decl.getConstructors()) {
            Map<String, String> paramToFieldMap = mapParamToFields(constructor);
            for (Parameter param : constructor.getParameters()) {
                detectConstructorInjectionHelper(cu, owner.testSuiteClass(), param, paramToFieldMap);
            }
        }
    }

    private void detectConstructorInjectionHelper(CompilationUnit cu, ClassOrInterfaceDeclaration suite,
                                                  Parameter param, Map<String, String> paramToFieldMap) {
        List<TypeWrapper> wrappers = AbstractCompiler.findTypesInVariable(param);
        String registryKey = MockingRegistry.generateRegistryKey(wrappers);
        String paramName = param.getNameAsString();
        String fieldName = paramToFieldMap.getOrDefault(paramName, paramName);

        if (suite.getFieldByName(fieldName).isEmpty()) {
            if (!MockingRegistry.isMockTarget(registryKey)) {
                MockingRegistry.markAsMocked(registryKey);
            }
            FieldDeclaration field = suite.addField(param.getType(), fieldName);
            applyMockAnnotationForDependencyType(field, param.getType());

            for (TypeWrapper wrapper : wrappers) {
                ImportWrapper imp = AbstractCompiler.findImport(cu, wrapper.getFullyQualifiedName());
                if (imp != null) {
                    TestGenerator.addImport(imp.getImport());
                }
            }
        }
    }

    private Map<String, String> mapParamToFields(ConstructorDeclaration constructor) {
        Map<String, String> paramToFieldMap = new HashMap<>();
        constructor.getBody().findAll(AssignExpr.class).forEach(assignExpr -> {
            if (assignExpr.getTarget().isFieldAccessExpr()) {
                String fieldName = assignExpr.getTarget().asFieldAccessExpr().getName().asString();
                if (assignExpr.getValue().isNameExpr()) {
                    String paramName = assignExpr.getValue().asNameExpr().getNameAsString();
                    paramToFieldMap.put(paramName, fieldName);
                }
            }
        });
        return paramToFieldMap;
    }

    void wireParameterObjectFields(Variable v, String nameAsString) {
        if (v.getValue() instanceof Evaluator eval) {
            Optional<TypeDeclaration<?>> typeDeclarationOpt = seams.typeDeclarations().apply(eval.getClassName());
            if (typeDeclarationOpt.isPresent()) {
                TypeDeclaration<?> t = typeDeclarationOpt.get();
                for (FieldDeclaration field : t.getFields()) {
                    if (!v.getInitializer().isEmpty() && v.getInitializer().getFirst() instanceof ObjectCreationExpr) {
                        mockFieldWithSetter(nameAsString, eval, t, field);
                    } else {
                        mockFieldWithMockito(nameAsString, eval, field);
                    }
                }
            }
        }
    }

    private void addListCollectionSetterStub(BlockStmt body, String receiverName, String setterName) {
        TestGenerator.addImport(new ImportDeclaration(Reflect.JAVA_UTIL_LIST, false, false));
        TestGenerator.addImport(new ImportDeclaration(Reflect.JAVA_UTIL_ARRAY_LIST, false, false));
        body.addStatement(String.format("%s.%s(new ArrayList());", receiverName, setterName));
    }

    private void addSetterCallWithCoercedInitializer(BlockStmt body, String receiverName, String setterName,
            TypeDeclaration<?> ownerType, Expression fieldInitializer) {
        Expression coercedInitializer = fieldInitializer;
        if (fieldInitializer != null) {
            coercedInitializer = owner.resolveSetterParameterType(ownerType, setterName)
                    .map(type -> owner.coerceInitializerForFieldType(type, fieldInitializer))
                    .orElse(fieldInitializer);
        }
        if (coercedInitializer == null) {
            return;
        }
        if (coercedInitializer.isMethodCallExpr() && coercedInitializer.toString().startsWith("set")) {
            body.addStatement(String.format("%s.%s;", receiverName, coercedInitializer));
        } else {
            body.addStatement(String.format("%s.%s(%s);", receiverName, setterName, coercedInitializer));
        }
    }

    private void mockFieldWithSetter(String nameAsString, Evaluator eval, TypeDeclaration<?> ownerType, FieldDeclaration field) {
        BlockStmt body = owner.getBody(owner.testMethod);
        String name = field.getVariable(0).getNameAsString();
        String setterName = JavaBeansConventions.setterNameForField(ownerType, field);

        if (!doesFieldNeedMocking(eval, name)) {
            return;
        }
        Variable fieldVar = eval.getField(name);
        Object value = fieldVar.getValue();
        if (value instanceof List || (value == null && TypeInspector.isCollectionOrMapFieldType(fieldVar.getType()))) {
            addListCollectionSetterStub(body, nameAsString, setterName);
            return;
        }
        Expression fieldInitializer = createFieldInitializer(field, fieldVar);
        addSetterCallWithCoercedInitializer(body, nameAsString, setterName, ownerType, fieldInitializer);
    }

    private boolean doesFieldNeedMocking(Evaluator eval, String name) {
        Variable f = eval.getField(name);
        if (f == null || f.getType() == null || name.equals("serialVersionUID")) {
            return false;
        }
        Object value = f.getValue();
        if (value == null) {
            return TypeInspector.isCollectionOrMapFieldType(f.getType()) || canInstantiateFieldType(f.getType());
        }
        return !(f.getType().isPrimitiveType() && f.getValue().equals(Reflect.getDefault(f.getClazz())));
    }

    private Expression createFieldInitializerFromEvaluatorValue(FieldDeclaration field, Object value) {
        if (value instanceof String s) {
            return new StringLiteralExpr(owner.coerceGeneratedStringPlaceholder(s));
        }
        return adjustInitializerForField(field, owner.createOptionalValueExpression(value));
    }

    Expression createFieldInitializer(FieldDeclaration field, Variable fieldVar) {
        if (fieldVar.getValue() == null && canInstantiateFieldType(field.getElementType())) {
            return createEmptyObjectInitializer(field.getElementType());
        }
        if (!fieldVar.getInitializer().isEmpty()) {
            return adjustInitializerForField(field, fieldVar.getInitializer().getFirst());
        }
        if (fieldVar.getValue() == null) {
            return createEmptyObjectInitializer(field.getElementType());
        }
        return createFieldInitializerFromEvaluatorValue(field, fieldVar.getValue());
    }

    private Expression adjustInitializerForField(FieldDeclaration field, Expression initializer) {
        Expression adjusted = adjustStringPlaceholder(initializer);
        return owner.coerceInitializerForFieldType(field.getElementType(), adjusted);
    }

    private boolean canInstantiateFieldType(Type type) {
        if (type == null || type.isPrimitiveType() || TypeInspector.isCollectionOrMapFieldType(type)) {
            return false;
        }
        String raw = type.asString().replaceAll("<.*>", "").trim();
        if (raw.equals("String") || raw.startsWith("java.lang.")) {
            return false;
        }
        Optional<TypeDeclaration<?>> typeDeclarationOpt = seams.typeDeclarations().apply(owner.resolveFieldTypeName(type));
        if (typeDeclarationOpt.isPresent() && typeDeclarationOpt.get() instanceof ClassOrInterfaceDeclaration coid) {
            return !coid.isInterface() && !coid.isAbstract();
        }
        return false;
    }

    private Expression createEmptyObjectInitializer(Type type) {
        if (!canInstantiateFieldType(type)) {
            return null;
        }
        return StaticJavaParser.parseExpression("new " + owner.resolveFieldTypeName(type) + "()");
    }

    private Expression adjustStringPlaceholder(Expression initializer) {
        if (initializer instanceof StringLiteralExpr stringLiteral) {
            return new StringLiteralExpr(owner.coerceGeneratedStringPlaceholder(stringLiteral.getValue()));
        }
        return initializer;
    }

    private void mockFieldWithMockito(String nameAsString, Evaluator eval, FieldDeclaration field) {
        BlockStmt body = owner.getBody(owner.testMethod);
        String name = field.getVariable(0).getNameAsString();

        if (!doesFieldNeedMocking(eval, name)) {
            return;
        }
        Object value = eval.getField(name).getValue();
        if (value instanceof List) {
            TestGenerator.addImport(new ImportDeclaration(Reflect.JAVA_UTIL_LIST, false, false));
            body.addStatement(String.format("Mockito.when(%s.%s()).thenReturn(List.of());",
                    nameAsString,
                    JavaBeansConventions.getterMethodNameForField(field)
            ));
        } else {
            if (value instanceof String) {
                body.addStatement(String.format("Mockito.when(%s.%s()).thenReturn(\"%s\");",
                        nameAsString,
                        JavaBeansConventions.getterMethodNameForField(field), value));
            } else {
                body.addStatement(String.format("Mockito.when(%s.%s()).thenReturn(%s);",
                        nameAsString,
                        JavaBeansConventions.getterMethodNameForField(field),
                        value instanceof Long ? value + "L" : value.toString()));
            }
        }
    }
}
