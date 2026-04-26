package com.metallum.client.metal;

import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.GpuFormat;
import com.sun.jna.Pointer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

@Environment(EnvType.CLIENT)
final class MetalCompiledRenderPipeline implements CompiledRenderPipeline {
	enum ResourceKind {
		UNIFORM_BUFFER,
		SAMPLED_IMAGE,
		TEXEL_BUFFER
	}

	static final int STAGE_VERTEX = 1;
	static final int STAGE_FRAGMENT = 2;
	static final int STAGE_ALL = STAGE_VERTEX | STAGE_FRAGMENT;

	record ResourceBinding(ResourceKind kind, String name, int bindingIndex, int stageMask, @Nullable GpuFormat texelBufferFormat) {
	}

	private final RenderPipeline info;
	private final boolean valid;
	@Nullable
	private final String vertexMsl;
	@Nullable
	private final String fragmentMsl;
	@Nullable
	private final String vertexEntryPoint;
	@Nullable
	private final String fragmentEntryPoint;
	private final boolean flipVertexY;
	private final List<ResourceBinding> resources;
	private final Map<String, ResourceBinding> resourcesByName;
	private final int bufferBindingCount;
	private final int textureBindingCount;
	private final long[] vertexAttributeFormats;
	private final long[] vertexAttributeOffsets;
	private final long vertexStride;
	private final long depthCompareOp;
	private final int depthWrite;
	private final double depthBiasScaleFactor;
	private final double depthBiasConstant;
	private final Map<PipelineVariantKey, Pointer> nativePipelines = new ConcurrentHashMap<>();

