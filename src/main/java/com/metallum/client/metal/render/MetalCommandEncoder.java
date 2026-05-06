package com.metallum.client.metal.render;

import com.metallum.Metallum;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.GpuQueryPool;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

@Environment(EnvType.CLIENT)
final class MetalCommandEncoder implements CommandEncoderBackend {
	public static final int MAX_SUBMITS_IN_FLIGHT = 2;
	private final MetalDevice device;
	private long currentSubmitIndex = 2L;
	private long completedSubmitIndex = 0L;
	private final MetalDestructionQueue destroyQueue = new MetalDestructionQueue(MAX_SUBMITS_IN_FLIGHT + 1);
	private static final org.slf4j.Logger LOGGER = Metallum.LOGGER;
	private final Map<MetalGpuTexture, PendingTextureClear> pendingTextureClears = new IdentityHashMap<>();
	@Nullable
	private MetalRenderPass currentRenderPass;

	record ClearColor(float red, float green, float blue, float alpha) {
		static final ClearColor TRANSPARENT = new ClearColor(0.0F, 0.0F, 0.0F, 0.0F);

		static ClearColor copy(final Vector4fc color) {
			return new ClearColor(color.x(), color.y(), color.z(), color.w());
		}
	}

	MetalCommandEncoder(final MetalDevice device) {
		this.device = device;
	}

	@Override
	public void submit() {
		if (!this.awaitSubmitCompletion(this.currentSubmitIndex - MAX_SUBMITS_IN_FLIGHT, 5000L)) {
			throw new IllegalStateException("5s timeout reached when waiting for Metal submit completion");
		}

		int result = MetalNativeBridge.INSTANCE.metallum_signal_submit(this.device.commandQueue(), this.currentSubmitIndex);
		if (result != 0) {
			throw new IllegalStateException("Failed to signal Metal submit " + this.currentSubmitIndex + " (code " + result + ")");
		}
		this.currentSubmitIndex++;
		this.destroyQueue.rotate();
	}

	@Override
	public RenderPassBackend createRenderPass(final RenderPassDescriptor descriptor) {
		List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> colorAttachments = descriptor.colorAttachments();
		if (colorAttachments.isEmpty() || colorAttachments.get(0) == null) {
			throw new UnsupportedOperationException("Metal render passes currently require one color attachment");
		}
		if (colorAttachments.size() > 1) {
			throw new UnsupportedOperationException("Metal render passes currently support only one color attachment");
		}

		RenderPassDescriptor.Attachment<Optional<Vector4fc>> colorAttachment = colorAttachments.get(0);
		GpuTextureView colorTexture = colorAttachment.textureView();
		Optional<ClearColor> resolvedColorClear = this.resolveColorAttachmentClear(colorTexture, toClearColor(colorAttachment.clearValue()));

		RenderPassDescriptor.Attachment<OptionalDouble> depthAttachment = descriptor.depthAttachment();
		GpuTextureView depthTexture = depthAttachment == null ? null : depthAttachment.textureView();
		OptionalDouble resolvedDepthClear = depthAttachment == null
			? OptionalDouble.empty()
			: this.resolveDepthAttachmentClear(depthTexture, depthAttachment.clearValue());
		RenderPass.RenderArea renderArea = descriptor.renderArea != null
			? descriptor.renderArea
			: new RenderPass.RenderArea(0, 0, colorTexture.getWidth(0), colorTexture.getHeight(0));
		return this.currentRenderPass = new MetalRenderPass(
			this.device,
			this,
			descriptor.label(),
			colorTexture,
			depthTexture,
			renderArea,
			resolvedColorClear,
			resolvedDepthClear
		);
	}

	@Override
	public void submitRenderPass() {
		if (this.currentRenderPass != null) {
			this.currentRenderPass.end();
		}
		this.currentRenderPass = null;
	}

	@Override
	public void clearColorTexture(final GpuTexture colorTexture, final Vector4fc clearColor) {
		MetalGpuTexture texture = castTexture(colorTexture);
		this.deferColorClear(texture, ClearColor.copy(clearColor));
	}

