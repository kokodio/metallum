package com.metallum.client.metal.render.bridge;

import com.metallum.client.metal.render.mtl.*;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Environment(EnvType.CLIENT)
public final class MetalNativeBridge {
    private static final String RESOURCE_PATH = "/natives/macos/libmetallum.dylib";
    private static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT;
    private static final ValueLayout.OfLong LONG = ValueLayout.JAVA_LONG;
    private static final ValueLayout.OfFloat FLOAT = ValueLayout.JAVA_FLOAT;
    private static final ValueLayout.OfDouble DOUBLE = ValueLayout.JAVA_DOUBLE;
    private static final Linker LINKER = Linker.nativeLinker();

    static {
        try {
            Path tempLib = Files.createTempFile("metallum-native-", ".dylib");
            tempLib.toFile().deleteOnExit();
            try (InputStream stream = MetalNativeBridge.class.getResourceAsStream(RESOURCE_PATH)) {
                if (stream == null) {
                    throw new IllegalStateException("Missing native library resource: " + RESOURCE_PATH);
                }
                Files.copy(stream, tempLib, StandardCopyOption.REPLACE_EXISTING);
            }

            SymbolLookup lookup = SymbolLookup.libraryLookup(tempLib, Arena.global());


            createSystemDefaultDevice = downcall(lookup, "metallum_create_system_default_device", FunctionDescriptor.of(ValueLayout.ADDRESS));
            copyDeviceName = downcall(lookup, "metallum_copy_device_name", FunctionDescriptor.of(INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG));
            NSWindowBackingScaleFactor = downcall(lookup, "metallum_NSWindow_backingScaleFactor", FunctionDescriptor.of(DOUBLE, ValueLayout.ADDRESS));
            NSWindowIsVisible = downcall(lookup, "metallum_NSWindow_isVisible", FunctionDescriptor.of(INT, ValueLayout.ADDRESS));
            createMetalLayer = downcall(lookup, "metallum_create_metal_layer", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, DOUBLE));
            NSViewSetMetalLayer = downcall(lookup, "metallum_NSView_setMetalLayer", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            NSViewClearLayer = downcall(lookup, "metallum_NSView_clearLayer", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            setDebugLabelsEnabled = downcall(lookup, "metallum_set_debug_labels_enabled", FunctionDescriptor.ofVoid(INT));
            initPipelines = downcall(lookup, "metallum_init_pipelines", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

            MTLDeviceMaxMemoryAllocationSize = downcall(lookup, "metallum_MTLDevice_maxMemoryAllocationSize", FunctionDescriptor.of(LONG, ValueLayout.ADDRESS));
            MTLDeviceMakeCommandQueue = downcall(lookup, "metallum_MTLDevice_makeCommandQueue", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            MTLCommandQueueMakeCommandBuffer = downcall(lookup, "metallum_MTLCommandQueue_makeCommandBuffer", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            MTLCommandBufferCommit = downcall(lookup, "metallum_MTLCommandBuffer_commit", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            createSemaphore = downcall(lookup, "metallum_create_semaphore", FunctionDescriptor.of(ValueLayout.ADDRESS));
            MTLCommandBufferCommitWithSignal = downcall(lookup, "metallum_MTLCommandBuffer_commitWithSignal", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            semaphoreWait = downcallWithoutCritical(lookup, "metallum_semaphore_wait", FunctionDescriptor.of(INT, ValueLayout.ADDRESS, LONG));
            MTLCommandBufferIsCompleted = downcall(lookup, "metallum_MTLCommandBuffer_isCompleted", FunctionDescriptor.of(INT, ValueLayout.ADDRESS));
            MTLCommandBufferWaitUntilCompleted = downcallWithoutCritical(lookup, "metallum_MTLCommandBuffer_waitUntilCompleted", FunctionDescriptor.of(INT, ValueLayout.ADDRESS, LONG));
            MTLCommandBufferPushDebugGroup = downcall(lookup, "metallum_MTLCommandBuffer_pushDebugGroup", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            MTLCommandBufferPopDebugGroup = downcall(lookup, "metallum_MTLCommandBuffer_popDebugGroup", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            MTLCommandBufferMakeBlitCommandEncoder = downcall(lookup, "metallum_MTLCommandBuffer_makeBlitCommandEncoder", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            MTLCommandEncoderEndEncoding = downcall(lookup, "metallum_MTLCommandEncoder_endEncoding", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            MTLBlitCommandEncoderCopyFromBufferToBuffer = downcall(
                    lookup,
                    "metallum_MTLBlitCommandEncoder_copyFromBufferToBuffer",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, ValueLayout.ADDRESS, LONG, LONG)
            );
            MTLBlitCommandEncoderCopyFromBufferToTexture = downcall(
                    lookup,
                    "metallum_MTLBlitCommandEncoder_copyFromBufferToTexture",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, ValueLayout.ADDRESS, LONG, LONG, LONG, LONG, LONG, LONG, LONG, LONG)
            );
            MTLBlitCommandEncoderCopyFromTextureToTexture = downcall(
                    lookup,
                    "metallum_MTLBlitCommandEncoder_copyFromTextureToTexture",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG, LONG, LONG, LONG, LONG, LONG)
            );
            MTLBlitCommandEncoderCopyFromTextureToBuffer = downcall(
                    lookup,
                    "metallum_MTLBlitCommandEncoder_copyFromTextureToBuffer",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG, LONG, LONG, LONG, LONG, LONG, LONG, LONG)
            );
            MTLDeviceMakeDepthStencilState = downcall(lookup, "metallum_MTLDevice_makeDepthStencilState", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, INT));
            MTLCommandBufferMakeRenderCommandEncoder = downcall(
                    lookup,
                    "metallum_MTLCommandBuffer_makeRenderCommandEncoder",
                    FunctionDescriptor.of(
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            DOUBLE,
                            DOUBLE,
                            INT,
                            FLOAT,
                            FLOAT,
                            FLOAT,
                            FLOAT,
                            INT,
                            DOUBLE
                    )
            );
            MTLRenderCommandEncoderSetRenderPipelineState = downcall(lookup, "metallum_MTLRenderCommandEncoder_setRenderPipelineState", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            MTLRenderCommandEncoderSetDepthStencilState = downcall(lookup, "metallum_MTLRenderCommandEncoder_setDepthStencilState", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            MTLRenderCommandEncoderSetDepthBias = downcall(lookup, "metallum_MTLRenderCommandEncoder_setDepthBias", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, FLOAT, FLOAT, FLOAT));
            MTLRenderCommandEncoderSetFrontFacingWinding = downcall(lookup, "metallum_MTLRenderCommandEncoder_setFrontFacingWinding", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, INT));
            MTLRenderCommandEncoderSetCullMode = downcall(lookup, "metallum_MTLRenderCommandEncoder_setCullMode", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, LONG));
            MTLRenderCommandEncoderSetTriangleFillMode = downcall(lookup, "metallum_MTLRenderCommandEncoder_setTriangleFillMode", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, INT));
            MTLRenderCommandEncoderSetBuffer = downcall(lookup, "metallum_MTLRenderCommandEncoder_setBuffer", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG, INT));
            MTLRenderCommandEncoderSetBufferOffset = downcall(lookup, "metallum_MTLRenderCommandEncoder_setBufferOffset", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, LONG, LONG, INT));
            MTLRenderCommandEncoderSetTexture = downcall(lookup, "metallum_MTLRenderCommandEncoder_setTexture", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, INT));
            MTLRenderCommandEncoderSetTextureAndSampler = downcall(lookup, "metallum_MTLRenderCommandEncoder_setTextureAndSampler", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, INT));
            MTLRenderCommandEncoderSetScissorRect = downcall(lookup, "metallum_MTLRenderCommandEncoder_setScissorRect", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, LONG, LONG, LONG, LONG));
            MTLRenderCommandEncoderClearDraw = downcall(
                    lookup,
                    "metallum_MTLRenderCommandEncoder_clearDraw",
                    FunctionDescriptor.ofVoid(
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            DOUBLE,
                            DOUBLE,
                            INT,
                            FLOAT,
                            FLOAT,
                            FLOAT,
                            FLOAT,
                            INT,
                            DOUBLE
                    )
            );
            MTLRenderCommandEncoderDrawPrimitives = downcall(lookup, "metallum_MTLRenderCommandEncoder_drawPrimitives", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, LONG, LONG, LONG, LONG, LONG));
            MTLRenderCommandEncoderDrawIndexedPrimitives = downcall(
                    lookup,
                    "metallum_MTLRenderCommandEncoder_drawIndexedPrimitives",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, LONG, LONG, LONG, ValueLayout.ADDRESS, LONG, LONG, LONG, LONG)
            );
            MTLRenderCommandEncoderMultiDrawIndexed = downcall(
                    lookup,
                    "metallum_MTLRenderCommandEncoder_multiDrawIndexed",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, LONG, LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG, LONG)
            );
            MTLRenderCommandEncoderDrawIndexedPrimitivesIndirect = downcall(
                    lookup,
                    "metallum_MTLRenderCommandEncoder_drawIndexedPrimitivesIndirect",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, LONG, LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG, LONG)
            );
            MTLRenderCommandEncoderDrawPrimitivesIndirect = downcall(
                    lookup,
                    "metallum_MTLRenderCommandEncoder_drawPrimitivesIndirect",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, LONG, ValueLayout.ADDRESS, LONG, LONG, LONG)
            );
            MTLRenderCommandEncoderDrawIndexedPrimitivesTriangleFan = downcallWithoutCritical(
                    lookup,
                    "metallum_MTLRenderCommandEncoder_drawIndexedPrimitivesTriangleFan",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG, LONG, LONG, LONG, LONG, LONG)
            );
            MTLCommandBufferClearColorDepthTexturesRegion = downcall(
                    lookup,
                    "metallum_MTLCommandBuffer_clearColorDepthTexturesRegion",
                    FunctionDescriptor.ofVoid(
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            FLOAT,
                            FLOAT,
                            FLOAT,
                            FLOAT,
                            ValueLayout.ADDRESS,
                            DOUBLE,
                            INT,
                            INT,
                            INT,
                            INT,
                            ValueLayout.ADDRESS
                    )
            );
            MTLCommandBufferEncodePresentTextureToDrawable = downcallWithoutCritical(
                    lookup,
                    "metallum_MTLCommandBuffer_encodePresentTextureToDrawable",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
            );
            createBuffer = downcall(lookup, "metallum_create_buffer", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG));
            createTexture2d = downcall(
                    lookup,
                    "metallum_create_texture_2d",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG, LONG, LONG, LONG, LONG, LONG, LONG, ValueLayout.ADDRESS)
            );
            createTextureView = downcall(lookup, "metallum_create_texture_view", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG));
            createBufferTextureView = downcall(
                    lookup,
                    "metallum_create_buffer_texture_view",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG, LONG, LONG, LONG)
            );
            createSampler = downcall(
                    lookup,
                    "metallum_create_sampler",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG, LONG, LONG, LONG, INT, DOUBLE)
            );
            MTLVertexDescriptorCreate = downcall(
                    lookup,
                    "metallum_MTLVertexDescriptor_create",
                    FunctionDescriptor.of(ValueLayout.ADDRESS)
            );
            MTLVertexDescriptorSetAttribute = downcall(
                    lookup,
                    "metallum_MTLVertexDescriptor_setAttribute",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, LONG, LONG, LONG, LONG)
            );
            MTLVertexDescriptorSetLayout = downcall(
                    lookup,
                    "metallum_MTLVertexDescriptor_setLayout",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, LONG, LONG, LONG, LONG)
            );
            MTLRenderPipelineDescriptorCreate = downcall(
                    lookup,
                    "metallum_MTLRenderPipelineDescriptor_create",
                    FunctionDescriptor.of(ValueLayout.ADDRESS)
            );
            createShaderFunction = downcallWithoutCritical(
                    lookup,
                    "metallum_create_shader_function",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
            );
            MTLRenderPipelineDescriptorSetCompiledFunctions = downcall(
                    lookup,
                    "metallum_MTLRenderPipelineDescriptor_setCompiledFunctions",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
            );
            MTLRenderPipelineDescriptorSetVertexDescriptor = downcall(
                    lookup,
                    "metallum_MTLRenderPipelineDescriptor_setVertexDescriptor",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS)
            );
            MTLRenderPipelineDescriptorSetAttachmentFormats = downcall(
                    lookup,
                    "metallum_MTLRenderPipelineDescriptor_setAttachmentFormats",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, LONG, LONG, LONG)
            );
            MTLRenderPipelineDescriptorSetBlendState = downcall(
                    lookup,
                    "metallum_MTLRenderPipelineDescriptor_setBlendState",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, INT, LONG, LONG, LONG, LONG, LONG, LONG, LONG)
            );
            MTLDeviceMakeRenderPipelineState = downcall(
                    lookup,
                    "metallum_MTLDevice_makeRenderPipelineState",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
            );
            configureLayer = downcall(lookup, "metallum_configure_layer", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, DOUBLE, DOUBLE, INT));
            releaseObject = downcall(lookup, "metallum_release_object", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            getBufferContents = downcall(lookup, "metallum_get_buffer_contents", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            createFence = downcall(lookup, "metallum_create_fence", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            MTLRenderCommandEncoderUpdateFence = downcall(lookup, "MTLRenderCommandEncoder_updateFence", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG));
            MTLRenderCommandEncoderWaitForFence = downcallWithoutCritical(lookup, "MTLRenderCommandEncoder_waitForFence", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG));
            MTLBlitCommandEncoderUpdateFence = downcall(lookup, "MTLBlitCommandEncoder_updateFence", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            MTLBlitCommandEncoderWaitForFence = downcallWithoutCritical(lookup, "MTLBlitCommandEncoder_waitForFence", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load Metal native bridge", e);
        }
    }


    private static final MethodHandle createSystemDefaultDevice;
    private static final MethodHandle copyDeviceName;
    private static final MethodHandle NSWindowBackingScaleFactor;
    private static final MethodHandle NSWindowIsVisible;
    private static final MethodHandle createMetalLayer;
    private static final MethodHandle NSViewSetMetalLayer;
    private static final MethodHandle NSViewClearLayer;
    private static final MethodHandle setDebugLabelsEnabled;
    private static final MethodHandle MTLDeviceMaxMemoryAllocationSize;
    private static final MethodHandle MTLDeviceMakeCommandQueue;
    private static final MethodHandle MTLCommandQueueMakeCommandBuffer;
    private static final MethodHandle MTLCommandBufferCommit;
    private static final MethodHandle createSemaphore;
    private static final MethodHandle MTLCommandBufferCommitWithSignal;
    private static final MethodHandle semaphoreWait;
    private static final MethodHandle MTLCommandBufferIsCompleted;
    private static final MethodHandle MTLCommandBufferWaitUntilCompleted;
    private static final MethodHandle MTLCommandBufferPushDebugGroup;
    private static final MethodHandle MTLCommandBufferPopDebugGroup;
    private static final MethodHandle MTLCommandBufferMakeBlitCommandEncoder;
    private static final MethodHandle MTLCommandEncoderEndEncoding;
    private static final MethodHandle MTLBlitCommandEncoderCopyFromBufferToBuffer;
    private static final MethodHandle MTLBlitCommandEncoderCopyFromBufferToTexture;
    private static final MethodHandle MTLBlitCommandEncoderCopyFromTextureToTexture;
    private static final MethodHandle MTLBlitCommandEncoderCopyFromTextureToBuffer;
    private static final MethodHandle MTLDeviceMakeDepthStencilState;
    private static final MethodHandle MTLCommandBufferMakeRenderCommandEncoder;
    private static final MethodHandle MTLRenderCommandEncoderSetRenderPipelineState;
    private static final MethodHandle MTLRenderCommandEncoderSetDepthStencilState;
    private static final MethodHandle MTLRenderCommandEncoderSetDepthBias;
    private static final MethodHandle MTLRenderCommandEncoderSetFrontFacingWinding;
    private static final MethodHandle MTLRenderCommandEncoderSetCullMode;
    private static final MethodHandle MTLRenderCommandEncoderSetTriangleFillMode;
    private static final MethodHandle MTLRenderCommandEncoderSetBuffer;
    private static final MethodHandle MTLRenderCommandEncoderSetBufferOffset;
    private static final MethodHandle MTLRenderCommandEncoderSetTexture;
    private static final MethodHandle MTLRenderCommandEncoderSetTextureAndSampler;
    private static final MethodHandle MTLRenderCommandEncoderSetScissorRect;
    private static final MethodHandle MTLRenderCommandEncoderClearDraw;
    private static final MethodHandle MTLRenderCommandEncoderDrawPrimitives;
    private static final MethodHandle MTLRenderCommandEncoderDrawIndexedPrimitives;
    private static final MethodHandle MTLRenderCommandEncoderMultiDrawIndexed;
    private static final MethodHandle MTLRenderCommandEncoderDrawIndexedPrimitivesTriangleFan;
    private static final MethodHandle MTLRenderCommandEncoderDrawIndexedPrimitivesIndirect;
    private static final MethodHandle MTLRenderCommandEncoderDrawPrimitivesIndirect;
    private static final MethodHandle MTLCommandBufferClearColorDepthTexturesRegion;
    private static final MethodHandle MTLCommandBufferEncodePresentTextureToDrawable;
    private static final MethodHandle createBuffer;
    private static final MethodHandle createTexture2d;
    private static final MethodHandle createTextureView;
    private static final MethodHandle createBufferTextureView;
    private static final MethodHandle createSampler;
    private static final MethodHandle MTLVertexDescriptorCreate;
    private static final MethodHandle MTLVertexDescriptorSetAttribute;
    private static final MethodHandle MTLVertexDescriptorSetLayout;
    private static final MethodHandle MTLRenderPipelineDescriptorCreate;
    private static final MethodHandle createShaderFunction;
    private static final MethodHandle MTLRenderPipelineDescriptorSetCompiledFunctions;
    private static final MethodHandle MTLRenderPipelineDescriptorSetVertexDescriptor;
    private static final MethodHandle MTLRenderPipelineDescriptorSetAttachmentFormats;
    private static final MethodHandle MTLRenderPipelineDescriptorSetBlendState;
    private static final MethodHandle MTLDeviceMakeRenderPipelineState;
    private static final MethodHandle configureLayer;
    private static final MethodHandle releaseObject;
    private static final MethodHandle getBufferContents;
    private static final MethodHandle createFence;
    private static final MethodHandle MTLRenderCommandEncoderUpdateFence;
    private static final MethodHandle MTLRenderCommandEncoderWaitForFence;
    private static final MethodHandle MTLBlitCommandEncoderUpdateFence;
    private static final MethodHandle MTLBlitCommandEncoderWaitForFence;
    private static final MethodHandle initPipelines;


    private static MethodHandle downcall(final SymbolLookup lookup, final String symbol, final FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(lookup.findOrThrow(symbol), descriptor, Linker.Option.critical(false));
    }

    private static MethodHandle downcallWithoutCritical(final SymbolLookup lookup, final String symbol, final FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(lookup.findOrThrow(symbol), descriptor);
    }

    public static MemorySegment metallum_create_system_default_device() {
        try {
            return (MemorySegment) createSystemDefaultDevice.invokeExact();
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_create_system_default_device", throwable);
        }
    }

    public static String metallum_copy_device_name(final MemorySegment device) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(256L);
            int result = (int) copyDeviceName.invokeExact(segment(device), buffer, 256L);
            return result == 0 ? buffer.getString(0L) : "";
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_copy_device_name", throwable);
        }
    }

    public static double metallum_NSWindow_backingScaleFactor(final MemorySegment window) {
        try {
            return (double) NSWindowBackingScaleFactor.invokeExact(segment(window));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_NSWindow_backingScaleFactor", throwable);
        }
    }

    public static int metallum_NSWindow_isVisible(final MemorySegment window) {
        try {
            return (int) NSWindowIsVisible.invokeExact(segment(window));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_NSWindow_isVisible", throwable);
        }
    }

    public static MemorySegment metallum_create_metal_layer(final MemorySegment device, final double contentsScale) {
        try {
            return (MemorySegment) createMetalLayer.invokeExact(segment(device), contentsScale);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_create_metal_layer", throwable);
        }
    }

    public static void metallum_NSView_setMetalLayer(final MemorySegment view, final MemorySegment layer) {
        try {
            NSViewSetMetalLayer.invokeExact(segment(view), segment(layer));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_NSView_setMetalLayer", throwable);
        }
    }

    public static void metallum_NSView_clearLayer(final MemorySegment view) {
        try {
            NSViewClearLayer.invokeExact(segment(view));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_NSView_clearLayer", throwable);
        }
    }

    public static void metallum_set_debug_labels_enabled(final boolean enabled) {
        try {
            setDebugLabelsEnabled.invokeExact(enabled ? 1 : 0);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_set_debug_labels_enabled", throwable);
        }
    }

    public static void metallum_init_pipelines(final MemorySegment device) {
        try {
            initPipelines.invokeExact(segment(device));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_init_pipelines", throwable);
        }
    }


    public static long MTLDevice_maxMemoryAllocationSize(final MemorySegment device) {
        try {
            return (long) MTLDeviceMaxMemoryAllocationSize.invokeExact(segment(device));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLDevice_maxMemoryAllocationSize", throwable);
        }
    }

    public static MemorySegment MTLDevice_makeCommandQueue(final MemorySegment device) {
        try {
            return (MemorySegment) MTLDeviceMakeCommandQueue.invokeExact(segment(device));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLDevice_makeCommandQueue", throwable);
        }
    }

    public static MemorySegment MTLCommandQueue_makeCommandBuffer(final MemorySegment commandQueue, final String label) {
        try (Arena arena = Arena.ofConfined()) {
            return (MemorySegment) MTLCommandQueueMakeCommandBuffer.invokeExact(segment(commandQueue), toCString(arena, label));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLCommandQueue_makeCommandBuffer", throwable);
        }
    }

    public static void MTLCommandBuffer_commit(final MemorySegment commandBuffer) {
        try {
            MTLCommandBufferCommit.invokeExact(segment(commandBuffer));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLCommandBuffer_commit", throwable);
        }
    }

    public static MemorySegment metallum_create_semaphore() {
        try {
            return (MemorySegment) createSemaphore.invokeExact();
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_create_semaphore", throwable);
        }
    }

    public static void MTLCommandBuffer_commitWithSignal(final MemorySegment commandBuffer, final MemorySegment semaphore) {
        try {
            MTLCommandBufferCommitWithSignal.invokeExact(segment(commandBuffer), segment(semaphore));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLCommandBuffer_commitWithSignal", throwable);
        }
    }

    public static int metallum_semaphore_wait(final MemorySegment semaphore, final long timeoutMs) {
        try {
            return (int) semaphoreWait.invokeExact(segment(semaphore), timeoutMs);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_semaphore_wait", throwable);
        }
    }

    public static int MTLCommandBuffer_isCompleted(final MemorySegment commandBuffer) {
        try {
            return (int) MTLCommandBufferIsCompleted.invokeExact(segment(commandBuffer));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLCommandBuffer_isCompleted", throwable);
        }
    }

    public static int MTLCommandBuffer_waitUntilCompleted(final MemorySegment commandBuffer, final long timeoutMs) {
        try {
            return (int) MTLCommandBufferWaitUntilCompleted.invokeExact(segment(commandBuffer), timeoutMs);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLCommandBuffer_waitUntilCompleted", throwable);
        }
    }

    public static void MTLCommandBuffer_pushDebugGroup(final MemorySegment commandBuffer, final String label) {
        try (Arena arena = Arena.ofConfined()) {
            MTLCommandBufferPushDebugGroup.invokeExact(segment(commandBuffer), toCString(arena, label));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLCommandBuffer_pushDebugGroup", throwable);
        }
    }

    public static void MTLCommandBuffer_popDebugGroup(final MemorySegment commandBuffer) {
        try {
            MTLCommandBufferPopDebugGroup.invokeExact(segment(commandBuffer));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLCommandBuffer_popDebugGroup", throwable);
        }
    }

    public static MemorySegment MTLCommandBuffer_makeBlitCommandEncoder(final MemorySegment commandBuffer) {
        try {
            return (MemorySegment) MTLCommandBufferMakeBlitCommandEncoder.invokeExact(segment(commandBuffer));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLCommandBuffer_makeBlitCommandEncoder", throwable);
        }
    }

    public static void MTLCommandEncoder_endEncoding(final MemorySegment encoder) {
        try {
            MTLCommandEncoderEndEncoding.invokeExact(segment(encoder));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLCommandEncoder_endEncoding", throwable);
        }
    }

    public static void MTLBlitCommandEncoder_copyFromBufferToBuffer(
            final MemorySegment blitEncoder,
            final MemorySegment sourceBuffer,
            final long sourceOffset,
            final MemorySegment destinationBuffer,
            final long destinationOffset,
            final long length
    ) {
        try {
            MTLBlitCommandEncoderCopyFromBufferToBuffer.invokeExact(
                    segment(blitEncoder),
                    segment(sourceBuffer),
                    sourceOffset,
                    segment(destinationBuffer),
                    destinationOffset,
                    length
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLBlitCommandEncoder_copyFromBufferToBuffer", throwable);
        }
    }

    public static void MTLBlitCommandEncoder_copyFromBufferToTexture(
            final MemorySegment blitEncoder,
            final MemorySegment sourceBuffer,
            final long sourceOffset,
            final MemorySegment texture,
            final long mipLevel,
            final long slice,
            final long x,
            final long y,
            final long width,
            final long height,
            final long bytesPerRow,
            final long bytesPerImage
    ) {
        try {
            MTLBlitCommandEncoderCopyFromBufferToTexture.invokeExact(
                    segment(blitEncoder),
                    segment(sourceBuffer),
                    sourceOffset,
                    segment(texture),
                    mipLevel,
                    slice,
                    x,
                    y,
                    width,
                    height,
                    bytesPerRow,
                    bytesPerImage
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLBlitCommandEncoder_copyFromBufferToTexture", throwable);
        }
    }

    public static void MTLBlitCommandEncoder_copyFromTextureToTexture(
            final MemorySegment blitEncoder,
            final MemorySegment sourceTexture,
            final MemorySegment destinationTexture,
            final long mipLevel,
            final long sourceX,
            final long sourceY,
            final long destX,
            final long destY,
            final long width,
            final long height
    ) {
        try {
            MTLBlitCommandEncoderCopyFromTextureToTexture.invokeExact(
                    segment(blitEncoder),
                    segment(sourceTexture),
                    segment(destinationTexture),
                    mipLevel,
                    sourceX,
                    sourceY,
                    destX,
                    destY,
                    width,
                    height
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLBlitCommandEncoder_copyFromTextureToTexture", throwable);
        }
    }

    public static void MTLBlitCommandEncoder_copyFromTextureToBuffer(
            final MemorySegment blitEncoder,
            final MemorySegment sourceTexture,
            final MemorySegment destinationBuffer,
            final long destinationOffset,
            final long mipLevel,
            final long slice,
            final long x,
            final long y,
            final long width,
            final long height,
            final long bytesPerRow,
            final long bytesPerImage
    ) {
        try {
            MTLBlitCommandEncoderCopyFromTextureToBuffer.invokeExact(
                    segment(blitEncoder),
                    segment(sourceTexture),
                    segment(destinationBuffer),
                    destinationOffset,
                    mipLevel,
                    slice,
                    x,
                    y,
                    width,
                    height,
                    bytesPerRow,
                    bytesPerImage
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLBlitCommandEncoder_copyFromTextureToBuffer", throwable);
        }
    }

    public static MemorySegment metallum_create_buffer(final MemorySegment device, final long length, final long options) {
        try {
            return (MemorySegment) createBuffer.invokeExact(segment(device), length, options);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_create_buffer", throwable);
        }
    }

    public static MemorySegment metallum_create_texture_2d(
            final MemorySegment device,
            final MTLPixelFormat pixelFormat,
            final long width,
            final long height,
            final long depthOrLayers,
            final long mipLevels,
            final long cubeCompatible,
            final long usage,
            final MTLStorageMode storageMode,
            final String label
    ) {
        try (Arena arena = Arena.ofConfined()) {
            return (MemorySegment) createTexture2d.invokeExact(
                    segment(device),
                    pixelFormat.value,
                    width,
                    height,
                    depthOrLayers,
                    mipLevels,
                    cubeCompatible,
                    usage,
                    storageMode.value,
                    toCString(arena, label)
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_create_texture_2d", throwable);
        }
    }

    public static MemorySegment metallum_create_texture_view(final MemorySegment texture, final long baseMipLevel, final long mipLevelCount) {
        try {
            return (MemorySegment) createTextureView.invokeExact(segment(texture), baseMipLevel, mipLevelCount);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_create_texture_view", throwable);
        }
    }

    public static MemorySegment metallum_create_buffer_texture_view(
            final MemorySegment buffer,
            final long pixelFormat,
            final long offset,
            final long width,
            final long height,
            final long bytesPerRow
    ) {
        try {
            return (MemorySegment) createBufferTextureView.invokeExact(segment(buffer), pixelFormat, offset, width, height, bytesPerRow);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_create_buffer_texture_view", throwable);
        }
    }

    public static MemorySegment metallum_create_sampler(
            final MemorySegment device,
            final MTLSamplerAddressMode addressModeU,
            final MTLSamplerAddressMode addressModeV,
            final MTLSamplerMinMagFilter minFilter,
            final MTLSamplerMinMagFilter magFilter,
            final MTLSamplerMipFilter mipFilter,
            final int maxAnisotropy,
            final double lodMaxClamp
    ) {
        try {
            return (MemorySegment) createSampler.invokeExact(
                    segment(device),
                    addressModeU.value,
                    addressModeV.value,
                    minFilter.value,
                    magFilter.value,
                    mipFilter.value,
                    maxAnisotropy,
                    lodMaxClamp
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_create_sampler", throwable);
        }
    }

    public static MemorySegment MTLDevice_makeDepthStencilState(final MemorySegment device, final MTLCompareFunction depthCompareOp, final int writeDepth) {
        try {
            return (MemorySegment) MTLDeviceMakeDepthStencilState.invokeExact(segment(device), depthCompareOp.value, writeDepth);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLDevice_makeDepthStencilState", throwable);
        }
    }

    public static MemorySegment MTLCommandBuffer_makeRenderCommandEncoder(
            final MemorySegment commandBuffer,
            final MemorySegment colorTexture,
            final MemorySegment depthTexture,
            final double viewportWidth,
            final double viewportHeight,
            final int clearColorEnabled,
            final float clearColorRed,
            final float clearColorGreen,
            final float clearColorBlue,
            final float clearColorAlpha,
            final int clearDepthEnabled,
            final double clearDepth
    ) {
        try {
            return (MemorySegment) MTLCommandBufferMakeRenderCommandEncoder.invokeExact(
                    segment(commandBuffer),
                    segment(colorTexture),
                    segment(depthTexture),
                    viewportWidth,
                    viewportHeight,
                    clearColorEnabled,
                    clearColorRed,
                    clearColorGreen,
                    clearColorBlue,
                    clearColorAlpha,
                    clearDepthEnabled,
                    clearDepth
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLCommandBuffer_makeRenderCommandEncoder", throwable);
        }
    }

    public static void MTLRenderCommandEncoder_clearDraw(
            final MemorySegment encoder,
            final MemorySegment colorTexture,
            final MemorySegment depthTexture,
            final double viewportWidth,
            final double viewportHeight,
            final int clearColorEnabled,
            final float clearColorRed,
            final float clearColorGreen,
            final float clearColorBlue,
            final float clearColorAlpha,
            final int clearDepthEnabled,
            final double clearDepth
    ) {
        try {
            MTLRenderCommandEncoderClearDraw.invokeExact(
                    segment(encoder),
                    segment(colorTexture),
                    segment(depthTexture),
                    viewportWidth,
                    viewportHeight,
                    clearColorEnabled,
                    clearColorRed,
                    clearColorGreen,
                    clearColorBlue,
                    clearColorAlpha,
                    clearDepthEnabled,
                    clearDepth
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_clearDraw", throwable);
        }
    }

    public static void MTLRenderCommandEncoder_setRenderPipelineState(final MemorySegment encoder, final MemorySegment pipeline) {
        try {
            MTLRenderCommandEncoderSetRenderPipelineState.invokeExact(segment(encoder), segment(pipeline));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_setRenderPipelineState", throwable);
        }
    }

    public static void MTLRenderCommandEncoder_setDepthStencilState(final MemorySegment encoder, final MemorySegment depthStencilState) {
        try {
            MTLRenderCommandEncoderSetDepthStencilState.invokeExact(segment(encoder), segment(depthStencilState));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_setDepthStencilState", throwable);
        }
    }

    public static void MTLRenderCommandEncoder_setDepthBias(final MemorySegment encoder, final float depthBias, final float slopeScale, final float clamp) {
        try {
            MTLRenderCommandEncoderSetDepthBias.invokeExact(segment(encoder), depthBias, slopeScale, clamp);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_setDepthBias", throwable);
        }
    }

    public static void MTLRenderCommandEncoder_setFrontFacingWinding(final MemorySegment encoder, final int clockwise) {
        try {
            MTLRenderCommandEncoderSetFrontFacingWinding.invokeExact(segment(encoder), clockwise);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_setFrontFacingWinding", throwable);
        }
    }

    public static void MTLRenderCommandEncoder_setCullMode(final MemorySegment encoder, final long cullMode) {
        try {
            MTLRenderCommandEncoderSetCullMode.invokeExact(segment(encoder), cullMode);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_setCullMode", throwable);
        }
    }

    public static void MTLRenderCommandEncoder_setTriangleFillMode(final MemorySegment encoder, final int lines) {
        try {
            MTLRenderCommandEncoderSetTriangleFillMode.invokeExact(segment(encoder), lines);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_setTriangleFillMode", throwable);
        }
    }

    public static void MTLRenderCommandEncoder_setBuffer(final MemorySegment encoder, final MemorySegment buffer, final long offset, final long index, final int stageMask) {
        try {
            MTLRenderCommandEncoderSetBuffer.invokeExact(segment(encoder), segment(buffer), offset, index, stageMask);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_setBuffer", throwable);
        }
    }

    public static void MTLRenderCommandEncoder_setBufferOffset(final MemorySegment encoder, final long offset, final long index, final int stageMask) {
        try {
            MTLRenderCommandEncoderSetBufferOffset.invokeExact(segment(encoder), offset, index, stageMask);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_setBufferOffset", throwable);
        }
    }

    public static void MTLRenderCommandEncoder_setTexture(final MemorySegment encoder, final MemorySegment texture, final long index, final int stageMask) {
        try {
            MTLRenderCommandEncoderSetTexture.invokeExact(segment(encoder), segment(texture), index, stageMask);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_setTexture", throwable);
        }
    }

    public static void MTLRenderCommandEncoder_setTextureAndSampler(final MemorySegment encoder, final MemorySegment texture, final MemorySegment sampler, final long index, final int stageMask) {
        try {
            MTLRenderCommandEncoderSetTextureAndSampler.invokeExact(segment(encoder), segment(texture), segment(sampler), index, stageMask);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_setTextureAndSampler", throwable);
        }
    }

    public static void MTLRenderCommandEncoder_setScissorRect(final MemorySegment encoder, final long x, final long y, final long width, final long height) {
        try {
            MTLRenderCommandEncoderSetScissorRect.invokeExact(segment(encoder), x, y, width, height);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_setScissorRect", throwable);
        }
    }

    public static void MTLRenderCommandEncoder_drawPrimitives(
            final MemorySegment encoder,
            final long primitiveType,
            final long firstVertex,
            final long vertexCount,
            final long instanceCount,
            final long baseInstance
    ) {
        try {
            MTLRenderCommandEncoderDrawPrimitives.invokeExact(segment(encoder), primitiveType, firstVertex, vertexCount, instanceCount, baseInstance);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_drawPrimitives", throwable);
        }
    }

    public static void MTLRenderCommandEncoder_drawIndexedPrimitives(
            final MemorySegment encoder,
            final long primitiveType,
            final long indexCount,
            final long indexType,
            final MemorySegment indexBuffer,
            final long indexBufferOffset,
            final long instanceCount,
            final long baseVertex,
            final long baseInstance
    ) {
        try {
            MTLRenderCommandEncoderDrawIndexedPrimitives.invokeExact(
                    segment(encoder),
                    primitiveType,
                    indexCount,
                    indexType,
                    segment(indexBuffer),
                    indexBufferOffset,
                    instanceCount,
                    baseVertex,
                    baseInstance
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_drawIndexedPrimitives", throwable);
        }
    }

    public static void MTLRenderCommandEncoder_multiDrawIndexed(
            final MemorySegment encoder,
            final long primitiveType,
            final long indexType,
            final MemorySegment indexBuffer,
            final MemorySegment firstIndexOffsets,
            final MemorySegment indexCounts,
            final MemorySegment vertexOffsets,
            final long drawCount,
            final long instanceCount,
            final long baseInstance
    ) {
        try {
            MTLRenderCommandEncoderMultiDrawIndexed.invokeExact(
                    segment(encoder),
                    primitiveType,
                    indexType,
                    segment(indexBuffer),
                    segment(firstIndexOffsets),
                    segment(indexCounts),
                    segment(vertexOffsets),
                    drawCount,
                    instanceCount,
                    baseInstance
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_multiDrawIndexed", throwable);
        }
    }

    public static void MTLRenderCommandEncoder_drawIndexedPrimitivesIndirect(
            final MemorySegment encoder,
            final long primitiveType,
            final long indexType,
            final MemorySegment indexBuffer,
            final MemorySegment indirectBuffer,
            final long indirectBufferOffset,
            final long drawCount,
            final long stride
    ) {
        try {
            MTLRenderCommandEncoderDrawIndexedPrimitivesIndirect.invokeExact(
                    segment(encoder),
                    primitiveType,
                    indexType,
                    segment(indexBuffer),
                    segment(indirectBuffer),
                    indirectBufferOffset,
                    drawCount,
                    stride
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_drawIndexedPrimitivesIndirect", throwable);
        }
    }

    public static void MTLRenderCommandEncoder_drawPrimitivesIndirect(
            final MemorySegment encoder,
            final long primitiveType,
            final MemorySegment indirectBuffer,
            final long indirectBufferOffset,
            final long drawCount,
            final long stride
    ) {
        try {
            MTLRenderCommandEncoderDrawPrimitivesIndirect.invokeExact(
                    segment(encoder),
                    primitiveType,
                    segment(indirectBuffer),
                    indirectBufferOffset,
                    drawCount,
                    stride
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_drawPrimitivesIndirect", throwable);
        }
    }

    public static void MTLRenderCommandEncoder_drawIndexedPrimitivesTriangleFan(
            final MemorySegment encoder,
            final MemorySegment indexBuffer,
            final MemorySegment fanIndexBuffer,
            final long fanIndexBufferOffset,
            final long indexType,
            final long indexBufferOffset,
            final long indexCount,
            final long baseVertex,
            final long instanceCount,
            final long baseInstance
    ) {
        try {
            MTLRenderCommandEncoderDrawIndexedPrimitivesTriangleFan.invokeExact(
                    segment(encoder),
                    segment(indexBuffer),
                    segment(fanIndexBuffer),
                    fanIndexBufferOffset,
                    indexType,
                    indexBufferOffset,
                    indexCount,
                    baseVertex,
                    instanceCount,
                    baseInstance
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_drawIndexedPrimitivesTriangleFan", throwable);
        }
    }

    public static void MTLCommandBuffer_clearColorDepthTexturesRegion(
            final MemorySegment commandBuffer,
            final MemorySegment colorTexture,
            final float clearColorRed,
            final float clearColorGreen,
            final float clearColorBlue,
            final float clearColorAlpha,
            final MemorySegment depthTexture,
            final double clearDepth,
            final int x,
            final int y,
            final int width,
            final int height,
            final MemorySegment globalFence
    ) {
        try {
            MTLCommandBufferClearColorDepthTexturesRegion.invokeExact(
                    segment(commandBuffer),
                    segment(colorTexture),
                    clearColorRed,
                    clearColorGreen,
                    clearColorBlue,
                    clearColorAlpha,
                    segment(depthTexture),
                    clearDepth,
                    x,
                    y,
                    width,
                    height,
                    segment(globalFence)
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLCommandBuffer_clearColorDepthTexturesRegion", throwable);
        }
    }

    public static MemorySegment metallum_MTLVertexDescriptor_create() {
        try {
            return (MemorySegment) MTLVertexDescriptorCreate.invokeExact();
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLVertexDescriptor_create", throwable);
        }
    }

    public static void metallum_MTLVertexDescriptor_setAttribute(
            final MemorySegment desc,
            final long index,
            final long format,
            final long offset,
            final long bufferIndex
    ) {
        try {
            MTLVertexDescriptorSetAttribute.invokeExact(segment(desc), index, format, offset, bufferIndex);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLVertexDescriptor_setAttribute", throwable);
        }
    }

    public static void metallum_MTLVertexDescriptor_setLayout(
            final MemorySegment desc,
            final long bufferIndex,
            final long stride,
            final long stepFunction,
            final long stepRate
    ) {
        try {
            MTLVertexDescriptorSetLayout.invokeExact(segment(desc), bufferIndex, stride, stepFunction, stepRate);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLVertexDescriptor_setLayout", throwable);
        }
    }

    public static MemorySegment metallum_MTLRenderPipelineDescriptor_create() {
        try {
            return (MemorySegment) MTLRenderPipelineDescriptorCreate.invokeExact();
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderPipelineDescriptor_create", throwable);
        }
    }

    public static MemorySegment metallum_create_shader_function(
            final MemorySegment device,
            final String source,
            final String entryPoint
    ) {
        try (Arena arena = Arena.ofConfined()) {
            return (MemorySegment) createShaderFunction.invokeExact(
                    segment(device),
                    toCString(arena, source),
                    toCString(arena, entryPoint)
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_create_shader_function", throwable);
        }
    }

    public static void metallum_MTLRenderPipelineDescriptor_setCompiledFunctions(
            final MemorySegment desc,
            final MemorySegment vertexFunction,
            final MemorySegment fragmentFunction
    ) {
        try {
            MTLRenderPipelineDescriptorSetCompiledFunctions.invokeExact(
                    segment(desc),
                    segment(vertexFunction),
                    segment(fragmentFunction)
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderPipelineDescriptor_setCompiledFunctions", throwable);
        }
    }

    public static void metallum_MTLRenderPipelineDescriptor_setVertexDescriptor(
            final MemorySegment desc,
            final MemorySegment vertexDesc
    ) {
        try {
            MTLRenderPipelineDescriptorSetVertexDescriptor.invokeExact(segment(desc), segment(vertexDesc));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderPipelineDescriptor_setVertexDescriptor", throwable);
        }
    }

    public static void metallum_MTLRenderPipelineDescriptor_setAttachmentFormats(
            final MemorySegment desc,
            final MTLPixelFormat colorFormat,
            final MTLPixelFormat depthFormat,
            final MTLPixelFormat stencilFormat
    ) {
        try {
            MTLRenderPipelineDescriptorSetAttachmentFormats.invokeExact(segment(desc), colorFormat.value, depthFormat.value, stencilFormat.value);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderPipelineDescriptor_setAttachmentFormats", throwable);
        }
    }

    public static void metallum_MTLRenderPipelineDescriptor_setBlendState(
            final MemorySegment desc,
            final int enabled,
            final long srcRgb,
            final long dstRgb,
            final long opRgb,
            final long srcAlpha,
            final long dstAlpha,
            final long opAlpha,
            final long writeMask
    ) {
        try {
            MTLRenderPipelineDescriptorSetBlendState.invokeExact(
                    segment(desc),
                    enabled,
                    srcRgb,
                    dstRgb,
                    opRgb,
                    srcAlpha,
                    dstAlpha,
                    opAlpha,
                    writeMask
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderPipelineDescriptor_setBlendState", throwable);
        }
    }

    public static MemorySegment metallum_MTLDevice_makeRenderPipelineState(
            final MemorySegment device,
            final MemorySegment descriptor
    ) {
        try {
            return (MemorySegment) MTLDeviceMakeRenderPipelineState.invokeExact(segment(device), segment(descriptor));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLDevice_makeRenderPipelineState", throwable);
        }
    }

    public static void metallum_configure_layer(final MemorySegment layer, final double width, final double height, final int immediatePresentMode) {
        try {
            configureLayer.invokeExact(segment(layer), width, height, immediatePresentMode);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_configure_layer", throwable);
        }
    }

    public static void MTLCommandBuffer_encodePresentTextureToDrawable(final MemorySegment commandBuffer, final MemorySegment layer, final MemorySegment sourceTexture, final MemorySegment globalFence) {
        try {
            MTLCommandBufferEncodePresentTextureToDrawable.invokeExact(segment(commandBuffer), segment(layer), segment(sourceTexture), segment(globalFence));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLCommandBuffer_encodePresentTextureToDrawable", throwable);
        }
    }

    public static void metallum_release_object(final MemorySegment object) {
        try {
            releaseObject.invokeExact(segment(object));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_release_object", throwable);
        }
    }

    public static MemorySegment metallum_create_fence(final MemorySegment device) {
        try {
            return (MemorySegment) createFence.invokeExact(segment(device));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_create_fence", throwable);
        }
    }

    public static void MTLRenderCommandEncoder_updateFence(final MemorySegment encoder, final MemorySegment fence, final long stages) {
        try {
            MTLRenderCommandEncoderUpdateFence.invokeExact(segment(encoder), segment(fence), stages);
        } catch (Throwable throwable) {
            throw bridgeFailure("MTLRenderCommandEncoder_updateFence", throwable);
        }
    }

    public static void MTLRenderCommandEncoder_waitForFence(final MemorySegment encoder, final MemorySegment fence, final long stages) {
        try {
            MTLRenderCommandEncoderWaitForFence.invokeExact(segment(encoder), segment(fence), stages);
        } catch (Throwable throwable) {
            throw bridgeFailure("MTLRenderCommandEncoder_waitForFence", throwable);
        }
    }

    public static void MTLBlitCommandEncoder_updateFence(final MemorySegment encoder, final MemorySegment fence) {
        try {
            MTLBlitCommandEncoderUpdateFence.invokeExact(segment(encoder), segment(fence));
        } catch (Throwable throwable) {
            throw bridgeFailure("MTLBlitCommandEncoder_updateFence", throwable);
        }
    }

    public static void MTLBlitCommandEncoder_waitForFence(final MemorySegment encoder, final MemorySegment fence) {
        try {
            MTLBlitCommandEncoderWaitForFence.invokeExact(segment(encoder), segment(fence));
        } catch (Throwable throwable) {
            throw bridgeFailure("MTLBlitCommandEncoder_waitForFence", throwable);
        }
    }

    public static MemorySegment metallum_get_buffer_contents(final MemorySegment buffer) {
        try {
            return (MemorySegment) getBufferContents.invokeExact(segment(buffer));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_get_buffer_contents", throwable);
        }
    }

    public static ByteBuffer nativeByteBufferView(final MemorySegment pointer, final long byteSize) {
        if (pointer == null || pointer.address() == 0L) {
            throw new IllegalArgumentException("Cannot create a ByteBuffer view for a null native pointer");
        }
        if (byteSize < 0L) {
            throw new IllegalArgumentException("Byte size must be non-negative");
        }
        return MemorySegment.ofAddress(pointer.address()).reinterpret(byteSize).asByteBuffer();
    }

    private static MemorySegment segment(final MemorySegment pointer) {
        return pointer == null || pointer.address() == 0L ? MemorySegment.NULL : pointer;
    }

    private static MemorySegment toCString(final Arena arena, final String value) {
        return value == null ? MemorySegment.NULL : arena.allocateFrom(value);
    }

    public static boolean isNullHandle(@Nullable final MemorySegment pointer) {
        return pointer == null || pointer.address() == 0L;
    }

    private static RuntimeException bridgeFailure(final String symbol, final Throwable throwable) {
        return new IllegalStateException("Native bridge call failed: " + symbol, throwable);
    }
}
