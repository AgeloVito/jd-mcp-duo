package decompile;

import jd.core.DecompilationResult;
import org.jd.core.v1.service.converter.classfiletojavasyntax.util.ByteCodeWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class PatchedMetadataSupportTest {
    @Test
    void testRebuildPatchedMetadata(@TempDir java.nio.file.Path tempDir) {
        String v1 = """
                package demo;
                public class Example {
                    Example() { }
                    int ok() { return 1; }
                    int bad() { /* %s */ return -1; }
                }
                """.formatted(ByteCodeWriter.DECOMPILATION_FAILED_AT_LINE);
        String v0 = """
                package demo;
                public class Example {
                    Example() { }
                    int ok() { return 1; }
                    int bad() { return new String("x").length(); }
                }
                """;

        JdtMethodPatcher.PatchResult patchResult = JdtMethodPatcher.patchFailedMethodsDetailed(
                v1,
                v0,
                "demo/Example.class",
                tempDir.toUri(),
                List.of()
        );

        DecompilationResult v1Result = new DecompilationResult();
        v1Result.setDecompiledOutput(v1);
        v1Result.setLineNumbers(new LinkedHashMap<>(java.util.Map.of(1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6)));
        v1Result.setHyperlinks(new TreeMap<>());
        v1Result.setDeclarations(new LinkedHashMap<>());
        v1Result.setTypeDeclarations(new TreeMap<>());
        v1Result.setReferences(new java.util.ArrayList<>());
        v1Result.setStrings(new java.util.ArrayList<>());
        v1Result.setMaxLineNumber(6);

        DecompilationResult v0Result = new DecompilationResult();
        v0Result.setDecompiledOutput(v0);
        v0Result.setLineNumbers(new LinkedHashMap<>(java.util.Map.of(1, 1, 2, 2, 3, 3, 4, 4, 5, 40, 6, 41)));
        v0Result.setHyperlinks(new TreeMap<>());
        v0Result.setDeclarations(new LinkedHashMap<>());
        v0Result.setTypeDeclarations(new TreeMap<>());
        v0Result.setReferences(new java.util.ArrayList<>());
        v0Result.setStrings(new java.util.ArrayList<>());
        v0Result.setMaxLineNumber(41);

        DecompilationResult patched = new DecompilationResult();
        patched.setDecompiledOutput(patchResult.source());

        boolean rebuilt = PatchedMetadataSupport.rebuildPatchedMetadata(
                patched,
                v1Result,
                v0Result,
                patchResult.source(),
                patchResult,
                "demo/Example.java",
                tempDir.toUri(),
                List.of()
        );

        assertTrue(rebuilt);
        assertFalse(patched.getDeclarations().isEmpty());
        assertFalse(patched.getTypeDeclarations().isEmpty());
        assertFalse(patched.getStrings().isEmpty());
        assertTrue(patched.getDeclarations().values().stream().anyMatch(jd.core.links.DeclarationData::isAConstructor));
        assertTrue(patched.getReferences().stream().anyMatch(jd.core.links.ReferenceData::isAConstructor));
        assertTrue(patched.getReferences().stream().anyMatch(reference -> "length".equals(reference.getName())));
        assertTrue(patched.getHyperlinks().values().stream()
                .filter(jd.core.links.HyperlinkReferenceData.class::isInstance)
                .map(jd.core.links.HyperlinkReferenceData.class::cast)
                .anyMatch(link -> link.getReference().isAConstructor()));
        assertFalse(patched.getLineNumbers().isEmpty());
        assertTrue(patched.getMaxLineNumber() >= 40);
    }
}
