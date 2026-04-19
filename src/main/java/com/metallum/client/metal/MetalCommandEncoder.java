package com.metallum.client.metal;

import com.metallum.Metallum;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.GpuQueryPool;
import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.function.Supplier;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

@Environment(EnvType.CLIENT)
final class MetalCommandEncoder implements CommandEncoderBackend {
	public static final int MAX_SUBMITS_IN_FLIGHT = 2;
	private final MetalDevice device;
	private long currentSubmitIndex = 2L;
	private long completedSubmitIndex = 0L;
	private final MetalDestructionQueue destroyQueue = new MetalDestructionQueue(MAX_SUBMITS_IN_FLIGHT);
	private static final org.slf4j.Logger LOGGER = Metallum.LOGGER;
	@Nullable
	private MetalRenderPass currentRenderPass;

	MetalCommandEncoder(final MetalDevice device) {
		this.device = device;
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
		return this.currentRenderPass = new MetalRenderPass(
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
	}

	@Override
	public void submitRenderPass() {
		if (this.currentRenderPass != null) {
			this.currentRenderPass.end();
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
		MetalGpuTexture color = castTexture(colorTexture);
		MetalGpuTexture depth = castTexture(depthTexture);
		this.submitRenderPass();
		int result = MetalNativeBridge.INSTANCE.metallum_clear_color_depth_textures_region(
				this.device.commandQueue(),
				color.nativeHandle(),
				clearColor,
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
		this.writeToTextureFromRows(castTexture(destination), sourceBytes, source.format(), mipLevel, depthOrLayer, destX, destY, width, height, source.getWidth(), sourceX, sourceY);
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
		this.writeToTextureFromRows(castTexture(destination), source.duplicate(), format, mipLevel, depthOrLayer, destX, destY, width, height, width, 0, 0);
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

	static MetalGpuBuffer castBuffer(final GpuBuffer buffer) {
		return (MetalGpuBuffer)buffer;
	}

	static MetalGpuTexture castTexture(final GpuTexture texture) {
		return (MetalGpuTexture)texture;
	}

	private void writeToTextureFromRows(
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
			destination.device().useLabels() ? destination.getLabel() + " texture upload staging" : null,
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
			this.queueForDestroy(stagingBuffer::close);
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
}
