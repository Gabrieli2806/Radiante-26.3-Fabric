package com.g2806.radiante.client.option;

import com.g2806.radiante.client.pipeline.Pipeline;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * The look of the game as one line of text, to hand to someone else: the shader pack's settings, tone mapping, the
 * night sky and the lighting options. What depends on the machine is left out - the upscaler and its mode, frame
 * generation, HDR, render distance, chunk building - so a code from a strong GPU does not slow down a weak one.
 *
 * <p>A code is text from a stranger. Importing one only ever sets settings that exist here, to values their own type
 * allows; anything else in it is skipped.
 */
public final class SettingsCode {

    private static final String PREFIX = "RADIANTE1:";
    /** Longer than any real code by far; keeps a pasted novel from being unpacked. */
    private static final int MAX_CODE_LENGTH = 32 * 1024;
    private static final int MAX_UNPACKED_LENGTH = 256 * 1024;

    /** The modules a code carries, by the short name used inside it. */
    private static final Map<String, String> MODULES = Map.of(
        "rt", Pipeline.RAY_TRACING_MODULE_NAME,
        "tm", Pipeline.TONE_MAPPING_MODULE_NAME,
        "pr", Pipeline.POST_RENDER_MODULE_NAME);

    /** Options a code carries: on/off ones (null) and numbers with the range their sliders allow. */
    private static final Map<String, int[]> OPTIONS = new LinkedHashMap<>();

    static {
        for (String key : new String[] {"biomeFog", "firstPersonShadow", "heldItemLight", "rainWetness", "pixelLighting",
            "vanillaSunPath", "vanillaCelestialOrientation", "blockLightSampling"}) {
            OPTIONS.put(key, null);
        }
        for (String key : new String[] {"volumetricFogStrength", "biomeFogStrength", "dayBrightness", "nightBrightness",
            "emissionBrightness", "heldLightBrightness"}) {
            OPTIONS.put(key, new int[] {0, 400});
        }
        OPTIONS.put("entityLightReach", new int[] {8, 256});
    }

    private static final Pattern RANGE = Pattern.compile("(-?[0-9.]+)-(-?[0-9.]+)");

    private SettingsCode() {
    }

    /** Settings read from a code and checked, not applied yet. */
    public static final class Parsed {

        private final Map<String, String> options = new LinkedHashMap<>();
        private final Map<String, Map<String, String>> modules = new LinkedHashMap<>();
        private int skipped;

        /** How many settings the code sets here. */
        public int count() {
            int count = this.options.size();
            for (Map<String, String> values : this.modules.values()) {
                count += values.size();
            }
            return count;
        }

        /** Settings in the code this version does not have or would not accept. */
        public int skipped() {
            return this.skipped;
        }
    }

    // ---- export ----

