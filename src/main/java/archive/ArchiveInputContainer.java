package archive;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

public final class ArchiveInputContainer implements InputContainer {
    private final Path path;
    private final String kind;
    private final int targetReleaseVersion;
    private final ZipFile zipFile;
    private final Map<String, byte[]> nestedEntries;
    private final Map<String, ClassLocation> effectiveClasses;
    private final Map<String, NestedPrimaryClass> nestedPrimaryClasses;
    private final List<NestedDependencyArchive> dependencyArchives;
    private final List<Path> temporaryDependencyArchives;
    private final ArtifactCoordinates artifactCoordinates;
    private volatile List<ResourceEntry> cachedResources;

    public ArchiveInputContainer(Path path, Integer releaseVersion) throws IOException {
        this.path = path.toAbsolutePath().normalize();
        this.targetReleaseVersion = releaseVersion != null ? releaseVersion : Runtime.version().feature();
        String fileName = this.path.getFileName().toString().toLowerCase();
        this.kind = fileName.substring(fileName.lastIndexOf('.') + 1);
        if (fileName.endsWith(".dex")) {
            this.zipFile = null;
            this.nestedEntries = DexToClassMapConverter.getOrCreate(this.path);
            this.effectiveClasses = buildEffectiveClassesFromNestedEntries();
            this.nestedPrimaryClasses = Map.of();
            this.dependencyArchives = List.of();
            this.temporaryDependencyArchives = List.of();
            this.artifactCoordinates = null;
        } else if (fileName.endsWith(".apk")) {
            this.zipFile = null;
            this.nestedEntries = loadApkClasses(this.path);
            this.effectiveClasses = buildEffectiveClassesFromNestedEntries();
            this.nestedPrimaryClasses = Map.of();
            this.dependencyArchives = List.of();
            this.temporaryDependencyArchives = List.of();
            this.artifactCoordinates = null;
        } else if (fileName.endsWith(".aar")) {
            this.zipFile = null;
            AARContent aarContent = loadAarContent(this.path);
            this.nestedEntries = aarContent.classEntries();
            this.effectiveClasses = buildEffectiveClassesFromNestedEntries();
            this.nestedPrimaryClasses = Map.of();
            this.dependencyArchives = aarContent.dependencies();
            this.temporaryDependencyArchives = aarContent.temporaryFiles();
            this.artifactCoordinates = detectArtifactCoordinates(this.nestedEntries);
        } else {
            this.zipFile = new ZipFile(this.path.toFile());
            try {
                this.nestedEntries = null;
                Map<String, ClassLocation> classes = buildEffectiveClassesFromZip();
                ExtractedDependencies extractedDependencies = extractNestedDependencies(this.zipFile, name -> isDependencyArchiveEntry(name, false));
                this.nestedPrimaryClasses = shouldExposeNestedModulesAsPrimary()
                        ? buildNestedPrimaryClasses(extractedDependencies.archives(), classes)
                        : Map.of();
                this.effectiveClasses = Map.copyOf(classes);
                this.dependencyArchives = extractedDependencies.archives();
                this.temporaryDependencyArchives = extractedDependencies.temporaryFiles();
                this.artifactCoordinates = detectArtifactCoordinates(this.zipFile);
            } catch (RuntimeException | IOException e) {
                try {
                    this.zipFile.close();
                } catch (IOException suppressed) {
                    e.addSuppressed(suppressed);
                }
                throw e;
            }
        }
    }

    @Override
    public Path path() {
        return path;
    }

    @Override
    public String kind() {
        return kind;
    }

    @Override
    public URI contextUri() {
        return path.toUri();
    }

    @Override
    public List<ClassLocation> listClasses(boolean includeInnerClasses) {
        return effectiveClasses.values().stream()
                .filter(location -> includeInnerClasses || !location.internalName().contains("$"))
                .sorted(Comparator.comparing(ClassLocation::internalName))
                .toList();
    }

    @Override
    public ClassLocation resolveClass(String classNameOrInternal) {
        return effectiveClasses.get(InputContainers.normalizeClassName(classNameOrInternal));
    }

