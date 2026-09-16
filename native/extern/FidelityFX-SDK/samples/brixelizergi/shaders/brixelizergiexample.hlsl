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

#define FFX_GPU 1
#define FFX_HLSL 1
#include "brixelizergiexampletypes.h"

ConstantBuffer<BrixelizerExampleConstants> g_example_consts  : register(b0);

#define FFX_WAVE 1
#define FFX_BRIXELIZER_TRAVERSAL_EPS (g_example_consts.SolveEpsilon / 8.0f)
#include "../../sdk/include/FidelityFX/gpu/brixelizer/ffx_brixelizer_trace_ops.h"

ConstantBuffer<FfxBrixelizerContextInfo>   g_bx_context_info        : register(b1);

SamplerState                       g_sdf_atlas_sampler      : register(s0);

StructuredBuffer<FfxUInt32>        r_bctx_bricks_aabb       : register(t0);
Texture3D<FfxFloat32>              r_sdf_atlas              : register(t1);
StructuredBuffer<FfxUInt32>        r_cascade_aabbtree_0  : register(t2);
StructuredBuffer<FfxUInt32>        r_cascade_aabbtree_1  : register(t3);
StructuredBuffer<FfxUInt32>        r_cascade_aabbtree_2  : register(t4);
StructuredBuffer<FfxUInt32>        r_cascade_aabbtree_3  : register(t5);
StructuredBuffer<FfxUInt32>        r_cascade_aabbtree_4  : register(t6);
StructuredBuffer<FfxUInt32>        r_cascade_aabbtree_5  : register(t7);
StructuredBuffer<FfxUInt32>        r_cascade_aabbtree_6  : register(t8);
StructuredBuffer<FfxUInt32>        r_cascade_aabbtree_7  : register(t9);
StructuredBuffer<FfxUInt32>        r_cascade_aabbtree_8  : register(t10);
StructuredBuffer<FfxUInt32>        r_cascade_aabbtree_9  : register(t11);
StructuredBuffer<FfxUInt32>        r_cascade_aabbtree_10 : register(t12);
StructuredBuffer<FfxUInt32>        r_cascade_aabbtree_11 : register(t13);
StructuredBuffer<FfxUInt32>        r_cascade_aabbtree_12 : register(t14);
StructuredBuffer<FfxUInt32>        r_cascade_aabbtree_13 : register(t15);
StructuredBuffer<FfxUInt32>        r_cascade_aabbtree_14 : register(t16);
StructuredBuffer<FfxUInt32>        r_cascade_aabbtree_15 : register(t17);
StructuredBuffer<FfxUInt32>        r_cascade_aabbtree_16 : register(t18);
StructuredBuffer<FfxUInt32>        r_cascade_aabbtree_17 : register(t19);
StructuredBuffer<FfxUInt32>        r_cascade_aabbtree_18 : register(t20);
StructuredBuffer<FfxUInt32>        r_cascade_aabbtree_19 : register(t21);
StructuredBuffer<FfxUInt32>        r_cascade_aabbtree_20 : register(t22);
StructuredBuffer<FfxUInt32>        r_cascade_aabbtree_21 : register(t23);
StructuredBuffer<FfxUInt32>        r_cascade_aabbtree_22 : register(t24);
StructuredBuffer<FfxUInt32>        r_cascade_aabbtree_23 : register(t25);

