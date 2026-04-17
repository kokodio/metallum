package com.metallum.client.metal;

import com.metallum.Metallum;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.PolygonMode;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.GpuQueryPool;
import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collection;
import java.util.Collections;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.function.Supplier;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;
import com.sun.jna.Pointer;

@Environment(EnvType.CLIENT)
final class MetalCommandEncoder implements CommandEncoderBackend {
	public static final int MAX_SUBMITS_IN_FLIGHT = 2;
	private final MetalDevice device;
	private long currentSubmitIndex = 2L;
	private long completedSubmitIndex = 0L;
	private final MetalDestructionQueue destroyQueue = new MetalDestructionQueue(MAX_SUBMITS_IN_FLIGHT);
	private static final org.slf4j.Logger LOGGER = Metallum.LOGGER;
	private static final long TRIANGLE_FAN_PRIMITIVE = 5L;
	@Nullable
	private MetalRenderPass currentRenderPass;

	MetalCommandEncoder(final MetalDevice device) {
		this.device = device;
	}

	MetalDevice device() {
		return this.device;
	}

	@Override
	public void submit() {
		this.submitRenderPass();
		int result = MetalNativeBridge.INSTANCE.metallum_signal_submit(this.device.commandQueue(), this.currentSubmitIndex);
		if (result != 0) {
			throw new IllegalStateException("Failed to signal Metal submit " + this.currentSubmitIndex + " (code " + result + ")");
		}
		this.currentSubmitIndex++;
		if (!this.awaitSubmitCompletion(this.currentSubmitIndex - MAX_SUBMITS_IN_FLIGHT, 5000L)) {
			throw new IllegalStateException("5s timeout reached when waiting for Metal submit completion");
		}
		this.destroyQueue.rotate();
	}

	@Override
	public RenderPassBackend createRenderPass(final Supplier<String> label, final GpuTextureView colorTexture, final OptionalInt clearColor) {
		return this.createRenderPass(label, colorTexture, clearColor, null, OptionalDouble.empty());
	}

	@Override
	public RenderPassBackend createRenderPass(
		final Supplier<String> label,
		final GpuTextureView colorTexture,
		final OptionalInt clearColor,
		@Nullable final GpuTextureView depthTexture,
		final OptionalDouble clearDepth
	) {
		this.submitRenderPass();

		MetalRenderPass pass = new MetalRenderPass(
			this.device,
			this,
			label,
			colorTexture,
			depthTexture,
			clearColor.isPresent(),
			clearColor.orElse(0),
			depthTexture != null && clearDepth.isPresent(),
			clearDepth.orElse(1.0)
		);
		this.currentRenderPass = pass;
		return pass;
	}

	@Override
	public void submitRenderPass() {
		if (this.currentRenderPass != null) {
			this.currentRenderPass.submitNativeRenderPass();
		}
		this.currentRenderPass = null;
	}

	@Override
	public void clearColorTexture(final GpuTexture colorTexture, final int clearColor) {
		this.submitRenderPass();

		MetalGpuTexture texture = castTexture(colorTexture);
		int result = MetalNativeBridge.INSTANCE.metallum_clear_texture(
				this.device.commandQueue(),
				texture.nativeHandle(),
				1,
				clearColor,
				0,
				1.0
		);

		if (result != 0) {
			LOGGER.warn("Failed to clear Metal color texture '{}'", texture.getLabel());
		}
	}

	@Override
	public void clearColorAndDepthTextures(final GpuTexture colorTexture, final int clearColor, final GpuTexture depthTexture, final double clearDepth) {
		this.submitRenderPass();

		MetalGpuTexture color = castTexture(colorTexture);
		MetalGpuTexture depth = castTexture(depthTexture);
		int result = MetalNativeBridge.INSTANCE.metallum_clear_color_depth_textures(
				this.device.commandQueue(),
				color.nativeHandle(),
				clearColor,
				depth.nativeHandle(),
				clearDepth
		);

		if (result != 0) {
			LOGGER.warn("Failed to clear Metal color/depth textures '{}' and '{}'", color.getLabel(), depth.getLabel());
		}
	}

	@Override
	public void clearColorAndDepthTextures(
		final GpuTexture colorTexture,
		final int clearColor,
		final GpuTexture depthTexture,
		final double clearDepth,
		final int regionX,
		final int regionY,
		final int regionWidth,
		final int regionHeight
	) {
		MetalGpuTexture texture = castTexture(colorTexture);
		this.submitRenderPass();
		int result = MetalNativeBridge.INSTANCE.metallum_clear_color_texture_region(
				this.device.commandQueue(),
				texture.nativeHandle(),
				clearColor,
				regionX,
				regionY,
				regionWidth,
				regionHeight
		);
		if (result != 0) {
			LOGGER.warn("Failed to clear Metal color texture region '{}' {}x{} at {},{}", texture.getLabel(), regionWidth, regionHeight, regionX, regionY);
		}
		this.clearDepthTexture(depthTexture, clearDepth);
	}