    @Override
    public byte[] loadClassBytes(String internalName) throws IOException {
        ClassLocation location = effectiveClasses.get(InputContainers.normalizeClassName(internalName));
        if (location == null) {
            return null;
        }

        if (nestedEntries != null) {
            return nestedEntries.get(location.entryName());
        }

        NestedPrimaryClass nestedPrimaryClass = nestedPrimaryClasses.get(location.entryName());
        if (nestedPrimaryClass != null) {
            try (InputContainer nestedContainer = InputContainers.open(nestedPrimaryClass.archivePath(), targetReleaseVersion)) {
                return nestedContainer.loadClassBytes(nestedPrimaryClass.internalName());
            }
        }

        ZipEntry entry = zipFile.getEntry(location.entryName());
        if (entry == null) {
            return null;
        }
        try (InputStream inputStream = zipFile.getInputStream(entry)) {
            return inputStream.readAllBytes();
        }
    }

    @Override
    public List<ResourceEntry> listResources() {
        List<ResourceEntry> cached = cachedResources;
        if (cached != null) {
            return cached;
        }
        if (zipFile == null) {
            cachedResources = List.of();
            return cachedResources;
        }
        List<ResourceEntry> resources = zipFile.stream()
                .filter(entry -> !entry.isDirectory())
                .filter(entry -> !entry.getName().endsWith(".class"))
                .filter(entry -> !isDependencyArchiveEntry(entry.getName(), false))
                .map(entry -> new ResourceEntry(entry.getName()))
                .sorted(Comparator.comparing(ResourceEntry::entryName))
                .toList();
        cachedResources = resources;
        return resources;
    }

