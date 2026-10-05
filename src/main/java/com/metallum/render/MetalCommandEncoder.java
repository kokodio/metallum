package com.metallum.render;

import com.metallum.mtl.*;
import com.metallum.objc.ObjC;
import com.metallum.objc.ObjCBlock;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.systems.*;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Environment(EnvType.CLIENT)
final class MetalCommandEncoder implements CommandEncoderBackend {
    public static final int MAX_SUBMITS_IN_FLIGHT = 3;
    private final MetalDevice device;
    private long currentSubmitIndex = MAX_SUBMITS_IN_FLIGHT;
    private final InFlight[] inFlight = new InFlight[MAX_SUBMITS_IN_FLIGHT];
    private final Semaphore[] submitSemaphores = new Semaphore[MAX_SUBMITS_IN_FLIGHT];
    private final MemorySegment[] submitSignalBlocks = new MemorySegment[MAX_SUBMITS_IN_FLIGHT];
    private final MetalDestructionQueue destroyQueue = new MetalDestructionQueue(MAX_SUBMITS_IN_FLIGHT);
    private final MetalTransientMemory transientMemory;
    private final Map<MetalGpuTexture, Vector4fc> pendingColorClears = new IdentityHashMap<>();
    private final Map<MetalGpuTexture, Double> pendingDepthClears = new IdentityHashMap<>();
    private final MTLFence fence;
    // With VSync off, frames hand their image to the present queue through these (see presentTextureToDrawable)
    @Nullable
    private MTLCommandQueue presentQueue;
    @Nullable
    private MTLSharedEvent frameEvent;
    @Nullable
    private MTLSharedEvent presentEvent;
    private long frameEventValue;
    private long presentEventValue;
    private final PresentStaging[] presentStaging = {new PresentStaging(), new PresentStaging(), new PresentStaging()};
    private int nextPresentStaging;
    @Nullable
    private PendingPresent pendingPresent;
    @Nullable
    private MetalRenderPass currentRenderPass;
    @Nullable
    private MTLCommandBuffer commandBuffer;
    @Nullable
    private MTLCommandEncoder currentEncoder;
    private MemorySegment renderColorAttachment = MemorySegment.NULL;
    private MemorySegment renderDepthAttachment = MemorySegment.NULL;
    private final Long2ObjectOpenHashMap<ArrayDeque<MTLBuffer>> dynamicBackingPool = new Long2ObjectOpenHashMap<>();

    MetalCommandEncoder(final MetalDevice device) {
        this.device = device;
        this.transientMemory = new MetalTransientMemory(device, this);
        fence = device.metalDevice().newFence();
        for (int slot = 0; slot < MAX_SUBMITS_IN_FLIGHT; slot++) {
            Semaphore semaphore = new Semaphore(0);
            submitSemaphores[slot] = semaphore;
            submitSignalBlocks[slot] = ObjCBlock.withRunnable(semaphore::release);
        }
    }

    MTLCommandBuffer commandBuffer() {
        if (commandBuffer != null) {
            return commandBuffer;
        }
        return commandBuffer = device.commandQueue.makeCommandBuffer(
                device.useLabels() ? "Metallum frame " + currentSubmitIndex : null
        );
    }

    MTLBlitCommandEncoder blitCommandEncoder() {
        endEncoder();
        MTLBlitCommandEncoder encoder = commandBuffer().makeBlitCommandEncoder();
        encoder.waitForFence(fence);
        currentEncoder = encoder;
        return encoder;
    }

    void endEncoder() {
        if (currentEncoder != null) {
            if (currentEncoder instanceof MTLRenderCommandEncoder renderEncoder) {
                renderEncoder.updateFence(fence, MTLRenderStages.VertexAndFragment);
                if (currentRenderPass != null) {
                    currentRenderPass.invalidateEncoderState();
                }
            } else if (currentEncoder instanceof MTLBlitCommandEncoder blitEncoder) {
                blitEncoder.updateFence(fence);
            }
            currentEncoder.endEncoding();
            currentEncoder = null;
        }
        renderColorAttachment = MemorySegment.NULL;
        renderDepthAttachment = MemorySegment.NULL;
    }

