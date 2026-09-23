package com.g2806.radiante.client.pipeline;

import com.g2806.radiante.client.pipeline.config.AttributeConfig;
import com.g2806.radiante.client.pipeline.config.ImageConfig;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.network.chat.Component;

public class Module {

    public String name;
    public List<ImageConfig> inputImageConfigs;
    public List<ImageConfig> outputImageConfigs;
    public List<AttributeConfig> attributeConfigs;
    public List<AttributeConfig> staticAttributeConfigs;
    public Map<String, String> dynamicTranslations = new HashMap<>();
    public String dynamicAttributeStoragePath;

    // for GUI
    public double x, y;

    @Override
    public String toString() {
        return "name: " + name
            + ", inputImageConfigs: " + inputImageConfigs
            + ", outputImageConfigs: " + outputImageConfigs
            + ", attributeConfigs: " + attributeConfigs;
    }

    public static List<AttributeConfig> copyAttributeConfigs(List<AttributeConfig> source) {
        List<AttributeConfig> copy = new ArrayList<>();
        if (source == null) {
            return copy;
        }
        for (AttributeConfig attributeConfig : source) {
            if (attributeConfig == null) {
                continue;
            }
            AttributeConfig cloned = new AttributeConfig();
            cloned.type = attributeConfig.type;
            cloned.name = attributeConfig.name;
            cloned.value = attributeConfig.value;
            copy.add(cloned);
        }
        return copy;
    }

    public Component translateText(String key) {
        if (key == null || key.isEmpty()) {
            return Component.empty();
        }
        String translated = dynamicTranslations.get(key);
        return translated != null ? Component.literal(translated) : Component.translatable(key);
    }

    public ImageConfig getInputImageConfig(String name) {
        for (ImageConfig imageConfig : inputImageConfigs) {
            if (imageConfig.name.equals(name)) {
                return imageConfig;
            }
        }
        throw new RuntimeException("No such image config: " + name);
    }

    public ImageConfig getOutputImageConfig(String name) {
        for (ImageConfig imageConfig : outputImageConfigs) {
            if (imageConfig.name.equals(name)) {
                return imageConfig;
            }
        }
        throw new RuntimeException("No such image config: " + name);
    }
}
