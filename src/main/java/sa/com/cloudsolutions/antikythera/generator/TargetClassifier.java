package sa.com.cloudsolutions.antikythera.generator;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;
import sa.com.cloudsolutions.antikythera.parser.BaseRepositoryParser;
import sa.com.cloudsolutions.antikythera.parser.converter.EntityMappingResolver;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Classifies types discovered during full-project fallback mode for unit-test generation.
 * Rules are evaluated in a fixed order; see full-project-discovery-plan.md.
 */
public final class TargetClassifier {

    private static final Set<String> DATA_CARRIER_NAME_SUFFIXES = Set.of(
            "dto", "request", "response", "model", "vo", "payload", "form", "command", "event");

    private TargetClassifier() {
    }

    /**
     * Classifies a resolved type for fallback unit-test generation.
     *
     * @param tw type metadata from preprocessing (AST-backed types are fully supported)
     * @return classification result with decision and skip reason when applicable
     */
    public static ClassificationResult classify(TypeWrapper tw) {
        Objects.requireNonNull(tw, "type wrapper");
        TypeDeclaration<?> type = tw.getType();
        if (type == null) {
            return classifyBinaryOnly(tw);
        }
        if (type.isAnnotationDeclaration()) {
            return ClassificationResult.skip(SkipReason.ANNOTATION_TYPE, "Annotation type declaration");
        }
        if (type.isClassOrInterfaceDeclaration()) {
            return classifyClassOrInterface(tw, type.asClassOrInterfaceDeclaration());
        }
        if (type.isEnumDeclaration()) {
            return ClassificationResult.skip(SkipReason.ENUM, "Enum type");
        }
        if (type.isRecordDeclaration()) {
            return ClassificationResult.skip(SkipReason.RECORD, "Record type");
        }
        return ClassificationResult.skip(SkipReason.NO_TESTABLE_METHODS, "Unsupported type shape for classification");
    }

    /**
     * Applies {@code generator.yml} {@code include} / {@code skip} after automatic rules.
     * Precedence: automatic classification, then {@code include} (rescues from skip), then {@code skip}
     * (always wins).
     */
    public static ClassificationResult classifyForFallback(TypeWrapper tw) {
        ClassificationResult automatic = classify(tw);
        String fqn = tw.getFullyQualifiedName();
        ClassificationResult result = automatic;
        if (fqn != null && automatic.isSkip() && matchesIncludeSuffix(fqn)) {
            result = ClassificationResult.unitTarget("Matched generator.yml include list");
        }
        if (fqn != null && AbstractCompiler.matchesSkipPattern(fqn)) {
            return ClassificationResult.skip(SkipReason.USER_SKIP_LIST, "Matched generator.yml skip list");
        }
        return result;
    }

    private static boolean matchesIncludeSuffix(String fqn) {
        for (String inc : Settings.getPropertyList(Settings.INCLUDE, String.class)) {
            if (inc != null && fqn.endsWith(inc)) {
                return true;
            }
        }
        return false;
    }

    private static ClassificationResult classifyBinaryOnly(TypeWrapper tw) {
        if (tw.getClazz() == null) {
            return ClassificationResult.skip(SkipReason.NO_TESTABLE_METHODS, "No AST or binary class information");
        }
        Class<?> c = tw.getClazz();
        if (c.isAnnotation()) {
            return ClassificationResult.skip(SkipReason.ANNOTATION_TYPE, "Annotation type (binary)");
        }
        if (c.isInterface()) {
            if (hasRuntimeAnnotation(c, "org.springframework.cloud.openfeign.FeignClient")) {
                return ClassificationResult.skip(SkipReason.FEIGN_CLIENT, "Feign client interface (binary)");
            }
            return ClassificationResult.skip(SkipReason.INTERFACE, "Interface type (binary)");
        }
        if (c.isEnum()) {
            return ClassificationResult.skip(SkipReason.ENUM, "Enum type (binary)");
        }
        if (c.isRecord()) {
            return ClassificationResult.skip(SkipReason.RECORD, "Record type (binary)");
        }
        if ((c.getModifiers() & java.lang.reflect.Modifier.ABSTRACT) != 0) {
            return ClassificationResult.skip(SkipReason.ABSTRACT_CLASS, "Abstract class (binary)");
        }
        return ClassificationResult.unitTarget("Binary-only type; limited classification — verify in orchestration");
    }

