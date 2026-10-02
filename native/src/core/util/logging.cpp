#include "core/util/logging.hpp"

#include <atomic>
#include <iostream>
#include <deque>
#include <mutex>
#include <streambuf>
#include <string>

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

/**
 * A stream kept a line at a time for the Java side to put into the game's log (drainErrors, drainInfo), and copied
 * to the console while logging is on. Errors otherwise only reached a console players never see, and a black
 * world came with no reason in latest.log.
 */
class LineBuffer : public std::streambuf {
  public:
    explicit LineBuffer(std::ostream &echo) : echo_(echo) {}

    std::string drain() {
        std::scoped_lock lock(mutex_);
        std::string all;
        for (auto &line : lines_) {
            all += line;
            all += '\n';
        }
        lines_.clear();
        return all;
    }

  protected:
    int overflow(int c) override {
        if (c == EOF) return c;
        put(static_cast<char>(c));
        return c;
    }

    std::streamsize xsputn(const char *s, std::streamsize count) override {
        for (std::streamsize i = 0; i < count; i++) put(s[i]);
        return count;
    }

  private:
    void put(char c) {
        if (g_enabled.load(std::memory_order_relaxed)) echo_.put(c);
        std::scoped_lock lock(mutex_);
        if (c == '\n') {
            if (lines_.size() >= 256) lines_.pop_front();
            lines_.push_back(std::move(current_));
            current_.clear();
        } else if (current_.size() < 2048) {
            current_ += c;
        }
    }

    std::ostream &echo_;
    std::mutex mutex_;
    std::deque<std::string> lines_;
    std::string current_;
};

LineBuffer &errorBuffer() {
    static LineBuffer *buffer = new LineBuffer(std::cerr);
    return *buffer;
}

std::ostream &errorStream() {
    static std::ostream *stream = new std::ostream(&errorBuffer());
    return *stream;
}

// Diagnostics: kept for the game's log too, but only while logging is on (it is by default).
LineBuffer &infoBuffer() {
    static LineBuffer *buffer = new LineBuffer(std::cout);
    return *buffer;
}

std::ostream &infoStream() {
    static std::ostream *stream = new std::ostream(&infoBuffer());
    return *stream;
}

} // namespace

void setLoggingEnabled(bool enabled) {
    g_enabled.store(enabled, std::memory_order_relaxed);
}

bool loggingEnabled() {
    return g_enabled.load(std::memory_order_relaxed);
}

std::ostream &out() {
    return loggingEnabled() ? infoStream() : nullStream();
}

std::string drainInfo() {
    return infoBuffer().drain();
}

std::ostream &err() {
    return errorStream();
}

std::string drainErrors() {
    return errorBuffer().drain();
}

} // namespace radiante
