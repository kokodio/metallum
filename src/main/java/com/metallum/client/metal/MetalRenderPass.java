package com.metallum.client.metal;

import com.mojang.logging.LogUtils;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.GpuQueryPool;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.systems.ScissorState;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.sun.jna.Pointer;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.Collection;
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
		this.label = label.get();
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

		this.pipeline = pipeline;
		this.compiledPipeline = this.device.getOrCompilePipeline(pipeline);
		if (!this.compiledPipeline.isValid()) {
			throw new IllegalStateException("Pipeline is not valid (may contain invalid shaders?)");
		}
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
		this.encoder.executeDraw(this, baseVertex, firstIndex, indexCount, this.indexType, instanceCount);
	}

	@Override
	public <T> void drawMultipleIndexed(
		final Collection<RenderPass.Draw<T>> draws,
		@Nullable final GpuBuffer defaultIndexBuffer,
		final VertexFormat.IndexType defaultIndexType,
		final Collection<String> dynamicUniforms,
		final T uniformArgument
	) {
		this.encoder.executeDrawMultiple(this, draws, defaultIndexBuffer, defaultIndexType, dynamicUniforms, uniformArgument);
	}

	@Override
	public void draw(final int firstVertex, final int vertexCount) {
		this.encoder.executeDrawNonIndexed(this, firstVertex, vertexCount, 1);
	}

	@Override
	public void writeTimestamp(final GpuQueryPool pool, final int index) {
		if (pool instanceof MetalGpuQueryPool metalPool && index >= 0 && index < pool.size()) {
			metalPool.setValue(index, this.device.getTimestampNow());
		}
	}

	String label() {
		return this.label;
	}

	@Nullable
	RenderPipeline pipeline() {
		return this.pipeline;
	}

	@Nullable
	MetalCompiledRenderPipeline compiledPipeline() {
		return this.compiledPipeline;
	}

	@Nullable
	GpuBuffer vertexBuffer0() {
		return this.vertexBuffers[0];
	}

	@Nullable
	GpuBuffer indexBuffer() {
		return this.indexBuffer;
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

	VertexFormat.IndexType indexType() {
		return this.indexType;
	}

	ScissorState scissorState() {
		return this.scissorState;
	}

	HashMap<String, GpuBufferSlice> uniforms() {
		return this.uniforms;
	}

	HashMap<String, TextureViewAndSampler> samplers() {
		return this.samplers;
	}

	Set<String> dirtyUniforms() {
		return this.dirtyUniforms;
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

	@Nullable
	Pointer nativePipeline() {
		return this.nativePipeline;
	}

	void setNativePipeline(@Nullable final Pointer nativePipeline) {
		this.nativePipeline = nativePipeline;
	}

	boolean isPipelineDirty() {
		return this.pipelineDirty;
	}

	void markPipelineClean() {
		this.pipelineDirty = false;
	}

	boolean isDepthStateDirty() {
		return this.depthStateDirty;
	}

	void markDepthStateClean() {
		this.depthStateDirty = false;
	}

	boolean isVertexBuffersDirty() {
		return this.vertexBuffersDirty;
	}

	void markVertexBuffersClean() {
		this.vertexBuffersDirty = false;
	}

	boolean isIndexBufferDirty() {
		return this.indexBufferDirty;
	}

	void markIndexBufferClean() {
		this.indexBufferDirty = false;
	}

	boolean isScissorDirty() {
		return this.scissorDirty;
	}

	void markScissorClean() {
		this.scissorDirty = false;
	}

	void submitNativeRenderPass() {
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

	record TextureViewAndSampler(GpuTextureView textureView, GpuSampler sampler) {
	}

	private static boolean sameSlice(@Nullable final GpuBufferSlice left, final GpuBufferSlice right) {
		return left != null
			&& left.buffer() == right.buffer()
			&& left.offset() == right.offset()
			&& left.length() == right.length();
	}
}