    @Override
    public @NonNull TransientMemory transientMemory() {
        return transientMemory;
    }

    @Override
    public void submit() {
        InFlight toClose = null;
        if (commandBuffer != null) {
            submitRenderPass();
            endEncoder();

            int slot = (int) (currentSubmitIndex % MAX_SUBMITS_IN_FLIGHT);
            submitSemaphores[slot].drainPermits();
            commandBuffer.commitWithCompletionBlock(submitSignalBlocks[slot]);
            if (pendingPresent != null) {
                submitPendingPresent();
            }

            toClose = inFlight[slot];
            inFlight[slot] = new InFlight(currentSubmitIndex, commandBuffer);
            commandBuffer = null;
        }
        currentSubmitIndex++;

        if (!awaitSubmitCompletion(currentSubmitIndex - MAX_SUBMITS_IN_FLIGHT, 5000L)) {
            throw new IllegalStateException("5s timeout reached when waiting for Metal submit completion");
        }

        if (toClose != null) {
            toClose.buffer.close();
        }

        transientMemory.rotate();
        destroyQueue.rotate();
    }

    MTLRenderCommandEncoder renderCommandEncoder(
            final MetalGpuTextureView colorTextureView,
            @Nullable final MetalGpuTextureView depthTextureView,
            final int viewportWidth,
            final int viewportHeight,
            @Nullable final Vector4fc clearColor,
            @Nullable final Double clearDepth
    ) {
        MemorySegment colorAttachment = colorTextureView.nativeHandle();
        MemorySegment depthAttachment = depthTextureView == null ? MemorySegment.NULL : depthTextureView.nativeHandle();
        if (currentEncoder instanceof MTLRenderCommandEncoder enc
                && MetalPipelineSupport.sameHandle(renderColorAttachment, colorAttachment)
                && MetalPipelineSupport.sameHandle(renderDepthAttachment, depthAttachment)) {
            if (clearColor != null || clearDepth != null) {
                enc.clearDraw(
                        colorAttachment,
                        depthAttachment,
                        viewportWidth,
                        viewportHeight,
                        clearColor,
                        clearDepth
                );
            }
            return enc;
        }

        endEncoder();
        MTLRenderCommandEncoder encoder = commandBuffer().makeRenderCommandEncoder(
                colorAttachment,
                clearColor,
                depthAttachment,
                clearDepth,
                viewportWidth,
                viewportHeight
        );
        encoder.waitForFence(fence, MTLRenderStages.VertexAndFragment);
        currentEncoder = encoder;
        renderColorAttachment = colorAttachment;
        renderDepthAttachment = depthAttachment;
        return encoder;
    }

