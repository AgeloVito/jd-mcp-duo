package decompile;

import archive.InputContainer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

final class DecompilationCache {
    private static final int MAX_ENTRIES = 256;
    private static final Map<CacheKey, DecompilationOutcome> CACHE = java.util.Collections.synchronizedMap(
            new LinkedHashMap<>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<CacheKey, DecompilationOutcome> eldest) {
                    return size() > MAX_ENTRIES;
                }
            }
    );

    private DecompilationCache() {
    }

    static DecompilationOutcome get(InputContainer container, String classNameOrInternal, DecompilerOptions options) {
        CacheKey key = CacheKey.from(container, classNameOrInternal, options);
        DecompilationOutcome outcome = CACHE.get(key);
        return outcome == null ? null : DecompilationCopies.copyOutcome(outcome);
    }

    static void put(InputContainer container, String classNameOrInternal, DecompilerOptions options, DecompilationOutcome outcome) {
        CACHE.put(CacheKey.from(container, classNameOrInternal, options), DecompilationCopies.copyOutcome(outcome));
    }

    private record CacheKey(String path,
                            String fingerprint,
                            String className,
                            String engine,
                            String profile,
                            Integer releaseVersion,
                            boolean lineNumbers,
                            boolean advancedLookup,
                            List<String> classpathEntries,
                            Map<String, String> preferences) {
        static CacheKey from(InputContainer container, String classNameOrInternal, DecompilerOptions options) {
            Path path = container.path().toAbsolutePath().normalize();
            return new CacheKey(
                    path.toString(),
                    fingerprint(path),
                    classNameOrInternal,
                    options.requestedEngine(),
                    options.profile(),
                    options.releaseVersion(),
                    options.lineNumbers(),
                    options.advancedLookup(),
                    List.copyOf(options.classpathEntries()),
                    Map.copyOf(options.userPreferences())
            );
        }

        private static String fingerprint(Path path) {
            try {
                if (Files.isDirectory(path)) {
                    MessageDigest digest = newSha256();
                    try (Stream<Path> stream = Files.walk(path)) {
                        for (Path child : stream.filter(Files::isRegularFile).sorted().toList()) {
                            Path relative = path.relativize(child);
                            updateDigest(digest, relative.toString().replace('\\', '/'));
                            updateDigest(digest, Files.size(child));
                            updateDigest(digest, Files.getLastModifiedTime(child).toMillis());
                        }
                    }
                    return toHex(digest.digest());
                }
                return Files.getLastModifiedTime(path).toMillis() + ":" + Files.size(path);
            } catch (IOException e) {
                return "unknown";
            }
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
}
