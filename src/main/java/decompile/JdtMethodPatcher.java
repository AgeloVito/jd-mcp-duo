package decompile;

import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.text.edits.InsertEdit;
import org.eclipse.text.edits.MalformedTreeException;
import org.eclipse.text.edits.MultiTextEdit;
import org.eclipse.text.edits.ReplaceEdit;
import org.jd.core.v1.service.converter.classfiletojavasyntax.util.ByteCodeWriter;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class JdtMethodPatcher {
    private JdtMethodPatcher() {
    }

    public record MethodPatch(String bindingKey, int v1StartLine, int v1EndLine, int v0StartLine, int v0EndLine) {
    }

    public record PatchResult(String source, List<MethodPatch> methodPatches) {
    }

    public static String patchFailedMethods(String sourceCodeV1,
                                            String sourceCodeV0,
                                            String unitName,
                                            URI contextUri,
                                            List<String> classpathEntries) {
        return patchFailedMethodsDetailed(sourceCodeV1, sourceCodeV0, unitName, contextUri, classpathEntries).source();
    }

    public static PatchResult patchFailedMethodsDetailed(String sourceCodeV1,
                                                         String sourceCodeV0,
                                                         String unitName,
                                                         URI contextUri,
                                                         List<String> classpathEntries) {
        if (!containsFailureMarker(sourceCodeV1) || sourceCodeV0 == null || sourceCodeV0.isBlank()) {
            return new PatchResult(sourceCodeV1, List.of());
        }

        CompilationUnit v1 = parse(sourceCodeV1, unitName, contextUri, classpathEntries);
        CompilationUnit v0 = parse(sourceCodeV0, unitName, contextUri, classpathEntries);
        Map<String, int[]> failedMethods = new HashMap<>();

        v1.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration node) {
                if (node.getBody() == null) {
                    return false;
                }

                int start = node.getBody().getStartPosition();
                int end = start + node.getBody().getLength();
                String bodySource = sourceCodeV1.substring(start, end);
                if (!bodySource.contains(ByteCodeWriter.DECOMPILATION_FAILED_AT_LINE)) {
                    return false;
                }

                IMethodBinding binding = node.resolveBinding();
                if (binding != null) {
                    failedMethods.put(binding.getKey(), new int[]{
                            start,
                            end,
                            v1.getLineNumber(start),
                            v1.getLineNumber(Math.max(start, end - 1))
                    });
                }
                return false;
            }
        });

        if (failedMethods.isEmpty()) {
            return new PatchResult(sourceCodeV1, List.of());
        }

        Document document = new Document(sourceCodeV1);
        MultiTextEdit edits = new MultiTextEdit();
        List<MethodPatch> methodPatches = new java.util.ArrayList<>();

        v0.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration node) {
                if (node.getBody() == null) {
                    return false;
                }

                IMethodBinding binding = node.resolveBinding();
                if (binding == null) {
                    return false;
                }

                int[] range = failedMethods.get(binding.getKey());
                if (range == null) {
                    String relaxedKey = binding.getKey().replaceAll("L(\\w+/)*+", "L");
                    range = failedMethods.get(relaxedKey);
                }
                if (range == null) {
                    return false;
                }

                int start = node.getBody().getStartPosition();
                int end = start + node.getBody().getLength();
                String methodV0 = sourceCodeV0.substring(start, end);
                String methodV1 = sourceCodeV1.substring(range[0], range[1]);
                long missingLines = Math.max(0, methodV1.lines().count() - methodV0.lines().count());
                StringBuilder replacement = new StringBuilder(methodV0);
                for (int i = 0; i < missingLines; i++) {
                    replacement.append(System.lineSeparator());
                }

                edits.addChild(new ReplaceEdit(range[0], range[1] - range[0], replacement.toString()));
                edits.addChild(new InsertEdit(range[0], "/* Patched from JD-Core V0 */"));
                methodPatches.add(new MethodPatch(
                        binding.getKey(),
                        range[2],
                        range[3],
                        v0.getLineNumber(start),
                        v0.getLineNumber(Math.max(start, end - 1))
                ));
                return false;
            }
        });

        try {
            edits.apply(document);
            return new PatchResult(document.get(), List.copyOf(methodPatches));
        } catch (MalformedTreeException | BadLocationException e) {
            return new PatchResult(sourceCodeV1, List.of());
        }
    }

    public static boolean containsFailureMarker(String source) {
        return source != null
                && (source.contains(ByteCodeWriter.DECOMPILATION_FAILED_AT_LINE) || source.contains("// INTERNAL ERROR"));
    }

    private static CompilationUnit parse(String source,
                                         String unitName,
                                         URI contextUri,
                                         List<String> classpathEntries) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setSource(source.toCharArray());
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        parser.setStatementsRecovery(true);
        parser.setUnitName(unitName.endsWith(".class") ? unitName.replace(".class", ".java") : unitName);
        parser.setEnvironment(classpathEntries.toArray(String[]::new), null, null, true);

        Map<String, String> options = JavaCore.getOptions();
        options.put(JavaCore.CORE_ENCODING, StandardCharsets.UTF_8.name());
        options.put(JavaCore.COMPILER_COMPLIANCE, JavaCore.latestSupportedJavaVersion());
        options.put(JavaCore.COMPILER_SOURCE, JavaCore.latestSupportedJavaVersion());
        options.put(JavaCore.COMPILER_PB_MAX_PER_UNIT, String.valueOf(Integer.MAX_VALUE));
        parser.setCompilerOptions(options);

        return (CompilationUnit) parser.createAST(null);
    }
}
