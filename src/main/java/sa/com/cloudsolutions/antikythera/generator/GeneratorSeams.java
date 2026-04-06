package sa.com.cloudsolutions.antikythera.generator;

import com.github.javaparser.ast.body.TypeDeclaration;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;

import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Optional constructor dependencies for {@link UnitTestGenerator} so tests (or tooling) can stub
 * type resolution and plain-mock naming without static calls. Production code uses {@link #defaults()}.
 */
public record GeneratorSeams(
        Function<String, Optional<TypeDeclaration<?>>> typeDeclarations,
        Predicate<String> plainMockDependencySimpleNames
) {
    private static boolean defaultPlainMockListed(String simpleTypeName) {
        return Settings.getPropertyList(Settings.PLAIN_MOCK_DEPENDENCY_SIMPLE_NAMES, String.class).stream()
                .anyMatch(simpleTypeName::equals);
    }

    public static GeneratorSeams defaults() {
        return new GeneratorSeams(AntikytheraRunTime::getTypeDeclaration, GeneratorSeams::defaultPlainMockListed);
    }
}
