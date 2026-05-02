package tools;

import com.google.gson.JsonObject;
import model.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import testing.TestFixtures;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecompileJarToolTest {
    @Test
    void testPreviewRequiresExplicitClassForMultiClassArchive(@TempDir Path tempDir) throws Exception {
        Path classesDir = TestFixtures.compileSources(tempDir, Map.of(
                "demo/One.java", "package demo; public class One { }",
                "demo/Two.java", "package demo; public class Two { }"
        ));
        Path jarPath = TestFixtures.createJar(tempDir.resolve("demo.jar"), classesDir);

        DecompileJarTool tool = new DecompileJarTool();
        JsonObject arguments = new JsonObject();
        arguments.addProperty("path", jarPath.toString());
        arguments.addProperty("decompile", true);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> tool.execute(arguments));
        assertTrue(exception.getMessage().contains("className is required"));
    }

    @Test
    void testPreviewUsesExplicitClass(@TempDir Path tempDir) throws Exception {
        Path classesDir = TestFixtures.compileSources(tempDir, Map.of(
                "demo/One.java", "package demo; public class One { }",
                "demo/Two.java", "package demo; public class Two { public String hi(){ return \"hi\"; } }"
        ));
        Path jarPath = TestFixtures.createJar(tempDir.resolve("demo.jar"), classesDir);

        DecompileJarTool tool = new DecompileJarTool();
        JsonObject arguments = new JsonObject();
        arguments.addProperty("path", jarPath.toString());
        arguments.addProperty("decompile", true);
        arguments.addProperty("className", "demo.Two");

        ToolResult result = tool.execute(arguments);
        assertFalse(result.isError());
        assertTrue(result.structuredData().getAsJsonObject().get("previewClass").getAsString().equals("demo/Two"));
        assertTrue(result.text().contains("class Two"));
    }
}
