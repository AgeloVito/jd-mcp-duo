package support;

import archive.ClassLocation;
import archive.InputContainer;
import archive.InputContainers;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class AsmSupport {
    private AsmSupport() {
    }

    public static ClassNode readClassNode(InputContainer container, String classNameOrInternal) throws IOException {
        ClassLocation location = container.resolveClass(classNameOrInternal);
        if (location == null) {
            throw new IOException("Class not found: " + classNameOrInternal);
        }
        byte[] bytes = container.loadClassBytes(location.internalName());
        if (bytes == null) {
            throw new IOException("Failed to load class bytes: " + classNameOrInternal);
        }
        ClassReader reader = new ClassReader(bytes);
        ClassNode classNode = new ClassNode();
        reader.accept(classNode, ClassReader.SKIP_FRAMES);
        return classNode;
    }

    public static JsonObject classMetadataJson(ClassNode classNode) {
        JsonObject json = new JsonObject();
        json.addProperty("internalName", classNode.name);
        json.addProperty("displayName", classNode.name.replace('/', '.'));
        json.addProperty("superName", classNode.superName);
        json.addProperty("displaySuperName", classNode.superName == null ? null : classNode.superName.replace('/', '.'));
        json.addProperty("access", classNode.access);
        json.add("accessFlags", accessFlagsJson(classNode.access));
        json.addProperty("bytecodeVersion", classNode.version);

        JsonArray interfaces = new JsonArray();
        for (String interfaceName : classNode.interfaces) {
            interfaces.add(interfaceName.replace('/', '.'));
        }
        json.add("interfaces", interfaces);

        JsonArray annotations = new JsonArray();
        for (AnnotationNode annotation : listAnnotations(classNode.visibleAnnotations, classNode.invisibleAnnotations)) {
            annotations.add(annotation.desc);
        }
        json.add("annotations", annotations);

        JsonArray fields = new JsonArray();
        for (FieldNode field : classNode.fields) {
            JsonObject item = new JsonObject();
            item.addProperty("name", field.name);
            item.addProperty("descriptor", field.desc);
            item.addProperty("type", displayType(field.desc));
            item.addProperty("access", field.access);
            item.add("accessFlags", accessFlagsJson(field.access));
            fields.add(item);
        }
        json.add("fields", fields);

        JsonArray methods = new JsonArray();
        for (MethodNode method : classNode.methods) {
            JsonObject item = new JsonObject();
            item.addProperty("name", method.name);
            item.addProperty("descriptor", method.desc);
            item.addProperty("displaySignature", displayMethod(method.name, method.desc));
            item.addProperty("access", method.access);
            item.add("accessFlags", accessFlagsJson(method.access));
            JsonArray methodAnnotations = new JsonArray();
            for (AnnotationNode annotation : listAnnotations(method.visibleAnnotations, method.invisibleAnnotations)) {
                methodAnnotations.add(annotation.desc);
            }
            item.add("annotations", methodAnnotations);
            methods.add(item);
        }
        json.add("methods", methods);
        return json;
    }

    public static String displayMethod(String methodName, String descriptor) {
        Type methodType = Type.getMethodType(descriptor);
        StringBuilder builder = new StringBuilder();
        builder.append(methodName).append('(');
        Type[] args = methodType.getArgumentTypes();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(displayType(args[i]));
        }
        builder.append(") : ").append(displayType(methodType.getReturnType()));
        return builder.toString();
    }

    public static String displayType(String descriptor) {
        return displayType(Type.getType(descriptor));
    }

    public static String displayType(Type type) {
        return switch (type.getSort()) {
            case Type.VOID -> "void";
            case Type.BOOLEAN -> "boolean";
            case Type.CHAR -> "char";
            case Type.BYTE -> "byte";
            case Type.SHORT -> "short";
            case Type.INT -> "int";
            case Type.FLOAT -> "float";
            case Type.LONG -> "long";
            case Type.DOUBLE -> "double";
            case Type.ARRAY -> displayType(type.getElementType()) + "[]".repeat(type.getDimensions());
            case Type.OBJECT -> type.getClassName();
            case Type.METHOD -> displayMethod("method", type.getDescriptor());
            default -> type.getDescriptor();
        };
    }

    public static JsonArray accessFlagsJson(int access) {
        JsonArray flags = new JsonArray();
        addFlag(flags, access, Opcodes.ACC_PUBLIC, "public");
        addFlag(flags, access, Opcodes.ACC_PRIVATE, "private");
        addFlag(flags, access, Opcodes.ACC_PROTECTED, "protected");
        addFlag(flags, access, Opcodes.ACC_STATIC, "static");
        addFlag(flags, access, Opcodes.ACC_FINAL, "final");
        addFlag(flags, access, Opcodes.ACC_ABSTRACT, "abstract");
        addFlag(flags, access, Opcodes.ACC_INTERFACE, "interface");
        addFlag(flags, access, Opcodes.ACC_ENUM, "enum");
        addFlag(flags, access, Opcodes.ACC_SYNTHETIC, "synthetic");
        addFlag(flags, access, Opcodes.ACC_ANNOTATION, "annotation");
        addFlag(flags, access, Opcodes.ACC_RECORD, "record");
        return flags;
    }

    public static String guessSimpleClassName(Path path) {
        String fileName = path.getFileName().toString();
        return fileName.endsWith(".class") ? fileName.substring(0, fileName.length() - 6) : fileName;
    }

    private static void addFlag(JsonArray flags, int access, int mask, String name) {
        if ((access & mask) != 0) {
            flags.add(name);
        }
    }

    private static List<AnnotationNode> listAnnotations(List<AnnotationNode> visible, List<AnnotationNode> invisible) {
        List<AnnotationNode> annotations = new ArrayList<>();
        if (visible != null) {
            annotations.addAll(visible);
        }
        if (invisible != null) {
            annotations.addAll(invisible);
        }
        return annotations;
    }
}
