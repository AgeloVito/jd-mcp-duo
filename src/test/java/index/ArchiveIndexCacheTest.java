package index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import testing.TestFixtures;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchiveIndexCacheTest {
    @Test
    void testDirectoryFingerprintTracksNestedClassChanges(@TempDir Path tempDir) throws Exception {
        Path classesDir = TestFixtures.compileSources(tempDir.resolve("v1"), Map.of(
                "demo/App.java", "package demo; public class App { public String value(){ return \"one\"; } }"
        ));
        ArchiveIndex first = ArchiveIndexCache.get(classesDir);

        Path updatedClassesDir = TestFixtures.compileSources(tempDir.resolve("v2"), Map.of(
                "demo/App.java", "package demo; public class App { public String value(){ return \"two\"; } }"
        ));
        Files.write(classesDir.resolve("demo/App.class"), Files.readAllBytes(updatedClassesDir.resolve("demo/App.class")));

        ArchiveIndex second = ArchiveIndexCache.get(classesDir);

        assertNotSame(first, second);
        assertTrue(second.strings().stream().anyMatch(hit -> "two".equals(hit.text())));
    }
}
