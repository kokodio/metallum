package com.metallum.client.metal;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;
import com.sun.jna.Pointer;

@Environment(EnvType.CLIENT)
final class MetalPipelineSupport {
	static final long TRIANGLE_FAN_PRIMITIVE = 5L;

	private MetalPipelineSupport() {
	}

	static boolean samePointer(@Nullable final Pointer left, @Nullable final Pointer right) {
		long leftValue = left == null ? 0L : Pointer.nativeValue(left);
		long rightValue = right == null ? 0L : Pointer.nativeValue(right);
		return leftValue == rightValue;
	}

	static long[] vertexAttributeFormats(final VertexFormat vertexFormat) {
		java.util.List<com.mojang.blaze3d.vertex.VertexFormatElement> elements = vertexFormat.getElements();
		long[] result = new long[elements.size()];
		for (int i = 0; i < elements.size(); i++) {
			long formatCode = vertexAttributeFormatCode(elements.get(i).format());
			if (formatCode == 0L) {
				throw new IllegalStateException("Unsupported vertex attribute format: " + elements.get(i).format());
			}
			result[i] = formatCode;
		}
		return result;
	}

	static long[] vertexAttributeOffsets(final VertexFormat vertexFormat) {
		java.util.List<com.mojang.blaze3d.vertex.VertexFormatElement> elements = vertexFormat.getElements();
		long[] result = new long[elements.size()];
		for (int i = 0; i < elements.size(); i++) {
			result[i] = vertexFormat.getOffset(elements.get(i));
		}
		return result;
	}

	static long texelBufferPixelFormatCode(final GpuFormat format) {
		return switch (format) {
			case R8_UNORM -> 10L;
			case R8_SNORM -> 12L;
			case R8_UINT -> 13L;
			case R8_SINT -> 14L;
			case R16_UNORM -> 20L;
			case R16_SNORM -> 22L;
			case R16_UINT -> 23L;
			case R16_SINT -> 24L;
			case R16_FLOAT -> 25L;
			case RG8_UNORM -> 30L;
			case RG8_SNORM -> 32L;
			case RG8_UINT -> 33L;
			case RG8_SINT -> 34L;
			case R32_UINT -> 53L;
			case R32_SINT -> 54L;
			case R32_FLOAT -> 55L;
			case RG16_UNORM -> 60L;
			case RG16_SNORM -> 62L;
			case RG16_UINT -> 63L;
			case RG16_SINT -> 64L;
			case RG16_FLOAT -> 65L;
			case RGBA8_UNORM -> 70L;
			case RGBA8_SNORM -> 72L;
			case RGBA8_UINT -> 73L;
			case RGBA8_SINT -> 74L;
			case RGB10A2_UNORM -> 90L;
			case RG11B10_FLOAT -> 92L;
			case RG32_UINT -> 103L;
			case RG32_SINT -> 104L;
			case RG32_FLOAT -> 105L;
			case RGBA16_UNORM -> 110L;
			case RGBA16_SNORM -> 112L;
			case RGBA16_UINT -> 113L;
			case RGBA16_SINT -> 114L;
			case RGBA16_FLOAT -> 115L;
			case RGBA32_UINT -> 123L;
			case RGBA32_SINT -> 124L;
			case RGBA32_FLOAT -> 125L;
			default -> throw new IllegalStateException("Unsupported Metal texel buffer format: " + format);
		};
	}

	static long primitiveTypeCode(final VertexFormat.Mode mode) {
		return switch (mode) {
			case TRIANGLES, QUADS, LINES -> 0L;
			case TRIANGLE_STRIP -> 1L;
			case TRIANGLE_FAN -> TRIANGLE_FAN_PRIMITIVE;
			case DEBUG_LINES -> 2L;
			case DEBUG_LINE_STRIP -> 3L;
			case POINTS -> 4L;
			default -> -1L;
		};
	}

	static long toBlendFactorCode(final com.mojang.blaze3d.platform.BlendFactor factor) {
		return switch (factor) {
			case ZERO -> 0L;
			case ONE -> 1L;
			case SRC_COLOR -> 2L;
			case ONE_MINUS_SRC_COLOR -> 3L;
			case SRC_ALPHA -> 4L;
			case ONE_MINUS_SRC_ALPHA -> 5L;
			case DST_COLOR -> 6L;
			case ONE_MINUS_DST_COLOR -> 7L;
			case DST_ALPHA -> 8L;
			case ONE_MINUS_DST_ALPHA -> 9L;
			case SRC_ALPHA_SATURATE -> 10L;
			case CONSTANT_COLOR -> 11L;
			case ONE_MINUS_CONSTANT_COLOR -> 12L;
			case CONSTANT_ALPHA -> 13L;
			case ONE_MINUS_CONSTANT_ALPHA -> 14L;
		};
	}

	static long toBlendOpCode(final com.mojang.blaze3d.platform.BlendOp op) {
		return switch (op) {
			case ADD -> 0L;
			case SUBTRACT -> 1L;
			case REVERSE_SUBTRACT -> 2L;
			case MIN -> 3L;
			case MAX -> 4L;
		};
	}

	static long toCompareOpCode(final com.mojang.blaze3d.platform.CompareOp op) {
		return switch (op) {
			case ALWAYS_PASS -> 1L;
			case LESS_THAN -> 2L;
			case LESS_THAN_OR_EQUAL -> 3L;
			case EQUAL -> 4L;
			case NOT_EQUAL -> 5L;
			case GREATER_THAN_OR_EQUAL -> 6L;
			case GREATER_THAN -> 7L;
			case NEVER_PASS -> 8L;
		};
	}

	private static long vertexAttributeFormatCode(final GpuFormat format) {
		return switch (format) {
			case R32_FLOAT -> 1L;
			case RG32_FLOAT -> 2L;
			case RGB32_FLOAT -> 3L;
			case RGBA32_FLOAT -> 4L;
			case RGBA8_UNORM -> 5L;
			case RGBA8_UINT -> 6L;
			case RG16_UINT -> 7L;
			case RG16_UNORM -> 8L;
			case RG16_SINT -> 9L;
			case RG16_SNORM -> 10L;
			case RGBA16_UINT -> 11L;
			case RGBA16_SINT -> 12L;
			case RGBA16_UNORM -> 13L;
			case RGBA16_SNORM -> 14L;
			case R32_UINT -> 15L;
			case RG32_UINT -> 16L;
			case RGB32_UINT -> 17L;
			case RGBA32_UINT -> 18L;
			case R32_SINT -> 19L;
			case RG32_SINT -> 20L;
			case RGB32_SINT -> 21L;
			case RGBA32_SINT -> 22L;
			case R16_FLOAT -> 23L;
			case RG16_FLOAT -> 24L;
			case RGBA16_FLOAT -> 25L;
			case RGBA8_SNORM -> 26L;
			case RGBA8_SINT -> 27L;
			case RGB8_UNORM -> 28L;
			case RGB8_SNORM -> 29L;
			case RGB8_UINT -> 30L;
			case RGB8_SINT -> 31L;
			case RGB16_UINT -> 32L;
			case RGB16_SINT -> 33L;
			case RGB16_UNORM -> 34L;
			case RGB16_SNORM -> 35L;
			case RGB16_FLOAT -> 36L;
			default -> 0L;
		};
	}
}
