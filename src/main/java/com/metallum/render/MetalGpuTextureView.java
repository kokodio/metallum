package com.metallum.render;

import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import io.github.kokodio.metaljvm.foundation.NSRange;
import io.github.kokodio.metaljvm.metal.MTLTexture;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;


@Environment(EnvType.CLIENT)
final class MetalGpuTextureView extends GpuTextureView {
    private boolean closed;
    @Nullable
    private MTLTexture metalTexture;

    MetalGpuTextureView(final GpuTexture texture, final int baseMipLevel, final int mipLevels) {
        super(texture, baseMipLevel, mipLevels);
        ((MetalGpuTexture) texture).addView();
    }

    MTLTexture metalTexture() {
        if (this.metalTexture == null) {
            MetalGpuTexture texture = (MetalGpuTexture) this.texture();
            if (this.baseMipLevel() == 0 && this.mipLevels() >= texture.getMipLevels()) {
                texture.metalTexture().retain();
                this.metalTexture = texture.metalTexture();
            } else {
                boolean validRange = this.mipLevels() > 0 && this.baseMipLevel() + this.mipLevels() <= texture.getMipLevels();
                MTLTexture view = validRange
                        ? texture.metalTexture().newTextureViewWithPixelFormat(
                        texture.mtlPixelFormat(),
                        texture.textureType(),
                        new NSRange(this.baseMipLevel(), this.mipLevels()),
                        new NSRange(0, texture.sliceCount()))
                        : null;
                if (view == null) {
                    throw new IllegalStateException(
                            "Failed to create Metal texture view for mip range " + this.baseMipLevel() + "+" + this.mipLevels()
                    );
                }
                if (!texture.getLabel().isEmpty()) {
                    view.setLabel(texture.getLabel());
                }
                this.metalTexture = view;
            }
        }
        return this.metalTexture;
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        MTLTexture handle = this.metalTexture();
        this.closed = true;
        MetalGpuTexture texture = (MetalGpuTexture) this.texture();
        texture.queueNativeRelease(handle);
        texture.removeView();
    }

    @Override
    public boolean isClosed() {
        return this.closed;
    }
}
