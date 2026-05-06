package com.metallum.client.metal.render;

import com.metallum.client.metal.optimization.MetalTerrainFaceCulling;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.PolygonMode;
import com.mojang.blaze3d.systems.GpuQueryPool;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.systems.ScissorState;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.logging.LogUtils;
import com.sun.jna.Pointer;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.Supplier;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.SharedConstants;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

@Environment(EnvType.CLIENT)
final class MetalRenderPass implements RenderPassBackend {
	private static final Logger LOGGER = LogUtils.getLogger();
	static final boolean VALIDATION = SharedConstants.IS_RUNNING_IN_IDE;
	static final int MAX_VERTEX_BUFFERS = RenderPass.MAX_VERTEX_BUFFERS;
	private final MetalDevice device;
	private final MetalCommandEncoder encoder;
	@Nullable
	private final String label;
	private final GpuTextureView colorTexture;
	@Nullable
	private final GpuTextureView depthTexture;
	private final RenderPass.RenderArea renderArea;
	private final Optional<MetalCommandEncoder.ClearColor> clearColor;
	private final OptionalDouble clearDepth;
	private final ScissorState scissorState = new ScissorState();
	private final GpuBufferSlice[] vertexBuffers = new GpuBufferSlice[MAX_VERTEX_BUFFERS];
	private final HashMap<String, GpuBufferSlice> uniforms = new HashMap<>();
	private final HashMap<String, TextureViewAndSampler> samplers = new HashMap<>();
	private final Set<MetalCompiledRenderPipeline.ResourceBinding> dirtyBindings = new HashSet<>();
	@Nullable
	private RenderPipeline pipeline;
	@Nullable
	private MetalCompiledRenderPipeline compiledPipeline;
	@Nullable
	private GpuBuffer indexBuffer;
	private IndexType indexType = IndexType.SHORT;
	@Nullable
	private Pointer nativeRenderPass;
	@Nullable
	private Pointer nativePipeline;
	private boolean pipelineDirty = true;
	private boolean depthStateDirty = true;
	private boolean vertexBuffersDirty = true;
	private boolean scissorDirty = true;

	MetalRenderPass(
		final MetalDevice device,
		final MetalCommandEncoder encoder,
		final Supplier<String> label,
		final GpuTextureView colorTexture,
		@Nullable final GpuTextureView depthTexture,
		final RenderPass.RenderArea renderArea,
		final Optional<MetalCommandEncoder.ClearColor> clearColor,
		final OptionalDouble clearDepth
	) {
		this.device = device;
		this.encoder = encoder;
		this.label = device.useLabels() ? label.get() : null;
		this.colorTexture = colorTexture;
		this.depthTexture = depthTexture;
		this.renderArea = renderArea;
		this.clearColor = clearColor;
		this.clearDepth = clearDepth;
	}

	@Override
	public void pushDebugGroup(final Supplier<String> label) {
	}

	@Override
	public void popDebugGroup() {
	}

	@Override
	public void setPipeline(final RenderPipeline pipeline) {
		if (this.pipeline != pipeline) {
			this.dirtyBindings.clear();
			this.pipelineDirty = true;
			this.depthStateDirty = true;
			this.vertexBuffersDirty = true;
		}

		MetalCompiledRenderPipeline compiled = this.device.getOrCompilePipeline(pipeline);
		this.pipeline = pipeline;
		this.compiledPipeline = compiled;
	}

	@Override
	public void bindTexture(final String name, @Nullable final GpuTextureView textureView, @Nullable final GpuSampler sampler) {
		TextureViewAndSampler oldValue = this.samplers.get(name);
		TextureViewAndSampler newValue = textureView == null || sampler == null ? null : new TextureViewAndSampler(textureView, sampler);

		if (oldValue == null ? newValue != null : !oldValue.equals(newValue)) {
			if (newValue == null) {
				this.samplers.remove(name);
			} else {
				this.samplers.put(name, newValue);
			}
			this.markBindingDirty(name);
		}
	}

	@Override
	public void setUniform(final String name, final GpuBuffer value) {
		this.setUniform(name, value.slice());
	}

