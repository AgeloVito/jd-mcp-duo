package sqlite;

import index.*;
import support.ScopeSupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

// Scoped objects returned by query methods have null archiveIndex because
// the full in-memory index is not available from the SQLite backend.
public final class PersistentScopeIndex {
    private static final String DB_URL_PREFIX = "jdbc:sqlite:";
    private static final int SCHEMA_VERSION = 2;
    private final Path databasePath;
    private final List<Path> scopePaths;
    private final List<IndexFailure> indexFailures;

    private PersistentScopeIndex(Path databasePath, List<Path> scopePaths, List<IndexFailure> indexFailures) {
        this.databasePath = databasePath;
        this.scopePaths = scopePaths;
        this.indexFailures = indexFailures;
    }

    public static PersistentScopeIndex open(Path primaryPath, Path scopePath, boolean recursive) throws Exception {
        List<Path> inputs = ScopeSupport.collectScopeInputs(primaryPath, scopePath, recursive);
        Path databasePath = defaultDatabasePath();
        Files.createDirectories(databasePath.getParent());
        initializeDatabase(databasePath);
        IndexingResult result = ensureIndexed(databasePath, inputs);
        if (result.indexedInputs().isEmpty()) {
            String details = result.failures().stream()
                    .map(failure -> failure.sourcePath() + ": " + failure.message())
                    .reduce((left, right) -> left + "; " + right)
                    .orElse("no supported inputs");
            throw new IOException("No indexable scope inputs: " + details);
        }
        return new PersistentScopeIndex(databasePath, result.indexedInputs(), result.failures());
    }

    public List<ScopedClass> classes() throws Exception {
        return queryClasses(null);
    }

    public int scopeArchiveCount() {
        return scopePaths.size();
    }

    public int indexFailureCount() {
        return indexFailures.size();
    }

    public List<IndexFailure> indexFailures() {
        return indexFailures;
    }

    public Path databasePath() {
        return databasePath;
    }

    public List<ScopedClass> resolveClasses(String internalName) throws Exception {
        return queryClasses(internalName);
    }

