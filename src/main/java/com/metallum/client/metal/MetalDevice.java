package com.metallum.client.metal;

import ca.weblite.objc.Client;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.systems.DeviceInfo;
import com.mojang.blaze3d.systems.DeviceLimits;
import com.mojang.blaze3d.systems.DeviceType;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import com.mojang.blaze3d.systems.GpuQueryPool;
import com.mojang.blaze3d.systems.GpuSurfaceBackend;
import com.mojang.blaze3d.systems.HintsAndWorkarounds;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.sun.jna.Pointer;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

@Environment(EnvType.CLIENT)
final class MetalDevice implements GpuDeviceBackend {
	private static final Client COERCING_CLIENT = Client.getInstance();
	private final ShaderSource defaultShaderSource;
	private final MetalCocoaBootstrap.BootstrapContext bootstrap;
	private final MetalNativeBridge nativeApi;
	private final GpuDebugOptions debugOptions;
	private final MetalCommandEncoder commandEncoder;
	private final DeviceInfo deviceInfo;
	private final Pointer commandQueue;
	private final MetalBufferPool bufferPool = new MetalBufferPool();
	private final Map<RenderPipeline, MetalCompiledRenderPipeline> compiledPipelines = new ConcurrentHashMap<>();
	private volatile ShaderSource activeShaderSource;

	MetalDevice(
		final ShaderSource defaultShaderSource,
		final MetalCocoaBootstrap.BootstrapContext bootstrap,
		final GpuDebugOptions debugOptions
	) {
		this.defaultShaderSource = defaultShaderSource;
		this.activeShaderSource = defaultShaderSource;
		this.bootstrap = bootstrap;
		this.nativeApi = MetalNativeBridge.INSTANCE;
		this.debugOptions = debugOptions;
		this.commandQueue = this.nativeApi.metallum_create_command_queue(bootstrap.device());
		if (MetalProbe.isNullPointer(this.commandQueue)) {
			throw new IllegalStateException("Failed to create Metal command queue");
		}
		this.commandEncoder = new MetalCommandEncoder(this);
		this.deviceInfo = buildDeviceInfo(bootstrap);
	}

	@Override
	public GpuSurfaceBackend createSurface(final long windowHandle) {
		return new MetalSurface(windowHandle, this, this.bootstrap);
	}

	@Override
	public MetalCommandEncoder createCommandEncoder() {
		return this.commandEncoder;
	}

	@Override
	public GpuSampler createSampler(
		final AddressMode addressModeU,
		final AddressMode addressModeV,
		final FilterMode minFilter,
		final FilterMode magFilter,
		final int maxAnisotropy,
		final OptionalDouble maxLod
	) {
		return new MetalGpuSampler(this, addressModeU, addressModeV, minFilter, magFilter, maxAnisotropy, maxLod);
	}

	@Override
	public GpuTexture createTexture(
		@Nullable final Supplier<String> label,
		@GpuTexture.Usage final int usage,
		final GpuFormat format,
		final int width,
		final int height,
		final int depthOrLayers,
		final int mipLevels
	) {
		return this.createTexture(label == null ? null : label.get(), usage, format, width, height, depthOrLayers, mipLevels);
	}

	@Override
	public GpuTexture createTexture(
		@Nullable final String label,
		@GpuTexture.Usage final int usage,
		final GpuFormat format,
		final int width,
		final int height,
		final int depthOrLayers,
		final int mipLevels
	) {
		return new MetalGpuTexture(this, usage, label == null ? "" : label, format, width, height, depthOrLayers, mipLevels);
	}

	@Override
	public GpuTextureView createTextureView(final GpuTexture texture) {
		return this.createTextureView(texture, 0, texture.getMipLevels());
	}

	@Override
	public GpuTextureView createTextureView(final GpuTexture texture, final int baseMipLevel, final int mipLevels) {
		return new MetalGpuTextureView(texture, baseMipLevel, mipLevels);
	}

	@Override
	public GpuBuffer createBuffer(@Nullable final Supplier<String> label, @GpuBuffer.Usage final int usage, final long size) {
		return new MetalGpuBuffer(this, label == null ? null : label.get(), usage, size);
	}

	@Override
	public GpuBuffer createBuffer(@Nullable final Supplier<String> label, @GpuBuffer.Usage final int usage, final ByteBuffer data) {
		MetalGpuBuffer buffer = (MetalGpuBuffer)this.createBuffer(label, usage | GpuBuffer.USAGE_COPY_DST, data.remaining());
		this.commandEncoder.writeToBuffer(buffer.slice(), data.duplicate());
		return buffer;
	}

	@Override
	public List<String> getLastDebugMessages() {
		return List.of();
	}

	@Override
	public boolean isDebuggingEnabled() {
		return this.debugOptions.logLevel() > 0 || this.debugOptions.useLabels() || this.debugOptions.useValidationLayers();
	}

