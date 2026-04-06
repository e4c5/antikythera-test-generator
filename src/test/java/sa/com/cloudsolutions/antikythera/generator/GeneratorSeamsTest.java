package sa.com.cloudsolutions.antikythera.generator;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.io.File;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GeneratorSeamsTest {

    @BeforeAll
    static void beforeClass() throws IOException {
        Settings.loadConfigMap(new File("src/test/resources/generator.yml"));
        AbstractCompiler.reset();
        AbstractCompiler.preProcess();
    }

    @Test
    void defaultsIsNonNull() {
        assertNotNull(GeneratorSeams.defaults());
    }

    @Test
    void unitTestGeneratorConstructorAcceptsCustomSeams() {
        GeneratorSeams seams = new GeneratorSeams(AntikytheraRunTime::getTypeDeclaration, s -> false);
        var cu = AntikytheraRunTime.getCompilationUnit("sa.com.cloudsolutions.service.PersonService");
        assertNotNull(cu);
        UnitTestGenerator utg = new UnitTestGenerator(cu, seams);
        assertNotNull(utg);
    }
}
