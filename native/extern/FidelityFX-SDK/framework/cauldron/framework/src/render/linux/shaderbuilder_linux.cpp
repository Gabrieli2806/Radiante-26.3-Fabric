// This file is part of the FidelityFX SDK.
//
// Copyright (C) 2024 Advanced Micro Devices, Inc.
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files(the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and /or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions :
//
// The above copyright notice and this permission notice shall be included in
// all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
// THE SOFTWARE.

#if defined(__linux__)

#include "render/shaderbuilder.h"

#include "misc/assert.h"
#include "misc/fileio.h"
#include "misc/helpers.h"
#include "misc/log.h"
#include "render/dxc_stub.h"
#include "render/vk/pipelinedesc_vk.h"

#include <atomic>
#include <cstdlib>
#include <cstdio>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>
#include <unistd.h>
#include <cstdlib>

namespace cauldron
{
    namespace
    {
        bool NeedsShellQuoting(const std::string& arg)
        {
            for (char c : arg)
            {
                if (isspace(static_cast<unsigned char>(c)) || strchr("()[]{};|&<>$`\\\"'", c) != nullptr)
                    return true;
            }
            return false;
        }

        std::string ShellQuote(const std::string& arg)
        {
            if (!NeedsShellQuoting(arg))
                return arg;

            std::string out = "'";
            for (char c : arg)
            {
                if (c == '\'')
                    out += "'\\''";
                else
                    out += c;
            }
            out += "'";
            return out;
        }

        class LinuxDxcBlob final : public IDxcBlob
        {
        public:
            explicit LinuxDxcBlob(std::vector<uint8_t>&& data)
                : m_Data(std::move(data))
            {
            }

            virtual HRESULT QueryInterface(const void*, void**) override { return 0; }
            virtual ULONG AddRef() override { return ++m_RefCount; }
            virtual ULONG Release() override
            {
                ULONG count = --m_RefCount;
                if (count == 0)
                    delete this;
                return count;
            }

            virtual void* GetBufferPointer() override { return m_Data.data(); }
            virtual size_t GetBufferSize() override { return m_Data.size(); }

        private:
            std::vector<uint8_t> m_Data;
            std::atomic<ULONG> m_RefCount{1};
        };

        const char* StageToGlslcStage(ShaderStage stage)
        {
            switch (stage)
            {
            case ShaderStage::Vertex: return "vert";
            case ShaderStage::Pixel: return "frag";
            case ShaderStage::Hull: return "tesc";
            case ShaderStage::Domain: return "tese";
            case ShaderStage::Geometry: return "geom";
            case ShaderStage::Compute: return "comp";
            default: return "comp";
            }
        }

        std::string BuildDxcProfile(const ShaderBuildDesc& shaderDesc)
        {
            std::string profile;
            switch (shaderDesc.Stage)
            {
            case ShaderStage::Vertex: profile = "vs_"; break;
            case ShaderStage::Pixel: profile = "ps_"; break;
            case ShaderStage::Domain: profile = "ds_"; break;
            case ShaderStage::Hull: profile = "hs_"; break;
            case ShaderStage::Geometry: profile = "gs_"; break;
            case ShaderStage::Compute:
            default: profile = "cs_"; break;
            }

            switch (shaderDesc.Model)
            {
            case ShaderModel::SM5_1: profile += "5_1"; break;
            case ShaderModel::SM6_0: profile += "6_0"; break;
            case ShaderModel::SM6_1: profile += "6_1"; break;
            case ShaderModel::SM6_2: profile += "6_2"; break;
            case ShaderModel::SM6_3: profile += "6_3"; break;
            case ShaderModel::SM6_4: profile += "6_4"; break;
            case ShaderModel::SM6_5: profile += "6_5"; break;
            case ShaderModel::SM6_6: profile += "6_6"; break;
            case ShaderModel::SM6_7: profile += "6_7"; break;
            default: profile += "6_0"; break;
            }

            return profile;
        }

        bool IsEnvEnabled(const char* name)
        {
            const char* value = std::getenv(name);
            if (!value || value[0] == '\0')
                return false;
            return value[0] != '0';
        }

        bool RunCommand(const std::string& cmd, std::string& output)
        {
            FILE* pipe = popen(cmd.c_str(), "r");
            if (!pipe)
                return false;

            char buffer[256];
            while (fgets(buffer, sizeof(buffer), pipe) != nullptr)
                output += buffer;

            int status = pclose(pipe);
            return status == 0;
        }
    } // namespace

    int InitShaderCompileSystem()
    {
        return 0;
    }

    void TerminateShaderCompileSystem()
    {
    }

    void* CompileShaderToByteCode(const ShaderBuildDesc& shaderDesc, std::vector<const wchar_t*>* pAdditionalParameters)
    {
        if (!shaderDesc.ShaderCode || !shaderDesc.EntryPoint)
            return nullptr;

        std::string shaderPath;
        bool shaderFile = false;
        const uint64_t stringLength = wcslen(shaderDesc.ShaderCode);
        if (stringLength >= 5)
        {
            const wchar_t* ext = shaderDesc.ShaderCode + stringLength - 5;
            if ((ext[0] == L'.' || ext[0] == L'.') &&
                (ext[1] == L'h' || ext[1] == L'H') &&
                (ext[2] == L'l' || ext[2] == L'L') &&
                (ext[3] == L's' || ext[3] == L'S') &&
                (ext[4] == L'l' || ext[4] == L'L'))
            {
                shaderFile = true;
            }
        }

        std::string tempFilePath;
        if (shaderFile)
        {
            shaderPath = "shaders/";
            shaderPath += WStringToString(shaderDesc.ShaderCode);
        }
        else
        {
            tempFilePath = "/tmp/cauldron_shader_XXXXXX.hlsl";
            std::vector<char> tmp(tempFilePath.begin(), tempFilePath.end());
            tmp.push_back('\0');
            int fd = mkstemps(tmp.data(), 5);
            if (fd == -1)
                return nullptr;
            tempFilePath.assign(tmp.data());
            std::ofstream out(tempFilePath, std::ios::binary);
            out << WStringToString(shaderDesc.ShaderCode);
            out.close();
            shaderPath = tempFilePath;
        }

        std::string outputFile = "/tmp/cauldron_shader_XXXXXX.spv";
        std::vector<char> outTmp(outputFile.begin(), outputFile.end());
        outTmp.push_back('\0');
        int outFd = mkstemps(outTmp.data(), 4);
        if (outFd == -1)
            return nullptr;
        outputFile.assign(outTmp.data());
        close(outFd);

        const bool useDxc = IsEnvEnabled("CAULDRON_USE_DXC");
        const char* stage = StageToGlslcStage(shaderDesc.Stage);
        std::vector<std::string> args;
        if (useDxc)
        {
            args = {
                "dxc",
                "-E", WStringToString(shaderDesc.EntryPoint),
                "-T", BuildDxcProfile(shaderDesc),
                "-HV", "2018",
                "-fspv-extension=SPV_KHR_ray_query",
                "-fspv-extension=SPV_EXT_descriptor_indexing",
                "-Fo", outputFile,
                "-I", "shaders",
                "-DFFX_DXC=1",
            };
            if (shaderDesc.Model >= ShaderModel::SM6_2)
                args.emplace_back("-enable-16bit-types");
        }
        else
        {
            args = {
                "glslc",
                "-x", "hlsl",
                std::string("-fshader-stage=") + stage,
                std::string("-fentry-point=") + WStringToString(shaderDesc.EntryPoint),
                "--target-env=vulkan1.2",
                "-fhlsl-iomap",
                "-fhlsl-offsets",
                "-fhlsl-16bit-types",
                "-DFFX_GLSLC=1",
                "-fcbuffer-binding-base", stage, std::to_string(CONSTANT_BUFFER_BINDING_SHIFT),
                "-fsampler-binding-base", stage, std::to_string(SAMPLER_BINDING_SHIFT),
                "-ftexture-binding-base", stage, std::to_string(TEXTURE_BINDING_SHIFT),
                "-fuav-binding-base", stage, std::to_string(UNORDERED_ACCESS_VIEW_BINDING_SHIFT),
                "-o", outputFile,
                "-I", "shaders",
            };
        }

        for (auto& defineIt : shaderDesc.Defines)
        {
            std::string defineArg = "-D" + WStringToString(defineIt.first) + "=" + WStringToString(defineIt.second);
            args.emplace_back(std::move(defineArg));
        }

        if (useDxc && pAdditionalParameters)
        {
            for (const wchar_t* param : *pAdditionalParameters)
            {
                args.emplace_back(WStringToString(param));
            }
        }

        args.emplace_back(shaderPath);

        std::ostringstream cmd;
        for (const auto& arg : args)
        {
            cmd << ShellQuote(arg) << " ";
        }
        cmd << "2>&1";

        std::string output;
        bool ok = RunCommand(cmd.str(), output);
        if (!output.empty())
            Log::Write(LOGLEVEL_TRACE, StringToWString(output).c_str());

        if (!ok)
        {
            CauldronWarning(L"Shader compilation failed for %ls", shaderDesc.ShaderCode);
            return nullptr;
        }

        std::wstring outputFileW = StringToWString(outputFile);
        int64_t fileSize = GetFileSize(outputFileW.c_str());
        if (fileSize <= 0)
            return nullptr;

        std::vector<uint8_t> data;
        data.resize(static_cast<size_t>(fileSize));
        int64_t sizeRead = ReadFileAll(outputFileW.c_str(), data.data(), fileSize);
        if (sizeRead != fileSize)
            return nullptr;

        if (!tempFilePath.empty())
            std::remove(tempFilePath.c_str());
        std::remove(outputFile.c_str());

        return new LinuxDxcBlob(std::move(data));
    }

} // namespace cauldron

#endif // defined(__linux__)