	@Override
	public void setUniform(final String name, final GpuBufferSlice value) {
		GpuBufferSlice oldValue = this.uniforms.put(name, value);
		if (!sameSlice(oldValue, value)) {
			this.markBindingDirty(name);
		}
	}

	@Override
	public void enableScissor(final int x, final int y, final int width, final int height) {
		if (this.scissorState.enabled()
			&& this.scissorState.x() == x
			&& this.scissorState.y() == y
			&& this.scissorState.width() == width
			&& this.scissorState.height() == height) {
			return;
		}
		this.scissorState.enable(x, y, width, height);
		this.scissorDirty = true;
	}

	@Override
	public void disableScissor() {
		if (!this.scissorState.enabled()) {
			return;
		}
		this.scissorState.disable();
		this.scissorDirty = true;
	}

	@Override
	public void setVertexBuffer(final int slot, @Nullable final GpuBufferSlice vertexBuffer) {
		if (slot < 0 || slot >= MAX_VERTEX_BUFFERS) {
			throw new IllegalArgumentException("Unsupported Metal vertex buffer slot: " + slot);
		}

		if (!sameNullableSlice(this.vertexBuffers[slot], vertexBuffer)) {
			this.vertexBuffers[slot] = vertexBuffer;
			this.vertexBuffersDirty = true;
		}
	}

	@Override
	public void setIndexBuffer(@Nullable final GpuBuffer indexBuffer, final IndexType indexType) {
		if (this.indexBuffer != indexBuffer || this.indexType != indexType) {
			this.indexBuffer = indexBuffer;
			this.indexType = indexType;
		}
	}

	@Override
	public void drawIndexed(final int baseVertex, final int firstIndex, final int indexCount, final int instanceCount) {
		MetalGpuTexture colorAttachment = MetalCommandEncoder.castTexture(this.colorTexture.texture());
		MetalGpuBuffer nativeIndexBuffer = this.resolveIndexBuffer();
		Pointer renderPass = this.getOrCreateNativeRenderPass();
		if (MetalProbe.isNullPointer(renderPass)) {
			throw new IllegalStateException("Native render pass is unavailable");
		}

		this.bindDrawState(renderPass, colorAttachment);
		this.drawIndexedNative(renderPass, nativeIndexBuffer, firstIndex, indexCount, baseVertex, instanceCount, this.indexType);
	}

	@Override
	public <T> void drawMultipleIndexed(
		final Collection<RenderPass.Draw<T>> draws,
		@Nullable final GpuBuffer defaultIndexBuffer,
		@Nullable final IndexType defaultIndexType,
		final Collection<String> dynamicUniforms,
		final T uniformArgument
	) {
		IndexType fallbackIndexType = defaultIndexType == null ? IndexType.SHORT : defaultIndexType;
		MetalGpuTexture colorAttachment = MetalCommandEncoder.castTexture(this.colorTexture.texture());
		Pointer renderPass = this.getOrCreateNativeRenderPass();
		if (MetalProbe.isNullPointer(renderPass)) {
			throw new IllegalStateException("Native render pass is unavailable");
		}

		for (RenderPass.Draw<T> draw : draws) {
			IndexType drawIndexType = draw.indexType() == null ? fallbackIndexType : draw.indexType();
			GpuBuffer currentIndexBuffer = draw.indexBuffer() == null ? defaultIndexBuffer : draw.indexBuffer();

			this.setIndexBuffer(currentIndexBuffer, drawIndexType);
			this.setVertexBuffer(draw.slot(), draw.vertexBuffer().slice());

			if (draw.uniformUploaderConsumer() != null) {
				draw.uniformUploaderConsumer().accept(uniformArgument, this::setUniform);
			}

			if (this.needsDrawStateBinding()) {
				this.bindDrawState(renderPass, colorAttachment);
			}
			MetalGpuBuffer nativeIndexBuffer = this.resolveIndexBuffer();
			MetalTerrainFaceCulling.VisibleRanges visibleRanges = MetalTerrainFaceCulling.takeVisibleRanges(draw, currentIndexBuffer);
			if (visibleRanges != null) {
				for (int range = 0; range < visibleRanges.rangeCount(); range++) {
					int indexCount = visibleRanges.indexCount(range);
					if (indexCount > 0) {
						this.drawIndexedNative(renderPass, nativeIndexBuffer, draw.firstIndex() + visibleRanges.firstIndex(range), indexCount, draw.baseVertex(), 1, drawIndexType);
					}
				}
				continue;
			}
			this.drawIndexedNative(renderPass, nativeIndexBuffer, draw.firstIndex(), draw.indexCount(), draw.baseVertex(), 1, drawIndexType);
		}
	}

