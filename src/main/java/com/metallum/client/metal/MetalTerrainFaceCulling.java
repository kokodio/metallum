package com.metallum.client.metal;

import com.metallum.mixin.accessor.MeshDataAccessor;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.nio.ByteBuffer;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

@Environment(EnvType.CLIENT)
public final class MetalTerrainFaceCulling {
	private static final double SECTION_SIZE = 16.0;
	private static final double CAMERA_EPSILON = 1.0E-4;
	private static final float NORMAL_EPSILON = 1.0E-6F;
	private static final int MIN_CLASSIFIED_RATIO_NUMERATOR = 3;
	private static final int MIN_CLASSIFIED_RATIO_DENOMINATOR = 4;

	private static final int NEGATIVE_X = 0;
	private static final int POSITIVE_X = 1;
	private static final int NEGATIVE_Y = 2;
	private static final int POSITIVE_Y = 3;
	private static final int NEGATIVE_Z = 4;
	private static final int POSITIVE_Z = 5;
	private static final int UNKNOWN = 6;
	private static final int BUCKET_COUNT = 7;
	private static final int[] BUCKET_WRITE_ORDER = {
		NEGATIVE_X,
		NEGATIVE_Y,
		POSITIVE_X,
		POSITIVE_Y,
		NEGATIVE_Z,
		UNKNOWN,
		POSITIVE_Z
	};

	private static final Object LOCK = new Object();
	private static final Map<SectionMesh, BlockPos> SECTION_ORIGINS = new WeakHashMap<>();
	private static final Map<DrawKey, VisibleRanges> VISIBLE_DRAW_RANGES = new HashMap<>();

	private static volatile boolean enabled;
	@Nullable
	private static volatile Vec3 cameraPosition;

	private MetalTerrainFaceCulling() {
	}

	static void setEnabled(final boolean enabled) {
		MetalTerrainFaceCulling.enabled = enabled;
		if (!MetalTerrainFaceCulling.enabled) {
			beginPrepare();
			synchronized (LOCK) {
				SECTION_ORIGINS.clear();
			}
		}
	}

	public static boolean isEnabled() {
		return enabled;
	}

	public static boolean tryAttachIndexBuffer(final ChunkSectionLayer layer, final MeshData sourceMesh, final MeshData packedMesh) {
		if (!enabled || layer != ChunkSectionLayer.SOLID || sourceMesh == null || packedMesh == null) {
			return false;
		}

		MeshData.DrawState drawState = sourceMesh.drawState();
		if (drawState.format() != DefaultVertexFormat.BLOCK || drawState.mode() != VertexFormat.Mode.QUADS) {
			return false;
		}
		if (((MeshDataAccessor)sourceMesh).metallum$getIndexBuffer() != null) {
			return false;
		}

		FaceIndexBuild build = buildFaceIndexBuffer(sourceMesh);
		if (build == null) {
			return false;
		}

		((MeshDataAccessor)packedMesh).metallum$setIndexBuffer(build.indexBuffer());
		((MeshDataRanges)(Object)packedMesh).metallum$setTerrainFaceRanges(build.ranges());
		return true;
	}

	public static void beginPrepare() {
		synchronized (LOCK) {
			VISIBLE_DRAW_RANGES.clear();
		}
	}

	public static void setCameraPosition(final Vec3 cameraPosition) {
		MetalTerrainFaceCulling.cameraPosition = cameraPosition;
	}

	public static void rememberSectionOrigin(final SectionMesh sectionMesh, final BlockPos origin) {
		if (!enabled || !(sectionMesh instanceof SectionMeshRanges)) {
			return;
		}

		synchronized (LOCK) {
			SECTION_ORIGINS.put(sectionMesh, origin.immutable());
		}
	}