	MetalCompiledRenderPipeline(
		final RenderPipeline info,
		final boolean valid,
		@Nullable final String vertexMsl,
		@Nullable final String fragmentMsl,
		@Nullable final String vertexEntryPoint,
		@Nullable final String fragmentEntryPoint,
		final boolean flipVertexY,
		final List<ResourceBinding> resources
	) {
		this.info = info;
		this.valid = valid;
		this.vertexMsl = vertexMsl;
		this.fragmentMsl = fragmentMsl;
		this.vertexEntryPoint = vertexEntryPoint;
		this.fragmentEntryPoint = fragmentEntryPoint;
		this.flipVertexY = flipVertexY;
		this.resources = resources;
		this.resourcesByName = resources.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(ResourceBinding::name, binding -> binding));
		int maxBufferBinding = -1;
		int maxTextureBinding = -1;
		for (ResourceBinding resource : resources) {
			if (resource.kind() == ResourceKind.SAMPLED_IMAGE || resource.kind() == ResourceKind.TEXEL_BUFFER) {
				maxTextureBinding = Math.max(maxTextureBinding, resource.bindingIndex());
			} else {
				maxBufferBinding = Math.max(maxBufferBinding, resource.bindingIndex());
			}
		}
		this.bufferBindingCount = maxBufferBinding + 1;
		this.textureBindingCount = maxTextureBinding + 1;
		if (valid) {
			if (MetalTerrainVertexPacking.isPackedTerrainPipeline(info.getLocation().toString())) {
				this.vertexAttributeFormats = MetalTerrainVertexPacking.packedAttributeFormats();
				this.vertexAttributeOffsets = MetalTerrainVertexPacking.packedAttributeOffsets();
				this.vertexStride = MetalTerrainVertexPacking.PACKED_TERRAIN_VERTEX_SIZE;
			} else {
				this.vertexAttributeFormats = MetalPipelineSupport.vertexAttributeFormats(info.getVertexFormat());
				this.vertexAttributeOffsets = MetalPipelineSupport.vertexAttributeOffsets(info.getVertexFormat());
				this.vertexStride = info.getVertexFormat().getVertexSize();
			}
			var depthStencilState = info.getDepthStencilState();
			if (depthStencilState == null) {
				this.depthCompareOp = 1L;
				this.depthWrite = 0;
				this.depthBiasScaleFactor = 0.0;
				this.depthBiasConstant = 0.0;
			} else {
				this.depthCompareOp = MetalPipelineSupport.toCompareOpCode(depthStencilState.depthTest());
				this.depthWrite = depthStencilState.writeDepth() ? 1 : 0;
				this.depthBiasScaleFactor = depthStencilState.depthBiasScaleFactor();
				this.depthBiasConstant = depthStencilState.depthBiasConstant();
			}
		} else {
			this.vertexAttributeFormats = new long[0];
			this.vertexAttributeOffsets = new long[0];
			this.vertexStride = 0L;
			this.depthCompareOp = 1L;
			this.depthWrite = 0;
			this.depthBiasScaleFactor = 0.0;
			this.depthBiasConstant = 0.0;
		}
	}

	static MetalCompiledRenderPipeline invalid(final RenderPipeline info) {
		return new MetalCompiledRenderPipeline(info, false, null, null, null, null, false, List.of());
	}

	@Override
	public boolean isValid() {
		return this.valid;
	}

	RenderPipeline info() {
		return this.info;
	}

	@Nullable
	String vertexMsl() {
		return this.vertexMsl;
	}

	@Nullable
	String fragmentMsl() {
		return this.fragmentMsl;
	}

	@Nullable
	String vertexEntryPoint() {
		return this.vertexEntryPoint;
	}

	@Nullable
	String fragmentEntryPoint() {
		return this.fragmentEntryPoint;
	}

	boolean flipVertexY() {
		return this.flipVertexY;
	}

	List<ResourceBinding> resources() {
		return this.resources;
	}

	@Nullable
	ResourceBinding resource(final String name) {
		return this.resourcesByName.get(name);
	}

	int bufferBindingCount() {
		return this.bufferBindingCount;
	}

	int textureBindingCount() {
		return this.textureBindingCount;
	}

	long[] vertexAttributeFormats() {
		return this.vertexAttributeFormats;
	}

	long[] vertexAttributeOffsets() {
		return this.vertexAttributeOffsets;
	}

	long vertexStride() {
		return this.vertexStride;
	}

	long depthCompareOp() {
		return this.depthCompareOp;
	}

	int depthWrite() {
		return this.depthWrite;
	}

	double depthBiasScaleFactor() {
		return this.depthBiasScaleFactor;
	}

	double depthBiasConstant() {
		return this.depthBiasConstant;
	}

	int nativePipelineCount() {
		return this.nativePipelines.size();
	}

	void releaseNativePipelines(final MetalDevice device) {
		if (this.nativePipelines.isEmpty()) {
			return;
		}

		for (Pointer pointer : this.nativePipelines.values()) {
			if (!MetalProbe.isNullPointer(pointer)) {
				device.queueResourceRelease(pointer);
			}
		}
		this.nativePipelines.clear();
	}

	@Nullable
	Pointer getOrCreateNativePipeline(final MetalDevice device, final long colorFormat, final long depthFormat, final long stencilFormat) {
		if (!this.valid || this.vertexMsl == null || this.fragmentMsl == null || this.vertexEntryPoint == null || this.fragmentEntryPoint == null) {
			return null;
		}

		PipelineVariantKey key = new PipelineVariantKey(colorFormat, depthFormat, stencilFormat);
		Pointer cached = this.nativePipelines.get(key);
		if (!MetalProbe.isNullPointer(cached)) {
			return cached;
		}

		var colorTarget = this.info.getColorTargetState();
		var blendFunction = colorTarget.blendFunction();
		Pointer created = MetalNativeBridge.INSTANCE.metallum_create_render_pipeline(
			device.metalDevicePointer(),
			this.vertexMsl,
			this.fragmentMsl,
			this.vertexEntryPoint,
			this.fragmentEntryPoint,
			colorFormat,
			depthFormat,
			stencilFormat,
			this.vertexStride,
			this.vertexAttributeFormats,
			this.vertexAttributeOffsets,
			this.vertexAttributeFormats.length,
			blendFunction.isPresent() ? 1 : 0,
			blendFunction.isPresent() ? MetalPipelineSupport.toBlendFactorCode(blendFunction.get().color().sourceFactor()) : 0L,
			blendFunction.isPresent() ? MetalPipelineSupport.toBlendFactorCode(blendFunction.get().color().destFactor()) : 0L,
			blendFunction.isPresent() ? MetalPipelineSupport.toBlendOpCode(blendFunction.get().color().op()) : 0L,
			blendFunction.isPresent() ? MetalPipelineSupport.toBlendFactorCode(blendFunction.get().alpha().sourceFactor()) : 0L,
			blendFunction.isPresent() ? MetalPipelineSupport.toBlendFactorCode(blendFunction.get().alpha().destFactor()) : 0L,
			blendFunction.isPresent() ? MetalPipelineSupport.toBlendOpCode(blendFunction.get().alpha().op()) : 0L,
			colorTarget.writeMask()
		);
		if (MetalProbe.isNullPointer(created)) {
			return null;
		}

		this.nativePipelines.put(key, created);
		return created;
	}

	private record PipelineVariantKey(long colorFormat, long depthFormat, long stencilFormat) {
	}
}
