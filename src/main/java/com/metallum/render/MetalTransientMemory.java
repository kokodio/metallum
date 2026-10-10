package com.metallum.render;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBuffer.Usage;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice.MappedView;
import com.mojang.renderpearl.api.buffers.TransientMemory;
import com.mojang.renderpearl.backend.util.TransientBlockAllocator;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntComparator;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.util.Mth;
import org.jspecify.annotations.NonNull;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.stream.IntStream;

@Environment(EnvType.CLIENT)
final class MetalTransientMemory implements TransientMemory {
    private static final long BLOCK_SIZE = 524288L;
    private static final long MAX_CPU_ALIGNMENT = 16L;
    private static final long MAX_GPU_ALIGNMENT = Long.highestOneBit(Long.MAX_VALUE);
    private static final int BLOCK_USAGE = GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_MAP_WRITE;

    private final MetalDevice device;
    private final MetalCommandEncoder encoder;
    private final TransientBlockAllocator<TransientBlockAllocator.Allocator.CpuBlock> cpuBlockAllocator = new TransientBlockAllocator<>(
            BLOCK_SIZE, MAX_CPU_ALIGNMENT, TransientBlockAllocator.Allocator.CpuBlock.memalloc()
    );
    private final TransientBlockAllocator<GpuBlock> gpuBlockAllocator;
    private long submitIndex = 0L;

    MetalTransientMemory(final MetalDevice device, final MetalCommandEncoder encoder) {
        this.device = device;
        this.encoder = encoder;
        this.gpuBlockAllocator = new TransientBlockAllocator<GpuBlock>(
                BLOCK_SIZE, MAX_GPU_ALIGNMENT, TransientBlockAllocator.Allocator.create(this::allocateGpuBlock, this::freeGpuBlock)
        );
    }

    void rotate() {
        cpuBlockAllocator.rotate().run();
        encoder.queueForDestroy(gpuBlockAllocator.rotate());
        submitIndex++;
    }

    void close() {
        cpuBlockAllocator.close();
        gpuBlockAllocator.close();
    }

    private GpuBlock allocateGpuBlock(final long size) {
        return new GpuBlock(new MetalGpuBuffer(device, BLOCK_USAGE, size, device.useLabels() ? "Metal Transient Memory Buffer" : null));
    }

    private void freeGpuBlock(final GpuBlock block) {
        block.buffer().close();
    }

    private record GpuBlock(MetalGpuBuffer buffer) implements TransientBlockAllocator.Allocator.Block {
        @Override
        public boolean suboptimal() {
            return false;
        }
    }

    @Override
    public @NonNull ByteBuffer allocateCpu(final long size, final long alignment, final long minimumAllocation, final long elementSize) {
        TransientBlockAllocator.Allocation<TransientBlockAllocator.Allocator.CpuBlock> alloc = cpuBlockAllocator.allocate(size, alignment, minimumAllocation, elementSize);
        return MemoryUtil.memByteBuffer(alloc.block().address() + alloc.offset(), (int) alloc.size());
    }

    @Override
    public @NonNull MappedView allocateStaging(final long size, final long alignment, @Usage final int usage, final long minimumAllocation, final long elementSize) {
        return allocateMapped(size, alignment, usage, minimumAllocation, elementSize);
    }

    @Override
    public @NonNull GpuBufferSlice allocateGpu(final long size, final long alignment, @Usage final int usage, final long minimumAllocation, final long elementSize) {
        TransientBlockAllocator.Allocation<GpuBlock> alloc = gpuBlockAllocator.allocate(size, alignment, minimumAllocation, elementSize);
        return new GpuBufferSlice(wrap(alloc.block().buffer(), usage), alloc.offset(), alloc.size());
    }

    @Override
    public @NonNull MappedView allocateGpuMapped(final long size, final long alignment, @Usage final int usage, final long minimumAllocation, final long elementSize) {
        return allocateMapped(size, alignment, usage, minimumAllocation, elementSize);
    }

    private MappedView allocateMapped(final long size, final long alignment, @Usage final int usage, final long minimumAllocation, final long elementSize) {
        TransientBlockAllocator.Allocation<GpuBlock> alloc = gpuBlockAllocator.allocate(size, alignment, minimumAllocation, elementSize);
        GpuBufferSlice slice = new GpuBufferSlice(wrap(alloc.block().buffer(), usage), alloc.offset(), alloc.size());
        ByteBuffer hostView = alloc.block().buffer().sliceStorage(alloc.offset(), alloc.size());
        return new MappedView(slice, hostView, () -> {
        });
    }

    private MetalGpuBuffer wrap(final MetalGpuBuffer block, @Usage final int usage) {
        return new TransientGpuBuffer(block, usage, this, submitIndex);
    }

