#pragma once

#include "core/all_extern.hpp"

namespace framegen {

// What the frame generation code shares with the rest of the renderer: the pipeline images it reads, and the
// Reflex frame and latency markers (Reflex, VK_NV_low_latency2).
class FrameGeneration {
  public:
    // Pipeline image slots for the depth and motion vectors of the frame that is about to be presented. The mod
    // resolves them from the pipeline graph, so upscaled variants are used when an upscaler is active.
    static void setImageSlots(int depthSlot, int motionVectorSlot);

    // Opens a new Reflex frame; call once per client frame before anything else.
    static void beginClientFrame();
    // Latency markers Reflex needs while frame generation runs (VkLatencyMarkerNV values).
    static void marker(int marker);

    static int depthSlot();
    static int motionVectorSlot();

};

} // namespace framegen
