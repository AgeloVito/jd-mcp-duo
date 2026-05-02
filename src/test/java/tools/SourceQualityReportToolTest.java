package tools;

import com.google.gson.JsonObject;
import model.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import testing.TestFixtures;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceQualityReportToolTest {
    @Test
    void testQualityReportIncludesFallbackField(@TempDir Path tempDir) throws Exception {
        Path classesDir = TestFixtures.compileSources(tempDir, Map.of(
                "demo/App.java", "package demo; public class App { public String hi(){ return \"hi\"; } }"
        ));
        Path jarPath = TestFixtures.createJar(tempDir.resolve("demo.jar"), classesDir);

        SourceQualityReportTool tool = new SourceQualityReportTool();
        JsonObject arguments = new JsonObject();
        arguments.addProperty("path", jarPath.toString());

        ToolResult result = tool.execute(arguments);
        assertFalse(result.isError());
        assertEquals(0, result.structuredData().getAsJsonObject().get("fallback").getAsInt());
        assertEquals(0, result.structuredData().getAsJsonObject().get("nativeAndroid").getAsInt());
        assertTrue(result.structuredData().getAsJsonObject().has("failureCategories"));
        assertEquals(1, result.structuredData().getAsJsonObject().getAsJsonArray("classResults").size());
        assertTrue(result.structuredData().getAsJsonObject().getAsJsonArray("classResults").get(0).getAsJsonObject().has("attemptedEngines"));
    }
}