	@Override
	public void clearColorAndDepthTextures(final GpuTexture colorTexture, final Vector4fc clearColor, final GpuTexture depthTexture, final double clearDepth) {
		MetalGpuTexture color = castTexture(colorTexture);
		MetalGpuTexture depth = castTexture(depthTexture);
		this.deferColorClear(color, ClearColor.copy(clearColor));
		this.deferDepthClear(depth, clearDepth);
	}

	@Override
	public void clearColorAndDepthTextures(
		final GpuTexture colorTexture,
		final Vector4fc clearColor,
		final GpuTexture depthTexture,
		final double clearDepth,
		final int regionX,
		final int regionY,
		final int regionWidth,
		final int regionHeight
	) {
		MetalGpuTexture color = castTexture(colorTexture);
		MetalGpuTexture depth = castTexture(depthTexture);
		ClearColor resolvedClearColor = ClearColor.copy(clearColor);
		if (isFullTextureRegion(color, depth, regionX, regionY, regionWidth, regionHeight)) {
			this.deferColorClear(color, resolvedClearColor);
			this.deferDepthClear(depth, clearDepth);
			return;
		}
		int result = MetalNativeBridge.INSTANCE.metallum_clear_color_depth_textures_region(
				this.device.commandQueue(),
				color.nativeHandle(),
				resolvedClearColor.red(),
				resolvedClearColor.green(),
				resolvedClearColor.blue(),
				resolvedClearColor.alpha(),
				depth.nativeHandle(),
				clearDepth,
				regionX,
				regionY,
				regionWidth,
				regionHeight
		);
		if (result != 0) {
			LOGGER.warn("Failed to clear Metal color/depth texture region '{}' / '{}' {}x{} at {},{}", color.getLabel(), depth.getLabel(), regionWidth, regionHeight, regionX, regionY);
		}
	}

	@Override
	public void clearDepthTexture(final GpuTexture depthTexture, final double clearDepth) {
		MetalGpuTexture texture = castTexture(depthTexture);
		this.deferDepthClear(texture, clearDepth);
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

		MetalGpuBuffer stagingBuffer = this.createStagingBuffer(buffer.label() == null ? null : buffer.label() + " upload staging", source);
		try {
			int result = MetalNativeBridge.INSTANCE.metallum_copy_buffer_to_buffer(
				this.device.commandQueue(),
				stagingBuffer.nativeHandle(),
				0L,
				buffer.nativeHandle(),
				destination.offset(),
				length
			);
			if (result != 0) {
				throw new IllegalStateException("Failed to upload Metal buffer '" + buffer.label() + "' (code " + result + ")");
			}
		} finally {
			this.queueForDestroy(stagingBuffer::close);
		}
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
		MetalGpuTexture texture = castTexture(destination);
		int pixelSize = texture.pixelSize();
		int sourceWidth = source.getWidth();
		int sourceHeight = source.getHeight();
		long sourceOffset = ((long)sourceX + (long)sourceY * sourceWidth) * pixelSize;
		ByteBuffer sourceBytes = MemoryUtil.memByteBuffer(source.getPointer(), sourceWidth * sourceHeight * pixelSize);
		this.writeToTexture(texture, sourceBytes, sourceOffset, mipLevel, depthOrLayer, destX, destY, width, height, sourceWidth, sourceHeight);
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
		this.writeToTexture(castTexture(destination), source.duplicate(), 0L, mipLevel, depthOrLayer, destX, destY, width, height, width, height);
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
		MetalGpuTexture texture = castTexture(source);
		this.flushPendingClear(texture);
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
		MetalGpuTexture srcTexture = castTexture(source);
		MetalGpuTexture dstTexture = castTexture(destination);
		this.flushPendingClear(srcTexture);
		this.flushPendingClear(dstTexture);
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
		this.destroyQueue.close();
	}

