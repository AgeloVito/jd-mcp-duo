package tools;

import archive.InputContainers;
import model.MCPTool;
import model.ToolResult;
import support.JsonUtils;
import support.MavenSearchSupport;
import support.PathSupport;
import support.SchemaSupport;
import support.Sha1Support;
import support.ToolResults;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import support.VersionSupport;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BuildSkeletonTool implements MCPTool {
    private static final Pattern FILE_PATTERN = Pattern.compile(
            "([^-]+(?:-[^0-9][^-]*)*)-([0-9][A-Za-z0-9_.-]*)\\.(jar|war|zip|aar|jmod|ear|kar)$");

    @Override
    public String getDescription() {
        return "Generate Maven/Gradle build skeleton files from archives with inferred dependency coordinates.";
    }

    @Override
    public JsonObject getInputSchema() {
        JsonObject schema = SchemaSupport.objectSchema();
        JsonObject properties = SchemaSupport.properties(schema);
        SchemaSupport.addString(properties, "path", "Archive path or directory");
        SchemaSupport.addStringOrArray(properties, "files", "Optional explicit archive list");
        SchemaSupport.addString(properties, "outputDir", "Optional directory to write generated files");
        SchemaSupport.addString(properties, "configPath", "Optional repository config JSON/properties file");
        SchemaSupport.addString(properties, "searchProvider", "maven-central, nexus2, or nexus3");
        SchemaSupport.addString(properties, "searchBaseUrl", "Optional Maven search endpoint");
        SchemaSupport.addString(properties, "remoteContentBaseUrl", "Optional Maven remote content endpoint");
        SchemaSupport.addString(properties, "proxyHost", "Optional proxy host");
        SchemaSupport.addString(properties, "proxyPort", "Optional proxy port");
        SchemaSupport.addString(properties, "username", "Optional basic-auth username");
        SchemaSupport.addString(properties, "password", "Optional basic-auth password");
        SchemaSupport.addString(properties, "bearerToken", "Optional bearer token for private repositories");
        SchemaSupport.require(schema, "path");
        return schema;
    }

    @Override
    public ToolResult execute(JsonObject arguments) throws Exception {
        MavenSearchSupport.Endpoints endpoints = MavenSearchSupport.Endpoints.fromArgs(arguments);
        List<Path> inputs = new ArrayList<>();
        inputs.add(JsonUtils.getRequiredPath(arguments, "path"));
        for (String extra : JsonUtils.getStringList(arguments, "files")) {
            Path validated = PathSupport.validatePath(extra);
            if (validated != null) {
                inputs.add(validated);
            }
        }

        List<Path> archives = new ArrayList<>();
        for (Path input : inputs) {
            if (Files.isDirectory(input)) {
                try (var stream = Files.list(input)) {
                    archives.addAll(stream.filter(Files::isRegularFile).filter(InputContainers::isArchivePath).toList());
                }
            } else if (Files.isRegularFile(input) && InputContainers.isArchivePath(input)) {
                archives.add(input);
            }
        }
        if (archives.isEmpty()) {
            return ToolResults.error("No supported archives found");
        }

        List<InferredArchive> inferredArchives = new ArrayList<>();
        for (Path archive : archives) {
            String[] inferred = inferCoordinates(archive, endpoints);
            if (inferred != null) {
                inferredArchives.add(new InferredArchive(archive, inferred));
            }
        }
        if (inferredArchives.isEmpty()) {
            return ToolResults.error("Failed to infer coordinates for supported archives");
        }

        String pom = renderPom(inferredArchives);
        String gradle = renderGradle(inferredArchives);
        String deploy = renderDeploy(inferredArchives);

        String outputDirText = JsonUtils.getString(arguments, "outputDir", null);
        if (outputDirText != null && !outputDirText.isBlank()) {
            Path outputDir = Path.of(outputDirText).toAbsolutePath().normalize();
            Files.createDirectories(outputDir);
            Files.writeString(outputDir.resolve("pom.xml"), pom);
            Files.writeString(outputDir.resolve("build.gradle"), gradle);
            Files.writeString(outputDir.resolve("mvn_deploy.bat"), deploy);
        }

        JsonObject structured = new JsonObject();
        structured.addProperty("pomXml", pom);
        structured.addProperty("buildGradle", gradle);
        structured.addProperty("mvnDeployBat", deploy);
        JsonArray deps = new JsonArray();
        for (InferredArchive inferredArchive : inferredArchives) {
            String[] gav = inferredArchive.gav();
            JsonObject dep = new JsonObject();
            dep.addProperty("archivePath", inferredArchive.archive().toString());
            dep.addProperty("packaging", packagingFor(inferredArchive.archive()));
            dep.addProperty("groupId", gav[0]);
            dep.addProperty("artifactId", gav[1]);
            dep.addProperty("version", gav[2]);
            deps.add(dep);
        }
        structured.add("dependencies", deps);
        return ToolResults.structured("Generated build skeleton for " + inferredArchives.size() + " inferred dependencies.", structured);
    }

    private static String[] inferCoordinates(Path archive, MavenSearchSupport.Endpoints endpoints) throws Exception {
        if (!Files.isRegularFile(archive)) {
            return null;
        }
        try (JarFile jarFile = new JarFile(archive.toFile())) {
            var entry = jarFile.stream()
                    .filter(item -> item.getName().startsWith("META-INF/maven/") && item.getName().endsWith("/pom.properties"))
                    .findFirst()
                    .orElse(null);
            if (entry != null) {
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
        } catch (Exception ignored) {
        }

        try {
            String sha1 = Sha1Support.computeSha1(archive);
            List<MavenSearchSupport.MavenArtifact> candidates = MavenSearchSupport.searchBySha1(sha1, endpoints);
            if (!candidates.isEmpty()) {
                MavenSearchSupport.MavenArtifact artifact = candidates.get(0);
                return new String[]{artifact.groupId(), artifact.artifactId(), artifact.version()};
            }
        } catch (Exception ignored) {
            // best effort, keep local fallbacks
        }

        Matcher matcher = FILE_PATTERN.matcher(archive.getFileName().toString());
        if (matcher.matches()) {
            return new String[]{"local.inferred", matcher.group(1), matcher.group(2)};
        }
        String name = archive.getFileName().toString().replaceFirst("\\.[^.]+$", "");
        return new String[]{"local.inferred", name, "0.0.0-local"};
    }

    private static String renderPom(List<InferredArchive> inferredArchives) {
        StringBuilder pom = new StringBuilder();
        pom.append("<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n");
        pom.append("  <modelVersion>4.0.0</modelVersion>\n");
        pom.append("  <groupId>generated</groupId>\n");
        pom.append("  <artifactId>build-skeleton</artifactId>\n");
        pom.append("  <version>").append(VersionSupport.readVersion()).append("</version>\n");
        pom.append("  <dependencies>\n");
        for (InferredArchive inferredArchive : inferredArchives) {
            String[] gav = inferredArchive.gav();
            pom.append("    <dependency>\n");
            pom.append("      <groupId>").append(gav[0]).append("</groupId>\n");
            pom.append("      <artifactId>").append(gav[1]).append("</artifactId>\n");
            pom.append("      <version>").append(gav[2]).append("</version>\n");
            pom.append("    </dependency>\n");
        }
        pom.append("  </dependencies>\n");
        pom.append("</project>\n");
        return pom.toString();
    }

    private static String renderGradle(List<InferredArchive> inferredArchives) {
        StringBuilder gradle = new StringBuilder("plugins { id 'java' }\n\nrepositories { mavenCentral() }\n\ndependencies {\n");
        for (InferredArchive inferredArchive : inferredArchives) {
            String[] gav = inferredArchive.gav();
            gradle.append("    implementation '").append(gav[0]).append(':').append(gav[1]).append(':').append(gav[2]).append("'\n");
        }
        gradle.append("}\n");
        return gradle.toString();
    }

    private static String renderDeploy(List<InferredArchive> inferredArchives) {
        StringBuilder deploy = new StringBuilder("@echo off\n");
        for (InferredArchive inferredArchive : inferredArchives) {
            String[] gav = inferredArchive.gav();
            deploy.append("mvn install:install-file -Dfile=").append(inferredArchive.archive().toAbsolutePath())
                    .append(" -DgroupId=").append(gav[0])
                    .append(" -DartifactId=").append(gav[1])
                    .append(" -Dversion=").append(gav[2])
                    .append(" -Dpackaging=").append(packagingFor(inferredArchive.archive()))
                    .append('\n');
        }
        return deploy.toString();
    }

    private static String packagingFor(Path archive) {
        String fileName = archive.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 && dot + 1 < fileName.length()
                ? fileName.substring(dot + 1).toLowerCase()
                : "jar";
    }

    private record InferredArchive(Path archive, String[] gav) {
    }
}
