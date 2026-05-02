package support;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

public final class MavenSearchSupport {
    private MavenSearchSupport() {
    }

    public record Endpoints(String searchProvider,
                            String searchBaseUrl,
                            String remoteContentBaseUrl,
                            String proxyHost,
                            Integer proxyPort,
                            String username,
                            String password,
                            String bearerToken) {
        public static Endpoints fromArgs(com.google.gson.JsonObject arguments) {
            com.google.gson.JsonObject merged;
            try {
                merged = RepositoryConfigSupport.mergeWithConfig(arguments);
            } catch (IOException e) {
                throw new IllegalArgumentException("Failed to read repository config: " + e.getMessage(), e);
            }
            String searchProvider = JsonUtils.getString(merged, "searchProvider", "maven-central");
            String searchBaseUrl = JsonUtils.getString(merged, "searchBaseUrl", "https://search.maven.org/solrsearch/select");
            String remoteContentBaseUrl = JsonUtils.getString(merged, "remoteContentBaseUrl", "https://search.maven.org/remotecontent?filepath=");
            String proxyHost = JsonUtils.getString(merged, "proxyHost", null);
            String proxyPortText = JsonUtils.getString(merged, "proxyPort", null);
            Integer proxyPort = proxyPortText == null || proxyPortText.isBlank() ? null : Integer.parseInt(proxyPortText);
            String username = JsonUtils.getString(merged, "username", null);
            String password = JsonUtils.getString(merged, "password", null);
            String bearerToken = JsonUtils.getString(merged, "bearerToken", null);
            return new Endpoints(searchProvider, searchBaseUrl, remoteContentBaseUrl, proxyHost, proxyPort, username, password, bearerToken);
        }
    }

    public record MavenArtifact(String groupId,
                                String artifactId,
                                String version,
                                String packaging,
                                String classifier,
                                String sourceJarUrl) {
        public String gav() {
            return groupId + ":" + artifactId + ":" + version;
        }
    }

    public static List<MavenArtifact> searchBySha1(String sha1, Endpoints endpoints) throws Exception {
        if ("nexus2".equalsIgnoreCase(endpoints.searchProvider())) {
            return executeNexus2Query(List.of("sha1=" + urlEncode(sha1)), endpoints);
        }
        if ("nexus3".equalsIgnoreCase(endpoints.searchProvider())) {
            return executeNexusQuery(List.of("sha1=" + urlEncode(sha1), "maven.classifier=sources"), endpoints);
        }
        return executeMavenCentralQuery("1:\"" + sha1 + "\"", endpoints);
    }

    public static List<MavenArtifact> searchByGav(String groupId, String artifactId, String version, Endpoints endpoints) throws Exception {
        if ("nexus2".equalsIgnoreCase(endpoints.searchProvider())) {
            List<String> params = new ArrayList<>();
            appendParam(params, "g", groupId);
            appendParam(params, "a", artifactId);
            appendParam(params, "v", version);
            params.add("c=" + urlEncode("sources"));
            params.add("p=" + urlEncode("jar"));
            return executeNexus2Query(params, endpoints);
        }
        if ("nexus3".equalsIgnoreCase(endpoints.searchProvider())) {
            List<String> params = new ArrayList<>();
            appendParam(params, "maven.groupId", groupId);
            appendParam(params, "maven.artifactId", artifactId);
            appendParam(params, "maven.baseVersion", version);
            params.add("maven.classifier=sources");
            return executeNexusQuery(params, endpoints);
        }
        StringBuilder query = new StringBuilder();
        appendTerm(query, "g", groupId);
        appendTerm(query, "a", artifactId);
        appendTerm(query, "v", version);
        return executeMavenCentralQuery(query.toString(), endpoints);
    }

