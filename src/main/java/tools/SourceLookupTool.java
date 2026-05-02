package tools;

import archive.InputContainer;
import archive.InputContainers;
import com.google.gson.JsonObject;
import model.MCPTool;
import model.ToolResult;
import support.JsonUtils;
import support.MavenSearchSupport;
import support.SchemaSupport;
import support.Sha1Support;
import support.ToolResults;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class SourceLookupTool implements MCPTool {
    @Override
    public String getDescription() {
        return "Lookup original source from a local sources jar, sibling -sources.jar, or Maven Central coordinates.";
    }

    @Override
    public JsonObject getInputSchema() {
        JsonObject schema = SchemaSupport.objectSchema();
        JsonObject properties = SchemaSupport.properties(schema);
        SchemaSupport.addString(properties, "path", "Archive or class path to infer coordinates or class name");
        SchemaSupport.addString(properties, "className", "Class name to lookup in sources");
        SchemaSupport.addString(properties, "sourceJarPath", "Local sources jar path");
        SchemaSupport.addString(properties, "groupId", "Maven groupId");
        SchemaSupport.addString(properties, "artifactId", "Maven artifactId");
        SchemaSupport.addString(properties, "version", "Maven version");
        SchemaSupport.addString(properties, "sha1", "Optional SHA-1 checksum of the binary artifact");
        SchemaSupport.addString(properties, "sha1File", "Optional SHA1SUMS-style file for local checksum lookup");
        SchemaSupport.addString(properties, "configPath", "Optional repository config JSON/properties file");
        SchemaSupport.addString(properties, "searchProvider", "maven-central, nexus2, or nexus3");
        SchemaSupport.addString(properties, "searchBaseUrl", "Optional Maven search endpoint");
        SchemaSupport.addString(properties, "remoteContentBaseUrl", "Optional Maven remote content endpoint");
        SchemaSupport.addString(properties, "proxyHost", "Optional proxy host");
        SchemaSupport.addString(properties, "proxyPort", "Optional proxy port");
        SchemaSupport.addString(properties, "username", "Optional basic-auth username");
        SchemaSupport.addString(properties, "password", "Optional basic-auth password");
        SchemaSupport.addString(properties, "bearerToken", "Optional bearer token for private repositories");
        SchemaSupport.addString(properties, "saveTo", "Optional output file");
        return schema;
    }

    @Override
    public ToolResult execute(JsonObject arguments) throws Exception {
        String className = JsonUtils.getString(arguments, "className", null);
        Path path = null;
        if (arguments.has("path")) {
            path = JsonUtils.getRequiredPath(arguments, "path");
        }

        if ((className == null || className.isBlank()) && path != null && Files.exists(path)) {
            try (InputContainer container = InputContainers.open(path)) {
                var defaultClass = container.defaultClass();
                if (defaultClass != null) {
                    className = defaultClass.displayName();
                }
            }
        }
        if (className == null || className.isBlank()) {
            return ToolResults.error("className is required");
        }

        String entryName = className.replace('.', '/') + ".java";
        String source = null;
        String sourceOrigin = null;
        String sha1 = JsonUtils.getString(arguments, "sha1", null);
        MavenSearchSupport.Endpoints endpoints = MavenSearchSupport.Endpoints.fromArgs(arguments);

        String sourceJarPath = JsonUtils.getString(arguments, "sourceJarPath", null);
        if (sourceJarPath != null && !sourceJarPath.isBlank()) {
            Path localSources = Path.of(sourceJarPath).toAbsolutePath().normalize();
            source = readSourceFromJar(localSources, entryName, className);
            sourceOrigin = localSources.toString();
        }

        if (source == null && path != null && Files.exists(path)) {
            Path sibling = siblingSourcesJar(path);
            if (sibling != null && Files.exists(sibling)) {
                source = readSourceFromJar(sibling, entryName, className);
                sourceOrigin = sibling.toString();
            }
        }

        String sha1FileText = JsonUtils.getString(arguments, "sha1File", null);
        if (sha1 == null && sha1FileText != null && !sha1FileText.isBlank()) {
            Path sha1File = Path.of(sha1FileText).toAbsolutePath().normalize();
            if (Files.exists(sha1File)) {
                Map<Path, String> sha1Map = Sha1Support.readSha1File(sha1File);
                if (path != null) {
                    Path fileName = path.getFileName();
                    if (fileName != null) {
                        for (Map.Entry<Path, String> entry : sha1Map.entrySet()) {
                            if (entry.getKey().getFileName().equals(fileName)) {
                                sha1 = entry.getValue();
                                break;
                            }
                        }
                    }
                }
            }
        }

        if (sha1 == null && path != null && Files.isRegularFile(path)) {
            try {
                sha1 = Sha1Support.computeSha1(path);
            } catch (IOException ignored) {
                // best effort
            }
        }

        if (source == null) {
            String groupId = JsonUtils.getString(arguments, "groupId", null);
            String artifactId = JsonUtils.getString(arguments, "artifactId", null);
            String version = JsonUtils.getString(arguments, "version", null);
            if ((groupId == null || artifactId == null || version == null) && path != null && Files.isRegularFile(path)) {
                String[] inferred = inferCoordinates(path);
                if (inferred != null) {
                    groupId = groupId == null ? inferred[0] : groupId;
                    artifactId = artifactId == null ? inferred[1] : artifactId;
                    version = version == null ? inferred[2] : version;
                }
            }
            if (groupId != null && artifactId != null && version != null) {
                source = fetchFromMavenCentral(groupId, artifactId, version, entryName, endpoints);
                sourceOrigin = groupId + ":" + artifactId + ":" + version;
            } else if (sha1 != null && !sha1.isBlank()) {
                for (MavenSearchSupport.MavenArtifact artifact : MavenSearchSupport.searchBySha1(sha1, endpoints)) {
                    source = MavenSearchSupport.fetchSources(artifact, entryName, endpoints);
                    if (source != null) {
                        sourceOrigin = artifact.gav();
                        if (groupId == null) {
                            groupId = artifact.groupId();
                        }
                        if (artifactId == null) {
                            artifactId = artifact.artifactId();
                        }
                        if (version == null) {
                            version = artifact.version();
                        }
                        break;
                    }
                }
            }
        }

        if (source == null) {
            return ToolResults.error("Source not found for " + className);
        }

        String saveTo = JsonUtils.getString(arguments, "saveTo", null);
        if (saveTo != null && !saveTo.isBlank()) {
            Path savePath = Path.of(saveTo).toAbsolutePath().normalize();
            if (savePath.getParent() != null) {
                Files.createDirectories(savePath.getParent());
            }
            Files.writeString(savePath, source);
        }

        JsonObject structured = new JsonObject();
        structured.addProperty("className", className);
        structured.addProperty("source", source);
        structured.addProperty("origin", sourceOrigin);
        if (sha1 != null) {
            structured.addProperty("sha1", sha1);
        }
        return ToolResults.structured(source, structured);
    }

    private static Path siblingSourcesJar(Path path) {
        String fileName = path.getFileName().toString();
        if (!fileName.matches(".*\\.(jar|war|zip|aar|jmod)$")) {
            return null;
        }
        return path.resolveSibling(fileName.replaceFirst("\\.(jar|war|zip|aar|jmod)$", "-sources.jar"));
    }

    private static String[] inferCoordinates(Path archive) throws Exception {
        try (JarFile jarFile = new JarFile(archive.toFile())) {
            JarEntry entry = jarFile.stream()
                    .filter(item -> item.getName().startsWith("META-INF/maven/") && item.getName().endsWith("/pom.properties"))
                    .findFirst()
                    .orElse(null);
            if (entry == null) {
                return null;
            }
            Properties properties = new Properties();
            try (InputStream inputStream = jarFile.getInputStream(entry)) {
                properties.load(inputStream);
            }
            return new String[]{
                    properties.getProperty("groupId"),
                    properties.getProperty("artifactId"),
                    properties.getProperty("version")
            };
        }
    }

    private static String fetchFromMavenCentral(String groupId, String artifactId, String version, String entryName, MavenSearchSupport.Endpoints endpoints) throws Exception {
        for (MavenSearchSupport.MavenArtifact artifact : MavenSearchSupport.searchByGav(groupId, artifactId, version, endpoints)) {
            String source = MavenSearchSupport.fetchSources(artifact, entryName, endpoints);
            if (source != null) {
                return source;
            }
        }
        return null;
    }

    private static String readSourceFromJar(Path jarPath, String entryName, String className) throws Exception {
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            JarEntry entry = jarFile.getJarEntry(entryName);
            if (entry == null) {
                String fallbackSuffix = className.substring(className.lastIndexOf('.') + 1) + ".java";
                entry = jarFile.stream()
                        .filter(item -> !item.isDirectory() && item.getName().endsWith(fallbackSuffix))
                        .findFirst()
                        .orElse(null);
                if (entry == null) {
                    return null;
                }
            }
            try (InputStream inputStream = jarFile.getInputStream(entry)) {
                return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }

}
