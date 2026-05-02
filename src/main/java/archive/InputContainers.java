package archive;

import org.objectweb.asm.ClassReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public final class InputContainers {
    private InputContainers() {
    }

    public static InputContainer open(Path path) throws IOException {
        return open(path, null);
    }

    public static InputContainer open(Path path, Integer releaseVersion) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        if (!Files.exists(normalized)) {
            throw new IOException("File not found: " + normalized);
        }

        if (Files.isDirectory(normalized)) {
            return new DirectoryInputContainer(normalized, normalized, "directory");
        }

        String fileName = normalized.getFileName().toString().toLowerCase(Locale.ROOT);
        if (fileName.endsWith(".class")) {
            byte[] bytes = Files.readAllBytes(normalized);
            String internalName = new ClassReader(bytes).getClassName();
            Path expectedSuffix = Path.of(internalName + ".class");
            Path root = normalized;
            if (normalized.endsWith(expectedSuffix)) {
                root = normalized;
                for (int i = 0; i < expectedSuffix.getNameCount(); i++) {
                    root = root.getParent();
                }
            } else if (normalized.getParent() != null) {
                root = normalized.getParent();
            }
            return new DirectoryInputContainer(root, normalized, "class-file");
        }

        if (isArchivePath(normalized)) {
            return new ArchiveInputContainer(normalized, releaseVersion);
        }

        throw new IOException("Unsupported input path: " + normalized);
    }

    public static boolean isArchivePath(Path path) {
        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return fileName.endsWith(".jar")
                || fileName.endsWith(".war")
                || fileName.endsWith(".zip")
                || fileName.endsWith(".jmod")
                || fileName.endsWith(".aar")
                || fileName.endsWith(".ear")
                || fileName.endsWith(".kar")
                || fileName.endsWith(".apk")
                || fileName.endsWith(".dex");
    }

    public static String normalizeClassName(String classNameOrInternal) {
        String value = classNameOrInternal.replace('\\', '/');
        if (value.endsWith(".class")) {
            value = value.substring(0, value.length() - 6);
        }
        value = stripRoot(value);
        return value.replace('.', '/');
    }

    private static String stripRoot(String value) {
        String[] prefixes = {
                "BOOT-INF/classes/",
                "WEB-INF/classes/",
                "classes/"
        };
        for (String prefix : prefixes) {
            if (value.startsWith(prefix)) {
                return value.substring(prefix.length());
            }
        }
        return value;
    }
}
