package tools;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import model.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import support.Sha1Support;
import testing.TestFixtures;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class RemoteLookupAndSkeletonTest {
    @Test
    void testSourceLookupAndBuildSkeletonViaMockMavenSearch(@TempDir Path tempDir) throws Exception {
        Map<String, String> sources = Map.of(
                "demo/App.java", "package demo; public class App { public String hi(){ return \"hi\"; } }"
        );
        Path classesDir = TestFixtures.compileSources(tempDir.resolve("classes"), sources);
        Path jarPath = TestFixtures.createJar(tempDir.resolve("demo-binary.jar"), classesDir);
        String sha1 = Sha1Support.computeSha1(jarPath);
        byte[] sourcesJarBytes = createSourcesJarBytes(sources);

        try (MockMavenServer server = new MockMavenServer(sha1, sourcesJarBytes)) {
            SourceLookupTool sourceLookupTool = new SourceLookupTool();
            JsonObject lookupArgs = new JsonObject();
            lookupArgs.addProperty("path", jarPath.toString());
            lookupArgs.addProperty("className", "demo.App");
            lookupArgs.addProperty("sha1", sha1);
            lookupArgs.addProperty("searchBaseUrl", server.searchBaseUrl());
            lookupArgs.addProperty("remoteContentBaseUrl", server.remoteBaseUrl());
            ToolResult lookup = sourceLookupTool.execute(lookupArgs);
            assertFalse(lookup.isError());
            assertTrue(lookup.text().contains("class App"));
            assertEquals("com.example:demo-lib:1.2.3", lookup.structuredData().getAsJsonObject().get("origin").getAsString());

            BuildSkeletonTool buildSkeletonTool = new BuildSkeletonTool();
            JsonObject skeletonArgs = new JsonObject();
            skeletonArgs.addProperty("path", jarPath.toString());
            skeletonArgs.addProperty("searchBaseUrl", server.searchBaseUrl());
            skeletonArgs.addProperty("remoteContentBaseUrl", server.remoteBaseUrl());
            ToolResult skeleton = buildSkeletonTool.execute(skeletonArgs);
            assertFalse(skeleton.isError());
            assertTrue(skeleton.structuredData().getAsJsonObject().getAsJsonArray("dependencies").size() > 0);
            assertTrue(skeleton.structuredData().getAsJsonObject().getAsJsonArray("dependencies").get(0).getAsJsonObject().get("groupId").getAsString().equals("com.example"));
        }
    }

    @Test
    void testSourceLookupViaMockNexus3(@TempDir Path tempDir) throws Exception {
        Map<String, String> sources = Map.of(
                "demo/App.java", "package demo; public class App { public String hi(){ return \"hi\"; } }"
        );
        Path classesDir = TestFixtures.compileSources(tempDir.resolve("classes"), sources);
        Path jarPath = TestFixtures.createJar(tempDir.resolve("demo-binary.jar"), classesDir);
        String sha1 = Sha1Support.computeSha1(jarPath);
        byte[] sourcesJarBytes = createSourcesJarBytes(sources);

        try (MockNexusServer server = new MockNexusServer(sha1, sourcesJarBytes)) {
            SourceLookupTool sourceLookupTool = new SourceLookupTool();
            JsonObject lookupArgs = new JsonObject();
            lookupArgs.addProperty("path", jarPath.toString());
            lookupArgs.addProperty("className", "demo.App");
            lookupArgs.addProperty("sha1", sha1);
            lookupArgs.addProperty("searchProvider", "nexus3");
            lookupArgs.addProperty("searchBaseUrl", server.searchBaseUrl());
            lookupArgs.addProperty("username", "user");
            lookupArgs.addProperty("password", "pass");
            ToolResult lookup = sourceLookupTool.execute(lookupArgs);
            assertFalse(lookup.isError());
            assertTrue(lookup.text().contains("class App"));

            BuildSkeletonTool buildSkeletonTool = new BuildSkeletonTool();
            JsonObject skeletonArgs = new JsonObject();
            skeletonArgs.addProperty("path", jarPath.toString());
            skeletonArgs.addProperty("searchProvider", "nexus3");
            skeletonArgs.addProperty("searchBaseUrl", server.searchBaseUrl());
            skeletonArgs.addProperty("bearerToken", "secret-token");
            ToolResult skeleton = buildSkeletonTool.execute(skeletonArgs);
            assertFalse(skeleton.isError());
            assertTrue(skeleton.structuredData().getAsJsonObject().getAsJsonArray("dependencies").size() > 0);
        }
    }

    @Test
    void testSourceLookupViaMockNexus2(@TempDir Path tempDir) throws Exception {
        Map<String, String> sources = Map.of(
                "demo/App.java", "package demo; public class App { public String hi(){ return \"hi\"; } }"
        );
        Path classesDir = TestFixtures.compileSources(tempDir.resolve("classes"), sources);
        Path jarPath = TestFixtures.createJar(tempDir.resolve("demo-binary.jar"), classesDir);
        String sha1 = Sha1Support.computeSha1(jarPath);
        byte[] sourcesJarBytes = createSourcesJarBytes(sources);

        try (MockNexus2Server server = new MockNexus2Server(sha1, sourcesJarBytes)) {
            SourceLookupTool sourceLookupTool = new SourceLookupTool();
            JsonObject lookupArgs = new JsonObject();
            lookupArgs.addProperty("path", jarPath.toString());
            lookupArgs.addProperty("className", "demo.App");
            lookupArgs.addProperty("sha1", sha1);
            lookupArgs.addProperty("searchProvider", "nexus2");
            lookupArgs.addProperty("searchBaseUrl", server.baseUrl());
            lookupArgs.addProperty("username", "user");
            lookupArgs.addProperty("password", "pass");
            ToolResult lookup = sourceLookupTool.execute(lookupArgs);
            assertFalse(lookup.isError());
            assertTrue(lookup.text().contains("class App"));

            BuildSkeletonTool buildSkeletonTool = new BuildSkeletonTool();
            JsonObject skeletonArgs = new JsonObject();
            skeletonArgs.addProperty("path", jarPath.toString());
            skeletonArgs.addProperty("searchProvider", "nexus2");
            skeletonArgs.addProperty("searchBaseUrl", server.baseUrl());
            ToolResult skeleton = buildSkeletonTool.execute(skeletonArgs);
            assertFalse(skeleton.isError());
            assertTrue(skeleton.structuredData().getAsJsonObject().getAsJsonArray("dependencies").size() > 0);
        }
    }

    @Test
    void testRepositoryConfigFile(@TempDir Path tempDir) throws Exception {
        Map<String, String> sources = Map.of(
                "demo/App.java", "package demo; public class App { public String hi(){ return \"hi\"; } }"
        );
        Path classesDir = TestFixtures.compileSources(tempDir.resolve("classes"), sources);
        Path jarPath = TestFixtures.createJar(tempDir.resolve("demo-binary.jar"), classesDir);
        String sha1 = Sha1Support.computeSha1(jarPath);
        byte[] sourcesJarBytes = createSourcesJarBytes(sources);

        try (MockNexusServer server = new MockNexusServer(sha1, sourcesJarBytes)) {
            Path configPath = tempDir.resolve("repo.json");
            Files.writeString(configPath, """
                    {
                      "searchProvider": "nexus3",
                      "searchBaseUrl": "%s",
                      "username": "user",
                      "password": "pass"
                    }
                    """.formatted(server.searchBaseUrl()));

            SourceLookupTool sourceLookupTool = new SourceLookupTool();
            JsonObject lookupArgs = new JsonObject();
            lookupArgs.addProperty("path", jarPath.toString());
            lookupArgs.addProperty("className", "demo.App");
            lookupArgs.addProperty("sha1", sha1);
            lookupArgs.addProperty("configPath", configPath.toString());
            ToolResult lookup = sourceLookupTool.execute(lookupArgs);
            assertFalse(lookup.isError());
            assertTrue(lookup.text().contains("class App"));
        }
    }

    @Test
    void testBuildSkeletonFiltersUnsupportedFilesAndUsesArchivePackaging(@TempDir Path tempDir) throws Exception {
        Path classesDir = TestFixtures.compileSources(tempDir.resolve("classes"), Map.of(
                "demo/App.java", "package demo; public class App { }"
        ));
        Path libsDir = tempDir.resolve("libs");
        Files.createDirectories(libsDir);
        Path warPath = TestFixtures.createJar(libsDir.resolve("demo-1.2.3.war"), classesDir);
        Files.writeString(libsDir.resolve("README.txt"), "ignore me");

        BuildSkeletonTool buildSkeletonTool = new BuildSkeletonTool();
        JsonObject arguments = new JsonObject();
        arguments.addProperty("path", libsDir.toString());

        ToolResult result = buildSkeletonTool.execute(arguments);
        assertFalse(result.isError());
        assertEquals(1, result.structuredData().getAsJsonObject().getAsJsonArray("dependencies").size());
        assertTrue(result.structuredData().getAsJsonObject().get("mvnDeployBat").getAsString().contains("-Dpackaging=war"));
        assertTrue(result.structuredData().getAsJsonObject().get("mvnDeployBat").getAsString().contains(warPath.toString()));
    }

    @Test
    void testSourceLookupSaveToFileInCurrentDirectory(@TempDir Path tempDir) throws Exception {
        Map<String, String> sources = Map.of(
                "demo/App.java", "package demo; public class App { }"
        );
        Path sourcesJar = TestFixtures.createSourcesJar(tempDir.resolve("demo-sources.jar"), sources);
        SourceLookupTool tool = new SourceLookupTool();
        JsonObject arguments = new JsonObject();
        arguments.addProperty("sourceJarPath", sourcesJar.toString());
        arguments.addProperty("className", "demo.App");
        arguments.addProperty("saveTo", tempDir.resolve("App.java").toString());

        ToolResult result = tool.execute(arguments);
        assertFalse(result.isError());
        assertTrue(Files.exists(tempDir.resolve("App.java")));
    }

    @Test
    void testRepositoryCredentialsAreNotSentOutsideConfiguredPath(@TempDir Path tempDir) throws Exception {
        byte[] sourcesJarBytes = createSourcesJarBytes(Map.of(
                "demo/App.java", "package demo; public class App { }"
        ));
        try (PathScopedAuthServer server = new PathScopedAuthServer(sourcesJarBytes)) {
            SourceLookupTool tool = new SourceLookupTool();
            JsonObject arguments = new JsonObject();
            arguments.addProperty("className", "demo.App");
            arguments.addProperty("groupId", "com.example");
            arguments.addProperty("artifactId", "demo-lib");
            arguments.addProperty("version", "1.0.0");
            arguments.addProperty("searchProvider", "nexus3");
            arguments.addProperty("searchBaseUrl", server.searchBaseUrl());
            arguments.addProperty("remoteContentBaseUrl", server.remoteBaseUrl());
            arguments.addProperty("username", "user");
            arguments.addProperty("password", "pass");

            ToolResult result = tool.execute(arguments);
            assertFalse(result.isError());
            assertTrue(server.outsideDownloadSawNoAuthorization);
        }
    }

    private static byte[] createSourcesJarBytes(Map<String, String> sources) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(output)) {
            for (Map.Entry<String, String> entry : sources.entrySet()) {
                jar.putNextEntry(new JarEntry(entry.getKey()));
                jar.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                jar.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private static final class MockMavenServer implements AutoCloseable {
        private final HttpServer server;
        private final String sha1;
        private final byte[] sourcesJarBytes;

        private MockMavenServer(String sha1, byte[] sourcesJarBytes) throws IOException {
            this.sha1 = sha1;
            this.sourcesJarBytes = sourcesJarBytes;
            this.server = HttpServer.create(new InetSocketAddress(0), 0);
            this.server.setExecutor(Executors.newCachedThreadPool());
            this.server.createContext("/solrsearch/select", this::handleSearch);
            this.server.createContext("/remotecontent", this::handleRemoteContent);
            this.server.start();
        }

        String searchBaseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/solrsearch/select";
        }

        String remoteBaseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/remotecontent?filepath=";
        }

        @Override
        public void close() {
            server.stop(0);
        }

        private void handleSearch(HttpExchange exchange) throws IOException {
            String json = """
                    {
                      "response": {
                        "docs": [
                          {
                            "g": "com.example",
                            "a": "demo-lib",
                            "v": "1.2.3",
                            "p": "jar"
                          }
                        ]
                      }
                    }
                    """;
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        }

        private void handleRemoteContent(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().add("Content-Type", "application/java-archive");
            exchange.sendResponseHeaders(200, sourcesJarBytes.length);
            exchange.getResponseBody().write(sourcesJarBytes);
            exchange.close();
        }
    }

    private static final class MockNexusServer implements AutoCloseable {
        private final HttpServer server;
        private final String sha1;
        private final byte[] sourcesJarBytes;

        private MockNexusServer(String sha1, byte[] sourcesJarBytes) throws IOException {
            this.sha1 = sha1;
            this.sourcesJarBytes = sourcesJarBytes;
            this.server = HttpServer.create(new InetSocketAddress(0), 0);
            this.server.setExecutor(Executors.newCachedThreadPool());
            this.server.createContext("/service/rest/v1/search/assets", this::handleSearch);
            this.server.createContext("/repository/maven-snapshots/com/example/demo-lib/1.2.3/demo-lib-1.2.3-sources.jar", this::handleDownload);
            this.server.start();
        }

        String searchBaseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/service/rest/v1/search/assets";
        }

        @Override
        public void close() {
            server.stop(0);
        }

        private void handleSearch(HttpExchange exchange) throws IOException {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            String basic = "Basic " + Base64.getEncoder().encodeToString("user:pass".getBytes(StandardCharsets.UTF_8));
            if (auth != null && !(auth.equals(basic) || auth.equals("Bearer secret-token"))) {
                exchange.sendResponseHeaders(401, -1);
                exchange.close();
                return;
            }
            String query = exchange.getRequestURI().getRawQuery();
            assertTrue(query != null && (query.contains("sha1=" + sha1) || query.contains("maven.classifier=sources")));
            String downloadUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/repository/maven-snapshots/com/example/demo-lib/1.2.3/demo-lib-1.2.3-sources.jar";
            String json = """
                    {
                      "items": [
                        {
                          "downloadUrl": "%s",
                          "maven2": {
                            "groupId": "com.example",
                            "artifactId": "demo-lib",
                            "version": "1.2.3",
                            "classifier": "sources",
                            "extension": "jar"
                          }
                        }
                      ]
                    }
                    """.formatted(downloadUrl);
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        }

        private void handleDownload(HttpExchange exchange) throws IOException {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            String basic = "Basic " + Base64.getEncoder().encodeToString("user:pass".getBytes(StandardCharsets.UTF_8));
            assertEquals(basic, auth);
            exchange.getResponseHeaders().add("Content-Type", "application/java-archive");
            exchange.sendResponseHeaders(200, sourcesJarBytes.length);
            exchange.getResponseBody().write(sourcesJarBytes);
            exchange.close();
        }
    }

    private static final class MockNexus2Server implements AutoCloseable {
        private final HttpServer server;
        private final String sha1;
        private final byte[] sourcesJarBytes;

        private MockNexus2Server(String sha1, byte[] sourcesJarBytes) throws IOException {
            this.sha1 = sha1;
            this.sourcesJarBytes = sourcesJarBytes;
            this.server = HttpServer.create(new InetSocketAddress(0), 0);
            this.server.setExecutor(Executors.newCachedThreadPool());
            this.server.createContext("/service/local/lucene/search", this::handleSearch);
            this.server.createContext("/service/local/artifact/maven/content", this::handleDownload);
            this.server.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }

        private void handleSearch(HttpExchange exchange) throws IOException {
            String query = exchange.getRequestURI().getRawQuery();
            assertTrue(query != null && (query.contains("sha1=" + sha1) || query.contains("g=com.example")));
            String xml = """
                    <searchNGResponse>
                      <data>
                        <artifact>
                          <groupId>com.example</groupId>
                          <artifactId>demo-lib</artifactId>
                          <version>1.2.3</version>
                          <artifactHits>
                            <artifactHit>
                              <repositoryId>releases</repositoryId>
                              <artifactLinks>
                                <artifactLink>
                                  <classifier>sources</classifier>
                                  <extension>jar</extension>
                                </artifactLink>
                              </artifactLinks>
                            </artifactHit>
                          </artifactHits>
                        </artifact>
                      </data>
                    </searchNGResponse>
                    """;
            byte[] body = xml.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/xml");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        }

        private void handleDownload(HttpExchange exchange) throws IOException {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            String basic = "Basic " + Base64.getEncoder().encodeToString("user:pass".getBytes(StandardCharsets.UTF_8));
            assertEquals(basic, auth);
            String query = exchange.getRequestURI().getRawQuery();
            assertTrue(query != null && query.contains("r=releases") && query.contains("g=com.example"));
            exchange.getResponseHeaders().add("Content-Type", "application/java-archive");
            exchange.sendResponseHeaders(200, sourcesJarBytes.length);
            exchange.getResponseBody().write(sourcesJarBytes);
            exchange.close();
        }
    }

    private static final class PathScopedAuthServer implements AutoCloseable {
        private final HttpServer server;
        private final byte[] sourcesJarBytes;
        private volatile boolean outsideDownloadSawNoAuthorization;

        private PathScopedAuthServer(byte[] sourcesJarBytes) throws IOException {
            this.sourcesJarBytes = sourcesJarBytes;
            this.server = HttpServer.create(new InetSocketAddress(0), 0);
            this.server.setExecutor(Executors.newCachedThreadPool());
            this.server.createContext("/repo/search/assets", this::handleSearch);
            this.server.createContext("/other/remotecontent", this::handleRemoteContent);
            this.server.start();
        }

        String searchBaseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/repo/search/assets";
        }

        String remoteBaseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/repo/content/remotecontent?filepath=";
        }

        @Override
        public void close() {
            server.stop(0);
        }

        private void handleSearch(HttpExchange exchange) throws IOException {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            assertNotNull(auth);
            String downloadUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/other/remotecontent";
            String json = """
                    {
                      "items": [
                        {
                          "downloadUrl": "%s",
                          "maven2": {
                            "groupId": "com.example",
                            "artifactId": "demo-lib",
                            "version": "1.0.0",
                            "classifier": "sources",
                            "extension": "jar"
                          }
                        }
                      ]
                    }
                    """.formatted(downloadUrl);
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        }

        private void handleRemoteContent(HttpExchange exchange) throws IOException {
            outsideDownloadSawNoAuthorization = exchange.getRequestHeaders().getFirst("Authorization") == null;
            exchange.getResponseHeaders().add("Content-Type", "application/java-archive");
            exchange.sendResponseHeaders(200, sourcesJarBytes.length);
            exchange.getResponseBody().write(sourcesJarBytes);
            exchange.close();
        }
    }
}
