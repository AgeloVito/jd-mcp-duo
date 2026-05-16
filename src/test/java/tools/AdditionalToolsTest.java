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

class AdditionalToolsTest {
    @Test
    void testHierarchyResolveMetadataAndCfg(@TempDir Path tempDir) throws Exception {
        Map<String, String> sources = Map.of(
                "demo/Base.java", "package demo; public class Base { public void work() {} }",
                "demo/Child.java", "package demo; public class Child extends Base { @Override public void work() { try { helper(); } catch (RuntimeException ex) { throw ex; } } private void helper() { throw new RuntimeException(\"boom\"); } }"
        );
        Path classesDir = TestFixtures.compileSources(tempDir, sources);
        Path jarPath = TestFixtures.createJar(tempDir.resolve("demo.jar"), classesDir);

        TypeHierarchyTool hierarchyTool = new TypeHierarchyTool();
        JsonObject hierarchyArgs = new JsonObject();
        hierarchyArgs.addProperty("path", jarPath.toString());
        hierarchyArgs.addProperty("className", "demo.Child");
        ToolResult hierarchy = hierarchyTool.execute(hierarchyArgs);
        assertFalse(hierarchy.isError());
        assertTrue(hierarchy.structuredData().getAsJsonObject().get("className").getAsString().equals("demo.Child"));

        ResolveSymbolTool resolveSymbolTool = new ResolveSymbolTool();
        JsonObject resolveArgs = new JsonObject();
        resolveArgs.addProperty("path", jarPath.toString());
        resolveArgs.addProperty("className", "demo.Child");
        resolveArgs.addProperty("methodName", "work");
        resolveArgs.addProperty("descriptor", "()V");
        ToolResult resolved = resolveSymbolTool.execute(resolveArgs);
        assertFalse(resolved.isError());
        assertTrue(resolved.structuredData().getAsJsonObject().getAsJsonArray("methods").size() == 1);

        ClassMetadataTool metadataTool = new ClassMetadataTool();
        JsonObject metadataArgs = new JsonObject();
        metadataArgs.addProperty("path", jarPath.toString());
        metadataArgs.addProperty("className", "demo.Child");
        ToolResult metadata = metadataTool.execute(metadataArgs);
        assertFalse(metadata.isError());
        assertTrue(metadata.structuredData().getAsJsonObject().getAsJsonArray("methods").size() > 0);

        ShowCfgTool cfgTool = new ShowCfgTool();
        JsonObject cfgArgs = new JsonObject();
        cfgArgs.addProperty("path", jarPath.toString());
        cfgArgs.addProperty("className", "demo.Child");
        cfgArgs.addProperty("methodName", "work");
        cfgArgs.addProperty("descriptor", "()V");
        cfgArgs.addProperty("format", "both");
        ToolResult cfg = cfgTool.execute(cfgArgs);
        assertFalse(cfg.isError());
        assertTrue(cfg.structuredData().getAsJsonObject().has("mermaid"));
        assertTrue(cfg.structuredData().getAsJsonObject().has("plantuml"));
        assertTrue(cfg.structuredData().getAsJsonObject().getAsJsonArray("edges").asList().stream()
                .anyMatch(item -> item.getAsJsonObject().get("kind").getAsString().equals("exception")));

        MethodOverridesTool overridesTool = new MethodOverridesTool();
        JsonObject overridesArgs = new JsonObject();
        overridesArgs.addProperty("path", jarPath.toString());
        overridesArgs.addProperty("className", "demo.Child");
        overridesArgs.addProperty("methodName", "work");
        overridesArgs.addProperty("descriptor", "()V");
        ToolResult overrides = overridesTool.execute(overridesArgs);
        assertFalse(overrides.isError());
        assertTrue(overrides.structuredData().getAsJsonObject().getAsJsonArray("matches").size() >= 2);
    }

