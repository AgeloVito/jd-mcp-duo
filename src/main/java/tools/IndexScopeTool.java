package tools;

import com.google.gson.JsonObject;
import model.MCPTool;
import model.ToolResult;
import sqlite.PersistentScopeIndex;
import support.JsonUtils;
import support.ProgressReporter;
import support.SchemaSupport;
import support.ToolResults;

import java.nio.file.Path;

/**
 * Build or refresh the SQLite index for a single archive or a directory scope.
 * Separates index construction from query tools so that agent workflows can
 * show indexing progress before running searches.
 */
public class IndexScopeTool implements MCPTool {
    @Override
    public String getDescription() {
        return "Build or refresh the SQLite cross-archive index for the given scope. " +
                "Run this before search_in_jar, call_chain, find_references, etc. to avoid blocking during queries.";
    }

    @Override
    public JsonObject getInputSchema() {
        JsonObject schema = SchemaSupport.objectSchema();
        JsonObject properties = SchemaSupport.properties(schema);
        SchemaSupport.addString(properties, "path", "Primary archive or directory");
        SchemaSupport.addString(properties, "scopePath", "Optional multi-archive scope directory");
        SchemaSupport.addBoolean(properties, "scopeRecursive", "Recursively scan scopePath when it is a directory", false);
        SchemaSupport.addString(properties, "indexPath", "Optional path for the SQLite index file; defaults to ~/.jd-mcp-duo/index.sqlite");
        SchemaSupport.require(schema, "path");
        return schema;
    }

    @Override
    public ToolResult execute(JsonObject arguments) throws Exception {
        return execute(arguments, new ProgressReporter(null, "index_scope"));
    }

    @Override
    public ToolResult execute(JsonObject arguments, ProgressReporter reporter) throws Exception {
        reporter.report(0, 0);
        Path primaryPath = JsonUtils.getRequiredPath(arguments, "path");
        Path scopePath = arguments.has("scopePath") && !JsonUtils.getString(arguments, "scopePath", "").isBlank()
                ? JsonUtils.getPath(arguments, "scopePath")
                : null;
        boolean scopeRecursive = JsonUtils.getBoolean(arguments, "scopeRecursive", false);
        Path indexPath = JsonUtils.getPath(arguments, "indexPath");

        PersistentScopeIndex index = PersistentScopeIndex.open(primaryPath, scopePath, scopeRecursive, indexPath);

        reporter.done();
        return ToolResults.text("Index ready: " + index.scopeArchiveCount() + " archives indexed, "
                + index.indexFailureCount() + " failures. Path: " + index.databasePath());
    }
}
