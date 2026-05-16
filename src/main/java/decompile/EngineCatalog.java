package decompile;

import com.beust.jcommander.Parameter;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.heliosdecompiler.transformerapi.decompilers.cfr.CFRSettings;
import com.heliosdecompiler.transformerapi.decompilers.fernflower.FernflowerSettings;
import com.heliosdecompiler.transformerapi.decompilers.jadx.MapJadxArgs;
import com.heliosdecompiler.transformerapi.decompilers.jd.JDSettings;
import com.heliosdecompiler.transformerapi.decompilers.procyon.MapDecompilerSettings;
import com.heliosdecompiler.transformerapi.decompilers.vineflower.VineflowerSettings;
import com.strobel.decompiler.CommandLineOptions;
import jadx.api.JadxArgs;
import org.benf.cfr.reader.util.getopt.OptionDecoderParam;
import org.benf.cfr.reader.util.getopt.OptionsImpl;
import org.benf.cfr.reader.util.getopt.PermittedOptionProvider.ArgumentParam;
import org.vineflower.java.decompiler.api.DecompilerOption;
import org.vineflower.java.decompiler.main.Init;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class EngineCatalog {
    private static final Map<String, JsonObject> ENGINES = new LinkedHashMap<>();

    static {
        register(DecompilerEngines.JD_CORE_V1, "Default analytical JD engine");
        register(DecompilerEngines.JD_CORE_V0, "Pattern-matching JD engine, useful as fallback and patch source");
        register(DecompilerEngines.CFR, "Broadly compatible decompiler with stable output");
        register(DecompilerEngines.PROCYON, "Decompiler with readable output and useful line-number options");
        register(DecompilerEngines.FERNFLOWER, "Classic analytical decompiler");
        register(DecompilerEngines.VINEFLOWER, "Most accurate modern Java-focused decompiler, first choice in auto");
        register(DecompilerEngines.JADX, "Decompiler for JVM classes and Android-oriented inputs, last resort in auto");
    }

    private EngineCatalog() {
    }

    public static JsonArray enginesJson() {
        JsonArray array = new JsonArray();
        ENGINES.values().forEach(array::add);
        return array;
    }

    public static JsonObject engineJson(String engine) {
        String normalized = DecompilerEngines.normalizeOrNull(engine);
        return normalized == null ? null : ENGINES.get(normalized);
    }

    public static JsonObject aliasesJson() {
        JsonObject aliases = new JsonObject();
        DecompilerEngines.aliases().forEach(aliases::addProperty);
        return aliases;
    }

    private static void register(String engine, String description) {
        JsonObject json = new JsonObject();
        json.addProperty("engine", engine);
        json.addProperty("description", description);
        json.add("aliases", aliasesFor(engine));
        json.add("defaultPreferences", mapJson(defaultPreferencesFor(engine)));
        json.add("lineNumberPreferences", mapJson(lineNumberPreferencesFor(engine)));
        json.add("options", optionsFor(engine));
        ENGINES.put(engine, json);
    }


    private static JsonArray optionsFor(String engine) {
        OptionSink options = new OptionSink();
        addOption(options, "lineNumbers", "boolean", "High-level MCP flag that selects line-number defaults when supported.");
        addOption(options, "advancedLookup", "boolean", "Search sibling archives for dependencies; JDK modules are included by default.");
        addOption(options, "classpath", "string|array", "Additional archive/class directories to include for dependency lookup.");
        addOption(options, "preferences", "object", "Raw transformer-api preferences passed as key/value strings to the selected engine.");
        addOption(options, "attemptTimeoutMillis", "integer", "Per-engine attempt timeout in milliseconds; 0 disables timeout.");

        switch (engine) {
            case DecompilerEngines.JD_CORE_V1, DecompilerEngines.JD_CORE_V0 -> addJdCoreOptions(options);
            case DecompilerEngines.CFR -> addCfrOptions(options);
            case DecompilerEngines.PROCYON -> addProcyonOptions(options);
            case DecompilerEngines.FERNFLOWER -> addFernflowerOptions(options);
            case DecompilerEngines.VINEFLOWER -> addVineflowerOptions(options);
            case DecompilerEngines.JADX -> addJadxOptions(options);
            default -> {
            }
        }
        return options.array();
    }

    private static void addJdCoreOptions(OptionSink sink) {
        addOption(sink, jd.core.preferences.Preferences.WRITE_LINE_NUMBERS, "boolean", "Write original line numbers.", "false");
        addOption(sink, jd.core.preferences.Preferences.WRITE_METADATA, "boolean", "Write JD-Core metadata.", "false");
        addOption(sink, jd.core.preferences.Preferences.REALIGN_LINE_NUMBERS, "boolean", "Realign source lines to bytecode line numbers.", "false");
        addOption(sink, jd.core.preferences.Preferences.ESCAPE_UNICODE_CHARACTERS, "boolean", "Escape unicode characters.", "false");
        addOption(sink, jd.core.preferences.Preferences.OMIT_THIS_PREFIX, "boolean", "JD-Core v0 option to omit this. where possible.", "false");
        addOption(sink, jd.core.preferences.Preferences.DISPLAY_DEFAULT_CONSTRUCTOR, "boolean", "JD-Core v0 option to display default constructors.", "false");
    }

    private static void addCfrOptions(OptionSink sink) {
        for (Field field : OptionsImpl.class.getFields()) {
            try {
                Object rawArgument = field.get(null);
                if (!(rawArgument instanceof ArgumentParam<?, ?> argument)) {
                    continue;
                }
                Method getFn = findNoArgMethod(field.getType(), "getFn");
                if (getFn == null) {
                    continue;
                }
                getFn.setAccessible(true);
                Object rawDecoder = getFn.invoke(argument);
                if (!(rawDecoder instanceof OptionDecoderParam<?, ?> decoder)) {
                    continue;
                }
                String description = invokeString(field.getType(), argument, "describe");
                String defaultValue = decoder.getDefaultValue();
                String range = decoder.getRangeDescription();
                String type = optionType(defaultValue, range);
                addOption(sink, argument.getName(), type, cleanDescription(description), defaultValue, allowedValues(range));
            } catch (Exception ignored) {
                // Keep the catalog best-effort; unsupported reflection details should not break the tool.
            }
        }
    }

    private static void addProcyonOptions(OptionSink sink) {
        CommandLineOptions defaults = new CommandLineOptions();
        Map<String, Method> getters = findGetters(CommandLineOptions.class);
        Map<String, Field> fields = findProcyonFields();
        Method[] methods = CommandLineOptions.class.getMethods();
        Arrays.sort(methods, java.util.Comparator.comparing(Method::getName));
        for (Method setter : methods) {
            if (!setter.getName().startsWith("set")) {
                continue;
            }
            String key = setter.getName().substring(3);
            Class<?>[] parameterTypes = setter.getParameterTypes();
            String type = null;
            if (Arrays.equals(parameterTypes, new Class<?>[]{boolean.class})) {
                type = "boolean";
            } else if (Arrays.equals(parameterTypes, new Class<?>[]{int.class})) {
                type = "integer";
            }
            Method getter = getters.get(key);
            if (type == null || getter == null) {
                continue;
            }
            try {
                Field field = fields.get("_" + key.substring(0, 1).toLowerCase() + key.substring(1));
                Parameter parameter = field == null ? null : field.getAnnotation(Parameter.class);
                String description = parameter == null ? "Procyon setting " + key + "." : parameter.description();
                addOption(sink, key, type, cleanDescription(description), String.valueOf(getter.invoke(defaults)));
            } catch (Exception ignored) {
                addOption(sink, key, type, "Procyon setting " + key + ".");
            }
        }
    }

    private static void addFernflowerOptions(OptionSink sink) {
        for (Field field : org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences.class.getFields()) {
            try {
                if (!String.class.equals(field.getType())) {
                    continue;
                }
                String key = (String) field.get(null);
                if (!key.matches("\\w{3}")) {
                    continue;
                }
                Object defaultValue = org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences.DEFAULTS.get(key);
                String type = fernflowerType(key, defaultValue);
                addOption(sink, key, type, field.getName(), defaultValue == null ? null : String.valueOf(defaultValue));
            } catch (Exception ignored) {
                // best effort
            }
        }
    }

    private static void addVineflowerOptions(OptionSink sink) {
        try {
            Init.init();
        } catch (Throwable ignored) {
            // Vineflower options are still usually available, but plugins may not be initialized.
        }
        for (DecompilerOption option : DecompilerOption.getAll()) {
            String type = switch (option.type()) {
                case BOOLEAN -> "boolean(1|0)";
                case INTEGER -> "integer";
                case STRING -> "string";
            };
            addOption(sink, option.id(), type, cleanDescription(option.description()), option.defaultValue());
        }
        addOption(sink,
                org.vineflower.java.decompiler.main.extern.IFernflowerPreferences.DUMP_ORIGINAL_LINES,
                "boolean(1|0)",
                "Dump original line numbers in the output.",
                "0");
    }

    private static void addJadxOptions(OptionSink sink) {
        JadxArgs defaults = new JadxArgs();
        Method[] methods = JadxArgs.class.getMethods();
        Arrays.sort(methods, java.util.Comparator.comparing(Method::getName));
        for (Method setter : methods) {
            if (!setter.getName().startsWith("set")) {
                continue;
            }
            String key = setter.getName().substring(3);
            Class<?>[] parameterTypes = setter.getParameterTypes();
            String type = null;
            String getterPrefix = null;
            if (Arrays.equals(parameterTypes, new Class<?>[]{boolean.class})) {
                type = "boolean";
                getterPrefix = "is";
            } else if (Arrays.equals(parameterTypes, new Class<?>[]{int.class})) {
                type = "integer";
                getterPrefix = "get";
            }
            if (type == null) {
                continue;
            }
            try {
                Method getter = JadxArgs.class.getMethod(getterPrefix + key);
                addOption(sink, key, type, "JADX setting " + key + ".", String.valueOf(getter.invoke(defaults)));
            } catch (Exception ignored) {
                addOption(sink, key, type, "JADX setting " + key + ".");
            }
        }
    }

    private static JsonArray aliasesFor(String engine) {
        JsonArray aliases = new JsonArray();
        DecompilerEngines.aliases().entrySet().stream()
                .filter(entry -> engine.equals(entry.getValue()))
                .map(Map.Entry::getKey)
                .forEach(aliases::add);
        return aliases;
    }

    private static Map<String, String> defaultPreferencesFor(String engine) {
        return preferencesFor(engine, false);
    }

    private static Map<String, String> lineNumberPreferencesFor(String engine) {
        return preferencesFor(engine, true);
    }

    private static Map<String, String> preferencesFor(String engine, boolean lineNumbers) {
        return switch (engine) {
            case DecompilerEngines.CFR -> lineNumbers ? CFRSettings.lineNumbers() : CFRSettings.defaults();
            case DecompilerEngines.PROCYON -> lineNumbers ? MapDecompilerSettings.lineNumbers() : MapDecompilerSettings.defaults();
            case DecompilerEngines.FERNFLOWER -> lineNumbers ? FernflowerSettings.lineNumbers() : FernflowerSettings.defaults();
            case DecompilerEngines.VINEFLOWER -> lineNumbers ? VineflowerSettings.lineNumbers() : VineflowerSettings.defaults();
            case DecompilerEngines.JADX -> lineNumbers ? MapJadxArgs.lineNumbers() : MapJadxArgs.defaults();
            default -> Map.of();
        };
    }

    private static JsonObject mapJson(Map<String, String> map) {
        JsonObject json = new JsonObject();
        map.forEach(json::addProperty);
        return json;
    }

    private static Map<String, Method> findGetters(Class<?> type) {
        Map<String, Method> getters = new HashMap<>();
        for (Method method : type.getMethods()) {
            String methodName = method.getName();
            if (method.getParameterCount() == 0 && (methodName.startsWith("get") || methodName.startsWith("is"))) {
                getters.put(methodName.replaceFirst("^(is|get)", ""), method);
            }
        }
        return getters;
    }

    private static Map<String, Field> findProcyonFields() {
        Map<String, Field> fields = new HashMap<>();
        for (Field field : CommandLineOptions.class.getDeclaredFields()) {
            String fieldName = field.getName();
            if (fieldName.startsWith("_is")) {
                fieldName = '_' + fieldName.substring(3, 4).toLowerCase() + fieldName.substring(4);
            }
            fields.put(fieldName, field);
        }
        return fields;
    }

    private static Method findNoArgMethod(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null) {
            try {
                Method method = current.getDeclaredMethod(name);
                if (method.getParameterCount() == 0) {
                    return method;
                }
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private static String invokeString(Class<?> type, Object target, String methodName) {
        try {
            Method method = findNoArgMethod(type, methodName);
            if (method == null) {
                return "";
            }
            method.setAccessible(true);
            Object value = method.invoke(target);
            return value == null ? "" : value.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String optionType(String defaultValue, String range) {
        if (range != null && range.startsWith("One of [") && range.endsWith("]")) {
            return "enum";
        }
        String normalizedRange = range == null ? "" : range.toLowerCase(Locale.ROOT);
        if (normalizedRange.contains("boolean")) {
            return "boolean";
        }
        if (normalizedRange.contains("int")) {
            return "integer";
        }
        String normalizedDefault = defaultValue == null ? "" : defaultValue.toLowerCase(Locale.ROOT).trim();
        if (normalizedDefault.equals("true")
                || normalizedDefault.equals("false")
                || normalizedDefault.startsWith("true ")
                || normalizedDefault.startsWith("false ")) {
            return "boolean";
        }
        if (normalizedDefault.matches("-?\\d+")) {
            return "integer";
        }
        return "string";
    }

    private static JsonArray allowedValues(String range) {
        JsonArray values = new JsonArray();
        if (range != null && range.startsWith("One of [") && range.endsWith("]")) {
            for (String value : range.substring(8, range.length() - 1).split(", ")) {
                values.add(value);
            }
        }
        return values;
    }

    private static String fernflowerType(String key, Object defaultValue) {
        if ("log".equals(key)) {
            return "enum";
        }
        if (!"mpm".equals(key) && ("0".equals(defaultValue) || "1".equals(defaultValue))) {
            return "boolean(1|0)";
        }
        return "string";
    }

    private static String cleanDescription(String description) {
        if (description == null || description.isBlank()) {
            return "";
        }
        return description.replace('\n', ' ').replaceAll("\\s+", " ").trim();
    }

    private static void addOption(OptionSink sink, String name, String type, String description) {
        addOption(sink, name, type, description, null);
    }

    private static void addOption(OptionSink sink, String name, String type, String description, String defaultValue) {
        addOption(sink, name, type, description, defaultValue, new JsonArray());
    }

    private static void addOption(OptionSink sink, String name, String type, String description, String defaultValue, JsonArray allowedValues) {
        JsonObject option = new JsonObject();
        option.addProperty("name", name);
        option.addProperty("type", type);
        if (description != null && !description.isBlank()) {
            option.addProperty("description", description);
        }
        if (defaultValue != null) {
            option.addProperty("defaultValue", defaultValue);
        }
        if (allowedValues != null && !allowedValues.isEmpty()) {
            option.add("allowedValues", allowedValues);
        }
        sink.add(option);
    }

    private static final class OptionSink {
        private final JsonArray array = new JsonArray();
        private final java.util.Set<String> names = new java.util.LinkedHashSet<>();

        void add(JsonObject option) {
            String name = option.get("name").getAsString();
            if (names.add(name)) {
                array.add(option);
            }
        }

        JsonArray array() {
            return array;
        }
    }
}
