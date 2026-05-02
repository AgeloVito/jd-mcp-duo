package support;

import archive.InputContainers;
import index.IndexedResource;
import index.TypeReferenceHit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;

public final class ResourceSearchSupport {
    private static final int MAX_TEXT_BYTES = 1_000_000;
    private static final Pattern FQCN = Pattern.compile("(?<![\\w$/])(?:[A-Za-z_$][\\w$]*\\.)+[A-Za-z_$][\\w$]*(?:\\$[A-Za-z_$][\\w$]*)*");
    private static final Set<String> XML_CLASS_PATHS = Set.of(
            "web-app/filter/filter-class",
            "web-app/listener/listener-class",
            "web-app/servlet/servlet-class",
            "ejb-jar/assembly-descriptor/application-exception/exception-class",
            "ejb-jar/assembly-descriptor/interceptor-binding/interceptor-class",
            "ejb-jar/enterprise-beans/entity/home",
            "ejb-jar/enterprise-beans/entity/remote",
            "ejb-jar/enterprise-beans/entity/ejb-class",
            "ejb-jar/enterprise-beans/entity/prim-key-class",
            "ejb-jar/enterprise-beans/message-driven/ejb-class",
            "ejb-jar/enterprise-beans/message-driven/messaging-type",
            "ejb-jar/enterprise-beans/message-driven/resource-ref/injection-target/injection-target-class",
            "ejb-jar/enterprise-beans/message-driven/resource-env-ref/injection-target/injection-target-class",
            "ejb-jar/enterprise-beans/session/home",
            "ejb-jar/enterprise-beans/session/local",
            "ejb-jar/enterprise-beans/session/remote",
            "ejb-jar/enterprise-beans/session/business-local",
            "ejb-jar/enterprise-beans/session/business-remote",
            "ejb-jar/enterprise-beans/session/service-endpoint",
            "ejb-jar/enterprise-beans/session/ejb-class",
            "ejb-jar/enterprise-beans/session/ejb-ref/home",
            "ejb-jar/enterprise-beans/session/ejb-ref/remote",
            "ejb-jar/interceptors/interceptor/around-invoke/class",
            "ejb-jar/interceptors/interceptor/ejb-ref/home",
            "ejb-jar/interceptors/interceptor/ejb-ref/remote",
            "ejb-jar/interceptors/interceptor/interceptor-class"
    );

    private ResourceSearchSupport() {
    }

    public record ResourceHit(Path sourcePath,
                              String entryPath,
                              String resourceType,
                              boolean pathMatch,
                              boolean contentMatch,
                              Integer lineNumber,
                              String snippet) {
    }

    public record SearchSummary(List<ResourceHit> hits, int scannedEntries, long scanMillis) {
    }

    public static SearchSummary search(Path primaryPath,
                                       Path scopePath,
                                       boolean recursive,
                                       String requestedType,
                                       Pattern pattern,
                                       int limit) throws IOException {
        long startedAt = System.nanoTime();
        List<ResourceHit> hits = new ArrayList<>();
        Counter counter = new Counter();
        Path effective = scopePath != null ? scopePath.toAbsolutePath().normalize() : primaryPath.toAbsolutePath().normalize();
        scan(effective, effective, recursive, requestedType.toLowerCase(Locale.ROOT), pattern, hits, counter, limit);
        return new SearchSummary(List.copyOf(hits), counter.scannedEntries, (System.nanoTime() - startedAt) / 1_000_000L);
    }

