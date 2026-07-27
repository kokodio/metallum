package com.metallum.client.metal.render;

import com.metallum.Metallum;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
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
        MemorySegment deviceHandle;
        MemorySegment cocoaWindow;
        MemorySegment cocoaView;
        MemorySegment metalLayer;
        String deviceName;
        deviceHandle = MetalNativeBridge.metallum_create_system_default_device();
        if (MetalNativeBridge.isNullHandle(deviceHandle)) {
            throw new BackendCreationException("MTLCreateSystemDefaultDevice returned null", BackendCreationException.Reason.OTHER);
        }

        deviceName = MetalNativeBridge.metallum_copy_device_name(deviceHandle);
        if (deviceName.isBlank()) deviceName = "<unknown Metal device>";

        cocoaWindow = MemorySegment.ofAddress(GLFWNativeCocoa.glfwGetCocoaWindow(window));
        if (MetalNativeBridge.isNullHandle(cocoaWindow)) {
            throw new BackendCreationException("glfwGetCocoaWindow returned null", BackendCreationException.Reason.GLFW_ERROR);
        }

        cocoaView = MemorySegment.ofAddress(GLFWNativeCocoa.glfwGetCocoaView(window));
        if (MetalNativeBridge.isNullHandle(cocoaView)) {
            throw new BackendCreationException("glfwGetCocoaView returned null", BackendCreationException.Reason.GLFW_ERROR);
        }

        double scale = MetalNativeBridge.metallum_NSWindow_backingScaleFactor(cocoaWindow);
        if (scale <= 0.0) scale = 1.0;


        metalLayer = MetalNativeBridge.metallum_create_metal_layer(deviceHandle, scale);
        if (MetalNativeBridge.isNullHandle(metalLayer)) {
            throw new BackendCreationException("Failed to create CAMetalLayer", BackendCreationException.Reason.OTHER);
        }

        MetalNativeBridge.metallum_NSView_setMetalLayer(cocoaView, metalLayer);

        Metallum.LOGGER.info("Metal device: {}", deviceName);

        try {
            return new GpuDevice(new MetalDevice(defaultShaderSource, debugOptions, deviceHandle, metalLayer, deviceName, cocoaView, cocoaWindow), criticalShaderLoader);
        } catch (Throwable throwable) {
            throw new BackendCreationException("Metal device initialization failed: " + throwable.getMessage(), BackendCreationException.Reason.OTHER);
        }
    }
}
