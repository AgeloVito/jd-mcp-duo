package decompile;

import jd.core.DecompilationResult;
import org.jd.core.v1.service.converter.classfiletojavasyntax.util.ByteCodeWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class DecompilerSupportTest {
    @Test
    void testPatchV1ResultRebuildsMetadata(@TempDir Path tempDir) {
        String v1 = """
                package demo;
                public class Example {
                    Example() { }
                    int bad() { /* %s */ return -1; }
                }
                """.formatted(ByteCodeWriter.DECOMPILATION_FAILED_AT_LINE);
        String v0 = """
                package demo;
                public class Example {
                    Example() { }
                    int bad() { return new String("x").length(); }
                }
                """;

        DecompilationResult v1Result = resultWithLines(v1, java.util.Map.of(1, 1, 2, 2, 3, 3, 4, 4, 5, 5));
        DecompilationResult v0Result = resultWithLines(v0, java.util.Map.of(1, 1, 2, 2, 3, 30, 4, 31, 5, 32));

        DecompilationOutcome outcome = DecompilerSupport.patchV1Result(
                v1Result,
                v0Result,
                "demo/Example",
                DecompilerEngines.AUTO,
                tempDir.toUri(),
                List.of(),
                new java.util.ArrayList<>(List.of(DecompilerEngines.JD_CORE_V1, DecompilerEngines.JD_CORE_V0)),
                new LinkedHashMap<>()
        );

        assertNotNull(outcome);
        assertTrue(outcome.patched());
        assertTrue(outcome.metadataRebuilt());
        assertFalse(outcome.metadataLimited());
        assertEquals(1, outcome.methodPatches().size());
        assertTrue(outcome.result().getDecompiledOutput().contains("Patched from JD-Core V0"));
        assertTrue(outcome.result().getReferences().stream().anyMatch(jd.core.links.ReferenceData::isAConstructor));
        assertTrue(outcome.result().getReferences().stream().anyMatch(reference -> "length".equals(reference.getName())));
        assertFalse(outcome.result().getHyperlinks().isEmpty());
    }

    private static DecompilationResult resultWithLines(String source, java.util.Map<Integer, Integer> lineNumbers) {
        DecompilationResult result = new DecompilationResult();
        result.setDecompiledOutput(source);
        result.setLineNumbers(new LinkedHashMap<>(lineNumbers));
        result.setHyperlinks(new TreeMap<>());
        result.setDeclarations(new LinkedHashMap<>());
        result.setTypeDeclarations(new TreeMap<>());
        result.setReferences(new java.util.ArrayList<>());
        result.setStrings(new java.util.ArrayList<>());
        result.setMaxLineNumber(lineNumbers.values().stream().mapToInt(Integer::intValue).max().orElse(0));
        return result;
    }
}