    private static boolean hasRuntimeAnnotation(Class<?> c, String annotationFqn) {
        for (var a : c.getAnnotations()) {
            if (annotationFqn.equals(a.annotationType().getName())) {
                return true;
            }
        }
        return false;
    }

    private static ClassificationResult classifyClassOrInterface(TypeWrapper tw,
            ClassOrInterfaceDeclaration cdecl) {
        CompilationUnit cu = cdecl.findCompilationUnit().orElse(null);

        // --- 1. Structural (Feign before generic INTERFACE) ---
        if (cdecl.isInterface()) {
            if (cdecl.isAnnotationPresent("FeignClient")
                    || cdecl.isAnnotationPresent("org.springframework.cloud.openfeign.FeignClient")) {
                return ClassificationResult.skip(SkipReason.FEIGN_CLIENT, "Feign client interface");
            }
            if (BaseRepositoryParser.isJpaRepository(cdecl)) {
                return ClassificationResult.skip(SkipReason.SPRING_DATA_REPOSITORY, "Spring Data repository interface");
            }
            return ClassificationResult.skip(SkipReason.INTERFACE, "Interface type");
        }

        if (cdecl.isAbstract()) {
            return ClassificationResult.skip(SkipReason.ABSTRACT_CLASS, "Abstract class cannot be instantiated for tests");
        }

        if (cdecl.isNestedType() && cdecl.isPrivate()) {
            return ClassificationResult.skip(SkipReason.PRIVATE_INNER_CLASS, "Private nested type not reachable from tests");
        }

        // --- 2. Spring MVC / web ---
        if (tw.isController()) {
            return ClassificationResult.skip(SkipReason.CONTROLLER, "Web controller");
        }
        if (cdecl.isAnnotationPresent("ControllerAdvice")
                || cdecl.isAnnotationPresent("RestControllerAdvice")
                || cdecl.isAnnotationPresent("org.springframework.web.bind.annotation.ControllerAdvice")
                || cdecl.isAnnotationPresent("org.springframework.web.bind.annotation.RestControllerAdvice")) {
            return ClassificationResult.skip(SkipReason.CONTROLLER_ADVICE, "Controller advice");
        }
        if (extendsResponseEntityExceptionHandler(cdecl)) {
            return ClassificationResult.skip(SkipReason.CONTROLLER_ADVICE, "Extends ResponseEntityExceptionHandler");
        }

        // --- 3. Persistence ---
        if (EntityMappingResolver.isEntity(tw)) {
            return ClassificationResult.skip(SkipReason.ENTITY, "JPA entity");
        }
        if (cdecl.isAnnotationPresent("Embeddable")
                || cdecl.isAnnotationPresent("jakarta.persistence.Embeddable")
                || cdecl.isAnnotationPresent("javax.persistence.Embeddable")) {
            return ClassificationResult.skip(SkipReason.EMBEDDABLE, "JPA embeddable");
        }
        if (cdecl.isAnnotationPresent("MappedSuperclass")
                || cdecl.isAnnotationPresent("jakarta.persistence.MappedSuperclass")
                || cdecl.isAnnotationPresent("javax.persistence.MappedSuperclass")) {
            return ClassificationResult.skip(SkipReason.MAPPED_SUPERCLASS, "JPA mapped superclass");
        }
        if (cdecl.isAnnotationPresent("IdClass")
                || cdecl.isAnnotationPresent("jakarta.persistence.IdClass")
                || cdecl.isAnnotationPresent("javax.persistence.IdClass")) {
            return ClassificationResult.skip(SkipReason.ID_CLASS, "JPA IdClass");
        }

        if (isPureSpringDataRepositoryStub(cdecl, cu)) {
            return ClassificationResult.skip(SkipReason.SPRING_DATA_REPOSITORY,
                    "Spring Data repository stub with no custom implementation methods");
        }

        // --- 4. Application wiring ---
        if (cdecl.isAnnotationPresent("SpringBootApplication")
                || cdecl.isAnnotationPresent("org.springframework.boot.autoconfigure.SpringBootApplication")) {
            return ClassificationResult.skip(SkipReason.SPRING_BOOT_APPLICATION, "Spring Boot application entry");
        }
        if (tw.isConfiguration()) {
            return ClassificationResult.skip(SkipReason.CONFIGURATION, "Configuration or configuration properties");
        }
        if (hasPublicStaticMain(cdecl)) {
            return ClassificationResult.skip(SkipReason.MAIN_CLASS, "Application entry point (public static void main)");
        }

        // --- 5. AOP ---
        if (cdecl.isAnnotationPresent("Aspect") || cdecl.isAnnotationPresent("org.aspectj.lang.annotation.Aspect")) {
            return ClassificationResult.skip(SkipReason.AOP_ASPECT, "Aspect");
        }

        // --- 6–8. Constants, exceptions, data carriers ---
        if (isConstantOnlyHolder(cdecl)) {
            return ClassificationResult.skip(SkipReason.CONSTANT_CLASS, "Only constants or passive static members");
        }
        if (isPureExceptionClass(cdecl)) {
            return ClassificationResult.skip(SkipReason.EXCEPTION_CLASS, "Throwable subtype with constructors/getters only");
        }

        if (hasNonBoilerplateMethod(cdecl)) {
            return ClassificationResult.unitTarget("Contains executable logic beyond boilerplate accessors");
        }

        if (hasLombokDataCarrierSignal(cdecl)) {
            return ClassificationResult.skip(SkipReason.DATA_CARRIER_BY_ANNOTATION, "Lombok data/value carrier");
        }
        if (matchesDataCarrierNameHeuristic(cdecl.getNameAsString())) {
            return ClassificationResult.skip(SkipReason.DATA_CARRIER_BY_NAME, "Naming pattern suggests DTO/model carrier");
        }
        if (cdecl.getMethods().stream().allMatch(TargetClassifier::isBoilerplateMethod)
                && cdecl.getConstructors().stream().allMatch(TargetClassifier::isTrivialConstructor)) {
            return ClassificationResult.skip(SkipReason.DATA_CARRIER_BY_STRUCTURE, "Only boilerplate accessors and trivial constructors");
        }

        return ClassificationResult.skip(SkipReason.NO_TESTABLE_METHODS, "No testable instance logic found");
    }