	@Override
	public void clearDepthTexture(final GpuTexture depthTexture, final double clearDepth) {
		this.submitRenderPass();

		MetalGpuTexture texture = castTexture(depthTexture);
		int result = MetalNativeBridge.INSTANCE.metallum_clear_texture(
				this.device.commandQueue(),
				texture.nativeHandle(),
				0,
				0,
				1,
				clearDepth
		);

		if (result != 0) {
			LOGGER.warn("Failed to clear Metal depth texture '{}'", texture.getLabel());
		}
	}

	@Override
	public void writeToBuffer(final GpuBufferSlice destination, final ByteBuffer data) {
		MetalGpuBuffer buffer = castBuffer(destination.buffer());
		ByteBuffer source = data.duplicate();
		int length = source.remaining();
		if (length == 0) {
			return;
		}
		if ((long)length > destination.length()) {
			throw new IllegalArgumentException("Buffer upload size exceeds destination slice length");
		}
		int result = MetalNativeBridge.INSTANCE.metallum_upload_buffer_region_async(
			this.device.commandQueue(),
			buffer.nativeHandle(),
			destination.offset(),
			source,
			length
		);
		if (result != 0) {
			throw new IllegalStateException("Failed to upload Metal buffer '" + buffer.label() + "' (code " + result + ")");
		}
	}

	@Override
	public GpuBuffer.MappedView mapBuffer(final GpuBufferSlice buffer, final boolean read, final boolean write) {
		MetalGpuBuffer nativeBuffer = castBuffer(buffer.buffer());
		ByteBuffer mapped = nativeBuffer.sliceStorage(buffer.offset(), buffer.length());
		return new GpuBuffer.MappedView() {
			@Override
			public ByteBuffer data() {
				return mapped;
			}

			@Override
			public void close() {
			}
		};
	}

	@Override
	public void copyToBuffer(final GpuBufferSlice source, final GpuBufferSlice target) {
		long copyLength = Math.min(source.length(), target.length());
		if (copyLength == 0L) {
			return;
		}
		MetalGpuBuffer sourceBuffer = castBuffer(source.buffer());
		MetalGpuBuffer targetBuffer = castBuffer(target.buffer());
		int result = MetalNativeBridge.INSTANCE.metallum_copy_buffer_to_buffer(
			this.device.commandQueue(),
			sourceBuffer.nativeHandle(),
			source.offset(),
			targetBuffer.nativeHandle(),
			target.offset(),
			copyLength
		);
		if (result != 0) {
			throw new IllegalStateException("Failed to copy Metal buffer '" + sourceBuffer.label() + "' -> '" + targetBuffer.label() + "' (code " + result + ")");
		}
	}

	@Override
	public void writeToTexture(
		final GpuTexture destination,
		final NativeImage source,
		final int mipLevel,
		final int depthOrLayer,
		final int destX,
		final int destY,
		final int width,
		final int height,
		final int sourceX,
		final int sourceY
	) {
		this.submitRenderPass();
		ByteBuffer sourceBytes = MemoryUtil.memByteBuffer(source.getPointer(), source.getWidth() * source.getHeight() * source.format().components());
		writeToTextureFromRows(castTexture(destination), sourceBytes, source.format(), mipLevel, depthOrLayer, destX, destY, width, height, source.getWidth(), sourceX, sourceY);
	}

	@Override
	public void writeToTexture(
		final GpuTexture destination,
		final ByteBuffer source,
		final NativeImage.Format format,
		final int mipLevel,
		final int depthOrLayer,
		final int destX,
		final int destY,
		final int width,
		final int height
	) {
		this.submitRenderPass();
		writeToTextureFromRows(castTexture(destination), source.duplicate(), format, mipLevel, depthOrLayer, destX, destY, width, height, width, 0, 0);
	}

	@Override
	public void copyTextureToBuffer(final GpuTexture source, final GpuBuffer destination, final long offset, final Runnable callback, final int mipLevel) {
		this.copyTextureToBuffer(source, destination, offset, callback, mipLevel, 0, 0, source.getWidth(mipLevel), source.getHeight(mipLevel));
	}

