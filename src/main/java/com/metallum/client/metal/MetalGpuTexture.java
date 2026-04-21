package com.metallum.client.metal;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.GpuTexture;
import com.sun.jna.Pointer;
import java.nio.ByteBuffer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

@Environment(EnvType.CLIENT)
final class MetalGpuTexture extends GpuTexture {
	private final MetalDevice device;
	private final int[] mipOffsets;
	private final long mtlPixelFormat;
	private boolean closed;
	private int views = 1;
	@Nullable
	private Pointer nativeHandle;

	MetalGpuTexture(
		final MetalDevice device,
		@GpuTexture.Usage final int usage,
		final String label,
		final GpuFormat format,
		final int width,
		final int height,
		final int depthOrLayers,
		final int mipLevels
	) {
		super(usage, label, format, width, height, depthOrLayers, mipLevels);
		this.device = device;
		this.mipOffsets = new int[mipLevels];
		long totalSize = 0L;

		for (int mipLevel = 0; mipLevel < mipLevels; mipLevel++) {
			this.mipOffsets[mipLevel] = Math.toIntExact(totalSize);
			totalSize += this.layerSizeBytes(mipLevel) * (long)depthOrLayers;
		}

		if (totalSize > Integer.MAX_VALUE) {
			throw new UnsupportedOperationException("Metal texture stub only supports textures up to 2 GiB");
		}

		this.mtlPixelFormat = toMtlPixelFormat(format, usage);
		long storageMode = toMtlStorageMode(usage, format);
		this.nativeHandle = MetalNativeBridge.INSTANCE.metallum_create_texture_2d(
			device.metalDevicePointer(),
			this.mtlPixelFormat,
			width,
			height,
			depthOrLayers,
			mipLevels,
			(usage & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0 ? 1L : 0L,
			toMtlTextureUsage(usage),
			storageMode,
			label
		);
	}

	int pixelSize() {
		return this.getFormat().pixelSize();
	}

	int mipWidth(final int mipLevel) {
		return Math.max(1, this.getWidth(mipLevel));
	}

	int mipHeight(final int mipLevel) {
		return Math.max(1, this.getHeight(mipLevel));
	}

	int layerSizeBytes(final int mipLevel) {
		return this.mipWidth(mipLevel) * this.mipHeight(mipLevel) * this.pixelSize();
	}

	int pixelOffset(final int mipLevel, final int depthOrLayer, final int x, final int y) {
		return this.mipOffsets[mipLevel] + depthOrLayer * this.layerSizeBytes(mipLevel) + (y * this.mipWidth(mipLevel) + x) * this.pixelSize();
	}

	ByteBuffer sliceStorage(final int byteOffset, final int byteLength) {
		throw new IllegalStateException("Metal textures are GPU-only and do not expose CPU storage");
	}

	ByteBuffer fullStorageView() {
		throw new IllegalStateException("Metal textures are GPU-only and do not expose CPU storage");
	}

	boolean hasCpuStorage() {
		return false;
	}

	Pointer nativeHandle() {
		if (this.nativeHandle == null) {
			throw new IllegalStateException("Native Metal texture is closed");
		}
		return this.nativeHandle;
	}

	MetalDevice device() {
		return this.device;
	}

	void queueNativeRelease(final Pointer handle) {
		this.device.queueResourceRelease(handle);
	}

	void addView() {
		this.views++;
	}

	void removeView() {
		this.views--;
		if (this.views < 0) {
			throw new IllegalStateException("Too many views removed from texture");
		}
		if (this.closed && this.views == 0 && this.nativeHandle != null) {
			Pointer handle = this.nativeHandle;
			this.nativeHandle = null;
			this.device.queueResourceRelease(handle);
		}
	}

	long mtlPixelFormat() {
		return this.mtlPixelFormat;
	}

	long mtlStencilPixelFormat() {
		return switch ((int)this.mtlPixelFormat) {
			case 255, 260 -> this.mtlPixelFormat;
			default -> 0L;
		};
	}

	@Override
	public void close() {
		if (this.closed) {
			return;
		}
		this.closed = true;
		this.removeView();
	}

	@Override
	public boolean isClosed() {
		return this.closed;
	}

	private static long toMtlPixelFormat(final GpuFormat format, @GpuTexture.Usage final int usage) {
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
			case RGBA8_UNORM -> usePresentCompatibleBgra(usage) ? 80L : 70L;
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
			case D16_UNORM -> 250L;
			case D32_FLOAT -> 252L;
			case S8_UINT -> 253L;
			case D24_UNORM_S8_UINT -> 255L;
			case D32_FLOAT_S8_UINT -> 260L;
			case RGB8_UNORM, RGB8_SNORM, RGB8_UINT, RGB8_SINT, RGB16_UNORM, RGB16_SNORM, RGB16_UINT, RGB16_SINT, RGB16_FLOAT, RGB32_UINT, RGB32_SINT, RGB32_FLOAT, RGB10A2_UINT -> throw new UnsupportedOperationException("Unsupported Metal pixel format mapping for " + format);
		};
	}

	private static boolean usePresentCompatibleBgra(@GpuTexture.Usage final int usage) {
		// CAMetalLayer presents BGRA. Minecraft may still mark the main render target
		// as COPY_DST, so key only off render-attachment usage to keep present on blit.
		return (usage & GpuTexture.USAGE_RENDER_ATTACHMENT) != 0;
	}

	private static long toMtlTextureUsage(@GpuTexture.Usage final int usage) {
		long result = 0L;
		if ((usage & GpuTexture.USAGE_TEXTURE_BINDING) != 0 || (usage & GpuTexture.USAGE_COPY_DST) != 0 || (usage & GpuTexture.USAGE_COPY_SRC) != 0) {
			result |= 1L; // MTLTextureUsageShaderRead
		}
		if ((usage & GpuTexture.USAGE_RENDER_ATTACHMENT) != 0) {
			result |= 4L; // MTLTextureUsageRenderTarget
			result |= 1L; // Render targets are sampled in presentation/composite paths.
		}
		return result == 0L ? 1L : result;
	}

	private static long toMtlStorageMode(@GpuTexture.Usage final int usage, final GpuFormat format) {
		return 2L; // MTLStorageModePrivate. CPU writes go through staging buffers on the command queue.
	}
}
