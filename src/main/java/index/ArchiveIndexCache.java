package index;

import archive.InputContainer;
import archive.InputContainers;

import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
            long lastModified = Files.getLastModifiedTime(normalized).toMillis();
            long size = Files.size(normalized);
            return normalized + ":" + size + ":" + lastModified;
        }

        MessageDigest digest = newSha256();
        try (Stream<Path> stream = Files.walk(normalized)) {
            for (Path child : stream.filter(Files::isRegularFile).sorted().toList()) {
                Path relative = normalized.relativize(child);
                updateDigest(digest, relative.toString().replace('\\', '/'));
                updateDigest(digest, Files.size(child));
                updateDigest(digest, Files.getLastModifiedTime(child).toMillis());
            }
        }
        return normalized + ":" + toHex(digest.digest());
    }

    private static MessageDigest newSha256() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 not available", e);
        }
    }

    private static void updateDigest(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static void updateDigest(MessageDigest digest, long value) {
        updateDigest(digest, Long.toString(value));
    }

    private static String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(Character.forDigit((value >>> 4) & 0xF, 16));
            builder.append(Character.forDigit(value & 0xF, 16));
        }
        return builder.toString();
    }
}
