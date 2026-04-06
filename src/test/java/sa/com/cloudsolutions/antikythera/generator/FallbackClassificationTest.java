package sa.com.cloudsolutions.antikythera.generator;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.io.File;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FallbackClassificationTest {

    @BeforeEach
    void loadConfig() throws IOException {
        Settings.loadConfigMap(new File("src/test/resources/generator.yml"));
    }

    @AfterEach
    void clearOverrides() {
        Settings.setProperty("skip", List.of());
        Settings.setProperty(Settings.INCLUDE, List.of());
    }

    private static TypeWrapper wrap(ClassOrInterfaceDeclaration t) {
        TypeWrapper tw = new TypeWrapper(t);
        AbstractCompiler.populateTypeMetadata(t, tw);
        tw.setInterface(t.isInterface());
        return tw;
    }

    @Test
    void skipListOverridesAutomaticUnitTarget() {
        CompilationUnit cu = StaticJavaParser.parse("""
                package com.example;
                public class MyService {
                    public int work() { return 1; }
                }
                """);
        ClassOrInterfaceDeclaration t = cu.getType(0).asClassOrInterfaceDeclaration();
        t.addAnnotation("Service");
        TypeWrapper tw = wrap(t);
        Settings.setProperty("skip", List.of("com.example.MyService"));
        ClassificationResult r = TargetClassifier.classifyForFallback(tw);
        assertEquals(SkipReason.USER_SKIP_LIST, r.reason());
    }

    @Test
    void includeListRescuesFromAutomaticSkip() {
        CompilationUnit cu = StaticJavaParser.parse("""
                public interface Marker { }
                """);
        ClassOrInterfaceDeclaration t = cu.getType(0).asClassOrInterfaceDeclaration();
        TypeWrapper tw = wrap(t);
        Settings.setProperty(Settings.INCLUDE, List.of("Marker"));
        ClassificationResult r = TargetClassifier.classifyForFallback(tw);
        assertTrue(r.isUnitTarget());
    }

    @Test
    void skipWinsOverIncludeWhenBothMatch() {
        CompilationUnit cu = StaticJavaParser.parse("""
                public interface Marker { }
                """);
        ClassOrInterfaceDeclaration t = cu.getType(0).asClassOrInterfaceDeclaration();
        TypeWrapper tw = wrap(t);
        Settings.setProperty(Settings.INCLUDE, List.of("Marker"));
        Settings.setProperty("skip", List.of("Marker"));
        ClassificationResult r = TargetClassifier.classifyForFallback(tw);
        assertEquals(SkipReason.USER_SKIP_LIST, r.reason());
    }

    @Test
    void antikythera_fallbackMode_falseWhenServicesConfigured() {
        assertFalse(Antikythera.isFallbackMode());
    }

    @Test
    void antikythera_fallbackMode_trueWhenBothListsEmpty() throws IOException {
        Settings.loadConfigMap(new File("src/test/resources/generator-fallback-empty.yml"));
        assertTrue(Antikythera.isFallbackMode());
    }
}
