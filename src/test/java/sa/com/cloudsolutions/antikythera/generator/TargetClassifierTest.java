package sa.com.cloudsolutions.antikythera.generator;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TargetClassifierTest {

    @BeforeEach
    void setUp() {
        ParserConfiguration config = new ParserConfiguration();
        config.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        StaticJavaParser.setConfiguration(config);
        AntikytheraRunTime.resetAll();
    }

    @AfterEach
    void tearDown() {
        AntikytheraRunTime.resetAll();
    }

    private static TypeWrapper wrap(TypeDeclaration<?> type) {
        TypeWrapper tw = new TypeWrapper(type);
        AbstractCompiler.populateTypeMetadata(type, tw);
        if (type.isClassOrInterfaceDeclaration()) {
            tw.setInterface(type.asClassOrInterfaceDeclaration().isInterface());
        }
        return tw;
    }

    @Test
    void serviceWithLogic_isUnitTarget() {
        CompilationUnit cu = StaticJavaParser.parse("""
                public class OrderService {
                    public int total() { return 1; }
                }
                """);
        ClassOrInterfaceDeclaration t = cu.getType(0).asClassOrInterfaceDeclaration();
        t.addAnnotation("Service");
        TypeWrapper tw = wrap(t);
        ClassificationResult r = TargetClassifier.classify(tw);
        assertTrue(r.isUnitTarget());
    }

    @Test
    void componentWithLogic_isUnitTarget() {
        CompilationUnit cu = StaticJavaParser.parse("""
                public class Helper {
                    public void run() { }
                }
                """);
        ClassOrInterfaceDeclaration t = cu.getType(0).asClassOrInterfaceDeclaration();
        t.addAnnotation("Component");
        TypeWrapper tw = wrap(t);
        ClassificationResult r = TargetClassifier.classify(tw);
        assertTrue(r.isUnitTarget());
    }

    @Test
    void restController_isSkipped() {
        CompilationUnit cu = StaticJavaParser.parse("""
                public class Api {
                    public void x() { }
                }
                """);
        ClassOrInterfaceDeclaration t = cu.getType(0).asClassOrInterfaceDeclaration();
        t.addAnnotation("RestController");
        TypeWrapper tw = wrap(t);
        ClassificationResult r = TargetClassifier.classify(tw);
        assertEquals(SkipReason.CONTROLLER, r.reason());
    }

    @Test
    void controllerAdvice_isSkipped() {
        CompilationUnit cu = StaticJavaParser.parse("""
                public class Adv { }
                """);
        ClassOrInterfaceDeclaration t = cu.getType(0).asClassOrInterfaceDeclaration();
        t.addAnnotation("ControllerAdvice");
        TypeWrapper tw = wrap(t);
        ClassificationResult r = TargetClassifier.classify(tw);
        assertEquals(SkipReason.CONTROLLER_ADVICE, r.reason());
    }

    @Test
    void entity_isSkipped() {
        CompilationUnit cu = StaticJavaParser.parse("""
                @Entity
                public class E {
                    private Long id;
                }
                """);
        ClassOrInterfaceDeclaration t = cu.getType(0).asClassOrInterfaceDeclaration();
        TypeWrapper tw = wrap(t);
        ClassificationResult r = TargetClassifier.classify(tw);
        assertEquals(SkipReason.ENTITY, r.reason());
    }

    @Test
    void embeddable_isSkipped() {
        CompilationUnit cu = StaticJavaParser.parse("""
                @Embeddable
                public class Address { }
                """);
        ClassOrInterfaceDeclaration t = cu.getType(0).asClassOrInterfaceDeclaration();
        TypeWrapper tw = wrap(t);
        ClassificationResult r = TargetClassifier.classify(tw);
        assertEquals(SkipReason.EMBEDDABLE, r.reason());
    }

    @Test
    void mappedSuperclass_isSkipped() {
        CompilationUnit cu = StaticJavaParser.parse("""
                @MappedSuperclass
                public class Base { }
                """);
        ClassOrInterfaceDeclaration t = cu.getType(0).asClassOrInterfaceDeclaration();
        TypeWrapper tw = wrap(t);
        ClassificationResult r = TargetClassifier.classify(tw);
        assertEquals(SkipReason.MAPPED_SUPERCLASS, r.reason());
    }

    @Test
    void jpaRepositoryInterface_isSkipped() {
        CompilationUnit cu = StaticJavaParser.parse("""
                public interface UserRepository extends org.springframework.data.jpa.repository.JpaRepository<Object, Long> {
                }
                """);
        ClassOrInterfaceDeclaration t = cu.getType(0).asClassOrInterfaceDeclaration();
        TypeWrapper tw = wrap(t);
        ClassificationResult r = TargetClassifier.classify(tw);
        assertEquals(SkipReason.SPRING_DATA_REPOSITORY, r.reason());
    }

    @Test
    void configuration_isSkipped() {
        CompilationUnit cu = StaticJavaParser.parse("""
                @Configuration
                public class Cfg { }
                """);
        ClassOrInterfaceDeclaration t = cu.getType(0).asClassOrInterfaceDeclaration();
        TypeWrapper tw = wrap(t);
        ClassificationResult r = TargetClassifier.classify(tw);
        assertEquals(SkipReason.CONFIGURATION, r.reason());
    }

    @Test
    void springBootApplication_isSkipped() {
        CompilationUnit cu = StaticJavaParser.parse("""
                @SpringBootApplication
                public class App { }
                """);
        ClassOrInterfaceDeclaration t = cu.getType(0).asClassOrInterfaceDeclaration();
        TypeWrapper tw = wrap(t);
        ClassificationResult r = TargetClassifier.classify(tw);
        assertEquals(SkipReason.SPRING_BOOT_APPLICATION, r.reason());
    }

    @Test
    void aspect_isSkipped() {
        CompilationUnit cu = StaticJavaParser.parse("""
                @Aspect
                public class LoggingAspect { }
                """);
        ClassOrInterfaceDeclaration t = cu.getType(0).asClassOrInterfaceDeclaration();
        TypeWrapper tw = wrap(t);
        ClassificationResult r = TargetClassifier.classify(tw);
        assertEquals(SkipReason.AOP_ASPECT, r.reason());
    }

    @Test
    void annotationTypeDeclaration_isSkipped() {
        CompilationUnit cu = StaticJavaParser.parse("""
                public @interface Tag {
                    String value() default "";
                }
                """);
        var t = cu.getType(0);
        TypeWrapper tw = wrap(t);
        ClassificationResult r = TargetClassifier.classify(tw);
        assertEquals(SkipReason.ANNOTATION_TYPE, r.reason());
    }

    @Test
    void privateInnerClass_isSkipped() {
        CompilationUnit cu = StaticJavaParser.parse("""
                public class Outer {
                    private class Inner {
                        public void m() { }
                    }
                }
                """);
        ClassOrInterfaceDeclaration outer = cu.getType(0).asClassOrInterfaceDeclaration();
        ClassOrInterfaceDeclaration inner = outer.getMembers().stream()
                .filter(ClassOrInterfaceDeclaration.class::isInstance)
                .map(ClassOrInterfaceDeclaration.class::cast)
                .findFirst()
                .orElseThrow();
        TypeWrapper tw = wrap(inner);
        ClassificationResult r = TargetClassifier.classify(tw);
        assertEquals(SkipReason.PRIVATE_INNER_CLASS, r.reason());
    }

    @Test
    void plainInterface_isSkipped() {
        CompilationUnit cu = StaticJavaParser.parse("""
                public interface Marker { }
                """);
        ClassOrInterfaceDeclaration t = cu.getType(0).asClassOrInterfaceDeclaration();
        TypeWrapper tw = wrap(t);
        ClassificationResult r = TargetClassifier.classify(tw);
        assertEquals(SkipReason.INTERFACE, r.reason());
    }

    @Test
    void enum_isSkipped() {
        CompilationUnit cu = StaticJavaParser.parse("""
                public enum Color { RED }
                """);
        TypeDeclaration<?> t = cu.getType(0);
        TypeWrapper tw = wrap(t);
        ClassificationResult r = TargetClassifier.classify(tw);
        assertEquals(SkipReason.ENUM, r.reason());
    }

    @Test
    void record_isSkipped() {
        CompilationUnit cu = StaticJavaParser.parse("""
                public record Point(int x, int y) { }
                """);
        TypeDeclaration<?> t = cu.getType(0);
        TypeWrapper tw = wrap(t);
        ClassificationResult r = TargetClassifier.classify(tw);
        assertEquals(SkipReason.RECORD, r.reason());
    }

    @Test
    void abstractClass_isSkipped() {
        CompilationUnit cu = StaticJavaParser.parse("""
                public abstract class Base { }
                """);
        ClassOrInterfaceDeclaration t = cu.getType(0).asClassOrInterfaceDeclaration();
        TypeWrapper tw = wrap(t);
        ClassificationResult r = TargetClassifier.classify(tw);
        assertEquals(SkipReason.ABSTRACT_CLASS, r.reason());
    }

    @Test
    void lombokDataCarrier_isSkipped() {
        CompilationUnit cu = StaticJavaParser.parse("""
                @Data
                public class Row {
                    private String a;
                }
                """);
        ClassOrInterfaceDeclaration t = cu.getType(0).asClassOrInterfaceDeclaration();
        TypeWrapper tw = wrap(t);
        ClassificationResult r = TargetClassifier.classify(tw);
        assertEquals(SkipReason.DATA_CARRIER_BY_ANNOTATION, r.reason());
    }

    @Test
    void onlyAccessors_isSkippedByStructure() {
        CompilationUnit cu = StaticJavaParser.parse("""
                public class Plain {
                    private int x;
                    public int getX() { return x; }
                    public void setX(int v) { this.x = v; }
                }
                """);
        ClassOrInterfaceDeclaration t = cu.getType(0).asClassOrInterfaceDeclaration();
        TypeWrapper tw = wrap(t);
        ClassificationResult r = TargetClassifier.classify(tw);
        assertEquals(SkipReason.DATA_CARRIER_BY_STRUCTURE, r.reason());
    }

    @Test
    void runtimeExceptionConstructorsOnly_isSkipped() {
        CompilationUnit cu = StaticJavaParser.parse("""
                public class BizException extends RuntimeException {
                    public BizException() {}
                    public BizException(String m) { super(m); }
                }
                """);
        ClassOrInterfaceDeclaration t = cu.getType(0).asClassOrInterfaceDeclaration();
        TypeWrapper tw = wrap(t);
        ClassificationResult r = TargetClassifier.classify(tw);
        assertEquals(SkipReason.EXCEPTION_CLASS, r.reason());
    }

    @Test
    void dtoNameWithRealLogic_isUnitTarget() {
        CompilationUnit cu = StaticJavaParser.parse("""
                public class ProblemDto {
                    public int compute() {
                        return 42;
                    }
                }
                """);
        ClassOrInterfaceDeclaration t = cu.getType(0).asClassOrInterfaceDeclaration();
        TypeWrapper tw = wrap(t);
        ClassificationResult r = TargetClassifier.classify(tw);
        assertTrue(r.isUnitTarget());
    }

    @Test
    void feignClientInterface_isFeignReason() {
        CompilationUnit cu = StaticJavaParser.parse("""
                @FeignClient(name = "x")
                public interface RemoteApi {
                    void call();
                }
                """);
        ClassOrInterfaceDeclaration t = cu.getType(0).asClassOrInterfaceDeclaration();
        TypeWrapper tw = wrap(t);
        ClassificationResult r = TargetClassifier.classify(tw);
        assertEquals(SkipReason.FEIGN_CLIENT, r.reason());
    }

    @Test
    void idClassAnnotation_isSkipped() {
        CompilationUnit cu = StaticJavaParser.parse("""
                @IdClass
                public class CompositeId {
                    private Long a;
                    private Long b;
                }
                """);
        ClassOrInterfaceDeclaration t = cu.getType(0).asClassOrInterfaceDeclaration();
        TypeWrapper tw = wrap(t);
        ClassificationResult r = TargetClassifier.classify(tw);
        assertEquals(SkipReason.ID_CLASS, r.reason());
    }

    @Test
    void publicStaticMain_isSkipped() {
        CompilationUnit cu = StaticJavaParser.parse("""
                public class Launcher {
                    public static void main(String[] args) { }
                }
                """);
        ClassOrInterfaceDeclaration t = cu.getType(0).asClassOrInterfaceDeclaration();
        TypeWrapper tw = wrap(t);
        ClassificationResult r = TargetClassifier.classify(tw);
        assertEquals(SkipReason.MAIN_CLASS, r.reason());
    }

    @Test
    void repositoryImplWithEntityManager_isUnitTarget() {
        CompilationUnit cu = StaticJavaParser.parse("""
                import jakarta.persistence.EntityManager;
                @Repository
                public class CustomRepo {
                    private EntityManager em;
                    public void runNative() {
                        em.createNativeQuery("select 1").executeUpdate();
                    }
                }
                """);
        ClassOrInterfaceDeclaration t = cu.getType(0).asClassOrInterfaceDeclaration();
        TypeWrapper tw = wrap(t);
        ClassificationResult r = TargetClassifier.classify(tw);
        assertTrue(r.isUnitTarget());
    }

    @Test
    void emptyRepositoryBeanImplementingJpaInterface_isSpringDataStub() {
        CompilationUnit ifaceCu = StaticJavaParser.parse("""
                package repo;
                public interface UserRepository extends org.springframework.data.jpa.repository.JpaRepository<Object, Long> {
                }
                """);
        ClassOrInterfaceDeclaration iface = ifaceCu.getType(0).asClassOrInterfaceDeclaration();
        TypeWrapper ifaceTw = new TypeWrapper(iface);
        ifaceTw.setInterface(true);
        AbstractCompiler.populateTypeMetadata(iface, ifaceTw);
        AntikytheraRunTime.addCompilationUnit("repo.UserRepository", ifaceCu);
        AntikytheraRunTime.addType("repo.UserRepository", ifaceTw);

        CompilationUnit implCu = StaticJavaParser.parse("""
                package repo;
                @Repository
                public class UserRepositoryBean implements UserRepository {
                }
                """);
        ClassOrInterfaceDeclaration impl = implCu.getType(0).asClassOrInterfaceDeclaration();
        TypeWrapper tw = new TypeWrapper(impl);
        AbstractCompiler.populateTypeMetadata(impl, tw);
        ClassificationResult r = TargetClassifier.classify(tw);
        assertEquals(SkipReason.SPRING_DATA_REPOSITORY, r.reason());
    }
}