	public static void registerVisibleRanges(
		final SectionMesh sectionMesh,
		final ChunkSectionLayer layer,
		final SectionRenderDispatcher.RenderSectionBufferSlice slice
	) {
		if (!enabled || layer != ChunkSectionLayer.SOLID || slice == null || !(sectionMesh instanceof SectionMeshRanges rangesHolder)) {
			return;
		}

		FaceRanges ranges = rangesHolder.metallum$getTerrainFaceRanges(layer);
		if (ranges == null || slice.indexBuffer() == null) {
			return;
		}

		SectionMesh.SectionDraw draw = sectionMesh.getSectionDraw(layer);
		if (draw == null || !draw.hasCustomIndexBuffer() || draw.indexType() == null) {
			return;
		}

		BlockPos origin;
		synchronized (LOCK) {
			origin = SECTION_ORIGINS.get(sectionMesh);
		}
		Vec3 camera = cameraPosition;
		if (origin == null || camera == null) {
			return;
		}

		VisibleRanges visibleRanges = ranges.visibleFrom(origin, camera);
		if (visibleRanges == null) {
			return;
		}

		int vertexStride = MetalTerrainVertexPacking.vertexSizeFor(layer.pipeline().getVertexFormat());
		if (vertexStride <= 0) {
			return;
		}

		int firstIndex = Math.toIntExact(slice.indexBufferOffset() / draw.indexType().bytes);
		int baseVertex = Math.toIntExact(slice.vertexBufferOffset() / vertexStride);
		DrawKey key = new DrawKey(slice.vertexBuffer(), slice.indexBuffer(), firstIndex, draw.indexCount(), baseVertex);
		synchronized (LOCK) {
			VISIBLE_DRAW_RANGES.put(key, visibleRanges);
		}
	}

	@Nullable
	public static VisibleRanges takeVisibleRanges(final RenderPass.Draw<?> draw, @Nullable final GpuBuffer resolvedIndexBuffer) {
		if (!enabled || resolvedIndexBuffer == null) {
			return null;
		}

		DrawKey key = new DrawKey(draw.vertexBuffer(), resolvedIndexBuffer, draw.firstIndex(), draw.indexCount(), draw.baseVertex());
		synchronized (LOCK) {
			return VISIBLE_DRAW_RANGES.remove(key);
		}
	}

	@Nullable
	private static FaceIndexBuild buildFaceIndexBuffer(final MeshData sourceMesh) {
		MeshData.DrawState drawState = sourceMesh.drawState();
		int vertexCount = drawState.vertexCount();
		if (vertexCount < 4 || vertexCount % 4 != 0) {
			return null;
		}

		int quadCount = vertexCount / 4;
		int expectedIndexCount = quadCount * 6;
		if (drawState.indexCount() != expectedIndexCount) {
			return null;
		}

		ByteBuffer source = sourceMesh.vertexBuffer().duplicate();
		if (!source.isDirect() || source.remaining() < (long)vertexCount * MetalTerrainVertexPacking.VANILLA_BLOCK_VERTEX_SIZE) {
			return null;
		}

		long sourceBase = MemoryUtil.memAddress(source);
		byte[] quadBuckets = new byte[quadCount];
		int[] quadCounts = new int[BUCKET_COUNT];
		int classifiedQuads = 0;
		for (int quad = 0; quad < quadCount; quad++) {
			int bucket = classifyQuad(sourceBase + (long)quad * 4L * MetalTerrainVertexPacking.VANILLA_BLOCK_VERTEX_SIZE);
			quadBuckets[quad] = (byte)bucket;
			quadCounts[bucket]++;
			if (bucket != UNKNOWN) {
				classifiedQuads++;
			}
		}

		if (classifiedQuads == 0 || classifiedQuads * MIN_CLASSIFIED_RATIO_DENOMINATOR < quadCount * MIN_CLASSIFIED_RATIO_NUMERATOR) {
			return null;
		}

		int[] firstIndices = new int[BUCKET_COUNT];
		int[] indexCounts = new int[BUCKET_COUNT];
		int indexCursor = 0;
		for (int bucket : BUCKET_WRITE_ORDER) {
			firstIndices[bucket] = indexCursor;
			indexCounts[bucket] = quadCounts[bucket] * 6;
			indexCursor += indexCounts[bucket];
		}

		VertexFormat.IndexType indexType = drawState.indexType();
		ByteBufferBuilder builder = ByteBufferBuilder.exactlySized(indexCursor * indexType.bytes);
		try {
			long indexBase = builder.reserve(indexCursor * indexType.bytes);
			long writePointer = indexBase;
			for (int bucket : BUCKET_WRITE_ORDER) {
				for (int quad = 0; quad < quadCount; quad++) {
					if ((quadBuckets[quad] & 0xFF) == bucket) {
						writePointer = writeQuadIndices(writePointer, indexType, quad * 4);
					}
				}
			}
			return new FaceIndexBuild(builder.build(), new FaceRanges(firstIndices, indexCounts, indexCursor));
		} catch (Throwable throwable) {
			builder.close();
			throw throwable;
		}
	}

