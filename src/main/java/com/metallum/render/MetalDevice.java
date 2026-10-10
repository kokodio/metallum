package com.metallum.render;

import com.metallum.Metallum;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.device.DeviceFeatures;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.device.DeviceLimits;
import com.mojang.renderpearl.api.device.DeviceType;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.HintsAndWorkarounds;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import com.mojang.renderpearl.util.ShaderCompileException;
import io.github.kokodio.metaljvm.foundation.NSObject;
import io.github.kokodio.metaljvm.metal.*;
import io.github.kokodio.metaljvm.quartzcore.CAMetalLayer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.function.Supplier;
import java.util.function.BooleanSupplier;

@Environment(EnvType.CLIENT)
final class MetalDevice implements GpuDeviceBackend {
    private final MTLDevice metalDevice;
    private final GpuDebugOptions debugOptions;
    private final MetalCommandEncoder commandEncoder;
    private final DeviceInfo deviceInfo;
    public final MTLCommandQueue commandQueue;
    private final Map<MslFunctionKey, MTLFunction> functionCache = new HashMap<>();
    private final Map<Long, MTLDepthStencilState> depthStencilStates = new HashMap<>();

    MetalDevice(
            final GpuDebugOptions debugOptions,
            final MTLDevice metalDevice,
            final String deviceName
    ) {
        this.debugOptions = debugOptions;
        this.metalDevice = metalDevice;
        this.commandQueue = MetalUtilities.nonNil(this.metalDevice.newCommandQueue(), "newCommandQueue");
        MetalUtilities.init(this.metalDevice);
        this.commandEncoder = new MetalCommandEncoder(this);
        this.deviceInfo = buildDeviceInfo(deviceName);
    }

    @Override
    public @NonNull GpuSurfaceBackend createSurface(final long windowHandle, final @NonNull BooleanSupplier isIconified) {
        return new MetalSurface(windowHandle, this.metalDevice);
    }

    @Override
    public @NonNull MetalCommandEncoder createCommandEncoder() {
        return this.commandEncoder;
    }

    @Override
    public @NonNull GpuSampler createSampler(
            final @NonNull AddressMode addressModeU,
            final @NonNull AddressMode addressModeV,
            final @NonNull FilterMode minFilter,
            final @NonNull FilterMode magFilter,
            final int maxAnisotropy,
            final @NonNull OptionalDouble maxLod
    ) {
        return new MetalGpuSampler(this, addressModeU, addressModeV, minFilter, magFilter, maxAnisotropy, maxLod);
    }

    @Override
    public @NonNull GpuTexture createTexture(
            @Nullable final String label,
            @GpuTexture.Usage final int usage,
            final @NonNull GpuFormat format,
            final int width,
            final int height,
            final int depthOrLayers,
            final int mipLevels
    ) {
        return new MetalGpuTexture(this, usage, this.useLabels() && label != null ? label : "", format, width, height, depthOrLayers, mipLevels);
    }

    @Override
    public @NonNull GpuTextureView createTextureView(final @NonNull GpuTexture texture, final int baseMipLevel, final int mipLevels) {
        return new MetalGpuTextureView(texture, baseMipLevel, mipLevels);
    }

    @Override
    public @NonNull GpuBuffer createBuffer(@Nullable final Supplier<String> label, @GpuBuffer.Usage final int usage, final long size) {
        return new MetalGpuBuffer(this, usage, size, this.resolveDebugLabel(label));
    }

    @Override
    public @NonNull GpuBuffer createBuffer(@Nullable final Supplier<String> label, @GpuBuffer.Usage final int usage, final ByteBuffer data) {
        MetalGpuBuffer buffer = (MetalGpuBuffer) this.createBuffer(label, usage | GpuBuffer.USAGE_COPY_DST, data.remaining());
        if (buffer.isCpuAccessible()) {
            buffer.writeDirect(0L, data);
        } else {
            this.commandEncoder.writeToBuffer(buffer.slice(), data.duplicate());
        }
        return buffer;
    }

    @Override
    public @NonNull List<String> getLastDebugMessages() {
        return List.of();
    }

    @Override
    public boolean isDebuggingEnabled() {
        return this.debugOptions.logLevel() > 0 || this.debugOptions.useLabels() || this.debugOptions.useValidationLayers();
    }

    boolean useLabels() {
        return this.debugOptions.useLabels();
    }

