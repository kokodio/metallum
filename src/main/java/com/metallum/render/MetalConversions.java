package com.metallum.render;

import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import io.github.kokodio.metaljvm.metal.*;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
final class MetalConversions {
    private MetalConversions() {
    }

    public static MTLPixelFormat pixelFormat(final com.mojang.renderpearl.api.GpuFormat format) {
        return switch (format) {
            case R8_UNORM -> MTLPixelFormat.R8Unorm;
            case R8_SNORM -> MTLPixelFormat.R8Snorm;
            case R8_UINT -> MTLPixelFormat.R8Uint;
            case R8_SINT -> MTLPixelFormat.R8Sint;
            case R16_UNORM -> MTLPixelFormat.R16Unorm;
            case R16_SNORM -> MTLPixelFormat.R16Snorm;
            case R16_UINT -> MTLPixelFormat.R16Uint;
            case R16_SINT -> MTLPixelFormat.R16Sint;
            case R16_FLOAT -> MTLPixelFormat.R16Float;
            case RG8_UNORM -> MTLPixelFormat.RG8Unorm;
            case RG8_SNORM -> MTLPixelFormat.RG8Snorm;
            case RG8_UINT -> MTLPixelFormat.RG8Uint;
            case RG8_SINT -> MTLPixelFormat.RG8Sint;
            case R32_UINT -> MTLPixelFormat.R32Uint;
            case R32_SINT -> MTLPixelFormat.R32Sint;
            case R32_FLOAT -> MTLPixelFormat.R32Float;
            case RG16_UNORM -> MTLPixelFormat.RG16Unorm;
            case RG16_SNORM -> MTLPixelFormat.RG16Snorm;
            case RG16_UINT -> MTLPixelFormat.RG16Uint;
            case RG16_SINT -> MTLPixelFormat.RG16Sint;
            case RG16_FLOAT -> MTLPixelFormat.RG16Float;
            case RGBA8_UNORM -> MTLPixelFormat.RGBA8Unorm;
            case RGBA8_SNORM -> MTLPixelFormat.RGBA8Snorm;
            case RGBA8_UINT -> MTLPixelFormat.RGBA8Uint;
            case RGBA8_SINT -> MTLPixelFormat.RGBA8Sint;
            case RGB10A2_UNORM -> MTLPixelFormat.RGB10A2Unorm;
            case RGB10A2_UINT -> MTLPixelFormat.RGB10A2Uint;
            case RG11B10_FLOAT -> MTLPixelFormat.RG11B10Float;
            case RG32_UINT -> MTLPixelFormat.RG32Uint;
            case RG32_SINT -> MTLPixelFormat.RG32Sint;
            case RG32_FLOAT -> MTLPixelFormat.RG32Float;
            case RGBA16_UNORM -> MTLPixelFormat.RGBA16Unorm;
            case RGBA16_SNORM -> MTLPixelFormat.RGBA16Snorm;
            case RGBA16_UINT -> MTLPixelFormat.RGBA16Uint;
            case RGBA16_SINT -> MTLPixelFormat.RGBA16Sint;
            case RGBA16_FLOAT -> MTLPixelFormat.RGBA16Float;
            case RGBA32_UINT -> MTLPixelFormat.RGBA32Uint;
            case RGBA32_SINT -> MTLPixelFormat.RGBA32Sint;
            case RGBA32_FLOAT -> MTLPixelFormat.RGBA32Float;
            case D16_UNORM -> MTLPixelFormat.Depth16Unorm;
            case D32_FLOAT -> MTLPixelFormat.Depth32Float;
            case S8_UINT -> MTLPixelFormat.Stencil8;
            case D24_UNORM_S8_UINT -> MTLPixelFormat.Depth24Unorm_Stencil8;
            case D32_FLOAT_S8_UINT -> MTLPixelFormat.Depth32Float_Stencil8;
            default -> throw new IllegalStateException("Unsupported Metal texel buffer format: " + format);
        };
    }