	@Override
	public void draw(final int firstVertex, final int vertexCount) {
		MetalGpuTexture colorAttachment = MetalCommandEncoder.castTexture(this.colorTexture.texture());
		PrimitiveTopology primitiveTopology = this.primitiveTopology();
		long primitiveType = MetalPipelineSupport.primitiveTypeCode(primitiveTopology);
		if (primitiveType < 0L) {
			throw new IllegalStateException("Unsupported primitive type: " + primitiveTopology);
		}

		Pointer renderPass = this.getOrCreateNativeRenderPass();
		if (MetalProbe.isNullPointer(renderPass)) {
			throw new IllegalStateException("Native render pass is unavailable");
		}

		this.bindDrawState(renderPass, colorAttachment);

		int result = primitiveType == MetalPipelineSupport.TRIANGLE_FAN_PRIMITIVE
			? this.drawTriangleFanNative(renderPass, firstVertex, vertexCount, 1)
			: MetalNativeBridge.INSTANCE.metallum_render_pass_draw(renderPass, primitiveType, firstVertex, vertexCount, 1);
		if (result != 0) {
			throw new IllegalStateException("Native draw failed with code " + result);
		}
	}

	@Override
	public void writeTimestamp(final GpuQueryPool pool, final int index) {
		if (pool instanceof MetalGpuQueryPool metalPool && index >= 0 && index < pool.size()) {
			metalPool.setValue(index, this.device.getTimestampNow());
		}
	}

	long colorAttachmentFormat() {
		return ((MetalGpuTexture)this.colorTexture.texture()).mtlPixelFormat();
	}

	long depthAttachmentFormat() {
		if (this.depthTexture == null) {
			return 0L;
		}
		return ((MetalGpuTexture)this.depthTexture.texture()).mtlPixelFormat();
	}

	long stencilAttachmentFormat() {
		if (this.depthTexture == null) {
			return 0L;
		}
		return ((MetalGpuTexture)this.depthTexture.texture()).mtlStencilPixelFormat();
	}

	@Nullable
	Pointer getOrCreateNativeRenderPass() {
		if (!MetalProbe.isNullPointer(this.nativeRenderPass)) {
			return this.nativeRenderPass;
		}

		for (TextureViewAndSampler textureBinding : this.samplers.values()) {
			this.encoder.flushPendingTextureViewClear(textureBinding.textureView());
		}

		MetalGpuTextureView colorTextureView = (MetalGpuTextureView)this.colorTexture;
		MetalGpuTextureView depthTextureView = this.depthTexture == null ? null : (MetalGpuTextureView)this.depthTexture;
		MetalCommandEncoder.ClearColor colorClear = this.clearColor.orElse(MetalCommandEncoder.ClearColor.TRANSPARENT);
		Pointer handle = MetalNativeBridge.INSTANCE.metallum_begin_render_pass(
			this.device.commandQueue(),
			colorTextureView.nativeHandle(),
			depthTextureView == null ? null : depthTextureView.nativeHandle(),
			this.colorTexture.getWidth(0),
			this.colorTexture.getHeight(0),
			this.clearColor.isPresent() ? 1 : 0,
			colorClear.red(),
			colorClear.green(),
			colorClear.blue(),
			colorClear.alpha(),
			this.clearDepth.isPresent() ? 1 : 0,
			this.clearDepth.orElse(1.0),
			this.label
		);
		if (MetalProbe.isNullPointer(handle)) {
			LOGGER.warn("Failed to begin Metal render pass '{}'", this.label);
			return null;
		}

		this.nativeRenderPass = handle;
		return handle;
	}

