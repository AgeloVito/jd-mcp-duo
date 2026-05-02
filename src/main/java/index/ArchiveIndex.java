package index;

import archive.ClassLocation;
import archive.InputContainer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.Handle;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import support.ResourceSearchSupport;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class ArchiveIndex {
    private final Path path;
    private final String fingerprint;
    private final Map<String, IndexedClass> classesByInternalName;
    private final Map<String, List<IndexedMethod>> methodsByOwner;
    private final Map<String, List<IndexedField>> fieldsByOwner;
    private final List<StringHit> strings;
    private final Map<MethodRef, Set<MethodRef>> outgoingCalls;
    private final Map<MethodRef, Set<MethodRef>> incomingCalls;
    private final Map<FieldRef, Set<MethodRef>> incomingFieldReferences;
    private final List<TypeReferenceHit> typeReferences;
    private final List<String> modules;
    private final List<IndexedResource> resources;

    private ArchiveIndex(Path path,
                         String fingerprint,
                         Map<String, IndexedClass> classesByInternalName,
                         Map<String, List<IndexedMethod>> methodsByOwner,
                         Map<String, List<IndexedField>> fieldsByOwner,
                         List<StringHit> strings,
                         Map<MethodRef, Set<MethodRef>> outgoingCalls,
                         Map<MethodRef, Set<MethodRef>> incomingCalls,
                         Map<FieldRef, Set<MethodRef>> incomingFieldReferences,
                         List<TypeReferenceHit> typeReferences,
                         List<String> modules,
                         List<IndexedResource> resources) {
        this.path = path;
        this.fingerprint = fingerprint;
        this.classesByInternalName = classesByInternalName;
        this.methodsByOwner = methodsByOwner;
        this.fieldsByOwner = fieldsByOwner;
        this.strings = strings;
        this.outgoingCalls = outgoingCalls;
        this.incomingCalls = incomingCalls;
        this.incomingFieldReferences = incomingFieldReferences;
        this.typeReferences = typeReferences;
        this.modules = modules;
        this.resources = resources;
    }

    public static ArchiveIndex build(InputContainer container, String fingerprint) throws IOException {
        Map<String, IndexedClass> classesByInternalName = new LinkedHashMap<>();
        Map<String, List<IndexedMethod>> methodsByOwner = new LinkedHashMap<>();
        Map<String, List<IndexedField>> fieldsByOwner = new LinkedHashMap<>();
        List<StringHit> strings = new ArrayList<>();
        Map<MethodRef, Set<MethodRef>> outgoing = new LinkedHashMap<>();
        Map<MethodRef, Set<MethodRef>> incoming = new LinkedHashMap<>();
        Map<FieldRef, Set<MethodRef>> incomingFieldReferences = new LinkedHashMap<>();
        List<TypeReferenceHit> typeReferences = new ArrayList<>();
        List<String> modules = new ArrayList<>();
        List<IndexedResource> resources = new ArrayList<>(ResourceSearchSupport.collectForIndex(container.path()));
        typeReferences.addAll(ResourceSearchSupport.extractTypeReferences(resources));

        // --- Pass 1: collect class hierarchy and method signatures ---
        Map<String, ClassNode> classNodes = new LinkedHashMap<>();
        Map<String, Integer> classVersions = new LinkedHashMap<>();

        for (ClassLocation location : container.listClasses(true)) {
            byte[] bytes = container.loadClassBytes(location.internalName());
            if (bytes == null) {
                continue;
            }
            ClassReader classReader = new ClassReader(bytes);
            ClassNode classNode = new ClassNode();
            classReader.accept(classNode, ClassReader.SKIP_FRAMES);
            classNodes.put(classNode.name, classNode);
            classVersions.put(classNode.name, Integer.valueOf(classReader.readShort(6)));
            if (classNode.module != null) {
                modules.add(classNode.module.name);
            }
            // Pre-populate methodsByOwner for CHA resolution in pass 2
            List<IndexedMethod> pass1Methods = new ArrayList<>();
            for (MethodNode methodNode : classNode.methods) {
                pass1Methods.add(new IndexedMethod(
                        new MethodRef(classNode.name, methodNode.name, methodNode.desc),
                        List.of(),
                        methodNode.access));
            }
            methodsByOwner.put(classNode.name, pass1Methods);
        }

        // Build hierarchy: for each parent type, which known subtypes exist in scope
        Map<String, Set<String>> subtypesByParent = new LinkedHashMap<>();
        for (Map.Entry<String, ClassNode> entry : classNodes.entrySet()) {
            ClassNode classNode = entry.getValue();
            if (classNode.superName != null) {
                subtypesByParent.computeIfAbsent(classNode.superName, k -> new LinkedHashSet<>()).add(classNode.name);
            }
            for (String iface : classNode.interfaces) {
                subtypesByParent.computeIfAbsent(iface, k -> new LinkedHashSet<>()).add(classNode.name);
            }
        }
        // Transitively close the hierarchy
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Map.Entry<String, Set<String>> entry : new LinkedHashMap<>(subtypesByParent).entrySet()) {
                Set<String> expanded = new LinkedHashSet<>(entry.getValue());
                for (String direct : entry.getValue()) {
                    Set<String> transitive = subtypesByParent.get(direct);
                    if (transitive != null) {
                        expanded.addAll(transitive);
                    }
                }
                if (expanded.size() > entry.getValue().size()) {
                    subtypesByParent.put(entry.getKey(), expanded);
                    changed = true;
                }
            }
        }
        // Clear pass-1 methodsByOwner — will be rebuilt in pass 2 with full data
        Map<String, List<IndexedMethod>> chaMethodLookup = Map.copyOf(methodsByOwner);
        methodsByOwner.clear();

        // --- Pass 2: build index with CHA-aware call resolution ---
        for (Map.Entry<String, ClassNode> entry : classNodes.entrySet()) {
            ClassNode classNode = entry.getValue();
            Integer bytecodeVersion = classVersions.get(entry.getKey());

            if (classNode.superName != null) {
                typeReferences.add(new TypeReferenceHit(classNode.name, null, null, classNode.superName, "extends"));
            }
            for (String interfaceName : classNode.interfaces) {
                typeReferences.add(new TypeReferenceHit(classNode.name, null, null, interfaceName, "implements"));
            }

            List<IndexedField> fields = new ArrayList<>();
            for (FieldNode fieldNode : classNode.fields) {
                IndexedField field = new IndexedField(classNode.name, fieldNode.name, fieldNode.desc, fieldNode.access);
                fields.add(field);
                if (fieldNode.value instanceof String stringValue) {
                    strings.add(new StringHit(classNode.name, fieldNode.name, fieldNode.desc, stringValue));
                }
                addDescriptorTypeReferences(typeReferences, classNode.name, fieldNode.name, fieldNode.desc, fieldNode.desc, "field-type");
            }

            List<IndexedMethod> methods = new ArrayList<>();
            for (MethodNode methodNode : classNode.methods) {
                MethodRef methodRef = new MethodRef(classNode.name, methodNode.name, methodNode.desc);
                List<String> methodStrings = new ArrayList<>();
                Set<MethodRef> callees = outgoing.computeIfAbsent(methodRef, ignored -> new LinkedHashSet<>());
                addDescriptorTypeReferences(typeReferences, classNode.name, methodNode.name, methodNode.desc, methodNode.desc, "method-signature");
                if (methodNode.exceptions != null) {
                    for (String exceptionType : methodNode.exceptions) {
                        typeReferences.add(new TypeReferenceHit(classNode.name, methodNode.name, methodNode.desc, exceptionType, "throws"));
                    }
                }

                methodNode.instructions.forEach(instruction -> {
                    if (instruction instanceof MethodInsnNode methodInsnNode) {
                        addMethodCall(outgoing, incoming, typeReferences, classNode, methodNode, methodRef,
                                methodInsnNode.owner, methodInsnNode.name, methodInsnNode.desc, "method-owner");
                        addDescriptorTypeReferences(typeReferences, classNode.name, methodNode.name, methodNode.desc, methodInsnNode.desc, "method-descriptor");

                        // CHA: for virtual calls, also resolve to concrete subtype implementations
                        if (isVirtualCall(methodInsnNode.getOpcode())) {
                            resolveVirtualTargets(outgoing, incoming, typeReferences, classNode, methodNode,
                                    methodRef, methodInsnNode, subtypesByParent, chaMethodLookup);
                        }
                    } else if (instruction instanceof InvokeDynamicInsnNode invokeDynamicInsnNode) {
                        addDescriptorTypeReferences(typeReferences, classNode.name, methodNode.name, methodNode.desc, invokeDynamicInsnNode.desc, "invokedynamic");
                        for (Object argument : invokeDynamicInsnNode.bsmArgs) {
                            if (argument instanceof Handle handle) {
                                MethodRef callee = new MethodRef(handle.getOwner(), handle.getName(), handle.getDesc());
                                callees.add(callee);
                                incoming.computeIfAbsent(callee, ignored -> new LinkedHashSet<>()).add(methodRef);
                                typeReferences.add(new TypeReferenceHit(classNode.name, methodNode.name, methodNode.desc, handle.getOwner(), "handle-owner"));
                                addDescriptorTypeReferences(typeReferences, classNode.name, methodNode.name, methodNode.desc, handle.getDesc(), "handle-descriptor");
                            } else if (argument instanceof Type type) {
                                addTypeReference(typeReferences, classNode.name, methodNode.name, methodNode.desc, type, "type-constant");
                            }
                        }
                    } else if (instruction instanceof FieldInsnNode fieldInsnNode) {
                        FieldRef fieldRef = new FieldRef(fieldInsnNode.owner, fieldInsnNode.name, fieldInsnNode.desc);
                        incomingFieldReferences.computeIfAbsent(fieldRef, ignored -> new LinkedHashSet<>()).add(methodRef);
                        typeReferences.add(new TypeReferenceHit(classNode.name, methodNode.name, methodNode.desc, fieldInsnNode.owner, "field-owner"));
                        addDescriptorTypeReferences(typeReferences, classNode.name, methodNode.name, methodNode.desc, fieldInsnNode.desc, "field-descriptor");
                    } else if (instruction instanceof TypeInsnNode typeInsnNode) {
                        typeReferences.add(new TypeReferenceHit(classNode.name, methodNode.name, methodNode.desc, typeInsnNode.desc, opcodeKind(typeInsnNode.getOpcode())));
                    } else if (instruction instanceof LdcInsnNode ldcInsnNode && ldcInsnNode.cst instanceof String stringValue) {
                        methodStrings.add(stringValue);
                        strings.add(new StringHit(classNode.name, methodNode.name, methodNode.desc, stringValue));
                    } else if (instruction instanceof LdcInsnNode ldcInsnNode && ldcInsnNode.cst instanceof Type type) {
                        addTypeReference(typeReferences, classNode.name, methodNode.name, methodNode.desc, type, "type-constant");
                    } else if (instruction instanceof MultiANewArrayInsnNode multiANewArrayInsnNode) {
                        addDescriptorTypeReferences(typeReferences, classNode.name, methodNode.name, methodNode.desc, multiANewArrayInsnNode.desc, "multi-anewarray");
                    }
                });
                if (methodNode.tryCatchBlocks != null) {
                    for (TryCatchBlockNode tryCatchBlock : methodNode.tryCatchBlocks) {
                        if (tryCatchBlock.type != null) {
                            typeReferences.add(new TypeReferenceHit(classNode.name, methodNode.name, methodNode.desc, tryCatchBlock.type, "catch"));
                        }
                    }
                }

                methods.add(new IndexedMethod(methodRef, List.copyOf(methodStrings), methodNode.access));
            }

            IndexedClass indexedClass = new IndexedClass(
                    classNode.name,
                    classNode.name.replace('/', '.'),
                    classNode.superName,
                    classNode.interfaces.isEmpty() ? List.of() : List.copyOf(classNode.interfaces),
                    classNode.access,
                    bytecodeVersion,
                    classNode.module != null ? classNode.module.name : null,
                    List.copyOf(fields),
                    List.copyOf(methods)
            );
            classesByInternalName.put(classNode.name, indexedClass);
            methodsByOwner.put(classNode.name, indexedClass.methods());
            fieldsByOwner.put(classNode.name, indexedClass.fields());
        }

        return new ArchiveIndex(
                container.path(),
                fingerprint,
                Map.copyOf(classesByInternalName),
                Map.copyOf(methodsByOwner),
                Map.copyOf(fieldsByOwner),
                List.copyOf(strings),
                freeze(outgoing),
                freeze(incoming),
                freezeFieldRefs(incomingFieldReferences),
                List.copyOf(typeReferences),
                List.copyOf(modules),
                List.copyOf(resources)
        );
    }

    private static void addMethodCall(Map<MethodRef, Set<MethodRef>> outgoing,
                                       Map<MethodRef, Set<MethodRef>> incoming,
                                       List<TypeReferenceHit> typeReferences,
                                       ClassNode classNode,
                                       MethodNode methodNode,
                                       MethodRef callerRef,
                                       String owner,
                                       String name,
                                       String desc,
                                       String typeRefKind) {
        MethodRef callee = new MethodRef(owner, name, desc);
        outgoing.computeIfAbsent(callerRef, ignored -> new LinkedHashSet<>()).add(callee);
        incoming.computeIfAbsent(callee, ignored -> new LinkedHashSet<>()).add(callerRef);
        typeReferences.add(new TypeReferenceHit(classNode.name, methodNode.name, methodNode.desc, owner, typeRefKind));
    }

    private static boolean isVirtualCall(int opcode) {
        return opcode == Opcodes.INVOKEVIRTUAL || opcode == Opcodes.INVOKEINTERFACE;
    }

    private static void resolveVirtualTargets(Map<MethodRef, Set<MethodRef>> outgoing,
                                               Map<MethodRef, Set<MethodRef>> incoming,
                                               List<TypeReferenceHit> typeReferences,
                                               ClassNode classNode,
                                               MethodNode methodNode,
                                               MethodRef callerRef,
                                               MethodInsnNode call,
                                               Map<String, Set<String>> subtypesByParent,
                                               Map<String, List<IndexedMethod>> methodsByOwner) {
        Set<String> subtypes = subtypesByParent.get(call.owner);
        if (subtypes == null || subtypes.isEmpty()) {
            return;
        }
        for (String subtype : subtypes) {
            List<IndexedMethod> subtypeMethods = methodsByOwner.get(subtype);
            if (subtypeMethods == null) {
                continue;
            }
            for (IndexedMethod m : subtypeMethods) {
                if (m.ref().name().equals(call.name) && m.ref().descriptor().equals(call.desc)) {
                    MethodRef concreteCallee = new MethodRef(subtype, call.name, call.desc);
                    outgoing.computeIfAbsent(callerRef, ignored -> new LinkedHashSet<>()).add(concreteCallee);
                    incoming.computeIfAbsent(concreteCallee, ignored -> new LinkedHashSet<>()).add(callerRef);
                    typeReferences.add(new TypeReferenceHit(classNode.name, methodNode.name, methodNode.desc, subtype, "cha-virtual"));
                }
            }
        }
    }

    public Path path() {
        return path;
    }

    public String fingerprint() {
        return fingerprint;
    }

    public Collection<IndexedClass> classes() {
        return classesByInternalName.values();
    }

    public List<StringHit> strings() {
        return strings;
    }

    public IndexedClass getClass(String internalName) {
        return classesByInternalName.get(internalName);
    }

    public List<IndexedMethod> resolveMethodCandidates(String owner, String methodName, String descriptor) {
        List<IndexedMethod> methods = methodsByOwner.getOrDefault(owner, List.of());
        return methods.stream()
                .filter(method -> method.ref().name().equals(methodName))
                .filter(method -> descriptor == null || descriptor.isBlank() || descriptor.equals(method.ref().descriptor()))
                .toList();
    }

    public List<IndexedField> resolveFieldCandidates(String owner, String fieldName) {
        return fieldsByOwner.getOrDefault(owner, List.of()).stream()
                .filter(field -> field.name().equals(fieldName))
                .toList();
    }

    public Set<MethodRef> outgoing(MethodRef method) {
        return outgoingCalls.getOrDefault(method, Set.of());
    }

    public Set<MethodRef> incoming(MethodRef method) {
        return incomingCalls.getOrDefault(method, Set.of());
    }

    public Set<MethodRef> incoming(FieldRef field) {
        return incomingFieldReferences.getOrDefault(field, Set.of());
    }

    Set<Map.Entry<MethodRef, Set<MethodRef>>> outgoingEntrySet() {
        return outgoingCalls.entrySet();
    }

    Set<Map.Entry<MethodRef, Set<MethodRef>>> incomingEntrySet() {
        return incomingCalls.entrySet();
    }

    Set<Map.Entry<FieldRef, Set<MethodRef>>> fieldRefEntrySet() {
        return incomingFieldReferences.entrySet();
    }

    public List<TypeReferenceHit> typeReferences() {
        return typeReferences;
    }

    public List<String> modules() {
        return modules;
    }

    public List<IndexedResource> resources() {
        return resources;
    }

    private static Map<MethodRef, Set<MethodRef>> freeze(Map<MethodRef, Set<MethodRef>> edges) {
        Map<MethodRef, Set<MethodRef>> frozen = new LinkedHashMap<>();
        for (Map.Entry<MethodRef, Set<MethodRef>> entry : edges.entrySet()) {
            frozen.put(entry.getKey(), Set.copyOf(entry.getValue()));
        }
        return Map.copyOf(frozen);
    }

    private static Map<FieldRef, Set<MethodRef>> freezeFieldRefs(Map<FieldRef, Set<MethodRef>> edges) {
        Map<FieldRef, Set<MethodRef>> frozen = new LinkedHashMap<>();
        for (Map.Entry<FieldRef, Set<MethodRef>> entry : edges.entrySet()) {
            frozen.put(entry.getKey(), Set.copyOf(entry.getValue()));
        }
        return Map.copyOf(frozen);
    }

    private static void addDescriptorTypeReferences(List<TypeReferenceHit> sink,
                                                    String sourceOwner,
                                                    String sourceMemberName,
                                                    String sourceMemberDescriptor,
                                                    String descriptor,
                                                    String kind) {
        for (Type type : extractTypes(descriptor)) {
            addTypeReference(sink, sourceOwner, sourceMemberName, sourceMemberDescriptor, type, kind);
        }
    }

    private static void addTypeReference(List<TypeReferenceHit> sink,
                                         String sourceOwner,
                                         String sourceMemberName,
                                         String sourceMemberDescriptor,
                                         Type type,
                                         String kind) {
        if (type == null) {
            return;
        }
        switch (type.getSort()) {
            case Type.OBJECT -> sink.add(new TypeReferenceHit(sourceOwner, sourceMemberName, sourceMemberDescriptor, type.getInternalName(), kind));
            case Type.ARRAY -> addTypeReference(sink, sourceOwner, sourceMemberName, sourceMemberDescriptor, type.getElementType(), kind);
            case Type.METHOD -> {
                addTypeReference(sink, sourceOwner, sourceMemberName, sourceMemberDescriptor, type.getReturnType(), kind);
                for (Type argumentType : type.getArgumentTypes()) {
                    addTypeReference(sink, sourceOwner, sourceMemberName, sourceMemberDescriptor, argumentType, kind);
                }
            }
            default -> {
            }
        }
    }

    private static List<Type> extractTypes(String descriptor) {
        if (descriptor == null || descriptor.isBlank()) {
            return List.of();
        }
        try {
            Type methodType = Type.getMethodType(descriptor);
            List<Type> types = new ArrayList<>();
            types.add(methodType.getReturnType());
            types.addAll(List.of(methodType.getArgumentTypes()));
            return types;
        } catch (RuntimeException ignored) {
            try {
                return List.of(Type.getType(descriptor));
            } catch (RuntimeException ignoredAgain) {
                return List.of();
            }
        }
    }

    private static String opcodeKind(int opcode) {
        return switch (opcode) {
            case Opcodes.NEW -> "new";
            case Opcodes.CHECKCAST -> "checkcast";
            case Opcodes.INSTANCEOF -> "instanceof";
            case Opcodes.ANEWARRAY -> "anewarray";
            default -> "type-insn";
        };
    }
}
