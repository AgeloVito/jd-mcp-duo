package index;

import java.nio.file.Path;

public record ScopedMethodRef(Path sourcePath, MethodRef methodRef) {
}
