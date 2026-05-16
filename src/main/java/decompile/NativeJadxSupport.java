package decompile;

import archive.InputContainer;
import archive.ClassLocation;
import com.heliosdecompiler.transformerapi.common.BytecodeSourceLinker;
import com.heliosdecompiler.transformerapi.common.ResultLinkSupport;
import com.heliosdecompiler.transformerapi.decompilers.jadx.MapJadxArgs;
import jd.core.DecompilationResult;
import jd.core.links.ReferenceData;
import jadx.api.ICodeInfo;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.api.JavaField;
import jadx.api.JavaMethod;
import jadx.api.JavaNode;
import jadx.api.JavaVariable;
import jadx.api.metadata.ICodeAnnotation;
import jadx.api.metadata.ICodeNodeRef;
import jadx.api.metadata.annotations.NodeDeclareRef;
import jadx.core.codegen.TypeGen;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.Callable;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class NativeJadxSupport {
    private NativeJadxSupport() {
    }

    static boolean supports(String kind) {
        return "dex".equals(kind) || "apk".equals(kind);
    }

    static DecompilationOutcome tryDecompile(InputContainer input,
                                             String internalName,
                                             DecompilerOptions options,
                                             List<String> attemptedEngines,
                                             Map<String, String> engineFailures,
                                             boolean fallbackUsed) {
        attemptedEngines.add("jadx-native");
        try {
            DecompilationResult result = runNativeAttempt(
                    () -> decompile(input, internalName, options),
                    options.attemptTimeoutMillis(),
                    internalName + " (jadx-native)"
            );
            return new DecompilationOutcome(
                    internalName,
                    options.requestedEngine(),
                    DecompilerEngines.JADX,
                    false,
                    fallbackUsed,
                    false,
                    false,
                    true,
                    List.copyOf(attemptedEngines),
                    Map.copyOf(engineFailures),
                    result
            );
        } catch (Exception e) {
            engineFailures.put("jadx-native", summarizeFailure(e));
            return null;
        }
    }

    static DecompilationResult runNativeAttempt(Callable<DecompilationResult> callable,
                                                long timeoutMillis, String label) throws Exception {
        return DecompilerAttemptRunner.run(callable, timeoutMillis, label);
    }

    private static DecompilationResult decompile(InputContainer input,
                                                 String internalName,
                                                 DecompilerOptions options) throws Exception {
        MapJadxArgs args = new MapJadxArgs(new LinkedHashMap<>(options.preferencesFor(DecompilerEngines.JADX)));
        Path inputPath = input.path();
        Path jadxInput = prepareJadxInput(inputPath);
        args.setInputFile(jadxInput.toFile());
        args.setSkipResources(true);
        args.setSkipSources(false);
        args.setIncludeDependencies(true);
        args.setDebugInfo(options.lineNumbers());
        args.setInsertDebugLines(options.lineNumbers());
        args.setShowInconsistentCode(true);
        try (JadxDecompiler jadx = new JadxDecompiler(args)) {
            jadx.load();
            String fullName = internalName.replace('/', '.');
            JavaClass javaClass = jadx.searchJavaClassByOrigFullName(fullName);
            if (javaClass == null) {
                javaClass = jadx.searchJavaClassByAliasFullName(fullName);
            }
            if (javaClass == null) {
                throw new IOException("Class not found in Android input: " + fullName);
            }
            javaClass.decompile();
            ICodeInfo codeInfo = javaClass.getCodeInfo();
            String code = codeInfo.getCodeStr();
            if (code == null || code.isBlank()) {
                throw new IOException("JADX produced empty output for " + fullName);
            }
            DecompilationResult result = new DecompilationResult();
            result.setDecompiledOutput(code);
            result.setDeclarations(new LinkedHashMap<>());
            result.setTypeDeclarations(new java.util.TreeMap<>());
            result.setReferences(new java.util.ArrayList<>());
            result.setStrings(new java.util.ArrayList<>());
            result.setLineNumbers(new LinkedHashMap<>());
            result.setHyperlinks(new java.util.TreeMap<>());
            addLinks(result, jadx, codeInfo);
            BytecodeSourceLinker.link(result, code, internalName, readImportantClassBytes(input, internalName));
            Map<Integer, Integer> lineMapping = codeInfo.getCodeMetadata().getLineMapping();
            if (lineMapping != null && !lineMapping.isEmpty()) {
                putLineNumbers(result, lineMapping);
            } else {
                putIdentityLineNumbers(result, code);
            }
            return result;
        } finally {
            args.close();
            if (!jadxInput.equals(inputPath)) {
                java.nio.file.Files.deleteIfExists(jadxInput);
            }
        }
    }

    private static Path prepareJadxInput(Path inputPath) throws IOException {
        if (!inputPath.getFileName().toString().toLowerCase().endsWith(".dex")) {
            return inputPath;
        }
        Path tempApk = java.nio.file.Files.createTempFile("jd-mcp-duo-jadx-", ".apk");
        try (OutputStream outputStream = java.nio.file.Files.newOutputStream(tempApk);
             ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream)) {
            zipOutputStream.putNextEntry(new ZipEntry("classes.dex"));
            zipOutputStream.write(java.nio.file.Files.readAllBytes(inputPath));
            zipOutputStream.closeEntry();
            zipOutputStream.putNextEntry(new ZipEntry("AndroidManifest.xml"));
            zipOutputStream.write("<manifest package=\"temp\"/>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zipOutputStream.closeEntry();
        }
        return tempApk;
    }

    private static Map<String, byte[]> readImportantClassBytes(InputContainer input, String internalName) {
        Map<String, byte[]> bytes = new LinkedHashMap<>();
        for (ClassLocation location : input.listClasses(true)) {
            String name = location.internalName();
            if (!name.equals(internalName) && !name.startsWith(internalName + "$")) {
                continue;
            }
            try {
                byte[] classBytes = input.loadClassBytes(name);
                if (classBytes != null) {
                    bytes.put(name, classBytes);
                }
            } catch (IOException ignored) {
                // Best effort: JADX metadata is still useful without supplemental bytecode links.
            }
        }
        return bytes;
    }

    private static void putLineNumbers(DecompilationResult result, Map<Integer, Integer> lineMapping) {
        int maxLine = 0;
        for (Map.Entry<Integer, Integer> entry : lineMapping.entrySet()) {
            Integer emittedLine = entry.getKey();
            Integer sourceLine = entry.getValue();
            if (emittedLine == null || sourceLine == null) {
                continue;
            }
            result.putLineNumber(emittedLine, sourceLine);
            maxLine = Math.max(maxLine, sourceLine);
        }
        result.setMaxLineNumber(maxLine);
    }

    private static void putIdentityLineNumbers(DecompilationResult result, String code) {
        int lineCount = code.split("\\R", -1).length;
        result.setMaxLineNumber(lineCount);
        for (int i = 1; i <= lineCount; i++) {
            result.putLineNumber(i, i);
        }
    }

    private static void addLinks(DecompilationResult result, JadxDecompiler jadx, ICodeInfo codeInfo) {
        String code = codeInfo.getCodeStr();
        Map<String, ReferenceData> references = new HashMap<>();
        for (Map.Entry<Integer, ICodeAnnotation> entry : codeInfo.getCodeMetadata().getAsMap().entrySet()) {
            addLinkAtPosition(result, jadx, codeInfo, code, references, entry.getKey(), entry.getValue());
        }
    }

    private static void addLinkAtPosition(DecompilationResult result,
                                          JadxDecompiler jadx,
                                          ICodeInfo codeInfo,
                                          String code,
                                          Map<String, ReferenceData> references,
                                          int position,
                                          ICodeAnnotation annotation) {
        if (annotation instanceof NodeDeclareRef declaration) {
            addDeclaration(result, jadx, declaration.getNode(), position, code);
            return;
        }
        if (isReferenceAnnotation(annotation)) {
            addReference(result, jadx, codeInfo, code, references, position, annotation);
        }
    }

    private static boolean isReferenceAnnotation(ICodeAnnotation annotation) {
        return switch (annotation.getAnnType()) {
            case CLASS, METHOD, FIELD, VAR, VAR_REF -> true;
            default -> false;
        };
    }

    private static void addDeclaration(DecompilationResult result, JadxDecompiler jadx, ICodeNodeRef node, int position, String code) {
        addDeclaration(result, jadx.getJavaNodeByRef(node), position, code);
    }

    private static void addDeclaration(DecompilationResult result, JavaNode javaNode, int position, String code) {
        if (javaNode == null) {
            return;
        }
        ResultLinkSupport.LinkTarget target = toTarget(javaNode);
        if (target == null) {
            return;
        }
        int length = ResultLinkSupport.identifierLength(code, position);
        if (length > 0) {
            ResultLinkSupport.addDeclaration(result, position, length, target);
        }
    }

    private static void addReference(DecompilationResult result,
                                     JadxDecompiler jadx,
                                     ICodeInfo codeInfo,
                                     String code,
                                     Map<String, ReferenceData> references,
                                     int position,
                                     ICodeAnnotation annotation) {
        JavaNode javaNode = jadx.getJavaNodeByCodeAnnotation(codeInfo, annotation);
        if (javaNode == null) {
            return;
        }
        if (position == javaNode.getDefPos()) {
            addDeclaration(result, javaNode, position, code);
            return;
        }
        int length = ResultLinkSupport.identifierLength(code, position);
        if (length <= 0) {
            return;
        }
        ResultLinkSupport.LinkTarget target = toTarget(javaNode);
        if (target != null) {
            ResultLinkSupport.addReference(result, references, position, length, target, enclosingType(jadx, codeInfo, position));
        }
    }

    private static String enclosingType(JadxDecompiler jadx, ICodeInfo codeInfo, int position) {
        JavaNode enclosing = jadx.getEnclosingNode(codeInfo, position);
        if (enclosing instanceof JavaClass javaClass) {
            return internalName(javaClass.getClassNode().getClassInfo().getRawName());
        }
        if (enclosing instanceof JavaMethod javaMethod) {
            return internalName(javaMethod.getDeclaringClass().getClassNode().getClassInfo().getRawName());
        }
        return null;
    }

    private static ResultLinkSupport.LinkTarget toTarget(JavaNode node) {
        if (node instanceof JavaClass javaClass) {
            return new ResultLinkSupport.LinkTarget(internalName(javaClass.getClassNode().getClassInfo().getRawName()), null, null);
        }
        if (node instanceof JavaMethod javaMethod) {
            String owner = internalName(javaMethod.getDeclaringClass().getClassNode().getClassInfo().getRawName());
            String name = javaMethod.getMethodNode().getMethodInfo().getName();
            return new ResultLinkSupport.LinkTarget(owner, name, methodDescriptor(javaMethod));
        }
        if (node instanceof JavaField javaField) {
            String owner = internalName(javaField.getDeclaringClass().getClassNode().getClassInfo().getRawName());
            String name = javaField.getFieldNode().getFieldInfo().getName();
            String descriptor = TypeGen.signature(javaField.getFieldNode().getFieldInfo().getType());
            return new ResultLinkSupport.LinkTarget(owner, name, descriptor);
        }
        if (node instanceof JavaVariable javaVariable) {
            String owner = internalName(javaVariable.getDeclaringClass().getClassNode().getClassInfo().getRawName());
            String name = javaVariable.getMth().getMethodNode().getMethodInfo().getName();
            String descriptor = javaVariable.getMth().getMethodNode().getMethodInfo().getShortId()
                    + "-v" + javaVariable.getReg() + '-' + javaVariable.getSsa();
            return new ResultLinkSupport.LinkTarget(owner, name, descriptor);
        }
        return null;
    }

    private static String internalName(String rawName) {
        return rawName.replace('.', '/');
    }

    private static String methodDescriptor(JavaMethod javaMethod) {
        String name = javaMethod.getMethodNode().getMethodInfo().getName();
        String shortId = javaMethod.getMethodNode().getMethodInfo().getShortId();
        return shortId.substring(name.length());
    }

    private static String summarizeFailure(Exception e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }
}
