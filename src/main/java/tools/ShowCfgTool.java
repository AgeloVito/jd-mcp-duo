package tools;

import archive.InputContainer;
import archive.InputContainers;
import model.MCPTool;
import model.ToolResult;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.util.Printer;
import support.AsmSupport;
import support.JsonUtils;
import support.SchemaSupport;
import support.ToolResults;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

public class ShowCfgTool implements MCPTool {
    @Override
    public String getDescription() {
        return "Show a method control-flow graph as mermaid and structured edges.";
    }

    @Override
    public JsonObject getInputSchema() {
        JsonObject schema = SchemaSupport.objectSchema();
        JsonObject properties = SchemaSupport.properties(schema);
        SchemaSupport.addString(properties, "path", "Input path");
        SchemaSupport.addString(properties, "className", "Declaring class name");
        SchemaSupport.addString(properties, "methodName", "Method name");
        SchemaSupport.addString(properties, "descriptor", "Optional JVM descriptor");
        SchemaSupport.addString(properties, "format", "mermaid, plantuml, or both");
        SchemaSupport.addInteger(properties, "releaseVersion", "Target multi-release class version; defaults to the current runtime", Runtime.version().feature());
        SchemaSupport.require(schema, "path");
        SchemaSupport.require(schema, "methodName");
        return schema;
    }

    @Override
    public ToolResult execute(JsonObject arguments) throws Exception {
        Path path = JsonUtils.getRequiredPath(arguments, "path");
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("File not found: " + path);
        }
        String className = JsonUtils.getString(arguments, "className", null);
        String methodName = JsonUtils.getString(arguments, "methodName", "");
        String descriptor = JsonUtils.getString(arguments, "descriptor", null);
        String format = JsonUtils.getString(arguments, "format", "mermaid").toLowerCase();

