package index;

import java.nio.file.Path;

public record ScopedMethod(Path sourcePath, ArchiveIndex archiveIndex, IndexedClass indexedClass, IndexedMethod indexedMethod) {
}