    @Override
    public BackendRenderPipeline.@NonNull Pending compilePipeline(final BackendRenderPipeline.@NonNull CreateInfo createInfo) {
        MetalCrossShaderCompiler.Compiled compiled;
        try {
            compiled = MetalCrossShaderCompiler.compile(createInfo);
        } catch (ShaderCompileException e) {
            Metallum.LOGGER.error("[metallum] Failed to cross-compile pipeline {}", createInfo.name(), e);
            return BackendRenderPipeline.Pending.NULL;
        }

        return () -> {
            MetalCompiledRenderPipeline pipeline = new MetalCompiledRenderPipeline(this, createInfo, compiled);
            if (!pipeline.isValid()) {
                pipeline.close();
                return null;
            }
            return pipeline;
        };
    }

    void clearFunctionCache() {
        this.waitForSubmittedGpuWork();
        this.functionCache.values().forEach(MTLFunction::release);
        this.functionCache.clear();
    }

    @Override
    public void close() {
        this.waitForSubmittedGpuWork();
        this.commandEncoder.close();
        this.clearFunctionCache();
        MetalUtilities.close();
        this.commandQueue.release();
        for (MTLDepthStencilState state : depthStencilStates.values()) {
            state.release();
        }
        depthStencilStates.clear();
        this.metalDevice.release();
    }

    @Override
    public @NonNull GpuQueryPool createTimestampQueryPool(final int size) {
        return new MetalGpuQueryPool(size);
    }

    @Override
    public long getTimestampCalibrationOffset() {
        return 0L;
    }

    long getTimestampNow() {
        return System.nanoTime();
    }

    @Override
    public @NonNull DeviceInfo getDeviceInfo() {
        return this.deviceInfo;
    }

    MTLDevice metalDevice() {
        return this.metalDevice;
    }

    MTLDepthStencilState depthStencilState(final MTLCompareFunction compareFunction, final boolean writeDepth) {
        long key = (compareFunction.value << 1) | (writeDepth ? 1L : 0L);
        MTLDepthStencilState cached = depthStencilStates.get(key);
        if (cached != null) {
            return cached;
        }
        MTLDepthStencilDescriptor descriptor = MTLDepthStencilDescriptor.alloc().init();
        descriptor.setDepthCompareFunction(compareFunction);
        descriptor.setDepthWriteEnabled(writeDepth);
        MTLDepthStencilState state = MetalUtilities.nonNil(metalDevice.newDepthStencilState(descriptor), "newDepthStencilStateWithDescriptor:");
        depthStencilStates.put(key, state);
        descriptor.release();
        return state;
    }

    void waitForSubmittedGpuWork() {
        this.commandEncoder.waitForSubmittedGpuWork();
    }

    void queueResourceRelease(final NSObject object) {
        this.commandEncoder.queueForDestroy(object::release);
    }

    @Nullable
    MTLFunction getOrCompileFunction(final String msl, final String entryPoint, final String name) {
        return this.functionCache.computeIfAbsent(new MslFunctionKey(msl, entryPoint), key -> this.compileFunction(key, name));
    }

    @Nullable
    private MTLFunction compileFunction(final MslFunctionKey key, final String name) {
        MTLLibrary library = MetalUtilities.newLibrary(this.metalDevice, key.msl());
        if (library == null) {
            return null;
        }

        MTLFunction function = library.newFunctionWithName(key.entryPoint());
        library.release();
        if (function == null) {
            Metallum.LOGGER.error("[metallum] Failed to resolve MSL entry point '{}'", key.entryPoint());
        } else if (this.useLabels()) {
            function.setLabel(name);
        }
        return function;
    }

    private record MslFunctionKey(String msl, String entryPoint) {
    }

    private DeviceInfo buildDeviceInfo(final String deviceName) {
        DeviceType type = DeviceType.INTEGRATED;
        Set<String> extensions = Set.of();
        String osVersion = System.getProperty("os.version", "").trim();
        String driverDescription = "macOS " + osVersion;
        long maxMemoryAllocationSize = Math.min(metalDevice.maxBufferLength(), metalDevice.recommendedMaxWorkingSetSize());
        return new DeviceInfo(
                deviceName,
                "Apple",
                driverDescription,
                true,
                "Metal",
                1.0F,
                new DeviceLimits(16, 16, 16384, maxMemoryAllocationSize, Integer.MAX_VALUE, ColorTargetState.MAX_COLOR_TARGETS, Integer.MAX_VALUE),
                new DeviceFeatures(true, true, true, true, true, true, true, true),
                extensions,
                new HintsAndWorkarounds(false, false, true, false),
                type
        );
    }

    @Nullable
    private String resolveDebugLabel(@Nullable final Supplier<String> label) {
        return this.useLabels() && label != null ? label.get() : null;
    }
}
