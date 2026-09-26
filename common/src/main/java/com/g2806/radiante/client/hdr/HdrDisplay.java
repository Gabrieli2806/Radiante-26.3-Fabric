package com.g2806.radiante.client.hdr;

import com.g2806.radiante.client.RadianteClient;
import com.g2806.radiante.client.option.Options;
import org.lwjgl.vulkan.VkSurfaceFormatKHR;

/**
 * HDR display output, the Java half: which window format to ask for, and whether the window got it.
 *
 * With the option on, the window's swapchain is created as 16-bit float scRGB (linear, sRGB primaries, 1.0 = 80
 * nits) when the display offers it; Windows then converts it for the monitor, at whatever depth the monitor is
 * driven with (10 or 12 bits). The swapchain is only picked when the window is created, so the option needs a
 * restart. The frame is composed for it at present time by the native HdrOutput (see HdrPresentMixin).
 */
public final class HdrDisplay {

    /** VK_FORMAT_R16G16B16A16_SFLOAT. */
    public static final int FORMAT_RGBA16F = 97;
    /** VK_COLOR_SPACE_EXTENDED_SRGB_LINEAR_EXT (scRGB). */
    public static final int COLOR_SPACE_SCRGB = 1000104002;

    private static volatile boolean active;

    private HdrDisplay() {
    }

    /** Whether the window was created as an HDR (scRGB) swapchain. */
    public static boolean isActive() {
        return active;
    }

    /**
     * The window's surface format when HDR is wanted and offered, or null to let Minecraft pick its usual SDR one.
     * Called whenever Minecraft (re)creates the swapchain.
     */
    public static VkSurfaceFormatKHR pick(VkSurfaceFormatKHR.Buffer formats) {
        if (!Options.hdrOutput) {
            active = false;
            return null;
        }
        for (VkSurfaceFormatKHR format : formats) {
            if (format.format() == FORMAT_RGBA16F && format.colorSpace() == COLOR_SPACE_SCRGB) {
                if (!active) {
                    RadianteClient.LOGGER.info("HDR output on: scRGB swapchain");
                }
                active = true;
                return format;
            }
        }
        if (active || !loggedUnavailable) {
            RadianteClient.LOGGER.warn("HDR output is on, but the display offers no scRGB format; staying SDR. "
                + "Is HDR turned on in the Windows display settings?");
            loggedUnavailable = true;
        }
        active = false;
        return null;
    }

    private static boolean loggedUnavailable;
}
