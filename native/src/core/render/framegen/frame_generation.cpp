#include "core/render/framegen/frame_generation.hpp"

#include "core/render/framegen/streamline.hpp"

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

#ifdef MCVR_ENABLE_STREAMLINE

#include <sl.h>
#include <sl_consts.h>
#include <sl_core_api.h>
#include <sl_pcl.h>
#include <sl_reflex.h>

#include "core/util/logging.hpp"

namespace {

sl::FrameToken *g_frame = nullptr;

struct SlApi {
    PFun_slGetNewFrameToken *getNewFrameToken = nullptr;
    PFun_slPCLSetMarker *setMarker = nullptr;
    PFun_slReflexSetOptions *setReflexOptions = nullptr;
    PFun_slReflexSleep *reflexSleep = nullptr;
    bool resolved = false;
    bool usable = false;
};

SlApi g_sl;

bool resolveApi() {
    if (g_sl.resolved) return g_sl.usable;
    g_sl.resolved = true;

    g_sl.getNewFrameToken =
        reinterpret_cast<PFun_slGetNewFrameToken *>(framegen::Streamline::procAddress("slGetNewFrameToken"));
    g_sl.setMarker =
        reinterpret_cast<PFun_slPCLSetMarker *>(framegen::Streamline::featureFunction(sl::kFeaturePCL, "slPCLSetMarker"));
    g_sl.setReflexOptions = reinterpret_cast<PFun_slReflexSetOptions *>(
        framegen::Streamline::featureFunction(sl::kFeatureReflex, "slReflexSetOptions"));
    g_sl.reflexSleep = reinterpret_cast<PFun_slReflexSleep *>(
        framegen::Streamline::featureFunction(sl::kFeatureReflex, "slReflexSleep"));

    g_sl.usable = g_sl.getNewFrameToken != nullptr && g_sl.setReflexOptions != nullptr;
    return g_sl.usable;
}

} // namespace

void framegen::FrameGeneration::beginClientFrame() {
    resolveApi();
    bool reflex = Streamline::reflexEnabled() && g_sl.usable;

    // Streamline keeps the last mode it was given, so turning Reflex off has to be sent once as well.
    static int lastMode = -1;
    int mode = reflex ? static_cast<int>(sl::ReflexMode::eLowLatency) : static_cast<int>(sl::ReflexMode::eOff);
    if (mode != lastMode && g_sl.setReflexOptions != nullptr) {
        sl::ReflexOptions options{};
        options.mode = static_cast<sl::ReflexMode>(mode);
        if (g_sl.setReflexOptions(options) == sl::Result::eOk) {
            lastMode = mode;
            radiante::out() << "[Streamline] Reflex " << (mode == 0 ? "off" : "low latency") << std::endl;
        }
    }

    if (!reflex) {
        g_frame = nullptr;
        return;
    }

    sl::FrameToken *frame = nullptr;
    if (g_sl.getNewFrameToken(frame, nullptr) != sl::Result::eOk) {
        g_frame = nullptr;
        return;
    }
    g_frame = frame;

    // The sleep is what lowers latency: it holds the CPU back just long enough that input is sampled as late as
    // the GPU allows, instead of queueing frames ahead of it.
    if (g_sl.reflexSleep != nullptr) {
        g_sl.reflexSleep(*frame);
    }
}

void framegen::FrameGeneration::marker(int marker) {
    if (g_frame == nullptr || g_sl.setMarker == nullptr) return;
    g_sl.setMarker(static_cast<sl::PCLMarker>(marker), *g_frame);
}

#else

void framegen::FrameGeneration::beginClientFrame() {}

void framegen::FrameGeneration::marker(int) {}

#endif