	@Override
	public CompiledRenderPipeline precompilePipeline(final RenderPipeline pipeline, @Nullable final ShaderSource shaderSource) {
		ShaderSource effectiveSource = shaderSource == null ? this.activeShaderSource : shaderSource;
		if (shaderSource != null) {
			this.activeShaderSource = shaderSource;
		}
		MetalCompiledRenderPipeline compiled = MetalCrossShaderCompiler.compile(pipeline, effectiveSource);
		if (!compiled.isValid() && effectiveSource != this.defaultShaderSource) {
			compiled = MetalCrossShaderCompiler.compile(pipeline, this.defaultShaderSource);
		}
		if (compiled.isValid()) {
			this.compiledPipelines.put(pipeline, compiled);
		} else {
			this.compiledPipelines.remove(pipeline);
		}
		return compiled;
	}

	@Override
	public void clearPipelineCache() {
		this.compiledPipelines.clear();
	}

	@Override
	public void close() {
		this.waitForSubmittedGpuWork();
		this.commandEncoder.close();
		this.bufferPool.close(this.nativeApi);
		try {
			COERCING_CLIENT.send(this.bootstrap.cocoaView(), "setWantsLayer:", false);
		} catch (Throwable ignored) {
		}
		if (!MetalProbe.isNullPointer(this.commandQueue)) {
			this.nativeApi.metallum_release_object(this.commandQueue);
		}
		if (!MetalProbe.isNullPointer(this.bootstrap.device())) {
			this.nativeApi.metallum_release_object(this.bootstrap.device());
		}
	}

	@Override
	public GpuQueryPool createTimestampQueryPool(final int size) {
		return new MetalGpuQueryPool(size);
	}

	@Override
	public long getTimestampNow() {
		return System.nanoTime();
	}

	@Override
	public DeviceInfo getDeviceInfo() {
		return this.deviceInfo;
	}

	ShaderSource defaultShaderSource() {
		return this.defaultShaderSource;
	}

	Pointer commandQueue() {
		return this.commandQueue;
	}

	Pointer metalDevicePointer() {
		return this.bootstrap.device();
	}

	void waitForSubmittedGpuWork() {
		this.commandEncoder.submitRenderPass();
		this.nativeApi.metallum_wait_for_command_queue_idle(this.commandQueue);
	}

	void queueResourceRelease(final Pointer handle) {
		if (MetalProbe.isNullPointer(handle)) {
			return;
		}
		this.commandEncoder.queueForDestroy(() -> this.nativeApi.metallum_release_object(handle));
	}

	@Nullable
	Pointer acquireReusableBuffer(final long size, final long resourceOptions) {
		return this.bufferPool.acquire(size, resourceOptions);
	}

	long allocationSize(final long requestedSize) {
		return this.bufferPool.allocationSize(requestedSize);
	}

	void queueBufferRecycle(final Pointer handle, final long size, final long resourceOptions) {
		if (MetalProbe.isNullPointer(handle)) {
			return;
		}
		this.commandEncoder.queueForDestroy(() -> this.bufferPool.recycle(handle, size, resourceOptions));
	}

	MetalCompiledRenderPipeline getOrCompilePipeline(final RenderPipeline pipeline) {
		MetalCompiledRenderPipeline cached = this.compiledPipelines.get(pipeline);
		if (cached != null && cached.isValid()) {
			return cached;
		}

		MetalCompiledRenderPipeline compiled = MetalCrossShaderCompiler.compile(pipeline, this.activeShaderSource);
		if (!compiled.isValid() && this.activeShaderSource != this.defaultShaderSource) {
			compiled = MetalCrossShaderCompiler.compile(pipeline, this.defaultShaderSource);
		}

		if (compiled.isValid()) {
			this.compiledPipelines.put(pipeline, compiled);
		} else {
			this.compiledPipelines.remove(pipeline);
		}
		return compiled;
	}

	private static DeviceInfo buildDeviceInfo(final MetalCocoaBootstrap.BootstrapContext bootstrap) {
		boolean lowPower = readBoolean(bootstrap.device(), "isLowPower");
		boolean unifiedMemory = readBoolean(bootstrap.device(), "hasUnifiedMemory");
		DeviceType type = lowPower || unifiedMemory ? DeviceType.INTEGRATED : DeviceType.DISCRETE;
		Set<String> underlyingExtensions = Set.of("CAMetalLayer", "MTLDevice", "Runtime MSL");
		String osVersion = System.getProperty("os.version", "").trim();
		String driverDescription = osVersion.isEmpty() ? "Metal" : "Metal (macOS " + osVersion + ")";
		return new DeviceInfo(
			bootstrap.deviceName(),
			"Apple",
			driverDescription,
			true,
			"Metal",
			1.0F,
			new DeviceLimits(16, 256, 16384),
			underlyingExtensions,
			new HintsAndWorkarounds(false, false),
			type
		);
	}

	private static boolean readBoolean(final com.sun.jna.Pointer receiver, final String selector) {
		Object value = COERCING_CLIENT.send(receiver, selector);
		return value instanceof Boolean bool && bool;
	}
}
