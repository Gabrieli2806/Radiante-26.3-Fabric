#include "core/render/renderer.hpp"

#include "core/render/buffers.hpp"
#include "core/render/render_framework.hpp"
#include "core/render/textures.hpp"
#include "core/render/world.hpp"
#include "core/util/logging.hpp"

std::filesystem::path Renderer::folderPath{};
Options Renderer::options{};

Renderer::Renderer(const SharedDeviceHandles &handles)
    : framework_(Framework::create(handles)),
      textures_(Textures::create(framework_)),
      buffers_(Buffers::create(framework_)),
      world_(World::create(framework_)) {}

Renderer::~Renderer() {}

std::shared_ptr<Framework> Renderer::framework() {
    return framework_;
}

std::shared_ptr<Textures> Renderer::textures() {
    return textures_;
}

std::shared_ptr<Buffers> Renderer::buffers() {
    return buffers_;
}

std::shared_ptr<World> Renderer::world() {
    return world_;
}

void Renderer::close() {
    if (framework_ != nullptr) framework_->waitDeviceIdle();

    if (world_ != nullptr) world_->close();
    if (framework_ != nullptr) framework_->close();

    textures_ = nullptr;
    buffers_ = nullptr;
    world_ = nullptr;
    framework_ = nullptr;

#ifdef DEBUG
    radiante::out() << "Renderer closed" << std::endl;
#endif
}