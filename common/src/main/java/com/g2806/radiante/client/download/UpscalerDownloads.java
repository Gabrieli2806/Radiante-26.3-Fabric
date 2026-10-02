package com.g2806.radiante.client.download;

import com.g2806.radiante.client.RadianteClient;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.jspecify.annotations.Nullable;

/**
 * NVIDIA DLSS and Intel XeSS are not in the jar: they are most of its size and only some players can use them. The
 * player downloads them here, from NVIDIA's and Intel's own repositories at a fixed version, each file checked
 * against the hash it was tested with. They take effect on the next start: NGX and the XeSS runtime are set up once,
 * when the Vulkan device is created. FSR is built into the renderer and always there.
 */
public final class UpscalerDownloads {

    private static final String DLSS_BASE = "https://raw.githubusercontent.com/NVIDIA/DLSS/v310.9.1/lib/";
    private static final String XESS_ZIP = "https://github.com/intel/xess/releases/download/v3.0.2/XeSS_SDK_3.0.2.zip";
    /** Choices remembered between starts: declined prompts and files to delete once nothing holds them. */
    private static final String STATE_FILE = "downloads.properties";

    /** One file of a component: where it comes from, where it goes in the radiante folder, and its hash. */
    private record Part(String url, @Nullable String zipEntry, String target, String sha256, long size) {
    }

    public enum Component {
        DLSS("dlss", 115_000_000L),
        XESS("xess", 73_000_000L);

        public final String id;
        /** Roughly what the download weighs, for the prompt. */
        public final long downloadBytes;

        Component(String id, long downloadBytes) {
            this.id = id;
            this.downloadBytes = downloadBytes;
        }

        List<Part> parts() {
            boolean windows = isWindows();
            return switch (this) {
                case DLSS -> windows ? List.of(
                    new Part(DLSS_BASE + "Windows_x86_64/rel/nvngx_dlss.dll", null, "dlss/nvngx_dlss.dll",
                        "3975567b8943c53acce397f2b72380092f84f162d00b0d2c7d08a1025c563983", 58_956_912L),
                    new Part(DLSS_BASE + "Windows_x86_64/rel/nvngx_dlssd.dll", null, "dlss/nvngx_dlssd.dll",
                        "4bc7ea5fcb2f32cf86bc2cb072e8d2860914a0be511cbdcb2fc206c1d9d83b80", 48_343_664L),
                    new Part(DLSS_BASE + "Windows_x86_64/rel/nvngx_dlssg.dll", null, "dlss/nvngx_dlssg.dll",
                        "ff6e90eb78b827927dff5b4ecc6b1c870c2e9bca29ed9f48c7d348cc9e170b82", 7_460_976L))
                    : List.of(
                    new Part(DLSS_BASE + "Linux_x86_64/rel/libnvidia-ngx-dlss.so.310.9.1", null,
                        "dlss/libnvidia-ngx-dlss.so.310.9.1",
                        "7561f16cab74e2b7ccf791bf44bb9cc43abb44bcf78f33c5663520ad4e857e02", 59_481_944L),
                    new Part(DLSS_BASE + "Linux_x86_64/rel/libnvidia-ngx-dlssd.so.310.9.1", null,
                        "dlss/libnvidia-ngx-dlssd.so.310.9.1",
                        "15043b4129a420b09dcd45a0294cb15f135da23054602827f68095f8080b05b0", 48_238_232L),
                    new Part(DLSS_BASE + "Linux_x86_64/rel/libnvidia-ngx-dlssg.so.310.9.1", null,
                        "dlss/libnvidia-ngx-dlssg.so.310.9.1",
                        "aef4c911831b0381a6afa0164b743138e8571822646b6a4226e810a035ad678e", 7_346_176L));
                // XeSS's Vulkan runtime only exists for Windows.
                case XESS -> windows ? List.of(
                    new Part(XESS_ZIP, "bin/libxess.dll", "libxess.dll",
                        "251659dd84a3e84de67c886a4186e01f3eca49b00641906fe38bb6b807e5d5b7", 77_795_704L))
                    : List.of();
            };
        }

        /** Whether it can be used on this system at all. */
        public boolean offeredHere() {
            return !parts().isEmpty();
        }
    }

    /** What a download is doing, for the progress screen. Read from the render thread, written by the worker. */
    public static final class Progress {
        public volatile long done;
        public volatile long total;
        public volatile boolean finished;
        public volatile @Nullable String error;

        public float fraction() {
            long t = this.total;
            return t <= 0 ? 0.0f : Math.min(1.0f, (float) this.done / t);
        }
    }

    private static final Properties state = new Properties();
    private static boolean stateLoaded;
    private static @Nullable Progress running;
    private static @Nullable Component runningComponent;

    private UpscalerDownloads() {
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("windows");
    }

    private static Path dir() {
        return RadianteClient.radianceDir;
    }

    /** True when every file of the component is in place at its expected size. */
    public static boolean isInstalled(Component component) {
        List<Part> parts = component.parts();
        if (parts.isEmpty() || isPendingDelete(component)) {
            return false;
        }
        for (Part part : parts) {
            Path file = dir().resolve(part.target());
            try {
                if (!Files.exists(file) || Files.size(file) != part.size()) {
                    return false;
                }
            } catch (IOException e) {
                return false;
            }
        }
        return true;
    }

    private static boolean offered;

    /** Set once something was installed or removed this session: the game has to be restarted before it is used. */
    private static volatile @Nullable Component restartFor;

    public static @Nullable Component restartRequiredFor() {
        return restartFor;
    }

    /**
     * True when the player deleted it: a development build ships DLSS and XeSS in its resources and would otherwise
     * put them straight back on the next start.
     */
    public static boolean isRemovedByPlayer(Component component) {
        return Boolean.parseBoolean(state().getProperty(component.id + ".removed", "false"));
    }