    public static List<IndexedResource> collectForIndex(Path input) throws IOException {
        Path normalized = input.toAbsolutePath().normalize();
        if (!Files.exists(normalized)) {
            return List.of();
        }
        List<IndexedResource> resources = new ArrayList<>();
        if (Files.isDirectory(normalized)) {
            try (Stream<Path> stream = Files.walk(normalized)) {
                for (Path child : stream.filter(Files::isRegularFile).sorted().toList()) {
                    if (child.toString().endsWith(".class") || isZipBackedArchive(child)) {
                        continue;
                    }
                    String entryName = normalized.relativize(child).toString().replace('\\', '/');
                    IndexedResource resource = toIndexedResource(entryName, Files.readAllBytes(child));
                    if (resource != null) {
                        resources.add(resource);
                    }
                }
            }
            return List.copyOf(resources);
        }
        if (isZipBackedArchive(normalized)) {
            try (ZipFile zipFile = new ZipFile(normalized.toFile())) {
                for (ZipEntry entry : zipFile.stream().filter(item -> !item.isDirectory()).sorted((left, right) -> left.getName().compareTo(right.getName())).toList()) {
                    if (entry.getName().endsWith(".class")) {
                        continue;
                    }
                    IndexedResource resource = toIndexedResource(entry.getName(), zipFile.getInputStream(entry).readAllBytes());
                    if (resource != null) {
                        resources.add(resource);
                    }
                }
            }
        }
        return List.copyOf(resources);
    }