    @Test
    void testSourceSaveCompareAndSkeleton(@TempDir Path tempDir) throws Exception {
        Map<String, String> sources = Map.of(
                "demo/App.java", "package demo; public class App { public String hi() { return \"hi\"; } }"
        );
        Path classesDir = TestFixtures.compileSources(tempDir, sources);
        Path jarPath = TestFixtures.createJar(tempDir.resolve("demo-1.0.0.jar"), classesDir);
        Path sourcesJar = TestFixtures.createSourcesJar(tempDir.resolve("demo-1.0.0-sources.jar"), sources);

        SourceLookupTool sourceLookupTool = new SourceLookupTool();
        JsonObject lookupArgs = new JsonObject();
        lookupArgs.addProperty("path", jarPath.toString());
        lookupArgs.addProperty("className", "demo.App");
        lookupArgs.addProperty("sourceJarPath", sourcesJar.toString());
        ToolResult lookup = sourceLookupTool.execute(lookupArgs);
        assertFalse(lookup.isError());
        assertTrue(lookup.text().contains("class App"));

        SaveAllSourcesTool saveAllSourcesTool = new SaveAllSourcesTool();
        Path outputDir = tempDir.resolve("out");
        JsonObject saveArgs = new JsonObject();
        saveArgs.addProperty("path", jarPath.toString());
        saveArgs.addProperty("output", outputDir.toString());
        saveArgs.addProperty("format", "directory");
        ToolResult saved = saveAllSourcesTool.execute(saveArgs);
        assertFalse(saved.isError());
        assertTrue(Files.exists(outputDir.resolve("demo/App.java")));

        CompareClassTool compareClassTool = new CompareClassTool();
        JsonObject compareArgs = new JsonObject();
        compareArgs.addProperty("leftPath", jarPath.toString());
        compareArgs.addProperty("leftClassName", "demo.App");
        compareArgs.addProperty("rightPath", jarPath.toString());
        compareArgs.addProperty("rightClassName", "demo.App");
        compareArgs.addProperty("leftEngine", "jd-core-v1");
        compareArgs.addProperty("rightEngine", "vineflower");
        ToolResult compare = compareClassTool.execute(compareArgs);
        assertFalse(compare.isError());
        assertTrue(compare.structuredData().getAsJsonObject().has("diff"));

        CompareJdCoreTool compareJdCoreTool = new CompareJdCoreTool();
        JsonObject compareJdArgs = new JsonObject();
        compareJdArgs.addProperty("path", jarPath.toString());
        compareJdArgs.addProperty("className", "demo.App");
        ToolResult compareJd = compareJdCoreTool.execute(compareJdArgs);
        assertFalse(compareJd.isError());
        assertEquals(decompile.DecompilerEngines.JD_CORE_V0, compareJd.structuredData().getAsJsonObject().get("leftEngine").getAsString());
        assertEquals(decompile.DecompilerEngines.JD_CORE_V1, compareJd.structuredData().getAsJsonObject().get("rightEngine").getAsString());

        BuildSkeletonTool skeletonTool = new BuildSkeletonTool();
        JsonObject skeletonArgs = new JsonObject();
        skeletonArgs.addProperty("path", jarPath.toString());
        skeletonArgs.addProperty("outputDir", tempDir.resolve("skeleton").toString());
        ToolResult skeleton = skeletonTool.execute(skeletonArgs);
        assertFalse(skeleton.isError());
        assertTrue(Files.exists(tempDir.resolve("skeleton/pom.xml")));
        assertTrue(Files.exists(tempDir.resolve("skeleton/build.gradle")));

        ListEnginesTool listEnginesTool = new ListEnginesTool();
        ToolResult engines = listEnginesTool.execute(new JsonObject());
        assertFalse(engines.isError());
        assertTrue(engines.structuredData().getAsJsonObject().getAsJsonArray("engines").size() > 0);
        assertTrue(engines.structuredData().getAsJsonObject().has("aliases"));
        assertTrue(engines.text().contains("Available decompiler engines:"));
        assertTrue(engines.text().contains("- auto:"));
        assertTrue(engines.text().contains("jd-core-v1"));
        assertTrue(engines.text().contains("jd-core-v1"));
        assertTrue(engines.text().contains("Aliases:"));
        assertTrue(engines.text().contains("Profiles:"));

        DescribeEngineOptionsTool describeTool = new DescribeEngineOptionsTool();
        assertEngineOptionsAreDiscoverable(describeTool, "jd-core-v1", 10);
        assertEngineOptionsAreDiscoverable(describeTool, "jd-core-v1", 10);
        assertEngineOptionsAreDiscoverable(describeTool, "cfr", 10);
        assertEngineOptionsAreDiscoverable(describeTool, "procyon", 10);
        assertEngineOptionsAreDiscoverable(describeTool, "fernflower", 10);
        assertEngineOptionsAreDiscoverable(describeTool, "vineflower", 10);
        assertEngineOptionsAreDiscoverable(describeTool, "jadx", 10);
    }

    private static void assertEngineOptionsAreDiscoverable(DescribeEngineOptionsTool tool, String engine, int minimumOptions) {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("engine", engine);
        ToolResult result = tool.execute(arguments);
        assertFalse(result.isError());
        JsonObject structured = result.structuredData().getAsJsonObject();
        assertTrue(result.text().contains("Options for"));
        assertTrue(result.text().contains("Available options:"));
        assertTrue(result.text().contains("--preferences"));
        assertTrue(structured.getAsJsonArray("options").size() >= minimumOptions);
        assertTrue(structured.has("defaultPreferences"));
        assertTrue(structured.has("lineNumberPreferences"));
        assertTrue(structured.has("aliases"));
    }
}
