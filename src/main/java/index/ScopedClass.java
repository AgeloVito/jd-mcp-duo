package index;

import java.nio.file.Path;

public record ScopedClass(Path sourcePath, ArchiveIndex archiveIndex, IndexedClass indexedClass) {
}
