package tools;

import model.ToolResult;
import testing.TestFixtures;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DecompileClassToolTest {
    private final DecompileClassTool tool = new DecompileClassTool();

    @Test
    void testGetDescription() {
        assertTrue(tool.getDescription().toLowerCase().contains("decompile"));
    }

    @Test
    void testGetInputSchema() {
        JsonObject schema = tool.getInputSchema();
        assertTrue(schema.getAsJsonObject("properties").has("path"));
        assertTrue(schema.getAsJsonObject("properties").has("engine"));
        assertTrue(schema.getAsJsonObject("properties").has("engine"));
        assertTrue(schema.getAsJsonObject("properties").has("preferences"));
    }

    @Test
    void testDecompilePackagedClassFile(@TempDir Path tempDir) throws Exception {
        Path classesDir = TestFixtures.compileSources(tempDir, Map.of(
                "com/example/Dependency.java", "package com.example; public class Dependency { public static String value(){ return \"ok\"; } }",
                "com/example/Main.java", "package com.example; public class Main { public String run(){ return Dependency.value(); } }"
        ));

        JsonObject arguments = new JsonObject();
        arguments.addProperty("path", classesDir.resolve("com/example/Main.class").toString());
        ToolResult result = tool.execute(arguments);

        assertFalse(result.isError());
        assertTrue(result.text().contains("package com.example;"));
        assertTrue(result.structuredData().getAsJsonObject().get("internalName").getAsString().equals("com/example/Main"));
    }

    @Test
    void testDecompileFromArchive(@TempDir Path tempDir) throws Exception {
        Path classesDir = TestFixtures.compileSources(tempDir, Map.of(
                "demo/Worker.java", "package demo; public class Worker { public void work(){} }"
        ));
        Path jarPath = TestFixtures.createJar(tempDir.resolve("demo.jar"), classesDir, "BOOT-INF/classes/");

        JsonObject arguments = new JsonObject();
        arguments.addProperty("path", jarPath.toString());
        arguments.addProperty("className", "demo.Worker");
        ToolResult result = tool.execute(arguments);

        assertFalse(result.isError());
        assertTrue(result.text().contains("class Worker"));
    }

    @Test
    void testJdCoreDuoEngineIsUsable(@TempDir Path tempDir) throws Exception {
        Path classesDir = TestFixtures.compileSources(tempDir, Map.of(
                "demo/App.java", "package demo; public class App { public int value(){ return 7; } }"
        ));

        JsonObject arguments = new JsonObject();
        arguments.addProperty("path", classesDir.resolve("demo/App.class").toString());
        arguments.addProperty("engine", "jd-core-v1");

        ToolResult result = tool.execute(arguments);
        JsonObject structured = result.structuredData().getAsJsonObject();

        assertFalse(result.isError());
        assertEquals(decompile.DecompilerEngines.JD_CORE_V1, structured.get("engineRequested").getAsString());
        assertEquals(decompile.DecompilerEngines.JD_CORE_V1, structured.get("engineUsed").getAsString());
        assertTrue(structured.has("methodPatches"));
    }

    @Test
    void testJdCoreV1WarnsAboutIgnoredV0OnlyPreferences(@TempDir Path tempDir) throws Exception {
        Path classesDir = TestFixtures.compileSources(tempDir, Map.of(
                "demo/App.java", "package demo; public class App { public int value(){ return 7; } }"
        ));

        JsonObject preferences = new JsonObject();
        preferences.addProperty(jd.core.preferences.Preferences.OMIT_THIS_PREFIX, "true");
        JsonObject arguments = new JsonObject();
        arguments.addProperty("path", classesDir.resolve("demo/App.class").toString());
        arguments.addProperty("engine", "jd-core-v1");
        arguments.add("preferences", preferences);

        ToolResult result = tool.execute(arguments);
        JsonObject structured = result.structuredData().getAsJsonObject();

        assertFalse(result.isError());
        assertEquals(1, structured.getAsJsonArray("warnings").size());
        assertTrue(structured.getAsJsonArray("warnings").get(0).getAsString()
                .contains(jd.core.preferences.Preferences.OMIT_THIS_PREFIX));
    }

    @Test
    void testArchiveWithoutClassNameIsRejectedWhenMultipleClasses(@TempDir Path tempDir) throws Exception {
        Path classesDir = TestFixtures.compileSources(tempDir, Map.of(
                "demo/One.java", "package demo; public class One { }",
                "demo/Two.java", "package demo; public class Two { }"
        ));
        Path jarPath = TestFixtures.createJar(tempDir.resolve("demo.jar"), classesDir);

        JsonObject arguments = new JsonObject();
        arguments.addProperty("path", jarPath.toString());

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> tool.execute(arguments));
        assertTrue(exception.getMessage().contains("className is required"));
    }

    @Test
    void testOutputWithoutParentDirectory(@TempDir Path tempDir) throws Exception {
        Path classesDir = TestFixtures.compileSources(tempDir, Map.of(
                "demo/App.java", "package demo; public class App { }"
        ));

        JsonObject arguments = new JsonObject();
        arguments.addProperty("path", classesDir.resolve("demo/App.class").toString());
        arguments.addProperty("output", tempDir.resolve("App.java").getFileName().toString());

        Path workingDir = Path.of("").toAbsolutePath().normalize();
        ToolResult result = tool.execute(arguments);

        assertFalse(result.isError());
        assertTrue(result.structuredData().getAsJsonObject().get("savedTo").getAsString().endsWith("App.java"));
        java.nio.file.Files.deleteIfExists(workingDir.resolve("App.java"));
    }

    @Test
    void testRenderLineNumbersInOutput(@TempDir Path tempDir) throws Exception {
        Path classesDir = TestFixtures.compileSources(tempDir, Map.of(
                "demo/App.java", "package demo; public class App { public int value(){ return 1; } }"
        ));
        Path output = tempDir.resolve("App.lines.java");

        JsonObject arguments = new JsonObject();
        arguments.addProperty("path", classesDir.resolve("demo/App.class").toString());
        arguments.addProperty("lineNumbers", true);
        arguments.addProperty("renderLineNumbers", "both");
        arguments.addProperty("output", output.toString());

        ToolResult result = tool.execute(arguments);

        assertFalse(result.isError());
        String saved = java.nio.file.Files.readString(output);
        assertTrue(saved.contains("   1 |"));
        assertEquals("both", result.structuredData().getAsJsonObject().get("renderLineNumbers").getAsString());
        assertTrue(result.structuredData().getAsJsonObject().get("renderedSource").getAsString().contains("   1 |"));
    }

    @Test
    void testWriteSidecarMetadata(@TempDir Path tempDir) throws Exception {
        Path classesDir = TestFixtures.compileSources(tempDir, Map.of(
                "demo/App.java", "package demo; public class App { public int value(){ return 1; } }"
        ));
        Path output = tempDir.resolve("App.java");

        JsonObject arguments = new JsonObject();
        arguments.addProperty("path", classesDir.resolve("demo/App.class").toString());
        arguments.addProperty("output", output.toString());
        arguments.addProperty("writeSidecarMetadata", true);

        ToolResult result = tool.execute(arguments);

        assertFalse(result.isError());
        Path sidecar = tempDir.resolve("App.java.meta.json");
        assertTrue(java.nio.file.Files.exists(sidecar));
        String metadata = java.nio.file.Files.readString(sidecar);
        assertTrue(metadata.contains("\"internalName\":\"demo/App\""));
        assertEquals(sidecar.toString(), result.structuredData().getAsJsonObject().get("savedMetadataTo").getAsString());
    }

    @Test
    void testInvalidEngineIsRejected(@TempDir Path tempDir) throws Exception {
        Path classesDir = TestFixtures.compileSources(tempDir, Map.of(
                "demo/App.java", "package demo; public class App { }"
        ));

        JsonObject arguments = new JsonObject();
        arguments.addProperty("path", classesDir.resolve("demo/App.class").toString());
        arguments.addProperty("engine", "vineflwer");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> tool.execute(arguments));
        assertTrue(exception.getMessage().contains("Unsupported decompiler engine"));
    }

    @Test
    void testReleaseVersionSelectsMultiReleaseVariant(@TempDir Path tempDir) throws Exception {
        Path baseClasses = TestFixtures.compileSources(tempDir.resolve("base"), Map.of(
                "demo/Sample.java", "package demo; public class Sample { public String value(){ return \"base\"; } }"
        ));
        Path mrClasses = TestFixtures.compileSources(tempDir.resolve("mr"), Map.of(
                "demo/Sample.java", "package demo; public class Sample { public String value(){ return \"mr\"; } }"
        ));
        Path jarPath = TestFixtures.createMultiReleaseJar(tempDir.resolve("mr.jar"), baseClasses, mrClasses, "demo/Sample", 17);

        JsonObject baseArgs = new JsonObject();
        baseArgs.addProperty("path", jarPath.toString());
        baseArgs.addProperty("className", "demo.Sample");
        baseArgs.addProperty("releaseVersion", 11);

        ToolResult baseResult = tool.execute(baseArgs);
        assertFalse(baseResult.isError());
        assertTrue(baseResult.text().contains("return \"base\";"));

        JsonObject mrArgs = new JsonObject();
        mrArgs.addProperty("path", jarPath.toString());
        mrArgs.addProperty("className", "demo.Sample");
        mrArgs.addProperty("releaseVersion", 21);

        ToolResult mrResult = tool.execute(mrArgs);
        assertFalse(mrResult.isError());
        assertTrue(mrResult.text().contains("return \"mr\";"));
    }

    @Test
    void testCfrResolvesJdkClassesByDefault(@TempDir Path tempDir) throws Exception {
        Path classesDir = TestFixtures.compileSources(tempDir, Map.of(
                "demo/JdkOnly.java", """
                        package demo;
                        import java.io.IOException;
                        import java.nio.file.Files;
                        import java.nio.file.Path;
                        public class JdkOnly {
                            public long size(Path path) throws IOException {
                                return Files.size(path);
                            }
                        }
                        """
        ));

        JsonObject arguments = new JsonObject();
        arguments.addProperty("path", classesDir.resolve("demo/JdkOnly.class").toString());
        arguments.addProperty("engine", "cfr");

        ToolResult result = tool.execute(arguments);

        assertFalse(result.isError());
        assertFalse(result.text().contains("Could not load the following classes"));
        assertFalse(result.text().contains("*  java."));
    }
}