    @Override
    public @NonNull RenderPassBackend createRenderPass(final RenderPassDescriptor descriptor) {
        RenderPassDescriptor.Attachment<Optional<Vector4fc>> colorAttachment = descriptor.colorAttachments().getFirst();
        GpuTextureView colorTexture = colorAttachment.textureView();
        MetalGpuTexture colorTex = (MetalGpuTexture) colorTexture.texture();
        Vector4fc colorClear = colorAttachment.clearValue().orElse(null);
        Vector4fc pendingColor = pendingColorClears.get(colorTex);
        if (pendingColor != null && colorClear == null) {
            if (isFullTextureView(colorTexture)) {
                pendingColorClears.remove(colorTex);
                colorClear = pendingColor;
            } else {
                flushPendingClear(colorTex);
            }
        } else {
            pendingColorClears.remove(colorTex);
        }
        colorTex.markContentsDirty();

        RenderPassDescriptor.Attachment<OptionalDouble> depthAttachment = descriptor.depthAttachment();
        GpuTextureView depthTexture = null;
        Double depthClear = null;
        if (depthAttachment != null) {
            depthTexture = depthAttachment.textureView();
            OptionalDouble attachmentClear = depthAttachment.clearValue();
            depthClear = attachmentClear.isPresent() ? attachmentClear.getAsDouble() : null;

            MetalGpuTexture metalDepth = (MetalGpuTexture) depthTexture.texture();
            Double pendingDepth = pendingDepthClears.get(metalDepth);
            if (pendingDepth != null && depthClear == null) {
                if (isFullTextureView(depthTexture)) {
                    pendingDepthClears.remove(metalDepth);
                    depthClear = pendingDepth;
                } else {
                    flushPendingClear(metalDepth);
                }
            } else {
                pendingDepthClears.remove(metalDepth);
            }
            metalDepth.markContentsDirty();
        }

        assert descriptor.renderArea != null;
        RenderPass.RenderArea renderArea = descriptor.renderArea;
        MetalRenderPass renderPass = new MetalRenderPass(
                device,
                this,
                descriptor.label(),
                colorTexture,
                depthTexture,
                renderArea,
                colorClear,
                depthClear
        );
        currentRenderPass = renderPass;
        renderPass.pushDebugGroup(descriptor.label());
        return renderPass;
    }

    @Override
    public void submitRenderPass() {
        if (currentRenderPass != null) {
            currentRenderPass.materializePendingClear();
            currentRenderPass.popDebugGroup();
            currentRenderPass = null;
        }
    }

    void presentTextureToDrawable(final CAMetalLayer layer, final GpuTextureView textureView) {
        MetalGpuTexture source = (MetalGpuTexture) textureView.texture();
        flushPendingClear(source);
        submitRenderPass();
        endEncoder();
        if (!layer.isMailbox()) {
            commandBuffer().encodePresentTextureToDrawable(layer, source.nativeHandle(), fence);
            return;
        }
        // A command buffer that draws into a drawable can't complete until the display gives one back, and the game waits
        // on recent frames (MappableRingBuffer, submits in flight), so with VSync off the frame only copies its image out
        // and a command buffer on a second queue presents it once the frame is done on the GPU.
        if (presentQueue == null) {
            presentQueue = device.metalDevice().newCommandQueue();
            frameEvent = device.metalDevice().newSharedEvent();
            presentEvent = device.metalDevice().newSharedEvent();
        }
        PresentStaging staging = presentStaging[nextPresentStaging];
        nextPresentStaging = (nextPresentStaging + 1) % presentStaging.length;
        long width = MTLTexture.width(source.nativeHandle());
        long height = MTLTexture.height(source.nativeHandle());
        if (staging.texture == null || staging.width != width || staging.height != height || staging.format != source.mtlPixelFormat()) {
            staging.replace(device, source.mtlPixelFormat(), width, height);
        } else if (staging.readDoneValue > presentEvent.signaledValue()) {
            commandBuffer().encodeWaitForEvent(presentEvent, staging.readDoneValue);
        }
        MTLBlitCommandEncoder blit = blitCommandEncoder();
        blit.copyFromTextureToTexture(source.nativeHandle(), 0, 0, 0, 0, width, height, staging.texture, 0, 0, 0, 0);
        endEncoder();
        commandBuffer().encodeSignalEvent(frameEvent, ++frameEventValue);
        pendingPresent = new PendingPresent(layer, staging, frameEventValue);
    }

    private void submitPendingPresent() {
        PendingPresent present = pendingPresent;
        pendingPresent = null;
        MTLCommandBuffer presentBuffer = presentQueue.makeCommandBuffer(device.useLabels() ? "Metallum present" : null);
        presentBuffer.encodeWaitForEvent(frameEvent, present.frameEventValue());
        presentBuffer.encodePresentTextureToDrawable(present.layer(), present.staging().texture, null);
        present.staging().readDoneValue = ++presentEventValue;
        presentBuffer.encodeSignalEvent(presentEvent, presentEventValue);
        presentBuffer.commit();
        presentBuffer.close();
    }