StructuredBuffer<FfxUInt32>        r_cascade_brick_map_0  : register(t26);
StructuredBuffer<FfxUInt32>        r_cascade_brick_map_1  : register(t27);
StructuredBuffer<FfxUInt32>        r_cascade_brick_map_2  : register(t28);
StructuredBuffer<FfxUInt32>        r_cascade_brick_map_3  : register(t29);
StructuredBuffer<FfxUInt32>        r_cascade_brick_map_4  : register(t30);
StructuredBuffer<FfxUInt32>        r_cascade_brick_map_5  : register(t31);
StructuredBuffer<FfxUInt32>        r_cascade_brick_map_6  : register(t32);
StructuredBuffer<FfxUInt32>        r_cascade_brick_map_7  : register(t33);
StructuredBuffer<FfxUInt32>        r_cascade_brick_map_8  : register(t34);
StructuredBuffer<FfxUInt32>        r_cascade_brick_map_9  : register(t35);
StructuredBuffer<FfxUInt32>        r_cascade_brick_map_10 : register(t36);
StructuredBuffer<FfxUInt32>        r_cascade_brick_map_11 : register(t37);
StructuredBuffer<FfxUInt32>        r_cascade_brick_map_12 : register(t38);
StructuredBuffer<FfxUInt32>        r_cascade_brick_map_13 : register(t39);
StructuredBuffer<FfxUInt32>        r_cascade_brick_map_14 : register(t40);
StructuredBuffer<FfxUInt32>        r_cascade_brick_map_15 : register(t41);
StructuredBuffer<FfxUInt32>        r_cascade_brick_map_16 : register(t42);
StructuredBuffer<FfxUInt32>        r_cascade_brick_map_17 : register(t43);
StructuredBuffer<FfxUInt32>        r_cascade_brick_map_18 : register(t44);
StructuredBuffer<FfxUInt32>        r_cascade_brick_map_19 : register(t45);
StructuredBuffer<FfxUInt32>        r_cascade_brick_map_20 : register(t46);
StructuredBuffer<FfxUInt32>        r_cascade_brick_map_21 : register(t47);
StructuredBuffer<FfxUInt32>        r_cascade_brick_map_22 : register(t48);
StructuredBuffer<FfxUInt32>        r_cascade_brick_map_23 : register(t49);

RWTexture2D<float4>                rw_output                : register(u0);

FfxUInt32 LoadCascadeAABBTreesUInt(FfxUInt32 cascadeID, FfxUInt32 elementIndex)
{
    switch (cascadeID)
    {
    case 0:  return r_cascade_aabbtree_0[elementIndex];
    case 1:  return r_cascade_aabbtree_1[elementIndex];
    case 2:  return r_cascade_aabbtree_2[elementIndex];
    case 3:  return r_cascade_aabbtree_3[elementIndex];
    case 4:  return r_cascade_aabbtree_4[elementIndex];
    case 5:  return r_cascade_aabbtree_5[elementIndex];
    case 6:  return r_cascade_aabbtree_6[elementIndex];
    case 7:  return r_cascade_aabbtree_7[elementIndex];
    case 8:  return r_cascade_aabbtree_8[elementIndex];
    case 9:  return r_cascade_aabbtree_9[elementIndex];
    case 10: return r_cascade_aabbtree_10[elementIndex];
    case 11: return r_cascade_aabbtree_11[elementIndex];
    case 12: return r_cascade_aabbtree_12[elementIndex];
    case 13: return r_cascade_aabbtree_13[elementIndex];
    case 14: return r_cascade_aabbtree_14[elementIndex];
    case 15: return r_cascade_aabbtree_15[elementIndex];
    case 16: return r_cascade_aabbtree_16[elementIndex];
    case 17: return r_cascade_aabbtree_17[elementIndex];
    case 18: return r_cascade_aabbtree_18[elementIndex];
    case 19: return r_cascade_aabbtree_19[elementIndex];
    case 20: return r_cascade_aabbtree_20[elementIndex];
    case 21: return r_cascade_aabbtree_21[elementIndex];
    case 22: return r_cascade_aabbtree_22[elementIndex];
    case 23: return r_cascade_aabbtree_23[elementIndex];
    default: return 0;
    }
}

FfxFloat32x3 LoadCascadeAABBTreesFloat3(FfxUInt32 cascadeID, FfxUInt32 elementIndex)
{ 
    return FfxFloat32x3(asfloat(LoadCascadeAABBTreesUInt(cascadeID, elementIndex + 0)),
                        asfloat(LoadCascadeAABBTreesUInt(cascadeID, elementIndex + 1)),
                        asfloat(LoadCascadeAABBTreesUInt(cascadeID, elementIndex + 2)));
}

FfxUInt32 LoadBricksAABB(FfxUInt32 elementIndex)
{
    return r_bctx_bricks_aabb[elementIndex];
}

FfxBrixelizerCascadeInfo GetCascadeInfo(FfxUInt32 cascadeID)
{
    return g_bx_context_info.cascades[cascadeID];
}

