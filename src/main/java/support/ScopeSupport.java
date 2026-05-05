package support;

import archive.InputContainers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Stream;

public final class ScopeSupport {
    private ScopeSupport() {
    }

    public static List<Path> collectScopeInputs(Path primaryPath, Path scopePath, boolean recursive) throws IOException {
        Path effective = scopePath != null ? scopePath.toAbsolutePath().normalize() : primaryPath.toAbsolutePath().normalize();
        if (!Files.exists(effective)) {
            return List.of(primaryPath.toAbsolutePath().normalize());
        }
        if (Files.isRegularFile(effective)) {
            return List.of(effective);
        }

        LinkedHashSet<Path> inputs = new LinkedHashSet<>();
        try (Stream<Path> stream = recursive ? Files.walk(effective) : Files.list(effective)) {
            stream.filter(Files::isRegularFile)
                    .filter(InputContainers::isArchivePath)
                    .sorted()
                    .forEach(path -> inputs.add(path.toAbsolutePath().normalize()));
        }
        if (containsClassFiles(effective)) {
            inputs.add(effective);
        }
        if (inputs.isEmpty()) {
            inputs.add(primaryPath.toAbsolutePath().normalize());
        }
        return List.copyOf(inputs);
    }

    private static boolean containsClassFiles(Path directory) throws IOException {
        try (Stream<Path> stream = Files.walk(directory)) {
            return stream.anyMatch(path -> Files.isRegularFile(path) && path.toString().endsWith(".class"));
        }
    }
}