    private static boolean extendsResponseEntityExceptionHandler(ClassOrInterfaceDeclaration cdecl) {
        for (ClassOrInterfaceType et : cdecl.getExtendedTypes()) {
            if ("ResponseEntityExceptionHandler".equals(et.getNameAsString())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPureSpringDataRepositoryStub(ClassOrInterfaceDeclaration cdecl, CompilationUnit cu) {
        if (!twIsRepositoryAnnotation(cdecl)) {
            return false;
        }
        if (!implementsSpringDataRepositoryInterface(cdecl, cu)) {
            return false;
        }
        return cdecl.getMethods().isEmpty();
    }

    private static boolean twIsRepositoryAnnotation(ClassOrInterfaceDeclaration cdecl) {
        return cdecl.isAnnotationPresent("Repository")
                || cdecl.isAnnotationPresent("org.springframework.stereotype.Repository");
    }

    private static boolean implementsSpringDataRepositoryInterface(ClassOrInterfaceDeclaration cdecl,
            CompilationUnit cu) {
        if (cu == null) {
            return false;
        }
        for (ClassOrInterfaceType impl : cdecl.getImplementedTypes()) {
            String fqn = AbstractCompiler.findFullyQualifiedName(cu, impl);
            if (fqn == null) {
                continue;
            }
            Optional<TypeDeclaration<?>> iface = AntikytheraRunTime.getTypeDeclaration(fqn);
            if (iface.isPresent() && BaseRepositoryParser.isJpaRepository(iface.get())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasPublicStaticMain(ClassOrInterfaceDeclaration cdecl) {
        for (MethodDeclaration m : cdecl.getMethods()) {
            if (!m.isPublic() || !m.isStatic()) {
                continue;
            }
            if (!"main".equals(m.getNameAsString())) {
                continue;
            }
            if (m.getParameters().size() != 1) {
                continue;
            }
            String ptype = m.getParameter(0).getType().asString();
            if (ptype.endsWith("String[]") || ptype.endsWith("java.lang.String[]")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isConstantOnlyHolder(ClassOrInterfaceDeclaration cdecl) {
        if (extendsThrowable(cdecl)) {
            return false;
        }
        boolean hasInstanceField = cdecl.getFields().stream()
                .anyMatch(f -> !f.isStatic());
        if (hasInstanceField) {
            return false;
        }
        boolean hasNonStaticMethod = cdecl.getMethods().stream().anyMatch(m -> !m.isStatic());
        if (hasNonStaticMethod) {
            return false;
        }
        return !cdecl.getFields().isEmpty() || !cdecl.getMethods().isEmpty() || !cdecl.getConstructors().isEmpty();
    }

    private static boolean isPureExceptionClass(ClassOrInterfaceDeclaration cdecl) {
        if (!extendsThrowable(cdecl)) {
            return false;
        }
        for (MethodDeclaration m : cdecl.getMethods()) {
            if (!isLikelyExceptionAccessor(m)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Uses simple supertype names only so classification does not depend on {@link Settings}
     * or import resolution during standalone tests.
     */
    private static boolean extendsThrowable(ClassOrInterfaceDeclaration cdecl) {
        for (ClassOrInterfaceType et : cdecl.getExtendedTypes()) {
            String name = et.getNameAsString();
            if ("Throwable".equals(name) || name.endsWith("Exception") || name.endsWith("Error")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLikelyExceptionAccessor(MethodDeclaration m) {
        String n = m.getNameAsString();
        return n.equals("getCause") || n.startsWith("get") || n.startsWith("is");
    }

    private static boolean hasLombokDataCarrierSignal(ClassOrInterfaceDeclaration cdecl) {
        return cdecl.getAnnotationByName("Data").isPresent()
                || cdecl.getAnnotationByName("Value").isPresent()
                || cdecl.getAnnotationByName("lombok.Data").isPresent()
                || cdecl.getAnnotationByName("lombok.Value").isPresent();
    }

    private static boolean matchesDataCarrierNameHeuristic(String simpleName) {
        String lower = simpleName.toLowerCase(Locale.ROOT);
        for (String suffix : DATA_CARRIER_NAME_SUFFIXES) {
            if (lower.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasNonBoilerplateMethod(ClassOrInterfaceDeclaration cdecl) {
        for (MethodDeclaration m : cdecl.getMethods()) {
            if (m.isAbstract()) {
                continue;
            }
            if (!isBoilerplateMethod(m)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBoilerplateMethod(MethodDeclaration m) {
        String name = m.getNameAsString();
        if ("equals".equals(name) || "hashCode".equals(name) || "toString".equals(name) || "canEqual".equals(name)) {
            return true;
        }
        if (name.startsWith("get") || name.startsWith("is")) {
            return isSimpleGetterShape(m);
        }
        if (name.startsWith("set")) {
            return isSimpleSetterShape(m);
        }
        return false;
    }

    private static boolean isSimpleGetterShape(MethodDeclaration m) {
        if (m.getBody().isEmpty()) {
            return false;
        }
        var stmts = m.getBody().get().getStatements();
        if (stmts.size() != 1 || !stmts.get(0).isReturnStmt()) {
            return false;
        }
        Optional<Expression> expr = stmts.get(0).asReturnStmt().getExpression();
        if (expr.isEmpty()) {
            return false;
        }
        Expression e = expr.get();
        return e.isNameExpr() || (e.isFieldAccessExpr() && "this".equals(e.asFieldAccessExpr().getScope().toString()));
    }

    private static boolean isSimpleSetterShape(MethodDeclaration m) {
        if (m.getParameters().size() != 1) {
            return false;
        }
        if (m.getBody().isEmpty()) {
            return false;
        }
        var stmts = m.getBody().get().getStatements();
        if (stmts.size() != 1) {
            return false;
        }
        Statement st = stmts.get(0);
        return st.isExpressionStmt() && st.asExpressionStmt().getExpression().isAssignExpr();
    }

    private static boolean isTrivialConstructor(ConstructorDeclaration c) {
        BlockStmt body = c.getBody();
        if (body == null) {
            return true;
        }
        for (Statement st : body.getStatements()) {
            if (st.isExplicitConstructorInvocationStmt()) {
                continue;
            }
            if (st.isExpressionStmt() && st.asExpressionStmt().getExpression().isAssignExpr()) {
                continue;
            }
            return false;
        }
        return true;
    }
}
