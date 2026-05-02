package tools;

import com.google.gson.JsonObject;
import model.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdtToolingTest {
    @Test
    void testCompilerDiagnosticsReportsErrors(@TempDir Path tempDir) throws Exception {
        Path sourceFile = tempDir.resolve("Broken.java");
        Files.writeString(sourceFile, """
                public class Broken {
                    int value() {
                        return missing;
                    }
                }
                """);

        CompilerDiagnosticsTool tool = new CompilerDiagnosticsTool();
        JsonObject arguments = new JsonObject();
        arguments.addProperty("path", sourceFile.toString());

        ToolResult result = tool.execute(arguments);
        assertFalse(result.isError());
        assertTrue(result.structuredData().getAsJsonObject().get("errorCount").getAsInt() > 0);
    }

    @Test
    void testCompilerDiagnosticsOnStandaloneClassResolvesPackageDependencies(@TempDir Path tempDir) throws Exception {
        Path classesDir = testing.TestFixtures.compileSources(tempDir.resolve("compiled"), java.util.Map.of(
                "com/example/Dependency.java", "package com.example; public class Dependency { }",
                "com/example/Main.java", "package com.example; public class Main { public Dependency run(){ return new Dependency(); } }"
        ));

        CompilerDiagnosticsTool tool = new CompilerDiagnosticsTool();
        JsonObject arguments = new JsonObject();
        arguments.addProperty("path", classesDir.resolve("com/example/Main.class").toString());

        ToolResult result = tool.execute(arguments);
        assertFalse(result.isError());
        assertEquals(0, result.structuredData().getAsJsonObject().get("errorCount").getAsInt());
    }

    @Test
    void testAdvancedLookupRecursesIntoSiblingArchiveDirectories(@TempDir Path tempDir) throws Exception {
        Path mainClasses = testing.TestFixtures.compileSources(tempDir.resolve("main"), java.util.Map.of(
                "dep/Helper.java", "package dep; public class Helper { }",
                "com/example/Main.java", "package com.example; public class Main { public dep.Helper run(){ return new dep.Helper(); } }"
        ));
        Path helperClasses = testing.TestFixtures.compileSources(tempDir.resolve("helper"), java.util.Map.of(
                "dep/Helper.java", "package dep; public class Helper { }"
        ));
        Path searchRoot = tempDir.resolve("workspace");
        java.nio.file.Files.createDirectories(searchRoot.resolve("classes/com/example"));
        java.nio.file.Files.createDirectories(searchRoot.resolve("libs/nested"));
        java.nio.file.Files.copy(mainClasses.resolve("com/example/Main.class"), searchRoot.resolve("classes/com/example/Main.class"));
        testing.TestFixtures.createJar(searchRoot.resolve("libs/nested/helper.jar"), helperClasses);

        CompilerDiagnosticsTool tool = new CompilerDiagnosticsTool();
        JsonObject arguments = new JsonObject();
        arguments.addProperty("path", searchRoot.resolve("classes/com/example/Main.class").toString());
        arguments.addProperty("advancedLookup", true);

        ToolResult result = tool.execute(arguments);
        assertFalse(result.isError());
        assertEquals(0, result.structuredData().getAsJsonObject().get("errorCount").getAsInt());
    }

    @Test
    void testRemoveUnnecessaryCastsRewritesSource(@TempDir Path tempDir) throws Exception {
        Path sourceFile = tempDir.resolve("Casts.java");
        Files.writeString(sourceFile, """
                public class Casts {
                    String hi() {
                        return (String) "hi";
                    }
                }
                """);

        RemoveUnnecessaryCastsTool tool = new RemoveUnnecessaryCastsTool();
        JsonObject arguments = new JsonObject();
        arguments.addProperty("path", sourceFile.toString());
        arguments.addProperty("saveTo", tempDir.resolve("Casts.cleaned.java").toString());

        ToolResult result = tool.execute(arguments);
        assertFalse(result.isError());
        assertTrue(result.structuredData().getAsJsonObject().get("removedCastCount").getAsInt() > 0);
        assertTrue(result.text().contains("return \"hi\";"));
        assertTrue(Files.readString(tempDir.resolve("Casts.cleaned.java")).contains("return \"hi\";"));
    }
}