    /**
     * The upscaler to offer on the first menu of this session, if any: DLSS on NVIDIA GPUs, XeSS on Intel ones,
     * unless it is there already or the player said not to ask. Other GPUs have FSR, which is built in.
     */
    public static @Nullable Component offerOnStartup(boolean nvidia, boolean intel) {
        if (offered) {
            return null;
        }
        offered = true;
        Component wanted = nvidia ? Component.DLSS : intel ? Component.XESS : null;
        if (wanted == null || !wanted.offeredHere() || isInstalled(wanted) || isDeclined(wanted)) {
            return null;
        }
        return wanted;
    }

    // ---- remembered choices ----

    private static synchronized Properties state() {
        if (!stateLoaded) {
            stateLoaded = true;
            Path file = dir().resolve(STATE_FILE);
            if (Files.exists(file)) {
                try (InputStream in = Files.newInputStream(file)) {
                    state.load(in);
                } catch (IOException ignored) {
                    // Defaults: nothing declined, nothing to delete.
                }
            }
        }
        return state;
    }

    private static synchronized void saveState() {
        try (OutputStream out = Files.newOutputStream(dir().resolve(STATE_FILE))) {
            state().store(out, "Radiante upscaler downloads");
        } catch (IOException e) {
            RadianteClient.LOGGER.warn("Could not save the upscaler download choices", e);
        }
    }

    public static boolean isDeclined(Component component) {
        return Boolean.parseBoolean(state().getProperty(component.id + ".declined", "false"));
    }

    public static void setDeclined(Component component, boolean declined) {
        state().setProperty(component.id + ".declined", Boolean.toString(declined));
        saveState();
    }

    private static boolean isPendingDelete(Component component) {
        return Boolean.parseBoolean(state().getProperty(component.id + ".delete", "false"));
    }

    // ---- deleting ----

    /**
     * Removes a component. The running game may hold its libraries open (always on Windows once they are loaded),
     * so what cannot go now is deleted at the next start, before anything loads it.
     */
    public static void delete(Component component) {
        boolean all = true;
        for (Part part : component.parts()) {
            try {
                Files.deleteIfExists(dir().resolve(part.target()));
            } catch (IOException inUse) {
                all = false;
            }
        }
        state().setProperty(component.id + ".delete", Boolean.toString(!all));
        state().setProperty(component.id + ".removed", "true");
        restartFor = component;
        state().setProperty(component.id + ".declined", "true");
        saveState();
    }

    /** At start, before the libraries are loaded: finishes deletions the last session could not. */
    public static void applyPendingDeletes() {
        boolean changed = false;
        for (Component component : Component.values()) {
            if (!isPendingDelete(component)) {
                continue;
            }
            for (Part part : component.parts()) {
                try {
                    Files.deleteIfExists(dir().resolve(part.target()));
                } catch (IOException ignored) {
                    // Another client still running from it; next start.
                }
            }
            state().setProperty(component.id + ".delete", "false");
            changed = true;
        }
        if (changed) {
            saveState();
        }
    }

    // ---- downloading ----

    public static synchronized @Nullable Progress running(Component component) {
        return component == runningComponent ? running : null;
    }

    /** Starts downloading in the background; returns the progress to show, or the one already running. */
    public static synchronized Progress start(Component component) {
        if (running != null && !running.finished) {
            return running;
        }
        Progress progress = new Progress();
        long total = 0;
        for (Part part : component.parts()) {
            total += part.size();
        }
        progress.total = total;
        running = progress;
        runningComponent = component;
        state().setProperty(component.id + ".delete", "false");
        state().setProperty(component.id + ".removed", "false");
        saveState();

        Thread worker = new Thread(() -> {
            try {
                download(component, progress);
            } catch (Exception e) {
                RadianteClient.LOGGER.warn("Downloading {} failed", component.id, e);
                progress.error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            } finally {
                progress.finished = true;
            }
        }, "Radiante upscaler download");
        worker.setDaemon(true);
        worker.start();
        return progress;
    }

    private static void download(Component component, Progress progress) throws Exception {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20)).build();
        long before = 0;
        for (Part part : component.parts()) {
            Path target = dir().resolve(part.target());
            Files.createDirectories(target.getParent());
            Path temp = target.resolveSibling(target.getFileName() + ".download");
            HttpResponse<InputStream> response = client.send(
                HttpRequest.newBuilder(URI.create(part.url())).timeout(Duration.ofMinutes(10)).build(),
                HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                response.body().close();
                throw new IOException("HTTP " + response.statusCode() + " for " + part.url());
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream body = response.body(); OutputStream out = Files.newOutputStream(temp)) {
                InputStream in = body;
                ZipInputStream zip = null;
                if (part.zipEntry() != null) {
                    zip = new ZipInputStream(body);
                    ZipEntry entry;
                    while ((entry = zip.getNextEntry()) != null && !entry.getName().equals(part.zipEntry())) {
                        // Skip to the one file wanted from the SDK archive.
                    }
                    if (entry == null) {
                        throw new IOException(part.zipEntry() + " not found in " + part.url());
                    }
                    in = zip;
                }
                byte[] buffer = new byte[1 << 16];
                long written = 0;
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                    digest.update(buffer, 0, n);
                    written += n;
                    progress.done = before + Math.min(written, part.size());
                }
            }
            String actual = HexFormat.of().formatHex(digest.digest());
            if (!actual.equals(part.sha256())) {
                Files.deleteIfExists(temp);
                throw new IOException("checksum mismatch for " + part.target());
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            before += part.size();
            progress.done = before;
        }
        RadianteClient.LOGGER.info("Downloaded {}; it takes effect after restarting the game", component.id);
        restartFor = component;
    }
}