	@Override
	public void copyTextureToBuffer(
		final GpuTexture source,
		final GpuBuffer destination,
		final long offset,
		final Runnable callback,
		final int mipLevel,
		final int x,
		final int y,
		final int width,
		final int height
	) {
		this.submitRenderPass();
		MetalGpuTexture texture = castTexture(source);
		MetalGpuBuffer buffer = castBuffer(destination);
		int bytesPerPixel = texture.pixelSize();
		int rowBytes = width * bytesPerPixel;
		int bytesPerImage = rowBytes * height;
		int result = MetalNativeBridge.INSTANCE.metallum_copy_texture_to_buffer(
			this.device.commandQueue(),
			texture.nativeHandle(),
			buffer.nativeHandle(),
			offset,
			mipLevel,
			0,
			x,
			y,
			width,
			height,
			rowBytes,
			bytesPerImage
		);
		if (result != 0) {
			LOGGER.warn("Failed to copy Metal texture '{}' -> buffer '{}'", texture.getLabel(), buffer.label());
			return;
		}

		this.queueForDestroy(callback);
	}

	@Override
	public void copyTextureToTexture(
		final GpuTexture source,
		final GpuTexture destination,
		final int mipLevel,
		final int destX,
		final int destY,
		final int sourceX,
		final int sourceY,
		final int width,
		final int height
	) {
		this.submitRenderPass();

		MetalGpuTexture srcTexture = castTexture(source);
		MetalGpuTexture dstTexture = castTexture(destination);
		int result = MetalNativeBridge.INSTANCE.metallum_copy_texture_to_texture(
				this.device.commandQueue(),
				srcTexture.nativeHandle(),
				dstTexture.nativeHandle(),
				mipLevel,
				sourceX,
				sourceY,
				destX,
				destY,
				width,
				height
		);
		if (result != 0) {
			LOGGER.warn("Failed to copy Metal texture '{}' -> '{}'", srcTexture.getLabel(), dstTexture.getLabel());
			return;
		}

	}

	@Override
	public GpuFence createFence() {
		return new MetalFence(this, this.currentSubmitIndex);
	}

	void queueForDestroy(final Runnable destroyAction) {
		this.destroyQueue.add(destroyAction);
	}

	boolean awaitSubmitCompletion(final long submitIndex, final long timeoutMs) {
		if (this.completedSubmitIndex >= submitIndex) {
			return true;
		}
		if (submitIndex == this.currentSubmitIndex) {
			throw new IllegalStateException("Cannot wait on a fence for the current submit");
		}
		if (submitIndex <= 1L) {
			this.completedSubmitIndex = Math.max(this.completedSubmitIndex, submitIndex);
			return true;
		}

		int result = MetalNativeBridge.INSTANCE.metallum_wait_for_submit_completion(this.device.commandQueue(), submitIndex, Math.max(timeoutMs, 0L));
		if (result == 0) {
			this.completedSubmitIndex = Math.max(this.completedSubmitIndex, submitIndex);
			return true;
		}
		if (result == 1) {
			return false;
		}
		throw new IllegalStateException("Failed to wait for Metal submit " + submitIndex + " (code " + result + ")");
	}

	void close() {
		this.submitRenderPass();
		this.destroyQueue.close();
	}

	@Override
	public void writeTimestamp(final GpuQueryPool pool, final int index) {
		if (pool instanceof MetalGpuQueryPool metalPool && index >= 0 && index < pool.size()) {
			metalPool.setValue(index, this.device.getTimestampNow());
		}
	}

	<T> void executeDrawMultiple(
			final MetalRenderPass pass,
			final Collection<RenderPass.Draw<T>> draws,
			@Nullable final GpuBuffer defaultIndexBuffer,
			final VertexFormat.IndexType defaultIndexType,
			final Collection<String> dynamicUniforms,
			final T uniformArgument
	) {
		if (MetalRenderPass.VALIDATION) {
			this.validateDrawState(pass, dynamicUniforms);
		}

		VertexFormat.IndexType fallbackIndexType =
				defaultIndexType == null ? VertexFormat.IndexType.SHORT : defaultIndexType;

		GpuBuffer lastIndexBuffer = null;
		VertexFormat.IndexType lastIndexType = null;
		GpuBuffer lastVertexBuffer = null;

		for (RenderPass.Draw<T> draw : draws) {
			VertexFormat.IndexType drawIndexType =
					draw.indexType() == null ? fallbackIndexType : draw.indexType();

			GpuBuffer currentIndexBuffer =
					draw.indexBuffer() == null ? defaultIndexBuffer : draw.indexBuffer();

			if (currentIndexBuffer != lastIndexBuffer || drawIndexType != lastIndexType) {
				pass.setIndexBuffer(currentIndexBuffer, drawIndexType);
				lastIndexBuffer = currentIndexBuffer;
				lastIndexType = drawIndexType;
			}

			if (draw.vertexBuffer() != lastVertexBuffer) {
				pass.setVertexBuffer(0, draw.vertexBuffer());
				lastVertexBuffer = draw.vertexBuffer();
			}

			if (draw.uniformUploaderConsumer() != null) {
				draw.uniformUploaderConsumer().accept(
						uniformArgument,
                        pass::setUniform
				);
			}

			this.executeDraw(
					pass,
					draw.baseVertex(),
					draw.firstIndex(),
					draw.indexCount(),
					drawIndexType,
					1
			);
		}
	}

