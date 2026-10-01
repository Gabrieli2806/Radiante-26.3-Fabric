/* Force-included in every Linux translation unit (see CMakeLists.txt).
 *
 * Building on a recent distribution makes the compiler bind a few libc functions to symbol versions that only exist
 * in the newest glibc (sqrtf@GLIBC_2.43, fmod and strtol* @GLIBC_2.38), so libcore.so refuses to load on older
 * systems with "version `GLIBC_2.xx' not found". The old versions are still provided by every newer glibc, so the
 * references are pinned to them. */
#if defined(__linux__) && defined(__x86_64__) && !defined(RADIANTE_GLIBC_SHIM)
__asm__(".symver sqrtf,sqrtf@GLIBC_2.2.5");
__asm__(".symver fmod,fmod@GLIBC_2.2.5");
__asm__(".symver __isoc23_strtol,strtol@GLIBC_2.2.5");
__asm__(".symver __isoc23_strtoll,strtoll@GLIBC_2.2.5");
__asm__(".symver __isoc23_strtoul,strtoul@GLIBC_2.2.5");
__asm__(".symver __isoc23_strtoull,strtoull@GLIBC_2.2.5");
#endif
/* The static libstdc++ inside libcore.so refers to arc4random (glibc 2.36) and __isoc23_strtoul (glibc 2.38) on its
 * own; src/core/glibc_shims.cpp provides private copies for it. */
