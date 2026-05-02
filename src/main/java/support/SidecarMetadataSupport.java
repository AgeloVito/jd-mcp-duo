package support;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

public final class SidecarMetadataSupport {
    private static final com.google.gson.Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private SidecarMetadataSupport() {
    }

    public static Path sidecarPath(Path sourceFile) {
        return sourceFile.resolveSibling(sourceFile.getFileName().toString() + ".meta.json");
    }

    public static void writeFile(Path sourceFile, JsonObject metadata) throws IOException {
        Path sidecar = sidecarPath(sourceFile);
        if (sidecar.getParent() != null) {
            Files.createDirectories(sidecar.getParent());
        }
        Files.writeString(sidecar, GSON.toJson(metadata), StandardCharsets.UTF_8);
    }

    public static void writeJarEntry(JarOutputStream outputStream, String sourceEntryName, JsonObject metadata) throws IOException {
        outputStream.putNextEntry(new JarEntry(sourceEntryName + ".meta.json"));
        outputStream.write(GSON.toJson(metadata).getBytes(StandardCharsets.UTF_8));
        outputStream.closeEntry();
    }
}
