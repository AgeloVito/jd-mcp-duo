package tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import model.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import testing.TestFixtures;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BatchAndSaveSourcesRegressionTest {
    @Test
    void testBatchDecompileJarsKeepsArchiveExtensionInOutputPath(@TempDir Path tempDir) throws Exception {
        Path classesDir = TestFixtures.compileSources(tempDir.resolve("classes"), Map.of(
                "demo/App.java", "package demo; public class App { }"
        ));
        Path inputDir = tempDir.resolve("input");
        Files.createDirectories(inputDir);
        TestFixtures.createJar(inputDir.resolve("sample.jar"), classesDir);
        TestFixtures.createJar(inputDir.resolve("sample.war"), classesDir);

        BatchDecompileJarsTool tool = new BatchDecompileJarsTool();
        JsonObject arguments = new JsonObject();
        arguments.addProperty("path", inputDir.toString());
        arguments.addProperty("outputDir", tempDir.resolve("out").toString());
        arguments.addProperty("verbose", true);

        ToolResult result = tool.execute(arguments);

        assertFalse(result.isError());
        assertTrue(Files.exists(tempDir.resolve("out/sample.jar/demo/App.java")));
        assertTrue(Files.exists(tempDir.resolve("out/sample.war/demo/App.java")));
        assertTrue(result.structuredData().getAsJsonObject().getAsJsonArray("archives").size() >= 2);
    }

    @Test
    void testSaveAllSourcesContinuesAfterBrokenClass(@TempDir Path tempDir) throws Exception {
        Path classesDir = TestFixtures.compileSources(tempDir.resolve("classes"), Map.of(
                "demo/Good.java", "package demo; public class Good { public String hi(){ return \"hi\"; } }"
        ));
        Path jarPath = tempDir.resolve("mixed.jar");
        try (JarOutputStream outputStream = new JarOutputStream(Files.newOutputStream(jarPath))) {
            outputStream.putNextEntry(new JarEntry("demo/Good.class"));
            outputStream.write(Files.readAllBytes(classesDir.resolve("demo/Good.class")));
            outputStream.closeEntry();
            outputStream.putNextEntry(new JarEntry("demo/Broken.class"));
            outputStream.write(new byte[]{0x01, 0x02, 0x03});
            outputStream.closeEntry();
        }

        SaveAllSourcesTool tool = new SaveAllSourcesTool();
        JsonObject arguments = new JsonObject();
        arguments.addProperty("path", jarPath.toString());
        arguments.addProperty("output", tempDir.resolve("out").toString());
        arguments.addProperty("format", "directory");
        arguments.addProperty("verbose", true);

        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        ToolResult result;
        try (PrintStream capture = new PrintStream(stderr)) {
            System.setErr(capture);
            result = tool.execute(arguments);
        } finally {
            System.setErr(originalErr);
        }
        assertTrue(result.isError());
        assertTrue(Files.exists(tempDir.resolve("out/demo/Good.java")));
        JsonObject structured = result.structuredData().getAsJsonObject();
        assertEquals(1, structured.get("savedCount").getAsInt());
        assertEquals(1, structured.get("failureCount").getAsInt());
        JsonArray failures = structured.getAsJsonArray("failures");
        assertEquals("demo.Broken", failures.get(0).getAsJsonObject().get("className").getAsString());
        assertFalse(stderr.toString().contains("EOFException"));
    }

    @Test
    void testSaveAllSourcesWritesSidecarMetadata(@TempDir Path tempDir) throws Exception {
        Path classesDir = TestFixtures.compileSources(tempDir.resolve("classes"), Map.of(
                "demo/Good.java", "package demo; public class Good { public String hi(){ return \"hi\"; } }"
        ));
        Path jarPath = TestFixtures.createJar(tempDir.resolve("demo.jar"), classesDir);

        SaveAllSourcesTool tool = new SaveAllSourcesTool();
        JsonObject arguments = new JsonObject();
        arguments.addProperty("path", jarPath.toString());
        arguments.addProperty("output", tempDir.resolve("out").toString());
        arguments.addProperty("format", "directory");
        arguments.addProperty("writeSidecarMetadata", true);

        ToolResult result = tool.execute(arguments);
        assertFalse(result.isError());
        assertTrue(Files.exists(tempDir.resolve("out/demo/Good.java")));
        assertTrue(Files.exists(tempDir.resolve("out/demo/Good.java.meta.json")));
    }
}