	void end() {
		if (MetalProbe.isNullPointer(this.nativeRenderPass) && (this.clearColor.isPresent() || this.clearDepth.isPresent())) {
			if (this.encoder.deferRenderPassClear(this.colorTexture, this.clearColor, this.depthTexture, this.clearDepth)) {
				return;
			}
			this.getOrCreateNativeRenderPass();
		}
		if (MetalProbe.isNullPointer(this.nativeRenderPass)) {
			return;
		}

		int result = MetalNativeBridge.INSTANCE.metallum_end_render_pass(this.nativeRenderPass);
		if (result != 0) {
			LOGGER.warn("Failed to end Metal render pass '{}' with code {}", this.label, result);
		}
		this.nativeRenderPass = null;
		this.nativePipeline = null;
	}

	private void pushVertexBuffers(final Pointer renderPass) {
		if (this.compiledPipeline == null) {
			throw new IllegalStateException("Pipeline is missing");
		}

		for (int slot = 0; slot < MAX_VERTEX_BUFFERS; slot++) {
			int metalSlot = this.compiledPipeline.metalSlotForVertexBinding(slot);
			if (metalSlot < 0) {
				continue;
			}

			GpuBufferSlice vertexBuffer = this.vertexBuffers[slot];
			if (vertexBuffer == null) {
				throw new IllegalStateException("Missing vertex buffer at slot " + slot);
			}
			if (VALIDATION && vertexBuffer.buffer().isClosed()) {
				throw new IllegalStateException("Vertex buffer at slot " + slot + " has been closed");
			}

			MetalGpuBuffer nativeVertexBuffer = MetalCommandEncoder.castBuffer(vertexBuffer.buffer());
			int result = MetalNativeBridge.INSTANCE.metallum_render_pass_set_vertex_buffer(
				renderPass,
				metalSlot,
				nativeVertexBuffer.nativeHandle(),
				vertexBuffer.offset()
			);
			if (result != 0) {
				throw new IllegalStateException("Failed to set native vertex buffer slot " + slot + ", code " + result);
			}
		}
	}

	private MetalGpuBuffer resolveIndexBuffer() {
		if (this.indexBuffer == null) {
			throw new IllegalStateException("Missing index buffer");
		}
		if (VALIDATION && this.indexBuffer.isClosed()) {
			throw new IllegalStateException("Index buffer has been closed");
		}
		return MetalCommandEncoder.castBuffer(this.indexBuffer);
	}

	private void drawIndexedNative(
		final Pointer renderPass,
		final MetalGpuBuffer nativeIndexBuffer,
		final int firstIndex,
		final int indexCount,
		final int baseVertex,
		final int instanceCount,
		final IndexType indexType
	) {
		PrimitiveTopology primitiveTopology = this.primitiveTopology();
		long primitiveType = MetalPipelineSupport.primitiveTypeCode(primitiveTopology);
		if (primitiveType < 0L) {
			throw new IllegalStateException("Unsupported primitive type: " + primitiveTopology);
		}

		int safeInstanceCount = Math.max(1, instanceCount);
		long indexOffsetBytes = (long)firstIndex * indexType.bytes;
		long nativeIndexType = indexType == IndexType.INT ? 1L : 0L;
		int result = primitiveType == MetalPipelineSupport.TRIANGLE_FAN_PRIMITIVE
			? this.drawIndexedTriangleFanNative(renderPass, nativeIndexBuffer, nativeIndexType, indexOffsetBytes, indexCount, baseVertex, safeInstanceCount)
			: MetalNativeBridge.INSTANCE.metallum_render_pass_draw_indexed(renderPass, nativeIndexBuffer.nativeHandle(), nativeIndexType, primitiveType, indexOffsetBytes, indexCount, baseVertex, safeInstanceCount);
		if (result != 0) {
			throw new IllegalStateException("Native draw failed with code " + result);
		}
	}

	private int drawTriangleFanNative(
		final Pointer renderPass,
		final int firstVertex,
		final int vertexCount,
		final int instanceCount
	) {
		if (vertexCount < 3) {
			return 0;
		}

		try (MetalGpuBuffer fanIndexBuffer = this.newTriangleFanBuffer(vertexCount)) {
			return MetalNativeBridge.INSTANCE.metallum_render_pass_draw_triangle_fan(
				renderPass,
				fanIndexBuffer.nativeHandle(),
				firstVertex,
				vertexCount,
				Math.max(1, instanceCount)
			);
		}
	}

