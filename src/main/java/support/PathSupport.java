package support;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

public final class PathSupport {

    private PathSupport() {
    }

    /**
     * Validate and normalize a raw user-supplied path string.
     * Rejects null, empty, control characters, and parent-directory traversal.
     * Returns the absolute normalized path, or null if rejected.
     */
    public static Path validatePath(String rawPath) {
        if (rawPath == null) {
            return null;
        }

        String trimmed = rawPath.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        if (containsControlCharacter(trimmed)) {
            return null;
        }

        try {
            Path candidate = Path.of(trimmed);
            if (containsParentDirectorySegment(candidate)) {
                return null;
            }
            return candidate.toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            return null;
        }
    }

    private static boolean containsControlCharacter(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsParentDirectorySegment(Path path) {
        for (Path part : path) {
            if ("..".equals(part.toString())) {
                return true;
            }
        }
        return false;
    }
}
