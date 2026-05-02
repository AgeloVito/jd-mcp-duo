package support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import testing.TestFixtures;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScopeSupportTest {
    @Test
    void testDirectoryScopeDoesNotAddLooseClassFiles(@TempDir Path tempDir) throws Exception {
        Path classesDir = TestFixtures.compileSources(tempDir, Map.of(
                "demo/App.java", "package demo; public class App { }"
        ));
        Path jarPath = TestFixtures.createJar(tempDir.resolve("demo.jar"), classesDir);

        List<Path> inputs = ScopeSupport.collectScopeInputs(tempDir, null, true);

        assertTrue(inputs.contains(tempDir.toAbsolutePath().normalize()));
        assertTrue(inputs.contains(jarPath.toAbsolutePath().normalize()));
        assertFalse(inputs.stream().anyMatch(path -> path.toString().endsWith(".class")));
        assertEquals(2, inputs.size());
    }

    @Test
    void testNonRecursiveScopeStillDetectsPackagedClassRoot(@TempDir Path tempDir) throws Exception {
        Path scopeRoot = tempDir.resolve("scope");
        Path classesDir = TestFixtures.compileSources(tempDir.resolve("compiled"), Map.of(
                "demo/App.java", "package demo; public class App { }"
        ));
        java.nio.file.Files.createDirectories(scopeRoot.resolve("demo"));
        java.nio.file.Files.copy(classesDir.resolve("demo/App.class"), scopeRoot.resolve("demo/App.class"));

        List<Path> inputs = ScopeSupport.collectScopeInputs(tempDir.resolve("primary.jar"), scopeRoot, false);

        assertEquals(List.of(scopeRoot.toAbsolutePath().normalize()), inputs);
    }
}