    @Override
    public void clearColorTexture(final @NonNull GpuTexture colorTexture, final @NonNull Vector4fc clearColor) {
        pendingColorClears.put((MetalGpuTexture) colorTexture, new Vector4f(clearColor));
    }

    @Override
    public void clearColorAndDepthTextures(final @NonNull GpuTexture colorTexture, final @NonNull Vector4fc clearColor, final @NonNull GpuTexture depthTexture, final double clearDepth) {
        MetalGpuTexture color = (MetalGpuTexture) colorTexture;
        MetalGpuTexture depth = (MetalGpuTexture) depthTexture;
        pendingColorClears.put(color, new Vector4f(clearColor));
        pendingDepthClears.put(depth, clearDepth);
    }

    @Override
    public void clearColorAndDepthTextures(
            final @NonNull GpuTexture colorTexture,
            final @NonNull Vector4fc clearColor,
            final @NonNull GpuTexture depthTexture,
            final double clearDepth,
            final int regionX,
            final int regionY,
            final int regionWidth,
            final int regionHeight
    ) {
        MetalGpuTexture color = (MetalGpuTexture) colorTexture;
        MetalGpuTexture depth = (MetalGpuTexture) depthTexture;
        Vector4fc clearColorCopy = new Vector4f(clearColor);
        if (isFullTextureRegion(color, depth, regionX, regionY, regionWidth, regionHeight)) {
            pendingColorClears.put(color, clearColorCopy);
            pendingDepthClears.put(depth, clearDepth);
            return;
        }
        color.markContentsDirty();
        depth.markContentsDirty();
        submitRenderPass();
        endEncoder();
        commandBuffer().clearColorDepthTexturesRegion(
                color.nativeHandle(),
                clearColorCopy,
                depth.nativeHandle(),
                clearDepth,
                regionX,
                regionY,
                regionWidth,
                regionHeight,
                fence
        );
    }

    @Override
    public void clearDepthTexture(final @NonNull GpuTexture depthTexture, final double clearDepth) {
        pendingDepthClears.put((MetalGpuTexture) depthTexture, clearDepth);
    }

    @Override
    public void writeToBuffer(final GpuBufferSlice destination, final ByteBuffer data) {
        MetalGpuBuffer buffer = (MetalGpuBuffer) destination.buffer();
        int length = data.remaining();

        if (buffer.isDynamic()) {
            orphanWrite(buffer, destination.offset(), data);
            return;
        }

        GpuBufferSlice staging = transientMemory.uploadStaging(data, 4L, GpuBuffer.USAGE_COPY_SRC);
        MetalGpuBuffer stagingBuffer = (MetalGpuBuffer) staging.buffer();

        MTLBlitCommandEncoder blit = blitCommandEncoder();
        blit.copyFromBufferToBuffer(
                stagingBuffer.metalBuffer(),
                staging.offset(),
                buffer.metalBuffer(),
                destination.offset(),
                length
        );
        endEncoder();
    }

    private void orphanWrite(final MetalGpuBuffer buffer, final long offset, final ByteBuffer data) {
        long size = buffer.allocationSize();
        MTLBuffer old = buffer.metalBuffer();
        MTLBuffer fresh = acquireDynamicBacking(size, buffer.resourceOptions());
        ByteBuffer freshStorage = ObjC.byteBufferView(fresh.contents(), size).order(ByteOrder.nativeOrder());

        if (offset != 0 || data.remaining() != buffer.size()) {
            ByteBuffer previous = buffer.currentStorage();
            previous.clear();
            freshStorage.duplicate().put(previous);
        }

        ByteBuffer dst = freshStorage.duplicate().order(ByteOrder.nativeOrder());
        dst.position(Math.toIntExact(offset));
        dst.put(data.duplicate());

        buffer.swapBacking(fresh, freshStorage);
        recycleDynamicBacking(old, size);
    }