    public static MTLVertexFormat vertexFormat(final com.mojang.renderpearl.api.GpuFormat format) {
        return switch (format) {
            case R32_FLOAT -> MTLVertexFormat.Float;
            case RG32_FLOAT -> MTLVertexFormat.Float2;
            case RGB32_FLOAT -> MTLVertexFormat.Float3;
            case RGBA32_FLOAT -> MTLVertexFormat.Float4;
            case RGBA8_UNORM -> MTLVertexFormat.UChar4Normalized;
            case RGBA8_UINT -> MTLVertexFormat.UChar4;
            case RG16_UINT -> MTLVertexFormat.UShort2;
            case RG16_UNORM -> MTLVertexFormat.UShort2Normalized;
            case RG16_SINT -> MTLVertexFormat.Short2;
            case RG16_SNORM -> MTLVertexFormat.Short2Normalized;
            case RGBA16_UINT -> MTLVertexFormat.UShort4;
            case RGBA16_SINT -> MTLVertexFormat.Short4;
            case RGBA16_UNORM -> MTLVertexFormat.UShort4Normalized;
            case RGBA16_SNORM -> MTLVertexFormat.Short4Normalized;
            case R32_UINT -> MTLVertexFormat.UInt;
            case RG32_UINT -> MTLVertexFormat.UInt2;
            case RGB32_UINT -> MTLVertexFormat.UInt3;
            case RGBA32_UINT -> MTLVertexFormat.UInt4;
            case R32_SINT -> MTLVertexFormat.Int;
            case RG32_SINT -> MTLVertexFormat.Int2;
            case RGB32_SINT -> MTLVertexFormat.Int3;
            case RGBA32_SINT -> MTLVertexFormat.Int4;
            case R16_FLOAT -> MTLVertexFormat.Half;
            case R16_UINT -> MTLVertexFormat.UShort;
            case R16_SINT -> MTLVertexFormat.Short;
            case R16_UNORM -> MTLVertexFormat.UShortNormalized;
            case R16_SNORM -> MTLVertexFormat.ShortNormalized;
            case R8_UINT -> MTLVertexFormat.UChar;
            case R8_SINT -> MTLVertexFormat.Char;
            case R8_UNORM -> MTLVertexFormat.UCharNormalized;
            case R8_SNORM -> MTLVertexFormat.CharNormalized;
            case RG16_FLOAT -> MTLVertexFormat.Half2;
            case RGBA16_FLOAT -> MTLVertexFormat.Half4;
            case RGBA8_SNORM -> MTLVertexFormat.Char4Normalized;
            case RGBA8_SINT -> MTLVertexFormat.Char4;
            case RGB8_UNORM -> MTLVertexFormat.UChar3Normalized;
            case RGB8_SNORM -> MTLVertexFormat.Char3Normalized;
            case RGB8_UINT -> MTLVertexFormat.UChar3;
            case RGB8_SINT -> MTLVertexFormat.Char3;
            case RGB16_UINT -> MTLVertexFormat.UShort3;
            case RGB16_SINT -> MTLVertexFormat.Short3;
            case RGB16_UNORM -> MTLVertexFormat.UShort3Normalized;
            case RGB16_SNORM -> MTLVertexFormat.Short3Normalized;
            case RGB16_FLOAT -> MTLVertexFormat.Half3;
            default -> MTLVertexFormat.Invalid;
        };
    }

    public static MTLBlendFactor blendFactor(final com.mojang.renderpearl.api.pipeline.BlendFactor factor) {
        return switch (factor) {
            case ZERO -> MTLBlendFactor.Zero;
            case ONE -> MTLBlendFactor.One;
            case SRC_COLOR -> MTLBlendFactor.SourceColor;
            case ONE_MINUS_SRC_COLOR -> MTLBlendFactor.OneMinusSourceColor;
            case SRC_ALPHA -> MTLBlendFactor.SourceAlpha;
            case ONE_MINUS_SRC_ALPHA -> MTLBlendFactor.OneMinusSourceAlpha;
            case DST_COLOR -> MTLBlendFactor.DestinationColor;
            case ONE_MINUS_DST_COLOR -> MTLBlendFactor.OneMinusDestinationColor;
            case DST_ALPHA -> MTLBlendFactor.DestinationAlpha;
            case ONE_MINUS_DST_ALPHA -> MTLBlendFactor.OneMinusDestinationAlpha;
            case SRC_ALPHA_SATURATE -> MTLBlendFactor.SourceAlphaSaturated;
            case CONSTANT_COLOR -> MTLBlendFactor.BlendColor;
            case ONE_MINUS_CONSTANT_COLOR -> MTLBlendFactor.OneMinusBlendColor;
            case CONSTANT_ALPHA -> MTLBlendFactor.BlendAlpha;
            case ONE_MINUS_CONSTANT_ALPHA -> MTLBlendFactor.OneMinusBlendAlpha;
        };
    }

