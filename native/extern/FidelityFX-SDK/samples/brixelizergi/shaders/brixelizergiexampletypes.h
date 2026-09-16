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

#ifndef BRIXELIZER_EXAMPLE_TYPES_H
#define BRIXELIZER_EXAMPLE_TYPES_H

#ifdef __cplusplus
enum BrixelizerExampleFlags {
    BRIXELIZER_EXAMPLE_SHOW_BRICK_OUTLINES = 1 << 0,
};
#else
#define BRIXELIZER_EXAMPLE_SHOW_BRICK_OUTLINES (1u << 0)
#endif

#define BRIXELIZER_EXAMPLE_OUTPUT_TYPES \
    OT(DISTANCE, 0) \
    OT(UVW, 1) \
    OT(ITERATIONS, 2) \
    OT(GRADIENT, 3) \
    OT(BRICK_ID, 4)

#ifdef __cplusplus
enum BrixelizerExampleOutputType : int32_t
{
#define OT(name, value) BRIXELIZER_EXAMPLE_OUTPUT_TYPE_##name = value,
    BRIXELIZER_EXAMPLE_OUTPUT_TYPES
#undef OT
};
#else
#define OT(name, value) static const uint BRIXELIZER_EXAMPLE_OUTPUT_TYPE_##name = value;
BRIXELIZER_EXAMPLE_OUTPUT_TYPES
#undef OT
#endif

struct BrixelizerExampleConstants
{
#ifdef __cplusplus
	float     SolveEpsilon;
	float     TMin;
	float     TMax;
	uint32_t  State;

	float     InvView[16];

	float     InvProj[16];

	uint32_t  StartCascadeID;
	uint32_t  EndCascadeID;
	uint32_t  Flags;
	float     Alpha;
#else
	float     SolveEpsilon;
	float     TMin;
	float     TMax;
	uint      State;

	float4x4  InvView;

	float4x4  InvProj;

	uint      StartCascadeID;
	uint      EndCascadeID;
	uint      Flags;
	float     Alpha;
#endif
};

#endif
