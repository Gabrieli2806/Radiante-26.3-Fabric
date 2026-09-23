package com.g2806.radiante.client.constant;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.stream.Collectors;

public class VulkanConstants {

    public enum VkFormat {
        VK_FORMAT_R8_UNORM(9, "R8_UNORM"),
        VK_FORMAT_R8_SRGB(15, "R8_SRGB"),
        VK_FORMAT_R8G8_UNORM(16, "R8G8_UNORM"),
        VK_FORMAT_R8G8_SRGB(22, "R8G8_SRGB"),
        VK_FORMAT_R8G8B8_UNORM(23, "R8G8B8_UNORM"),
        VK_FORMAT_R8G8B8_SRGB(29, "R8G8B8_SRGB"),
        VK_FORMAT_R8G8B8A8_UNORM(37, "R8G8B8A8_UNORM"),
        VK_FORMAT_R8G8B8A8_SRGB(43, "R8G8B8A8_SRGB"),
        VK_FORMAT_R16_SFLOAT(76, "R16_SFLOAT"),
        VK_FORMAT_R16G16_SFLOAT(83, "R16G16_SFLOAT"),
        VK_FORMAT_R16G16B16_SFLOAT(90, "R16G16B16_SFLOAT"),
        VK_FORMAT_R16G16B16A16_SFLOAT(97, "R16G16B16A16_SFLOAT");

        private static final Map<String, Integer>
            BY_NAME =
            Collections.unmodifiableMap(Arrays.stream(values())
                .collect(Collectors.toMap(VkFormat::getName, VkFormat::getValue)));
        private final int value;
        private final String name;

        VkFormat(int value, String name) {
            this.value = value;
            this.name = name;
        }

        public static int getVkFormatByName(String name) {
            if (BY_NAME.containsKey(name)) {
                return BY_NAME.get(name);
            } else {
                throw new IllegalStateException("Unsupported format: " + name);
            }
        }

        public int getValue() {
            return value;
        }

        public String getName() {
            return name;
        }

    }

    public enum VkFilter {
        VK_FILTER_NEAREST(0),
        VK_FILTER_LINEAR(1);

        private final int value;

        VkFilter(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }
    }

    public enum VkSamplerMipmapMode {
        VK_SAMPLER_MIPMAP_MODE_NEAREST(0),
        VK_SAMPLER_MIPMAP_MODE_LINEAR(1);

        private final int value;

        VkSamplerMipmapMode(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }
    }

    public enum VkSamplerAddressMode {
        VK_SAMPLER_ADDRESS_MODE_REPEAT(0),
        VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE(2);

        private final int value;

        VkSamplerAddressMode(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }
    }

}
