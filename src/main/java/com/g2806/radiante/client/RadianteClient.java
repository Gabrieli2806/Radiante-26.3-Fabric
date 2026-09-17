package com.g2806.radiante.client;

import com.g2806.radiante.client.gui.RadianteOptionsScreen;
import com.g2806.radiante.client.render.RadianteRenderer;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;

import com.g2806.radiante.client.option.Options;
import com.g2806.radiante.client.pipeline.Pipeline;
import com.g2806.radiante.client.proxy.vulkan.RendererProxy;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.stream.Stream;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

public class RadianteClient implements ClientModInitializer {

    public static final Logger LOGGER = LogUtils.getLogger();
    private static final String NATIVE_RESOURCE_ROOT = "/radiante-native";

    public static Path radianceDir;
    private static boolean streamlineLoaded;
    private static boolean nativeLoaded = false;

    /** True when Streamline was loaded before Minecraft created its Vulkan instance. */
    public static boolean streamlineLoaded() {
        return streamlineLoaded;
    }

    public static void setStreamlineLoaded(boolean loaded) {
        streamlineLoaded = loaded;
    }

    @Override
    public void onInitializeClient() {
        ensureNativeLoaded();

        SmokeTest.register();

        KeyMapping openSettings = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.radiante.open_settings",
            InputConstants.KEY_F6, KeyMapping.Category.MISC));
        KeyMapping toggleRayTracing = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key.radiante.toggle_ray_tracing", InputConstants.KEY_F7, KeyMapping.Category.MISC));

        // The warning has to wait for a screen to exist, so it goes up on the first menu after startup.
        ClientTickEvents.END_CLIENT_TICK.register(minecraft -> {
            if (!RadianteRenderer.isActive()
                && minecraft.gui.screen() instanceof net.minecraft.client.gui.screens.TitleScreen title
                && com.g2806.radiante.client.gui.UnsupportedHardwareScreen.shouldShow()) {
                minecraft.gui.setScreen(new com.g2806.radiante.client.gui.UnsupportedHardwareScreen(title));
            }

            while (openSettings.consumeClick()) {
                if (minecraft.gui.screen() == null) {
                    minecraft.gui.setScreen(new RadianteOptionsScreen(null, minecraft.options));
                }
            }

            while (toggleRayTracing.consumeClick()) {
                if (!RadianteRenderer.isActive()) {
                    sendStatus(minecraft, "message.radiante.ray_tracing_unavailable");
                    continue;
                }
                com.g2806.radiante.client.option.Options.rayTracingEnabled =
                    !com.g2806.radiante.client.option.Options.rayTracingEnabled;
                com.g2806.radiante.client.option.Options.overwriteConfig();

                // Each renderer keeps its own copy of the world, and only the one in use is kept up to date, so
                // the one being handed the world back has to rebuild it before it can draw anything.
                if (minecraft.level != null) {
                    minecraft.levelExtractor.allChanged();
                }

                sendStatus(minecraft, com.g2806.radiante.client.option.Options.rayTracingEnabled
                    ? "message.radiante.ray_tracing_on" : "message.radiante.ray_tracing_off");
            }
        });
    }

    /**
     * Extracts and loads the native renderer. Safe to call repeatedly; the Vulkan backend mixins call
     * it before the instance is created in case the client entrypoint has not run yet.
     */
    public static synchronized void ensureNativeLoaded() {
        if (nativeLoaded) {
            return;
        }

        String osName = System.getProperty("os.name").toLowerCase();
        if (!osName.contains("windows")) {
            throw new IllegalStateException("Radiante currently supports Windows only (detected " + osName + ")");
        }

        radianceDir = FabricLoader.getInstance().getGameDir().resolve("radiante");
        try {
            Files.createDirectories(radianceDir);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        copyOptionalFile("libxess.dll");
        copyOptionalFile("libxess_dx11.dll");
        copyOptionalFile("libxess_fg.dll");
        copyFile("core.dll");
        copyFolder("shaders", radianceDir.resolve("shaders"));
        copyFolder(null, radianceDir.resolve("modules"), "/modules");
        copyFolder("streamline", radianceDir.resolve("streamline"));

        Path xess = radianceDir.resolve("libxess.dll");
        if (Files.exists(xess)) {
            System.load(xess.toAbsolutePath().toString());
        }
        System.load(radianceDir.resolve("core.dll").toAbsolutePath().toString());
        nativeLoaded = true;

        RendererProxy.initFolderPath(radianceDir.toAbsolutePath().toString());
        Pipeline.initFolderPath(radianceDir);
        Options.readOptions();
        Pipeline.reloadAllModuleEntries();
        LOGGER.info("Radiante native renderer loaded from {}", radianceDir);
    }

    private static void sendStatus(net.minecraft.client.Minecraft minecraft, String translationKey) {
        if (minecraft.gui != null) {
            minecraft.gui.hud.setOverlayMessage(net.minecraft.network.chat.Component.translatable(translationKey),
                false);
        }
    }

    private static void copyFile(String name) {
        if (!copyOptionalFile(name)) {
            throw new IllegalStateException("Missing bundled native file: " + name);
        }
    }

    private static boolean copyOptionalFile(String name) {
        try (InputStream is = RadianteClient.class.getResourceAsStream(NATIVE_RESOURCE_ROOT + "/" + name)) {
            if (is == null) {
                return false;
            }
            Path target = radianceDir.resolve(name);
            try {
                Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                // A running client keeps the DLL locked; move it aside so this one gets the new build.
                try {
                    Files.deleteIfExists(target);
                    Files.copy(RadianteClient.class.getResourceAsStream(NATIVE_RESOURCE_ROOT + "/" + name), target,
                        StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException retry) {
                    try {
                        Files.move(target, target.resolveSibling(name + "." + System.nanoTime() + ".old"));
                        Files.copy(RadianteClient.class.getResourceAsStream(NATIVE_RESOURCE_ROOT + "/" + name), target,
                            StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException giveUp) {
                        if (!Files.exists(target)) {
                            throw giveUp;
                        }
                        LOGGER.warn("Could not overwrite {}, using existing copy", target);
                    }
                }
            }
            return true;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void copyFolder(String nativeSubFolder, Path target) {
        copyFolder(nativeSubFolder, target, NATIVE_RESOURCE_ROOT + "/" + nativeSubFolder);
    }

    private static void copyFolder(String ignored, Path target, String resourceFolder) {
        URL url = RadianteClient.class.getResource(resourceFolder);
        if (url == null) {
            throw new IllegalStateException("Resource folder not found: " + resourceFolder);
        }

        try {
            URI uri = url.toURI();
            if ("jar".equals(uri.getScheme())) {
                FileSystem fs;
                boolean created = false;
                try {
                    fs = FileSystems.getFileSystem(uri);
                } catch (FileSystemNotFoundException e) {
                    fs = FileSystems.newFileSystem(uri, Collections.emptyMap());
                    created = true;
                }
                try {
                    copyTree(fs.getPath(resourceFolder), target);
                } finally {
                    if (created) {
                        fs.close();
                    }
                }
            } else {
                copyTree(Paths.get(uri), target);
            }
        } catch (URISyntaxException | IOException e) {
            throw new RuntimeException("Failed to copy resource folder " + resourceFolder, e);
        }
    }

    private static void copyTree(Path source, Path target) throws IOException {
        try (Stream<Path> stream = Files.walk(source)) {
            for (Path file : (Iterable<Path>) stream.filter(Files::isRegularFile)::iterator) {
                Path destination = target.resolve(source.relativize(file).toString());
                Files.createDirectories(destination.getParent());
                try {
                    Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    // Another instance of the game may still hold these libraries open. An identical copy is
                    // already in place in that case, so refusing to start over it would be worse than using it.
                    if (!Files.exists(destination)) {
                        throw e;
                    }
                    LOGGER.warn("Keeping the existing {}: it is in use and could not be replaced",
                        destination.getFileName());
                }
            }
        }
    }
}