	private static int classifyQuad(final long quadBase) {
		float x0 = MemoryUtil.memGetFloat(quadBase);
		float y0 = MemoryUtil.memGetFloat(quadBase + 4L);
		float z0 = MemoryUtil.memGetFloat(quadBase + 8L);
		float x1 = MemoryUtil.memGetFloat(quadBase + MetalTerrainVertexPacking.VANILLA_BLOCK_VERTEX_SIZE);
		float y1 = MemoryUtil.memGetFloat(quadBase + MetalTerrainVertexPacking.VANILLA_BLOCK_VERTEX_SIZE + 4L);
		float z1 = MemoryUtil.memGetFloat(quadBase + MetalTerrainVertexPacking.VANILLA_BLOCK_VERTEX_SIZE + 8L);
		float x2 = MemoryUtil.memGetFloat(quadBase + 2L * MetalTerrainVertexPacking.VANILLA_BLOCK_VERTEX_SIZE);
		float y2 = MemoryUtil.memGetFloat(quadBase + 2L * MetalTerrainVertexPacking.VANILLA_BLOCK_VERTEX_SIZE + 4L);
		float z2 = MemoryUtil.memGetFloat(quadBase + 2L * MetalTerrainVertexPacking.VANILLA_BLOCK_VERTEX_SIZE + 8L);

		float ux = x1 - x0;
		float uy = y1 - y0;
		float uz = z1 - z0;
		float vx = x2 - x0;
		float vy = y2 - y0;
		float vz = z2 - z0;
		float nx = uy * vz - uz * vy;
		float ny = uz * vx - ux * vz;
		float nz = ux * vy - uy * vx;

		float ax = Math.abs(nx);
		float ay = Math.abs(ny);
		float az = Math.abs(nz);
		if (ax <= NORMAL_EPSILON && ay <= NORMAL_EPSILON && az <= NORMAL_EPSILON) {
			return UNKNOWN;
		}

		if (ax >= ay && ax >= az) {
			return dominantAxisBucket(nx, ax, Math.max(ay, az), NEGATIVE_X, POSITIVE_X);
		}
		if (ay >= az) {
			return dominantAxisBucket(ny, ay, Math.max(ax, az), NEGATIVE_Y, POSITIVE_Y);
		}
		return dominantAxisBucket(nz, az, Math.max(ax, ay), NEGATIVE_Z, POSITIVE_Z);
	}

	private static int dominantAxisBucket(
		final float normalComponent,
		final float largestAbsComponent,
		final float secondLargestAbsComponent,
		final int negativeBucket,
		final int positiveBucket
	) {
		if (secondLargestAbsComponent * 8.0F > largestAbsComponent) {
			return UNKNOWN;
		}

		boolean positive = normalComponent > 0.0F;
		return positive ? positiveBucket : negativeBucket;
	}

	private static long writeQuadIndices(final long pointer, final VertexFormat.IndexType indexType, final int baseVertex) {
		if (indexType == VertexFormat.IndexType.INT) {
			MemoryUtil.memPutInt(pointer, baseVertex);
			MemoryUtil.memPutInt(pointer + 4L, baseVertex + 1);
			MemoryUtil.memPutInt(pointer + 8L, baseVertex + 2);
			MemoryUtil.memPutInt(pointer + 12L, baseVertex + 2);
			MemoryUtil.memPutInt(pointer + 16L, baseVertex + 3);
			MemoryUtil.memPutInt(pointer + 20L, baseVertex);
			return pointer + 24L;
		}

		MemoryUtil.memPutShort(pointer, (short)baseVertex);
		MemoryUtil.memPutShort(pointer + 2L, (short)(baseVertex + 1));
		MemoryUtil.memPutShort(pointer + 4L, (short)(baseVertex + 2));
		MemoryUtil.memPutShort(pointer + 6L, (short)(baseVertex + 2));
		MemoryUtil.memPutShort(pointer + 8L, (short)(baseVertex + 3));
		MemoryUtil.memPutShort(pointer + 10L, (short)baseVertex);
		return pointer + 12L;
	}