	private int drawIndexedTriangleFanNative(
		final Pointer renderPass,
		final MetalGpuBuffer nativeIndexBuffer,
		final long nativeIndexType,
		final long indexOffsetBytes,
		final int indexCount,
		final int baseVertex,
		final int instanceCount
	) {
		if (indexCount < 3) {
			return 0;
		}

		try (MetalGpuBuffer fanIndexBuffer = this.newTriangleFanBuffer(indexCount)) {
			return MetalNativeBridge.INSTANCE.metallum_render_pass_draw_indexed_triangle_fan(
				renderPass,
				nativeIndexBuffer.nativeHandle(),
				fanIndexBuffer.nativeHandle(),
				nativeIndexType,
				indexOffsetBytes,
				indexCount,
				baseVertex,
				instanceCount
			);
		}
	}

	private MetalGpuBuffer newTriangleFanBuffer(final int sourceCount) {
		long byteSize = Math.multiplyExact(Math.multiplyExact((long)sourceCount - 2L, 3L), Integer.BYTES);
		if (byteSize > Integer.MAX_VALUE) {
			throw new UnsupportedOperationException("Triangle fan index buffer is too large: " + byteSize + " bytes");
		}

		return new MetalGpuBuffer(
			this.device,
			this.device.useLabels() ? "triangle fan index buffer" : null,
			GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_INDEX,
			byteSize
		);
	}

	private boolean needsDrawStateBinding() {
		return this.pipelineDirty
			|| this.depthStateDirty
			|| this.vertexBuffersDirty
			|| this.scissorDirty
			|| !this.dirtyBindings.isEmpty()
			|| MetalProbe.isNullPointer(this.nativePipeline);
	}

	private void bindDrawState(
		final Pointer renderPass,
		final MetalGpuTexture colorAttachment
	) {
		if (this.compiledPipeline == null) {
			throw new IllegalStateException("Pipeline is missing");
		}

		Pointer pipelineHandle = this.nativePipeline;
		boolean reboundPipeline = this.pipelineDirty;
		if (reboundPipeline || MetalProbe.isNullPointer(pipelineHandle)) {
			pipelineHandle = this.compiledPipeline.getOrCreateNativePipeline(
				this.device,
				this.colorAttachmentFormat(),
				this.depthAttachmentFormat(),
				this.stencilAttachmentFormat()
			);
			if (MetalProbe.isNullPointer(pipelineHandle)) {
				throw new IllegalStateException("Native pipeline is unavailable");
			}
			reboundPipeline = this.pipelineDirty || !MetalPipelineSupport.samePointer(this.nativePipeline, pipelineHandle);
		}

		if (reboundPipeline) {
			int result = MetalNativeBridge.INSTANCE.metallum_render_pass_set_pipeline(renderPass, pipelineHandle);
			if (result != 0) {
				throw new IllegalStateException("Failed to set native pipeline, code " + result);
			}
			this.nativePipeline = pipelineHandle;
			this.pipelineDirty = false;
		}

		if (reboundPipeline || this.depthStateDirty) {
			int result = MetalNativeBridge.INSTANCE.metallum_render_pass_set_depth_stencil_state(
				renderPass,
				this.compiledPipeline.depthCompareOp(),
				this.compiledPipeline.depthWrite(),
				this.compiledPipeline.depthBiasScaleFactor(),
				this.compiledPipeline.depthBiasConstant()
			);
			if (result != 0) {
				throw new IllegalStateException("Failed to set native depth state, code " + result);
			}
			this.depthStateDirty = false;
		}

		if (reboundPipeline) {
			RenderPipeline pipelineInfo = this.compiledPipeline.info();
			int result = MetalNativeBridge.INSTANCE.metallum_render_pass_set_raster_state(
				renderPass,
				pipelineInfo.isCull() ? 1 : 0,
				pipelineInfo.getPolygonMode() == PolygonMode.WIREFRAME ? 1 : 0
			);
			if (result != 0) {
				throw new IllegalStateException("Failed to set native raster state, code " + result);
			}
		}

		if (reboundPipeline || this.scissorDirty) {
			EffectiveScissor effectiveScissor = this.effectiveScissor();
			int result = MetalNativeBridge.INSTANCE.metallum_render_pass_set_scissor(
				renderPass,
				effectiveScissor.enabled() ? 1 : 0,
				effectiveScissor.x(),
				effectiveScissor.y(),
				effectiveScissor.width(),
				effectiveScissor.height()
			);
			if (result != 0) {
				throw new IllegalStateException("Failed to set native scissor, code " + result);
			}
			this.scissorDirty = false;
		}

		if (this.vertexBuffersDirty) {
			this.pushVertexBuffers(renderPass);
			this.vertexBuffersDirty = false;
		}

		this.pushDescriptors(renderPass, colorAttachment, reboundPipeline);
	}

