package sa.com.cloudsolutions.antikythera.generator;

import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.type.PrimitiveType;
import com.github.javaparser.ast.type.Type;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

/**
 * JavaBeans-style accessor naming used when generating Mockito stubs and setter calls.
 */
public final class JavaBeansConventions {

    private JavaBeansConventions() {
    }

    /**
     * Resolves the setter name declared on {@code owner} when possible, otherwise the conventional name.
     */
    public static String setterNameForField(TypeDeclaration<?> owner, FieldDeclaration field) {
        String fieldName = field.getVariable(0).getNameAsString();
        String standardSetter = "set" + AbstractCompiler.instanceToClassName(fieldName);
        String booleanStyleSetter = "set" + AbstractCompiler.setterSuffixFromFieldName(fieldName);
        if (owner != null && owner.getMethodsByName(standardSetter).stream().anyMatch(md -> md.getParameters().size() == 1)) {
            return standardSetter;
        }
        if (owner != null && owner.getMethodsByName(booleanStyleSetter).stream().anyMatch(md -> md.getParameters().size() == 1)) {
            return booleanStyleSetter;
        }
        Type fieldType = field.getElementType();
        if (fieldType.isPrimitiveType() && fieldType.asPrimitiveType().getType() == PrimitiveType.Primitive.BOOLEAN) {
            return booleanStyleSetter;
        }
        if (fieldName.startsWith("is") && fieldName.length() > 2 && Character.isUpperCase(fieldName.charAt(2))) {
            return standardSetter;
        }
        return standardSetter;
    }

    /**
     * Getter name for Mockito stubs: {@code isActive()} for primitive {@code boolean isActive},
     * {@code getIsActive()} for {@code Boolean isActive}, {@code getFoo()} otherwise.
     */
    public static String getterMethodNameForField(FieldDeclaration field) {
        String fieldName = field.getVariable(0).getNameAsString();
        Type t = field.getElementType();
        if (t.isPrimitiveType() && t.asPrimitiveType().getType() == PrimitiveType.Primitive.BOOLEAN) {
            if (fieldName.startsWith("is") && fieldName.length() > 2 && Character.isUpperCase(fieldName.charAt(2))) {
                return fieldName;
            }
            return "is" + AbstractCompiler.instanceToClassName(fieldName);
        }
        return "get" + AbstractCompiler.instanceToClassName(fieldName);
    }
}
