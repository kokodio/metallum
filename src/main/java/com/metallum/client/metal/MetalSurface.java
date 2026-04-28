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

@Environment(EnvType.CLIENT)
final class MetalSurface implements GpuSurfaceBackend {
	private static final Set<GpuSurface.PresentMode> SUPPORTED_PRESENT_MODES = EnumSet.of(GpuSurface.PresentMode.FIFO, GpuSurface.PresentMode.IMMEDIATE);
	private final long windowHandle;
	private final MetalDevice device;
	private final MetalCocoaBootstrap.BootstrapContext bootstrap;
	private GpuSurface.Configuration configuration;

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
		int result = MetalNativeBridge.INSTANCE.metallum_acquire_next_drawable(this.device.commandQueue(), this.bootstrap.metalLayer());
		if (result != 0) {
			throw new SurfaceException("Failed to acquire Metal drawable (code " + result + ")");
		}
	}

	@Override
	public void blitFromTexture(final CommandEncoderBackend commandEncoder, final GpuTextureView textureView) {
		if (commandEncoder instanceof MetalCommandEncoder metalEncoder) {
			metalEncoder.flushPendingTextureViewClear(textureView);
		}

		MetalGpuTexture source = (MetalGpuTexture)textureView.texture();
		int result = MetalNativeBridge.INSTANCE.metallum_enqueue_present_texture_to_layer(
			this.device.commandQueue(),
			this.bootstrap.metalLayer(),
			source.nativeHandle()
		);
		if (result != 0) {
			throw new IllegalStateException("Failed to enqueue Metal present for texture '" + source.getLabel() + "' (code " + result + ")");
		}
	}

	@Override
	public void present() {
		int result = MetalNativeBridge.INSTANCE.metallum_present_pending_drawable(this.device.commandQueue());
		if (result != 0) {
			throw new IllegalStateException("Failed to present pending Metal drawable (code " + result + ")");
		}
	}

	@Override
	public void close() {
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
