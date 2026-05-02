package index;

import java.nio.file.Path;

public record ScopedResource(Path sourcePath, ArchiveIndex archiveIndex, IndexedResource indexedResource) {
}