	private PrimitiveTopology primitiveTopology() {
		if (this.pipeline == null) {
			throw new IllegalStateException("Pipeline is missing");
		}
		return this.pipeline.getPrimitiveTopology();
	}

	private void pushDescriptors(final Pointer renderPass, final MetalGpuTexture colorAttachment, final boolean bindAll) {
		if (this.compiledPipeline == null) {
			throw new IllegalStateException("Pipeline is missing");
		}

		if (!bindAll) {
			if (this.dirtyBindings.isEmpty()) {
				return;
			}

			for (MetalCompiledRenderPipeline.ResourceBinding binding : this.dirtyBindings) {
				this.pushDescriptor(renderPass, colorAttachment, binding);
			}
			this.dirtyBindings.clear();
			return;
		}

		for (MetalCompiledRenderPipeline.ResourceBinding binding : this.compiledPipeline.resources()) {
			this.pushDescriptor(renderPass, colorAttachment, binding);
		}
		this.dirtyBindings.clear();
	}

	private EffectiveScissor effectiveScissor() {
		int areaLeft = this.renderArea.x();
		int areaTop = this.renderArea.y();
		int areaRight = areaLeft + this.renderArea.width();
		int areaBottom = areaTop + this.renderArea.height();
		if (!this.scissorState.enabled()) {
			return this.renderArea.fillsTexture(this.colorTexture)
				? EffectiveScissor.disabled()
				: new EffectiveScissor(true, areaLeft, areaTop, this.renderArea.width(), this.renderArea.height());
		}

		int left = Math.max(areaLeft, this.scissorState.x());
		int top = Math.max(areaTop, this.scissorState.y());
		int right = Math.min(areaRight, this.scissorState.x() + this.scissorState.width());
		int bottom = Math.min(areaBottom, this.scissorState.y() + this.scissorState.height());
		if (right <= left || bottom <= top) {
			return new EffectiveScissor(true, 0, 0, 0, 0);
		}
		return new EffectiveScissor(true, left, top, right - left, bottom - top);
	}

	private record EffectiveScissor(boolean enabled, int x, int y, int width, int height) {
		static EffectiveScissor disabled() {
			return new EffectiveScissor(false, 0, 0, 0, 0);
		}
	}

	private void markBindingDirty(final String name) {
		if (this.compiledPipeline == null) {
			return;
		}

		MetalCompiledRenderPipeline.ResourceBinding binding = this.compiledPipeline.resource(name);
		if (binding != null) {
			this.dirtyBindings.add(binding);
		}
	}

