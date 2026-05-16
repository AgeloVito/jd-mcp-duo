package tools;

import com.google.gson.JsonObject;
import decompile.DecompilerEngines;
import decompile.DecompilerOptions;
import model.MCPTool;
import model.ToolResult;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.BooleanLiteral;
import org.eclipse.jdt.core.dom.CastExpression;
import org.eclipse.jdt.core.dom.CharacterLiteral;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.TypeLiteral;
import org.eclipse.jdt.internal.corext.dom.ASTNodes;
import org.eclipse.jdt.internal.corext.fix.UnusedCodeFixCore.RemoveAllCastOperation;
import org.eclipse.jdt.internal.corext.refactoring.structure.CompilationUnitRewrite;
import org.eclipse.jdt.internal.ui.text.correction.ProblemLocation;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.text.edits.MalformedTreeException;
import org.eclipse.text.edits.TextEdit;
import support.JdtParserSupport;
import support.JsonUtils;
import support.SchemaSupport;
import support.ToolResults;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RemoveUnnecessaryCastsTool implements MCPTool {
    private static final Pattern STRING_LITERAL_CAST = Pattern.compile("\\((?:java\\.lang\\.)?String\\)\\s*(\"(?:\\\\.|[^\"\\\\])*\")");
    private static final Pattern CHAR_LITERAL_CAST = Pattern.compile("\\((?:java\\.lang\\.)?char\\)\\s*('(?:\\\\.|[^'\\\\])')");

    @Override
    public String getDescription() {
        return "Remove unnecessary casts from Java source or decompiled output using Eclipse JDT analysis.";
    }

    @Override
    public JsonObject getInputSchema() {
        JsonObject schema = SchemaSupport.objectSchema();
        JsonObject properties = SchemaSupport.properties(schema);
        SchemaSupport.addString(properties, "path", "Java source file, class file, archive, or directory");
        SchemaSupport.addString(properties, "className", "Class name when the input contains multiple classes");
        SchemaSupport.addString(properties, "engine", "Decompiler engine when path is not a .java file");
        SchemaSupport.addInteger(properties, "attemptTimeoutMillis", "Per-engine attempt timeout in milliseconds; 0 disables timeout", (int) decompile.DecompilerOptions.DEFAULT_ATTEMPT_TIMEOUT_MILLIS);
        SchemaSupport.addBoolean(properties, "lineNumbers", "Include line-number metadata when decompiling first", false);
        SchemaSupport.addBoolean(properties, "advancedLookup", "Search sibling archives for dependency resolution; JDK modules are included by default", false);
        SchemaSupport.addStringOrArray(properties, "classpath", "Additional classpath entries");
        SchemaSupport.addString(properties, "saveTo", "Optional output file");
        SchemaSupport.require(schema, "path");
        return schema;
    }

    @Override
    public ToolResult execute(JsonObject arguments) throws Exception {
        DecompilerOptions options = DecompilerOptions.fromArguments(arguments, DecompilerEngines.AUTO);
        JdtParserSupport.SourceContext context = JdtParserSupport.resolve(arguments, options);
        CompilationUnit unit = JdtParserSupport.parse(context, true);

        LinkedHashSet<CastExpression> unnecessaryCasts = new LinkedHashSet<>();
        for (IProblem problem : unit.getProblems()) {
            if (problem.getID() == IProblem.UnnecessaryCast) {
                ProblemLocation location = new ProblemLocation(problem);
                ASTNode selectedNode = location.getCoveringNode(unit);
                ASTNode current = ASTNodes.getUnparenthesedExpression(selectedNode);
                if (current instanceof CastExpression castExpression) {
                    unnecessaryCasts.add(castExpression);
                }
            }
        }
        if (unnecessaryCasts.isEmpty()) {
            unit.accept(new ASTVisitor() {
                @Override
                public boolean visit(CastExpression node) {
                    if (isObviouslyRedundant(node)) {
                        unnecessaryCasts.add(node);
                    }
                    return true;
                }
            });
        }

        String cleaned = context.source();
        int removedCount = unnecessaryCasts.size();
        if (!unnecessaryCasts.isEmpty()) {
            Document document = new Document(context.source());
            CompilationUnitRewrite rewrite = new CompilationUnitRewrite(null, unit);
            RemoveAllCastOperation removeAllCastOperation = new RemoveAllCastOperation(unnecessaryCasts);
            try {
                removeAllCastOperation.rewriteAST(rewrite, null);
                TextEdit textEdit = rewrite.getASTRewrite().rewriteAST(document, null);
                textEdit.apply(document);
                cleaned = document.get();
            } catch (MalformedTreeException | BadLocationException | CoreException e) {
                throw new IllegalStateException("Failed to rewrite unnecessary casts", e);
            }
        } else {
            TextCleanupResult fallback = stripObviousLiteralCasts(context.source());
            cleaned = fallback.source();
            removedCount = fallback.removedCount();
        }
        String saveTo = JsonUtils.getString(arguments, "saveTo", null);
        if (saveTo != null && !saveTo.isBlank()) {
            Path savePath = Path.of(saveTo).toAbsolutePath().normalize();
            if (savePath.getParent() != null) {
                Files.createDirectories(savePath.getParent());
            }
            Files.writeString(savePath, cleaned);
        }

        JsonObject structured = new JsonObject();
        structured.addProperty("logicalName", context.logicalName());
        structured.addProperty("removedCastCount", removedCount);
        structured.addProperty("source", cleaned);
        return ToolResults.structured(cleaned, structured);
    }

    private static boolean isObviouslyRedundant(CastExpression castExpression) {
        ITypeBinding castType = castExpression.getType().resolveBinding();
        Expression expression = ASTNodes.getUnparenthesedExpression(castExpression.getExpression());
        ITypeBinding expressionType = expression != null ? expression.resolveTypeBinding() : null;
        if (castType == null || expressionType == null) {
            return isObviouslyRedundantBySyntax(castExpression, expression);
        }
        ITypeBinding castErasure = castType.getErasure();
        ITypeBinding expressionErasure = expressionType.getErasure();
        if (castErasure == null || expressionErasure == null) {
            return isObviouslyRedundantBySyntax(castExpression, expression);
        }
        if (castErasure.isEqualTo(expressionErasure)) {
            return true;
        }
        if (castErasure.isPrimitive() && expressionErasure.isPrimitive()
                && castErasure.getQualifiedName().equals(expressionErasure.getQualifiedName())) {
            return true;
        }
        return isObviouslyRedundantBySyntax(castExpression, expression);
    }

    private static boolean isObviouslyRedundantBySyntax(CastExpression castExpression, Expression expression) {
        String typeName = castExpression.getType().toString();
        return (expression instanceof StringLiteral && matchesSimpleTypeName(typeName, "String"))
                || (expression instanceof BooleanLiteral && matchesSimpleTypeName(typeName, "boolean", "Boolean"))
                || (expression instanceof CharacterLiteral && matchesSimpleTypeName(typeName, "char", "Character"))
                || (expression instanceof TypeLiteral && matchesSimpleTypeName(typeName, "Class"))
                || (expression instanceof ClassInstanceCreation creation && matchesTypeSuffix(typeName, creation.getType().toString()));
    }

    private static boolean matchesSimpleTypeName(String actual, String... expected) {
        for (String candidate : expected) {
            if (actual.equals(candidate) || actual.endsWith("." + candidate)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesTypeSuffix(String actual, String createdType) {
        return actual.equals(createdType)
                || actual.endsWith("." + createdType)
                || createdType.endsWith("." + actual);
    }

    private static TextCleanupResult stripObviousLiteralCasts(String source) {
        int removedCount = 0;
        Matcher stringMatcher = STRING_LITERAL_CAST.matcher(source);
        StringBuffer buffer = new StringBuffer();
        while (stringMatcher.find()) {
            removedCount++;
            stringMatcher.appendReplacement(buffer, Matcher.quoteReplacement(stringMatcher.group(1)));
        }
        stringMatcher.appendTail(buffer);

        Matcher charMatcher = CHAR_LITERAL_CAST.matcher(buffer.toString());
        StringBuffer finalBuffer = new StringBuffer();
        while (charMatcher.find()) {
            removedCount++;
            charMatcher.appendReplacement(finalBuffer, Matcher.quoteReplacement(charMatcher.group(1)));
        }
        charMatcher.appendTail(finalBuffer);
        return new TextCleanupResult(finalBuffer.toString(), removedCount);
    }

    private record TextCleanupResult(String source, int removedCount) {
    }
}
