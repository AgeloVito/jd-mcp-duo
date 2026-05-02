package support;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;

public final class Sha1Support {
    private Sha1Support() {
    }

    public static Map<Path, String> readSha1File(Path path) throws IOException {
        Map<Path, String> results = new HashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                // sha1sum format: <hash>  <filename> (text) or <hash> *<filename> (binary)
                String[] tokens = line.trim().split("\\s+", 2);
                if (tokens.length == 2) {
                    String name = tokens[1].startsWith("*") ? tokens[1].substring(1) : tokens[1];
                    results.put(Path.of(name), tokens[0]);
                }
            }
        }
        return results;
    }

    public static String computeSha1(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] buffer = new byte[8192];
            try (DigestInputStream stream = new DigestInputStream(new FileInputStream(path.toFile()), digest)) {
                while (stream.read(buffer) > -1) {
                    // fully read
                }
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-1 algorithm unavailable", e);
        }
    }
}
