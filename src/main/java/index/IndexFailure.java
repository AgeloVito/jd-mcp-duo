package index;

import java.nio.file.Path;

public record IndexFailure(Path sourcePath, String errorType, String message) {
}
