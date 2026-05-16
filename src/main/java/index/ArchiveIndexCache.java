package index;

import archive.InputContainer;
import archive.InputContainers;
import support.FingerprintSupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

public final class ArchiveIndexCache {
    private static final int MAX_ENTRIES = 16;
    private static final Map<String, ArchiveIndex> CACHE = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ArchiveIndex> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    private ArchiveIndexCache() {
    }

    public static synchronized ArchiveIndex get(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        String fingerprint = fingerprint(normalized);
        ArchiveIndex existing = CACHE.get(fingerprint);
        if (existing != null) {
            return existing;
        }

        try (InputContainer container = InputContainers.open(normalized)) {
            ArchiveIndex index = ArchiveIndex.build(container, fingerprint);
            CACHE.put(fingerprint, index);
            return index;
        }
    }

    public static String fingerprint(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        if (Files.isRegularFile(normalized)) {
            return FingerprintSupport.sha256Hex(Files.readAllBytes(normalized));
        }

        MessageDigest digest = FingerprintSupport.newSha256();
        try (Stream<Path> stream = Files.walk(normalized)) {
            for (Path child : stream.filter(Files::isRegularFile).sorted().toList()) {
                Path relative = normalized.relativize(child);
                FingerprintSupport.updateDigest(digest, relative.toString().replace('\\', '/'));
                FingerprintSupport.updateDigest(digest, Files.size(child));
                FingerprintSupport.updateDigest(digest, Files.getLastModifiedTime(child).toMillis());
            }
        }
        return FingerprintSupport.toHex(digest.digest());
    }
}
