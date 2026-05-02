package archive;

import com.googlecode.d2j.dex.Dex2jar;
import com.googlecode.d2j.reader.BaseDexFileReader;
import com.googlecode.d2j.reader.MultiDexFileReader;
import com.googlecode.dex2jar.tools.BaksmaliBaseDexExceptionHandler;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

final class DexToClassMapConverter {
    private static final Map<ConversionKey, Map<String, byte[]>> CACHE = new ConcurrentHashMap<>();

    private DexToClassMapConverter() {
    }

    static Map<String, byte[]> getOrCreate(Path sourcePath) throws IOException {
        ConversionKey key = ConversionKey.of(sourcePath);
        Map<String, byte[]> cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }

        Map<String, byte[]> converted = Collections.unmodifiableMap(convert(sourcePath));
        Map<String, byte[]> previous = CACHE.putIfAbsent(key, converted);
        return previous != null ? previous : converted;
    }

    private static Map<String, byte[]> convert(Path sourcePath) throws IOException {
        byte[] bytes = Files.readAllBytes(sourcePath);
        BaseDexFileReader reader = MultiDexFileReader.open(bytes);
        BaksmaliBaseDexExceptionHandler exceptionHandler = new BaksmaliBaseDexExceptionHandler();
        ByteArrayOutputStream translatedClasses = new ByteArrayOutputStream();

        Dex2jar.from(reader)
                .withExceptionHandler(exceptionHandler)
                .skipDebug(false)
                .reUseReg(false)
                .topoLogicalSort(true)
                .doTranslate(translatedClasses);

        Map<String, byte[]> classFiles = readClassFiles(translatedClasses.toByteArray());
        if (classFiles.isEmpty()) {
            throw new IOException("Dex to jar conversion produced no classes for " + sourcePath);
        }
        return classFiles;
    }

    private static Map<String, byte[]> readClassFiles(byte[] translatedClasses) throws IOException {
        NavigableMap<String, byte[]> classFiles = new TreeMap<>();
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(translatedClasses))) {
            while (input.available() > 0) {
                int classNameLength = input.readInt();
                if (classNameLength <= 0) {
                    throw new IOException("Dex to jar conversion produced an invalid class name length");
                }

                byte[] classNameBytes = new byte[classNameLength];
                input.readFully(classNameBytes);

                int classFileLength = input.readInt();
                if (classFileLength <= 0) {
                    throw new IOException("Dex to jar conversion produced an invalid class file length");
                }

                byte[] classFileBytes = new byte[classFileLength];
                input.readFully(classFileBytes);
                classFiles.put(new String(classNameBytes, java.nio.charset.StandardCharsets.UTF_8) + ".class", classFileBytes);
            }
        } catch (EOFException e) {
            throw new IOException("Dex to jar conversion produced truncated output", e);
        }
        return classFiles;
    }

    private record ConversionKey(String path, long lastModified, long length) {
        static ConversionKey of(Path path) throws IOException {
            return new ConversionKey(path.toAbsolutePath().normalize().toString(), Files.getLastModifiedTime(path).toMillis(), Files.size(path));
        }
    }
}
