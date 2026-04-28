package com.metallum.client.metal;

import com.metallum.mixin.accessor.MeshDataAccessor;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.HashMap;
import java.util.Map;
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
	private static final int NEGATIVE_Y = 1;
	private static final int POSITIVE_X = 2;
	private static final int POSITIVE_Y = 3;
	private static final int NEGATIVE_Z = 4;
	private static final int UNKNOWN = 5;
	private static final int POSITIVE_Z = 6;
	private static final int BUCKET_COUNT = 7;

	private static final Object LOCK = new Object();
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
		}
	}

	public static boolean isEnabled() {
		return enabled;
	}

	static void attachSegments(final MeshData mesh, final FaceSegments segments) {
		((MeshDataSegments)(Object)mesh).metallum$setTerrainFaceSegments(segments);
	}

	@Nullable
	static CullableFaceLayout buildCullableFaceLayout(final ChunkSectionLayer layer, final MeshData sourceMesh, final long sourceBase) {
		if (!enabled || layer != ChunkSectionLayer.SOLID || sourceMesh == null) {
			return null;
		}

		MeshData.DrawState drawState = sourceMesh.drawState();
		if (drawState.format() != DefaultVertexFormat.BLOCK || drawState.mode() != VertexFormat.Mode.QUADS) {
			return null;
		}
		if (((MeshDataAccessor)sourceMesh).metallum$getIndexBuffer() != null) {
			return null;
		}

		int vertexCount = drawState.vertexCount();
		if (vertexCount < 4 || vertexCount % 4 != 0) {
			return null;
		}

		int quadCount = vertexCount / 4;
		if (drawState.indexCount() != quadCount * 6) {
			return null;
		}

		byte[] quadBuckets = new byte[quadCount];
		int negativeXQuadCount = 0;
		int negativeYQuadCount = 0;
		int positiveXQuadCount = 0;
		int positiveYQuadCount = 0;
		int negativeZQuadCount = 0;
		int unknownQuadCount = 0;
		int positiveZQuadCount = 0;
		int classifiedQuads = 0;
		for (int quad = 0; quad < quadCount; quad++) {
			int bucket = classifyQuad(sourceBase + (long)quad * 4L * MetalTerrainVertexPacking.VANILLA_BLOCK_VERTEX_SIZE);
			quadBuckets[quad] = (byte)bucket;
			switch (bucket) {
				case NEGATIVE_X -> negativeXQuadCount++;
				case NEGATIVE_Y -> negativeYQuadCount++;
				case POSITIVE_X -> positiveXQuadCount++;
				case POSITIVE_Y -> positiveYQuadCount++;
				case NEGATIVE_Z -> negativeZQuadCount++;
				case UNKNOWN -> unknownQuadCount++;
				case POSITIVE_Z -> positiveZQuadCount++;
				default -> {
				}
			}
			if (bucket != UNKNOWN) {
				classifiedQuads++;
			}
		}

		if (classifiedQuads == 0 || classifiedQuads * MIN_CLASSIFIED_RATIO_DENOMINATOR < quadCount * MIN_CLASSIFIED_RATIO_NUMERATOR) {
			return null;
		}

		FaceSegments segments = new FaceSegments(
			negativeXQuadCount * 6,
			negativeYQuadCount * 6,
			positiveXQuadCount * 6,
			positiveYQuadCount * 6,
			negativeZQuadCount * 6,
			unknownQuadCount * 6,
			positiveZQuadCount * 6,
			quadCount * 6
		);
		return new CullableFaceLayout(quadBuckets, segments);
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
		if (!enabled || !(sectionMesh instanceof SectionMeshSegments)) {
			return;
		}

		((SectionMeshSegments)sectionMesh).metallum$setTerrainSectionOrigin(origin.immutable());
	}

	public static void registerVisibleRanges(
		final SectionMesh sectionMesh,
		final ChunkSectionLayer layer,
		final SectionRenderDispatcher.RenderSectionBufferSlice slice
	) {
		if (!enabled || layer != ChunkSectionLayer.SOLID || slice == null || !(sectionMesh instanceof SectionMeshSegments segmentsHolder)) {
			return;
		}

		FaceSegments segments = segmentsHolder.metallum$getTerrainFaceSegments();
		if (segments == null) {
			return;
		}

		SectionMesh.SectionDraw draw = sectionMesh.getSectionDraw(layer);
		if (draw == null || draw.indexType() == null) {
			return;
		}
		boolean customIndexBuffer = draw.hasCustomIndexBuffer();
		if (customIndexBuffer && slice.indexBuffer() == null) {
			return;
		}

		BlockPos origin = segmentsHolder.metallum$getTerrainSectionOrigin();
		Vec3 camera = cameraPosition;
		if (origin == null || camera == null) {
			return;
		}

		VisibleRanges visibleRanges = segments.visibleFrom(origin, camera);
		if (visibleRanges == null) {
			return;
		}

		int vertexStride = MetalTerrainVertexPacking.vertexSizeFor(layer.pipeline().getVertexFormat());
		if (vertexStride <= 0) {
			return;
		}

		GpuBuffer indexBuffer = null;
		int firstIndex = 0;
		if (customIndexBuffer) {
			indexBuffer = slice.indexBuffer();
			firstIndex = Math.toIntExact(slice.indexBufferOffset() / draw.indexType().bytes);
		}
		int baseVertex = Math.toIntExact(slice.vertexBufferOffset() / vertexStride);
		DrawKey key = new DrawKey(slice.vertexBuffer(), indexBuffer, firstIndex, draw.indexCount(), baseVertex);
		synchronized (LOCK) {
			VISIBLE_DRAW_RANGES.put(key, visibleRanges);
		}
	}

	@Nullable
	public static VisibleRanges takeVisibleRanges(final RenderPass.Draw<?> draw, @Nullable final GpuBuffer resolvedIndexBuffer) {
		if (!enabled) {
			return null;
		}

		DrawKey key = new DrawKey(draw.vertexBuffer(), resolvedIndexBuffer, draw.firstIndex(), draw.indexCount(), draw.baseVertex());
		synchronized (LOCK) {
			VisibleRanges visibleRanges = VISIBLE_DRAW_RANGES.remove(key);
			if (visibleRanges == null && draw.indexBuffer() == null) {
				visibleRanges = VISIBLE_DRAW_RANGES.remove(new DrawKey(draw.vertexBuffer(), null, draw.firstIndex(), draw.indexCount(), draw.baseVertex()));
			}
			return visibleRanges;
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

	public interface MeshDataSegments {
		@Nullable
		FaceSegments metallum$getTerrainFaceSegments();

		void metallum$setTerrainFaceSegments(@Nullable FaceSegments segments);
	}

	public interface SectionMeshSegments {
		@Nullable
		FaceSegments metallum$getTerrainFaceSegments();

		void metallum$setTerrainFaceSegments(FaceSegments segments);

		@Nullable
		BlockPos metallum$getTerrainSectionOrigin();

		void metallum$setTerrainSectionOrigin(BlockPos origin);
	}

	public record FaceSegments(
		int negativeXIndexCount,
		int negativeYIndexCount,
		int positiveXIndexCount,
		int positiveYIndexCount,
		int negativeZIndexCount,
		int unknownIndexCount,
		int positiveZIndexCount,
		int totalIndexCount
	) {
		@Nullable
		private VisibleRanges visibleFrom(final BlockPos origin, final Vec3 camera) {
			int mask = visibleMask(origin, camera);
			long range0 = 0L;
			long range1 = 0L;
			long range2 = 0L;
			long range3 = 0L;
			int rangeCount = 0;
			int activeFirstIndex = 0;
			int activeIndexCount = 0;
			int indexCursor = 0;
			int visibleIndexCount = 0;

			for (int bucket = 0; bucket < BUCKET_COUNT; bucket++) {
				int indexCount = this.indexCount(bucket);
				boolean visible = (mask & (1 << bucket)) != 0;
				if (indexCount > 0 && visible) {
					if (activeIndexCount == 0) {
						activeFirstIndex = indexCursor;
					}
					activeIndexCount += indexCount;
					visibleIndexCount += indexCount;
				} else if (indexCount > 0 && activeIndexCount > 0) {
					switch (rangeCount++) {
						case 0 -> range0 = VisibleRanges.pack(activeFirstIndex, activeIndexCount);
						case 1 -> range1 = VisibleRanges.pack(activeFirstIndex, activeIndexCount);
						case 2 -> range2 = VisibleRanges.pack(activeFirstIndex, activeIndexCount);
						case 3 -> range3 = VisibleRanges.pack(activeFirstIndex, activeIndexCount);
						default -> throw new IllegalStateException("Too many terrain face ranges");
					}
					activeIndexCount = 0;
				}

				indexCursor += indexCount;
			}

			if (activeIndexCount > 0) {
				switch (rangeCount++) {
					case 0 -> range0 = VisibleRanges.pack(activeFirstIndex, activeIndexCount);
					case 1 -> range1 = VisibleRanges.pack(activeFirstIndex, activeIndexCount);
					case 2 -> range2 = VisibleRanges.pack(activeFirstIndex, activeIndexCount);
					case 3 -> range3 = VisibleRanges.pack(activeFirstIndex, activeIndexCount);
					default -> throw new IllegalStateException("Too many terrain face ranges");
				}
			}

			if (visibleIndexCount == this.totalIndexCount) {
				return null;
			}

			return new VisibleRanges(rangeCount, range0, range1, range2, range3);
		}

		private int indexCount(final int bucket) {
			return switch (bucket) {
				case NEGATIVE_X -> this.negativeXIndexCount;
				case NEGATIVE_Y -> this.negativeYIndexCount;
				case POSITIVE_X -> this.positiveXIndexCount;
				case POSITIVE_Y -> this.positiveYIndexCount;
				case NEGATIVE_Z -> this.negativeZIndexCount;
				case UNKNOWN -> this.unknownIndexCount;
				case POSITIVE_Z -> this.positiveZIndexCount;
				default -> 0;
			};
		}
	}

	public record VisibleRanges(int rangeCount, long range0, long range1, long range2, long range3) {
		private static long pack(final int firstIndex, final int indexCount) {
			return ((long)firstIndex << Integer.SIZE) | Integer.toUnsignedLong(indexCount);
		}

		public int firstIndex(final int range) {
			return (int)(this.range(range) >> Integer.SIZE);
		}

		public int indexCount(final int range) {
			return (int)this.range(range);
		}

		private long range(final int range) {
			return switch (range) {
				case 0 -> this.range0;
				case 1 -> this.range1;
				case 2 -> this.range2;
				case 3 -> this.range3;
				default -> throw new IndexOutOfBoundsException(range);
			};
		}
	}

	static final class CullableFaceLayout {
		private final byte[] quadBuckets;
		private final FaceSegments segments;
		private int negativeXVertexCursor;
		private int negativeYVertexCursor;
		private int positiveXVertexCursor;
		private int positiveYVertexCursor;
		private int negativeZVertexCursor;
		private int unknownVertexCursor;
		private int positiveZVertexCursor;

		private CullableFaceLayout(final byte[] quadBuckets, final FaceSegments segments) {
			this.quadBuckets = quadBuckets;
			this.segments = segments;
			int vertexCursor = 0;
			this.negativeXVertexCursor = vertexCursor;
			vertexCursor += indexCountToVertexCount(segments.negativeXIndexCount());
			this.negativeYVertexCursor = vertexCursor;
			vertexCursor += indexCountToVertexCount(segments.negativeYIndexCount());
			this.positiveXVertexCursor = vertexCursor;
			vertexCursor += indexCountToVertexCount(segments.positiveXIndexCount());
			this.positiveYVertexCursor = vertexCursor;
			vertexCursor += indexCountToVertexCount(segments.positiveYIndexCount());
			this.negativeZVertexCursor = vertexCursor;
			vertexCursor += indexCountToVertexCount(segments.negativeZIndexCount());
			this.unknownVertexCursor = vertexCursor;
			vertexCursor += indexCountToVertexCount(segments.unknownIndexCount());
			this.positiveZVertexCursor = vertexCursor;
		}

		int quadCount() {
			return this.quadBuckets.length;
		}

		int takeDestinationVertex(final int quad) {
			return switch (this.quadBuckets[quad] & 0xFF) {
				case NEGATIVE_X -> {
					int vertex = this.negativeXVertexCursor;
					this.negativeXVertexCursor += 4;
					yield vertex;
				}
				case NEGATIVE_Y -> {
					int vertex = this.negativeYVertexCursor;
					this.negativeYVertexCursor += 4;
					yield vertex;
				}
				case POSITIVE_X -> {
					int vertex = this.positiveXVertexCursor;
					this.positiveXVertexCursor += 4;
					yield vertex;
				}
				case POSITIVE_Y -> {
					int vertex = this.positiveYVertexCursor;
					this.positiveYVertexCursor += 4;
					yield vertex;
				}
				case NEGATIVE_Z -> {
					int vertex = this.negativeZVertexCursor;
					this.negativeZVertexCursor += 4;
					yield vertex;
				}
				case UNKNOWN -> {
					int vertex = this.unknownVertexCursor;
					this.unknownVertexCursor += 4;
					yield vertex;
				}
				case POSITIVE_Z -> {
					int vertex = this.positiveZVertexCursor;
					this.positiveZVertexCursor += 4;
					yield vertex;
				}
				default -> throw new IllegalStateException("Unknown terrain face bucket");
			};
		}

		FaceSegments segments() {
			return this.segments;
		}

		private static int indexCountToVertexCount(final int indexCount) {
			return indexCount / 6 * 4;
		}
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
