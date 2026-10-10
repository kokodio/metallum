package com.metallum.render;

import com.metallum.MetallumConfig;
import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.device.SurfaceException;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import io.github.kokodio.metaljvm.coregraphics.CGColorSpace;
import io.github.kokodio.metaljvm.coregraphics.CGSize;
import io.github.kokodio.metaljvm.metal.MTLDevice;
import io.github.kokodio.metaljvm.metal.MTLPixelFormat;
import io.github.kokodio.metaljvm.quartzcore.CAMetalLayer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.NonNull;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLMetal;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;

@Environment(EnvType.CLIENT)
final class MetalSurface implements GpuSurfaceBackend {
    private static final Set<GpuSurface.PresentMode> SUPPORTED_PRESENT_MODES = EnumSet.of(GpuSurface.PresentMode.FIFO, GpuSurface.PresentMode.MAILBOX);
    private final long metalView;
    private final CAMetalLayer metalLayer;
    private MetalCommandEncoder pendingPresentEncoder;

    MetalSurface(final long windowHandle, final MTLDevice metalDevice) {
        this.metalView = SDLMetal.SDL_Metal_CreateView(windowHandle);
        if (this.metalView == 0L) {
            throw new IllegalStateException("Failed to create Metal view: " + SDLError.SDL_GetError());
        }
        long layerHandle = SDLMetal.SDL_Metal_GetLayer(this.metalView);
        if (layerHandle == 0L) {
            SDLMetal.SDL_Metal_DestroyView(this.metalView);
            throw new IllegalStateException("Failed to get Metal layer: " + SDLError.SDL_GetError());
        }

        this.metalLayer = new CAMetalLayer(layerHandle);
        this.metalLayer.setDevice(metalDevice);
        this.metalLayer.setFramebufferOnly(false);
        this.metalLayer.setOpaque(true);
        long colorspace = CGColorSpace.CGColorSpaceCreateWithName(MetallumConfig.INSTANCE.displayP3 ? CGColorSpace.kCGColorSpaceDisplayP3 : CGColorSpace.kCGColorSpaceSRGB);
        this.metalLayer.setColorspace(colorspace);
        CGColorSpace.CGColorSpaceRelease(colorspace);
    }

    @Override
    public void configure(final GpuSurface.Configuration config) throws SurfaceException {
        if (config.width() <= 0 || config.height() <= 0) {
            throw new SurfaceException("Metal surface configuration must be positive, got " + config.width() + "x" + config.height());
        }

        this.metalLayer.setPixelFormat(MTLPixelFormat.BGRA8Unorm);
        this.metalLayer.setDrawableSize(new CGSize(config.width(), config.height()));
        this.metalLayer.setAllowsNextDrawableTimeout(false);
        this.metalLayer.setPresentsWithTransaction(false);
        this.metalLayer.setDisplaySyncEnabled(config.presentMode() != GpuSurface.PresentMode.MAILBOX);
    }

    @Override
    public boolean isSuboptimal() {
        return false;
    }

    @Override
    public void acquireNextTexture() {
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
        SDLMetal.SDL_Metal_DestroyView(this.metalView);
    }

    @Override
    public @NonNull Collection<GpuSurface.PresentMode> supportedPresentModes() {
        return SUPPORTED_PRESENT_MODES;
    }
}
