package decompile;

import archive.InputContainer;
import archive.InputContainers;
import archive.ArchiveInputContainer;
import com.heliosdecompiler.transformerapi.common.ClasspathUtil;
import com.heliosdecompiler.transformerapi.common.Loader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

public final class ClassSourceResolver implements AutoCloseable {
    private final InputContainer primary;
    private final List<AuxiliaryContainer> auxiliaryContainers;
    private final boolean advancedLookup;
    private final List<String> parserClasspathEntries;

    private ClassSourceResolver(InputContainer primary,
                                List<AuxiliaryContainer> auxiliaryContainers,
                                boolean advancedLookup,
                                List<String> parserClasspathEntries) {
        this.primary = primary;
        this.auxiliaryContainers = auxiliaryContainers;
        this.advancedLookup = advancedLookup;
        this.parserClasspathEntries = parserClasspathEntries;
    }

    public static ClassSourceResolver open(InputContainer primary,
                                           boolean advancedLookup,
                                           List<String> explicitClasspathEntries) throws IOException {
        return open(primary, advancedLookup, explicitClasspathEntries, null);
    }

    public static ClassSourceResolver open(InputContainer primary,
                                           boolean advancedLookup,
                                           List<String> explicitClasspathEntries,
                                           Integer releaseVersion) throws IOException {
        List<AuxiliaryContainer> auxiliaryContainers = new ArrayList<>();
        LinkedHashSet<Path> auxiliaryPaths = new LinkedHashSet<>();
        LinkedHashSet<String> parserClasspathEntries = new LinkedHashSet<>();

        Path primaryPath = primary.classpathRoot().toAbsolutePath().normalize();
        if (Files.isDirectory(primaryPath)) {
            parserClasspathEntries.add(primaryPath.toString());
        } else if (primaryPath.toString().endsWith(".class") && primaryPath.getParent() != null) {
            parserClasspathEntries.add(primaryPath.getParent().toString());
        } else {
            parserClasspathEntries.add(primaryPath.toString());
        }

        for (String entry : explicitClasspathEntries) {
            Path classpathPath = Path.of(entry).toAbsolutePath().normalize();
            if (!Files.exists(classpathPath)) {
                continue;
            }
            try {
                addAuxiliaryContainer(primary, classpathPath, classpathPath.toString(), 0, auxiliaryPaths, auxiliaryContainers, releaseVersion);
                parserClasspathEntries.add(classpathPath.toString());
            } catch (IOException ignored) {
                // Bad classpath entries must not block decompilation of the primary class.
            }
        }

        if (primary instanceof ArchiveInputContainer archiveContainer) {
            for (ArchiveInputContainer.NestedDependencyArchive dependencyArchive : archiveContainer.dependencyArchives()) {
                try {
                    addAuxiliaryContainer(primary, dependencyArchive.extractedPath(), dependencyArchive.entryName(), 1, auxiliaryPaths, auxiliaryContainers, releaseVersion);
                    parserClasspathEntries.add(dependencyArchive.extractedPath().toString());
                } catch (IOException ignored) {
                    // Broken nested dependencies are common in large WAR/EAR files; skip them best-effort.
                }
            }
        }

        addJdkContainers(primary, auxiliaryPaths, auxiliaryContainers, parserClasspathEntries, releaseVersion);

        if (advancedLookup) {
            LinkedHashSet<Path> searchRoots = new LinkedHashSet<>();
            Path classpathRoot = primary.classpathRoot().toAbsolutePath().normalize();
            if (Files.isDirectory(classpathRoot)) {
                searchRoots.add(classpathRoot);
                if (classpathRoot.getParent() != null) {
                    searchRoots.add(classpathRoot.getParent().toAbsolutePath().normalize());
                }
            } else if (primary.path().getParent() != null) {
                searchRoots.add(primary.path().getParent().toAbsolutePath().normalize());
            }
            for (Path searchBase : searchRoots) {
                if (!Files.isDirectory(searchBase)) {
                    continue;
                }
                try (var stream = Files.walk(searchBase)) {
                    stream.filter(Files::isRegularFile)
                            .filter(InputContainers::isArchivePath)
                            .map(path -> path.toAbsolutePath().normalize())
                            .forEach(path -> {
                                try {
                                    addAuxiliaryContainer(primary, path, path.toString(), 2, auxiliaryPaths, auxiliaryContainers, releaseVersion);
                                    parserClasspathEntries.add(path.toString());
                                } catch (IOException ignored) {
                                    // best effort: skip archives that cannot be opened
                                }
                            });
                }
            }
        }

        auxiliaryContainers.sort(AUXILIARY_ORDER);
        return new ClassSourceResolver(primary, auxiliaryContainers, advancedLookup, List.copyOf(parserClasspathEntries));
    }