    public static String fetchSources(MavenArtifact artifact, String entryName, Endpoints endpoints) throws Exception {
        HttpResponse<byte[]> response = client(endpoints).send(
                request(URI.create(artifact.sourceJarUrl()), endpoints).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray()
        );
        if (response.statusCode() / 100 != 2) {
            return null;
        }
        try (java.util.zip.ZipInputStream zipInputStream = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(response.body()))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                if (entryName.equals(entry.getName())) {
                    return new String(zipInputStream.readAllBytes(), StandardCharsets.UTF_8);
                }
                zipInputStream.closeEntry();
            }
        }
        return null;
    }

    private static List<MavenArtifact> executeMavenCentralQuery(String query, Endpoints endpoints) throws Exception {
        String url = endpoints.searchBaseUrl()
                + "?wt=json&rows=20&q="
                + urlEncode(query);
        HttpResponse<String> response = client(endpoints).send(
                request(URI.create(url), endpoints).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        if (response.statusCode() / 100 != 2) {
            return List.of();
        }

        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        JsonObject responseNode = root.getAsJsonObject("response");
        if (responseNode == null) {
            return List.of();
        }
        JsonArray docs = responseNode.getAsJsonArray("docs");
        if (docs == null) {
            return List.of();
        }

        List<MavenArtifact> artifacts = new ArrayList<>();
        docs.forEach(item -> {
            JsonObject doc = item.getAsJsonObject();
            String groupId = doc.has("g") ? doc.get("g").getAsString() : null;
            String artifactId = doc.has("a") ? doc.get("a").getAsString() : null;
            String version = doc.has("v") ? doc.get("v").getAsString() : doc.has("latestVersion") ? doc.get("latestVersion").getAsString() : null;
            if (groupId == null || artifactId == null || version == null) {
                return;
            }
            String packaging = doc.has("p") ? doc.get("p").getAsString() : "jar";
            String classifier = "sources";
            String sourceUrl = buildSourceUrl(groupId, artifactId, version, classifier, packaging, endpoints);
            artifacts.add(new MavenArtifact(groupId, artifactId, version, packaging, classifier, sourceUrl));
        });
        return artifacts;
    }

    private static List<MavenArtifact> executeNexus2Query(List<String> params, Endpoints endpoints) throws Exception {
        List<String> allParams = new ArrayList<>(params);
        allParams.add("from=0");
        allParams.add("count=20");
        String queryString = String.join("&", allParams);
        String url = nexus2SearchUrl(endpoints) + (nexus2SearchUrl(endpoints).contains("?") ? "&" : "?") + queryString;
        HttpResponse<String> response = client(endpoints).send(
                request(URI.create(url), endpoints).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        if (response.statusCode() / 100 != 2) {
            return List.of();
        }
        return parseNexus2Artifacts(response.body(), endpoints);
    }

    private static List<MavenArtifact> executeNexusQuery(List<String> params, Endpoints endpoints) throws Exception {
        String queryString = String.join("&", params);
        String separator = endpoints.searchBaseUrl().contains("?") ? "&" : "?";
        String url = endpoints.searchBaseUrl() + separator + queryString;
        HttpResponse<String> response = client(endpoints).send(
                request(URI.create(url), endpoints).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        if (response.statusCode() / 100 != 2) {
            return List.of();
        }
        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        JsonArray items = root.getAsJsonArray("items");
        if (items == null) {
            return List.of();
        }

        List<MavenArtifact> artifacts = new ArrayList<>();
        items.forEach(item -> {
            JsonObject node = item.getAsJsonObject();
            JsonObject maven2 = node.has("maven2") && node.get("maven2").isJsonObject()
                    ? node.getAsJsonObject("maven2")
                    : null;
            String groupId = stringValue(maven2, "groupId");
            String artifactId = stringValue(maven2, "artifactId");
            String version = stringValue(maven2, "version");
            String classifier = stringValue(maven2, "classifier");
            String extension = stringValue(maven2, "extension");
            String downloadUrl = node.has("downloadUrl") ? node.get("downloadUrl").getAsString() : null;
            if (groupId == null || artifactId == null || version == null || downloadUrl == null) {
                return;
            }
            artifacts.add(new MavenArtifact(
                    groupId,
                    artifactId,
                    version,
                    extension == null || extension.isBlank() ? "jar" : extension,
                    classifier == null || classifier.isBlank() ? "sources" : classifier,
                    downloadUrl
            ));
        });
        return artifacts;
    }

    private static String buildSourceUrl(String groupId, String artifactId, String version, String classifier, String packaging, Endpoints endpoints) {
        return endpoints.remoteContentBaseUrl()
                + groupId.replace('.', '/')
                + "/"
                + artifactId
                + "/"
                + version
                + "/"
                + artifactId
                + "-"
                + version
                + "-"
                + classifier
                + "."
                + packaging;
    }

    private static void appendTerm(StringBuilder builder, String key, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append(" AND ");
        }
        builder.append(key).append(':').append(value);
    }

    private static void appendParam(List<String> params, String key, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        params.add(key + "=" + urlEncode(value));
    }

    private static volatile HttpClient cachedClient;
    private static volatile String cachedProxyKey;

    private static HttpClient client(Endpoints endpoints) {
        String proxyKey = (endpoints.proxyHost() != null && endpoints.proxyPort() != null)
                ? endpoints.proxyHost() + ":" + endpoints.proxyPort()
                : "";
        HttpClient client = cachedClient;
        if (client != null && proxyKey.equals(cachedProxyKey)) {
            return client;
        }
        synchronized (MavenSearchSupport.class) {
            if (cachedClient == null || !proxyKey.equals(cachedProxyKey)) {
                HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30));
                if (!proxyKey.isEmpty()) {
                    builder.proxy(ProxySelector.of(new InetSocketAddress(endpoints.proxyHost(), endpoints.proxyPort())));
                }
                cachedClient = builder.build();
                cachedProxyKey = proxyKey;
            }
            return cachedClient;
        }
    }

    private static HttpRequest.Builder request(URI uri, Endpoints endpoints) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30));
        if (shouldAuthorize(uri, endpoints) && endpoints.bearerToken() != null && !endpoints.bearerToken().isBlank()) {
            builder.header("Authorization", "Bearer " + endpoints.bearerToken());
        } else if (shouldAuthorize(uri, endpoints) && endpoints.username() != null && endpoints.password() != null) {
            String credentials = endpoints.username() + ":" + endpoints.password();
            String encoded = java.util.Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.ISO_8859_1));
            builder.header("Authorization", "Basic " + encoded);
        }
        return builder;
    }

    private static String stringValue(JsonObject object, String key) {
        return object != null && object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsString()
                : null;
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String nexus2SearchUrl(Endpoints endpoints) {
        String base = endpoints.searchBaseUrl();
        if (base.contains("/service/local/lucene/search")) {
            return base;
        }
        return trimTrailingSlash(base) + "/service/local/lucene/search";
    }

    private static String nexus2BaseUrl(Endpoints endpoints) {
        String base = endpoints.searchBaseUrl();
        int marker = base.indexOf("/service/local/lucene/search");
        if (marker >= 0) {
            return base.substring(0, marker);
        }
        return trimTrailingSlash(base);
    }

    private static List<MavenArtifact> parseNexus2Artifacts(String xml, Endpoints endpoints) {
        List<MavenArtifact> artifacts = new ArrayList<>();
        if (xml == null || xml.isBlank()) {
            return artifacts;
        }
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            dbf.setXIncludeAware(false);
            dbf.setExpandEntityReferences(false);
            DocumentBuilder builder = dbf.newDocumentBuilder();
            Document document = builder.parse(new java.io.ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            NodeList artifactNodes = document.getElementsByTagName("artifact");
            for (int i = 0; i < artifactNodes.getLength(); i++) {
                Element artifactElement = (Element) artifactNodes.item(i);
                String groupId = text(artifactElement, "groupId");
                String artifactId = text(artifactElement, "artifactId");
                String version = text(artifactElement, "version");
                NodeList hitNodes = artifactElement.getElementsByTagName("artifactHit");
                for (int h = 0; h < hitNodes.getLength(); h++) {
                    Element hitElement = (Element) hitNodes.item(h);
                    String repositoryId = text(hitElement, "repositoryId");
                    NodeList links = hitElement.getElementsByTagName("artifactLink");
                    for (int l = 0; l < links.getLength(); l++) {
                        Element linkElement = (Element) links.item(l);
                        String classifier = text(linkElement, "classifier");
                        String extension = text(linkElement, "extension");
                        artifacts.add(new MavenArtifact(
                                groupId,
                                artifactId,
                                version,
                                extension == null || extension.isBlank() ? "jar" : extension,
                                classifier,
                                buildNexus2DownloadUrl(nexus2BaseUrl(endpoints), groupId, artifactId, version, extension, classifier, repositoryId)
                        ));
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return artifacts;
    }

    private static String buildNexus2DownloadUrl(String baseUrl,
                                                 String groupId,
                                                 String artifactId,
                                                 String version,
                                                 String packaging,
                                                 String classifier,
                                                 String repositoryId) {
        StringBuilder url = new StringBuilder(trimTrailingSlash(baseUrl)).append("/service/local/artifact/maven/content");
        appendUrlParam(url, "r", repositoryId);
        appendUrlParam(url, "g", groupId);
        appendUrlParam(url, "a", artifactId);
        appendUrlParam(url, "v", version);
        appendUrlParam(url, "p", packaging == null || packaging.isBlank() ? "jar" : packaging);
        if (classifier != null && !classifier.isBlank()) {
            appendUrlParam(url, "c", classifier);
        }
        return url.toString();
    }

    private static void appendUrlParam(StringBuilder builder, String key, String value) {
        builder.append(builder.indexOf("?") >= 0 ? '&' : '?')
                .append(key).append('=').append(urlEncode(value));
    }

    private static String text(Element element, String tag) {
        NodeList nodes = element.getElementsByTagName(tag);
        if (nodes.getLength() == 0) {
            return null;
        }
        String value = nodes.item(0).getTextContent();
        return value == null ? null : value.trim();
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static boolean shouldAuthorize(URI targetUri, Endpoints endpoints) {
        return matchesBase(targetUri, endpoints.searchBaseUrl())
                || matchesBase(targetUri, endpoints.remoteContentBaseUrl())
                || matchesDerivedRepositoryPath(targetUri, endpoints.searchBaseUrl())
                || matchesDerivedRepositoryPath(targetUri, endpoints.remoteContentBaseUrl());
    }

    private static boolean matchesBase(URI targetUri, String baseUrl) {
        if (targetUri == null || baseUrl == null || baseUrl.isBlank()) {
            return false;
        }
        URI baseUri;
        try {
            baseUri = URI.create(baseUrl);
        } catch (Exception ignored) {
            return false;
        }
        if (baseUri.getScheme() == null || baseUri.getHost() == null || targetUri.getScheme() == null || targetUri.getHost() == null) {
            return false;
        }
        return baseUri.getScheme().equalsIgnoreCase(targetUri.getScheme())
                && baseUri.getHost().equalsIgnoreCase(targetUri.getHost())
                && effectivePort(baseUri) == effectivePort(targetUri)
                && isUnderBasePath(targetUri.getPath(), baseUri.getPath());
    }

    private static boolean matchesDerivedRepositoryPath(URI targetUri, String baseUrl) {
        if (targetUri == null || baseUrl == null || baseUrl.isBlank()) {
            return false;
        }
        URI baseUri;
        try {
            baseUri = URI.create(baseUrl);
        } catch (Exception ignored) {
            return false;
        }
        if (baseUri.getScheme() == null || baseUri.getHost() == null || targetUri.getScheme() == null || targetUri.getHost() == null) {
            return false;
        }
        if (!baseUri.getScheme().equalsIgnoreCase(targetUri.getScheme())
                || !baseUri.getHost().equalsIgnoreCase(targetUri.getHost())
                || effectivePort(baseUri) != effectivePort(targetUri)) {
            return false;
        }

        String basePath = normalizeBasePath(baseUri.getPath());
        String targetPath = targetUri.getPath() == null || targetUri.getPath().isBlank() ? "/" : targetUri.getPath();
        if (basePath.startsWith("/service/rest/") && targetPath.startsWith("/repository/")) {
            return true;
        }
        return basePath.startsWith("/service/local/") && targetPath.startsWith("/service/local/");
    }

    private static boolean isUnderBasePath(String targetPath, String basePath) {
        String normalizedBase = normalizeBasePath(basePath);
        String normalizedTarget = targetPath == null || targetPath.isBlank() ? "/" : targetPath;
        if ("/".equals(normalizedBase)) {
            return true;
        }
        return normalizedTarget.equals(normalizedBase) || normalizedTarget.startsWith(normalizedBase + "/");
    }

    private static String normalizeBasePath(String basePath) {
        if (basePath == null || basePath.isBlank()) {
            return "/";
        }
        String normalized = basePath;
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.isBlank() ? "/" : normalized;
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        if ("http".equalsIgnoreCase(uri.getScheme())) {
            return 80;
        }
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            return 443;
        }
        return -1;
    }
}