FfxFloat32 SampleSDFAtlas(FfxFloat32x3 uvw)
{
    return r_sdf_atlas.SampleLevel(g_sdf_atlas_sampler, uvw, 0.0f);
}

FfxUInt32 LoadCascadeBrickMapArrayUniform(FfxUInt32 cascadeID, FfxUInt32 elementIndex)
{
    switch (cascadeID)
    {
    case 0:  return r_cascade_brick_map_0[elementIndex];
    case 1:  return r_cascade_brick_map_1[elementIndex];
    case 2:  return r_cascade_brick_map_2[elementIndex];
    case 3:  return r_cascade_brick_map_3[elementIndex];
    case 4:  return r_cascade_brick_map_4[elementIndex];
    case 5:  return r_cascade_brick_map_5[elementIndex];
    case 6:  return r_cascade_brick_map_6[elementIndex];
    case 7:  return r_cascade_brick_map_7[elementIndex];
    case 8:  return r_cascade_brick_map_8[elementIndex];
    case 9:  return r_cascade_brick_map_9[elementIndex];
    case 10: return r_cascade_brick_map_10[elementIndex];
    case 11: return r_cascade_brick_map_11[elementIndex];
    case 12: return r_cascade_brick_map_12[elementIndex];
    case 13: return r_cascade_brick_map_13[elementIndex];
    case 14: return r_cascade_brick_map_14[elementIndex];
    case 15: return r_cascade_brick_map_15[elementIndex];
    case 16: return r_cascade_brick_map_16[elementIndex];
    case 17: return r_cascade_brick_map_17[elementIndex];
    case 18: return r_cascade_brick_map_18[elementIndex];
    case 19: return r_cascade_brick_map_19[elementIndex];
    case 20: return r_cascade_brick_map_20[elementIndex];
    case 21: return r_cascade_brick_map_21[elementIndex];
    case 22: return r_cascade_brick_map_22[elementIndex];
    case 23: return r_cascade_brick_map_23[elementIndex];
    default: return 0;
    }
}

#define FLT_INF 1e30f

// By Morgan McGuire @morgan3d, http://graphicscodex.com
// Reuse permitted under the BSD license.
// https://www.shadertoy.com/view/4dsSzr
float3 FFX_HeatmapGradient(float t)
{
    return clamp((pow(t, 1.5) * 0.8 + 0.2) * float3(smoothstep(0.0, 0.35, t) + t * 0.5, smoothstep(0.5, 1.0, t), max(1.0 - t * 1.7, t * 7.0 - 6.0)), 0.0, 1.0);
}

// License?
float3 FFX_RandomColor(float2 uv) {
    uv                 = ffxFract(uv * float(15.718281828459045));
    float3 seeds = float3(float(0.123), float(0.456), float(0.789));
    seeds              = ffxFract((uv.x + float(0.5718281828459045) + seeds) * ((seeds + fmod(uv.x, float(0.141592653589793))) * float(27.61803398875) + float(4.718281828459045)));
    seeds              = ffxFract((uv.y + float(0.5718281828459045) + seeds) * ((seeds + fmod(uv.y, float(0.141592653589793))) * float(27.61803398875) + float(4.718281828459045)));
    seeds              = ffxFract((float(0.5718281828459045) + seeds) * ((seeds + fmod(uv.x, float(0.141592653589793))) * float(27.61803398875) + float(4.718281828459045)));
    return seeds;
}

float3 FFX_ViewSpaceToWorldSpace(float4 view_space_coord)
{
    return mul(g_example_consts.InvView, view_space_coord ).xyz;
}

float3 FFX_InvProjectPosition(float3 coord, float4x4 mat)
{
    coord.y  = (float(1.0) - coord.y);
    coord.xy = float(2.0) * coord.xy - 1;
    float4 projected = mul(mat, float4(coord, 1));
    projected.xyz /= projected.w;
    return projected.xyz;
}

float3 FFX_ScreenSpaceToViewSpace(float3 screen_uv_coord)
{
    return FFX_InvProjectPosition(screen_uv_coord, g_example_consts.InvProj);
}

