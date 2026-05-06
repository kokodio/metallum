package com.metallum.client.metal.optimization;

import com.metallum.mixin.optimization.accessor.MeshDataAccessor;
import com.metallum.mixin.optimization.accessor.SectionCompilerResultsAccessor;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.nio.ByteBuffer;
import java.util.Map;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import org.lwjgl.system.MemoryUtil;

@Environment(EnvType.CLIENT)
public final class MetalTerrainVertexPacking {
	public static final int VANILLA_BLOCK_VERTEX_SIZE = 28;
	public static final int PACKED_TERRAIN_VERTEX_SIZE = 16;

	private static final int SRC_POSITION_X = 0;
	private static final int SRC_POSITION_Y = 4;
	private static final int SRC_POSITION_Z = 8;
	private static final int SRC_COLOR = 12;
	private static final int SRC_UV0_U = 16;
	private static final int SRC_UV0_V = 20;
	private static final int SRC_UV2_BLOCK = 24;
	private static final int SRC_UV2_SKY = 26;

	private static final int DST_POSITION_X = 0;
	private static final int DST_POSITION_Y = 2;
	private static final int DST_POSITION_Z = 4;
	private static final int DST_COLOR = 6;
	private static final int DST_UV0_U = 10;
	private static final int DST_UV0_V = 12;
	private static final int DST_UV2 = 14;

	private static final long METAL_VERTEX_FORMAT_HALF3 = 36L;
	private static final long METAL_VERTEX_FORMAT_UCHAR4_NORMALIZED = 5L;
	private static final long METAL_VERTEX_FORMAT_USHORT2_NORMALIZED = 8L;
	private static final long METAL_VERTEX_FORMAT_UCHAR2 = 38L;
	private static final long[] PACKED_ATTRIBUTE_FORMATS = {
		METAL_VERTEX_FORMAT_HALF3,
		METAL_VERTEX_FORMAT_UCHAR4_NORMALIZED,
		METAL_VERTEX_FORMAT_USHORT2_NORMALIZED,
		METAL_VERTEX_FORMAT_UCHAR2
	};
	private static final long[] PACKED_ATTRIBUTE_OFFSETS = {
		DST_POSITION_X,
		DST_COLOR,
		DST_UV0_U,
		DST_UV2
	};

	private static volatile boolean enabled;

	private MetalTerrainVertexPacking() {
	}

	public static void setEnabled(final boolean enabled) {
		MetalTerrainVertexPacking.enabled = enabled;
	}

	public static boolean isPackedTerrainPipeline(final String pipelineLocation) {
		return enabled && isTerrainPipeline(pipelineLocation);
	}

	public static long[] packedAttributeFormats() {
		return PACKED_ATTRIBUTE_FORMATS.clone();
	}

	public static long[] packedAttributeOffsets() {
		return PACKED_ATTRIBUTE_OFFSETS.clone();
	}

	public static int vertexSizeFor(final VertexFormat format) {
		return enabled && format == DefaultVertexFormat.BLOCK ? PACKED_TERRAIN_VERTEX_SIZE : format.getVertexSize();
	}

	public static void optimize(final SectionCompiler.Results results, final SectionBufferBuilderPack sectionBufferBuilderPack) {
		if (!enabled) {
			return;
		}

		Map<ChunkSectionLayer, MeshData> renderedLayers = ((SectionCompilerResultsAccessor)(Object)results).metallum$getRenderedLayers();
		for (Map.Entry<ChunkSectionLayer, MeshData> entry : renderedLayers.entrySet()) {
			optimizeLayer(entry.getKey(), entry.getValue(), sectionBufferBuilderPack.buffer(entry.getKey()));
		}
	}

	private static void optimizeLayer(final ChunkSectionLayer layer, final MeshData mesh, final ByteBufferBuilder target) {
		if (mesh == null || !isPackableLayer(layer)) {
			return;
		}

		MeshData.DrawState drawState = mesh.drawState();
		if (!isBlockQuadMesh(drawState)) {
			return;
		}

		int vertexCount = drawState.vertexCount();
		int sourceSize = Math.multiplyExact(vertexCount, VANILLA_BLOCK_VERTEX_SIZE);
		ByteBuffer source = mesh.vertexBuffer();
		if (source.remaining() < sourceSize) {
			return;
		}

		if (!source.isDirect()) {
			return;
		}

		long sourceBase = MemoryUtil.memAddress(source);
		MetalTerrainFaceCulling.CullableFaceLayout faceLayout = MetalTerrainFaceCulling.buildCullableFaceLayout(layer, mesh, drawState, sourceBase);
		long destinationBase = target.reserve(Math.multiplyExact(vertexCount, PACKED_TERRAIN_VERTEX_SIZE));
		boolean packed = faceLayout == null
			? packVertices(sourceBase, destinationBase, vertexCount)
			: packVertices(sourceBase, destinationBase, faceLayout);

		ByteBufferBuilder.Result packedVertexBuffer = target.build();
		if (!packed || packedVertexBuffer == null) {
			if (packedVertexBuffer != null) {
				packedVertexBuffer.close();
			}
			return;
		}

		ByteBufferBuilder.Result originalVertexBuffer = mesh.vertexBufferSlice();
		((MeshDataAccessor) mesh).metallum$setVertexBuffer(packedVertexBuffer);
		originalVertexBuffer.close();
		if (faceLayout != null) {
			MetalTerrainFaceCulling.attachSegments(mesh, faceLayout.segments());
		}
	}

