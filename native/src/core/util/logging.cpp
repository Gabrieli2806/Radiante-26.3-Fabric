#include "core/util/logging.hpp"

#include <atomic>
#include <iostream>
#include <streambuf>

namespace radiante {
namespace {

/**
 * A stream buffer that throws its characters away. Writing to a silenced stream still costs the formatting, but
 * that is a handful of strings per pipeline build, not per frame, so it is not worth guarding every call site.
 */
class NullBuffer : public std::streambuf {
  protected:
    int overflow(int c) override {
        return c;
    }

    std::streamsize xsputn(const char *, std::streamsize count) override {
        return count;
    }
};

NullBuffer &nullBuffer() {
    static NullBuffer buffer;
    return buffer;
}

std::ostream &nullStream() {
    static std::ostream stream(&nullBuffer());
    return stream;
}

std::atomic<bool> g_enabled{false};

} // namespace

void setLoggingEnabled(bool enabled) {
    g_enabled.store(enabled, std::memory_order_relaxed);
}

bool loggingEnabled() {
    return g_enabled.load(std::memory_order_relaxed);
}

std::ostream &out() {
    return loggingEnabled() ? std::cout : nullStream();
}

std::ostream &err() {
    return loggingEnabled() ? std::cerr : nullStream();
}

} // namespace radiante
