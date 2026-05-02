package testing;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public final class TestFixtures {
    private TestFixtures() {
    }

    public static Path compileSources(Path tempDir, Map<String, String> sources) throws IOException {
        return compileSources(tempDir, sources, List.of());
    }

    public static Path compileSources(Path tempDir, Map<String, String> sources, List<Path> classpathEntries) throws IOException {
        Path srcDir = tempDir.resolve("src");
        Path classesDir = tempDir.resolve("classes");
        Files.createDirectories(srcDir);
        Files.createDirectories(classesDir);

        List<String> sourceFiles = new ArrayList<>();
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            Path file = srcDir.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue());
            sourceFiles.add(file.toString());
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "JDK compiler is required for tests");

        List<String> args = new ArrayList<>();
        args.add("-d");
        args.add(classesDir.toString());
        if (!classpathEntries.isEmpty()) {
            args.add("-classpath");
            args.add(classpathEntries.stream()
                    .map(path -> path.toAbsolutePath().normalize().toString())
                    .reduce((left, right) -> left + java.io.File.pathSeparator + right)
                    .orElse(""));
        }
        args.addAll(sourceFiles);
        int exitCode = compiler.run(null, null, null, args.toArray(String[]::new));
        assertEquals(0, exitCode, "Compilation must succeed");
        return classesDir;
    }

    public static Path createJar(Path jarPath, Path classesDir) throws IOException {
        return createJar(jarPath, classesDir, "");
    }

    public static Path createJar(Path jarPath, Path classesDir, String rootPrefix) throws IOException {
        return createJar(jarPath, classesDir, rootPrefix, Map.of());
    }

    public static Path createJar(Path jarPath, Path classesDir, String rootPrefix, Map<String, byte[]> extraEntries) throws IOException {
        try (JarOutputStream outputStream = new JarOutputStream(Files.newOutputStream(jarPath))) {
            try (var stream = Files.walk(classesDir)) {
                for (Path classFile : stream.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".class")).toList()) {
                    String relative = classesDir.relativize(classFile).toString().replace('\\', '/');
                    outputStream.putNextEntry(new JarEntry(rootPrefix + relative));
                    outputStream.write(Files.readAllBytes(classFile));
                    outputStream.closeEntry();
                }
            }
            for (Map.Entry<String, byte[]> entry : extraEntries.entrySet()) {
                outputStream.putNextEntry(new JarEntry(entry.getKey()));
                outputStream.write(entry.getValue());
                outputStream.closeEntry();
            }
        }
        return jarPath;
    }

    public static Path createSourcesJar(Path jarPath, Map<String, String> sources) throws IOException {
        try (JarOutputStream outputStream = new JarOutputStream(Files.newOutputStream(jarPath))) {
            for (Map.Entry<String, String> entry : sources.entrySet()) {
                outputStream.putNextEntry(new JarEntry(entry.getKey()));
                outputStream.write(entry.getValue().getBytes());
                outputStream.closeEntry();
            }
        }
        return jarPath;
    }

    public static Path createMultiReleaseJar(Path jarPath, Path classesDir, String internalName, int version) throws IOException {
        Path classFile = classesDir.resolve(internalName + ".class");
        byte[] bytes = Files.readAllBytes(classFile);
        try (JarOutputStream outputStream = new JarOutputStream(Files.newOutputStream(jarPath))) {
            outputStream.putNextEntry(new JarEntry(internalName + ".class"));
            outputStream.write(bytes);
            outputStream.closeEntry();

            outputStream.putNextEntry(new JarEntry("META-INF/versions/" + version + "/" + internalName + ".class"));
            outputStream.write(bytes);
            outputStream.closeEntry();
        }
        return jarPath;
    }

    public static Path createMultiReleaseJar(Path jarPath,
                                             Path baseClassesDir,
                                             Path versionedClassesDir,
                                             String internalName,
                                             int version) throws IOException {
        Path baseClassFile = baseClassesDir.resolve(internalName + ".class");
        Path versionedClassFile = versionedClassesDir.resolve(internalName + ".class");
        try (JarOutputStream outputStream = new JarOutputStream(Files.newOutputStream(jarPath))) {
            outputStream.putNextEntry(new JarEntry(internalName + ".class"));
            outputStream.write(Files.readAllBytes(baseClassFile));
            outputStream.closeEntry();

            outputStream.putNextEntry(new JarEntry("META-INF/versions/" + version + "/" + internalName + ".class"));
            outputStream.write(Files.readAllBytes(versionedClassFile));
            outputStream.closeEntry();
        }
        return jarPath;
    }
}