    @Override
    public @NonNull GpuBufferSlice uploadStaging(final @NonNull List<ByteBuffer> data, final long alignment, @Usage final int usage, final long minimumAllocation, final long elementSize) {
        return upload(data, alignment, usage, minimumAllocation, elementSize);
    }

    @Override
    public @NonNull GpuBufferSlice uploadGpu(final @NonNull List<ByteBuffer> data, final long alignment, @Usage final int usage, final long minimumAllocation, final long elementSize) {
        return upload(data, alignment, usage, minimumAllocation, elementSize);
    }

    private GpuBufferSlice upload(final List<ByteBuffer> data, final long alignment, @Usage final int usage, final long minimumAllocation, final long elementSize) {
        long totalSize = 0L;
        for (ByteBuffer buffer : data) {
            totalSize += buffer.remaining();
            totalSize = Mth.roundToward(totalSize, alignment);
        }

        GpuBufferSlice result;
        try (MappedView mapped = allocateMapped(totalSize, alignment, usage, minimumAllocation, elementSize)) {
            long mappedPtr = MemoryUtil.memAddress(mapped.data());
            long offset = 0L;
            for (ByteBuffer buffer : data) {
                MemoryUtil.memCopy(MemoryUtil.memAddress(buffer), mappedPtr + offset, Math.min(mapped.slice().length() - offset, buffer.remaining()));
                offset += buffer.remaining();
                offset = Mth.roundToward(offset, alignment);
                if (offset >= mapped.slice().length()) {
                    break;
                }
            }
            result = mapped.slice();
        }
        return result;
    }

    @Override
    public @NonNull List<GpuBufferSlice> multiUploadStaging(final @NonNull List<ByteBuffer> data, final long alignment, @Usage final int usage) {
        return multiUpload(data, alignment, usage);
    }

    @Override
    public @NonNull List<GpuBufferSlice> multiUploadGpu(final @NonNull List<ByteBuffer> data, final long alignment, @Usage final int usage) {
        return multiUpload(data, alignment, usage);
    }

    private List<GpuBufferSlice> multiUpload(final List<ByteBuffer> data, final long alignment, @Usage final int usage) {
        ReferenceArrayList<GpuBufferSlice> uploaded = new ReferenceArrayList<>();
        uploaded.size(data.size());
        IntArrayList sortedIndices = IntArrayList.toList(IntStream.range(0, data.size()));
        sortedIndices.sort(IntComparator.comparingInt(index -> data.get(index).remaining()));

        while (!sortedIndices.isEmpty()) {
            boolean allocatedAnything = false;

            for (int i = sortedIndices.size() - 1; i >= 0; i--) {
                int bufferIndex = sortedIndices.getInt(i);
                ByteBuffer currentBuffer = data.get(bufferIndex);
                if (gpuBlockAllocator.canAllocateInCurrentBlock(currentBuffer.remaining(), alignment)) {
                    sortedIndices.removeInt(i);
                    try (MappedView view = allocateGpuMapped(currentBuffer.remaining(), alignment, usage)) {
                        MemoryUtil.memCopy(currentBuffer, view.data());
                        uploaded.set(bufferIndex, view.slice());
                    }
                    allocatedAnything = true;
                    break;
                }
            }

            if (!allocatedAnything) {
                int bufferIndex = sortedIndices.popInt();
                ByteBuffer currentBuffer = data.get(bufferIndex);
                try (MappedView view = allocateGpuMapped(currentBuffer.remaining(), alignment, usage)) {
                    MemoryUtil.memCopy(currentBuffer, view.data());
                    uploaded.set(bufferIndex, view.slice());
                }
            }
        }

        return uploaded;
    }

    private static final class TransientGpuBuffer extends MetalGpuBuffer {
        private final MetalTransientMemory owner;
        private final long bufferSubmitIndex;
        private boolean closed;

        TransientGpuBuffer(final MetalGpuBuffer block, @Usage final int usage, final MetalTransientMemory owner, final long submitIndex) {
            super(usage, block);
            this.owner = owner;
            this.bufferSubmitIndex = submitIndex;
        }

        @Override
        public boolean isClosed() {
            if (closed) {
                return true;
            }
            closed = bufferSubmitIndex < owner.submitIndex;
            return closed;
        }

        @Override
        public void close() {
            closed = true;
        }

        @Override
        public GpuBufferSlice.@NonNull MappedView map(final long offset, final long length, final boolean read, final boolean write) {
            throw new IllegalStateException("Cannot map transient buffer");
        }

        @Override
        public @NonNull GpuBufferSlice slice(final long offset, final long length) {
            throw new IllegalStateException("Cannot slice transient buffer");
        }

        @Override
        public @NonNull GpuBufferSlice slice() {
            throw new IllegalStateException("Cannot slice transient buffer");
        }
    }
}
