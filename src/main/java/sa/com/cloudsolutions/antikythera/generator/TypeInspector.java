package sa.com.cloudsolutions.antikythera.generator;

import com.github.javaparser.ast.type.Type;

/**
 * Erased simple-name checks for JavaParser {@link Type} nodes used during test generation.
 */
public final class TypeInspector {

    private TypeInspector() {
    }

    public static String rawSimpleName(Type type) {
        return type.asString().replaceAll("<.*>", "").trim();
    }

    /**
     * Collection types used when matching method parameters for exception-path collection seeding.
     */
    public static boolean isCollectionParameterType(Type type) {
        if (!type.isClassOrInterfaceType()) {
            return false;
        }
        String raw = rawSimpleName(type);
        return raw.equals("List") || raw.equals("ArrayList") || raw.equals("Collection")
                || raw.equals("Set") || raw.equals("HashSet");
    }

    /**
     * Collection and map types for field mocking and dependency setup.
     */
    public static boolean isCollectionOrMapFieldType(Type type) {
        String raw = rawSimpleName(type);
        return raw.equals("List") || raw.equals("ArrayList") || raw.equals("LinkedList")
                || raw.equals("Set") || raw.equals("HashSet") || raw.equals("LinkedHashSet")
                || raw.equals("Collection") || raw.equals("Map") || raw.equals("HashMap")
                || raw.equals("LinkedHashMap");
    }

    public static boolean isMapType(Type type) {
        if (!type.isClassOrInterfaceType()) {
            return false;
        }
        String raw = rawSimpleName(type);
        return raw.equals("Map") || raw.equals("HashMap") || raw.equals("LinkedHashMap");
    }

    /**
     * True for {@code new X<>()} where {@code X} is treated as an empty collection constructor
     * in {@link CollectionExpressionAnalyzer}.
     */
    public static boolean isLikelyConcreteEmptyCollectionType(Type type) {
        if (!type.isClassOrInterfaceType()) {
            return false;
        }
        String simple = type.asClassOrInterfaceType().getNameAsString();
        if (simple.contains("ArrayList") || simple.contains("LinkedList") || simple.contains("HashSet")
                || simple.contains("TreeSet") || simple.contains("LinkedHashSet")) {
            return true;
        }
        String raw = rawSimpleName(type);
        return raw.equals("List") || raw.equals("ArrayList") || raw.equals("Collection")
                || raw.equals("Set") || raw.equals("HashSet");
    }
}
