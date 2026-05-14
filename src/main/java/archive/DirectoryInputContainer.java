package archive;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public final class DirectoryInputContainer implements InputContainer {
    private final Path root;
    private final Path anchor;
    private final String kind;

    public DirectoryInputContainer(Path root, Path anchor, String kind) {
        this.root = root.toAbsolutePath().normalize();
        this.anchor = anchor.toAbsolutePath().normalize();
        this.kind = kind;
    }

    @Override
    public Path path() {
        return anchor;
    }

    @Override
    public Path classpathRoot() {
        return root;
    }

    @Override
    public String kind() {
        return kind;
    }

    @Override
    public URI contextUri() {
        return anchor.toUri();
    }

    @Override
    public List<ClassLocation> listClasses(boolean includeInnerClasses) {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".class"))
                    .map(this::toClassLocation)
                    .filter(location -> includeInnerClasses || !location.internalName().contains("$"))
                    .sorted((left, right) -> left.internalName().compareTo(right.internalName()))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to list classes under " + root, e);
        }
    }

    @Override
    public ClassLocation resolveClass(String classNameOrInternal) {
        String internalName = InputContainers.normalizeClassName(classNameOrInternal);
        Path classFile = resolveWithinRoot(internalName + ".class");
        if (classFile == null || !Files.exists(classFile)) {
            return null;
        }
        return toClassLocation(classFile);
    }

    @Override
    public byte[] loadClassBytes(String internalName) throws IOException {
        Path classFile = resolveWithinRoot(InputContainers.normalizeClassName(internalName) + ".class");
        if (classFile == null || !Files.exists(classFile)) {
            return null;
        }
        return Files.readAllBytes(classFile);
    }

    private Path resolveWithinRoot(String relative) {
        Path resolved = root.resolve(relative).toAbsolutePath().normalize();
        if (!resolved.startsWith(root)) {
            return null;
        }
        return resolved;
    }

    @Override
    public ClassLocation defaultClass() {
        if (Files.isRegularFile(anchor) && anchor.toString().endsWith(".class")) {
            return toClassLocation(anchor);
        }
        return InputContainer.super.defaultClass();
    }

    @Override
    public List<ResourceEntry> listResources() {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> !path.toString().endsWith(".class"))
                    .map(path -> {
                        Path relative = root.relativize(path.toAbsolutePath().normalize());
                        return new ResourceEntry(relative.toString().replace('\\', '/'));
                    })
                    .sorted(Comparator.comparing(ResourceEntry::entryName))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to list resources under " + root, e);
        }
    }

    @Override
    public byte[] loadResourceBytes(String entryName) throws IOException {
        Path resourceFile = resolveWithinRoot(entryName);
        if (resourceFile == null || !Files.exists(resourceFile)) {
            return null;
        }
        return Files.readAllBytes(resourceFile);
    }

    private ClassLocation toClassLocation(Path classFile) {
        Path relative = root.relativize(classFile.toAbsolutePath().normalize());
        String entryName = relative.toString().replace('\\', '/');
        String internalName = entryName.substring(0, entryName.length() - 6);
        return new ClassLocation(internalName, entryName, internalName.replace('/', '.'), null);
    }
}
