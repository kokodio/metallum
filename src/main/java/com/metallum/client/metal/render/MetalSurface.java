package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.GpuSurface;
import com.mojang.blaze3d.systems.GpuSurfaceBackend;
import com.mojang.blaze3d.systems.SurfaceException;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.NonNull;

import java.lang.foreign.MemorySegment;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;

@Environment(EnvType.CLIENT)
final class MetalSurface implements GpuSurfaceBackend {
    private static final Set<GpuSurface.PresentMode> SUPPORTED_PRESENT_MODES = EnumSet.of(GpuSurface.PresentMode.FIFO, GpuSurface.PresentMode.MAILBOX);
    private final MetalDevice device;
    private final MemorySegment metalLayer;
    private final MemorySegment cocoaWindow;
    private GpuSurface.Configuration configuration;
    private MetalCommandEncoder pendingPresentEncoder;

    MetalSurface(final MetalDevice device, final MemorySegment metalLayer, final MemorySegment cocoaWindow) {
        this.device = device;
        this.metalLayer = metalLayer;
        this.cocoaWindow = cocoaWindow;
    }

    @Override
    public void configure(final GpuSurface.Configuration config) throws SurfaceException {
        if (config.width() <= 0 || config.height() <= 0) {
            throw new SurfaceException("Metal surface configuration must be positive, got " + config.width() + "x" + config.height());
        }

        MetalNativeBridge.metallum_configure_layer(
                this.metalLayer,
                config.width(),
                config.height(),
                config.presentMode() == GpuSurface.PresentMode.MAILBOX ? 1 : 0
        );

        this.configuration = config;
    }

    @Override
    public boolean isSuboptimal() {
        return false;
    }

    /**
     * When VSync is enabled but the window is minimized/occluded,
     * displaySyncEnabled cannot function properly, causing FPS to
     * spike (e.g. from 60→120 with Stage Manager). Throttle to a
     * reasonable rate (~62 fps) while the window is not visible.
     */
    @Override
    public void acquireNextTexture() {
        if (MetalNativeBridge.isNullHandle(cocoaWindow) || configuration == null) {
            return;
        }
        boolean vsyncOn = configuration.presentMode() == GpuSurface.PresentMode.FIFO;
        if (vsyncOn && MetalNativeBridge.metallum_NSWindow_isVisible(cocoaWindow) == 0) {
            try {
                Thread.sleep(0L, 16_000_000); // ~16ms ≈ 62 fps
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void blitFromTexture(final @NonNull CommandEncoderBackend commandEncoder, final @NonNull GpuTextureView textureView) {
        if (!(commandEncoder instanceof MetalCommandEncoder metalEncoder)) {
            throw new IllegalArgumentException("Metal surface requires MetalCommandEncoder");
        }

        metalEncoder.presentTextureToDrawable(metalLayer, textureView);
        this.pendingPresentEncoder = metalEncoder;
    }

    @Override
    public void present() {
        pendingPresentEncoder.submit();
    }

    @Override
    public void close() {
    }

    @Override
    public @NonNull Collection<GpuSurface.PresentMode> supportedPresentModes() {
        return SUPPORTED_PRESENT_MODES;
    }
}