    private MTLBuffer acquireDynamicBacking(final long size, final long resourceOptions) {
        ArrayDeque<MTLBuffer> bucket = dynamicBackingPool.get(size);
        if (bucket != null && !bucket.isEmpty()) {
            return bucket.pop();
        }
        return device.metalDevice().newBuffer(size, resourceOptions);
    }

    private void recycleDynamicBacking(final MTLBuffer buffer, final long size) {
        queueForDestroy(() -> dynamicBackingPool.computeIfAbsent(size, _ -> new ArrayDeque<>()).push(buffer));
    }

    @Override
    public void copyToBuffer(final GpuBufferSlice source, final GpuBufferSlice target) {
        MetalGpuBuffer sourceBuffer = (MetalGpuBuffer) source.buffer();
        MetalGpuBuffer targetBuffer = (MetalGpuBuffer) target.buffer();
        MTLBlitCommandEncoder blit = blitCommandEncoder();
        blit.copyFromBufferToBuffer(
                sourceBuffer.metalBuffer(),
                source.offset(),
                targetBuffer.metalBuffer(),
                target.offset(),
                source.length()
        );
        endEncoder();
    }

    @Override
    public void writeToTexture(
            final @NonNull GpuTexture destination,
            final @NonNull ByteBuffer source,
            final int mipLevel,
            final int depthOrLayer,
            final int destX,
            final int destY,
            final int width,
            final int height
    ) {
        MetalGpuTexture metalDst = (MetalGpuTexture) destination;
        flushPendingClearForWrite(metalDst);

        int pixelSize = metalDst.pixelSize();
        int rowBytes = width * pixelSize;
        int bytesPerImage = rowBytes * height;
        GpuBufferSlice slice = transientMemory.uploadStaging(source.duplicate().limit(bytesPerImage), pixelSize, GpuBuffer.USAGE_COPY_SRC);

        MTLBlitCommandEncoder blit = blitCommandEncoder();
        blit.copyFromBufferToTexture(
                ((MetalGpuBuffer) slice.buffer()).metalBuffer(),
                slice.offset(),
                rowBytes,
                bytesPerImage,
                width,
                height,
                metalDst.nativeHandle(),
                depthOrLayer,
                mipLevel,
                destX,
                destY
        );
        endEncoder();
    }

    @Override
    public void copyBufferToTexture(
            final @NonNull GpuBufferSlice source,
            final int sourceX,
            final int sourceY,
            final int sourceWidth,
            final int sourceHeight,
            final @NonNull GpuTexture destination,
            final int destinationX,
            final int destinationY,
            final int copyWidth,
            final int copyHeight,
            final int mipLevel,
            final int arrayLayer
    ) {
        MetalGpuTexture metalDst = (MetalGpuTexture) destination;
        flushPendingClearForWrite(metalDst);

        int texelSize = destination.getFormat().blockSize();
        long skipBytes = (sourceX + (long) sourceY * sourceWidth) * texelSize;
        long rowBytes = (long) sourceWidth * texelSize;

        MTLBlitCommandEncoder blit = blitCommandEncoder();
        blit.copyFromBufferToTexture(
                ((MetalGpuBuffer) source.buffer()).metalBuffer(),
                source.offset() + skipBytes,
                rowBytes,
                rowBytes * sourceHeight,
                copyWidth,
                copyHeight,
                metalDst.nativeHandle(),
                arrayLayer,
                mipLevel,
                destinationX,
                destinationY
        );
        endEncoder();
    }

