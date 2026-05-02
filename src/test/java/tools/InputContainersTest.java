package tools;

import archive.InputContainers;
import testing.TestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InputContainersTest {
    @Test
    void testMultiReleaseClassPreference(@TempDir Path tempDir) throws Exception {
        Path classesDir = TestFixtures.compileSources(tempDir, Map.of(
                "demo/Sample.java", "package demo; public class Sample { public int v(){ return 1; } }"
        ));
        Path jarPath = TestFixtures.createMultiReleaseJar(tempDir.resolve("mr.jar"), classesDir, "demo/Sample", 17);

        try (var container = InputContainers.open(jarPath)) {
            var location = container.resolveClass("demo.Sample");
            assertEquals("demo/Sample", location.internalName());
            assertEquals(17, location.multiReleaseVersion());
        }
    }

    @Test
    void testMultiReleaseClassSelectionHonorsReleaseVersion(@TempDir Path tempDir) throws Exception {
        Path baseClasses = TestFixtures.compileSources(tempDir.resolve("base"), Map.of(
                "demo/Sample.java", "package demo; public class Sample { public String v(){ return \"base\"; } }"
        ));
        Path mrClasses = TestFixtures.compileSources(tempDir.resolve("mr"), Map.of(
                "demo/Sample.java", "package demo; public class Sample { public String v(){ return \"mr\"; } }"
        ));
        Path jarPath = TestFixtures.createMultiReleaseJar(tempDir.resolve("mr.jar"), baseClasses, mrClasses, "demo/Sample", 17);

        try (var baseContainer = InputContainers.open(jarPath, 11);
             var mrContainer = InputContainers.open(jarPath, 21)) {
            assertEquals("demo/Sample.class", baseContainer.resolveClass("demo.Sample").entryName());
            assertEquals("META-INF/versions/17/demo/Sample.class", mrContainer.resolveClass("demo.Sample").entryName());
        }
    }
}
