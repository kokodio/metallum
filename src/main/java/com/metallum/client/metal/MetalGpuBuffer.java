package com.metallum.client.metal;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.sun.jna.Pointer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

@Environment(EnvType.CLIENT)
final class MetalGpuBuffer extends GpuBuffer {
	private final MetalDevice device;
	@Nullable
	private final String label;
	private final boolean cpuAccessible;
	private final long resourceOptions;
	private final long allocationSize;
	@Nullable
	private Pointer nativeHandle;
	@Nullable
	private ByteBuffer storage;
	private boolean closed;

	MetalGpuBuffer(final MetalDevice device, @Nullable final String label, @GpuBuffer.Usage final int usage, final long size) {
		super(usage, size);
		this.device = device;
		this.label = label;

		if (size > Integer.MAX_VALUE) {
			throw new UnsupportedOperationException("Metal buffer stub only supports buffers up to 2 GiB");
		}

		this.cpuAccessible = isCpuAccessible(usage);
		this.resourceOptions = toMtlResourceOptions(usage);
		this.allocationSize = device.allocationSize(size);
		Pointer pooledHandle = device.acquireReusableBuffer(this.allocationSize, this.resourceOptions);
		this.nativeHandle = MetalProbe.isNullPointer(pooledHandle)
			? MetalNativeBridge.INSTANCE.metallum_create_buffer(device.metalDevicePointer(), this.allocationSize, this.resourceOptions, label)
			: pooledHandle;
		if (MetalProbe.isNullPointer(this.nativeHandle)) {
			throw new IllegalStateException("Failed to create Metal buffer");
		}

		if (this.cpuAccessible) {
			Pointer contents = MetalNativeBridge.INSTANCE.metallum_get_buffer_contents(this.nativeHandle);
			if (MetalProbe.isNullPointer(contents)) {
				MetalNativeBridge.INSTANCE.metallum_release_object(this.nativeHandle);
				this.nativeHandle = null;
				throw new IllegalStateException("MTLBuffer.contents returned null");
			}

			this.storage = MetalNativeBridge.INSTANCE.nativeByteBufferView(contents, this.allocationSize).order(ByteOrder.nativeOrder());
		} else {
			this.storage = null;
		}
	}

	@Nullable
	public String label() {
		return this.label;
	}

	ByteBuffer sliceStorage(final long offset, final long length) {
		if (this.storage == null) {
			throw new IllegalStateException("Buffer is not CPU-accessible");
		}

		ByteBuffer duplicate = this.storage.duplicate().order(this.storage.order());
		duplicate.position(Math.toIntExact(offset));
		duplicate.limit(Math.toIntExact(offset + length));
		return duplicate.slice().order(this.storage.order());
	}

	ByteBuffer fullStorageView() {
		if (this.storage == null) {
			throw new IllegalStateException("Buffer is not CPU-accessible");
		}
		return this.storage.duplicate().order(this.storage.order());
	}

	boolean hasCpuStorage() {
		return this.storage != null;
	}

	Pointer nativeHandle() {
		if (this.nativeHandle == null) {
			throw new IllegalStateException("Native Metal buffer is closed");
		}
		return this.nativeHandle;
	}

	@Override
	public boolean isClosed() {
		return this.closed || this.nativeHandle == null;
	}

	@Override
	public void close() {
		if (this.closed) {
			return;
		}
		this.closed = true;
		this.storage = null;
		if (this.nativeHandle != null) {
			Pointer handle = this.nativeHandle;
			this.nativeHandle = null;
			this.device.queueBufferRecycle(handle, this.allocationSize, this.resourceOptions);
		}
	}

	private static boolean isCpuAccessible(@GpuBuffer.Usage final int usage) {
		return (usage & GpuBuffer.USAGE_MAP_READ) != 0
			|| (usage & GpuBuffer.USAGE_MAP_WRITE) != 0
			|| (usage & GpuBuffer.USAGE_HINT_CLIENT_STORAGE) != 0;
	}

	private static long toMtlResourceOptions(@GpuBuffer.Usage final int usage) {
		return isCpuAccessible(usage) ? 0L : 32L;
	}
}
