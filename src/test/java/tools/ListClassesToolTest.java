package tools;

import model.ToolResult;
import testing.TestFixtures;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ListClassesToolTest {
    private final ListClassesTool tool = new ListClassesTool();

    @Test
    void testListBootInfClasses(@TempDir Path tempDir) throws Exception {
        Path classesDir = TestFixtures.compileSources(tempDir, Map.of(
                "com/example/App.java", "package com.example; public class App { static class Inner {} }"
        ));
        Path jarPath = TestFixtures.createJar(tempDir.resolve("app.jar"), classesDir, "BOOT-INF/classes/");

        JsonObject arguments = new JsonObject();
        arguments.addProperty("path", jarPath.toString());
        arguments.addProperty("package", "com.example");
        ToolResult result = tool.execute(arguments);

        assertTrue(result.text().contains("com.example.App"));
        assertFalse(result.text().contains("BOOT-INF/classes"));
    }
}
