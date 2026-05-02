package tools;

import model.ToolResult;
import testing.TestFixtures;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ResolutionAndNormalizationToolTest {
    @Test
    void testCallChainOverloadUnresolvedAndNormalization(@TempDir Path tempDir) throws Exception {
        Path classesDir = TestFixtures.compileSources(tempDir, Map.of(
                "demo/Worker.java", """
                        package demo;
                        public class Worker {
                            public void run() {}
                            public void run(String name) {}
                        }
                        """,
                "demo/Main.java", """
                        package demo;
                        public class Main {
                            public void start() { new Worker().run(); }
                        }
                        """
        ));
        Path jarPath = TestFixtures.createJar(tempDir.resolve("demo.jar"), classesDir);

        CallChainTool tool = new CallChainTool();

        JsonObject ambiguousArgs = new JsonObject();
        ambiguousArgs.addProperty("path", jarPath.toString());
        ambiguousArgs.addProperty("className", "demo.Worker");
        ambiguousArgs.addProperty("methodName", "run");
        ToolResult ambiguous = tool.execute(ambiguousArgs);
        assertTrue(ambiguous.isError());
        assertEquals("overload_ambiguous", ambiguous.structuredData().getAsJsonObject().get("unresolvedReason").getAsString());
        assertTrue(ambiguous.structuredData().getAsJsonObject().getAsJsonArray("candidateDescriptors").size() >= 2);

        JsonObject mismatchArgs = new JsonObject();
        mismatchArgs.addProperty("path", jarPath.toString());
        mismatchArgs.addProperty("className", "demo.Worker");
        mismatchArgs.addProperty("methodName", "run");
        mismatchArgs.addProperty("descriptor", "(I)V");
        ToolResult mismatch = tool.execute(mismatchArgs);
        assertTrue(mismatch.isError());
        assertEquals("descriptor_mismatch", mismatch.structuredData().getAsJsonObject().get("unresolvedReason").getAsString());
        assertTrue(mismatch.structuredData().getAsJsonObject().getAsJsonArray("candidateDescriptors").size() >= 2);

        JsonObject normalizedArgs = new JsonObject();
        normalizedArgs.addProperty("path", jarPath.toString());
        normalizedArgs.addProperty("className", "demo.Main");
        normalizedArgs.addProperty("methodName", "start");
        normalizedArgs.addProperty("direction", "CALLEES");
        normalizedArgs.addProperty("maxNodes", 1);
        ToolResult normalized = tool.execute(normalizedArgs);
        assertFalse(normalized.isError());
        assertTrue(normalized.structuredData().getAsJsonObject().get("truncated").getAsBoolean());
    }

    @Test
    void testSearchTypeNormalization(@TempDir Path tempDir) throws Exception {
        Path classesDir = TestFixtures.compileSources(tempDir, Map.of(
                "demo/Main.java", "package demo; public class Main { public void run() {} }"
        ));
        Path jarPath = TestFixtures.createJar(tempDir.resolve("demo.jar"), classesDir);

        SearchInJarTool tool = new SearchInJarTool();
        JsonObject arguments = new JsonObject();
        arguments.addProperty("path", jarPath.toString());
        arguments.addProperty("query", "run");
        arguments.addProperty("type", "METHOD");
        arguments.addProperty("queryMode", "PLAIN");
        ToolResult result = tool.execute(arguments);

        assertFalse(result.isError());
        assertTrue(result.structuredData().getAsJsonObject().getAsJsonArray("results").size() > 0);
    }
}