    @Override
    public byte[] loadResourceBytes(String entryName) throws IOException {
        if (zipFile == null) {
            return null;
        }
        ZipEntry entry = zipFile.getEntry(entryName);
        if (entry == null) {
            return null;
        }
        try (InputStream inputStream = zipFile.getInputStream(entry)) {
            return inputStream.readAllBytes();
        }
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        if (zipFile != null) {
            try {
                zipFile.close();
            } catch (IOException e) {
                failure = e;
            }
        }
        for (Path tempFile : temporaryDependencyArchives) {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    public List<NestedDependencyArchive> dependencyArchives() {
        return dependencyArchives;
    }

    public ArtifactCoordinates artifactCoordinates() {
        return artifactCoordinates;
    }

    private Map<String, ClassLocation> buildEffectiveClassesFromZip() {
        Map<String, ClassLocation> classes = new LinkedHashMap<>();
        zipFile.stream()
                .filter(entry -> !entry.isDirectory())
                .map(ZipEntry::getName)
                .forEach(entryName -> {
                    ClassLocation location = toClassLocation(entryName, "jmod".equals(kind));
                    if (location != null) {
                        classes.merge(location.internalName(), location, this::preferLocation);
                    }
                });
        return classes;
    }

    private Map<String, NestedPrimaryClass> buildNestedPrimaryClasses(List<NestedDependencyArchive> dependencies,
                                                                      Map<String, ClassLocation> classes) throws IOException {
        Map<String, NestedPrimaryClass> nestedClasses = new LinkedHashMap<>();
        for (NestedDependencyArchive dependency : dependencies) {
            try (InputContainer nestedContainer = InputContainers.open(dependency.extractedPath(), targetReleaseVersion)) {
                for (ClassLocation location : nestedContainer.listClasses(true)) {
                    String syntheticEntryName = dependency.entryName() + "!/" + location.entryName();
                    ClassLocation exposed = new ClassLocation(
                            location.internalName(),
                            syntheticEntryName,
                            location.displayName(),
                            location.multiReleaseVersion()
                    );
                    ClassLocation selected = classes.containsKey(location.internalName())
                            ? preferLocation(classes.get(location.internalName()), exposed)
                            : exposed;
                    classes.put(location.internalName(), selected);
                    if (selected.equals(exposed)) {
                        nestedClasses.put(syntheticEntryName, new NestedPrimaryClass(dependency.extractedPath(), location.internalName()));
                    }
                }
            }
        }
        return Map.copyOf(nestedClasses);
    }

    private Map<String, ClassLocation> buildEffectiveClassesFromNestedEntries() {
        Map<String, ClassLocation> classes = new LinkedHashMap<>();
        for (String entryName : nestedEntries.keySet()) {
            ClassLocation location = toClassLocation(entryName, false);
            if (location != null) {
                classes.merge(location.internalName(), location, this::preferLocation);
            }
        }
        return classes;
    }

    private ClassLocation preferLocation(ClassLocation left, ClassLocation right) {
        Integer leftVersion = left.multiReleaseVersion();
        Integer rightVersion = right.multiReleaseVersion();
        if (Objects.equals(leftVersion, rightVersion)) {
            return left;
        }
        boolean leftEligible = leftVersion == null || leftVersion <= targetReleaseVersion;
        boolean rightEligible = rightVersion == null || rightVersion <= targetReleaseVersion;
        if (leftEligible != rightEligible) {
            return leftEligible ? left : right;
        }
        if (leftVersion == null) {
            return right;
        }
        if (rightVersion == null) {
            return left;
        }
        return leftVersion >= rightVersion ? left : right;
    }

    private static AARContent loadAarContent(Path aarPath) throws IOException {
        try (ZipFile aar = new ZipFile(aarPath.toFile())) {
            ZipEntry classesJar = aar.getEntry("classes.jar");
            if (classesJar == null) {
                throw new IOException("AAR does not contain classes.jar: " + aarPath);
            }

            Map<String, byte[]> entries = new LinkedHashMap<>();
            try (InputStream classesJarStream = aar.getInputStream(classesJar);
                 ZipInputStream zipInputStream = new ZipInputStream(classesJarStream)) {
                ZipEntry entry;
                while ((entry = zipInputStream.getNextEntry()) != null) {
                    if (!entry.isDirectory()) {
                        entries.put(entry.getName(), zipInputStream.readAllBytes());
                    }
                    zipInputStream.closeEntry();
                }
            }

            ExtractedDependencies dependencies = extractNestedDependencies(aar, name -> isDependencyArchiveEntry(name, true));
            return new AARContent(
                    entries,
                    dependencies.archives(),
                    dependencies.temporaryFiles()
            );
        }
    }

    private static Map<String, byte[]> loadApkClasses(Path apkPath) throws IOException {
        try (ZipFile apk = new ZipFile(apkPath.toFile())) {
            Map<String, byte[]> entries = new LinkedHashMap<>();
            boolean foundDex = false;
            for (int index = 1; ; index++) {
                String entryName = index == 1 ? "classes.dex" : "classes" + index + ".dex";
                ZipEntry dexEntry = apk.getEntry(entryName);
                if (dexEntry == null) {
                    break;
                }
                foundDex = true;
                Path tempDex = Files.createTempFile("jd-mcp-duo-", ".dex");
                try {
                    try (InputStream inputStream = apk.getInputStream(dexEntry)) {
                        Files.copy(inputStream, tempDex, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                    entries.putAll(DexToClassMapConverter.getOrCreate(tempDex));
                } finally {
                    Files.deleteIfExists(tempDex);
                }
            }
            if (!foundDex) {
                throw new IOException("APK does not contain classes.dex: " + apkPath);
            }
            return entries;
        }
    }

    private static ClassLocation toClassLocation(String entryName, boolean jmod) {
        if (!entryName.endsWith(".class")) {
            return null;
        }

        String normalized = entryName;
        if (jmod && normalized.startsWith("classes/")) {
            normalized = normalized.substring("classes/".length());
        }

        String[] roots = {
                "BOOT-INF/classes/",
                "WEB-INF/classes/",
                "classes/"
        };
        for (String root : roots) {
            if (normalized.startsWith(root)) {
                normalized = normalized.substring(root.length());
                break;
            }
        }

        Integer multiReleaseVersion = null;
        String prefix = "META-INF/versions/";
        if (normalized.startsWith(prefix)) {
            String suffix = normalized.substring(prefix.length());
            int slash = suffix.indexOf('/');
            if (slash < 0) {
                return null;
            }
            String versionText = suffix.substring(0, slash);
            if (!versionText.matches("\\d+")) {
                return null;
            }
            multiReleaseVersion = Integer.parseInt(versionText);
            normalized = suffix.substring(slash + 1);
        }

        if (!normalized.endsWith(".class")) {
            return null;
        }

        String internalName = normalized.substring(0, normalized.length() - 6);
        return new ClassLocation(internalName, entryName, internalName.replace('/', '.'), multiReleaseVersion);
    }

    private static ExtractedDependencies extractNestedDependencies(ZipFile archive,
                                                                   java.util.function.Predicate<String> includeEntry) throws IOException {
        List<NestedDependencyArchive> archives = new ArrayList<>();
        List<Path> tempFiles = new ArrayList<>();
        for (ZipEntry entry : archive.stream().filter(zipEntry -> !zipEntry.isDirectory()).toList()) {
            String entryName = entry.getName();
            if (!includeEntry.test(entryName)) {
                continue;
            }
            String suffix = extension(entryName);
            Path extracted = Files.createTempFile("jd-mcp-duo-nested-", suffix);
            tempFiles.add(extracted);
            try (InputStream inputStream = archive.getInputStream(entry)) {
                Files.copy(inputStream, extracted, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            if (!isReadableZipDependency(extracted, suffix)) {
                Files.deleteIfExists(extracted);
                tempFiles.remove(extracted);
                continue;
            }
            archives.add(new NestedDependencyArchive(extracted, entryName));
        }
        return new ExtractedDependencies(List.copyOf(archives), List.copyOf(tempFiles));
    }

    private static boolean isReadableZipDependency(Path extracted, String suffix) {
        if (".dex".equalsIgnoreCase(suffix)) {
            return true;
        }
        try (ZipFile ignored = new ZipFile(extracted.toFile())) {
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    private static boolean isDependencyArchiveEntry(String entryName, boolean aar) {
        if (!InputContainers.isArchivePath(Path.of(entryName))) {
            return false;
        }
        String normalized = entryName.replace('\\', '/');
        if (aar && "classes.jar".equals(normalized)) {
            return false;
        }
        return normalized.startsWith("BOOT-INF/lib/")
                || normalized.startsWith("WEB-INF/lib/")
                || normalized.startsWith("APP-INF/lib/")
                || normalized.startsWith("lib/")
                || normalized.startsWith("libs/")
                || normalized.startsWith("dependencies/")
                || normalized.indexOf('/') < 0;
    }

    private boolean shouldExposeNestedModulesAsPrimary() {
        return "ear".equals(kind) || "kar".equals(kind);
    }

    private static String extension(String entryName) {
        String fileName = Path.of(entryName).getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(dot) : ".bin";
    }

    private static ArtifactCoordinates detectArtifactCoordinates(ZipFile archive) throws IOException {
        for (ZipEntry entry : archive.stream().filter(zipEntry -> !zipEntry.isDirectory()).toList()) {
            if (!entry.getName().startsWith("META-INF/maven/") || !entry.getName().endsWith("/pom.properties")) {
                continue;
            }
            try (InputStream inputStream = archive.getInputStream(entry)) {
                ArtifactCoordinates coordinates = readArtifactCoordinates(inputStream);
                if (coordinates != null) {
                    return coordinates;
                }
            }
        }
        return null;
    }

    private static ArtifactCoordinates detectArtifactCoordinates(Map<String, byte[]> entries) throws IOException {
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            String name = entry.getKey();
            if (!name.startsWith("META-INF/maven/") || !name.endsWith("/pom.properties")) {
                continue;
            }
            try (InputStream inputStream = new ByteArrayInputStream(entry.getValue())) {
                ArtifactCoordinates coordinates = readArtifactCoordinates(inputStream);
                if (coordinates != null) {
                    return coordinates;
                }
            }
        }
        return null;
    }

    private static ArtifactCoordinates readArtifactCoordinates(InputStream inputStream) throws IOException {
        Properties properties = new Properties();
        properties.load(inputStream);
        String groupId = properties.getProperty("groupId");
        String artifactId = properties.getProperty("artifactId");
        String version = properties.getProperty("version");
        if (groupId == null || artifactId == null || version == null) {
            return null;
        }
        return new ArtifactCoordinates(groupId, artifactId, version);
    }

    public record NestedDependencyArchive(Path extractedPath, String entryName) {
    }

    public record ArtifactCoordinates(String groupId, String artifactId, String version) {
    }

    private record ExtractedDependencies(List<NestedDependencyArchive> archives, List<Path> temporaryFiles) {
    }

    private record NestedPrimaryClass(Path archivePath, String internalName) {
    }

    private record AARContent(Map<String, byte[]> classEntries,
                              List<NestedDependencyArchive> dependencies,
                              List<Path> temporaryFiles) {
    }
}