    @Override
    public void copyTextureToBuffer(final @NonNull GpuTexture source, final @NonNull GpuBuffer destination, final long offset, final @NonNull Runnable callback, final int mipLevel) {
        copyTextureToBuffer(source, destination, offset, callback, mipLevel, 0, 0, source.getWidth(mipLevel), source.getHeight(mipLevel));
    }

    @Override
    public void copyTextureToBuffer(
            final @NonNull GpuTexture source,
            final @NonNull GpuBuffer destination,
            final long offset,
            final @NonNull Runnable callback,
            final int mipLevel,
            final int x,
            final int y,
            final int width,
            final int height
    ) {
        MetalGpuTexture texture = (MetalGpuTexture) source;
        flushPendingClear(texture);
        MetalGpuBuffer buffer = (MetalGpuBuffer) destination;
        int bytesPerPixel = texture.pixelSize();
        int rowBytes = width * bytesPerPixel;
        int bytesPerImage = rowBytes * height;

        MTLBlitCommandEncoder blit = blitCommandEncoder();
        blit.copyFromTextureToBuffer(
                texture.nativeHandle(),
                0,
                mipLevel,
                x,
                y,
                width,
                height,
                buffer.metalBuffer(),
                offset,
                rowBytes,
                bytesPerImage
        );

        endEncoder();
        queueForDestroy(callback);
    }

    @Override
    public void copyTextureToTexture(
            final @NonNull GpuTexture source,
            final @NonNull GpuTexture destination,
            final int mipLevel,
            final int destX,
            final int destY,
            final int sourceX,
            final int sourceY,
            final int width,
            final int height
    ) {
        MetalGpuTexture srcTexture = (MetalGpuTexture) source;
        MetalGpuTexture dstTexture = (MetalGpuTexture) destination;
        flushPendingClear(srcTexture);
        flushPendingClearForWrite(dstTexture);
        MTLBlitCommandEncoder blit = blitCommandEncoder();
        blit.copyFromTextureToTexture(
                srcTexture.nativeHandle(),
                0,
                mipLevel,
                sourceX,
                sourceY,
                width,
                height,
                dstTexture.nativeHandle(),
                0,
                mipLevel,
                destX,
                destY
        );
        endEncoder();
    }

    @Override
    public @NonNull GpuFence createFence() {
        return new MetalFence(this, currentSubmitIndex);
    }

    void queueForDestroy(final Runnable destroyAction) {
        destroyQueue.add(destroyAction);
    }

    boolean awaitSubmitCompletion(final long submitIndex, final long timeoutMs) {
        if (submitIndex == currentSubmitIndex) {
            if (timeoutMs == 0L) {
                return false;
            }
            throw new IllegalStateException("Cannot wait on a fence for the current submit");
        }
        int slot = (int) (submitIndex % MAX_SUBMITS_IN_FLIGHT);
        InFlight f = inFlight[slot];
        if (f != null && f.index == submitIndex) {
            Semaphore semaphore = submitSemaphores[slot];
            try {
                if (!semaphore.tryAcquire(Math.max(timeoutMs, 0L), TimeUnit.MILLISECONDS)) {
                    return false;
                }
                semaphore.release();
                return true;
            } catch (InterruptedException e) {
                throw new IllegalStateException("Render thread interrupted while waiting for Metal submit completion", e);
            }
        }
        return true;
    }

    void close() {
        submitRenderPass();
        endEncoder();
        for (int slot = 0; slot < inFlight.length; slot++) {
            InFlight f = inFlight[slot];
            if (f != null) {
                f.buffer.close();
                inFlight[slot] = null;
            }
        }
        if (commandBuffer != null) {
            commandBuffer.close();
            commandBuffer = null;
        }
        transientMemory.close();
        for (PresentStaging staging : presentStaging) {
            staging.close();
        }
        if (presentQueue != null) {
            presentQueue.close();
            ObjC.release(frameEvent.handle());
            ObjC.release(presentEvent.handle());
        }
        device.queueResourceRelease(fence.handle());
        destroyQueue.close();
        for (ArrayDeque<MTLBuffer> bucket : dynamicBackingPool.values()) {
            for (MTLBuffer buffer : bucket) {
                ObjC.release(buffer.handle());
            }
        }
        dynamicBackingPool.clear();
    }

