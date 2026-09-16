// Minimal DXC interface stubs for non-Windows builds.
#pragma once

#if !defined(_WIN32) && !defined(_WIN)

#include <cstddef>
#include <cstdint>

typedef int32_t HRESULT;
typedef uint32_t ULONG;

struct IUnknown
{
    virtual ~IUnknown() = default;
    virtual HRESULT QueryInterface(const void*, void**) = 0;
    virtual ULONG AddRef() = 0;
    virtual ULONG Release() = 0;
};

struct IDxcBlob : public IUnknown
{
    virtual void* GetBufferPointer() = 0;
    virtual size_t GetBufferSize() = 0;
};

#endif
