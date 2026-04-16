package com.metallum.client.metal;

import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.sun.jna.Pointer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

@Environment(EnvType.CLIENT)
final class MetalGpuTextureView extends GpuTextureView {
	private boolean closed;
	@Nullable
	private Pointer nativeHandle;
	private boolean ownsNativeHandle;

	MetalGpuTextureView(final GpuTexture texture, final int baseMipLevel, final int mipLevels) {
		super(texture, baseMipLevel, mipLevels);
		((MetalGpuTexture)texture).addView();
	}

	Pointer nativeHandle() {
		if (this.closed) {
			throw new IllegalStateException("Texture view is closed");
		}

		MetalGpuTexture texture = (MetalGpuTexture)this.texture();
		if (this.baseMipLevel() == 0 && this.mipLevels() >= texture.getMipLevels()) {
			return texture.nativeHandle();
		}
		if (this.nativeHandle == null) {
			Pointer viewHandle = MetalNativeBridge.INSTANCE.metallum_create_texture_view(
				texture.nativeHandle(),
				this.baseMipLevel(),
				this.mipLevels()
			);
			if (MetalProbe.isNullPointer(viewHandle)) {
				throw new IllegalStateException(
					"Failed to create Metal texture view for mip range " + this.baseMipLevel() + "+" + this.mipLevels()
				);
			}
			this.nativeHandle = viewHandle;
			this.ownsNativeHandle = true;
		}
		return this.nativeHandle;
	}

	@Override
	public void close() {
		if (this.closed) {
			return;
		}
		if (this.ownsNativeHandle && this.nativeHandle != null) {
			Pointer handle = this.nativeHandle;
			MetalGpuTexture texture = (MetalGpuTexture)this.texture();
			this.nativeHandle = null;
			this.ownsNativeHandle = false;
			texture.queueNativeRelease(handle);
		}
		this.closed = true;
		((MetalGpuTexture)this.texture()).removeView();
	}

	@Override
	public boolean isClosed() {
		return this.closed;
	}
}