	private void pushDescriptor(
		final Pointer renderPass,
		final MetalGpuTexture colorAttachment,
		final MetalCompiledRenderPipeline.ResourceBinding binding
	) {
		if (binding.kind() == MetalCompiledRenderPipeline.ResourceKind.SAMPLED_IMAGE) {
			TextureViewAndSampler textureBinding = this.samplers.get(binding.name());
			if (textureBinding == null) {
				throw new IllegalStateException("Missing sampler " + binding.name());
			}

			if (VALIDATION && textureBinding.textureView().isClosed()) {
				throw new IllegalStateException("Sampler " + binding.name() + " texture view has been closed");
			}

			MetalGpuTexture texture = MetalCommandEncoder.castTexture(textureBinding.textureView().texture());
			MetalGpuTextureView textureView = (MetalGpuTextureView)textureBinding.textureView();
			if (texture == colorAttachment) {
				throw new IllegalStateException("Feedback sampler is not allowed for binding " + binding.name());
			}

			MetalGpuSampler sampler = (MetalGpuSampler)textureBinding.sampler();
			int result = MetalNativeBridge.INSTANCE.metallum_render_pass_set_texture_binding(
				renderPass,
				binding.bindingIndex(),
				textureView.nativeHandle(),
				sampler.nativeHandle(),
				binding.stageMask()
			);
			if (result != 0) {
				throw new IllegalStateException("Failed to set sampler binding " + binding.name() + ", code " + result);
			}
			return;
		}

		if (binding.kind() == MetalCompiledRenderPipeline.ResourceKind.TEXEL_BUFFER) {
			this.pushTexelBufferDescriptor(renderPass, binding);
			return;
		}

		GpuBufferSlice uniformSlice = this.uniforms.get(binding.name());
		if (uniformSlice == null) {
			throw new IllegalStateException("Missing uniform " + binding.name());
		}
		if (VALIDATION && uniformSlice.buffer().isClosed()) {
			throw new IllegalStateException("Uniform " + binding.name() + " buffer has been closed");
		}

		MetalGpuBuffer uniformBuffer = MetalCommandEncoder.castBuffer(uniformSlice.buffer());
		int result = MetalNativeBridge.INSTANCE.metallum_render_pass_set_buffer_binding(
			renderPass,
			binding.bindingIndex(),
			uniformBuffer.nativeHandle(),
			uniformSlice.offset(),
			binding.stageMask()
		);
		if (result != 0) {
			throw new IllegalStateException("Failed to set uniform binding " + binding.name() + ", code " + result);
		}
	}

	private void pushTexelBufferDescriptor(final Pointer renderPass, final MetalCompiledRenderPipeline.ResourceBinding binding) {
		GpuBufferSlice texelSlice = this.uniforms.get(binding.name());
		if (texelSlice == null) {
			throw new IllegalStateException("Missing texel buffer " + binding.name());
		}
		if (VALIDATION && texelSlice.buffer().isClosed()) {
			throw new IllegalStateException("Texel buffer " + binding.name() + " has been closed");
		}

		GpuFormat texelFormat = binding.texelBufferFormat();
		if (texelFormat == null) {
			throw new IllegalStateException("Texel buffer " + binding.name() + " is missing a format");
		}

		MetalGpuBuffer texelBuffer = MetalCommandEncoder.castBuffer(texelSlice.buffer());
		long pixelFormat = MetalPipelineSupport.texelBufferPixelFormatCode(texelFormat);
		int pixelSize = texelFormat.pixelSize();
		long width = 4096L;
		long bytesPerRow = width * pixelSize;
		long height = Math.max(1L, (texelSlice.length() + bytesPerRow - 1L) / bytesPerRow);
		Pointer texelTexture = MetalNativeBridge.INSTANCE.metallum_create_buffer_texture_view(
			texelBuffer.nativeHandle(),
			pixelFormat,
			texelSlice.offset(),
			width,
			height,
			bytesPerRow
		);
		if (MetalProbe.isNullPointer(texelTexture)) {
			throw new IllegalStateException("Failed to create Metal texel buffer texture for " + binding.name());
		}

		int result = MetalNativeBridge.INSTANCE.metallum_render_pass_set_texture_binding(
			renderPass,
			binding.bindingIndex(),
			texelTexture,
			null,
			binding.stageMask()
		);
		if (result != 0) {
			MetalNativeBridge.INSTANCE.metallum_release_object(texelTexture);
			throw new IllegalStateException("Failed to set texel buffer binding " + binding.name() + ", code " + result);
		}
		this.encoder.queueForDestroy(() -> MetalNativeBridge.INSTANCE.metallum_release_object(texelTexture));
	}

	record TextureViewAndSampler(GpuTextureView textureView, GpuSampler sampler) {
	}

	private static boolean sameNullableSlice(@Nullable final GpuBufferSlice left, @Nullable final GpuBufferSlice right) {
		if (left == null || right == null) {
			return left == right;
		}
		return sameSlice(left, right);
	}

	private static boolean sameSlice(@Nullable final GpuBufferSlice left, final GpuBufferSlice right) {
		return left != null
			&& left.buffer() == right.buffer()
			&& left.offset() == right.offset()
			&& left.length() == right.length();
	}
}
