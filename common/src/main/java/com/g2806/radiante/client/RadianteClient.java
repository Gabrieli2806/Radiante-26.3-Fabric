package com.g2806.radiante.client;

import com.g2806.radiante.client.gui.RadianteOptionsScreen;
import com.g2806.radiante.client.render.RadianteRenderer;
import com.g2806.radiante.platform.RadiantePlatform;
import net.minecraft.client.Minecraft;

import com.g2806.radiante.client.option.Options;
import com.g2806.radiante.client.pipeline.Pipeline;
import com.g2806.radiante.client.proxy.vulkan.RendererProxy;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.io.UncheckedIOException;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.tukaani.xz.XZInputStream;

/**
 * Radiante's client side, independent of the mod loader. Each loader module calls {@link #init()} from its client
 * entrypoint, registers {@link RadianteKeys}, and calls {@link #onEndClientTick} at the end of every client tick.
 */
public final class RadianteClient {

    public static final Logger LOGGER = LogUtils.getLogger();
    private static final String NATIVE_RESOURCE_ROOT = "/radiante-native";

    public static Path radianceDir;
    private static boolean nativeLoaded = false;

    private RadianteClient() {
    }

    public static void init() {
        ensureNativeLoaded();
        DevAutomation.register();
        com.g2806.radiante.client.gui.RadianteDebugEntries.register();
        LOGGER.info("Radiante running on {}", RadiantePlatform.INSTANCE.loaderName());
    }

