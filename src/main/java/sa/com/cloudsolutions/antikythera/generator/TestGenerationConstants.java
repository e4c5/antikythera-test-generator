package sa.com.cloudsolutions.antikythera.generator;

import sa.com.cloudsolutions.antikythera.evaluator.Reflect;

import java.nio.file.Path;

/**
 * Shared literals for paths, annotation simple names, and placeholders used when emitting tests.
 */
public final class TestGenerationConstants {

    private TestGenerationConstants() {
    }

    public static final String MAVEN_MAIN_JAVA_PATH = "src/main/java";
    public static final String MAVEN_TEST_JAVA_PATH = "src/test/java";

    /**
     * Under a Maven {@code src} directory, the relative path to Java test sources
     * ({@code src/test/java} without the leading {@code src/}).
     */
    public static final Path MAVEN_TEST_JAVA_UNDER_SRC = Path.of("test", "java");

    public static final String MOCK_SIMPLE_NAME = "Mock";
    public static final String MOCK_BEAN_SIMPLE_NAME = "MockBean";
    public static final String INJECT_MOCKS_SIMPLE_NAME = "InjectMocks";

    public static final String REFLECTION_TEST_UTILS_FQN = "org.springframework.test.util.ReflectionTestUtils";
    public static final String REFLECTION_TEST_UTILS_SIMPLE_NAME = "ReflectionTestUtils";

    /** Marker in generated method comments (spaced, matches {@link TestGenerator} output). */
    public static final String GENERATED_COMMENT_AUTHOR_SPACED = "Author : Antikythera";

    /** Marker when scanning existing generated sources for Antikythera-owned methods. */
    public static final String GENERATED_COMMENT_AUTHOR_COMPACT = "Author: Antikythera";

    /** String placeholder produced by the evaluator, coerced in generated tests when needed. */
    public static final String EVALUATOR_STRING_PLACEHOLDER = Reflect.ANTIKYTHERA;

    public static final String NUMERIC_PLACEHOLDER = "0";

    /** Simple name used in {@code List.of(...)} factory calls in parsed expressions. */
    public static final String LIST_FACTORY_SCOPE = "List";

    /** Simple name used in {@code Set.of(...)} factory calls in parsed expressions. */
    public static final String SET_FACTORY_SCOPE = "Set";
}
