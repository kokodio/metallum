package com.metallum.client.metal;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.PolygonMode;
import com.mojang.blaze3d.systems.GpuQueryPool;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.systems.ScissorState;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import com.sun.jna.Pointer;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
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
	static final int MAX_VERTEX_BUFFERS = 1;
	private final MetalDevice device;
	private final MetalCommandEncoder encoder;
	@Nullable
	private final String label;
	private final GpuTextureView colorTexture;
	@Nullable
	private final GpuTextureView depthTexture;
	private final boolean clearColorEnabled;
	private final int clearColor;
	private final boolean clearDepthEnabled;
	private final double clearDepth;
	private final ScissorState scissorState = new ScissorState();
	private final GpuBuffer[] vertexBuffers = new GpuBuffer[MAX_VERTEX_BUFFERS];
	private final HashMap<String, GpuBufferSlice> uniforms = new HashMap<>();
	private final HashMap<String, TextureViewAndSampler> samplers = new HashMap<>();
	private final Set<String> dirtyUniforms = new HashSet<>();
	@Nullable
	private RenderPipeline pipeline;
	@Nullable
	private MetalCompiledRenderPipeline compiledPipeline;
	@Nullable
	private GpuBuffer indexBuffer;
	private VertexFormat.IndexType indexType = VertexFormat.IndexType.SHORT;
	@Nullable
	private Pointer nativeRenderPass;
	@Nullable
	private Pointer nativePipeline;
	private boolean pipelineDirty = true;
	private boolean depthStateDirty = true;
	private boolean vertexBuffersDirty = true;
	private boolean indexBufferDirty = true;
	private boolean scissorDirty = true;

	MetalRenderPass(
		final MetalDevice device,
		final MetalCommandEncoder encoder,
		final Supplier<String> label,
		final GpuTextureView colorTexture,
		@Nullable final GpuTextureView depthTexture,
		final boolean clearColorEnabled,
		final int clearColor,
		final boolean clearDepthEnabled,
		final double clearDepth
	) {
		this.device = device;
		this.encoder = encoder;
		this.label = device.useLabels() ? label.get() : null;
		this.colorTexture = colorTexture;
		this.depthTexture = depthTexture;
		this.clearColorEnabled = clearColorEnabled;
		this.clearColor = clearColor;
		this.clearDepthEnabled = clearDepthEnabled;
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
			this.dirtyUniforms.addAll(this.uniforms.keySet());
			this.dirtyUniforms.addAll(this.samplers.keySet());
			this.pipelineDirty = true;
			this.depthStateDirty = true;
			this.vertexBuffersDirty = true;
			this.indexBufferDirty = true;
		}

		MetalCompiledRenderPipeline compiled = this.device.getOrCompilePipeline(pipeline);
		if (!compiled.isValid()) {
			throw new IllegalStateException("Pipeline is not valid (may contain invalid shaders?)");
		}

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
			this.dirtyUniforms.add(name);
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
			this.dirtyUniforms.add(name);
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
	public void setVertexBuffer(final int slot, final GpuBuffer vertexBuffer) {
		if (slot < 0 || slot >= MAX_VERTEX_BUFFERS) {
			throw new IllegalArgumentException("Unsupported Metal vertex buffer slot: " + slot);
		}

		if (this.vertexBuffers[slot] != vertexBuffer) {
			this.vertexBuffers[slot] = vertexBuffer;
			this.vertexBuffersDirty = true;
		}
	}

	@Override
	public void setIndexBuffer(final GpuBuffer indexBuffer, final VertexFormat.IndexType indexType) {
		if (this.indexBuffer != indexBuffer || this.indexType != indexType) {
			this.indexBuffer = indexBuffer;
			this.indexType = indexType;
			this.indexBufferDirty = true;
		}
	}

	@Override
	public void drawIndexed(final int baseVertex, final int firstIndex, final int indexCount, final int instanceCount) {
		if (VALIDATION) {
			this.validateDrawState(Collections.emptyList());
		}

		MetalGpuTexture colorAttachment = MetalCommandEncoder.castTexture(this.colorTexture.texture());
		MetalGpuBuffer nativeVertexBuffer = this.resolveVertexBuffer();
		MetalGpuBuffer nativeIndexBuffer = this.resolveIndexBuffer();
		long primitiveType = MetalPipelineSupport.primitiveTypeCode(this.pipeline.getVertexFormatMode());
		if (primitiveType < 0L) {
			throw new IllegalStateException("Unsupported primitive type: " + this.pipeline.getVertexFormatMode());
		}

		Pointer renderPass = this.getOrCreateNativeRenderPass();
		if (MetalProbe.isNullPointer(renderPass)) {
			throw new IllegalStateException("Native render pass is unavailable");
		}

		this.bindDrawState(renderPass, colorAttachment, nativeVertexBuffer, nativeIndexBuffer, this.indexType, true);

		int safeInstanceCount = Math.max(1, instanceCount);
		int result = primitiveType == MetalPipelineSupport.TRIANGLE_FAN_PRIMITIVE
			? MetalNativeBridge.INSTANCE.metallum_render_pass_draw_indexed_triangle_fan(
				renderPass,
				(long)firstIndex * (this.indexType == VertexFormat.IndexType.INT ? 4L : 2L),
				indexCount,
				baseVertex,
				safeInstanceCount
			)
			: MetalNativeBridge.INSTANCE.metallum_render_pass_draw_indexed(
				renderPass,
				primitiveType,
				(long)firstIndex * (this.indexType == VertexFormat.IndexType.INT ? 4L : 2L),
				indexCount,
				baseVertex,
				safeInstanceCount
			);
		if (result != 0) {
			throw new IllegalStateException("Native draw failed with code " + result);
		}
	}

	@Override
	public <T> void drawMultipleIndexed(
		final Collection<RenderPass.Draw<T>> draws,
		@Nullable final GpuBuffer defaultIndexBuffer,
		final VertexFormat.IndexType defaultIndexType,
		final Collection<String> dynamicUniforms,
		final T uniformArgument
	) {
		if (VALIDATION) {
			this.validateDrawState(dynamicUniforms);
		}

		VertexFormat.IndexType fallbackIndexType = defaultIndexType == null ? VertexFormat.IndexType.SHORT : defaultIndexType;
		GpuBuffer lastIndexBuffer = null;
		VertexFormat.IndexType lastIndexType = null;
		GpuBuffer lastVertexBuffer = null;

		for (RenderPass.Draw<T> draw : draws) {
			VertexFormat.IndexType drawIndexType = draw.indexType() == null ? fallbackIndexType : draw.indexType();
			GpuBuffer currentIndexBuffer = draw.indexBuffer() == null ? defaultIndexBuffer : draw.indexBuffer();

			if (currentIndexBuffer != lastIndexBuffer || drawIndexType != lastIndexType) {
				this.setIndexBuffer(currentIndexBuffer, drawIndexType);
				lastIndexBuffer = currentIndexBuffer;
				lastIndexType = drawIndexType;
			}

			if (draw.vertexBuffer() != lastVertexBuffer) {
				this.setVertexBuffer(0, draw.vertexBuffer());
				lastVertexBuffer = draw.vertexBuffer();
			}

			if (draw.uniformUploaderConsumer() != null) {
				draw.uniformUploaderConsumer().accept(uniformArgument, this::setUniform);
			}

			this.drawIndexed(draw.baseVertex(), draw.firstIndex(), draw.indexCount(), 1);
		}
	}

	@Override
	public void draw(final int firstVertex, final int vertexCount) {
		if (VALIDATION) {
			this.validateDrawState(Collections.emptyList());
		}
		MetalGpuTexture colorAttachment = MetalCommandEncoder.castTexture(this.colorTexture.texture());
		MetalGpuBuffer nativeVertexBuffer = this.resolveVertexBuffer();
		long primitiveType = MetalPipelineSupport.primitiveTypeCode(this.pipeline.getVertexFormatMode());
		if (primitiveType < 0L) {
			throw new IllegalStateException("Unsupported primitive type: " + this.pipeline.getVertexFormatMode());
		}

		Pointer renderPass = this.getOrCreateNativeRenderPass();
		if (MetalProbe.isNullPointer(renderPass)) {
			throw new IllegalStateException("Native render pass is unavailable");
		}

		this.bindDrawState(renderPass, colorAttachment, nativeVertexBuffer, null, null, false);

		int result = primitiveType == MetalPipelineSupport.TRIANGLE_FAN_PRIMITIVE
			? MetalNativeBridge.INSTANCE.metallum_render_pass_draw_triangle_fan(renderPass, firstVertex, vertexCount, 1)
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

	@Nullable
	String label() {
		return this.label;
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
		return 0L;
	}

	GpuTextureView colorTexture() {
		return this.colorTexture;
	}

	@Nullable
	GpuTextureView depthTexture() {
		return this.depthTexture;
	}

	@Nullable
	Pointer getOrCreateNativeRenderPass() {
		if (!MetalProbe.isNullPointer(this.nativeRenderPass)) {
			return this.nativeRenderPass;
		}

		MetalGpuTextureView colorTextureView = (MetalGpuTextureView)this.colorTexture;
		MetalGpuTextureView depthTextureView = this.depthTexture == null ? null : (MetalGpuTextureView)this.depthTexture;
		Pointer handle = MetalNativeBridge.INSTANCE.metallum_begin_render_pass(
			this.device.commandQueue(),
			colorTextureView.nativeHandle(),
			depthTextureView == null ? null : depthTextureView.nativeHandle(),
			this.colorTexture.getWidth(0),
			this.colorTexture.getHeight(0),
			this.clearColorEnabled ? 1 : 0,
			this.clearColor,
			this.clearDepthEnabled ? 1 : 0,
			this.clearDepth
		);
		if (MetalProbe.isNullPointer(handle)) {
			LOGGER.warn("Failed to begin Metal render pass '{}'", this.label);
			return null;
		}

		this.nativeRenderPass = handle;
		return handle;
	}

	void end() {
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

	private void validateDrawState(final Collection<String> dynamicUniforms) {
		if (this.pipeline == null) {
			throw new IllegalStateException("Can't draw without a render pipeline");
		}
		if (this.compiledPipeline == null || !this.compiledPipeline.isValid()) {
			throw new IllegalStateException("Pipeline is missing or not valid");
		}
		if (this.compiledPipeline.vertexMsl() == null
			|| this.compiledPipeline.fragmentMsl() == null
			|| this.compiledPipeline.vertexEntryPoint() == null
			|| this.compiledPipeline.fragmentEntryPoint() == null) {
			throw new IllegalStateException("Pipeline shader source is missing");
		}

		for (var entry : this.uniforms.entrySet()) {
			String uniformName = entry.getKey();
			GpuBufferSlice uniformSlice = entry.getValue();
			if (uniformSlice == null || dynamicUniforms.contains(uniformName)) {
				continue;
			}
			if (uniformSlice.buffer().isClosed()) {
				throw new IllegalStateException("Uniform " + uniformName + " buffer has been closed");
			}
		}
	}

	@Nullable
	private MetalGpuBuffer resolveVertexBuffer() {
		if (this.compiledPipeline == null) {
			throw new IllegalStateException("Pipeline is missing");
		}

		GpuBuffer vertexBuffer = this.vertexBuffers[0];
		if (this.compiledPipeline.vertexAttributeFormats().length <= 0L) {
			if (vertexBuffer == null) {
				return null;
			}
			if (VALIDATION && vertexBuffer.isClosed()) {
				throw new IllegalStateException("Vertex buffer at slot 0 has been closed");
			}
			return MetalCommandEncoder.castBuffer(vertexBuffer);
		}

		if (vertexBuffer == null) {
			throw new IllegalStateException("Missing vertex buffer at slot 0");
		}
		if (VALIDATION && vertexBuffer.isClosed()) {
			throw new IllegalStateException("Vertex buffer at slot 0 has been closed");
		}
		return MetalCommandEncoder.castBuffer(vertexBuffer);
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

	private void bindDrawState(
		final Pointer renderPass,
		final MetalGpuTexture colorAttachment,
		@Nullable final MetalGpuBuffer nativeVertexBuffer,
		@Nullable final MetalGpuBuffer nativeIndexBuffer,
		final VertexFormat.IndexType indexType,
		final boolean indexed
	) {
		if (this.compiledPipeline == null) {
			throw new IllegalStateException("Pipeline is missing");
		}

		RenderPipeline pipelineInfo = this.compiledPipeline.info();
		Pointer pipelineHandle = this.compiledPipeline.getOrCreateNativePipeline(
			this.device,
			this.colorAttachmentFormat(),
			this.depthAttachmentFormat(),
			this.stencilAttachmentFormat()
		);
		if (MetalProbe.isNullPointer(pipelineHandle)) {
			throw new IllegalStateException("Native pipeline is unavailable");
		}

		boolean reboundPipeline = this.pipelineDirty || !MetalPipelineSupport.samePointer(this.nativePipeline, pipelineHandle);
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
			int result = MetalNativeBridge.INSTANCE.metallum_render_pass_set_raster_state(
				renderPass,
				pipelineInfo.isCull() ? 1 : 0,
				pipelineInfo.getPolygonMode() == PolygonMode.WIREFRAME ? 1 : 0,
				this.compiledPipeline.flipVertexY() ? 1 : 0
			);
			if (result != 0) {
				throw new IllegalStateException("Failed to set native raster state, code " + result);
			}
		}

		if (reboundPipeline || this.scissorDirty) {
			int result = MetalNativeBridge.INSTANCE.metallum_render_pass_set_scissor(
				renderPass,
				this.scissorState.enabled() ? 1 : 0,
				this.scissorState.x(),
				this.scissorState.y(),
				this.scissorState.width(),
				this.scissorState.height()
			);
			if (result != 0) {
				throw new IllegalStateException("Failed to set native scissor, code " + result);
			}
			this.scissorDirty = false;
		}

		if (this.vertexBuffersDirty) {
			int result = MetalNativeBridge.INSTANCE.metallum_render_pass_set_vertex_buffer(
				renderPass,
				0L,
				nativeVertexBuffer == null ? null : nativeVertexBuffer.nativeHandle(),
				0L
			);
			if (result != 0) {
				throw new IllegalStateException("Failed to set native vertex buffer, code " + result);
			}
			this.vertexBuffersDirty = false;
		}

		if (indexed && (reboundPipeline || this.indexBufferDirty)) {
			if (indexType == null) {
				throw new IllegalStateException("Indexed draw requires an index type");
			}

			int result = MetalNativeBridge.INSTANCE.metallum_render_pass_set_index_buffer(
				renderPass,
				nativeIndexBuffer == null ? null : nativeIndexBuffer.nativeHandle(),
				indexType == VertexFormat.IndexType.INT ? 1L : 0L
			);
			if (result != 0) {
				throw new IllegalStateException("Failed to set native index buffer, code " + result);
			}
			this.indexBufferDirty = false;
		}

		this.pushDescriptors(renderPass, colorAttachment, reboundPipeline);
	}

	private void pushDescriptors(final Pointer renderPass, final MetalGpuTexture colorAttachment, final boolean bindAll) {
		if (this.compiledPipeline == null) {
			throw new IllegalStateException("Pipeline is missing");
		}

		if (!bindAll) {
			if (this.dirtyUniforms.isEmpty()) {
				return;
			}

			for (String dirtyName : this.dirtyUniforms) {
				MetalCompiledRenderPipeline.ResourceBinding binding = this.compiledPipeline.resource(dirtyName);
				if (binding != null) {
					this.pushDescriptor(renderPass, colorAttachment, binding);
				}
			}
			this.dirtyUniforms.clear();
			return;
		}

		for (MetalCompiledRenderPipeline.ResourceBinding binding : this.compiledPipeline.resources()) {
			this.pushDescriptor(renderPass, colorAttachment, binding);
		}
		this.dirtyUniforms.clear();
	}

	private void pushDescriptor(
		final Pointer renderPass,
		final MetalGpuTexture colorAttachment,
		final MetalCompiledRenderPipeline.ResourceBinding binding
	) {
		if (binding.kind() == MetalCompiledRenderPipeline.ResourceKind.SAMPLED_IMAGE) {
			TextureViewAndSampler textureBinding = this.samplers.get(binding.name());
			if (textureBinding == null) {
				int result = MetalNativeBridge.INSTANCE.metallum_render_pass_set_texture_binding(
					renderPass,
					binding.bindingIndex(),
					null,
					null,
					binding.stageMask()
				);
				if (result != 0) {
					throw new IllegalStateException("Failed to unset sampler binding " + binding.name() + ", code " + result);
				}
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

	private static boolean sameSlice(@Nullable final GpuBufferSlice left, final GpuBufferSlice right) {
		return left != null
			&& left.buffer() == right.buffer()
			&& left.offset() == right.offset()
			&& left.length() == right.length();
	}
}
