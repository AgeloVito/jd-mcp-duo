package decompile;

import archive.ClassLocation;
import archive.InputContainer;
import archive.InputContainers;
import jd.core.DecompilationResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.attribute.FileTime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DecompilationCacheTest {
    @Test
    void testCacheReturnsCopiedOutcome(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("demo.class");
        Files.write(file, new byte[]{0x01});
        InputContainer container = new InputContainer() {
            @Override
            public Path path() {
                return file;
            }

            @Override
            public String kind() {
                return "class-file";
            }

            @Override
            public java.net.URI contextUri() {
                return file.toUri();
            }

            @Override
            public List<ClassLocation> listClasses(boolean includeInnerClasses) {
                return List.of(new ClassLocation("demo/App", "demo/App.class", "demo.App", null));
            }

            @Override
            public ClassLocation resolveClass(String classNameOrInternal) {
                return new ClassLocation("demo/App", "demo/App.class", "demo.App", null);
            }

            @Override
            public byte[] loadClassBytes(String internalName) {
                return new byte[]{0x01};
            }
        };

        JsonFixture fixture = new JsonFixture();
        DecompilationCache.put(container, "demo/App", fixture.options(), fixture.outcome());

        DecompilationOutcome cached = DecompilationCache.get(container, "demo/App", fixture.options());
        assertNotNull(cached);
        assertNotSame(fixture.outcome(), cached);
        assertEquals("class App {}", cached.result().getDecompiledOutput());

        cached.result().setDecompiledOutput("mutated");
        DecompilationOutcome cachedAgain = DecompilationCache.get(container, "demo/App", fixture.options());
        assertEquals("class App {}", cachedAgain.result().getDecompiledOutput());
    }

    @Test
    void testDirectoryCacheFingerprintChangesWhenChildClassChanges(@TempDir Path tempDir) throws Exception {
        Path root = tempDir.resolve("classes");
        Files.createDirectories(root.resolve("demo"));
        Path classFile = root.resolve("demo/App.class");
        Files.write(classFile, new byte[]{0x01});
        InputContainer container = InputContainers.open(root);
        JsonFixture fixture = new JsonFixture();
        DecompilationCache.put(container, "demo/App", fixture.options(), fixture.outcome());
        assertNotNull(DecompilationCache.get(container, "demo/App", fixture.options()));

        Files.write(classFile, new byte[]{0x02, 0x03});
        Files.setLastModifiedTime(classFile, FileTime.fromMillis(System.currentTimeMillis() + 10_000));

        assertNull(DecompilationCache.get(container, "demo/App", fixture.options()));
    }

    private record JsonFixture(DecompilerOptions options, DecompilationOutcome outcome) {
        JsonFixture() {
            this(
                    new DecompilerOptions(DecompilerEngines.JD_CORE_V1, "fast", null, false, false, List.of(), Map.of(), DecompilerOptions.DEFAULT_ATTEMPT_TIMEOUT_MILLIS),
                    new DecompilationOutcome(
                            "demo/App",
                            DecompilerEngines.JD_CORE_V1,
                            DecompilerEngines.JD_CORE_V1,
                            false,
                            false,
                            false,
                            false,
                            false,
                            List.of(DecompilerEngines.JD_CORE_V1),
                            Map.of(),
                            result()
                    )
            );
        }

        private static DecompilationResult result() {
            DecompilationResult result = new DecompilationResult();
            result.setDecompiledOutput("class App {}");
            result.setDeclarations(new java.util.LinkedHashMap<>());
            result.setTypeDeclarations(new java.util.TreeMap<>());
            result.setReferences(new java.util.ArrayList<>());
            result.setStrings(new java.util.ArrayList<>());
            result.setLineNumbers(new java.util.LinkedHashMap<>());
            result.setHyperlinks(new java.util.TreeMap<>());
            result.setMaxLineNumber(0);
            return result;
        }
    }
}
