package index;

import java.nio.file.Path;

public record ScopedField(Path sourcePath, ArchiveIndex archiveIndex, IndexedClass indexedClass, IndexedField indexedField) {
}
