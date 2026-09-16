#pragma once

#if defined(_WIN32)
#    define VK_USE_PLATFORM_WIN32_KHR
#    ifndef NOMINMAX
#        define NOMINMAX
#    endif
#elif defined(__linux__) || defined(__unix__)
#    define VK_USE_PLATFORM_XLIB_KHR
#elif defined(__APPLE__)
#    define VK_USE_PLATFORM_MACOS_MVK
#else
#endif
#include "volk.h"

#define GLM_FORCE_RADIANS
#define GLM_FORCE_DEPTH_ZERO_TO_ONE
#define GLM_ENABLE_EXPERIMENTAL
#include <glm/gtc/matrix_transform.hpp>
#include <glm/gtx/hash.hpp>
#include <glm/gtx/string_cast.hpp>

#define VMA_STATIC_VULKAN_FUNCTIONS 0
#define VMA_DYNAMIC_VULKAN_FUNCTIONS 0
#include "vk_mem_alloc.h"

#include <memory>
template <typename T, typename... Args>
concept TwoStepInit =
    std::is_default_constructible_v<T> && requires(T t, Args &&...args) { t.init(std::forward<Args>(args)...); };

template <typename Derived>
class SharedObject : public std::enable_shared_from_this<Derived> {
    friend Derived;

  public:
    template <typename... Args>
    static std::shared_ptr<Derived> create(Args &&...args) {
        if constexpr (TwoStepInit<Derived, Args...>) {
            auto ptr = std::make_shared<Derived>();
            ptr->init(std::forward<Args>(args)...);
            return ptr;
        } else {
            return std::make_shared<Derived>(std::forward<Args>(args)...);
        }
    }
};
#define sharedDecl(funName, retName) std::shared_ptr<retName> funName();
#define sharedImpl(namespace, funName, varName, retName)                                                               \
    std::shared_ptr<retName> namespace ::funName() {                                                                   \
        return std::shared_ptr<retName>(shared_from_this(), varName##_);                                               \
    }

// #include <stacktrace>
// #define printStackTrace(os) os << std::stacktrace::current() << std::endl

#include "stb_image.h"
