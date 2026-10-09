package com.metallum.render;

import io.github.kokodio.metaljvm.metal.*;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.GpuTexture;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;


@Environment(EnvType.CLIENT)
final class MetalGpuTexture extends GpuTexture {

    private final MetalDevice device;
    private final MTLPixelFormat mtlPixelFormat;
    private boolean closed;
    @Nullable
    private Vector4fc materializedColorClear;
    @Nullable
    private Double materializedDepthClear;
    private int views = 1;
    @Nullable
    private MTLTexture metalTexture;
    private final MTLTextureType textureType;
    private final long sliceCount;

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
        this.mtlPixelFormat = MetalConversions.pixelFormat(format);

        MTLTextureDescriptor descriptor = MTLTextureDescriptor.alloc().init();
        descriptor.setPixelFormat(this.mtlPixelFormat);
        descriptor.setWidth(width);
        descriptor.setHeight(height);
        if ((usage & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0) {
            long cubes = depthOrLayers > 6 ? depthOrLayers / 6 : 1;
            this.textureType = depthOrLayers > 6 ? MTLTextureType.TypeCubeArray : MTLTextureType.TypeCube;
            this.sliceCount = cubes * 6;
            descriptor.setArrayLength(cubes);
        } else if (depthOrLayers > 1) {
            this.textureType = MTLTextureType.Type2DArray;
            this.sliceCount = depthOrLayers;
            descriptor.setArrayLength(depthOrLayers);
        } else {
            this.textureType = MTLTextureType.Type2D;
            this.sliceCount = 1;
        }
        descriptor.setTextureType(this.textureType);
        descriptor.setMipmapLevelCount(Math.max(mipLevels, 1));
        descriptor.setUsage(toMtlTextureUsage(usage));
        descriptor.setStorageMode(MTLStorageMode.Private);
        descriptor.setHazardTrackingMode(MTLHazardTrackingMode.Untracked);
        this.metalTexture = MetalUtilities.nonNil(device.metalDevice().newTexture(descriptor), "newTextureWithDescriptor:");
        descriptor.release();
        if (!label.isEmpty()) {
            this.metalTexture.setLabel(label);
        }
    }

    int pixelSize() {
        return this.getFormat().blockSize();
    }

    void recordMaterializedClear(@Nullable final Vector4fc color, @Nullable final Double depth) {
        if (color != null) {
            this.materializedColorClear = color;
        }
        if (depth != null) {
            this.materializedDepthClear = depth;
        }
    }

    boolean clearIsRedundant(@Nullable final Vector4fc color, @Nullable final Double depth) {
        return (color == null || color.equals(this.materializedColorClear))
                && (depth == null || depth.equals(this.materializedDepthClear));
    }

    void markContentsDirty() {
        this.materializedColorClear = null;
        this.materializedDepthClear = null;
    }

    MTLTexture metalTexture() {
        if (this.metalTexture == null) {
            throw new IllegalStateException("Native Metal texture is closed");
        }
        return this.metalTexture;
    }

    MTLTextureType textureType() {
        return this.textureType;
    }

    long sliceCount() {
        return this.sliceCount;
    }

    void queueNativeRelease(final MTLTexture texture) {
        this.device.queueResourceRelease(texture);
    }

    void addView() {
        this.views++;
    }

    void removeView() {
        this.views--;
        if (this.views < 0) {
            throw new IllegalStateException("Too many views removed from texture");
        }
        if (this.closed && this.views == 0 && this.metalTexture != null) {
            MTLTexture released = this.metalTexture;
            this.metalTexture = null;
            this.device.queueResourceRelease(released);
        }
    }

    MTLPixelFormat mtlPixelFormat() {
        return this.mtlPixelFormat;
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

    private static long toMtlTextureUsage(@GpuTexture.Usage final int usage) {
        long result = 0L;
        if ((usage & GpuTexture.USAGE_TEXTURE_BINDING) != 0 || (usage & GpuTexture.USAGE_COPY_DST) != 0 || (usage & GpuTexture.USAGE_COPY_SRC) != 0) {
            result |= MTLTextureUsage.ShaderRead;
        }
        if ((usage & GpuTexture.USAGE_RENDER_ATTACHMENT) != 0) {
            result |= MTLTextureUsage.RenderTarget;
            result |= MTLTextureUsage.ShaderRead;
        }
        return result == 0L ? MTLTextureUsage.ShaderRead : result;
    }
}
