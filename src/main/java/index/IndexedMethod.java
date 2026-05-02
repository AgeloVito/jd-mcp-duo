package index;

import java.util.List;

public record IndexedMethod(MethodRef ref, List<String> strings, int access) {
    public String displayName() {
        return ref.displayName();
    }

    public boolean isAbstract() {
        return (access & java.lang.reflect.Modifier.ABSTRACT) != 0;
    }
}