	void executeDraw(
			final MetalRenderPass pass,
			final int baseVertex,
			final int firstIndex,
			final int count,
			final VertexFormat.IndexType indexType,
			final int instanceCount
	) {
		if (MetalRenderPass.VALIDATION) {
			this.validateDrawState(pass, Collections.emptyList());
		}
		this.executeNativeDraw(pass, true, baseVertex, firstIndex, count, indexType, instanceCount);
	}

	void executeDrawNonIndexed(final MetalRenderPass pass, final int firstVertex, final int vertexCount, final int instanceCount) {
		if (MetalRenderPass.VALIDATION) {
			this.validateDrawState(pass, Collections.emptyList());
		}
		this.executeNativeDraw(pass, false, 0, firstVertex, vertexCount, null, instanceCount);
	}

	private void validateDrawState(final MetalRenderPass pass, final Collection<String> dynamicUniforms) {
		RenderPipeline pipeline = pass.pipeline();
		if (pipeline == null) {
			throw new IllegalStateException("Can't draw without a render pipeline");
		}
		MetalCompiledRenderPipeline compiled = pass.compiledPipeline();
		if (compiled == null || !compiled.isValid()) {
			throw new IllegalStateException("Pipeline is missing or not valid");
		}
		if (compiled.vertexMsl() == null || compiled.fragmentMsl() == null || compiled.vertexEntryPoint() == null || compiled.fragmentEntryPoint() == null) {
			throw new IllegalStateException("Pipeline shader source is missing");
		}

		for (var entry : pass.uniforms().entrySet()) {
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
	private MetalGpuBuffer resolveVertexBuffer(final MetalRenderPass pass, final MetalCompiledRenderPipeline compiled) {
		if (compiled.vertexAttributeFormats().length <= 0L) {
			GpuBuffer vertexBuffer = pass.vertexBuffer0();
			if (vertexBuffer == null) {
				return null;
			}
			if (MetalRenderPass.VALIDATION && vertexBuffer.isClosed()) {
				throw new IllegalStateException("Vertex buffer at slot 0 has been closed");
			}
			return castBuffer(vertexBuffer);
		}

		GpuBuffer vertexBuffer = pass.vertexBuffer0();
		if (vertexBuffer == null) {
			throw new IllegalStateException("Missing vertex buffer at slot 0");
		}
		if (MetalRenderPass.VALIDATION && vertexBuffer.isClosed()) {
			throw new IllegalStateException("Vertex buffer at slot 0 has been closed");
		}
		return castBuffer(vertexBuffer);
	}

	private MetalGpuBuffer resolveIndexBuffer(final MetalRenderPass pass) {
		GpuBuffer indexBuffer = pass.indexBuffer();
		if (indexBuffer == null) {
			throw new IllegalStateException("Missing index buffer");
		}
		if (MetalRenderPass.VALIDATION && indexBuffer.isClosed()) {
			throw new IllegalStateException("Index buffer has been closed");
		}
		return castBuffer(indexBuffer);
	}

	private void executeNativeDraw(
			final MetalRenderPass pass,
			final boolean indexed,
			final int baseVertex,
			final int first,
			final int count,
			final VertexFormat.IndexType indexType,
			final int instanceCount
	) {
		RenderPipeline pipeline = pass.pipeline();
		MetalCompiledRenderPipeline compiled = pass.compiledPipeline();
		if (pipeline == null || compiled == null) {
			throw new IllegalStateException("Pipeline is missing");
		}
		if (!compiled.isValid()) {
			throw new IllegalStateException("Pipeline is not valid");
		}

		MetalGpuTexture colorTexture = castTexture(pass.colorTexture().texture());
		DepthState depthState = depthState(pipeline);

		MetalGpuBuffer nativeVertexBuffer = resolveVertexBuffer(pass, compiled);
		MetalGpuBuffer nativeIndexBuffer = indexed ? resolveIndexBuffer(pass) : null;

		long primitiveType = primitiveTypeCode(pipeline.getVertexFormatMode());
		if (primitiveType < 0L) {
			throw new IllegalStateException("Unsupported primitive type: " + pipeline.getVertexFormatMode());
		}

		Pointer nativeRenderPass = pass.getOrCreateNativeRenderPass();
		if (MetalProbe.isNullPointer(nativeRenderPass)) {
			throw new IllegalStateException("Native render pass is unavailable");
		}

		this.applyNativeDrawState(pass, compiled, colorTexture, nativeRenderPass, depthState, nativeVertexBuffer, nativeIndexBuffer, indexType, indexed);

		int safeInstanceCount = Math.max(1, instanceCount);
		int nativeResult;
		if (primitiveType == TRIANGLE_FAN_PRIMITIVE) {
			nativeResult = indexed
					? MetalNativeBridge.INSTANCE.metallum_render_pass_draw_indexed_triangle_fan(
					nativeRenderPass,
					(long) first * (indexType == VertexFormat.IndexType.INT ? 4L : 2L),
					count,
					baseVertex,
					safeInstanceCount
			)
					: MetalNativeBridge.INSTANCE.metallum_render_pass_draw_triangle_fan(
					nativeRenderPass,
					first,
					count,
					safeInstanceCount
			);
		} else {
			nativeResult = indexed
					? MetalNativeBridge.INSTANCE.metallum_render_pass_draw_indexed(
					nativeRenderPass,
					primitiveType,
					(long) first * (indexType == VertexFormat.IndexType.INT ? 4L : 2L),
					count,
					baseVertex,
					safeInstanceCount
			)
					: MetalNativeBridge.INSTANCE.metallum_render_pass_draw(
					nativeRenderPass,
					primitiveType,
					first,
					count,
					safeInstanceCount
			);
		}

		if (nativeResult != 0) {
			throw new IllegalStateException("Native draw failed with code " + nativeResult);
		}
	}

	private void applyNativeDrawState(
			final MetalRenderPass pass,
			final MetalCompiledRenderPipeline compiled,
			final MetalGpuTexture colorTexture,
			final com.sun.jna.Pointer nativeRenderPass,
			final DepthState depthState,
			@Nullable final MetalGpuBuffer nativeVertexBuffer,
			@Nullable final MetalGpuBuffer nativeIndexBuffer,
			final VertexFormat.IndexType indexType,
			final boolean indexed
	) {
		RenderPipeline pipeline = compiled.info();
		com.sun.jna.Pointer nativePipeline = compiled.getOrCreateNativePipeline(
				this.device,
				pass.colorAttachmentFormat(),
				pass.depthAttachmentFormat(),
				pass.stencilAttachmentFormat()
		);
		if (MetalProbe.isNullPointer(nativePipeline)) {
			throw new IllegalStateException("Native pipeline is unavailable");
		}

		boolean reboundPipeline = pass.isPipelineDirty() || !samePointer(pass.nativePipeline(), nativePipeline);
		if (reboundPipeline) {
			int result = MetalNativeBridge.INSTANCE.metallum_render_pass_set_pipeline(nativeRenderPass, nativePipeline);
			if (result != 0) {
				throw new IllegalStateException("Failed to set native pipeline, code " + result);
			}
			pass.setNativePipeline(nativePipeline);
			pass.markPipelineClean();
		}

		if (reboundPipeline || pass.isDepthStateDirty()) {
			int result = MetalNativeBridge.INSTANCE.metallum_render_pass_set_depth_stencil_state(
					nativeRenderPass,
					depthState.compareOp(),
					depthState.writeDepth(),
					depthState.depthBiasScaleFactor(),
					depthState.depthBiasConstant()
			);
			if (result != 0) {
				throw new IllegalStateException("Failed to set native depth state, code " + result);
			}
			pass.markDepthStateClean();
		}

		if (reboundPipeline) {
			int result = MetalNativeBridge.INSTANCE.metallum_render_pass_set_raster_state(
				nativeRenderPass,
				pipeline.isCull() ? 1 : 0,
				pipeline.getPolygonMode() == PolygonMode.WIREFRAME ? 1 : 0,
				compiled.flipVertexY() ? 1 : 0
			);
			if (result != 0) {
				throw new IllegalStateException("Failed to set native raster state, code " + result);
			}
		}

		if (reboundPipeline || pass.isScissorDirty()) {
			int result = MetalNativeBridge.INSTANCE.metallum_render_pass_set_scissor(
					nativeRenderPass,
					pass.scissorState().enabled() ? 1 : 0,
					pass.scissorState().x(),
					pass.scissorState().y(),
					pass.scissorState().width(),
					pass.scissorState().height()
			);
			if (result != 0) {
				throw new IllegalStateException("Failed to set native scissor, code " + result);
			}
			pass.markScissorClean();
		}

		if (pass.isVertexBuffersDirty()) {
			int result = MetalNativeBridge.INSTANCE.metallum_render_pass_set_vertex_buffer(
					nativeRenderPass,
					0L,
					nativeVertexBuffer == null ? null : nativeVertexBuffer.nativeHandle(),
					0L
			);
			if (result != 0) {
				throw new IllegalStateException("Failed to set native vertex buffer, code " + result);
			}
			pass.markVertexBuffersClean();
		}

		if (indexed && (reboundPipeline || pass.isIndexBufferDirty())) {
			int result = MetalNativeBridge.INSTANCE.metallum_render_pass_set_index_buffer(
					nativeRenderPass,
					nativeIndexBuffer == null ? null : nativeIndexBuffer.nativeHandle(),
					indexType == VertexFormat.IndexType.INT ? 1L : 0L
			);
			if (result != 0) {
				throw new IllegalStateException("Failed to set native index buffer, code " + result);
			}
			pass.markIndexBufferClean();
		}

		this.applyDirtyResources(pass, compiled, colorTexture, nativeRenderPass, reboundPipeline);
	}

	private void applyDirtyResources(
			final MetalRenderPass pass,
			final MetalCompiledRenderPipeline compiled,
			final MetalGpuTexture colorTexture,
			final Pointer nativeRenderPass,
			final boolean bindAll
	) {
		Collection<MetalCompiledRenderPipeline.ResourceBinding> bindings;
		if (bindAll) {
			bindings = compiled.resources();
		} else {
			if (pass.dirtyUniforms().isEmpty()) {
				return;
			}
			for (String dirtyName : pass.dirtyUniforms()) {
				MetalCompiledRenderPipeline.ResourceBinding binding = compiled.resource(dirtyName);
				if (binding != null) {
					this.applyResourceBinding(pass, compiled, colorTexture, nativeRenderPass, binding);
				}
			}
			pass.dirtyUniforms().clear();
			return;
		}

		for (MetalCompiledRenderPipeline.ResourceBinding binding : bindings) {
			this.applyResourceBinding(pass, compiled, colorTexture, nativeRenderPass, binding);
		}

		pass.dirtyUniforms().clear();
	}

	private void applyResourceBinding(
			final MetalRenderPass pass,
			final MetalCompiledRenderPipeline compiled,
			final MetalGpuTexture colorTexture,
			final com.sun.jna.Pointer nativeRenderPass,
			final MetalCompiledRenderPipeline.ResourceBinding binding
	) {
		if (binding.kind() == MetalCompiledRenderPipeline.ResourceKind.SAMPLED_IMAGE) {
			MetalRenderPass.TextureViewAndSampler textureBinding = pass.samplers().get(binding.name());
			if (textureBinding == null) {
				int result = MetalNativeBridge.INSTANCE.metallum_render_pass_set_texture_binding(nativeRenderPass, binding.bindingIndex(), null, null, binding.stageMask());
				if (result != 0) {
					throw new IllegalStateException("Failed to unset sampler binding " + binding.name() + ", code " + result);
				}
				throw new IllegalStateException("Missing sampler " + binding.name());
			}

			if (MetalRenderPass.VALIDATION && textureBinding.textureView().isClosed()) {
				throw new IllegalStateException("Sampler " + binding.name() + " texture view has been closed");
			}

			MetalGpuTexture texture = castTexture(textureBinding.textureView().texture());
			MetalGpuTextureView textureView = (MetalGpuTextureView)textureBinding.textureView();
			if (texture == colorTexture) {
				throw new IllegalStateException("Feedback sampler is not allowed for binding " + binding.name());
			}

			MetalGpuSampler sampler = (MetalGpuSampler)textureBinding.sampler();
			int result = MetalNativeBridge.INSTANCE.metallum_render_pass_set_texture_binding(
					nativeRenderPass,
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
			this.applyTexelBufferBinding(pass, nativeRenderPass, binding);
			return;
		}

		GpuBufferSlice uniformSlice = pass.uniforms().get(binding.name());
		if (uniformSlice == null) {
			throw new IllegalStateException("Missing uniform " + binding.name());
		}
		if (MetalRenderPass.VALIDATION && uniformSlice.buffer().isClosed()) {
			throw new IllegalStateException("Uniform " + binding.name() + " buffer has been closed");
		}

		MetalGpuBuffer uniformBuffer = castBuffer(uniformSlice.buffer());
		int result = MetalNativeBridge.INSTANCE.metallum_render_pass_set_buffer_binding(
				nativeRenderPass,
				binding.bindingIndex(),
				uniformBuffer.nativeHandle(),
				uniformSlice.offset(),
				binding.stageMask()
		);
		if (result != 0) {
			throw new IllegalStateException("Failed to set uniform binding " + binding.name() + ", code " + result);
		}
	}

	private void applyTexelBufferBinding(
		final MetalRenderPass pass,
		final Pointer nativeRenderPass,
		final MetalCompiledRenderPipeline.ResourceBinding binding
	) {
		GpuBufferSlice texelSlice = pass.uniforms().get(binding.name());
		if (texelSlice == null) {
			throw new IllegalStateException("Missing texel buffer " + binding.name());
		}
		if (MetalRenderPass.VALIDATION && texelSlice.buffer().isClosed()) {
			throw new IllegalStateException("Texel buffer " + binding.name() + " has been closed");
		}

		GpuFormat texelFormat = binding.texelBufferFormat();
		if (texelFormat == null) {
			throw new IllegalStateException("Texel buffer " + binding.name() + " is missing a format");
		}

		MetalGpuBuffer texelBuffer = castBuffer(texelSlice.buffer());
		long pixelFormat = texelBufferPixelFormatCode(texelFormat);
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
			nativeRenderPass,
			binding.bindingIndex(),
			texelTexture,
			null,
			binding.stageMask()
		);
		if (result != 0) {
			MetalNativeBridge.INSTANCE.metallum_release_object(texelTexture);
			throw new IllegalStateException("Failed to set texel buffer binding " + binding.name() + ", code " + result);
		}
		this.queueForDestroy(() -> MetalNativeBridge.INSTANCE.metallum_release_object(texelTexture));
	}

	private static MetalGpuBuffer castBuffer(final GpuBuffer buffer) {
		return (MetalGpuBuffer)buffer;
	}

	private static MetalGpuTexture castTexture(final GpuTexture texture) {
		return (MetalGpuTexture)texture;
	}

	private static void writeToTextureFromRows(
		final MetalGpuTexture destination,
		final ByteBuffer source,
		final NativeImage.Format sourceFormat,
		final int mipLevel,
		final int depthOrLayer,
		final int destX,
		final int destY,
		final int width,
		final int height,
		final int sourceRowWidth,
		final int sourceX,
		final int sourceY
	) {
		if (width <= 0 || height <= 0) {
			return;
		}

		int pixelSize = destination.pixelSize();
		int rowBytes = width * pixelSize;
		int bytesPerImage = rowBytes * height;
		MetalGpuBuffer stagingBuffer = new MetalGpuBuffer(
			destination.device(),
			destination.getLabel() + " texture upload staging",
			GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_COPY_SRC,
			bytesPerImage
		);
		ByteBuffer uploadData = stagingBuffer.fullStorageView().order(ByteOrder.nativeOrder());
		uploadData.limit(bytesPerImage);
		try {
			if (canCopyRowsDirectly(destination, sourceFormat)) {
				copyRows(source, uploadData, sourceFormat.components(), sourceRowWidth, sourceX, sourceY, width, height, rowBytes);
			} else {
				for (int row = 0; row < height; row++) {
					for (int col = 0; col < width; col++) {
						int pixelBase = ((sourceY + row) * sourceRowWidth + sourceX + col) * sourceFormat.components();
						int packedOffset = (row * width + col) * pixelSize;
						writeConvertedPixel(uploadData, packedOffset, pixelSize, source, pixelBase, sourceFormat);
					}
				}
			}
			uploadPackedTextureRegion(destination, stagingBuffer, mipLevel, depthOrLayer, destX, destY, width, height, rowBytes, bytesPerImage);
		} finally {
			destination.device().createCommandEncoder().queueForDestroy(stagingBuffer::close);
		}
	}

	private static boolean canCopyRowsDirectly(final MetalGpuTexture destination, final NativeImage.Format sourceFormat) {
		if ((destination.usage() & GpuTexture.USAGE_RENDER_ATTACHMENT) != 0) {
			return false;
		}
		return sourceFormat == NativeImage.Format.RGBA && destination.pixelSize() == 4
			|| sourceFormat == NativeImage.Format.LUMINANCE && destination.pixelSize() == 1;
	}

	private static void copyRows(
		final ByteBuffer source,
		final ByteBuffer target,
		final int sourcePixelSize,
		final int sourceRowWidth,
		final int sourceX,
		final int sourceY,
		final int width,
		final int height,
		final int rowBytes
	) {
		ByteBuffer sourceDuplicate = source.duplicate();
		ByteBuffer targetDuplicate = target.duplicate();
		for (int row = 0; row < height; row++) {
			int sourceOffset = ((sourceY + row) * sourceRowWidth + sourceX) * sourcePixelSize;
			sourceDuplicate.clear();
			sourceDuplicate.position(sourceOffset);
			sourceDuplicate.limit(sourceOffset + rowBytes);
			targetDuplicate.clear();
			targetDuplicate.position(row * rowBytes);
			targetDuplicate.put(sourceDuplicate.slice());
		}
	}

	private static void writeConvertedPixel(
		final ByteBuffer target,
		final int targetOffset,
		final int pixelSize,
		final ByteBuffer source,
		final int sourceIndex,
		final NativeImage.Format sourceFormat
	) {
		int luminance = 0;
		int alpha = 255;
		int red = 0;
		int green = 0;
		int blue = 0;

		if (sourceFormat.hasLuminance()) {
			luminance = source.get(sourceIndex + sourceFormat.luminanceOffset() / 8) & 255;
			red = luminance;
			green = luminance;
			blue = luminance;
		}

		if (sourceFormat.hasRed()) {
			red = source.get(sourceIndex + sourceFormat.redOffset() / 8) & 255;
		}

		if (sourceFormat.hasGreen()) {
			green = source.get(sourceIndex + sourceFormat.greenOffset() / 8) & 255;
		}

		if (sourceFormat.hasBlue()) {
			blue = source.get(sourceIndex + sourceFormat.blueOffset() / 8) & 255;
		}

		if (sourceFormat.hasAlpha()) {
			alpha = source.get(sourceIndex + sourceFormat.alphaOffset() / 8) & 255;
		}

		if (pixelSize == 1) {
			target.put(targetOffset, (byte)(sourceFormat.hasAlpha() ? alpha : red));
		} else if (pixelSize == 4) {
			target.put(targetOffset, (byte)red);
			target.put(targetOffset + 1, (byte)green);
			target.put(targetOffset + 2, (byte)blue);
			target.put(targetOffset + 3, (byte)alpha);
		} else {
			int copyLength = Math.min(pixelSize, sourceFormat.components());
			for (int i = 0; i < copyLength; i++) {
				target.put(targetOffset + i, source.get(sourceIndex + i));
			}

			for (int i = copyLength; i < pixelSize; i++) {
				target.put(targetOffset + i, (byte)0);
			}
		}
	}

	private static void uploadPackedTextureRegion(
		final MetalGpuTexture texture,
		final MetalGpuBuffer stagingBuffer,
		final int mipLevel,
		final int depthOrLayer,
		final int x,
		final int y,
		final int width,
		final int height,
		final int rowBytes,
		final int bytesPerImage
	) {
		if (width <= 0 || height <= 0) {
			return;
		}

		int result = MetalNativeBridge.INSTANCE.metallum_copy_buffer_to_texture(
			texture.device().commandQueue(),
			stagingBuffer.nativeHandle(),
			0L,
			texture.nativeHandle(),
			mipLevel,
			depthOrLayer,
			x,
			y,
			width,
			height,
			rowBytes,
			bytesPerImage
		);
		if (result != 0) {
			LOGGER.warn("Failed to upload Metal texture region '{}' mip {} layer {}", texture.getLabel(), mipLevel, depthOrLayer);
			return;
		}
	}

	private static boolean samePointer(@Nullable final Pointer left, @Nullable final Pointer right) {
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

	private static long texelBufferPixelFormatCode(final GpuFormat format) {
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

	private static long primitiveTypeCode(final VertexFormat.Mode mode) {
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

	private static DepthState depthState(final RenderPipeline pipeline) {
		var depthStencilState = pipeline.getDepthStencilState();
		if (depthStencilState == null) {
			return new DepthState(1L, 0, 0.0, 0.0);
		}

		return new DepthState(
			toCompareOpCode(depthStencilState.depthTest()),
			depthStencilState.writeDepth() ? 1 : 0,
			depthStencilState.depthBiasScaleFactor(),
			depthStencilState.depthBiasConstant()
		);
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

	private static long toCompareOpCode(final com.mojang.blaze3d.platform.CompareOp op) {
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

	private record DepthState(
		long compareOp,
		int writeDepth,
		double depthBiasScaleFactor,
		double depthBiasConstant
	) {
	}

}