	@Override
	public void writeTimestamp(final GpuQueryPool pool, final int index) {
		if (pool instanceof MetalGpuQueryPool metalPool && index >= 0 && index < pool.size()) {
			metalPool.setValue(index, this.device.getTimestampNow());
		}
	}

	static MetalGpuBuffer castBuffer(final GpuBuffer buffer) {
		return (MetalGpuBuffer)buffer;
	}

	static MetalGpuTexture castTexture(final GpuTexture texture) {
		return (MetalGpuTexture)texture;
	}

	void flushPendingTextureViewClear(final GpuTextureView textureView) {
		this.flushPendingClear(castTexture(textureView.texture()));
	}

	boolean deferRenderPassClear(
		final GpuTextureView colorTexture,
		final Optional<ClearColor> clearColor,
		@Nullable final GpuTextureView depthTexture,
		final OptionalDouble clearDepth
	) {
		if (clearColor.isPresent() && !isFullTextureView(colorTexture)) {
			return false;
		}
		if (clearDepth.isPresent() && (depthTexture == null || !isFullTextureView(depthTexture))) {
			return false;
		}
		clearColor.ifPresent(color -> this.deferColorClear(castTexture(colorTexture.texture()), color));
		if (clearDepth.isPresent()) {
			this.deferDepthClear(castTexture(depthTexture.texture()), clearDepth.getAsDouble());
		}
		return true;
	}

	private void deferColorClear(final MetalGpuTexture texture, final ClearColor clearColor) {
		PendingTextureClear state = this.pendingTextureClears.computeIfAbsent(texture, ignored -> new PendingTextureClear());
		state.color = Optional.of(clearColor);
	}

	private void deferDepthClear(final MetalGpuTexture texture, final double clearDepth) {
		PendingTextureClear state = this.pendingTextureClears.computeIfAbsent(texture, ignored -> new PendingTextureClear());
		state.depth = OptionalDouble.of(clearDepth);
	}

	private void flushPendingClear(final MetalGpuTexture texture) {
		PendingTextureClear pending = this.pendingTextureClears.remove(texture);
		if (pending == null) {
			return;
		}
		if (pending.color.isPresent()) {
			int result = MetalNativeBridge.INSTANCE.metallum_clear_texture(
				this.device.commandQueue(),
				texture.nativeHandle(),
				1,
				pending.color.get().red(),
				pending.color.get().green(),
				pending.color.get().blue(),
				pending.color.get().alpha(),
				0,
				1.0
			);
			if (result != 0) {
				LOGGER.warn("Failed to flush deferred Metal color clear '{}'", texture.getLabel());
			}
		}
		if (pending.depth.isPresent()) {
			int result = MetalNativeBridge.INSTANCE.metallum_clear_texture(
				this.device.commandQueue(),
				texture.nativeHandle(),
				0,
				0.0F,
				0.0F,
				0.0F,
				0.0F,
				1,
				pending.depth.getAsDouble()
			);
			if (result != 0) {
				LOGGER.warn("Failed to flush deferred Metal depth clear '{}'", texture.getLabel());
			}
		}
	}

	private Optional<ClearColor> resolveColorAttachmentClear(
		final GpuTextureView textureView,
		final Optional<ClearColor> explicitClear
	) {
		MetalGpuTexture texture = castTexture(textureView.texture());
		PendingTextureClear pending = this.pendingTextureClears.get(texture);
		if (pending == null || pending.color.isEmpty()) {
			return explicitClear;
		}
		if (!isFullTextureView(textureView)) {
			this.flushPendingClear(texture);
			return explicitClear;
		}
		Optional<ClearColor> clear = explicitClear.isPresent() ? explicitClear : pending.color;
		pending.color = Optional.empty();
		this.removePendingIfEmpty(texture, pending);
		return clear;
	}