    public static List<TypeReferenceHit> extractTypeReferences(List<IndexedResource> resources) {
        List<TypeReferenceHit> hits = new ArrayList<>();
        for (IndexedResource resource : resources) {
            if (resource.textContent() == null || resource.textContent().isBlank()) {
                continue;
            }
            String sourceOwner = "resource:" + resource.entryPath();
            switch (resource.resourceType()) {
                case "service" -> {
                    String serviceType = serviceTypeFromEntry(resource.entryPath());
                    if (serviceType != null) {
                        hits.add(new TypeReferenceHit(sourceOwner, "service-interface", null, serviceType, "resource-service-interface"));
                    }
                    for (String line : resource.textContent().split("\\R")) {
                        String trimmed = line.strip();
                        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                            continue;
                        }
                        hits.add(new TypeReferenceHit(sourceOwner, "service-provider", null, trimmed.replace('.', '/'), "resource-service-provider"));
                    }
                }
                case "xml" -> hits.addAll(extractXmlTypeReferences(resource, sourceOwner));
                default -> hits.addAll(extractPatternTypeReferences(resource, sourceOwner));
            }
        }
        return List.copyOf(hits);
    }

    private static void scan(Path input,
                             Path root,
                             boolean recursive,
                             String requestedType,
                             Pattern pattern,
                             List<ResourceHit> hits,
                             Counter counter,
                             int limit) throws IOException {
        if (hits.size() >= limit || !Files.exists(input)) {
            return;
        }
        if (Files.isDirectory(input)) {
            try (Stream<Path> stream = recursive ? Files.walk(input) : Files.list(input)) {
                for (Path child : stream.filter(Files::isRegularFile).sorted().toList()) {
                    if (hits.size() >= limit) {
                        return;
                    }
                    if (isZipBackedArchive(child)) {
                        scanArchive(child, requestedType, pattern, hits, counter, limit);
                    } else if (!child.toString().endsWith(".class")) {
                        String relative = root.relativize(child).toString().replace('\\', '/');
                        scanFile(child, relative, child, requestedType, pattern, hits, counter, limit);
                    }
                }
            }
            return;
        }
        if (isZipBackedArchive(input)) {
            scanArchive(input, requestedType, pattern, hits, counter, limit);
            return;
        }
        if (!input.toString().endsWith(".class")) {
            scanFile(input, input.getFileName().toString(), input, requestedType, pattern, hits, counter, limit);
        }
    }

    private static void scanArchive(Path archive,
                                    String requestedType,
                                    Pattern pattern,
                                    List<ResourceHit> hits,
                                    Counter counter,
                                    int limit) throws IOException {
        try (ZipFile zipFile = new ZipFile(archive.toFile())) {
            for (ZipEntry entry : zipFile.stream().filter(item -> !item.isDirectory()).sorted((left, right) -> left.getName().compareTo(right.getName())).toList()) {
                if (hits.size() >= limit) {
                    return;
                }
                String entryName = entry.getName();
                if (entryName.endsWith(".class")) {
                    continue;
                }
                String resourceType = classifyResource(entryName);
                if (resourceType == null || !matchesRequestedType(requestedType, resourceType)) {
                    continue;
                }
                byte[] bytes = zipFile.getInputStream(entry).readAllBytes();
                addHitIfMatched(archive, entryName, bytes, resourceType, pattern, hits, counter, limit);
            }
        } catch (IOException e) {
            if (InputContainers.isArchivePath(archive) && !archive.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".dex")) {
                throw e;
            }
        }
    }

    private static void scanFile(Path file,
                                 String entryName,
                                 Path sourcePath,
                                 String requestedType,
                                 Pattern pattern,
                                 List<ResourceHit> hits,
                                 Counter counter,
                                 int limit) throws IOException {
        String resourceType = classifyResource(entryName);
        if (resourceType == null || !matchesRequestedType(requestedType, resourceType)) {
            return;
        }
        byte[] bytes = Files.readAllBytes(file);
        addHitIfMatched(sourcePath, entryName, bytes, resourceType, pattern, hits, counter, limit);
    }

    private static void addHitIfMatched(Path sourcePath,
                                        String entryName,
                                        byte[] bytes,
                                        String resourceType,
                                        Pattern pattern,
                                        List<ResourceHit> hits,
                                        Counter counter,
                                        int limit) {
        counter.scannedEntries++;
        boolean pathMatch = pattern.matcher(entryName).find();
        if (bytes.length > MAX_TEXT_BYTES) {
            if (pathMatch) {
                hits.add(new ResourceHit(sourcePath, entryName, resourceType, true, false, null, null));
            }
            return;
        }

        String text = decodeText(bytes);
        if (text == null) {
            if (pathMatch) {
                hits.add(new ResourceHit(sourcePath, entryName, resourceType, true, false, null, null));
            }
            return;
        }

        Integer lineNumber = null;
        String snippet = null;
        String[] lines = text.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            if (pattern.matcher(lines[i]).find()) {
                lineNumber = i + 1;
                snippet = lines[i].strip();
                break;
            }
        }
        boolean contentMatch = lineNumber != null;
        if (pathMatch || contentMatch) {
            hits.add(new ResourceHit(sourcePath, entryName, resourceType, pathMatch, contentMatch, lineNumber, snippet));
        }
    }

    private static IndexedResource toIndexedResource(String entryName, byte[] bytes) {
        String resourceType = classifyResource(entryName);
        if (resourceType == null) {
            return null;
        }
        String text = bytes.length > MAX_TEXT_BYTES ? null : decodeText(bytes);
        return new IndexedResource(entryName, resourceType, text);
    }

    private static boolean matchesRequestedType(String requestedType, String resourceType) {
        return switch (requestedType) {
            case "all", "resource", "allresources" -> true;
            case "service", "services" -> "service".equals(resourceType);
            case "yaml", "yml" -> "yaml".equals(resourceType);
            default -> requestedType.equals(resourceType);
        };
    }

    private static String classifyResource(String entryName) {
        String normalized = entryName.replace('\\', '/');
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.startsWith("meta-inf/services/")) {
            return "service";
        }
        if ("meta-inf/manifest.mf".equals(lower)) {
            return "manifest";
        }
        if (lower.endsWith(".xml")) {
            return "xml";
        }
        if (lower.endsWith(".properties")) {
            return "properties";
        }
        if (lower.endsWith(".json")) {
            return "json";
        }
        if (lower.endsWith(".yml") || lower.endsWith(".yaml")) {
            return "yaml";
        }
        if (lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".conf") || lower.endsWith(".cfg") || lower.endsWith(".ini")) {
            return "text";
        }
        return null;
    }

    private static boolean isZipBackedArchive(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".jar")
                || name.endsWith(".war")
                || name.endsWith(".zip")
                || name.endsWith(".jmod")
                || name.endsWith(".aar")
                || name.endsWith(".ear")
                || name.endsWith(".kar")
                || name.endsWith(".apk");
    }

    private static String decodeText(byte[] bytes) {
        if (bytes.length == 0) {
            return "";
        }
        int zeroBytes = 0;
        for (byte value : bytes) {
            if (value == 0) {
                zeroBytes++;
            }
        }
        if (zeroBytes > 0) {
            return null;
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static List<TypeReferenceHit> extractPatternTypeReferences(IndexedResource resource, String sourceOwner) {
        Set<String> seen = new HashSet<>();
        List<TypeReferenceHit> hits = new ArrayList<>();
        Matcher matcher = FQCN.matcher(resource.textContent());
        while (matcher.find()) {
            String fqcn = matcher.group();
            if (seen.add(fqcn)) {
                hits.add(new TypeReferenceHit(sourceOwner, resource.resourceType(), null, fqcn.replace('.', '/'), "resource-" + resource.resourceType()));
            }
        }
        return hits;
    }

    private static List<TypeReferenceHit> extractXmlTypeReferences(IndexedResource resource, String sourceOwner) {
        List<TypeReferenceHit> hits = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try {
            XMLStreamReader reader = XMLInputFactory.newFactory().createXMLStreamReader(new java.io.StringReader(resource.textContent()));
            List<String> path = new ArrayList<>();
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    path.add(reader.getLocalName());
                    for (int i = 0; i < reader.getAttributeCount(); i++) {
                        addFqcnHits(hits, seen, sourceOwner, String.join("/", path) + "/@" + reader.getAttributeLocalName(i), reader.getAttributeValue(i), "resource-xml");
                    }
                } else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                    String currentPath = String.join("/", path);
                    if (XML_CLASS_PATHS.contains(currentPath)) {
                        addExactTypeHit(hits, seen, sourceOwner, currentPath, reader.getText(), "resource-xml-path");
                    } else {
                        addFqcnHits(hits, seen, sourceOwner, currentPath, reader.getText(), "resource-xml");
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT && !path.isEmpty()) {
                    path.remove(path.size() - 1);
                }
            }
        } catch (Exception ignored) {
            return extractPatternTypeReferences(resource, sourceOwner);
        }
        return hits;
    }

    private static void addExactTypeHit(List<TypeReferenceHit> hits,
                                        Set<String> seen,
                                        String sourceOwner,
                                        String sourceMemberName,
                                        String value,
                                        String kind) {
        String trimmed = value == null ? "" : value.strip();
        if (trimmed.isEmpty()) {
            return;
        }
        String internal = trimmed.replace('.', '/');
        if (seen.add(sourceMemberName + "|" + internal)) {
            hits.add(new TypeReferenceHit(sourceOwner, sourceMemberName, null, internal, kind));
        }
    }

    private static void addFqcnHits(List<TypeReferenceHit> hits,
                                    Set<String> seen,
                                    String sourceOwner,
                                    String sourceMemberName,
                                    String text,
                                    String kind) {
        if (text == null || text.isBlank()) {
            return;
        }
        Matcher matcher = FQCN.matcher(text);
        while (matcher.find()) {
            String fqcn = matcher.group();
            if (seen.add(sourceMemberName + "|" + fqcn)) {
                hits.add(new TypeReferenceHit(sourceOwner, sourceMemberName, null, fqcn.replace('.', '/'), kind));
            }
        }
    }

    private static String serviceTypeFromEntry(String entryPath) {
        String prefix = "META-INF/services/";
        if (!entryPath.startsWith(prefix) || entryPath.length() <= prefix.length()) {
            return null;
        }
        return entryPath.substring(prefix.length()).replace('.', '/');
    }

    private static final class Counter {
        private int scannedEntries;
    }
}