    public static void onEndClientTick(Minecraft minecraft) {
        logNativeErrors();

        // The warning has to wait for a screen to exist, so it goes up on the first menu after startup.
        if (!RadianteRenderer.isActive()
            && minecraft.gui.screen() instanceof net.minecraft.client.gui.screens.TitleScreen title
            && com.g2806.radiante.client.gui.UnsupportedHardwareScreen.shouldShow()) {
            minecraft.gui.setScreen(new com.g2806.radiante.client.gui.UnsupportedHardwareScreen(title));
        }
        // After installing or removing an upscaler the game must restart before going on: nothing else is offered.
        var restart = com.g2806.radiante.client.download.UpscalerDownloads.restartRequiredFor();
        if (restart != null && !(minecraft.gui.screen() instanceof com.g2806.radiante.client.gui.UpscalerDownloadScreen screen
            && screen.isRestartScreen())) {
            minecraft.gui.setScreen(new com.g2806.radiante.client.gui.UpscalerDownloadScreen(minecraft.gui.screen(),
                restart, com.g2806.radiante.client.gui.UpscalerDownloadScreen.Mode.RESTART));
            return;
        }
        // DLSS or XeSS are downloaded on request; the first menu offers the one that suits this GPU.
        if (RadianteRenderer.isActive()
            && minecraft.gui.screen() instanceof net.minecraft.client.gui.screens.TitleScreen title) {
            // An upscaler installed before this start: offer to use it, now that it can be.
            var installed = com.g2806.radiante.client.download.UpscalerDownloads.switchOfferOnStartup();
            if (installed != null) {
                String presetKey = com.g2806.radiante.client.gui.UpscalerDownloadScreen.presetOf(installed).key;
                if (com.g2806.radiante.client.pipeline.Pipeline.isPresetAvailable(presetKey) && !java.util.Objects.equals(
                    presetKey, com.g2806.radiante.client.pipeline.Pipeline.INSTANCE.getActivePresetName())) {
                    minecraft.gui.setScreen(new com.g2806.radiante.client.gui.UpscalerDownloadScreen(title, installed,
                        com.g2806.radiante.client.gui.UpscalerDownloadScreen.Mode.SWITCH));
                    return;
                }
                com.g2806.radiante.client.download.UpscalerDownloads.clearSwitchOffer(installed);
            }
            var offer = com.g2806.radiante.client.download.UpscalerDownloads.offerOnStartup(
                RadianteRenderer.isNvidiaGpu(), RadianteRenderer.isIntelGpu());
            if (offer != null) {
                minecraft.gui.setScreen(new com.g2806.radiante.client.gui.UpscalerDownloadScreen(title, offer,
                    com.g2806.radiante.client.gui.UpscalerDownloadScreen.Mode.OFFER));
            }
        }

        while (RadianteKeys.OPEN_SETTINGS.consumeClick()) {
            if (minecraft.gui.screen() == null) {
                minecraft.gui.setScreen(new RadianteOptionsScreen(null, minecraft.options));
            }
        }

        while (RadianteKeys.TOGGLE_RAY_TRACING.consumeClick()) {
            toggleRayTracing(minecraft);
        }

        com.g2806.radiante.client.compat.distanthorizons.DistantHorizonsCompat.tick();
        DevAutomation.tick(minecraft);
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
        boolean windows = osName.contains("windows");
        if (!windows && !osName.contains("linux")) {
            throw new IllegalStateException("Radiante supports Windows and Linux only (detected " + osName + ")");
        }
        if (!windows && !"amd64".equals(System.getProperty("os.arch"))
            && !"x86_64".equals(System.getProperty("os.arch"))) {
            throw new IllegalStateException("Radiante on Linux needs an x86-64 CPU (detected "
                + System.getProperty("os.arch") + ")");
        }

        radianceDir = RadiantePlatform.INSTANCE.gameDir().resolve("radiante");
        try {
            Files.createDirectories(radianceDir);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        long unpackStart = System.nanoTime();
        loadInstalledNatives();
        removeStaleNatives();
        com.g2806.radiante.client.download.UpscalerDownloads.applyPendingDeletes();
        copyFolder("shaders", radianceDir.resolve("shaders"));
        copyFolder(null, radianceDir.resolve("modules"), "/modules");
        if (windows) {
            if (!com.g2806.radiante.client.download.UpscalerDownloads.isRemovedByPlayer(
                com.g2806.radiante.client.download.UpscalerDownloads.Component.XESS)) {
                copyOptionalFile("libxess.dll");
            }
            copyFile("core.dll");
            if (!com.g2806.radiante.client.download.UpscalerDownloads.isRemovedByPlayer(
                com.g2806.radiante.client.download.UpscalerDownloads.Component.DLSS)) {
                copyOptionalFolder(NATIVE_RESOURCE_ROOT + "/dlss", radianceDir.resolve("dlss"));
            }

            Path xess = radianceDir.resolve("libxess.dll");
            if (Files.exists(xess)) {
                System.load(xess.toAbsolutePath().toString());
            }
            System.load(radianceDir.resolve("core.dll").toAbsolutePath().toString());
        } else {
            // Linux: no XeSS or Streamline (both Windows only); the renderer and DLSS come from their own folder.
            copyFile(LINUX_FOLDER + "/libcore.so", radianceDir.resolve("libcore.so"));
            if (!com.g2806.radiante.client.download.UpscalerDownloads.isRemovedByPlayer(
                com.g2806.radiante.client.download.UpscalerDownloads.Component.DLSS)) {
                copyOptionalFolder(NATIVE_RESOURCE_ROOT + "/" + LINUX_FOLDER + "/dlss", radianceDir.resolve("dlss"));
            }
            System.load(radianceDir.resolve("libcore.so").toAbsolutePath().toString());
        }
        saveInstalledNatives();
        if (unpackedNatives > 0) {
            LOGGER.info("Radiante unpacked {} native libraries in {} ms", unpackedNatives,
                (System.nanoTime() - unpackStart) / 1_000_000L);
        }
        nativeLoaded = true;

        RendererProxy.initFolderPath(radianceDir.toAbsolutePath().toString());
        Pipeline.initFolderPath(radianceDir);
        Options.readOptions();
        // On by default; a missing entry in an older config leaves the renderer's copy of the flag unset otherwise.
        Options.setDebugLogging(Options.debugLogging, false);
        Pipeline.reloadAllModuleEntries();
        LOGGER.info("Radiante native renderer loaded from {}", radianceDir);
    }

    /** Ray tracing on or off, as the key binding and the settings screen switch it. */
    public static void toggleRayTracing(Minecraft minecraft) {
        if (!RadianteRenderer.isActive()) {
            sendStatus(minecraft, "message.radiante.ray_tracing_unavailable");
            return;
        }
        Options.rayTracingEnabled = !Options.rayTracingEnabled;
        Options.overwriteConfig();

        // Each renderer keeps its own copy of the world, and only the one in use is kept up to date, so the one
        // being handed the world back has to rebuild it before it can draw anything.
        if (minecraft.level != null) {
            minecraft.levelExtractor.allChanged();
        }

        sendStatus(minecraft, Options.rayTracingEnabled
            ? "message.radiante.ray_tracing_on" : "message.radiante.ray_tracing_off");
    }

    private static String lastNativeError;
    private static int lastNativeErrorRepeats;

    /**
     * The renderer's error lines, into the game log: a player's latest.log is all that comes back with a report, and
     * a renderer that fails every frame would otherwise leave a black world with no reason given. A line repeated
     * frame after frame is written once, with a count when it stops.
     */
    private static void logNativeErrors() {
        if (!nativeLoaded) {
            return;
        }
        String info = RendererProxy.drainNativeInfo();
        if (info != null) {
            for (String line : info.split("\n")) {
                if (!line.isBlank()) {
                    LOGGER.info("[native] {}", line);
                }
            }
        }
        String errors = RendererProxy.drainNativeErrors();
        if (errors == null) {
            return;
        }
        for (String line : errors.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            if (line.equals(lastNativeError)) {
                lastNativeErrorRepeats++;
                continue;
            }
            if (lastNativeErrorRepeats > 0) {
                LOGGER.warn("[native] (previous line repeated {} more times)", lastNativeErrorRepeats);
            }
            lastNativeError = line;
            lastNativeErrorRepeats = 0;
            LOGGER.warn("[native] {}", line);
        }
    }

    private static void sendStatus(net.minecraft.client.Minecraft minecraft, String translationKey) {
        if (minecraft.gui != null) {
            minecraft.gui.hud.setOverlayMessage(net.minecraft.network.chat.Component.translatable(translationKey),
                false);
        }
    }

    /** Libraries older builds shipped and this one no longer does. */
    /** Where the Linux renderer and its DLSS libraries sit inside radiante-native. */
    private static final String LINUX_FOLDER = "linux-x64";

    private static final String[] RETIRED_NATIVES = {"libxess_fg.dll", "libxess_dx11.dll"};

    /**
     * DLLs a running client held locked were moved aside as *.old (see copyOptionalFile), and nothing ever took them
     * away again; they piled up by the dozen. Whatever is no longer locked goes now, with the retired libraries.
     */
    private static void removeStaleNatives() {
        try (var files = Files.list(radianceDir)) {
            files.filter(file -> file.getFileName().toString().endsWith(".old")).forEach(RadianteClient::tryDelete);
        } catch (IOException ignored) {
            // Nothing to tidy, or the folder is not readable; the copies below report real problems.
        }
        for (String name : RETIRED_NATIVES) {
            tryDelete(radianceDir.resolve(name));
        }
    }

    private static void tryDelete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException stillLocked) {
            // A client still running from it; the next start takes it.
        }
    }

    private static void copyFile(String name) {
        copyFile(name, radianceDir.resolve(name));
    }

    private static void copyFile(String name, Path target) {
        if (!copyOptionalFile(name, target)) {
            throw new IllegalStateException("Missing bundled native file: " + name);
        }
    }

    private static boolean copyOptionalFile(String name) {
        return copyOptionalFile(name, radianceDir.resolve(name));
    }

    private static boolean copyOptionalFile(String name, Path target) {
        String resource = NATIVE_RESOURCE_ROOT + "/" + name;
        if (RadianteClient.class.getResource(resource + PACKED_SUFFIX) != null) {
            unpackNative(name, () -> RadianteClient.class.getResourceAsStream(resource + PACKED_SUFFIX), target);
            return true;
        }
        if (RadianteClient.class.getResource(resource) == null) {
            return false;
        }
        writeNative(() -> RadianteClient.class.getResourceAsStream(resource), target);
        return true;
    }

    // ---- packed natives ----

    /** Released jars carry each native library xz-compressed under this suffix (see compressNatives in Gradle). */
    private static final String PACKED_SUFFIX = ".xz";
    /** What each packed library unpacks to, by its path in radiante-native: "sha256  path" per line. */
    private static final String PACKED_MANIFEST = "natives.sha256";
    /** What was unpacked into the game folder before, so unchanged libraries are not unpacked again each start. */
    private static final String INSTALLED_MANIFEST = "natives.installed";

    private static Map<String, String> packedHashes;
    private static final Map<String, String> installedNatives = new TreeMap<>();
    private static int unpackedNatives;

    private static Map<String, String> packedHashes() {
        if (packedHashes == null) {
            packedHashes = new HashMap<>();
            try (InputStream in = RadianteClient.class.getResourceAsStream(
                NATIVE_RESOURCE_ROOT + "/" + PACKED_MANIFEST)) {
                if (in != null) {
                    for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                        int split = line.indexOf("  ");
                        if (split > 0) {
                            packedHashes.put(line.substring(split + 2).trim(), line.substring(0, split).trim());
                        }
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return packedHashes;
    }

    /** "sha256 size path" per line, for the files unpacked into the game folder. */
    private static void loadInstalledNatives() {
        installedNatives.clear();
        unpackedNatives = 0;
        Path file = radianceDir.resolve(INSTALLED_MANIFEST);
        try {
            if (Files.exists(file)) {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String[] parts = line.split(" ", 3);
                    if (parts.length == 3) {
                        installedNatives.put(parts[2], parts[0] + " " + parts[1]);
                    }
                }
            }
        } catch (IOException ignored) {
            // Everything is unpacked again.
        }
    }

    private static void saveInstalledNatives() {
        StringBuilder text = new StringBuilder();
        installedNatives.forEach((path, state) -> text.append(state).append(' ').append(path).append('\n'));
        try {
            Files.writeString(radianceDir.resolve(INSTALLED_MANIFEST), text.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("Could not record the unpacked native libraries; they are unpacked again next start", e);
        }
    }

    /**
     * Unpacks one xz-packed library, unless the same one (by the hash the build recorded) is already in place. The
     * unpacked file is checked against that hash, so a damaged jar or disk never gets loaded.
     */
    private static void unpackNative(String name, Supplier<InputStream> packed, Path target) {
        String expected = packedHashes().get(name);
        String recorded = installedNatives.get(name);
        try {
            if (expected != null && recorded != null && Files.exists(target)
                && recorded.equals(expected + " " + Files.size(target))) {
                return;
            }
            if (unpackedNatives == 0) {
                LOGGER.info("Radiante is unpacking its native libraries (first start after installing or updating)");
            }
            writeNative(() -> unxz(packed.get()), target);
            unpackedNatives++;
            String actual = sha256(target);
            if (expected != null && !expected.equals(actual)) {
                throw new IllegalStateException("Unpacked " + name + " does not match the mod's checksum");
            }
            installedNatives.put(name, actual + " " + Files.size(target));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static InputStream unxz(InputStream in) {
        if (in == null) {
            throw new IllegalStateException("Packed native library missing from the jar");
        }
        try {
            return new XZInputStream(new BufferedInputStream(in, 1 << 16));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1 << 16];
            int n;
            while ((n = in.read(buffer)) > 0) {
                digest.update(buffer, 0, n);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static InputStream open(Path file) {
        try {
            return Files.newInputStream(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Writes a library into the game folder. A running client keeps its DLLs locked, so a locked one is deleted or
     * moved aside (*.old, tidied next start) to make room for this build's.
     */
    private static void writeNative(Supplier<InputStream> source, Path target) {
        try {
            try (InputStream in = source.get()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                try {
                    Files.deleteIfExists(target);
                    try (InputStream in = source.get()) {
                        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException retry) {
                    try {
                        Files.move(target,
                            target.resolveSibling(target.getFileName() + "." + System.nanoTime() + ".old"));
                        try (InputStream in = source.get()) {
                            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                        }
                    } catch (IOException giveUp) {
                        if (!Files.exists(target)) {
                            throw giveUp;
                        }
                        LOGGER.warn("Could not overwrite {}, using existing copy", target);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * DLSS ships in development builds only; released jars leave it to be downloaded (UpscalerDownloads), so its
     * folder may be missing.
     */
    private static void copyOptionalFolder(String resourceFolder, Path target) {
        boolean present = RadianteClient.class.getResource(resourceFolder + "/nvngx_dlss.dll") != null
            || RadianteClient.class.getResource(resourceFolder + "/nvngx_dlss.dll.xz") != null
            || RadianteClient.class.getResource(resourceFolder + "/libnvidia-ngx-dlss.so.310.9.1") != null
            || RadianteClient.class.getResource(resourceFolder + "/libnvidia-ngx-dlss.so.310.9.1.xz") != null;
        if (present) {
            copyFolder(null, target, resourceFolder);
        }
    }

    private static void copyFolder(String nativeSubFolder, Path target) {
        copyFolder(nativeSubFolder, target, NATIVE_RESOURCE_ROOT + "/" + nativeSubFolder);
    }

    /**
     * Copies a folder of the mod's resources. Folders are found through a file known to be there, not looked up
     * directly: NeoForge serves mod resources from its own file system, which resolves files but not directories.
     */
    private static void copyFolder(String ignored, Path target, String resourceFolder) {
        // A Linux-only build ships no core.dll, so its libcore.so (one folder deeper) serves as the anchor instead.
        // In a released jar the libraries are packed (.xz); natives.sha256 sits next to them.
        URL anchor = null;
        int anchorDepth = 0;
        for (String candidate : new String[] {"/core.dll", "/" + PACKED_MANIFEST, "/" + LINUX_FOLDER + "/libcore.so"}) {
            anchor = RadianteClient.class.getResource(NATIVE_RESOURCE_ROOT + candidate);
            if (anchor != null) {
                anchorDepth = candidate.split("/").length;
                break;
            }
        }
        if (anchor == null) {
            throw new IllegalStateException("Missing bundled native renderer (core.dll or " + LINUX_FOLDER
                + "/libcore.so)");
        }

        try {
            URI uri = anchor.toURI();
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
                    copyTree(requireFolder(fs.getPath(resourceFolder), resourceFolder), target, resourceFolder);
                } finally {
                    if (created) {
                        fs.close();
                    }
                }
            } else {
                // The anchor sits in radiante-native (or its Linux folder), below the resource root.
                Path resourceRoot = Paths.get(uri);
                for (int i = 0; i < anchorDepth; i++) {
                    resourceRoot = resourceRoot.getParent();
                }
                copyTree(requireFolder(resourceRoot.resolve(resourceFolder.substring(1)), resourceFolder), target,
                    resourceFolder);
            }
        } catch (URISyntaxException | IOException e) {
            throw new RuntimeException("Failed to copy resource folder " + resourceFolder, e);
        }
    }

    private static Path requireFolder(Path folder, String resourceFolder) {
        if (!Files.isDirectory(folder)) {
            throw new IllegalStateException("Resource folder not found: " + resourceFolder);
        }
        return folder;
    }

    private static void copyTree(Path source, Path target, String resourceFolder) throws IOException {
        // Native libraries inside radiante-native are named by their path there in natives.sha256.
        String nativePrefix = resourceFolder.startsWith(NATIVE_RESOURCE_ROOT + "/")
            ? resourceFolder.substring(NATIVE_RESOURCE_ROOT.length() + 1) + "/" : null;
        try (Stream<Path> stream = Files.walk(source)) {
            for (Path file : (Iterable<Path>) stream.filter(Files::isRegularFile)::iterator) {
                String relative = source.relativize(file).toString().replace('\\', '/');
                if (nativePrefix != null && relative.endsWith(PACKED_SUFFIX)) {
                    String name = relative.substring(0, relative.length() - PACKED_SUFFIX.length());
                    Path destination = target.resolve(name);
                    Files.createDirectories(destination.getParent());
                    unpackNative(nativePrefix + name, () -> open(file), destination);
                    continue;
                }
                Path destination = target.resolve(relative);
                Files.createDirectories(destination.getParent());
                if (Files.exists(destination) && Files.size(destination) == Files.size(file)
                    && destination.getFileName().toString().contains("ngx")) {
                    // The DLSS runtimes are over 100 MB and only change with a new SDK; skip rewriting them each start.
                    continue;
                }
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
