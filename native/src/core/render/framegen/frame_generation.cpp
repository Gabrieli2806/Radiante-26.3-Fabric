#include "core/render/framegen/frame_generation.hpp"

#include "core/render/framegen/reflex.hpp"

namespace {
// The pipeline images frame generation reads.
int g_depthSlot = -1;
int g_motionVectorSlot = -1;
} // namespace

void framegen::FrameGeneration::setImageSlots(int depthSlot, int motionVectorSlot) {
    g_depthSlot = depthSlot;
    g_motionVectorSlot = motionVectorSlot;
}

int framegen::FrameGeneration::depthSlot() {
    return g_depthSlot;
}

int framegen::FrameGeneration::motionVectorSlot() {
    return g_motionVectorSlot;
}

void framegen::FrameGeneration::beginClientFrame() {
    Reflex::beginFrame();
}

void framegen::FrameGeneration::marker(int marker) {
    Reflex::marker(marker);
}