        Integer releaseVersion = arguments.has("releaseVersion") && !arguments.get("releaseVersion").isJsonNull()
                ? JsonUtils.getInt(arguments, "releaseVersion", Runtime.version().feature())
                : null;
        try (InputContainer container = InputContainers.open(path, releaseVersion)) {
            if (className == null || className.isBlank()) {
                var defaultClass = container.defaultClass();
                if (defaultClass == null) {
                    throw new IllegalArgumentException("className is required when the input contains multiple classes");
                }
                className = defaultClass.internalName();
            }
            ClassNode classNode = AsmSupport.readClassNode(container, className);
            MethodNode method = selectMethod(classNode, methodName, descriptor);
            if (method == null) {
                return ToolResults.error("Method not found: " + className + "#" + methodName);
            }

            JsonObject structured = buildCfg(method);
            structured.addProperty("className", classNode.name.replace('/', '.'));
            structured.addProperty("methodName", method.name);
            structured.addProperty("descriptor", method.desc);
            String text = switch (format) {
                case "plantuml" -> structured.get("plantuml").getAsString();
                case "both" -> structured.get("mermaid").getAsString() + "\n\n" + structured.get("plantuml").getAsString();
                default -> structured.get("mermaid").getAsString();
            };
            return ToolResults.structured(text, structured);
        }
    }

    private static MethodNode selectMethod(ClassNode classNode, String methodName, String descriptor) {
        MethodNode match = null;
        for (MethodNode method : classNode.methods) {
            if (!method.name.equals(methodName)) {
                continue;
            }
            if (descriptor != null && !descriptor.isBlank() && !method.desc.equals(descriptor)) {
                continue;
            }
            if (match != null && (descriptor == null || descriptor.isBlank())) {
                throw new IllegalArgumentException("Multiple overloads found. Please provide descriptor.");
            }
            match = method;
        }
        return match;
    }

    private static JsonObject buildCfg(MethodNode method) {
        List<AbstractInsnNode> instructions = new ArrayList<>();
        Map<LabelNode, Integer> labelIndices = new HashMap<>();
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() >= 0) {
                instructions.add(insn);
            } else if (insn instanceof LabelNode labelNode) {
                labelIndices.put(labelNode, instructions.size());
            }
        }
        if (instructions.isEmpty()) {
            JsonObject empty = new JsonObject();
            empty.addProperty("mermaid", "graph TD\n  Empty[\"empty\"]");
            empty.addProperty("plantuml", "@startuml\nstate Empty\n@enduml");
            empty.add("nodes", new JsonArray());
            empty.add("edges", new JsonArray());
            return empty;
        }

        TreeSet<Integer> leaders = new TreeSet<>();
        leaders.add(0);
        for (int i = 0; i < instructions.size(); i++) {
            AbstractInsnNode insn = instructions.get(i);
            if (insn instanceof JumpInsnNode jump) {
                leaders.add(labelIndices.getOrDefault(jump.label, i));
                if (i + 1 < instructions.size()) {
                    leaders.add(i + 1);
                }
            } else if (insn instanceof LookupSwitchInsnNode lookup) {
                leaders.add(labelIndices.getOrDefault(lookup.dflt, i));
                for (LabelNode label : lookup.labels) {
                    leaders.add(labelIndices.getOrDefault(label, i));
                }
                if (i + 1 < instructions.size()) {
                    leaders.add(i + 1);
                }
            } else if (insn instanceof TableSwitchInsnNode table) {
                leaders.add(labelIndices.getOrDefault(table.dflt, i));
                for (LabelNode label : table.labels) {
                    leaders.add(labelIndices.getOrDefault(label, i));
                }
                if (i + 1 < instructions.size()) {
                    leaders.add(i + 1);
                }
            }
        }

        List<Integer> leaderList = new ArrayList<>(leaders);
        Map<Integer, Integer> blockOfInstruction = new HashMap<>();
        JsonArray nodes = new JsonArray();
        for (int i = 0; i < leaderList.size(); i++) {
            int start = leaderList.get(i);
            int end = (i + 1 < leaderList.size() ? leaderList.get(i + 1) : instructions.size()) - 1;
            JsonObject node = new JsonObject();
            node.addProperty("id", "B" + i);
            node.addProperty("startInstruction", start);
            node.addProperty("endInstruction", end);
            node.addProperty("label", blockLabel(instructions, start, end));
            nodes.add(node);
            for (int insn = start; insn <= end; insn++) {
                blockOfInstruction.put(insn, i);
            }
        }

        Map<String, JsonObject> edges = new LinkedHashMap<>();
        for (int i = 0; i < leaderList.size(); i++) {
            int start = leaderList.get(i);
            int end = (i + 1 < leaderList.size() ? leaderList.get(i + 1) : instructions.size()) - 1;
            AbstractInsnNode tail = instructions.get(end);
            if (tail instanceof JumpInsnNode jump) {
                addEdge(edges, i, blockOfInstruction.getOrDefault(labelIndices.get(jump.label), i), "jump");
                if (tail.getOpcode() != org.objectweb.asm.Opcodes.GOTO && end + 1 < instructions.size()) {
                    addEdge(edges, i, blockOfInstruction.get(end + 1), "fallthrough");
                }
            } else if (tail instanceof LookupSwitchInsnNode lookup) {
                addEdge(edges, i, blockOfInstruction.getOrDefault(labelIndices.get(lookup.dflt), i), "default");
                for (LabelNode label : lookup.labels) {
                    addEdge(edges, i, blockOfInstruction.getOrDefault(labelIndices.get(label), i), "case");
                }
            } else if (tail instanceof TableSwitchInsnNode table) {
                addEdge(edges, i, blockOfInstruction.getOrDefault(labelIndices.get(table.dflt), i), "default");
                for (LabelNode label : table.labels) {
                    addEdge(edges, i, blockOfInstruction.getOrDefault(labelIndices.get(label), i), "case");
                }
            } else if (!isTerminal(tail) && end + 1 < instructions.size()) {
                addEdge(edges, i, blockOfInstruction.get(end + 1), "fallthrough");
            }
        }
        if (method.tryCatchBlocks != null) {
            for (TryCatchBlockNode tryCatchBlock : method.tryCatchBlocks) {
                Integer startBlock = blockOfInstruction.get(labelIndices.getOrDefault(tryCatchBlock.start, -1));
                Integer endBlock = blockOfInstruction.get(labelIndices.getOrDefault(tryCatchBlock.end, -1));
                Integer handlerBlock = blockOfInstruction.get(labelIndices.getOrDefault(tryCatchBlock.handler, -1));
                if (startBlock == null || handlerBlock == null) {
                    continue;
                }
                int protectedEnd = endBlock == null ? nodes.size() - 1 : Math.max(startBlock, endBlock - 1);
                for (int block = startBlock; block <= protectedEnd; block++) {
                    addEdge(edges, block, handlerBlock, tryCatchBlock.type == null ? "finally" : "exception");
                }
            }
        }

        JsonArray edgeArray = new JsonArray();
        StringBuilder mermaid = new StringBuilder("graph TD\n");
        StringBuilder plantuml = new StringBuilder("@startuml\n");
        nodes.forEach(item -> {
            JsonObject node = item.getAsJsonObject();
            mermaid.append("  ").append(node.get("id").getAsString())
                    .append("[\"").append(node.get("label").getAsString().replace("\"", "'")).append("\"]\n");
            plantuml.append("state ").append(node.get("id").getAsString())
                    .append(" as \"").append(node.get("label").getAsString().replace("\"", "'")).append("\"\n");
        });
        edges.values().forEach(edge -> {
            edgeArray.add(edge);
            mermaid.append("  ").append(edge.get("from").getAsString())
                    .append(" -->|").append(edge.get("kind").getAsString()).append("| ")
                    .append(edge.get("to").getAsString()).append('\n');
            plantuml.append(edge.get("from").getAsString())
                    .append(" --> ").append(edge.get("to").getAsString())
                    .append(" : ").append(edge.get("kind").getAsString()).append('\n');
        });
        plantuml.append("@enduml");

        JsonObject structured = new JsonObject();
        structured.addProperty("mermaid", mermaid.toString());
        structured.addProperty("plantuml", plantuml.toString());
        structured.add("nodes", nodes);
        structured.add("edges", edgeArray);
        return structured;
    }

    private static boolean isTerminal(AbstractInsnNode tail) {
        int opcode = tail.getOpcode();
        return opcode == org.objectweb.asm.Opcodes.RETURN
                || opcode == org.objectweb.asm.Opcodes.IRETURN
                || opcode == org.objectweb.asm.Opcodes.LRETURN
                || opcode == org.objectweb.asm.Opcodes.FRETURN
                || opcode == org.objectweb.asm.Opcodes.DRETURN
                || opcode == org.objectweb.asm.Opcodes.ARETURN
                || opcode == org.objectweb.asm.Opcodes.ATHROW;
    }

    private static String blockLabel(List<AbstractInsnNode> instructions, int start, int end) {
        StringBuilder label = new StringBuilder();
        for (int i = start; i <= Math.min(end, start + 2); i++) {
            if (label.length() > 0) {
                label.append("\\n");
            }
            label.append(i).append(": ").append(Printer.OPCODES[instructions.get(i).getOpcode()]);
        }
        return label.toString();
    }

    private static void addEdge(Map<String, JsonObject> edges, int from, int to, String kind) {
        if (to < 0) {
            return;
        }
        String key = from + "->" + to;
        if (edges.containsKey(key)) {
            return;
        }
        JsonObject edge = new JsonObject();
        edge.addProperty("from", "B" + from);
        edge.addProperty("to", "B" + to);
        edge.addProperty("kind", kind);
        edges.put(key, edge);
    }
}
