package tools;

import model.ToolResult;
import testing.TestFixtures;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SearchAndCallChainToolTest {
    @Test
    void testSearchAndCallChain(@TempDir Path tempDir) throws Exception {
        String oldIndex = System.getProperty("jd.mcp.sqlite.index");
        System.setProperty("jd.mcp.sqlite.index", tempDir.resolve("index.sqlite").toString());
        try {
            Path classesDir = TestFixtures.compileSources(tempDir, Map.of(
                    "demo/Dependency.java", "package demo; public class Dependency { public static String value(){ return \"worker\"; } }",
                    "demo/Worker.java", "package demo; public class Worker { public void work(){ Dependency.value(); } }",
                    "demo/Main.java", "package demo; public class Main { public String run(){ helper(); return Dependency.value(); } private void helper(){ new Worker().work(); } }"
            ));
            Path jarPath = TestFixtures.createJar(tempDir.resolve("demo.jar"), classesDir);

            SearchInJarTool searchTool = new SearchInJarTool();
            JsonObject searchArgs = new JsonObject();
            searchArgs.addProperty("path", jarPath.toString());
            searchArgs.addProperty("query", "run");
            searchArgs.addProperty("type", "method");
            ToolResult searchResult = searchTool.execute(searchArgs);
            assertFalse(searchResult.isError());
            assertTrue(searchResult.structuredData().getAsJsonObject().getAsJsonArray("results").size() > 0);

            JsonObject stringArgs = new JsonObject();
            stringArgs.addProperty("path", jarPath.toString());
            stringArgs.addProperty("query", "worker");
            stringArgs.addProperty("type", "string");
            ToolResult stringResult = searchTool.execute(stringArgs);
            assertFalse(stringResult.isError());
            assertTrue(stringResult.structuredData().getAsJsonObject().getAsJsonArray("results").size() > 0);

            CallChainTool callChainTool = new CallChainTool();
            JsonObject chainArgs = new JsonObject();
            chainArgs.addProperty("path", jarPath.toString());
            chainArgs.addProperty("className", "demo.Main");
            chainArgs.addProperty("methodName", "run");
            chainArgs.addProperty("direction", "callees");
            chainArgs.addProperty("depth", 3);
            ToolResult chainResult = callChainTool.execute(chainArgs);
            assertFalse(chainResult.isError());
            assertTrue(chainResult.structuredData().getAsJsonObject().has("callees"));
        } finally {
            restoreIndexProperty(oldIndex);
        }
    }

    @Test
    void testResourceSearch(@TempDir Path tempDir) throws Exception {
        String oldIndex = System.getProperty("jd.mcp.sqlite.index");
        System.setProperty("jd.mcp.sqlite.index", tempDir.resolve("resources-index.sqlite").toString());
        try {
            Path jarPath = tempDir.resolve("resources.jar");
            try (JarOutputStream outputStream = new JarOutputStream(Files.newOutputStream(jarPath))) {
                outputStream.putNextEntry(new JarEntry("META-INF/services/demo.Service"));
                outputStream.write("demo.impl.ServiceImpl\n".getBytes(StandardCharsets.UTF_8));
                outputStream.closeEntry();

                outputStream.putNextEntry(new JarEntry("config/demo.xml"));
                outputStream.write("<beans><bean class=\"demo.Service\"/></beans>\n".getBytes(StandardCharsets.UTF_8));
                outputStream.closeEntry();

                outputStream.putNextEntry(new JarEntry("config/app.properties"));
                outputStream.write("feature.enabled=true\n".getBytes(StandardCharsets.UTF_8));
                outputStream.closeEntry();
            }

            SearchInJarTool tool = new SearchInJarTool();

            JsonObject serviceArgs = new JsonObject();
            serviceArgs.addProperty("path", jarPath.toString());
            serviceArgs.addProperty("query", "ServiceImpl");
            serviceArgs.addProperty("type", "service");
            ToolResult serviceResult = tool.execute(serviceArgs);
            assertFalse(serviceResult.isError());
            assertTrue(serviceResult.structuredData().getAsJsonObject().getAsJsonArray("results").size() > 0);

            JsonObject xmlArgs = new JsonObject();
            xmlArgs.addProperty("path", jarPath.toString());
            xmlArgs.addProperty("query", "demo.Service");
            xmlArgs.addProperty("type", "xml");
            ToolResult xmlResult = tool.execute(xmlArgs);
            assertFalse(xmlResult.isError());
            assertTrue(xmlResult.structuredData().getAsJsonObject().getAsJsonArray("results").size() > 0);

            FindReferencesTool referencesTool = new FindReferencesTool();
            JsonObject referencesArgs = new JsonObject();
            referencesArgs.addProperty("path", jarPath.toString());
            referencesArgs.addProperty("kind", "type");
            referencesArgs.addProperty("className", "demo.Service");
            ToolResult references = referencesTool.execute(referencesArgs);
            assertFalse(references.isError());
            assertTrue(references.structuredData().getAsJsonObject().getAsJsonArray("references").asList().stream()
                    .anyMatch(item -> item.getAsJsonObject().has("resourceEntry")));
        } finally {
            restoreIndexProperty(oldIndex);
        }
    }

    @Test
    void testSearchSkipsDamagedScopeInputs(@TempDir Path tempDir) throws Exception {
        String oldIndex = System.getProperty("jd.mcp.sqlite.index");
        System.setProperty("jd.mcp.sqlite.index", tempDir.resolve("index.sqlite").toString());
        try {
            Path classesDir = TestFixtures.compileSources(tempDir.resolve("classes"), Map.of(
                    "demo/App.java", "package demo; public class App { public String run(){ return \"ok\"; } }"
            ));
            Path scopeDir = tempDir.resolve("scope");
            Files.createDirectories(scopeDir);
            Path goodJar = TestFixtures.createJar(scopeDir.resolve("good.jar"), classesDir);
            try (JarOutputStream outputStream = new JarOutputStream(Files.newOutputStream(scopeDir.resolve("bad.apk")))) {
                outputStream.putNextEntry(new JarEntry("AndroidManifest.xml"));
                outputStream.write("<manifest/>".getBytes(StandardCharsets.UTF_8));
                outputStream.closeEntry();
            }

            SearchInJarTool tool = new SearchInJarTool();
            JsonObject arguments = new JsonObject();
            arguments.addProperty("path", goodJar.toString());
            arguments.addProperty("scopePath", scopeDir.toString());
            arguments.addProperty("query", "App");
            arguments.addProperty("type", "type");

            ToolResult result = tool.execute(arguments);
            assertFalse(result.isError());
            JsonObject structured = result.structuredData().getAsJsonObject();
            assertTrue(structured.getAsJsonArray("results").size() > 0);
            assertEquals(1, structured.get("indexFailureCount").getAsInt());
        } finally {
            restoreIndexProperty(oldIndex);
        }
    }

    @Test
    void testCallChainReportsAmbiguousDuplicateMethodsAcrossScope(@TempDir Path tempDir) throws Exception {
        String oldIndex = System.getProperty("jd.mcp.sqlite.index");
        System.setProperty("jd.mcp.sqlite.index", tempDir.resolve("duplicate-index.sqlite").toString());
        try {
            Path leftClasses = TestFixtures.compileSources(tempDir.resolve("left-src"), Map.of(
                    "demo/Target.java", "package demo; public class Target { public void run(){ left(); } private void left(){} }"
            ));
            Path rightClasses = TestFixtures.compileSources(tempDir.resolve("right-src"), Map.of(
                    "demo/Target.java", "package demo; public class Target { public void run(){ right(); } private void right(){} }"
            ));
            Path scopeDir = tempDir.resolve("scope-dupes");
            Files.createDirectories(scopeDir);
            Path leftJar = TestFixtures.createJar(scopeDir.resolve("left.jar"), leftClasses);
            TestFixtures.createJar(scopeDir.resolve("right.jar"), rightClasses);

            CallChainTool tool = new CallChainTool();
            JsonObject arguments = new JsonObject();
            arguments.addProperty("path", leftJar.toString());
            arguments.addProperty("scopePath", scopeDir.toString());
            arguments.addProperty("className", "demo.Target");
            arguments.addProperty("methodName", "run");
            arguments.addProperty("descriptor", "()V");
            arguments.addProperty("direction", "callees");

            ToolResult result = tool.execute(arguments);
            assertTrue(result.isError());
            JsonObject structured = result.structuredData().getAsJsonObject();
            assertEquals("source_ambiguous", structured.get("unresolvedReason").getAsString());
            assertEquals(2, structured.getAsJsonArray("candidateMethods").size());
        } finally {
            restoreIndexProperty(oldIndex);
        }
    }

    @Test
    void testHierarchyAndOverridesUseIndexedSubtypes(@TempDir Path tempDir) throws Exception {
        String oldIndex = System.getProperty("jd.mcp.sqlite.index");
        System.setProperty("jd.mcp.sqlite.index", tempDir.resolve("hierarchy-index.sqlite").toString());
        try {
            Path classesDir = TestFixtures.compileSources(tempDir.resolve("hierarchy-src"), Map.of(
                    "demo/Base.java", "package demo; public class Base { public String value(){ return \"base\"; } }",
                    "demo/Child.java", "package demo; public class Child extends Base { @Override public String value(){ return \"child\"; } }"
            ));
            Path jarPath = TestFixtures.createJar(tempDir.resolve("hierarchy.jar"), classesDir);

            TypeHierarchyTool hierarchyTool = new TypeHierarchyTool();
            JsonObject hierarchyArgs = new JsonObject();
            hierarchyArgs.addProperty("path", jarPath.toString());
            hierarchyArgs.addProperty("className", "demo.Base");
            ToolResult hierarchy = hierarchyTool.execute(hierarchyArgs);
            assertFalse(hierarchy.isError());
            assertTrue(hierarchy.structuredData().getAsJsonObject().getAsJsonArray("subtypes").asList().stream()
                    .anyMatch(item -> "demo.Child".equals(item.getAsJsonObject().get("displayName").getAsString())));

            MethodOverridesTool overridesTool = new MethodOverridesTool();
            JsonObject overridesArgs = new JsonObject();
            overridesArgs.addProperty("path", jarPath.toString());
            overridesArgs.addProperty("className", "demo.Base");
            overridesArgs.addProperty("methodName", "value");
            overridesArgs.addProperty("descriptor", "()Ljava/lang/String;");
            ToolResult overrides = overridesTool.execute(overridesArgs);
            assertFalse(overrides.isError());
            assertTrue(overrides.structuredData().getAsJsonObject().getAsJsonArray("matches").asList().stream()
                    .anyMatch(item -> "demo.Child".equals(item.getAsJsonObject().get("owner").getAsString())));
        } finally {
            restoreIndexProperty(oldIndex);
        }
    }

    private static void restoreIndexProperty(String oldIndex) {
        if (oldIndex == null) {
            System.clearProperty("jd.mcp.sqlite.index");
        } else {
            System.setProperty("jd.mcp.sqlite.index", oldIndex);
        }
    }
}
