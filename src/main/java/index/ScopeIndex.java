package index;

import support.ScopeSupport;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ScopeIndex {
    private final List<ArchiveIndex> archives;
    private final Map<String, List<ScopedClass>> classesByInternalName;
    private final Map<MethodRef, Set<MethodRef>> outgoingCalls;
    private final Map<MethodRef, Set<MethodRef>> incomingCalls;
    private final Map<FieldRef, Set<MethodRef>> incomingFieldReferences;
    private final List<ScopedStringHit> strings;
    private final List<TypeReferenceHit> typeReferences;
    private final List<String> modules;

    private ScopeIndex(List<ArchiveIndex> archives,
                       Map<String, List<ScopedClass>> classesByInternalName,
                       Map<MethodRef, Set<MethodRef>> outgoingCalls,
                       Map<MethodRef, Set<MethodRef>> incomingCalls,
                       Map<FieldRef, Set<MethodRef>> incomingFieldReferences,
                       List<ScopedStringHit> strings,
                       List<TypeReferenceHit> typeReferences,
                       List<String> modules) {
        this.archives = archives;
        this.classesByInternalName = classesByInternalName;
        this.outgoingCalls = outgoingCalls;
        this.incomingCalls = incomingCalls;
        this.incomingFieldReferences = incomingFieldReferences;
        this.strings = strings;
        this.typeReferences = typeReferences;
        this.modules = modules;
    }

    public static ScopeIndex open(Path primaryPath, Path scopePath, boolean recursive) throws IOException {
        List<Path> inputs = ScopeSupport.collectScopeInputs(primaryPath, scopePath, recursive);

        List<ArchiveIndex> archives = new ArrayList<>();
        Map<String, List<ScopedClass>> classesByInternalName = new LinkedHashMap<>();
        Map<MethodRef, Set<MethodRef>> outgoingCalls = new LinkedHashMap<>();
        Map<MethodRef, Set<MethodRef>> incomingCalls = new LinkedHashMap<>();
        Map<FieldRef, Set<MethodRef>> incomingFieldReferences = new LinkedHashMap<>();
        List<ScopedStringHit> strings = new ArrayList<>();
        List<TypeReferenceHit> typeReferences = new ArrayList<>();
        List<String> modules = new ArrayList<>();

        for (Path input : inputs) {
            ArchiveIndex archive = ArchiveIndexCache.get(input);
            archives.add(archive);
            for (IndexedClass indexedClass : archive.classes()) {
                classesByInternalName.computeIfAbsent(indexedClass.internalName(), ignored -> new ArrayList<>())
                        .add(new ScopedClass(archive.path(), archive, indexedClass));
            }
            mergeEdges(outgoingCalls, archive);
            mergeIncomingEdges(incomingCalls, archive);
            mergeFieldIncoming(incomingFieldReferences, archive);
            for (StringHit hit : archive.strings()) {
                strings.add(new ScopedStringHit(archive.path(), archive, hit));
            }
            typeReferences.addAll(archive.typeReferences());
            modules.addAll(archive.modules());
        }

        return new ScopeIndex(
                List.copyOf(archives),
                freezeLists(classesByInternalName),
                freezeSets(outgoingCalls),
                freezeSets(incomingCalls),
                freezeSets(incomingFieldReferences),
                List.copyOf(strings),
                List.copyOf(typeReferences),
                List.copyOf(modules)
        );
    }

    public List<ArchiveIndex> archives() {
        return archives;
    }

    public List<ScopedClass> classes() {
        List<ScopedClass> results = new ArrayList<>();
        classesByInternalName.values().forEach(results::addAll);
        return results;
    }

    public List<ScopedClass> resolveClasses(String internalName) {
        return classesByInternalName.getOrDefault(internalName, List.of());
    }

    public List<ScopedMethod> resolveMethodCandidates(String owner, String methodName, String descriptor) {
        List<ScopedMethod> results = new ArrayList<>();
        for (ScopedClass scopedClass : resolveClasses(owner)) {
            for (IndexedMethod method : scopedClass.indexedClass().methods()) {
                if (!method.ref().name().equals(methodName)) {
                    continue;
                }
                if (descriptor != null && !descriptor.isBlank() && !descriptor.equals(method.ref().descriptor())) {
                    continue;
                }
                results.add(new ScopedMethod(scopedClass.sourcePath(), scopedClass.archiveIndex(), scopedClass.indexedClass(), method));
            }
        }
        return results;
    }

    public List<ScopedField> resolveFieldCandidates(String owner, String fieldName, String descriptor) {
        List<ScopedField> results = new ArrayList<>();
        for (ScopedClass scopedClass : resolveClasses(owner)) {
            for (IndexedField field : scopedClass.indexedClass().fields()) {
                if (!field.name().equals(fieldName)) {
                    continue;
                }
                if (descriptor != null && !descriptor.isBlank() && !descriptor.equals(field.descriptor())) {
                    continue;
                }
                results.add(new ScopedField(scopedClass.sourcePath(), scopedClass.archiveIndex(), scopedClass.indexedClass(), field));
            }
        }
        return results;
    }

    public Set<MethodRef> outgoing(MethodRef ref) {
        return outgoingCalls.getOrDefault(ref, Set.of());
    }

    public Set<MethodRef> incoming(MethodRef ref) {
        return incomingCalls.getOrDefault(ref, Set.of());
    }

    public Set<MethodRef> incoming(FieldRef ref) {
        return incomingFieldReferences.getOrDefault(ref, Set.of());
    }

    public List<ScopedStringHit> strings() {
        return strings;
    }

    public List<TypeReferenceHit> typeReferences() {
        return typeReferences;
    }

    public List<String> modules() {
        return modules;
    }

    public Set<String> allKnownTypes() {
        return classesByInternalName.keySet();
    }

    private static void mergeEdges(Map<MethodRef, Set<MethodRef>> sink, ArchiveIndex archive) {
        for (Map.Entry<MethodRef, Set<MethodRef>> entry : archive.outgoingEntrySet()) {
            sink.computeIfAbsent(entry.getKey(), ignored -> new LinkedHashSet<>()).addAll(entry.getValue());
        }
    }

    private static void mergeIncomingEdges(Map<MethodRef, Set<MethodRef>> sink, ArchiveIndex archive) {
        for (Map.Entry<MethodRef, Set<MethodRef>> entry : archive.incomingEntrySet()) {
            sink.computeIfAbsent(entry.getKey(), ignored -> new LinkedHashSet<>()).addAll(entry.getValue());
        }
    }

    private static void mergeFieldIncoming(Map<FieldRef, Set<MethodRef>> sink, ArchiveIndex archive) {
        for (Map.Entry<FieldRef, Set<MethodRef>> entry : archive.fieldRefEntrySet()) {
            sink.computeIfAbsent(entry.getKey(), ignored -> new LinkedHashSet<>()).addAll(entry.getValue());
        }
    }

    private static Map<String, List<ScopedClass>> freezeLists(Map<String, List<ScopedClass>> source) {
        Map<String, List<ScopedClass>> frozen = new LinkedHashMap<>();
        source.forEach((key, value) -> frozen.put(key, List.copyOf(value)));
        return Map.copyOf(frozen);
    }

    private static <K> Map<K, Set<MethodRef>> freezeSets(Map<K, Set<MethodRef>> source) {
        Map<K, Set<MethodRef>> frozen = new LinkedHashMap<>();
        source.forEach((key, value) -> frozen.put(key, Set.copyOf(value)));
        return Map.copyOf(frozen);
    }
}
