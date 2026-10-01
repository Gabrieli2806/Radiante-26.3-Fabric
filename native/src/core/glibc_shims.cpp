// Private stand-ins for libc functions that the statically linked libstdc++ binds to glibc 2.36/2.38 symbol versions
// when built on a recent distribution. Defined here they resolve inside libcore.so (nothing but the JNI entry points
// is exported, see exports.map), so the library loads on older glibc. No libc headers on purpose: with them the calls
// below would bind to the very same new symbols again.
#if defined(__linux__)
extern "C" {

long getrandom(void *buffer, unsigned long length, unsigned int flags);
unsigned long radiante_strtoul(const char *text, char **end, int base) __asm__("strtoul");

unsigned int arc4random(void) {
    unsigned int value = 0;
    char *out = reinterpret_cast<char *>(&value);
    unsigned long left = sizeof(value);
    while (left > 0) {
        long got = getrandom(out, left, 0);
        if (got <= 0) { continue; }
        out += got;
        left -= static_cast<unsigned long>(got);
    }
    return value;
}

unsigned long __isoc23_strtoul(const char *text, char **end, int base) {
    return radiante_strtoul(text, end, base);
}

}
#endif
