package support;

import archive.InputContainer;
import archive.InputContainers;
import com.google.gson.JsonObject;
import decompile.ClassSourceResolver;
import decompile.DecompilerSession;
import decompile.DecompilerOptions;
import decompile.DecompilerSupport;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JdtParserSupport {
    private static final Pattern PACKAGE_PATTERN = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");

    private JdtParserSupport() {
    }

    public record SourceContext(String source,
                                String unitName,
                                URI contextUri,
                                List<String> classpathEntries,
                                List<String> sourcepathEntries,
                                String logicalName) {
    }

    public static SourceContext resolve(JsonObject arguments, DecompilerOptions options) throws Exception {
        Path path = JsonUtils.getRequiredPath(arguments, "path");
        if (!Files.exists(path)) {
            throw new IOException("File not found: " + path);
        }

        if (path.toString().endsWith(".java")) {
            String source = Files.readString(path);
            Path sourceRoot = inferSourceRoot(path, source);
            List<String> classpath = new ArrayList<>(options.classpathEntries());
            classpath.add(sourceRoot.toString());
            return new SourceContext(
                    source,
                    sourceRoot.relativize(path).toString().replace('\\', '/'),
                    sourceRoot.toUri(),
                    List.copyOf(classpath),
                    List.of(sourceRoot.toString()),
                    path.getFileName().toString()
            );
        }

        try (InputContainer container = InputContainers.open(path, options.releaseVersion())) {
            String className = JsonUtils.getString(arguments, "className", null);
            if (className == null || className.isBlank()) {
                var defaultClass = container.defaultClass();
                if (defaultClass == null) {
                    throw new IllegalArgumentException("className is required when the input contains multiple classes");
                }
                className = defaultClass.internalName();
            }
            try (DecompilerSession session = DecompilerSession.open(container, options)) {
                var outcome = session.decompile(className);
                return new SourceContext(
                        outcome.result().getDecompiledOutput(),
                        className.replace('.', '/').replace('/', '/') + ".java",
                        container.contextUri(),
                        session.parserClasspathEntries(),
                        List.of(),
                        outcome.internalName()
                );
            }
        }
    }

    public static CompilationUnit parse(SourceContext context, boolean resolveBindings) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setSource(context.source().toCharArray());
        parser.setResolveBindings(resolveBindings);
        parser.setBindingsRecovery(resolveBindings);
        parser.setStatementsRecovery(true);
        parser.setUnitName(context.unitName());

        String[] classpath = context.classpathEntries().toArray(String[]::new);
        String[] sourcepath = context.sourcepathEntries().isEmpty() ? null : context.sourcepathEntries().toArray(String[]::new);
        parser.setEnvironment(classpath, sourcepath, null, true);

        Map<String, String> options = new LinkedHashMap<>(JavaCore.getOptions());
        options.put(JavaCore.CORE_ENCODING, StandardCharsets.UTF_8.name());
        options.put(JavaCore.COMPILER_COMPLIANCE, JavaCore.latestSupportedJavaVersion());
        options.put(JavaCore.COMPILER_SOURCE, JavaCore.latestSupportedJavaVersion());
        options.put(JavaCore.COMPILER_PB_MAX_PER_UNIT, String.valueOf(Integer.MAX_VALUE));
        options.put(JavaCore.COMPILER_PB_UNNECESSARY_TYPE_CHECK, "warning");
        parser.setCompilerOptions(options);
        return (CompilationUnit) parser.createAST(null);
    }

    public static List<JsonObject> diagnostics(CompilationUnit unit) {
        List<JsonObject> diagnostics = new ArrayList<>();
        for (IProblem problem : unit.getProblems()) {
            JsonObject item = new JsonObject();
            item.addProperty("id", problem.getID());
            item.addProperty("message", problem.getMessage());
            item.addProperty("line", problem.getSourceLineNumber());
            item.addProperty("start", problem.getSourceStart());
            item.addProperty("end", problem.getSourceEnd());
            item.addProperty("isError", problem.isError());
            item.addProperty("isWarning", problem.isWarning());
            diagnostics.add(item);
        }
        return diagnostics;
    }

    private static Path inferSourceRoot(Path path, String source) {
        Path parent = path.getParent();
        if (parent == null) {
            return path.toAbsolutePath().normalize().getParent();
        }
        Matcher matcher = PACKAGE_PATTERN.matcher(source);
        if (!matcher.find()) {
            return parent;
        }
        String[] segments = matcher.group(1).split("\\.");
        Path sourceRoot = parent;
        for (int i = segments.length - 1; i >= 0; i--) {
            if (sourceRoot.getFileName() != null && sourceRoot.getFileName().toString().equals(segments[i])) {
                sourceRoot = sourceRoot.getParent();
            } else {
                return parent;
            }
            if (sourceRoot == null) {
                return parent;
            }
        }
        return sourceRoot;
    }
}
