package com.metallum.render;

import com.metallum.Metallum;
import com.metallum.MetallumConfig;
import com.metallum.mtl.CAMetalLayer;
import com.metallum.mtl.CGColorSpace;
import com.metallum.mtl.MTLDevice;
import com.metallum.objc.Cocoa;
import com.mojang.blaze3d.GLFWErrorCapture;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.systems.BackendCreationException;
import com.mojang.blaze3d.systems.GpuBackend;
import com.mojang.blaze3d.systems.GpuDevice;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.NonNull;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeCocoa;

import java.lang.foreign.MemorySegment;

@Environment(EnvType.CLIENT)
public class MetalBackend implements GpuBackend {
    @Override
    public @NonNull String getName() {
        return "Metal";
    }

    @Override
    public void setWindowHints() {
        GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_NO_API);
    }

    @Override
    public void handleWindowCreationErrors(final GLFWErrorCapture.Error error) throws BackendCreationException {
        throw new BackendCreationException(error.toString(), BackendCreationException.Reason.GLFW_ERROR);
    }

    @Override
    public @NonNull GpuDevice createDevice(
            final long window, final @NonNull ShaderSource defaultShaderSource, final @NonNull GpuDebugOptions debugOptions, final @NonNull Runnable criticalShaderLoader
    ) throws BackendCreationException {
        MTLDevice metalDevice = MTLDevice.createSystemDefault();
        if (metalDevice == null) {
            throw new BackendCreationException("MTLCreateSystemDefaultDevice returned null", BackendCreationException.Reason.OTHER);
        }

        String deviceName = metalDevice.name();
        if (deviceName.isBlank()) deviceName = "<unknown Metal device>";

        Cocoa cocoa;
        try {
            cocoa = new Cocoa(
                    MemorySegment.ofAddress(GLFWNativeCocoa.glfwGetCocoaWindow(window)),
                    MemorySegment.ofAddress(GLFWNativeCocoa.glfwGetCocoaView(window))
            );
        } catch (IllegalStateException e) {
            throw new BackendCreationException(e.getMessage(), BackendCreationException.Reason.GLFW_ERROR);
        }

        CAMetalLayer metalLayer;
        try {
            metalLayer = new CAMetalLayer();
            metalLayer.setDevice(metalDevice);
            metalLayer.setFramebufferOnly(false);
            metalLayer.setOpaque(true);
            metalLayer.setContentsScale(cocoa.backingScaleFactor());
            try (CGColorSpace colorspace = CGColorSpace.createWithName(MetallumConfig.INSTANCE.displayP3 ? CGColorSpace.kCGColorSpaceDisplayP3 : CGColorSpace.kCGColorSpaceSRGB)) {
                metalLayer.setColorspace(colorspace);
            }
        } catch (IllegalStateException e) {
            throw new BackendCreationException(e.getMessage(), BackendCreationException.Reason.OTHER);
        }

        cocoa.setViewLayer(metalLayer.handle());

        Metallum.LOGGER.info("Metal device: {}", deviceName);

        try {
            return new GpuDevice(new MetalDevice(defaultShaderSource, debugOptions, metalDevice.handle(), metalLayer, deviceName, cocoa), criticalShaderLoader);
        } catch (Throwable throwable) {
            throw new BackendCreationException("Metal device initialization failed: " + throwable.getMessage(), BackendCreationException.Reason.OTHER);
        }
    }
}
