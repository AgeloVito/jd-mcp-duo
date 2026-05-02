package index;

import java.util.List;

public record IndexedClass(String internalName,
                           String displayName,
                           String superName,
                           List<String> interfaces,
                           int access,
                           Integer bytecodeVersion,
                           String moduleName,
                           List<IndexedField> fields,
                           List<IndexedMethod> methods) {
}