    public List<ScopedClass> classHeaders(String internalName) throws Exception {
        String sql = """
                SELECT archive_path, internal_name, display_name, super_name, access_flags, bytecode_version, module_name
                FROM classes
                WHERE archive_path IN (%s) AND internal_name = ?
                ORDER BY archive_path, internal_name
                """.formatted(placeholders(scopePaths.size()));

        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = bindPaths(statement, 1);
            statement.setString(index, internalName);
            try (ResultSet rs = statement.executeQuery()) {
                List<ScopedClass> results = new ArrayList<>();
                while (rs.next()) {
                    Path archivePath = Path.of(rs.getString("archive_path"));
                    String owner = rs.getString("internal_name");
                    results.add(new ScopedClass(
                            archivePath,
                            null,
                            new IndexedClass(
                                    owner,
                                    rs.getString("display_name"),
                                    rs.getString("super_name"),
                                    interfacesFor(connection, archivePath, owner),
                                    rs.getInt("access_flags"),
                                    (Integer) rs.getObject("bytecode_version"),
                                    rs.getString("module_name"),
                                    List.of(),
                                    List.of()
                            )
                    ));
                }
                return results;
            }
        }
    }

    public List<String> directSubtypeInternalNames(String internalName) throws Exception {
        String sql = """
                SELECT DISTINCT classes.internal_name AS internal_name
                FROM classes
                LEFT JOIN interfaces
                  ON interfaces.archive_path = classes.archive_path
                 AND interfaces.owner = classes.internal_name
                WHERE classes.archive_path IN (%s)
                  AND (classes.super_name = ? OR interfaces.interface_name = ?)
                ORDER BY classes.internal_name
                """.formatted(placeholders(scopePaths.size()));

        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = bindPaths(statement, 1);
            statement.setString(index++, internalName);
            statement.setString(index, internalName);
            try (ResultSet rs = statement.executeQuery()) {
                List<String> results = new ArrayList<>();
                while (rs.next()) {
                    results.add(rs.getString("internal_name"));
                }
                return results;
            }
        }
    }

    public List<ScopedClass> directSubtypes(String internalName) throws Exception {
        String sql = """
                SELECT DISTINCT classes.archive_path AS archive_path,
                       classes.internal_name AS internal_name,
                       classes.display_name AS display_name,
                       classes.super_name AS super_name,
                       classes.access_flags AS access_flags,
                       classes.bytecode_version AS bytecode_version,
                       classes.module_name AS module_name
                FROM classes
                LEFT JOIN interfaces
                  ON interfaces.archive_path = classes.archive_path
                 AND interfaces.owner = classes.internal_name
                WHERE classes.archive_path IN (%s)
                  AND (classes.super_name = ? OR interfaces.interface_name = ?)
                ORDER BY classes.archive_path, classes.internal_name
                """.formatted(placeholders(scopePaths.size()));

        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = bindPaths(statement, 1);
            statement.setString(index++, internalName);
            statement.setString(index, internalName);
            try (ResultSet rs = statement.executeQuery()) {
                List<ScopedClass> results = new ArrayList<>();
                while (rs.next()) {
                    Path archivePath = Path.of(rs.getString("archive_path"));
                    String owner = rs.getString("internal_name");
                    results.add(new ScopedClass(
                            archivePath,
                            null,
                            new IndexedClass(
                                    owner,
                                    rs.getString("display_name"),
                                    rs.getString("super_name"),
                                    interfacesFor(connection, archivePath, owner),
                                    rs.getInt("access_flags"),
                                    (Integer) rs.getObject("bytecode_version"),
                                    rs.getString("module_name"),
                                    List.of(),
                                    List.of()
                            )
                    ));
                }
                return results;
            }
        }
    }

    public List<ScopedMethod> resolveMethodCandidates(String owner, String methodName, String descriptor) throws Exception {
        String sql = """
                SELECT methods.archive_path AS archive_path, methods.owner AS owner, methods.name AS name, methods.descriptor AS descriptor, methods.access_flags AS access_flags, classes.display_name AS display_name
                FROM methods
                JOIN classes ON classes.archive_path = methods.archive_path AND classes.internal_name = methods.owner
                WHERE methods.archive_path IN (%s) AND methods.owner = ? AND methods.name = ?
                """.formatted(placeholders(scopePaths.size()));
        if (descriptor != null && !descriptor.isBlank()) {
            sql += " AND descriptor = ?";
        }
        sql += " ORDER BY archive_path";

        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = bindPaths(statement, 1);
            statement.setString(index++, owner);
            statement.setString(index++, methodName);
            if (descriptor != null && !descriptor.isBlank()) {
                statement.setString(index, descriptor);
            }
            try (ResultSet rs = statement.executeQuery()) {
                List<ScopedMethod> results = new ArrayList<>();
                while (rs.next()) {
                    results.add(new ScopedMethod(
                            Path.of(rs.getString("archive_path")),
                            null,
                            new IndexedClass(owner, rs.getString("display_name"), null, List.of(), 0, null, null, List.of(), List.of()),
                            new IndexedMethod(new MethodRef(owner, rs.getString("name"), rs.getString("descriptor")), List.of(), rs.getInt("access_flags"))
                    ));
                }
                return results;
            }
        }
    }

    public List<ScopedField> resolveFieldCandidates(String owner, String fieldName, String descriptor) throws Exception {
        String sql = """
                SELECT fields.archive_path AS archive_path, fields.owner AS owner, fields.name AS name, fields.descriptor AS descriptor, fields.access_flags AS access_flags, classes.display_name AS display_name
                FROM fields
                JOIN classes ON classes.archive_path = fields.archive_path AND classes.internal_name = fields.owner
                WHERE fields.archive_path IN (%s) AND fields.owner = ? AND fields.name = ?
                """.formatted(placeholders(scopePaths.size()));
        if (descriptor != null && !descriptor.isBlank()) {
            sql += " AND descriptor = ?";
        }
        sql += " ORDER BY archive_path";

        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = bindPaths(statement, 1);
            statement.setString(index++, owner);
            statement.setString(index++, fieldName);
            if (descriptor != null && !descriptor.isBlank()) {
                statement.setString(index, descriptor);
            }
            try (ResultSet rs = statement.executeQuery()) {
                List<ScopedField> results = new ArrayList<>();
                while (rs.next()) {
                    results.add(new ScopedField(
                            Path.of(rs.getString("archive_path")),
                            null,
                            new IndexedClass(owner, rs.getString("display_name"), null, List.of(), 0, null, null, List.of(), List.of()),
                            new IndexedField(owner, rs.getString("name"), rs.getString("descriptor"), rs.getInt("access_flags"))
                    ));
                }
                return results;
            }
        }
    }

    public Set<MethodRef> incoming(MethodRef target) throws Exception {
        Set<MethodRef> results = new LinkedHashSet<>();
        for (ScopedMethodRef ref : incomingScoped(target)) {
            results.add(ref.methodRef());
        }
        return results;
    }

    public Set<MethodRef> outgoing(MethodRef source) throws Exception {
        Set<MethodRef> results = new LinkedHashSet<>();
        for (Path scopePath : scopePaths) {
            for (ScopedMethodRef ref : outgoingScoped(scopePath, source)) {
                results.add(ref.methodRef());
            }
        }
        return results;
    }

    public Set<ScopedMethodRef> incomingScoped(MethodRef target) throws Exception {
        String sql = """
                SELECT archive_path, source_owner, source_name, source_desc
                FROM calls
                WHERE archive_path IN (%s) AND target_owner = ? AND target_name = ? AND target_desc = ?
                ORDER BY archive_path, source_owner, source_name, source_desc
                """.formatted(placeholders(scopePaths.size()));
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = bindPaths(statement, 1);
            statement.setString(index++, target.owner());
            statement.setString(index++, target.name());
            statement.setString(index, target.descriptor());
            try (ResultSet rs = statement.executeQuery()) {
                Set<ScopedMethodRef> results = new LinkedHashSet<>();
                while (rs.next()) {
                    results.add(new ScopedMethodRef(
                            Path.of(rs.getString("archive_path")),
                            new MethodRef(rs.getString("source_owner"), rs.getString("source_name"), rs.getString("source_desc"))
                    ));
                }
                results.addAll(expandVirtualCallers(connection, target));
                return results;
            }
        }
    }

    private Set<ScopedMethodRef> expandVirtualCallers(Connection connection, MethodRef target) throws Exception {
        String parentSql = """
                SELECT DISTINCT parent
                FROM (
                    SELECT i.interface_name AS parent
                    FROM interfaces i
                    WHERE i.archive_path IN (%1$s) AND i.owner = ?
                    UNION
                    SELECT c.super_name AS parent
                    FROM classes c
                    WHERE c.archive_path IN (%1$s) AND c.internal_name = ? AND c.super_name IS NOT NULL
                )
                WHERE parent IS NOT NULL
                """.formatted(placeholders(scopePaths.size()));
        Set<String> parentOwners = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(parentSql)) {
            int index = bindPaths(statement, 1);
            statement.setString(index++, target.owner());
            statement.setString(index, target.owner());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    parentOwners.add(rs.getString("parent"));
                }
            }
        }

        if (parentOwners.isEmpty()) {
            return Set.of();
        }

        Set<ScopedMethodRef> results = new LinkedHashSet<>();
        String callerSql = """
                SELECT archive_path, source_owner, source_name, source_desc
                FROM calls
                WHERE archive_path IN (%s) AND target_owner = ? AND target_name = ? AND target_desc = ?
                ORDER BY archive_path, source_owner, source_name, source_desc
                """.formatted(placeholders(scopePaths.size()));
        try (PreparedStatement statement = connection.prepareStatement(callerSql)) {
            for (String parent : parentOwners) {
                int index = bindPaths(statement, 1);
                statement.setString(index++, parent);
                statement.setString(index++, target.name());
                statement.setString(index, target.descriptor());
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        results.add(new ScopedMethodRef(
                                Path.of(rs.getString("archive_path")),
                                new MethodRef(rs.getString("source_owner"), rs.getString("source_name"), rs.getString("source_desc"))
                        ));
                    }
                }
            }
        }
        return results;
    }

    public Set<ScopedMethodRef> outgoingScoped(MethodRef source) throws Exception {
        String sql = """
                SELECT archive_path, target_owner, target_name, target_desc
                FROM calls
                WHERE archive_path IN (%s) AND source_owner = ? AND source_name = ? AND source_desc = ?
                ORDER BY archive_path, target_owner, target_name, target_desc
                """.formatted(placeholders(scopePaths.size()));
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = bindPaths(statement, 1);
            statement.setString(index++, source.owner());
            statement.setString(index++, source.name());
            statement.setString(index, source.descriptor());
            try (ResultSet rs = statement.executeQuery()) {
                Set<ScopedMethodRef> results = new LinkedHashSet<>();
                while (rs.next()) {
                    MethodRef callee = new MethodRef(rs.getString("target_owner"), rs.getString("target_name"), rs.getString("target_desc"));
                    results.add(new ScopedMethodRef(Path.of(rs.getString("archive_path")), callee));
                    results.addAll(expandVirtualTargets(connection, callee));
                }
                return results;
            }
        }
    }

    private Set<ScopedMethodRef> expandVirtualTargets(Connection connection, MethodRef target) throws Exception {
        String sql = """
                SELECT m.archive_path, m.owner, m.name, m.descriptor
                FROM methods m
                WHERE m.archive_path IN (%s)
                  AND m.name = ? AND m.descriptor = ?
                  AND m.owner IN (
                    SELECT DISTINCT c.internal_name
                    FROM classes c
                    LEFT JOIN interfaces i ON i.archive_path = c.archive_path AND i.owner = c.internal_name
                    WHERE c.archive_path IN (%1$s)
                      AND (c.super_name = ? OR i.interface_name = ?)
                  )
                ORDER BY m.archive_path, m.owner
                """.formatted(placeholders(scopePaths.size()));
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = bindPaths(statement, 1);
            statement.setString(index++, target.name());
            statement.setString(index++, target.descriptor());
            statement.setString(index++, target.owner());
            statement.setString(index, target.owner());
            try (ResultSet rs = statement.executeQuery()) {
                Set<ScopedMethodRef> results = new LinkedHashSet<>();
                while (rs.next()) {
                    results.add(new ScopedMethodRef(
                            Path.of(rs.getString("archive_path")),
                            new MethodRef(rs.getString("owner"), rs.getString("name"), rs.getString("descriptor"))
                    ));
                }
                return results;
            }
        }
    }

    public Set<ScopedMethodRef> outgoingScoped(Path sourcePath, MethodRef source) throws Exception {
        String sql = """
                SELECT archive_path, target_owner, target_name, target_desc
                FROM calls
                WHERE archive_path = ? AND source_owner = ? AND source_name = ? AND source_desc = ?
                ORDER BY target_owner, target_name, target_desc
                """;
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sourcePath.toAbsolutePath().normalize().toString());
            statement.setString(2, source.owner());
            statement.setString(3, source.name());
            statement.setString(4, source.descriptor());
            try (ResultSet rs = statement.executeQuery()) {
                Set<ScopedMethodRef> results = new LinkedHashSet<>();
                while (rs.next()) {
                    results.add(new ScopedMethodRef(
                            Path.of(rs.getString("archive_path")),
                            new MethodRef(rs.getString("target_owner"), rs.getString("target_name"), rs.getString("target_desc"))
                    ));
                }
                return results;
            }
        }
    }

    public Set<MethodRef> incoming(FieldRef field) throws Exception {
        Set<MethodRef> results = new LinkedHashSet<>();
        for (ScopedMethodRef ref : incomingScoped(field)) {
            results.add(ref.methodRef());
        }
        return results;
    }

    public Set<ScopedMethodRef> incomingScoped(FieldRef field) throws Exception {
        String sql = """
                SELECT archive_path, source_owner, source_name, source_desc
                FROM field_refs
                WHERE archive_path IN (%s) AND field_owner = ? AND field_name = ? AND field_desc = ?
                ORDER BY archive_path, source_owner, source_name, source_desc
                """.formatted(placeholders(scopePaths.size()));
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = bindPaths(statement, 1);
            statement.setString(index++, field.owner());
            statement.setString(index++, field.name());
            statement.setString(index, field.descriptor());
            try (ResultSet rs = statement.executeQuery()) {
                Set<ScopedMethodRef> results = new LinkedHashSet<>();
                while (rs.next()) {
                    results.add(new ScopedMethodRef(
                            Path.of(rs.getString("archive_path")),
                            new MethodRef(rs.getString("source_owner"), rs.getString("source_name"), rs.getString("source_desc"))
                    ));
                }
                return results;
            }
        }
    }

    public List<ScopedStringHit> strings() throws Exception {
        String sql = """
                SELECT archive_path, owner, method_name, descriptor, text
                FROM strings
                WHERE archive_path IN (%s)
                ORDER BY archive_path
                """.formatted(placeholders(scopePaths.size()));
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bindPaths(statement, 1);
            try (ResultSet rs = statement.executeQuery()) {
                List<ScopedStringHit> results = new ArrayList<>();
                while (rs.next()) {
                    results.add(new ScopedStringHit(
                            Path.of(rs.getString("archive_path")),
                            null,
                            new StringHit(rs.getString("owner"), rs.getString("method_name"), rs.getString("descriptor"), rs.getString("text"))
                    ));
                }
                return results;
            }
        }
    }

    public List<TypeReferenceHit> typeReferences() throws Exception {
        return typeReferences(null);
    }

    public List<TypeReferenceHit> typeReferences(String targetType) throws Exception {
        String sql = """
                SELECT source_owner, source_member_name, source_member_desc, target_type, kind
                FROM type_refs
                WHERE archive_path IN (%s)
                """.formatted(placeholders(scopePaths.size()));
        if (targetType != null && !targetType.isBlank()) {
            sql += " AND target_type = ?";
        }
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = bindPaths(statement, 1);
            if (targetType != null && !targetType.isBlank()) {
                statement.setString(index, targetType);
            }
            try (ResultSet rs = statement.executeQuery()) {
                List<TypeReferenceHit> results = new ArrayList<>();
                while (rs.next()) {
                    results.add(new TypeReferenceHit(
                            rs.getString("source_owner"),
                            rs.getString("source_member_name"),
                            rs.getString("source_member_desc"),
                            rs.getString("target_type"),
                            rs.getString("kind")
                    ));
                }
                return results;
            }
        }
    }

    public List<String> modules() throws Exception {
        String sql = """
                SELECT DISTINCT module_name
                FROM classes
                WHERE archive_path IN (%s) AND module_name IS NOT NULL
                ORDER BY module_name
                """.formatted(placeholders(scopePaths.size()));
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bindPaths(statement, 1);
            try (ResultSet rs = statement.executeQuery()) {
                List<String> results = new ArrayList<>();
                while (rs.next()) {
                    results.add(rs.getString(1));
                }
                return results;
            }
        }
    }

    public List<ScopedResource> resources(List<String> resourceTypes) throws Exception {
        StringBuilder sql = new StringBuilder("""
                SELECT archive_path, entry_path, resource_type, text_content
                FROM resources
                WHERE archive_path IN (%s)
                """.formatted(placeholders(scopePaths.size())));
        List<String> normalizedTypes = resourceTypes == null ? List.of() : resourceTypes.stream()
                .filter(type -> type != null && !type.isBlank())
                .toList();
        if (!normalizedTypes.isEmpty()) {
            sql.append(" AND resource_type IN (").append(placeholders(normalizedTypes.size())).append(')');
        }
        sql.append(" ORDER BY archive_path, entry_path");

        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int index = bindPaths(statement, 1);
            for (String resourceType : normalizedTypes) {
                statement.setString(index++, resourceType);
            }
            try (ResultSet rs = statement.executeQuery()) {
                List<ScopedResource> results = new ArrayList<>();
                while (rs.next()) {
                    results.add(new ScopedResource(
                            Path.of(rs.getString("archive_path")),
                            null,
                            new IndexedResource(
                                    rs.getString("entry_path"),
                                    rs.getString("resource_type"),
                                    rs.getString("text_content")
                            )
                    ));
                }
                return results;
            }
        }
    }

    private List<ScopedClass> queryClasses(String internalName) throws Exception {
        String sql = """
                SELECT archive_path, internal_name, display_name, super_name, access_flags, bytecode_version, module_name
                FROM classes
                WHERE archive_path IN (%s)
                """.formatted(placeholders(scopePaths.size()));
        if (internalName != null) {
            sql += " AND internal_name = ?";
        }
        sql += " ORDER BY archive_path, internal_name";

        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = bindPaths(statement, 1);
            if (internalName != null) {
                statement.setString(index, internalName);
            }
            try (ResultSet rs = statement.executeQuery()) {
                List<ScopedClass> results = new ArrayList<>();
                while (rs.next()) {
                    Path archivePath = Path.of(rs.getString("archive_path"));
                    String owner = rs.getString("internal_name");
                    results.add(new ScopedClass(
                            archivePath,
                            null,
                            new IndexedClass(
                                    owner,
                                    rs.getString("display_name"),
                                    rs.getString("super_name"),
                                    interfacesFor(connection, archivePath, owner),
                                    rs.getInt("access_flags"),
                                    (Integer) rs.getObject("bytecode_version"),
                                    rs.getString("module_name"),
                                    fieldsFor(connection, archivePath, owner),
                                    methodsFor(connection, archivePath, owner)
                            )
                    ));
                }
                return results;
            }
        }
    }

    private List<String> interfacesFor(Connection connection, Path archivePath, String owner) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT interface_name FROM interfaces WHERE archive_path = ? AND owner = ? ORDER BY interface_name")) {
            statement.setString(1, archivePath.toString());
            statement.setString(2, owner);
            try (ResultSet rs = statement.executeQuery()) {
                List<String> interfaces = new ArrayList<>();
                while (rs.next()) {
                    interfaces.add(rs.getString(1));
                }
                return interfaces;
            }
        }
    }

    private List<IndexedField> fieldsFor(Connection connection, Path archivePath, String owner) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT name, descriptor, access_flags FROM fields WHERE archive_path = ? AND owner = ? ORDER BY name, descriptor")) {
            statement.setString(1, archivePath.toString());
            statement.setString(2, owner);
            try (ResultSet rs = statement.executeQuery()) {
                List<IndexedField> fields = new ArrayList<>();
                while (rs.next()) {
                    fields.add(new IndexedField(owner, rs.getString("name"), rs.getString("descriptor"), rs.getInt("access_flags")));
                }
                return fields;
            }
        }
    }

    private List<IndexedMethod> methodsFor(Connection connection, Path archivePath, String owner) throws SQLException {
        Map<String, List<String>> stringsByMethod = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT method_name, descriptor, text FROM strings WHERE archive_path = ? AND owner = ?")) {
            statement.setString(1, archivePath.toString());
            statement.setString(2, owner);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    String key = rs.getString("method_name") + ":" + rs.getString("descriptor");
                    stringsByMethod.computeIfAbsent(key, k -> new ArrayList<>()).add(rs.getString("text"));
                }
            }
        }

        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT name, descriptor, access_flags FROM methods WHERE archive_path = ? AND owner = ? ORDER BY name, descriptor")) {
            statement.setString(1, archivePath.toString());
            statement.setString(2, owner);
            try (ResultSet rs = statement.executeQuery()) {
                List<IndexedMethod> methods = new ArrayList<>();
                while (rs.next()) {
                    String name = rs.getString("name");
                    String descriptor = rs.getString("descriptor");
                    String key = name + ":" + descriptor;
                    List<String> methodStrings = stringsByMethod.getOrDefault(key, List.of());
                    methods.add(new IndexedMethod(new MethodRef(owner, name, descriptor), methodStrings, rs.getInt("access_flags")));
                }
                return methods;
            }
        }
    }

    private Set<MethodRef> queryMethodEdges(String sqlTemplate, String owner, String name, String descriptor) throws Exception {
        String sql = sqlTemplate.formatted(placeholders(scopePaths.size()));
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = bindPaths(statement, 1);
            statement.setString(index++, owner);
            statement.setString(index++, name);
            statement.setString(index, descriptor);
            try (ResultSet rs = statement.executeQuery()) {
                Set<MethodRef> results = new LinkedHashSet<>();
                while (rs.next()) {
                    results.add(new MethodRef(rs.getString(1), rs.getString(2), rs.getString(3)));
                }
                return results;
            }
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(DB_URL_PREFIX + databasePath);
    }

    private static Path defaultDatabasePath() {
        String override = System.getProperty("jd.mcp.sqlite.index");
        if (override != null && !override.isBlank()) {
            return Path.of(override).toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("user.home"), ".jd-mcp-duo", "index.sqlite").toAbsolutePath().normalize();
    }

    private static void initializeDatabase(Path databasePath) throws SQLException {
        try (Connection connection = DriverManager.getConnection(DB_URL_PREFIX + databasePath)) {
            execute(connection, "PRAGMA busy_timeout=5000");
            execute(connection, "PRAGMA journal_mode=WAL");
            execute(connection, "PRAGMA synchronous=NORMAL");
            execute(connection, """
                    CREATE TABLE IF NOT EXISTS archives (
                        archive_path TEXT PRIMARY KEY,
                        fingerprint TEXT NOT NULL,
                        kind TEXT,
                        indexed_at INTEGER
                    )""");
            execute(connection, """
                    CREATE TABLE IF NOT EXISTS classes (
                        archive_path TEXT NOT NULL,
                        internal_name TEXT NOT NULL,
                        display_name TEXT NOT NULL,
                        super_name TEXT,
                        access_flags INTEGER,
                        bytecode_version INTEGER,
                        module_name TEXT,
                        PRIMARY KEY (archive_path, internal_name)
                    )""");
            execute(connection, """
                    CREATE TABLE IF NOT EXISTS interfaces (
                        archive_path TEXT NOT NULL,
                        owner TEXT NOT NULL,
                        interface_name TEXT NOT NULL
                    )""");
            execute(connection, """
                    CREATE TABLE IF NOT EXISTS fields (
                        archive_path TEXT NOT NULL,
                        owner TEXT NOT NULL,
                        name TEXT NOT NULL,
                        descriptor TEXT NOT NULL,
                        access_flags INTEGER
                    )""");
            execute(connection, """
                    CREATE TABLE IF NOT EXISTS methods (
                        archive_path TEXT NOT NULL,
                        owner TEXT NOT NULL,
                        name TEXT NOT NULL,
                        descriptor TEXT NOT NULL,
                        access_flags INTEGER
                    )""");
            execute(connection, """
                    CREATE TABLE IF NOT EXISTS strings (
                        archive_path TEXT NOT NULL,
                        owner TEXT NOT NULL,
                        method_name TEXT,
                        descriptor TEXT,
                        text TEXT NOT NULL
                    )""");
            execute(connection, """
                    CREATE TABLE IF NOT EXISTS calls (
                        archive_path TEXT NOT NULL,
                        source_owner TEXT NOT NULL,
                        source_name TEXT NOT NULL,
                        source_desc TEXT NOT NULL,
                        target_owner TEXT NOT NULL,
                        target_name TEXT NOT NULL,
                        target_desc TEXT NOT NULL
                    )""");
            execute(connection, """
                    CREATE TABLE IF NOT EXISTS field_refs (
                        archive_path TEXT NOT NULL,
                        field_owner TEXT NOT NULL,
                        field_name TEXT NOT NULL,
                        field_desc TEXT NOT NULL,
                        source_owner TEXT NOT NULL,
                        source_name TEXT NOT NULL,
                        source_desc TEXT NOT NULL
                    )""");
            execute(connection, """
                    CREATE TABLE IF NOT EXISTS type_refs (
                        archive_path TEXT NOT NULL,
                        source_owner TEXT NOT NULL,
                        source_member_name TEXT,
                        source_member_desc TEXT,
                        target_type TEXT NOT NULL,
                        kind TEXT NOT NULL
                    )""");
            execute(connection, """
                    CREATE TABLE IF NOT EXISTS resources (
                        archive_path TEXT NOT NULL,
                        entry_path TEXT NOT NULL,
                        resource_type TEXT NOT NULL,
                        text_content TEXT,
                        PRIMARY KEY (archive_path, entry_path)
                    )""");
            execute(connection, "CREATE INDEX IF NOT EXISTS idx_classes_name ON classes(display_name)");
            execute(connection, "CREATE INDEX IF NOT EXISTS idx_classes_internal ON classes(internal_name)");
            execute(connection, "CREATE INDEX IF NOT EXISTS idx_classes_super ON classes(super_name)");
            execute(connection, "CREATE INDEX IF NOT EXISTS idx_classes_archive_internal ON classes(archive_path, internal_name)");
            execute(connection, "CREATE INDEX IF NOT EXISTS idx_classes_archive_super ON classes(archive_path, super_name)");
            execute(connection, "CREATE INDEX IF NOT EXISTS idx_methods_lookup ON methods(owner, name, descriptor)");
            execute(connection, "CREATE INDEX IF NOT EXISTS idx_methods_name ON methods(name)");
            execute(connection, "CREATE INDEX IF NOT EXISTS idx_methods_archive_lookup ON methods(archive_path, owner, name, descriptor)");
            execute(connection, "CREATE INDEX IF NOT EXISTS idx_fields_lookup ON fields(owner, name, descriptor)");
            execute(connection, "CREATE INDEX IF NOT EXISTS idx_fields_archive_lookup ON fields(archive_path, owner, name, descriptor)");
            execute(connection, "CREATE INDEX IF NOT EXISTS idx_strings_text ON strings(text)");
            execute(connection, "CREATE INDEX IF NOT EXISTS idx_calls_target ON calls(target_owner, target_name, target_desc)");
            execute(connection, "CREATE INDEX IF NOT EXISTS idx_calls_source ON calls(source_owner, source_name, source_desc)");
            execute(connection, "CREATE INDEX IF NOT EXISTS idx_calls_archive_target ON calls(archive_path, target_owner, target_name, target_desc)");
            execute(connection, "CREATE INDEX IF NOT EXISTS idx_calls_archive_source ON calls(archive_path, source_owner, source_name, source_desc)");
            execute(connection, "CREATE INDEX IF NOT EXISTS idx_field_refs_target ON field_refs(field_owner, field_name, field_desc)");
            execute(connection, "CREATE INDEX IF NOT EXISTS idx_field_refs_archive_target ON field_refs(archive_path, field_owner, field_name, field_desc)");
            execute(connection, "CREATE INDEX IF NOT EXISTS idx_type_refs_target ON type_refs(target_type, kind)");
            execute(connection, "CREATE INDEX IF NOT EXISTS idx_type_refs_archive_target ON type_refs(archive_path, target_type, kind)");
            execute(connection, "CREATE INDEX IF NOT EXISTS idx_interfaces_target ON interfaces(interface_name)");
            execute(connection, "CREATE INDEX IF NOT EXISTS idx_interfaces_archive_target ON interfaces(archive_path, interface_name, owner)");
            execute(connection, "CREATE INDEX IF NOT EXISTS idx_resources_type ON resources(resource_type)");
            execute(connection, "CREATE INDEX IF NOT EXISTS idx_resources_path ON resources(entry_path)");
            upgradeSchemaIfNeeded(connection);
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static IndexingResult ensureIndexed(Path databasePath, List<Path> inputs) throws Exception {
        List<Path> indexedInputs = new ArrayList<>();
        List<IndexFailure> failures = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(DB_URL_PREFIX + databasePath)) {
            for (Path input : inputs) {
                try {
                    Path normalized = input.toAbsolutePath().normalize();
                    String fingerprint = ArchiveIndexCache.fingerprint(normalized);
                    if (isCurrent(connection, normalized, fingerprint)) {
                        indexedInputs.add(normalized);
                        continue;
                    }

                    ArchiveIndex archive = ArchiveIndexCache.get(normalized);
                    connection.setAutoCommit(false);
                    deleteArchive(connection, archive.path());
                    insertArchive(connection, archive);
                    connection.commit();
                    indexedInputs.add(archive.path());
                } catch (Exception e) {
                    try {
                        connection.rollback();
                    } catch (SQLException rollbackFailure) {
                        e.addSuppressed(rollbackFailure);
                    }
                    failures.add(new IndexFailure(
                            input.toAbsolutePath().normalize(),
                            e.getClass().getSimpleName(),
                            e.getMessage() == null ? e.toString() : e.getMessage()
                    ));
                } finally {
                    try {
                        connection.setAutoCommit(true);
                    } catch (SQLException ignored) {
                        // The next operation will surface connection failures.
                    }
                }
            }
        }
        return new IndexingResult(List.copyOf(indexedInputs), List.copyOf(failures));
    }

    private static boolean isCurrent(Connection connection, Path archivePath, String fingerprint) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT fingerprint FROM archives WHERE archive_path = ?")) {
            statement.setString(1, archivePath.toAbsolutePath().normalize().toString());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() && fingerprint.equals(rs.getString(1));
            }
        }
    }

    private static void deleteArchive(Connection connection, Path archivePath) throws SQLException {
        String archive = archivePath.toString();
        for (String table : List.of("interfaces", "fields", "methods", "strings", "calls", "field_refs", "type_refs", "resources", "classes", "archives")) {
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM " + table + " WHERE archive_path = ?")) {
                statement.setString(1, archive);
                statement.executeUpdate();
            }
        }
    }

    private static void insertArchive(Connection connection, ArchiveIndex archive) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO archives(archive_path, fingerprint, kind, indexed_at) VALUES(?,?,?,?)")) {
            statement.setString(1, archive.path().toString());
            statement.setString(2, archive.fingerprint());
            statement.setString(3, archive.path().getFileName().toString());
            statement.setLong(4, System.currentTimeMillis());
            statement.executeUpdate();
        }

        try (PreparedStatement classStmt = connection.prepareStatement(
                "INSERT INTO classes(archive_path, internal_name, display_name, super_name, access_flags, bytecode_version, module_name) VALUES(?,?,?,?,?,?,?)");
             PreparedStatement ifaceStmt = connection.prepareStatement(
                     "INSERT INTO interfaces(archive_path, owner, interface_name) VALUES(?,?,?)");
             PreparedStatement fieldStmt = connection.prepareStatement(
                     "INSERT INTO fields(archive_path, owner, name, descriptor, access_flags) VALUES(?,?,?,?,?)");
             PreparedStatement methodStmt = connection.prepareStatement(
                     "INSERT INTO methods(archive_path, owner, name, descriptor, access_flags) VALUES(?,?,?,?,?)");
             PreparedStatement stringStmt = connection.prepareStatement(
                     "INSERT INTO strings(archive_path, owner, method_name, descriptor, text) VALUES(?,?,?,?,?)");
             PreparedStatement callStmt = connection.prepareStatement(
                     "INSERT INTO calls(archive_path, source_owner, source_name, source_desc, target_owner, target_name, target_desc) VALUES(?,?,?,?,?,?,?)");
             PreparedStatement fieldRefStmt = connection.prepareStatement(
                     "INSERT INTO field_refs(archive_path, field_owner, field_name, field_desc, source_owner, source_name, source_desc) VALUES(?,?,?,?,?,?,?)");
             PreparedStatement typeRefStmt = connection.prepareStatement(
                     "INSERT INTO type_refs(archive_path, source_owner, source_member_name, source_member_desc, target_type, kind) VALUES(?,?,?,?,?,?)");
             PreparedStatement resourceStmt = connection.prepareStatement(
                     "INSERT INTO resources(archive_path, entry_path, resource_type, text_content) VALUES(?,?,?,?)")) {
            for (IndexedClass indexedClass : archive.classes()) {
                classStmt.setString(1, archive.path().toString());
                classStmt.setString(2, indexedClass.internalName());
                classStmt.setString(3, indexedClass.displayName());
                classStmt.setString(4, indexedClass.superName());
                classStmt.setInt(5, indexedClass.access());
                if (indexedClass.bytecodeVersion() != null) {
                    classStmt.setInt(6, indexedClass.bytecodeVersion());
                } else {
                    classStmt.setNull(6, Types.INTEGER);
                }
                classStmt.setString(7, indexedClass.moduleName());
                classStmt.addBatch();

                for (String iface : indexedClass.interfaces()) {
                    ifaceStmt.setString(1, archive.path().toString());
                    ifaceStmt.setString(2, indexedClass.internalName());
                    ifaceStmt.setString(3, iface);
                    ifaceStmt.addBatch();
                }

                for (IndexedField field : indexedClass.fields()) {
                    fieldStmt.setString(1, archive.path().toString());
                    fieldStmt.setString(2, field.owner());
                    fieldStmt.setString(3, field.name());
                    fieldStmt.setString(4, field.descriptor());
                    fieldStmt.setInt(5, field.access());
                    fieldStmt.addBatch();
                }

                for (IndexedMethod method : indexedClass.methods()) {
                    methodStmt.setString(1, archive.path().toString());
                    methodStmt.setString(2, method.ref().owner());
                    methodStmt.setString(3, method.ref().name());
                    methodStmt.setString(4, method.ref().descriptor());
                    methodStmt.setInt(5, method.access());
                    methodStmt.addBatch();
                }
            }

            for (StringHit hit : archive.strings()) {
                stringStmt.setString(1, archive.path().toString());
                stringStmt.setString(2, hit.owner());
                stringStmt.setString(3, hit.methodName());
                stringStmt.setString(4, hit.descriptor());
                stringStmt.setString(5, hit.text());
                stringStmt.addBatch();
            }

            for (IndexedClass indexedClass : archive.classes()) {
                for (IndexedMethod method : indexedClass.methods()) {
                    for (MethodRef callee : archive.outgoing(method.ref())) {
                        callStmt.setString(1, archive.path().toString());
                        callStmt.setString(2, method.ref().owner());
                        callStmt.setString(3, method.ref().name());
                        callStmt.setString(4, method.ref().descriptor());
                        callStmt.setString(5, callee.owner());
                        callStmt.setString(6, callee.name());
                        callStmt.setString(7, callee.descriptor());
                        callStmt.addBatch();
                    }
                }
                for (IndexedField field : indexedClass.fields()) {
                    for (MethodRef ref : archive.incoming(new FieldRef(field.owner(), field.name(), field.descriptor()))) {
                        fieldRefStmt.setString(1, archive.path().toString());
                        fieldRefStmt.setString(2, field.owner());
                        fieldRefStmt.setString(3, field.name());
                        fieldRefStmt.setString(4, field.descriptor());
                        fieldRefStmt.setString(5, ref.owner());
                        fieldRefStmt.setString(6, ref.name());
                        fieldRefStmt.setString(7, ref.descriptor());
                        fieldRefStmt.addBatch();
                    }
                }
            }

            for (TypeReferenceHit hit : archive.typeReferences()) {
                typeRefStmt.setString(1, archive.path().toString());
                typeRefStmt.setString(2, hit.sourceOwner());
                typeRefStmt.setString(3, hit.sourceMemberName());
                typeRefStmt.setString(4, hit.sourceMemberDescriptor());
                typeRefStmt.setString(5, hit.targetType());
                typeRefStmt.setString(6, hit.kind());
                typeRefStmt.addBatch();
            }

            for (IndexedResource resource : archive.resources()) {
                resourceStmt.setString(1, archive.path().toString());
                resourceStmt.setString(2, resource.entryPath());
                resourceStmt.setString(3, resource.resourceType());
                resourceStmt.setString(4, resource.textContent());
                resourceStmt.addBatch();
            }

            classStmt.executeBatch();
            ifaceStmt.executeBatch();
            fieldStmt.executeBatch();
            methodStmt.executeBatch();
            stringStmt.executeBatch();
            callStmt.executeBatch();
            fieldRefStmt.executeBatch();
            typeRefStmt.executeBatch();
            resourceStmt.executeBatch();
        }
    }

    private static void upgradeSchemaIfNeeded(Connection connection) throws SQLException {
        int currentVersion;
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("PRAGMA user_version")) {
            currentVersion = rs.next() ? rs.getInt(1) : 0;
        }
        if (currentVersion >= SCHEMA_VERSION) {
            return;
        }
        for (String table : List.of("interfaces", "fields", "methods", "strings", "calls", "field_refs", "type_refs", "resources", "classes", "archives")) {
            execute(connection, "DELETE FROM " + table);
        }
        execute(connection, "PRAGMA user_version=" + SCHEMA_VERSION);
    }

    private int bindPaths(PreparedStatement statement, int startIndex) throws SQLException {
        int index = startIndex;
        for (Path scopePath : scopePaths) {
            statement.setString(index++, scopePath.toString());
        }
        return index;
    }

    private static String placeholders(int count) {
        return String.join(",", Collections.nCopies(Math.max(1, count), "?"));
    }

    private record IndexingResult(List<Path> indexedInputs, List<IndexFailure> failures) {
    }
}
