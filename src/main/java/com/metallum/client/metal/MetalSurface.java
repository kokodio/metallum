package com.metallum.client.metal;

import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.GpuSurface;
import com.mojang.blaze3d.systems.GpuSurfaceBackend;
import com.mojang.blaze3d.systems.SurfaceException;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import com.sun.jna.Pointer;
import org.jspecify.annotations.Nullable;

@Environment(EnvType.CLIENT)
final class MetalSurface implements GpuSurfaceBackend {
	private static final Set<GpuSurface.PresentMode> SUPPORTED_PRESENT_MODES = EnumSet.of(GpuSurface.PresentMode.FIFO, GpuSurface.PresentMode.IMMEDIATE);
	private final long windowHandle;
	private final MetalDevice device;
	private final MetalCocoaBootstrap.BootstrapContext bootstrap;
	private GpuSurface.Configuration configuration;
	@Nullable
	private Pointer currentDrawable;
	private boolean blittedToDrawable;

	MetalSurface(final long windowHandle, final MetalDevice device, final MetalCocoaBootstrap.BootstrapContext bootstrap) {
		this.windowHandle = windowHandle;
		this.device = device;
		this.bootstrap = bootstrap;
	}

	@Override
	public void configure(final GpuSurface.Configuration config) throws SurfaceException {
		if (config.width() <= 0 || config.height() <= 0) {
			throw new SurfaceException("Metal surface configuration must be positive, got " + config.width() + "x" + config.height());
		}

		int nativeResult = MetalNativeBridge.INSTANCE.metallum_configure_layer(
			this.bootstrap.metalLayer(),
			config.width(),
			config.height(),
			config.presentMode() == GpuSurface.PresentMode.IMMEDIATE ? 1 : 0
		);
		if (nativeResult != 0) {
			throw new SurfaceException("Failed to configure CAMetalLayer drawable size for " + config.width() + "x" + config.height());
		}

		this.configuration = config;
	}

	@Override
	public boolean isSuboptimal() {
		return false;
	}

	@Override
	public void acquireNextTexture() throws SurfaceException {
		if (this.configuration == null) {
			throw new SurfaceException("Metal surface must be configured before acquire");
		}
		this.currentDrawable = MetalNativeBridge.INSTANCE.metallum_acquire_next_drawable(this.bootstrap.metalLayer());
		if (MetalProbe.isNullPointer(this.currentDrawable)) {
			this.currentDrawable = null;
			throw new SurfaceException("Failed to acquire CAMetalDrawable");
		}
		this.blittedToDrawable = false;
	}

	@Override
	public void blitFromTexture(final CommandEncoderBackend commandEncoder, final GpuTextureView textureView) {
		if (this.currentDrawable == null) {
			throw new IllegalStateException("Cannot blit to a Metal surface without an acquired drawable");
		}

		MetalGpuTexture source = (MetalGpuTexture)textureView.texture();
		int result = MetalNativeBridge.INSTANCE.metallum_copy_texture_to_drawable(
			this.device.commandQueue(),
			this.currentDrawable,
			source.nativeHandle()
		);
		if (result != 0) {
			throw new IllegalStateException("Failed to blit Metal texture '" + source.getLabel() + "' to drawable (code " + result + ")");
		}
		this.blittedToDrawable = true;
	}

	@Override
	public void present() {
		if (this.currentDrawable != null && this.blittedToDrawable) {
			MetalNativeBridge.INSTANCE.metallum_present_drawable(this.device.commandQueue(), this.currentDrawable);
		}
		if (this.currentDrawable != null) {
			MetalNativeBridge.INSTANCE.metallum_release_object(this.currentDrawable);
			this.currentDrawable = null;
		}
		this.blittedToDrawable = false;
	}

	@Override
	public void close() {
		if (this.currentDrawable != null) {
			MetalNativeBridge.INSTANCE.metallum_release_object(this.currentDrawable);
			this.currentDrawable = null;
		}
		this.blittedToDrawable = false;
	}

	@Override
	public Collection<GpuSurface.PresentMode> supportedPresentModes() {
		return SUPPORTED_PRESENT_MODES;
	}

	long windowHandle() {
		return this.windowHandle;
	}

	MetalCocoaBootstrap.BootstrapContext bootstrap() {
		return this.bootstrap;
	}
}
