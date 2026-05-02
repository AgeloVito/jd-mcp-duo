package decompile;

import org.jd.core.v1.service.converter.classfiletojavasyntax.util.ByteCodeWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class JdtMethodPatcherTest {
    @Test
    void testPatchFailedMethod(@TempDir Path tempDir) {
        String sourceV1 = """
                package demo;
                public class Example {
                    int ok() { return 1; }
                    int bad() { /* %s */ return -1; }
                }
                """.formatted(ByteCodeWriter.DECOMPILATION_FAILED_AT_LINE);
        String sourceV0 = """
                package demo;
                public class Example {
                    int ok() { return 1; }
                    int bad() { return 42; }
                }
                """;

        String patched = JdtMethodPatcher.patchFailedMethods(
                sourceV1,
                sourceV0,
                "demo/Example.class",
                tempDir.toUri(),
                List.of()
        );

        assertTrue(patched.contains("Patched from JD-Core V0"));
        assertTrue(patched.contains("return 42;"));
    }
}