	private OptionalDouble resolveDepthAttachmentClear(
		final GpuTextureView textureView,
		final OptionalDouble explicitClear
	) {
		MetalGpuTexture texture = castTexture(textureView.texture());
		PendingTextureClear pending = this.pendingTextureClears.get(texture);
		if (pending == null || pending.depth.isEmpty()) {
			return explicitClear;
		}
		if (!isFullTextureView(textureView)) {
			this.flushPendingClear(texture);
			return explicitClear;
		}
		OptionalDouble clear = explicitClear.isPresent() ? explicitClear : pending.depth;
		pending.depth = OptionalDouble.empty();
		this.removePendingIfEmpty(texture, pending);
		return clear;
	}

	private void removePendingIfEmpty(final MetalGpuTexture texture, final PendingTextureClear pending) {
		if (pending.color.isEmpty() && pending.depth.isEmpty()) {
			this.pendingTextureClears.remove(texture);
		}
	}

	private static boolean isFullTextureView(final GpuTextureView textureView) {
		return textureView.baseMipLevel() == 0
			&& textureView.mipLevels() >= textureView.texture().getMipLevels()
			&& textureView.texture().getDepthOrLayers() == 1;
	}

	private static boolean isFullTextureRegion(
		final MetalGpuTexture color,
		final MetalGpuTexture depth,
		final int x,
		final int y,
		final int width,
		final int height
	) {
		return x == 0
			&& y == 0
			&& width == color.getWidth(0)
			&& height == color.getHeight(0)
			&& width == depth.getWidth(0)
			&& height == depth.getHeight(0);
	}

	private static Optional<ClearColor> toClearColor(final Optional<Vector4fc> clearColor) {
		return clearColor.map(ClearColor::copy);
	}

	private static final class PendingTextureClear {
		Optional<ClearColor> color = Optional.empty();
		OptionalDouble depth = OptionalDouble.empty();
	}

	private MetalGpuBuffer createStagingBuffer(@Nullable final String label, final ByteBuffer data) {
		ByteBuffer source = data.duplicate();
		int length = source.remaining();
		MetalGpuBuffer stagingBuffer = new MetalGpuBuffer(
			this.device,
			label,
			GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_COPY_SRC,
			length
		);
		ByteBuffer staging = stagingBuffer.fullStorageView().order(ByteOrder.nativeOrder());
		staging.limit(length);
		staging.put(source);
		return stagingBuffer;
	}

	private void writeToTexture(
		final MetalGpuTexture destination,
		final ByteBuffer source,
		final long sourceOffset,
		final int mipLevel,
		final int depthOrLayer,
		final int destX,
		final int destY,
		final int width,
		final int height,
		final int sourceWidth,
		final int sourceHeight
	) {
		if (width <= 0 || height <= 0) {
			return;
		}
		this.flushPendingClear(destination);

		int pixelSize = destination.pixelSize();
		long bytesPerRow = (long)sourceWidth * pixelSize;
		long bytesPerImage = bytesPerRow * sourceHeight;
		long requiredBytes = sourceOffset + (long)(height - 1) * bytesPerRow + (long)width * pixelSize;
		if (sourceOffset < 0L || requiredBytes > source.remaining()) {
			throw new IllegalArgumentException("Texture upload source buffer is too small");
		}

		MetalGpuBuffer stagingBuffer = this.createStagingBuffer(
			destination.device().useLabels() ? destination.getLabel() + " texture upload staging" : null,
			source
		);
		try {
			int result = MetalNativeBridge.INSTANCE.metallum_copy_buffer_to_texture(
				destination.device().commandQueue(),
				stagingBuffer.nativeHandle(),
				sourceOffset,
				destination.nativeHandle(),
				mipLevel,
				depthOrLayer,
				destX,
				destY,
				width,
				height,
				bytesPerRow,
				bytesPerImage
			);
			if (result != 0) {
				LOGGER.warn("Failed to upload Metal texture region '{}' mip {} layer {}", destination.getLabel(), mipLevel, depthOrLayer);
			}
		} finally {
			this.queueForDestroy(stagingBuffer::close);
		}
	}
}