    /** The applied settings as a code. */
    public static String export() {
        JsonObject root = new JsonObject();
        JsonObject options = new JsonObject();
        Options.exportValues(OPTIONS.keySet()).forEach(options::addProperty);
        root.add("o", options);
        for (Map.Entry<String, String> module : MODULES.entrySet()) {
            String prefix = attributePrefix(module.getValue());
            JsonObject values = new JsonObject();
            for (Map.Entry<String, String> attribute : Pipeline.moduleValues(module.getValue()).entrySet()) {
                String name = attribute.getKey();
                String value = attribute.getValue();
                // Only what an import would take back: file paths and other free text stay out of a code, and the
                // renderer's debug views are nobody's look.
                String type = Pipeline.getModuleAttributeType(module.getValue(), name);
                if (value == null || !name.startsWith(prefix) || type == null || !allowed(type, value)
                    || name.contains("debug")) {
                    continue;
                }
                // Enum values repeat their attribute's name; "~" stands for it.
                values.addProperty(name.substring(prefix.length()),
                    value.startsWith(name + ".") ? "~" + value.substring(name.length() + 1) : value);
            }
            root.add(module.getKey(), values);
        }
        byte[] text = root.toString().getBytes(StandardCharsets.UTF_8);
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION, true);
        deflater.setInput(text);
        deflater.finish();
        ByteArrayOutputStream packed = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        while (!deflater.finished()) {
            packed.write(buffer, 0, deflater.deflate(buffer));
        }
        deflater.end();
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(packed.toByteArray());
    }

    // ---- import ----

    /** Reads and checks a code; null when the text is not one. */
    public static Parsed parse(String code) {
        if (code == null) {
            return null;
        }
        String trimmed = code.trim();
        if (!trimmed.startsWith(PREFIX) || trimmed.length() > MAX_CODE_LENGTH) {
            return null;
        }
        try {
            byte[] packed = Base64.getUrlDecoder().decode(trimmed.substring(PREFIX.length()));
            Inflater inflater = new Inflater(true);
            inflater.setInput(packed);
            ByteArrayOutputStream text = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            while (!inflater.finished()) {
                int n = inflater.inflate(buffer);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                text.write(buffer, 0, n);
                if (text.size() > MAX_UNPACKED_LENGTH) {
                    inflater.end();
                    return null;
                }
            }
            inflater.end();
            JsonObject root = JsonParser.parseString(text.toString(StandardCharsets.UTF_8)).getAsJsonObject();
            Parsed parsed = new Parsed();
            readOptions(root, parsed);
            for (Map.Entry<String, String> module : MODULES.entrySet()) {
                readModule(root, module.getKey(), module.getValue(), parsed);
            }
            return parsed.count() == 0 ? null : parsed;
        } catch (RuntimeException | java.util.zip.DataFormatException notACode) {
            return null;
        }
    }

    private static void readOptions(JsonObject root, Parsed parsed) {
        if (!root.has("o") || !root.get("o").isJsonObject()) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("o").entrySet()) {
            String value = text(entry.getValue());
            if (value == null || !OPTIONS.containsKey(entry.getKey())) {
                parsed.skipped++;
                continue;
            }
            int[] range = OPTIONS.get(entry.getKey());
            if (range == null) {
                if (!value.equals("true") && !value.equals("false")) {
                    parsed.skipped++;
                    continue;
                }
            } else {
                try {
                    int number = Integer.parseInt(value);
                    value = String.valueOf(Math.max(range[0], Math.min(range[1], number)));
                } catch (NumberFormatException e) {
                    parsed.skipped++;
                    continue;
                }
            }
            parsed.options.put(entry.getKey(), value);
        }
    }

    private static void readModule(JsonObject root, String shortName, String moduleName, Parsed parsed) {
        if (!root.has(shortName) || !root.get(shortName).isJsonObject()) {
            return;
        }
        String prefix = attributePrefix(moduleName);
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject(shortName).entrySet()) {
            String name = prefix + entry.getKey();
            String value = text(entry.getValue());
            String type = Pipeline.getModuleAttributeType(moduleName, name);
            if (value != null && value.startsWith("~")) {
                value = name + "." + value.substring(1);
            }
            if (value == null || type == null || !allowed(type, value) || name.contains("debug")) {
                parsed.skipped++;
                continue;
            }
            values.put(name, value);
        }
        if (!values.isEmpty()) {
            parsed.modules.put(moduleName, values);
        }
    }

    private static String text(JsonElement element) {
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

    /** "render_pipeline.module.x.name" names the module whose attributes start "render_pipeline.module.x.attribute.". */
    private static String attributePrefix(String moduleName) {
        return moduleName.substring(0, moduleName.length() - "name".length()) + "attribute.";
    }

    /** True when {@code value} is one the attribute's declared type allows. */
    static boolean allowed(String type, String value) {
        if (value.length() > 256) {
            return false;
        }
        try {
            if (type.equals("bool")) {
                return value.equals("render_pipeline.true") || value.equals("render_pipeline.false");
            }
            if (type.startsWith("int_range:") || type.startsWith("float_range:")) {
                Matcher range = RANGE.matcher(type.substring(type.indexOf(':') + 1));
                if (!range.matches()) {
                    return false;
                }
                double number = type.startsWith("int_range:") ? Integer.parseInt(value) : Double.parseDouble(value);
                return Double.isFinite(number) && number >= Double.parseDouble(range.group(1))
                    && number <= Double.parseDouble(range.group(2));
            }
            if (type.startsWith("enum:")) {
                for (String option : type.substring(5).split("-")) {
                    if (option.equals(value)) {
                        return true;
                    }
                }
                return false;
            }
            if (type.equals("vec3")) {
                String[] parts = value.split(",");
                if (parts.length != 3) {
                    return false;
                }
                for (String part : parts) {
                    double number = Double.parseDouble(part.trim());
                    if (!Double.isFinite(number) || Math.abs(number) > 1.0e6) {
                        return false;
                    }
                }
                return true;
            }
        } catch (NumberFormatException e) {
            return false;
        }
        return false;
    }

    /** Applies and saves checked settings; true when the pipeline has to be rebuilt. */
    public static boolean apply(Parsed parsed) {
        parsed.options.forEach(Options::importValue);
        Options.overwriteConfig();
        boolean rebuild = false;
        for (Map.Entry<String, Map<String, String>> module : parsed.modules.entrySet()) {
            for (Map.Entry<String, String> attribute : module.getValue().entrySet()) {
                rebuild |= Pipeline.setModuleValue(module.getKey(), attribute.getKey(), attribute.getValue());
            }
        }
        if (rebuild) {
            Pipeline.savePipeline();
        }
        return rebuild;
    }
}