    public Loader createLoader() {
        return new Loader(this::canLoad, this::load, advancedLookup ? primary.contextUri() : null);
    }

    public List<String> parserClasspathEntries() {
        return parserClasspathEntries;
    }

    public java.net.URI primaryContextUri() {
        return primary.contextUri();
    }

    public byte[] load(String internalName) throws IOException {
        byte[] primaryBytes = primary.loadClassBytes(internalName);
        if (primaryBytes != null) {
            return primaryBytes;
        }
        for (AuxiliaryContainer auxiliaryContainer : auxiliaryContainers) {
            byte[] bytes = auxiliaryContainer.container().loadClassBytes(internalName);
            if (bytes != null) {
                return bytes;
            }
        }
        return null;
    }

    public boolean canLoad(String internalName) {
        try {
            return load(internalName) != null;
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        for (AuxiliaryContainer auxiliaryContainer : auxiliaryContainers) {
            try {
                auxiliaryContainer.container().close();
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

    private static void addJdkContainers(InputContainer primary,
                                         LinkedHashSet<Path> auxiliaryPaths,
                                         List<AuxiliaryContainer> auxiliaryContainers,
                                         LinkedHashSet<String> parserClasspathEntries,
                                         Integer releaseVersion) {
        for (String entry : ClasspathUtil.getJDKClasspath()) {
            Path classpathPath = Path.of(entry).toAbsolutePath().normalize();
            parserClasspathEntries.add(classpathPath.toString());
            if (!Files.exists(classpathPath)) {
                continue;
            }
            try {
                addAuxiliaryContainer(primary, classpathPath, classpathPath.toString(), 3, auxiliaryPaths, auxiliaryContainers, releaseVersion);
            } catch (IOException ignored) {
                // Standard library lookup is best-effort and must not break decompilation.
            }
        }
    }

    private static void addAuxiliaryContainer(InputContainer primary,
                                              Path path,
                                              String logicalName,
                                              int priority,
                                              LinkedHashSet<Path> auxiliaryPaths,
                                              List<AuxiliaryContainer> auxiliaryContainers,
                                              Integer releaseVersion) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        if (normalized.equals(primary.path().toAbsolutePath().normalize())
                || normalized.equals(primary.classpathRoot().toAbsolutePath().normalize())
                || !auxiliaryPaths.add(normalized)) {
            return;
        }
        InputContainer opened = InputContainers.open(normalized, releaseVersion);
        auxiliaryContainers.add(new AuxiliaryContainer(
                opened,
                normalized,
                logicalName,
                priority,
                depth(logicalName),
                ArtifactIdentity.from(opened, logicalName)
        ));
    }

    private static int depth(String logicalName) {
        if (logicalName == null || logicalName.isBlank()) {
            return Integer.MAX_VALUE;
        }
        return logicalName.replace('\\', '/').split("/").length;
    }

    private static final Comparator<AuxiliaryContainer> AUXILIARY_ORDER = Comparator
            .comparingInt(AuxiliaryContainer::priority)
            .thenComparing((AuxiliaryContainer container) -> container.artifactIdentity().artifactKey())
            .thenComparing(AuxiliaryContainer::artifactIdentity, Comparator.reverseOrder())
            .thenComparingInt(AuxiliaryContainer::depth)
            .thenComparing(AuxiliaryContainer::logicalName);

    private record AuxiliaryContainer(InputContainer container,
                                      Path physicalPath,
                                      String logicalName,
                                      int priority,
                                      int depth,
                                      ArtifactIdentity artifactIdentity) {
    }

    private record ArtifactIdentity(String artifactKey, List<VersionToken> versionTokens, String rawVersion) implements Comparable<ArtifactIdentity> {
        private static ArtifactIdentity from(InputContainer container, String logicalName) {
            if (container instanceof ArchiveInputContainer archiveContainer && archiveContainer.artifactCoordinates() != null) {
                ArchiveInputContainer.ArtifactCoordinates coordinates = archiveContainer.artifactCoordinates();
                return new ArtifactIdentity(
                        coordinates.groupId() + ":" + coordinates.artifactId(),
                        parseVersion(coordinates.version()),
                        coordinates.version()
                );
            }
            String fileName = Path.of(logicalName).getFileName().toString();
            int dot = fileName.lastIndexOf('.');
            String baseName = dot >= 0 ? fileName.substring(0, dot) : fileName;
            int versionSeparator = findVersionSeparator(baseName);
            if (versionSeparator < 0) {
                return new ArtifactIdentity(baseName, List.of(), "");
            }
            String artifactKey = baseName.substring(0, versionSeparator);
            String rawVersion = baseName.substring(versionSeparator + 1);
            return new ArtifactIdentity(artifactKey, parseVersion(rawVersion), rawVersion);
        }

        private static int findVersionSeparator(String baseName) {
            for (int i = 0; i < baseName.length() - 1; i++) {
                if (baseName.charAt(i) == '-' && Character.isDigit(baseName.charAt(i + 1))) {
                    return i;
                }
            }
            return -1;
        }

        private static List<VersionToken> parseVersion(String rawVersion) {
            if (rawVersion == null || rawVersion.isBlank()) {
                return List.of();
            }
            List<VersionToken> tokens = new ArrayList<>();
            for (String token : rawVersion.split("[._-]")) {
                if (token.isBlank()) {
                    continue;
                }
                tokens.add(VersionToken.of(token));
            }
            return List.copyOf(tokens);
        }

        @Override
        public int compareTo(ArtifactIdentity other) {
            int longest = Math.max(versionTokens.size(), other.versionTokens.size());
            for (int i = 0; i < longest; i++) {
                VersionToken left = i < versionTokens.size() ? versionTokens.get(i) : VersionToken.zero();
                VersionToken right = i < other.versionTokens.size() ? other.versionTokens.get(i) : VersionToken.zero();
                int comparison = left.compareTo(right);
                if (comparison != 0) {
                    return comparison;
                }
            }
            return rawVersion.compareToIgnoreCase(other.rawVersion);
        }
    }

    private record VersionToken(boolean numeric, long numericValue, String textValue) implements Comparable<VersionToken> {
        private static VersionToken of(String token) {
            if (token.chars().allMatch(Character::isDigit)) {
                try {
                    return new VersionToken(true, Long.parseLong(token), token);
                } catch (NumberFormatException ignored) {
                    return new VersionToken(false, 0, token);
                }
            }
            return new VersionToken(false, 0, token);
        }

        private static VersionToken zero() {
            return new VersionToken(true, 0, "0");
        }

        @Override
        public int compareTo(VersionToken other) {
            if (numeric && other.numeric) {
                return Long.compare(numericValue, other.numericValue);
            }
            if (numeric != other.numeric) {
                return numeric ? 1 : -1;
            }
            return textValue.compareToIgnoreCase(other.textValue);
        }
    }
}
