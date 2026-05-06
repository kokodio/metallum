package com.metallum.client.metal.render;

import com.metallum.client.metal.optimization.MetalTerrainVertexPacking;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.sun.jna.Pointer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

@Environment(EnvType.CLIENT)
final class MetalCompiledRenderPipeline implements CompiledRenderPipeline {
	private static final int MAX_METAL_VERTEX_BUFFER_SLOT = 30;

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
	private final String vertexMsl;
	private final String fragmentMsl;
	private final String vertexEntryPoint;
	private final String fragmentEntryPoint;
	private final List<ResourceBinding> resources;
	private final Map<String, ResourceBinding> resourcesByName;
	private final long[] vertexAttributeFormats;
	private final long[] vertexAttributeOffsets;
	private final long[] vertexAttributeBufferSlots;
	private final long[] vertexBindingBufferSlots;
	private final long[] vertexBindingStrides;
	private final long[] vertexBindingStepRates;
	private final int[] metalSlotByVertexBinding;
	private final long depthCompareOp;
	private final int depthWrite;
	private final double depthBiasScaleFactor;
	private final double depthBiasConstant;
	private final Map<PipelineVariantKey, Pointer> nativePipelines = new ConcurrentHashMap<>();

	MetalCompiledRenderPipeline(
		final RenderPipeline info,
		final String vertexMsl,
		final String fragmentMsl,
		final String vertexEntryPoint,
		final String fragmentEntryPoint,
		final List<ResourceBinding> resources
	) {
		this.info = info;
		this.vertexMsl = vertexMsl;
		this.fragmentMsl = fragmentMsl;
		this.vertexEntryPoint = vertexEntryPoint;
		this.fragmentEntryPoint = fragmentEntryPoint;
		this.resources = resources;
		this.resourcesByName = resources.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(ResourceBinding::name, binding -> binding));
		VertexInputState vertexInput = buildVertexInputState(info, firstAvailableVertexBufferSlot(resources));
		this.vertexAttributeFormats = vertexInput.attributeFormats();
		this.vertexAttributeOffsets = vertexInput.attributeOffsets();
		this.vertexAttributeBufferSlots = vertexInput.attributeBufferSlots();
		this.vertexBindingBufferSlots = vertexInput.bindingBufferSlots();
		this.vertexBindingStrides = vertexInput.bindingStrides();
		this.vertexBindingStepRates = vertexInput.bindingStepRates();
		this.metalSlotByVertexBinding = vertexInput.metalSlotByVertexBinding();
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
	}

	@Override
	public boolean isValid() {
		return true;
	}

	RenderPipeline info() {
		return this.info;
	}

	List<ResourceBinding> resources() {
		return this.resources;
	}

	@Nullable
	ResourceBinding resource(final String name) {
		return this.resourcesByName.get(name);
	}

	int metalSlotForVertexBinding(final int binding) {
		return binding >= 0 && binding < this.metalSlotByVertexBinding.length ? this.metalSlotByVertexBinding[binding] : -1;
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

	private static VertexInputState buildVertexInputState(final RenderPipeline pipeline, final int firstMetalVertexBufferSlot) {
		VertexFormat[] bindings = pipeline.getVertexFormatBindings();
		int[] metalSlotByVertexBinding = new int[RenderPass.MAX_VERTEX_BUFFERS];
		Arrays.fill(metalSlotByVertexBinding, -1);
		List<Long> attributeFormats = new ArrayList<>();
		List<Long> attributeOffsets = new ArrayList<>();
		List<Long> attributeBufferSlots = new ArrayList<>();
		List<Long> bindingBufferSlots = new ArrayList<>();
		List<Long> bindingStrides = new ArrayList<>();
		List<Long> bindingStepRates = new ArrayList<>();
		int nextMetalSlot = firstMetalVertexBufferSlot;
		boolean packedTerrain = MetalTerrainVertexPacking.isPackedTerrainPipeline(pipeline.getLocation().toString());

		for (int i = 0; i < bindings.length; i++) {
			VertexFormat binding = bindings[i];
			if (binding == null || binding.getElements().isEmpty()) {
				continue;
			}

			if (nextMetalSlot > MAX_METAL_VERTEX_BUFFER_SLOT) {
				throw new UnsupportedOperationException("Metal vertex/input buffer slots exceeded for " + pipeline.getLocation());
			}

			int metalSlot = nextMetalSlot++;
			metalSlotByVertexBinding[i] = metalSlot;
			bindingBufferSlots.add((long)metalSlot);
			bindingStrides.add((long)(packedTerrain && i == 0 ? MetalTerrainVertexPacking.PACKED_TERRAIN_VERTEX_SIZE : binding.getVertexSize()));
			bindingStepRates.add((long)binding.getStepRate());
			if (packedTerrain && i == 0) {
				addPackedTerrainAttributes(attributeFormats, attributeOffsets, attributeBufferSlots, metalSlot);
			} else {
				addVertexFormatAttributes(attributeFormats, attributeOffsets, attributeBufferSlots, binding, metalSlot);
			}
		}

		return new VertexInputState(
			toLongArray(attributeFormats),
			toLongArray(attributeOffsets),
			toLongArray(attributeBufferSlots),
			toLongArray(bindingBufferSlots),
			toLongArray(bindingStrides),
			toLongArray(bindingStepRates),
			metalSlotByVertexBinding
		);
	}

	private static int firstAvailableVertexBufferSlot(final List<ResourceBinding> resources) {
		int maxVertexBufferBinding = -1;
		for (ResourceBinding resource : resources) {
			if (resource.kind() == ResourceKind.UNIFORM_BUFFER && (resource.stageMask() & STAGE_VERTEX) != 0) {
				maxVertexBufferBinding = Math.max(maxVertexBufferBinding, resource.bindingIndex());
			}
		}
		return maxVertexBufferBinding + 1;
	}

	private static void addPackedTerrainAttributes(
		final List<Long> attributeFormats,
		final List<Long> attributeOffsets,
		final List<Long> attributeBufferSlots,
		final int metalSlot
	) {
		long[] formats = MetalTerrainVertexPacking.packedAttributeFormats();
		long[] offsets = MetalTerrainVertexPacking.packedAttributeOffsets();
		for (int i = 0; i < formats.length; i++) {
			attributeFormats.add(formats[i]);
			attributeOffsets.add(offsets[i]);
			attributeBufferSlots.add((long)metalSlot);
		}
	}

	private static void addVertexFormatAttributes(
		final List<Long> attributeFormats,
		final List<Long> attributeOffsets,
		final List<Long> attributeBufferSlots,
		final VertexFormat binding,
		final int metalSlot
	) {
		for (VertexFormatElement element : binding.getElements()) {
			long formatCode = MetalPipelineSupport.vertexAttributeFormatCode(element.format());
			if (formatCode == 0L) {
				throw new IllegalStateException("Unsupported vertex attribute format: " + element.format());
			}
			attributeFormats.add(formatCode);
			attributeOffsets.add((long)element.offset());
			attributeBufferSlots.add((long)metalSlot);
		}
	}

	private static long[] toLongArray(final List<Long> values) {
		long[] result = new long[values.size()];
		for (int i = 0; i < values.size(); i++) {
			result[i] = values.get(i);
		}
		return result;
	}

	@Nullable
	Pointer getOrCreateNativePipeline(final MetalDevice device, final long colorFormat, final long depthFormat, final long stencilFormat) {
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
			this.vertexAttributeFormats,
			this.vertexAttributeOffsets,
			this.vertexAttributeBufferSlots,
			this.vertexAttributeFormats.length,
			this.vertexBindingBufferSlots,
			this.vertexBindingStrides,
			this.vertexBindingStepRates,
			this.vertexBindingBufferSlots.length,
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

	private record VertexInputState(
		long[] attributeFormats,
		long[] attributeOffsets,
		long[] attributeBufferSlots,
		long[] bindingBufferSlots,
		long[] bindingStrides,
		long[] bindingStepRates,
		int[] metalSlotByVertexBinding
	) {
	}
}