	private static boolean isTerrainPipeline(final String pipelineLocation) {
		return switch (pipelineLocation) {
			case "minecraft:pipeline/solid_terrain",
				 "minecraft:pipeline/cutout_terrain",
				 "minecraft:pipeline/translucent_terrain" -> true;
			default -> false;
		};
	}

	private static boolean isPackableLayer(final ChunkSectionLayer layer) {
		return layer == ChunkSectionLayer.SOLID || layer == ChunkSectionLayer.CUTOUT || layer == ChunkSectionLayer.TRANSLUCENT;
	}

	private static boolean isBlockQuadMesh(final MeshData.DrawState drawState) {
		return drawState.format() == DefaultVertexFormat.BLOCK && drawState.primitiveTopology() == PrimitiveTopology.QUADS;
	}

	private static boolean packVertices(final long sourceBase, final long destinationBase, final int vertexCount) {
		for (int vertex = 0; vertex < vertexCount; vertex++) {
			if (!packVertex(sourceBase, destinationBase, vertex, vertex)) {
				return false;
			}
		}
		return true;
	}

	private static boolean packVertices(
		final long sourceBase,
		final long destinationBase,
		final MetalTerrainFaceCulling.CullableFaceLayout faceLayout
	) {
		for (int quad = 0; quad < faceLayout.quadCount(); quad++) {
			int sourceVertex = quad * 4;
			int destinationVertex = faceLayout.takeDestinationVertex(quad);
			for (int vertexInQuad = 0; vertexInQuad < 4; vertexInQuad++) {
				if (!packVertex(sourceBase, destinationBase, sourceVertex + vertexInQuad, destinationVertex + vertexInQuad)) {
					return false;
				}
			}
		}
		return true;
	}

	private static boolean packVertex(final long sourceBase, final long destinationBase, final int sourceVertex, final int destinationVertex) {
		long src = sourceBase + (long)sourceVertex * VANILLA_BLOCK_VERTEX_SIZE;
		long dst = destinationBase + (long)destinationVertex * PACKED_TERRAIN_VERTEX_SIZE;

		float x = MemoryUtil.memGetFloat(src + SRC_POSITION_X);
		float y = MemoryUtil.memGetFloat(src + SRC_POSITION_Y);
		float z = MemoryUtil.memGetFloat(src + SRC_POSITION_Z);
		float u = MemoryUtil.memGetFloat(src + SRC_UV0_U);
		float v = MemoryUtil.memGetFloat(src + SRC_UV0_V);
		if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z) || !isAtlasUv(u) || !isAtlasUv(v)) {
			return false;
		}

		MemoryUtil.memPutShort(dst + DST_POSITION_X, Float.floatToFloat16(x));
		MemoryUtil.memPutShort(dst + DST_POSITION_Y, Float.floatToFloat16(y));
		MemoryUtil.memPutShort(dst + DST_POSITION_Z, Float.floatToFloat16(z));
		MemoryUtil.memCopy(src + SRC_COLOR, dst + DST_COLOR, Integer.BYTES);
		MemoryUtil.memPutShort(dst + DST_UV0_U, toUnorm16(u));
		MemoryUtil.memPutShort(dst + DST_UV0_V, toUnorm16(v));
		MemoryUtil.memPutByte(dst + DST_UV2, toUnsignedByte(MemoryUtil.memGetShort(src + SRC_UV2_BLOCK) & 0xFFFF));
		MemoryUtil.memPutByte(dst + DST_UV2 + 1, toUnsignedByte(MemoryUtil.memGetShort(src + SRC_UV2_SKY) & 0xFFFF));
		return true;
	}

	private static boolean isAtlasUv(final float value) {
		return Float.isFinite(value) && value >= -0.0001F && value <= 1.0001F;
	}

	private static short toUnorm16(final float value) {
		float clamped = Math.max(0.0F, Math.min(1.0F, value));
		return (short)Math.round(clamped * 65535.0F);
	}

	private static byte toUnsignedByte(final int value) {
		return (byte)Math.max(0, Math.min(255, value));
	}
}