    public static MTLBlendOperation blendOperation(final com.mojang.renderpearl.api.pipeline.BlendOp op) {
        return switch (op) {
            case ADD -> MTLBlendOperation.Add;
            case SUBTRACT -> MTLBlendOperation.Subtract;
            case REVERSE_SUBTRACT -> MTLBlendOperation.ReverseSubtract;
            case MIN -> MTLBlendOperation.Min;
            case MAX -> MTLBlendOperation.Max;
        };
    }

    public static MTLCompareFunction compareFunction(final com.mojang.renderpearl.api.pipeline.CompareOp op) {
        return switch (op) {
            case NEVER_PASS -> MTLCompareFunction.Never;
            case LESS_THAN -> MTLCompareFunction.Less;
            case EQUAL -> MTLCompareFunction.Equal;
            case LESS_THAN_OR_EQUAL -> MTLCompareFunction.LessEqual;
            case GREATER_THAN -> MTLCompareFunction.Greater;
            case NOT_EQUAL -> MTLCompareFunction.NotEqual;
            case GREATER_THAN_OR_EQUAL -> MTLCompareFunction.GreaterEqual;
            case ALWAYS_PASS -> MTLCompareFunction.Always;
        };
    }

    public static MTLPrimitiveType primitiveType(final com.mojang.renderpearl.api.pipeline.PrimitiveTopology mode) {
        return switch (mode) {
            case TRIANGLES, QUADS, LINES -> MTLPrimitiveType.Triangle;
            case TRIANGLE_STRIP -> MTLPrimitiveType.TriangleStrip;
            case DEBUG_LINES -> MTLPrimitiveType.Line;
            case DEBUG_LINE_STRIP -> MTLPrimitiveType.LineStrip;
            case POINTS -> MTLPrimitiveType.Point;
            // Metal has no triangle fans, MetalRenderPass re-indexes them into a triangle list
            case TRIANGLE_FAN -> MTLPrimitiveType.Triangle;
        };
    }

    public static MTLSamplerMinMagFilter minMagFilter(final com.mojang.renderpearl.api.textures.FilterMode filterMode) {
        return switch (filterMode) {
            case NEAREST -> MTLSamplerMinMagFilter.Nearest;
            case LINEAR -> MTLSamplerMinMagFilter.Linear;
        };
    }

    public static MTLSamplerAddressMode addressMode(final com.mojang.renderpearl.api.textures.AddressMode addressMode) {
        return switch (addressMode) {
            case REPEAT -> MTLSamplerAddressMode.Repeat;
            case CLAMP_TO_EDGE -> MTLSamplerAddressMode.ClampToEdge;
        };
    }

    public static MTLIndexType indexType(final com.mojang.renderpearl.api.pipeline.IndexType indexType) {
        return indexType == com.mojang.renderpearl.api.pipeline.IndexType.INT ? MTLIndexType.UInt32 : MTLIndexType.UInt16;
    }

    public static long colorWriteMask(@ColorTargetState.WriteMask final int blazeMask) {
        long mask = 0L;
        if ((blazeMask & ColorTargetState.WRITE_RED) != 0) mask |= MTLColorWriteMask.Red;
        if ((blazeMask & ColorTargetState.WRITE_GREEN) != 0) mask |= MTLColorWriteMask.Green;
        if ((blazeMask & ColorTargetState.WRITE_BLUE) != 0) mask |= MTLColorWriteMask.Blue;
        if ((blazeMask & ColorTargetState.WRITE_ALPHA) != 0) mask |= MTLColorWriteMask.Alpha;
        return mask;
    }

    public static long resourceOptions(final MTLStorageMode storageMode, final MTLHazardTrackingMode hazardTrackingMode) {
        return (storageMode.value << 4) | (hazardTrackingMode.value << 8);
    }

    public static int bytes(final MTLIndexType indexType) {
        return indexType == MTLIndexType.UInt32 ? 4 : 2;
    }
}