float max3(float3 v)
{
    return max(v.x, max(v.y, v.z));
}

void FfxBrixelizerDebugVisualization(uint2 pixelCoord)
{
    uint width, height;
    rw_output.GetDimensions(width, height);
    if (pixelCoord.x > width || pixelCoord.y > height) {
        return;
    }

    float3 out_color = float3(0.0f, 0.0f, 0.0f);

    float2 uv = (float2(pixelCoord) + float(0.5).xx) / float2(width, height);

    float3 screen_uv_space_ray_origin = float3(uv, float(0.5));
    float3 view_space_ray             = FFX_ScreenSpaceToViewSpace(screen_uv_space_ray_origin);
    float3 view_space_ray_direction   = normalize(view_space_ray);
    float3 ray_direction              = normalize(FFX_ViewSpaceToWorldSpace(float4(view_space_ray_direction, float(0.0))));
    float3 ray_origin                 = FFX_ViewSpaceToWorldSpace(float4(0.0, 0.0, 0.0, 1.0));

    FfxBrixelizerRayDesc ray_desc;
    ray_desc.start_cascade_id = g_example_consts.StartCascadeID;
    ray_desc.end_cascade_id   = g_example_consts.EndCascadeID;
    ray_desc.t_min            = g_example_consts.TMin;
    ray_desc.t_max            = g_example_consts.TMax;
    ray_desc.origin           = ray_origin;
    ray_desc.direction        = ray_direction;

    FfxBrixelizerHitRaw hit_payload;
    bool hit = FfxBrixelizerTraverseRaw(ray_desc, hit_payload);
    float hit_dist = FLT_INF;

    if (hit) {
        hit_dist = hit_payload.t;

        float3 uvw = float3(
            FfxBrixelizerUnpackUnsigned8Bits((hit_payload.uvwc >> 0) & 0xff),
            FfxBrixelizerUnpackUnsigned8Bits((hit_payload.uvwc >> 8) & 0xff),
            FfxBrixelizerUnpackUnsigned8Bits((hit_payload.uvwc >> 16) & 0xff)
        );

        switch (g_example_consts.State) {
        case BRIXELIZER_EXAMPLE_OUTPUT_TYPE_DISTANCE: {
            float dist = (hit_payload.t - ray_desc.t_min) / (ray_desc.t_max - ray_desc.t_min);
            out_color = float3(0.0f, smoothstep(0.0f, 1.0f, dist), smoothstep(0.0f, 1.0f, 1.0f - dist));
            break;
        }
        case BRIXELIZER_EXAMPLE_OUTPUT_TYPE_UVW: {
            out_color = uvw;
            break;
        }
        case BRIXELIZER_EXAMPLE_OUTPUT_TYPE_ITERATIONS:
            out_color = FFX_HeatmapGradient(float(hit_payload.iter_count) / float(64));
            break;
        case BRIXELIZER_EXAMPLE_OUTPUT_TYPE_GRADIENT: {
            out_color = FfxBrixelizerGetHitNormal(hit_payload) * float(0.5) + float(0.5);
            break;
        }
        case BRIXELIZER_EXAMPLE_OUTPUT_TYPE_BRICK_ID:
            out_color = FFX_RandomColor(float2(float(hit_payload.brick_id % 256) / float(256.0), float((hit_payload.brick_id / 256) % 256) / float(256.0)));
            break;
        } // end switch

        if (g_example_consts.Flags & BRIXELIZER_EXAMPLE_SHOW_BRICK_OUTLINES) {
            const float beginOutline = 0.45f;
            float fade = 1.0f - 0.5f * smoothstep(beginOutline, 0.5f, max3(abs(uvw - 0.5f)));
            out_color *= fade;
        }
    } else {
        out_color = FFX_HeatmapGradient(float(hit_payload.iter_count) / float(64));
    }

    float3 in_color = rw_output[pixelCoord].xyz;

    rw_output[pixelCoord] = float4(lerp(in_color, out_color, g_example_consts.Alpha), 1.0f);
}

[numthreads(8, 8, 1)]
void MainCS(uint2 tid : SV_DispatchThreadID)
{
    FfxBrixelizerDebugVisualization(tid);
}
