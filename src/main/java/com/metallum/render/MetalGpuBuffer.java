package com.metallum.render;

import com.metallum.mtl.MTLBuffer;
import com.metallum.mtl.MTLHazardTrackingMode;
import com.metallum.mtl.MTLResourceOptions;
import com.metallum.mtl.MTLStorageMode;
import com.metallum.objc.ObjC;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

@Environment(EnvType.CLIENT)
class MetalGpuBuffer extends GpuBuffer {
    private final MetalDevice device;
    private final boolean cpuAccessible;
    private final boolean dynamic;
    private final MTLStorageMode storageMode;
    private final long resourceOptions;
    private final long allocationSize;
    @Nullable
    private final String label;
    @Nullable
    private MTLBuffer nativeBuffer;
    @Nullable
    private ByteBuffer storage;
    private boolean closed;

    MetalGpuBuffer(final MetalDevice device, @GpuBuffer.Usage final int usage, final long size, @Nullable final String label) {
        super(usage, size);
        this.device = device;
        this.label = label;

        this.dynamic = isDynamic(usage);
        this.cpuAccessible = isCpuAccessible(usage) || this.dynamic;
        this.storageMode = this.cpuAccessible ? MTLStorageMode.Shared : MTLStorageMode.Private;
        this.resourceOptions = MTLResourceOptions.of(this.storageMode, MTLHazardTrackingMode.Untracked);
        this.allocationSize = (size + 15L) & ~15L;
        this.nativeBuffer = device.metalDevice().newBuffer(this.allocationSize, this.resourceOptions);
        if (label != null) {
            this.nativeBuffer.setLabel(label);
        }

        if (this.cpuAccessible) {
            MemorySegment contents = this.nativeBuffer.contents();
            if (ObjC.isNil(contents)) {
                this.nativeBuffer.release();
                this.nativeBuffer = null;
                throw new IllegalStateException("MTLBuffer.contents returned null");
            }

            this.storage = ObjC.byteBufferView(contents, this.allocationSize).order(ByteOrder.nativeOrder());
        } else {
            this.storage = null;
        }
    }

    MetalGpuBuffer(@GpuBuffer.Usage final int usage, final MetalGpuBuffer block) {
        super(usage, block.size());
        this.device = block.device;
        this.label = block.label;
        this.dynamic = false;
        this.cpuAccessible = false;
        this.storageMode = block.storageMode;
        this.resourceOptions = block.resourceOptions;
        this.allocationSize = block.allocationSize;
        this.nativeBuffer = block.nativeBuffer;
        this.storage = null;
    }

    ByteBuffer sliceStorage(final long offset, final long length) {
        if (this.storage == null) {
            throw new IllegalStateException("Buffer is not CPU-accessible");
        }

        return storage.duplicate()
                .position(Math.toIntExact(offset))
                .limit(Math.toIntExact(offset + length))
                .slice()
                .order(this.storage.order());
    }

    MTLBuffer metalBuffer() {
        if (this.nativeBuffer == null) {
            throw new IllegalStateException("Native Metal buffer is closed");
        }
        return this.nativeBuffer;
    }

    boolean isDynamic() {
        return this.dynamic;
    }

    boolean isCpuAccessible() {
        return this.cpuAccessible;
    }

    void writeDirect(final long offset, final ByteBuffer data) {
        this.sliceStorage(offset, data.remaining()).put(data.duplicate());
    }

    long allocationSize() {
        return this.allocationSize;
    }

    long resourceOptions() {
        return this.resourceOptions;
    }

    MTLStorageMode storageMode() {
        return this.storageMode;
    }

    ByteBuffer currentStorage() {
        if (this.storage == null) {
            throw new IllegalStateException("Buffer is not CPU-accessible");
        }
        return this.storage.duplicate().order(this.storage.order());
    }

    void swapBacking(final MTLBuffer buffer, final ByteBuffer storage) {
        if (this.label != null) {
            buffer.setLabel(this.label);
        }
        this.nativeBuffer = buffer;
        this.storage = storage;
    }

    @Override
    public boolean isClosed() {
        return this.closed || this.nativeBuffer == null;
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.storage = null;
        if (this.nativeBuffer != null) {
            MTLBuffer released = this.nativeBuffer;
            this.nativeBuffer = null;
            this.device.queueResourceRelease(released);
        }
    }

    @Override
    public GpuBufferSlice.@NonNull MappedView map(final long offset, final long length, final boolean read, final boolean write) {
        if (this.isClosed()) {
            throw new IllegalStateException("Buffer already closed");
        }
        if (!read && !write) {
            throw new IllegalArgumentException("At least read or write must be true");
        }
        if (read && (this.usage() & GpuBuffer.USAGE_MAP_READ) == 0) {
            throw new IllegalStateException("Buffer is not readable");
        }
        if (write && (this.usage() & GpuBuffer.USAGE_MAP_WRITE) == 0) {
            throw new IllegalStateException("Buffer is not writable");
        }
        ByteBuffer mapped = this.sliceStorage(offset, length);
        return new GpuBufferSlice.MappedView(this.slice(offset, length), mapped, () -> {
        });
    }

    public int getUsage() {
        return this.usage();
    }

    private static boolean isCpuAccessible(@GpuBuffer.Usage final int usage) {
        return (usage & GpuBuffer.USAGE_INDEX) != 0
                || (usage & GpuBuffer.USAGE_MAP_READ) != 0
                || (usage & GpuBuffer.USAGE_MAP_WRITE) != 0
                || (usage & GpuBuffer.USAGE_HINT_CLIENT_STORAGE) != 0;
    }

    private static boolean isDynamic(@GpuBuffer.Usage final int usage) {
        return (usage & GpuBuffer.USAGE_UNIFORM) != 0 && (usage & GpuBuffer.USAGE_COPY_DST) != 0;
    }
}
