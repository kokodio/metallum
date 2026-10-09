package com.metallum.render;

import com.metallum.Metallum;
import com.metallum.MetallumConfig;
import com.mojang.blaze3d.GLFWErrorCapture;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.systems.BackendCreationException;
import com.mojang.blaze3d.systems.GpuBackend;
import com.mojang.blaze3d.systems.GpuDevice;
import io.github.kokodio.metaljvm.appkit.NSView;
import io.github.kokodio.metaljvm.appkit.NSWindow;
import io.github.kokodio.metaljvm.coregraphics.CGColorSpace;
import io.github.kokodio.metaljvm.metal.MTLDevice;
import io.github.kokodio.metaljvm.metal.Metal;
import io.github.kokodio.metaljvm.objc.ObjC;
import io.github.kokodio.metaljvm.quartzcore.CAMetalLayer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.NonNull;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeCocoa;

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
        MTLDevice metalDevice = Metal.MTLCreateSystemDefaultDevice();
        if (metalDevice == null) {
            throw new BackendCreationException("MTLCreateSystemDefaultDevice returned null", BackendCreationException.Reason.OTHER);
        }

        String deviceName = metalDevice.name();
        if (deviceName.isBlank()) deviceName = "<unknown Metal device>";

        NSWindow nsWindow = new NSWindow(GLFWNativeCocoa.glfwGetCocoaWindow(window));
        if (ObjC.isNil(nsWindow.handle())) {
            throw new BackendCreationException("NSWindow handle is null", BackendCreationException.Reason.GLFW_ERROR);
        }
        NSView nsView = new NSView(GLFWNativeCocoa.glfwGetCocoaView(window));
        if (ObjC.isNil(nsView.handle())) {
            throw new BackendCreationException("NSView handle is null", BackendCreationException.Reason.GLFW_ERROR);
        }

        CAMetalLayer metalLayer;
        try {
            metalLayer = CAMetalLayer.alloc().init();
            metalLayer.setDevice(metalDevice);
            metalLayer.setFramebufferOnly(false);
            metalLayer.setOpaque(true);
            double backingScaleFactor = nsWindow.backingScaleFactor();
            metalLayer.setContentsScale(backingScaleFactor > 0.0 ? backingScaleFactor : 1.0);
            long colorspace = CGColorSpace.CGColorSpaceCreateWithName(MetallumConfig.INSTANCE.displayP3 ? CGColorSpace.kCGColorSpaceDisplayP3 : CGColorSpace.kCGColorSpaceSRGB);
            metalLayer.setColorspace(colorspace);
            CGColorSpace.CGColorSpaceRelease(colorspace);
        } catch (IllegalStateException e) {
            throw new BackendCreationException(e.getMessage(), BackendCreationException.Reason.OTHER);
        }

        nsView.setLayer(metalLayer);
        nsView.setWantsLayer(true);

        Metallum.LOGGER.info("Metal device: {}", deviceName);

        try {
            return new GpuDevice(new MetalDevice(defaultShaderSource, debugOptions, metalDevice, metalLayer, deviceName), criticalShaderLoader);
        } catch (Throwable throwable) {
            throw new BackendCreationException("Metal device initialization failed: " + throwable.getMessage(), BackendCreationException.Reason.OTHER);
        }
    }
}