    void waitForSubmittedGpuWork() {
        if (commandBuffer != null || currentRenderPass != null || currentEncoder != null) {
            submit();
        } else {
            endEncoder();
        }
        long latestSubmit = currentSubmitIndex - 1L;
        if (latestSubmit >= MAX_SUBMITS_IN_FLIGHT) {
            awaitSubmitCompletion(latestSubmit, Long.MAX_VALUE);
        }
    }

    @Override
    public void writeTimestamp(final @NonNull GpuQueryPool pool, final int index) {
        if (pool instanceof MetalGpuQueryPool metalPool && index >= 0 && index < pool.size()) {
            metalPool.setValue(index, device.getTimestampNow());
        }
    }

    private void flushPendingClearForWrite(final MetalGpuTexture texture) {
        flushPendingClear(texture);
        texture.markContentsDirty();
    }

    void flushPendingClear(final MetalGpuTexture texture) {
        Vector4fc colorClear = pendingColorClears.remove(texture);
        Double depthClear = pendingDepthClears.remove(texture);
        if (colorClear == null && depthClear == null) {
            return;
        }

        if (texture.clearIsRedundant(colorClear, depthClear)) {
            return;
        }

        endEncoder();
        MTLRenderCommandEncoder encoder = commandBuffer().makeRenderCommandEncoder(
                colorClear != null ? texture.nativeHandle() : MemorySegment.NULL,
                colorClear,
                depthClear != null ? texture.nativeHandle() : MemorySegment.NULL,
                depthClear,
                1.0, 1.0
        );
        encoder.waitForFence(fence, MTLRenderStages.VertexAndFragment);
        currentEncoder = encoder;
        texture.recordMaterializedClear(colorClear, depthClear);
    }

    private static boolean isFullTextureView(final GpuTextureView textureView) {
        return textureView.baseMipLevel() == 0
                && textureView.mipLevels() >= textureView.texture().getMipLevels()
                && textureView.texture().getDepthOrLayers() == 1;
    }

    private static boolean isFullTextureRegion(
            final MetalGpuTexture color,
            final MetalGpuTexture depth,
            final int x,
            final int y,
            final int width,
            final int height
    ) {
        return x == 0
                && y == 0
                && width == color.getWidth(0)
                && height == color.getHeight(0)
                && width == depth.getWidth(0)
                && height == depth.getHeight(0);
    }

    private record InFlight(long index, MTLCommandBuffer buffer) {
    }

    private static final class PresentStaging {
        @Nullable
        MemorySegment texture;
        long width;
        long height;
        @Nullable
        MTLPixelFormat format;
        long readDoneValue;

        void replace(final MetalDevice device, final MTLPixelFormat format, final long width, final long height) {
            if (texture != null) {
                ObjC.release(texture); // a present still reading it has it retained
            }
            try (MTLTextureDescriptor descriptor = MTLTextureDescriptor.create()) {
                descriptor.pixelFormat(format);
                descriptor.width(width);
                descriptor.height(height);
                descriptor.usage(MTLTextureUsage.ShaderRead.value);
                descriptor.storageMode(MTLStorageMode.Private);
                descriptor.hazardTrackingMode(MTLHazardTrackingMode.Untracked);
                texture = device.metalDevice().newTexture(descriptor);
            }
            this.width = width;
            this.height = height;
            this.format = format;
            this.readDoneValue = 0L;
        }

        void close() {
            if (texture != null) {
                ObjC.release(texture);
                texture = null;
            }
        }
    }

    private record PendingPresent(CAMetalLayer layer, PresentStaging staging, long frameEventValue) {
    }
}
