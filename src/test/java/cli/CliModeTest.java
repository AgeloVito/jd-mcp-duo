package cli;

import server.MCPServer;
import testing.TestFixtures;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliModeTest {
    @Test
    void testGeneralHelpIncludesBulkDecompileExamples() {
        CliMode cliMode = new CliMode(new MCPServer().getTools());
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();

        int exitCode = cliMode.run(new String[]{"--help"}, new PrintStream(stdout), System.err);

        String help = stdout.toString();
        assertEquals(0, exitCode);
        assertTrue(help.contains("save_all_sources --path=/path/to/app.jar --output=/path/to/out"));
        assertTrue(help.contains("save_all_sources --path=/path/to/app.jar --output=/path/to/out --engine=jadx"));
        assertTrue(help.contains("decompile_directory --path=/path/to/input --outputDir=/path/to/out"));
        assertTrue(help.contains("decompile_directory --path=/path/to/input --outputDir=/path/to/out --engine=jadx"));
        assertTrue(help.contains("java -jar") && help.contains(".jar list_engines"));
        assertTrue(help.contains("decompile_class --path=/path/to/app.jar --className=com.example.Main --engine=jd-core-duo"));
        assertTrue(help.contains("compare_jd_core --path=/path/to/app.jar --className=com.example.Main"));
    }

    @Test
    void testJsonOutput(@TempDir Path tempDir) throws Exception {
        Path classesDir = TestFixtures.compileSources(tempDir, Map.of(
                "demo/App.java", "package demo; public class App { }"
        ));
        Path jarPath = TestFixtures.createJar(tempDir.resolve("demo.jar"), classesDir);

        CliMode cliMode = new CliMode(new MCPServer().getTools());
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        int exitCode = cliMode.run(
                new String[]{"list_classes", "--path=" + jarPath, "--json"},
                new PrintStream(stdout),
                System.err
        );

        assertEquals(0, exitCode);
        JsonParser.parseString(stdout.toString()).getAsJsonObject();
        assertTrue(stdout.toString().contains("\"structuredData\""));
        assertTrue(stdout.toString().contains("demo.App"));
    }

    @Test
    void testJsonOutputIsNotPollutedByDecompilerStdout(@TempDir Path tempDir) throws Exception {
        Path classesDir = TestFixtures.compileSources(tempDir, Map.of(
                "demo/App.java", "package demo; public class App { }"
        ));

        CliMode cliMode = new CliMode(new MCPServer().getTools());
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exitCode = cliMode.run(
                new String[]{
                        "decompile_class",
                        "--path=" + classesDir.resolve("demo/App.class"),
                        "--engine=vineflower",
                        "--json"
                },
                new PrintStream(stdout),
                new PrintStream(stderr)
        );

        assertEquals(0, exitCode);
        JsonParser.parseString(stdout.toString()).getAsJsonObject();
        assertTrue(stdout.toString().contains("\"isError\": false"));
        assertTrue(stdout.toString().contains("class App"));
        assertTrue(stderr.toString().isBlank());
    }
}
