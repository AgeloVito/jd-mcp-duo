package index;

import java.nio.file.Path;

public record ScopedStringHit(Path sourcePath, ArchiveIndex archiveIndex, StringHit stringHit) {
}
