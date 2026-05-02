package tools;

import model.ToolResult;
import testing.TestFixtures;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DecompileDirectoryToolTest {
    @Test
    void testDecompileDirectoryForClassesAndArchives(@TempDir Path tempDir) throws Exception {
        Map<String, String> classSources = Map.of(
                "demo/App.java", "package demo; public class App { public String hi(){ return \"hi\"; } }"
        );
        Path looseClasses = TestFixtures.compileSources(tempDir.resolve("loose"), classSources);
        Path archiveClasses = TestFixtures.compileSources(tempDir.resolve("archived"), Map.of(
                "pkg/Worker.java", "package pkg; public class Worker { public void run(){} }"
        ));

        Path inputDir = tempDir.resolve("input");
        Files.createDirectories(inputDir.resolve("classes/demo"));
        Files.createDirectories(inputDir.resolve("libs"));
        Files.copy(looseClasses.resolve("demo/App.class"), inputDir.resolve("classes/demo/App.class"));
        TestFixtures.createJar(inputDir.resolve("libs/sample.jar"), archiveClasses);

        DecompileDirectoryTool tool = new DecompileDirectoryTool();
        JsonObject arguments = new JsonObject();
        arguments.addProperty("path", inputDir.toString());
        arguments.addProperty("outputDir", tempDir.resolve("out").toString());
        arguments.addProperty("recursive", true);

        ToolResult result = tool.execute(arguments);
        assertFalse(result.isError());
        assertTrue(Files.exists(tempDir.resolve("out/classes/demo/App.java")));
        assertTrue(Files.exists(tempDir.resolve("out/libs/sample.jar/pkg/Worker.java")));
        assertTrue(result.structuredData().getAsJsonObject().get("sourcesWritten").getAsInt() >= 2);
    }

    @Test
    void testArchiveBasenameCollisionDoesNotOverwrite(@TempDir Path tempDir) throws Exception {
        Path firstClasses = TestFixtures.compileSources(tempDir.resolve("jar-src"), Map.of(
                "demo/Main.java", "package demo; public class Main { public String v(){ return \"jar\"; } }"
        ));
        Path secondClasses = TestFixtures.compileSources(tempDir.resolve("war-src"), Map.of(
                "demo/Main.java", "package demo; public class Main { public String v(){ return \"war\"; } }"
        ));

        Path inputDir = tempDir.resolve("input");
        Files.createDirectories(inputDir.resolve("libs"));
        TestFixtures.createJar(inputDir.resolve("libs/sample.jar"), firstClasses);
        TestFixtures.createJar(inputDir.resolve("libs/sample.war"), secondClasses);

        DecompileDirectoryTool tool = new DecompileDirectoryTool();
        JsonObject arguments = new JsonObject();
        arguments.addProperty("path", inputDir.toString());
        arguments.addProperty("outputDir", tempDir.resolve("out").toString());

        ToolResult result = tool.execute(arguments);
        assertFalse(result.isError());
        assertTrue(Files.exists(tempDir.resolve("out/libs/sample.jar/demo/Main.java")));
        assertTrue(Files.exists(tempDir.resolve("out/libs/sample.war/demo/Main.java")));
        assertTrue(Files.readString(tempDir.resolve("out/libs/sample.jar/demo/Main.java")).contains("\"jar\""));
        assertTrue(Files.readString(tempDir.resolve("out/libs/sample.war/demo/Main.java")).contains("\"war\""));
    }

    @Test
    void testBrokenClassDoesNotAbortWholeArchive(@TempDir Path tempDir) throws Exception {
        Path goodClasses = TestFixtures.compileSources(tempDir.resolve("good"), Map.of(
                "demo/Good.java", "package demo; public class Good { public String ok(){ return \"ok\"; } }"
        ));
        Path jarRoot = tempDir.resolve("jarroot");
        Files.createDirectories(jarRoot.resolve("demo"));
        Files.copy(goodClasses.resolve("demo/Good.class"), jarRoot.resolve("demo/Good.class"));
        Files.writeString(jarRoot.resolve("demo/Broken.class"), "not-a-class");

        Path inputDir = tempDir.resolve("input");
        Files.createDirectories(inputDir);
        TestFixtures.createJar(inputDir.resolve("mixed.jar"), jarRoot);

        DecompileDirectoryTool tool = new DecompileDirectoryTool();
        JsonObject arguments = new JsonObject();
        arguments.addProperty("path", inputDir.toString());
        arguments.addProperty("outputDir", tempDir.resolve("out").toString());

        ToolResult result = tool.execute(arguments);
        assertFalse(result.isError());
        assertTrue(Files.exists(tempDir.resolve("out/mixed.jar/demo/Good.java")));
        assertTrue(result.structuredData().getAsJsonObject().get("sourcesWritten").getAsInt() >= 1);
        assertTrue(result.structuredData().getAsJsonObject().get("sourceFailures").getAsInt() >= 1);
    }

    @Test
    void testSummaryOnlySuppressesPerFileTextButStillWritesFiles(@TempDir Path tempDir) throws Exception {
        Path classesDir = TestFixtures.compileSources(tempDir.resolve("src"), Map.of(
                "demo/App.java", "package demo; public class App { public String hi(){ return \"hi\"; } }"
        ));
        Path inputDir = tempDir.resolve("input");
        Files.createDirectories(inputDir.resolve("classes/demo"));
        Files.copy(classesDir.resolve("demo/App.class"), inputDir.resolve("classes/demo/App.class"));

        DecompileDirectoryTool tool = new DecompileDirectoryTool();
        JsonObject arguments = new JsonObject();
        arguments.addProperty("path", inputDir.toString());
        arguments.addProperty("outputDir", tempDir.resolve("out").toString());
        arguments.addProperty("summaryOnly", true);

        ToolResult result = tool.execute(arguments);
        assertFalse(result.isError());
        assertTrue(Files.exists(tempDir.resolve("out/classes/demo/App.java")));
        assertFalse(result.text().contains("App.java"));
        assertTrue(result.structuredData().getAsJsonObject().get("summaryOnly").getAsBoolean());
    }
}
