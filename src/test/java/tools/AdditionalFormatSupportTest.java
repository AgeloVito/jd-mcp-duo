package tools;

import archive.InputContainers;
import com.googlecode.dex2jar.tools.Jar2Dex;
import model.ToolResult;
import testing.TestFixtures;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class AdditionalFormatSupportTest {
    @Test
    void testEarAndKarAreAccepted(@TempDir Path tempDir) throws Exception {
        Path classesDir = TestFixtures.compileSources(tempDir.resolve("src"), Map.of(
                "demo/App.java", "package demo; public class App { public String hi(){ return \"hi\"; } }"
        ));
        Path earPath = TestFixtures.createJar(tempDir.resolve("sample.ear"), classesDir);
        Path karPath = TestFixtures.createJar(tempDir.resolve("sample.kar"), classesDir);

        assertTrue(InputContainers.isArchivePath(earPath));
        assertTrue(InputContainers.isArchivePath(karPath));

        ListClassesTool tool = new ListClassesTool();
        JsonObject earArgs = new JsonObject();
        earArgs.addProperty("path", earPath.toString());
        ToolResult earResult = tool.execute(earArgs);
        assertFalse(earResult.isError());
        assertTrue(earResult.text().contains("demo.App"));

        JsonObject karArgs = new JsonObject();
        karArgs.addProperty("path", karPath.toString());
        ToolResult karResult = tool.execute(karArgs);
        assertFalse(karResult.isError());
        assertTrue(karResult.text().contains("demo.App"));
    }

    @Test
    void testEarNestedModuleClassesArePrimaryTargets(@TempDir Path tempDir) throws Exception {
        Path moduleClasses = TestFixtures.compileSources(tempDir.resolve("module-src"), Map.of(
                "demo/App.java", "package demo; public class App { public String hi(){ return \"ear-module\"; } }"
        ));
        Path moduleJar = TestFixtures.createJar(tempDir.resolve("module.jar"), moduleClasses);
        Path emptyClasses = tempDir.resolve("empty");
        Files.createDirectories(emptyClasses);
        Path earPath = TestFixtures.createJar(
                tempDir.resolve("app.ear"),
                emptyClasses,
                "",
                Map.of("lib/module.jar", Files.readAllBytes(moduleJar))
        );

        ListClassesTool listTool = new ListClassesTool();
        JsonObject listArgs = new JsonObject();
        listArgs.addProperty("path", earPath.toString());
        ToolResult listResult = listTool.execute(listArgs);
        assertFalse(listResult.isError());
        assertTrue(listResult.text().contains("demo.App"));

        DecompileClassTool decompileTool = new DecompileClassTool();
        JsonObject decompileArgs = new JsonObject();
        decompileArgs.addProperty("path", earPath.toString());
        decompileArgs.addProperty("className", "demo.App");
        ToolResult decompileResult = decompileTool.execute(decompileArgs);
        assertFalse(decompileResult.isError());
        assertTrue(decompileResult.text().contains("ear-module"));
    }

    @Test
    void testApkWithoutDexFailsCleanly(@TempDir Path tempDir) throws Exception {
        Path apkPath = tempDir.resolve("broken.apk");
        try (ZipOutputStream zipOutputStream = new ZipOutputStream(Files.newOutputStream(apkPath))) {
            zipOutputStream.putNextEntry(new ZipEntry("AndroidManifest.xml"));
            zipOutputStream.write("<manifest/>".getBytes());
            zipOutputStream.closeEntry();
        }

        DecompileJarTool tool = new DecompileJarTool();
        JsonObject arguments = new JsonObject();
        arguments.addProperty("path", apkPath.toString());

        Exception exception = assertThrows(Exception.class, () -> tool.execute(arguments));
        assertTrue(exception.getMessage().contains("classes.dex") || exception.getMessage().contains("APK"));
    }

    @Test
    void testDexAndApkSuccessfulPath(@TempDir Path tempDir) throws Exception {
        Path classesDir = TestFixtures.compileSources(tempDir.resolve("src"), Map.of(
                "demo/App.java", "package demo; public class App { public String hi(){ return \"hi\"; } }"
        ));
        Path jarPath = TestFixtures.createJar(tempDir.resolve("sample.jar"), classesDir);
        Path dexPath = tempDir.resolve("sample.dex");

        Jar2Dex.main("-o", dexPath.toString(), jarPath.toString());
        assertTrue(Files.exists(dexPath));

        ListClassesTool listClassesTool = new ListClassesTool();
        JsonObject dexArgs = new JsonObject();
        dexArgs.addProperty("path", dexPath.toString());
        ToolResult dexResult = listClassesTool.execute(dexArgs);
        assertFalse(dexResult.isError());
        assertTrue(dexResult.text().contains("demo.App"));

        DecompileClassTool decompileClassTool = new DecompileClassTool();
        JsonObject decompileDexArgs = new JsonObject();
        decompileDexArgs.addProperty("path", dexPath.toString());
        decompileDexArgs.addProperty("className", "demo.App");
        ToolResult decompileDexResult = decompileClassTool.execute(decompileDexArgs);
        assertFalse(decompileDexResult.isError());
        assertTrue(decompileDexResult.text().contains("class App"));
        assertTrue(decompileDexResult.structuredData().getAsJsonObject().get("nativeAndroid").getAsBoolean()
                || decompileDexResult.structuredData().getAsJsonObject().getAsJsonObject("engineFailures").has("jadx-native"));
        assertNativeJadxMetadataWhenUsed(decompileDexResult);

        Path apkPath = tempDir.resolve("sample.apk");
        try (ZipOutputStream zipOutputStream = new ZipOutputStream(Files.newOutputStream(apkPath))) {
            zipOutputStream.putNextEntry(new ZipEntry("classes.dex"));
            zipOutputStream.write(Files.readAllBytes(dexPath));
            zipOutputStream.closeEntry();
            zipOutputStream.putNextEntry(new ZipEntry("AndroidManifest.xml"));
            zipOutputStream.write("<manifest/>".getBytes());
            zipOutputStream.closeEntry();
        }

        JsonObject apkArgs = new JsonObject();
        apkArgs.addProperty("path", apkPath.toString());
        ToolResult apkResult = listClassesTool.execute(apkArgs);
        assertFalse(apkResult.isError());
        assertTrue(apkResult.text().contains("demo.App"));

        JsonObject decompileApkArgs = new JsonObject();
        decompileApkArgs.addProperty("path", apkPath.toString());
        decompileApkArgs.addProperty("className", "demo.App");
        ToolResult decompileApkResult = decompileClassTool.execute(decompileApkArgs);
        assertFalse(decompileApkResult.isError());
        assertTrue(decompileApkResult.text().contains("class App"));
        assertTrue(decompileApkResult.structuredData().getAsJsonObject().get("nativeAndroid").getAsBoolean()
                || decompileApkResult.structuredData().getAsJsonObject().getAsJsonObject("engineFailures").has("jadx-native"));
        assertNativeJadxMetadataWhenUsed(decompileApkResult);

        SourceQualityReportTool qualityReportTool = new SourceQualityReportTool();
        JsonObject qualityArgs = new JsonObject();
        qualityArgs.addProperty("path", apkPath.toString());
        ToolResult qualityResult = qualityReportTool.execute(qualityArgs);
        assertFalse(qualityResult.isError());
        assertEquals(1, qualityResult.structuredData().getAsJsonObject().get("success").getAsInt());
    }

    private static void assertNativeJadxMetadataWhenUsed(ToolResult result) {
        JsonObject structured = result.structuredData().getAsJsonObject();
        if (!structured.get("nativeAndroid").getAsBoolean()) {
            return;
        }
        assertFalse(structured.get("metadataLimited").getAsBoolean());
        assertTrue(structured.get("maxLineNumber").getAsInt() > 0);
        assertTrue(structured.getAsJsonObject("lineNumbers").size() > 0);
        assertTrue(structured.getAsJsonArray("declarations").size() > 0
                || structured.getAsJsonArray("references").size() > 0
                || structured.getAsJsonArray("hyperlinks").size() > 0);
    }
}
