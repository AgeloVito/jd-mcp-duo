package decompile;

import jd.core.DecompilationResult;
import jd.core.links.DeclarationData;
import jd.core.links.HyperlinkData;
import jd.core.links.HyperlinkReferenceData;
import jd.core.links.ReferenceData;
import jd.core.links.StringData;
import org.eclipse.jdt.core.dom.*;
import support.JdtParserSupport;

import java.net.URI;
import java.util.*;

final class PatchedMetadataSupport {
    private PatchedMetadataSupport() {
    }

    static boolean rebuildPatchedMetadata(DecompilationResult target,
                                          DecompilationResult v1Result,
                                          DecompilationResult v0Result,
                                          String patchedSource,
                                          JdtMethodPatcher.PatchResult patchResult,
                                          String unitName,
                                          URI contextUri,
                                          List<String> classpathEntries) {
        if (target == null || patchResult == null || patchResult.methodPatches().isEmpty()) {
            return false;
        }

        try {
            JdtParserSupport.SourceContext context = new JdtParserSupport.SourceContext(
                    patchedSource,
                    unitName.endsWith(".class") ? unitName.replace(".class", ".java") : unitName,
                    contextUri,
                    List.copyOf(classpathEntries),
                    List.of(),
                    unitName
            );
            CompilationUnit unit = JdtParserSupport.parse(context, true);
            RebuiltMetadata metadata = collect(unit);

            target.setDeclarations(metadata.declarations());
            target.setTypeDeclarations(metadata.typeDeclarations());
            target.setStrings(metadata.strings());
            target.setReferences(metadata.references());
            target.setHyperlinks(metadata.hyperlinks());

            Map<Integer, Integer> mergedLineNumbers = mergeLineNumbers(
                    v1Result,
                    v0Result,
                    patchResult.methodPatches(),
                    metadata.patchedMethodLines()
            );
            target.setLineNumbers(mergedLineNumbers);
            target.setMaxLineNumber(mergedLineNumbers.values().stream().mapToInt(Integer::intValue).max().orElse(0));
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static RebuiltMetadata collect(CompilationUnit unit) {
        Map<String, DeclarationData> declarations = new LinkedHashMap<>();
        NavigableMap<Integer, DeclarationData> typeDeclarations = new TreeMap<>();
        List<ReferenceData> references = new ArrayList<>();
        SortedMap<Integer, HyperlinkData> hyperlinks = new TreeMap<>();
        List<StringData> strings = new ArrayList<>();
        Map<String, int[]> patchedMethodLines = new LinkedHashMap<>();
        Set<String> referenceKeys = new LinkedHashSet<>();
        Deque<String> typeStack = new ArrayDeque<>();

        unit.accept(new ASTVisitor() {
            @Override
            public boolean visit(TypeDeclaration node) {
                registerType(node, node.resolveBinding());
                return true;
            }

            @Override
            public void endVisit(TypeDeclaration node) {
                popType();
            }

            @Override
            public boolean visit(EnumDeclaration node) {
                registerType(node, node.resolveBinding());
                return true;
            }

            @Override
            public void endVisit(EnumDeclaration node) {
                popType();
            }

            @Override
            public boolean visit(AnnotationTypeDeclaration node) {
                registerType(node, node.resolveBinding());
                return true;
            }

            @Override
            public void endVisit(AnnotationTypeDeclaration node) {
                popType();
            }

            @Override
            public boolean visit(RecordDeclaration node) {
                registerType(node, node.resolveBinding());
                return true;
            }

            @Override
            public void endVisit(RecordDeclaration node) {
                popType();
            }

            @Override
            public boolean visit(MethodDeclaration node) {
                IMethodBinding binding = node.resolveBinding();
                SimpleName name = node.getName();
                String owner = binding != null ? internalName(binding.getDeclaringClass()) : currentType();
                String descriptor = binding != null ? methodDescriptor(binding) : null;
                String methodName = binding != null && binding.isConstructor() ? "<init>" : name.getIdentifier();
                DeclarationData data = new DeclarationData(name.getStartPosition(), name.getLength(), owner, methodName, descriptor);
                declarations.put(declarationKey(owner, methodName, descriptor, name.getStartPosition()), data);
                if (binding != null) {
                    int[] lineRange = methodLineRange(unit, node);
                    patchedMethodLines.put(binding.getKey(), lineRange);
                    patchedMethodLines.put(relaxedBindingKey(binding.getKey()), lineRange);
                }
                return true;
            }

            @Override
            public boolean visit(AnnotationTypeMemberDeclaration node) {
                IMethodBinding binding = node.resolveBinding();
                SimpleName name = node.getName();
                String owner = binding != null ? internalName(binding.getDeclaringClass()) : currentType();
                String descriptor = binding != null ? methodDescriptor(binding) : "()Ljava/lang/Object;";
                DeclarationData data = new DeclarationData(name.getStartPosition(), name.getLength(), owner, name.getIdentifier(), descriptor);
                declarations.put(declarationKey(owner, name.getIdentifier(), descriptor, name.getStartPosition()), data);
                return true;
            }

            @Override
            public boolean visit(VariableDeclarationFragment node) {
                IVariableBinding binding = node.resolveBinding();
                if (binding != null && binding.isField()) {
                    String owner = internalName(binding.getDeclaringClass());
                    String descriptor = descriptor(binding.getType());
                    DeclarationData data = new DeclarationData(node.getName().getStartPosition(), node.getName().getLength(), owner, node.getName().getIdentifier(), descriptor);
                    declarations.put(declarationKey(owner, node.getName().getIdentifier(), descriptor, node.getStartPosition()), data);
                }
                return true;
            }

            @Override
            public boolean visit(EnumConstantDeclaration node) {
                IVariableBinding binding = node.resolveVariable();
                if (binding != null) {
                    String owner = internalName(binding.getDeclaringClass());
                    String descriptor = descriptor(binding.getType());
                    DeclarationData data = new DeclarationData(node.getName().getStartPosition(), node.getName().getLength(), owner, node.getName().getIdentifier(), descriptor);
                    declarations.put(declarationKey(owner, node.getName().getIdentifier(), descriptor, node.getStartPosition()), data);
                }
                addReference(node.getName().getStartPosition(), node.getName().getLength(), constructorReference(node.resolveConstructorBinding()));
                return true;
            }

            @Override
            public boolean visit(StringLiteral node) {
                strings.add(new StringData(node.getStartPosition(), node.getLiteralValue(), currentType()));
                return true;
            }

            @Override
            public boolean visit(TextBlock node) {
                strings.add(new StringData(node.getStartPosition(), node.getLiteralValue(), currentType()));
                return true;
            }

            @Override
            public boolean visit(MethodInvocation node) {
                addReference(node.getName().getStartPosition(), node.getName().getLength(), methodReference(node.resolveMethodBinding()));
                return true;
            }

            @Override
            public boolean visit(SuperMethodInvocation node) {
                addReference(node.getName().getStartPosition(), node.getName().getLength(), methodReference(node.resolveMethodBinding()));
                return true;
            }

            @Override
            public boolean visit(ExpressionMethodReference node) {
                addReference(node.getName().getStartPosition(), node.getName().getLength(), methodReference(node.resolveMethodBinding()));
                return true;
            }

            @Override
            public boolean visit(TypeMethodReference node) {
                addReference(node.getName().getStartPosition(), node.getName().getLength(), methodReference(node.resolveMethodBinding()));
                return true;
            }

            @Override
            public boolean visit(SuperMethodReference node) {
                addReference(node.getName().getStartPosition(), node.getName().getLength(), methodReference(node.resolveMethodBinding()));
                return true;
            }

            @Override
            public boolean visit(ClassInstanceCreation node) {
                int[] span = typeSpan(node.getType());
                addReference(span[0], span[1], constructorReference(node.resolveConstructorBinding()));
                return true;
            }

            @Override
            public boolean visit(ConstructorInvocation node) {
                addReference(node.getStartPosition(), 4, constructorReference(node.resolveConstructorBinding()));
                return true;
            }

            @Override
            public boolean visit(SuperConstructorInvocation node) {
                addReference(node.getStartPosition(), 5, constructorReference(node.resolveConstructorBinding()));
                return true;
            }

            @Override
            public boolean visit(CreationReference node) {
                int[] span = typeSpan(node.getType());
                addReference(span[0], span[1], constructorReference(node.resolveMethodBinding()));
                return true;
            }

            @Override
            public boolean visit(SimpleName node) {
                if (isDeclarationName(node) || isHandledReference(node)) {
                    return true;
                }
                addReference(node.getStartPosition(), node.getLength(), referenceFor(node.resolveBinding()));
                return true;
            }

            private void addReference(int startPosition, int length, ReferenceData reference) {
                if (reference == null || startPosition < 0 || length <= 0) {
                    return;
                }
                String key = startPosition + ":" + length + ":" + reference.getTypeName() + ":" + reference.getOwner() + ":" + reference.getName() + ":" + reference.getDescriptor();
                if (!referenceKeys.add(key)) {
                    return;
                }
                references.add(reference);
                hyperlinks.put(startPosition, new HyperlinkReferenceData(startPosition, length, reference));
            }

            private void registerType(AbstractTypeDeclaration node, ITypeBinding binding) {
                String internalName = internalName(binding);
                DeclarationData data = new DeclarationData(node.getName().getStartPosition(), node.getName().getLength(), internalName, null, null);
                typeDeclarations.put(node.getName().getStartPosition(), data);
                declarations.put(declarationKey(internalName, null, null, node.getStartPosition()), data);
                typeStack.push(internalName);
            }

            private void popType() {
                if (!typeStack.isEmpty()) {
                    typeStack.pop();
                }
            }

            private String currentType() {
                return typeStack.isEmpty() ? null : typeStack.peek();
            }
        });

        return new RebuiltMetadata(
                Map.copyOf(declarations),
                Collections.unmodifiableNavigableMap(typeDeclarations),
                List.copyOf(references),
                Collections.unmodifiableSortedMap(new TreeMap<>(hyperlinks)),
                List.copyOf(strings),
                Map.copyOf(patchedMethodLines)
        );
    }

    private static boolean isDeclarationName(SimpleName node) {
        ASTNode parent = node.getParent();
        return (parent instanceof TypeDeclaration type && type.getName() == node)
                || (parent instanceof EnumDeclaration enumDecl && enumDecl.getName() == node)
                || (parent instanceof AnnotationTypeDeclaration annotation && annotation.getName() == node)
                || (parent instanceof RecordDeclaration record && record.getName() == node)
                || (parent instanceof MethodDeclaration method && method.getName() == node)
                || (parent instanceof AnnotationTypeMemberDeclaration annotationMember && annotationMember.getName() == node)
                || (parent instanceof EnumConstantDeclaration enumConstant && enumConstant.getName() == node)
                || (parent instanceof VariableDeclarationFragment fragment && fragment.getName() == node);
    }

    private static boolean isHandledReference(SimpleName node) {
        ASTNode parent = node.getParent();
        if (parent instanceof MethodInvocation method && method.getName() == node) {
            return true;
        }
        if (parent instanceof SuperMethodInvocation method && method.getName() == node) {
            return true;
        }
        if (parent instanceof ExpressionMethodReference method && method.getName() == node) {
            return true;
        }
        if (parent instanceof TypeMethodReference method && method.getName() == node) {
            return true;
        }
        if (parent instanceof SuperMethodReference method && method.getName() == node) {
            return true;
        }
        if (parent instanceof SimpleType type && type.getName() == node) {
            return isConstructorType(type.getParent());
        }
        if (parent instanceof NameQualifiedType type && type.getName() == node) {
            return isConstructorType(type.getParent());
        }
        if (parent instanceof QualifiedType type && type.getName() == node) {
            return isConstructorType(type.getParent());
        }
        if (parent instanceof QualifiedName qualifiedName && qualifiedName.getName() == node) {
            return isConstructorType(qualifiedName.getParent());
        }
        return false;
    }

    private static boolean isConstructorType(ASTNode parent) {
        ASTNode current = parent;
        while (current instanceof Type || current instanceof Name) {
            current = current.getParent();
        }
        return current instanceof ClassInstanceCreation || current instanceof CreationReference;
    }

    private static ReferenceData referenceFor(IBinding binding) {
        if (binding == null) {
            return null;
        }
        if (binding instanceof ITypeBinding typeBinding) {
            return new ReferenceData(internalName(typeBinding), null, null, null);
        }
        if (binding instanceof IMethodBinding methodBinding) {
            return methodReference(methodBinding);
        }
        if (binding instanceof IVariableBinding variableBinding && variableBinding.isField()) {
            String owner = internalName(variableBinding.getDeclaringClass());
            return new ReferenceData(owner, variableBinding.getName(), descriptor(variableBinding.getType()), owner);
        }
        return null;
    }

    private static Map<Integer, Integer> mergeLineNumbers(DecompilationResult v1Result,
                                                          DecompilationResult v0Result,
                                                          List<JdtMethodPatcher.MethodPatch> methodPatches,
                                                          Map<String, int[]> patchedMethodLines) {
        LinkedHashMap<Integer, Integer> merged = new LinkedHashMap<>();
        if (v1Result != null && v1Result.getLineNumbers() != null) {
            merged.putAll(v1Result.getLineNumbers());
        }
        Map<Integer, Integer> v0Lines = v0Result == null || v0Result.getLineNumbers() == null ? Map.of() : v0Result.getLineNumbers();
        for (JdtMethodPatcher.MethodPatch patch : methodPatches) {
            int[] patchedLines = patchedMethodLines.get(patch.bindingKey());
            if (patchedLines == null) {
                patchedLines = patchedMethodLines.get(relaxedBindingKey(patch.bindingKey()));
            }
            int targetStartLine = patchedLines == null ? patch.v1StartLine() : patchedLines[0];
            int targetEndLine = patchedLines == null ? patch.v1EndLine() : patchedLines[1];
            int targetSpan = Math.max(0, targetEndLine - targetStartLine);
            int sourceSpan = Math.max(0, patch.v0EndLine() - patch.v0StartLine());
            for (int offset = 0; offset <= targetSpan; offset++) {
                int targetLine = targetStartLine + offset;
                int sourceLine = patch.v0StartLine() + Math.min(offset, sourceSpan);
                Integer mapped = v0Lines.get(sourceLine);
                if (mapped != null) {
                    merged.put(targetLine, mapped);
                }
            }
        }
        return merged;
    }

    private static String declarationKey(String owner, String name, String descriptor, int startPosition) {
        return (owner == null ? "?" : owner) + "#" + (name == null ? "<type>" : name) + "#" + (descriptor == null ? "" : descriptor) + "@" + startPosition;
    }

    private static String internalName(ITypeBinding binding) {
        if (binding == null) {
            return null;
        }
        ITypeBinding erasure = binding.getErasure();
        String binaryName = erasure.getBinaryName();
        if (binaryName != null && !binaryName.isBlank()) {
            return binaryName.replace('.', '/');
        }
        String qualifiedName = erasure.getQualifiedName();
        return qualifiedName == null || qualifiedName.isBlank() ? null : qualifiedName.replace('.', '/');
    }

    private static ReferenceData methodReference(IMethodBinding binding) {
        if (binding == null) {
            return null;
        }
        String owner = internalName(binding.getDeclaringClass());
        String name = binding.isConstructor() ? "<init>" : binding.getName();
        return new ReferenceData(owner, name, methodDescriptor(binding), owner);
    }

    private static ReferenceData constructorReference(IMethodBinding binding) {
        if (binding == null) {
            return null;
        }
        String owner = internalName(binding.getDeclaringClass());
        return new ReferenceData(owner, "<init>", methodDescriptor(binding), owner);
    }

    private static String descriptor(ITypeBinding binding) {
        if (binding == null) {
            return "Ljava/lang/Object;";
        }
        ITypeBinding erasure = binding.getErasure();
        if (erasure.isPrimitive()) {
            return switch (erasure.getName()) {
                case "void" -> "V";
                case "boolean" -> "Z";
                case "byte" -> "B";
                case "char" -> "C";
                case "short" -> "S";
                case "int" -> "I";
                case "long" -> "J";
                case "float" -> "F";
                case "double" -> "D";
                default -> "Ljava/lang/Object;";
            };
        }
        if (erasure.isArray()) {
            return "[" + descriptor(erasure.getComponentType());
        }
        String internalName = internalName(erasure);
        return internalName == null ? "Ljava/lang/Object;" : "L" + internalName + ";";
    }

    private static String methodDescriptor(IMethodBinding binding) {
        StringBuilder builder = new StringBuilder("(");
        for (ITypeBinding parameterType : binding.getParameterTypes()) {
            builder.append(descriptor(parameterType));
        }
        if (binding.isConstructor()) {
            builder.append(")V");
            return builder.toString();
        }
        builder.append(')').append(descriptor(binding.getReturnType()));
        return builder.toString();
    }

    private static int[] methodLineRange(CompilationUnit unit, MethodDeclaration declaration) {
        ASTNode node = declaration.getBody() != null ? declaration.getBody() : declaration;
        int start = unit.getLineNumber(node.getStartPosition());
        int end = unit.getLineNumber(Math.max(node.getStartPosition(), node.getStartPosition() + node.getLength() - 1));
        return new int[]{start, end};
    }

    private static int[] typeSpan(Type type) {
        if (type instanceof ParameterizedType parameterizedType) {
            return typeSpan(parameterizedType.getType());
        }
        if (type instanceof ArrayType arrayType) {
            return typeSpan(arrayType.getElementType());
        }
        if (type instanceof SimpleType simpleType) {
            return nameSpan(simpleType.getName());
        }
        if (type instanceof QualifiedType qualifiedType) {
            return new int[]{qualifiedType.getName().getStartPosition(), qualifiedType.getName().getLength()};
        }
        if (type instanceof NameQualifiedType nameQualifiedType) {
            return new int[]{nameQualifiedType.getName().getStartPosition(), nameQualifiedType.getName().getLength()};
        }
        return new int[]{type.getStartPosition(), type.getLength()};
    }

    private static int[] nameSpan(Name name) {
        if (name instanceof QualifiedName qualifiedName) {
            return new int[]{qualifiedName.getName().getStartPosition(), qualifiedName.getName().getLength()};
        }
        return new int[]{name.getStartPosition(), name.getLength()};
    }

    private static String relaxedBindingKey(String bindingKey) {
        return bindingKey == null ? null : bindingKey.replaceAll("L(\\w+/)*+", "L");
    }

    private record RebuiltMetadata(Map<String, DeclarationData> declarations,
                                   NavigableMap<Integer, DeclarationData> typeDeclarations,
                                   List<ReferenceData> references,
                                   SortedMap<Integer, HyperlinkData> hyperlinks,
                                   List<StringData> strings,
                                   Map<String, int[]> patchedMethodLines) {
    }
}