	private static int visibleMask(final BlockPos origin, final Vec3 camera) {
		int mask = 1 << UNKNOWN;
		mask |= visibleAxisMask(camera.x, origin.getX(), origin.getX() + SECTION_SIZE, NEGATIVE_X, POSITIVE_X);
		mask |= visibleAxisMask(camera.y, origin.getY(), origin.getY() + SECTION_SIZE, NEGATIVE_Y, POSITIVE_Y);
		mask |= visibleAxisMask(camera.z, origin.getZ(), origin.getZ() + SECTION_SIZE, NEGATIVE_Z, POSITIVE_Z);
		return mask;
	}

	private static int visibleAxisMask(
		final double camera,
		final double min,
		final double max,
		final int negativeBucket,
		final int positiveBucket
	) {
		if (camera < min - CAMERA_EPSILON) {
			return 1 << negativeBucket;
		}
		if (camera > max + CAMERA_EPSILON) {
			return 1 << positiveBucket;
		}
		return (1 << negativeBucket) | (1 << positiveBucket);
	}

	public interface MeshDataRanges {
		@Nullable
		FaceRanges metallum$getTerrainFaceRanges();

		void metallum$setTerrainFaceRanges(@Nullable FaceRanges ranges);
	}

	public interface SectionMeshRanges {
		@Nullable
		FaceRanges metallum$getTerrainFaceRanges(ChunkSectionLayer layer);

		void metallum$setTerrainFaceRanges(ChunkSectionLayer layer, FaceRanges ranges);
	}

	public record FaceRanges(int[] firstIndices, int[] indexCounts, int totalIndexCount) {
		@Nullable
		private VisibleRanges visibleFrom(final BlockPos origin, final Vec3 camera) {
			int mask = visibleMask(origin, camera);
			Range[] ranges = new Range[BUCKET_COUNT];
			int rangeCount = 0;
			int visibleIndexCount = 0;
			for (int bucket : BUCKET_WRITE_ORDER) {
				if ((mask & (1 << bucket)) == 0 || this.indexCounts[bucket] == 0) {
					continue;
				}
				int firstIndex = this.firstIndices[bucket];
				int indexCount = this.indexCounts[bucket];
				if (rangeCount > 0) {
					Range previous = ranges[rangeCount - 1];
					if (previous.firstIndex + previous.indexCount == firstIndex) {
						ranges[rangeCount - 1] = new Range(previous.firstIndex, previous.indexCount + indexCount);
						visibleIndexCount += indexCount;
						continue;
					}
				}
				ranges[rangeCount++] = new Range(firstIndex, indexCount);
				visibleIndexCount += indexCount;
			}

			if (visibleIndexCount == this.totalIndexCount) {
				return null;
			}

			Range[] compactRanges = new Range[rangeCount];
			System.arraycopy(ranges, 0, compactRanges, 0, rangeCount);
			return new VisibleRanges(compactRanges, visibleIndexCount);
		}
	}

	public record Range(int firstIndex, int indexCount) {
	}

	public record VisibleRanges(Range[] ranges, int indexCount) {
	}

	private record FaceIndexBuild(ByteBufferBuilder.Result indexBuffer, FaceRanges ranges) {
	}

	private static final class DrawKey {
		private final GpuBuffer vertexBuffer;
		private final GpuBuffer indexBuffer;
		private final int firstIndex;
		private final int indexCount;
		private final int baseVertex;

		private DrawKey(
			final GpuBuffer vertexBuffer,
			final GpuBuffer indexBuffer,
			final int firstIndex,
			final int indexCount,
			final int baseVertex
		) {
			this.vertexBuffer = vertexBuffer;
			this.indexBuffer = indexBuffer;
			this.firstIndex = firstIndex;
			this.indexCount = indexCount;
			this.baseVertex = baseVertex;
		}

		@Override
		public boolean equals(final Object object) {
			if (this == object) {
				return true;
			}
			if (!(object instanceof DrawKey other)) {
				return false;
			}
			return this.vertexBuffer == other.vertexBuffer
				&& this.indexBuffer == other.indexBuffer
				&& this.firstIndex == other.firstIndex
				&& this.indexCount == other.indexCount
				&& this.baseVertex == other.baseVertex;
		}

		@Override
		public int hashCode() {
			int result = System.identityHashCode(this.vertexBuffer);
			result = 31 * result + System.identityHashCode(this.indexBuffer);
			result = 31 * result + this.firstIndex;
			result = 31 * result + this.indexCount;
			result = 31 * result + this.baseVertex;
			return result;
		}
	}
}
