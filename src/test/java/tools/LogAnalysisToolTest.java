package tools;

import com.google.gson.JsonObject;
import model.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import testing.TestFixtures;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.google.gson.JsonArray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogAnalysisToolTest {
    @Test
    void testAnalyzeComplexLog(@TempDir Path tempDir) throws Exception {
        String oldIndex = System.getProperty("jd.mcp.sqlite.index");
        System.setProperty("jd.mcp.sqlite.index", tempDir.resolve("log-index.sqlite").toString());
        try {
            Path classesDir = TestFixtures.compileSources(tempDir, Map.of(
                    "demo/App.java", "package demo; public class App { public void run(){ helper(); } private void helper(){ throw new RuntimeException(\"boom\"); } }"
            ));
            Path jarPath = TestFixtures.createJar(tempDir.resolve("demo.jar"), classesDir);
            Path logPath = tempDir.resolve("app.log");
            Files.writeString(logPath, """
                    2026-04-26 22:50:12,345 ERROR [main] demo.App - starting failure handling
                    Exception in thread "main" java.lang.RuntimeException: boom
                    \tat demo.App.run(App.java:1)
                    \tat demo.Native.call(Native Method)
                    Caused by: java.lang.IllegalStateException: broken
                    \tat demo.App.helper(Unknown Source)
                    \t... 2 more
                    """);

            ResolveStacktraceTool tool = new ResolveStacktraceTool();
            JsonObject arguments = new JsonObject();
            arguments.addProperty("path", jarPath.toString());
            arguments.addProperty("textPath", logPath.toString());

            ToolResult result = tool.execute(arguments);
            assertFalse(result.isError());
            JsonObject structured = result.structuredData().getAsJsonObject();
            assertEquals("sqlite", structured.get("indexBackend").getAsString());
            assertTrue(structured.get("frameCount").getAsInt() >= 2);
            assertTrue(structured.getAsJsonArray("entries").asList().stream()
                    .anyMatch(item -> item.getAsJsonObject().has("nativeMethod")));
            assertTrue(structured.getAsJsonArray("entries").asList().stream()
                    .anyMatch(item -> item.getAsJsonObject().has("unknownSource")));
            assertTrue(structured.getAsJsonArray("entries").asList().stream()
                    .anyMatch(item -> "causedBy".equals(item.getAsJsonObject().get("kind").getAsString())));
            assertTrue(structured.getAsJsonArray("entries").asList().stream()
                    .anyMatch(item -> "log".equals(item.getAsJsonObject().get("kind").getAsString())));
        } finally {
            restoreIndexProperty(oldIndex);
        }
    }

    @Test
    void testCandidateMethodsAreScopedPerArchive(@TempDir Path tempDir) throws Exception {
        String oldIndex = System.getProperty("jd.mcp.sqlite.index");
        System.setProperty("jd.mcp.sqlite.index", tempDir.resolve("scoped-log-index.sqlite").toString());
        try {
            Path leftClasses = TestFixtures.compileSources(tempDir.resolve("left"), Map.of(
                    "demo/App.java", "package demo; public class App { public void run(){} }"
            ));
            Path rightClasses = TestFixtures.compileSources(tempDir.resolve("right"), Map.of(
                    "demo/App.java", "package demo; public class App { public void other(){} }"
            ));
            Path scopeDir = tempDir.resolve("scope");
            Files.createDirectories(scopeDir);
            Path leftJar = TestFixtures.createJar(scopeDir.resolve("left.jar"), leftClasses);
            TestFixtures.createJar(scopeDir.resolve("right.jar"), rightClasses);

            ResolveStacktraceTool tool = new ResolveStacktraceTool();
            JsonObject arguments = new JsonObject();
            arguments.addProperty("path", leftJar.toString());
            arguments.addProperty("scopePath", scopeDir.toString());
            arguments.addProperty("scopeRecursive", true);
            arguments.addProperty("text", "at demo.App.run(App.java:1)");

            ToolResult result = tool.execute(arguments);
            assertFalse(result.isError());
            JsonArray candidates = result.structuredData().getAsJsonObject()
                    .getAsJsonArray("frames").get(0).getAsJsonObject()
                    .getAsJsonArray("candidates");
            assertEquals(2, candidates.size());
            assertTrue(candidates.asList().stream().anyMatch(item ->
                    item.getAsJsonObject().get("sourcePath").getAsString().endsWith("right.jar")
                            && item.getAsJsonObject().getAsJsonArray("methodCandidates").isEmpty()));
        } finally {
            restoreIndexProperty(oldIndex);
        }
    }

    @Test
    void testMappedDecompiledLinesIgnoredWhenMetadataIsLimited() {
        com.google.gson.JsonArray lines = ResolveStacktraceTool.mappedDecompiledLines(
                new decompile.DecompilationOutcome(
                        "demo/App",
                        "auto",
                        "jd-core-v1",
                        true,
                        false,
                        true,
                        false,
                        false,
                        java.util.List.of("jd-core-v1", "jd-core-v0"),
                        java.util.Map.of(),
                        null
                ),
                12
        );
        assertEquals(0, lines.size());
    }

    private static void restoreIndexProperty(String oldIndex) {
        if (oldIndex == null) {
            System.clearProperty("jd.mcp.sqlite.index");
        } else {
            System.setProperty("jd.mcp.sqlite.index", oldIndex);
        }
    }
}
