package com.metallum.mtl;

import com.metallum.Metallum;
import com.metallum.objc.AutoreleasePool;
import com.metallum.objc.Msg;
import com.metallum.objc.ObjC;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

@Environment(EnvType.CLIENT)
public record MTLDevice(MemorySegment handle) {
    private static final MethodHandle CREATE_SYSTEM_DEFAULT_DEVICE = ObjC.LINKER.downcallHandle(
            ObjC.METAL.findOrThrow("MTLCreateSystemDefaultDevice"), FunctionDescriptor.of(ADDRESS));

    private static final Msg NEW_BUFFER = Msg.of("newBufferWithLength:options:", ADDRESS, JAVA_LONG, JAVA_LONG);
    private static final Msg NEW_COMMAND_QUEUE = Msg.of("newCommandQueue", ADDRESS);
    private static final Msg NEW_TEXTURE = Msg.of("newTextureWithDescriptor:", ADDRESS, ADDRESS);
    private static final Msg NEW_SAMPLER_STATE = Msg.of("newSamplerStateWithDescriptor:", ADDRESS, ADDRESS);
    private static final Msg NEW_DEPTH_STENCIL_STATE = Msg.of("newDepthStencilStateWithDescriptor:", ADDRESS, ADDRESS);
    private static final Msg NEW_FENCE = Msg.of("newFence", ADDRESS);
    private static final Msg NEW_LIBRARY_WITH_SOURCE = Msg.of("newLibraryWithSource:options:error:", true, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
    private static final Msg NEW_FUNCTION_WITH_NAME = Msg.of("newFunctionWithName:", true, ADDRESS, ADDRESS);
    private static final Msg NEW_RENDER_PIPELINE_STATE = Msg.of("newRenderPipelineStateWithDescriptor:error:", true, ADDRESS, ADDRESS, ADDRESS);
    private static final Msg LOCALIZED_DESCRIPTION = Msg.of("localizedDescription", ADDRESS);
    private static final Msg MINIMUM_TEXTURE_BUFFER_ALIGNMENT = Msg.of("minimumTextureBufferAlignmentForPixelFormat:", JAVA_LONG, JAVA_LONG);
    private static final Msg NAME = Msg.of("name", ADDRESS);
    private static final Msg MAX_BUFFER_LENGTH = Msg.of("maxBufferLength", JAVA_LONG);
    private static final Msg RECOMMENDED_MAX_WORKING_SET_SIZE = Msg.of("recommendedMaxWorkingSetSize", JAVA_LONG);

    public MTLDevice {
        if (handle == null || handle.address() == 0L) {
            throw new IllegalArgumentException("MTLDevice handle is null");
        }
    }

    @Nullable
    public static MTLDevice createSystemDefault() {
        try {
            MemorySegment device = (MemorySegment) CREATE_SYSTEM_DEFAULT_DEVICE.invokeExact();
            return ObjC.isNil(device) ? null : new MTLDevice(device);
        } catch (Throwable throwable) {
            throw new IllegalStateException("MTLCreateSystemDefaultDevice failed", throwable);
        }
    }

    public String name() {
        try (AutoreleasePool _ = AutoreleasePool.push()) {
            return ObjC.javaString(NAME.sendPtr(handle));
        }
    }

    public long maxBufferLength() {
        return MAX_BUFFER_LENGTH.sendLong(handle);
    }

    public long recommendedMaxWorkingSetSize() {
        return RECOMMENDED_MAX_WORKING_SET_SIZE.sendLong(handle);
    }

    public MTLBuffer newBuffer(final long length, final long options) {
        MemorySegment buffer = NEW_BUFFER.sendPtr(handle, length, options);
        if (ObjC.isNil(buffer)) {
            throw new IllegalStateException("newBufferWithLength:options: returned nil (length=" + length + ")");
        }
        return new MTLBuffer(buffer);
    }

    public MTLCommandQueue newCommandQueue() {
        MemorySegment queue = NEW_COMMAND_QUEUE.sendPtr(handle);
        if (ObjC.isNil(queue)) {
            throw new IllegalStateException("newCommandQueue returned nil");
        }
        return new MTLCommandQueue(queue);
    }

    public MemorySegment newTexture(final MTLTextureDescriptor descriptor) {
        MemorySegment texture = NEW_TEXTURE.sendPtr(handle, descriptor.handle());
        if (ObjC.isNil(texture)) {
            throw new IllegalStateException("newTextureWithDescriptor: returned nil");
        }
        return texture;
    }

    public MemorySegment newSamplerState(final MTLSamplerDescriptor descriptor) {
        MemorySegment sampler = NEW_SAMPLER_STATE.sendPtr(handle, descriptor.handle());
        if (ObjC.isNil(sampler)) {
            throw new IllegalStateException("newSamplerStateWithDescriptor: returned nil");
        }
        return sampler;
    }

    public MemorySegment newDepthStencilState(final MTLDepthStencilDescriptor descriptor) {
        MemorySegment state = NEW_DEPTH_STENCIL_STATE.sendPtr(handle, descriptor.handle());
        if (ObjC.isNil(state)) {
            throw new IllegalStateException("newDepthStencilStateWithDescriptor: returned nil");
        }
        return state;
    }

    public MTLFence newFence() {
        MemorySegment fence = NEW_FENCE.sendPtr(handle);
        if (ObjC.isNil(fence)) {
            throw new IllegalStateException("newFence returned nil");
        }
        return new MTLFence(fence);
    }

    public MemorySegment newFunction(final String mslSource, final String entryPoint) {
        try (AutoreleasePool _ = AutoreleasePool.push(); Arena arena = Arena.ofConfined()) {
            MemorySegment errorOut = arena.allocate(ADDRESS);
            MemorySegment nsSource = ObjC.nsString(mslSource);
            MemorySegment library = NEW_LIBRARY_WITH_SOURCE.sendPtr(handle, nsSource, MemorySegment.NULL, errorOut);
            ObjC.release(nsSource);
            if (ObjC.isNil(library)) {
                Metallum.LOGGER.error("[metallum] Failed to compile MSL: {}", errorDescription(errorOut));
                return MemorySegment.NULL;
            }
            MemorySegment nsEntry = ObjC.nsString(entryPoint);
            MemorySegment function = NEW_FUNCTION_WITH_NAME.sendPtr(library, nsEntry);
            ObjC.release(nsEntry);
            ObjC.release(library);
            if (ObjC.isNil(function)) {
                Metallum.LOGGER.error("[metallum] Failed to resolve MSL entry point '{}'", entryPoint);
                return MemorySegment.NULL;
            }
            return function;
        }
    }

    public MemorySegment newRenderPipelineState(final MTLRenderPipelineDescriptor descriptor) {
        try (AutoreleasePool _ = AutoreleasePool.push(); Arena arena = Arena.ofConfined()) {
            MemorySegment errorOut = arena.allocate(ADDRESS);
            MemorySegment pipeline = NEW_RENDER_PIPELINE_STATE.sendPtr(handle, descriptor.handle(), errorOut);
            if (ObjC.isNil(pipeline)) {
                Metallum.LOGGER.error("[metallum] Failed to create render pipeline state: {}", errorDescription(errorOut));
                return MemorySegment.NULL;
            }
            return pipeline;
        }
    }

    static long minimumTextureBufferAlignment(final MemorySegment device, final long pixelFormat) {
        return MINIMUM_TEXTURE_BUFFER_ALIGNMENT.sendLong(device, pixelFormat);
    }

    private static String errorDescription(final MemorySegment errorOut) {
        MemorySegment error = errorOut.get(ADDRESS, 0L);
        return ObjC.isNil(error) ? "unknown error" : ObjC.javaString(LOCALIZED_DESCRIPTION.sendPtr(error));
    }
}
