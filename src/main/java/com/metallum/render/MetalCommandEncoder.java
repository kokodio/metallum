package com.metallum.render;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.api.buffers.TransientMemory;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import io.github.kokodio.metaljvm.metal.*;
import io.github.kokodio.metaljvm.objc.AutoreleasePool;
import io.github.kokodio.metaljvm.objc.ObjC;
import io.github.kokodio.metaljvm.objc.ObjCBlock;
import io.github.kokodio.metaljvm.quartzcore.CAMetalLayer;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

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
    private final long[] submitSignalBlocks = new long[MAX_SUBMITS_IN_FLIGHT];
    private final MetalDestructionQueue destroyQueue = new MetalDestructionQueue(MAX_SUBMITS_IN_FLIGHT);
    private final MetalTransientMemory transientMemory;
    private final Map<MetalGpuTexture, Vector4fc> pendingColorClears = new IdentityHashMap<>();
    private final Map<MetalGpuTexture, Double> pendingDepthClears = new IdentityHashMap<>();
    private final MTLFence fence;
    @Nullable
    private MetalRenderPass currentRenderPass;
    @Nullable
    private MTLCommandBuffer commandBuffer;
    @Nullable
    private MTLCommandEncoder currentEncoder;
    @Nullable
    private MTLTexture[] renderColorAttachments;
    @Nullable
    private MTLTexture renderDepthAttachment;
    private final Long2ObjectOpenHashMap<ArrayDeque<MTLBuffer>> dynamicBackingPool = new Long2ObjectOpenHashMap<>();

    MetalCommandEncoder(final MetalDevice device) {
        this.device = device;
        this.transientMemory = new MetalTransientMemory(device, this);
        fence = MetalUtilities.nonNil(device.metalDevice().newFence(), "newFence");
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
        try (AutoreleasePool _ = AutoreleasePool.push()) {
            commandBuffer = MetalUtilities.nonNil(device.commandQueue.commandBuffer(), "commandBuffer");
            commandBuffer.retain();
        }
        if (device.useLabels()) {
            commandBuffer.setLabel("Metallum frame " + currentSubmitIndex);
        }
        return commandBuffer;
    }

    MTLBlitCommandEncoder blitCommandEncoder() {
        endEncoder();
        MTLCommandBuffer buffer = commandBuffer();
        MTLBlitCommandEncoder encoder;
        try (AutoreleasePool _ = AutoreleasePool.push()) {
            encoder = MetalUtilities.nonNil(buffer.blitCommandEncoder(), "blitCommandEncoder");
            encoder.retain();
        }
        encoder.waitForFence(fence);
        currentEncoder = encoder;
        return encoder;
    }

    void endEncoder() {
        if (currentEncoder != null) {
            if (currentEncoder instanceof MTLRenderCommandEncoder renderEncoder) {
                renderEncoder.updateFence(fence, MTLRenderStages.Vertex | MTLRenderStages.Fragment);
                if (currentRenderPass != null) {
                    currentRenderPass.invalidateEncoderState();
                }
            } else if (currentEncoder instanceof MTLBlitCommandEncoder blitEncoder) {
                blitEncoder.updateFence(fence);
            }
            currentEncoder.endEncoding();
            currentEncoder.release();
            currentEncoder = null;
        }
        renderColorAttachments = null;
        renderDepthAttachment = null;
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
            commandBuffer.addCompletedHandler(submitSignalBlocks[slot]);
            commandBuffer.commit();

            toClose = inFlight[slot];
            inFlight[slot] = new InFlight(currentSubmitIndex, commandBuffer);
            commandBuffer = null;
        }
        currentSubmitIndex++;

        if (!awaitSubmitCompletion(currentSubmitIndex - MAX_SUBMITS_IN_FLIGHT, 5000L)) {
            throw new IllegalStateException("5s timeout reached when waiting for Metal submit completion");
        }

        if (toClose != null) {
            toClose.buffer.release();
        }

        transientMemory.rotate();
        destroyQueue.rotate();
    }

    MTLRenderCommandEncoder renderCommandEncoder(
            final MTLTexture[] colorAttachments,
            final MTLPixelFormat[] colorFormats,
            @Nullable final MTLTexture depthAttachment,
            final MTLPixelFormat depthFormat,
            final int viewportWidth,
            final int viewportHeight,
            @Nullable final Vector4fc[] clearColors,
            @Nullable final Double clearDepth,
            final RenderPass.RenderArea renderArea,
            final boolean fullArea
    ) {
        boolean clear = clearColors != null || clearDepth != null;

        if (currentEncoder instanceof MTLRenderCommandEncoder enc
                && sameAttachments(renderColorAttachments, colorAttachments)
                && MetalUtilities.sameHandle(renderDepthAttachment, depthAttachment)) {
            if (clear) {
                MetalUtilities.clearDraw(enc, colorFormats, depthFormat, viewportWidth, viewportHeight, clearColors, clearDepth, renderArea);
            }
            return enc;
        }

        if (!clear || fullArea) {
            return beginRenderEncoder(colorAttachments, clearColors, depthAttachment, clearDepth, viewportWidth, viewportHeight, 0);
        }

        MTLRenderCommandEncoder enc = beginRenderEncoder(colorAttachments, null, depthAttachment, null, viewportWidth, viewportHeight, 0);
        MetalUtilities.clearDraw(enc, colorFormats, depthFormat, viewportWidth, viewportHeight, clearColors, clearDepth, renderArea);
        return enc;
    }

    private static boolean sameAttachments(@Nullable final MTLTexture[] current, final MTLTexture[] wanted) {
        if (current == null || current.length != wanted.length) {
            return false;
        }
        for (int i = 0; i < wanted.length; i++) {
            if (!MetalUtilities.sameHandle(current[i], wanted[i])) {
                return false;
            }
        }
        return true;
    }

    private MTLRenderCommandEncoder beginRenderEncoder(
            final MTLTexture[] colorAttachments,
            @Nullable final Vector4fc[] clearColors,
            @Nullable final MTLTexture depthAttachment,
            @Nullable final Double clearDepth,
            final int viewportWidth,
            final int viewportHeight,
            final int level
    ) {
        endEncoder();
        MTLRenderCommandEncoder encoder = renderCommandEncoder(
                colorAttachments,
                clearColors,
                depthAttachment,
                clearDepth,
                viewportWidth,
                viewportHeight,
                level
        );
        encoder.waitForFence(fence, MTLRenderStages.Vertex | MTLRenderStages.Fragment);
        currentEncoder = encoder;
        renderColorAttachments = colorAttachments;
        renderDepthAttachment = depthAttachment;
        return encoder;
    }

    private MTLRenderCommandEncoder renderCommandEncoder(
            final MTLTexture[] colorTextures,
            @Nullable final Vector4fc[] clearColors,
            @Nullable final MTLTexture depthTexture,
            @Nullable final Double clearDepth,
            final double viewportWidth,
            final double viewportHeight,
            final int level
    ) {
        MTLRenderCommandEncoder encoder;
        try (AutoreleasePool _ = AutoreleasePool.push()) {
            MTLRenderPassDescriptor renderPass = MTLRenderPassDescriptor.alloc().init();
            boolean hasAttachment = depthTexture != null;
            for (int i = 0; i < colorTextures.length; i++) {
                MTLTexture colorTexture = colorTextures[i];
                if (colorTexture == null) {
                    continue;
                }
                hasAttachment = true;
                Vector4fc clearColor = clearColors == null ? null : clearColors[i];
                MTLRenderPassColorAttachmentDescriptor attachment = renderPass.colorAttachments().objectAtIndexedSubscript(i);
                attachment.setTexture(colorTexture);
                attachment.setLevel(level);
                attachment.setLoadAction(clearColor != null ? MTLLoadAction.Clear : MTLLoadAction.Load);
                attachment.setStoreAction(MTLStoreAction.Store);
                if (clearColor != null) {
                    attachment.setClearColor(new MTLClearColor(clearColor.x(), clearColor.y(), clearColor.z(), clearColor.w()));
                }
            }
            if (!hasAttachment) {
                renderPass.release();
                throw new IllegalStateException("Render pass requires a color or depth attachment");
            }
            if (depthTexture != null) {
                MTLRenderPassDepthAttachmentDescriptor attachment = renderPass.depthAttachment();
                attachment.setTexture(depthTexture);
                attachment.setLevel(level);
                attachment.setLoadAction(clearDepth != null ? MTLLoadAction.Clear : MTLLoadAction.Load);
                attachment.setStoreAction(MTLStoreAction.Store);
                if (clearDepth != null) {
                    attachment.setClearDepth(clearDepth);
                }
            }
            encoder = MetalUtilities.nonNil(commandBuffer().renderCommandEncoder(renderPass), "renderCommandEncoderWithDescriptor:");
            encoder.retain();
            renderPass.release();
        }
        encoder.setViewport(new MTLViewport(0.0, 0.0, viewportWidth, viewportHeight, 0.0, 1.0));
        return encoder;
    }

    @Override
    public @NonNull RenderPassBackend createRenderPass(final RenderPassDescriptor descriptor) {
        List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> colorAttachments = descriptor.colorAttachments();

        RenderPass.RenderArea renderArea = descriptor.renderArea();
        RenderPassDescriptor.Attachment<OptionalDouble> depthAttachment = descriptor.depthAttachment();
        boolean fullArea = renderArea.fillsTexture(colorAttachments.isEmpty() ? depthAttachment.textureView() : colorAttachments.getFirst().textureView());

        GpuTextureView[] colorTextures = new GpuTextureView[colorAttachments.size()];
        Vector4fc[] colorClears = null;
        for (int i = 0; i < colorTextures.length; i++) {
            RenderPassDescriptor.Attachment<Optional<Vector4fc>> colorAttachment = colorAttachments.get(i);
            if (colorAttachment == null) {
                continue;
            }
            GpuTextureView colorTexture = colorAttachment.textureView();
            MetalGpuTexture colorTex = (MetalGpuTexture) colorTexture.texture();
            if (!fullArea || colorTex.getMipLevels() > 1) {
                flushPendingClear(colorTex);
            }
            Vector4fc pendingColor = pendingColorClears.remove(colorTex);
            Vector4fc colorClear = colorAttachment.clearValue().orElse(pendingColor);
            if (colorClear != null) {
                if (colorClears == null) {
                    colorClears = new Vector4fc[colorTextures.length];
                }
                colorClears[i] = colorClear;
            }
            colorTex.markContentsDirty();
            colorTextures[i] = colorTexture;
        }

        GpuTextureView depthTexture = null;
        Double depthClear = null;
        if (depthAttachment != null) {
            depthTexture = depthAttachment.textureView();
            MetalGpuTexture metalDepth = (MetalGpuTexture) depthTexture.texture();
            if (!fullArea || metalDepth.getMipLevels() > 1) {
                flushPendingClear(metalDepth);
            }
            Double pendingDepth = pendingDepthClears.remove(metalDepth);
            OptionalDouble attachmentClear = depthAttachment.clearValue();
            depthClear = attachmentClear.isPresent() ? Double.valueOf(attachmentClear.getAsDouble()) : pendingDepth;
            metalDepth.markContentsDirty();
        }

        MetalRenderPass renderPass = new MetalRenderPass(
                device,
                this,
                descriptor.label(),
                colorTextures,
                depthTexture,
                renderArea,
                colorClears,
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
        MTLCommandBuffer commandBuffer = commandBuffer();
        MetalUtilities.encodePresentTextureToDrawable(commandBuffer, layer, source.metalTexture(), fence);
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
            final int regionHeight,
            final int mipLevel
    ) {
        MetalGpuTexture color = (MetalGpuTexture) colorTexture;
        MetalGpuTexture depth = (MetalGpuTexture) depthTexture;
        if (color.getMipLevels() == 1 && isFullTextureRegion(color, depth, regionX, regionY, regionWidth, regionHeight)) {
            pendingColorClears.put(color, new Vector4f(clearColor));
            pendingDepthClears.put(depth, clearDepth);
            return;
        }

        submitRenderPass();
        flushPendingClear(color);
        flushPendingClear(depth);
        color.markContentsDirty();
        depth.markContentsDirty();

        int width = color.getWidth(mipLevel);
        int height = color.getHeight(mipLevel);
        MTLRenderCommandEncoder encoder = beginRenderEncoder(new MTLTexture[]{color.metalTexture()}, null, depth.metalTexture(), null, width, height, mipLevel);

        RenderPass.RenderArea region = new RenderPass.RenderArea(regionX, regionY, regionWidth, regionHeight);
        MetalUtilities.clearDraw(encoder, new MTLPixelFormat[]{color.mtlPixelFormat()}, depth.mtlPixelFormat(), width, height, new Vector4fc[]{clearColor}, clearDepth, region);
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
        blit.copyFromBuffer(
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
        return MetalUtilities.nonNil(device.metalDevice().newBuffer(size, resourceOptions), "newBufferWithLength:options:");
    }

    private void recycleDynamicBacking(final MTLBuffer buffer, final long size) {
        queueForDestroy(() -> dynamicBackingPool.computeIfAbsent(size, _ -> new ArrayDeque<>()).push(buffer));
    }

    @Override
    public void copyToBuffer(final GpuBufferSlice source, final GpuBufferSlice target) {
        MetalGpuBuffer sourceBuffer = (MetalGpuBuffer) source.buffer();
        MetalGpuBuffer targetBuffer = (MetalGpuBuffer) target.buffer();
        MTLBlitCommandEncoder blit = blitCommandEncoder();
        blit.copyFromBuffer(
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
        blit.copyFromBuffer(
                ((MetalGpuBuffer) slice.buffer()).metalBuffer(),
                slice.offset(),
                rowBytes,
                bytesPerImage,
                new MTLSize(width, height, 1),
                metalDst.metalTexture(),
                depthOrLayer,
                mipLevel,
                new MTLOrigin(destX, destY, 0)
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
        blit.copyFromBuffer(
                ((MetalGpuBuffer) source.buffer()).metalBuffer(),
                source.offset() + skipBytes,
                rowBytes,
                rowBytes * sourceHeight,
                new MTLSize(copyWidth, copyHeight, 1),
                metalDst.metalTexture(),
                arrayLayer,
                mipLevel,
                new MTLOrigin(destinationX, destinationY, 0)
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
        blit.copyFromTexture(
                texture.metalTexture(),
                0,
                mipLevel,
                new MTLOrigin(x, y, 0),
                new MTLSize(width, height, 1),
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
        blit.copyFromTexture(
                srcTexture.metalTexture(),
                0,
                mipLevel,
                new MTLOrigin(sourceX, sourceY, 0),
                new MTLSize(width, height, 1),
                dstTexture.metalTexture(),
                0,
                mipLevel,
                new MTLOrigin(destX, destY, 0)
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
                f.buffer.release();
                inFlight[slot] = null;
            }
        }
        if (commandBuffer != null) {
            commandBuffer.release();
            commandBuffer = null;
        }
        transientMemory.close();
        device.queueResourceRelease(fence);
        destroyQueue.close();
        for (ArrayDeque<MTLBuffer> bucket : dynamicBackingPool.values()) {
            for (MTLBuffer buffer : bucket) {
                buffer.release();
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

        for (int level = 0; level < texture.getMipLevels(); level++) {
            endEncoder();
            MTLRenderCommandEncoder encoder = renderCommandEncoder(
                    colorClear != null ? new MTLTexture[]{texture.metalTexture()} : new MTLTexture[0],
                    colorClear != null ? new Vector4fc[]{colorClear} : null,
                    depthClear != null ? texture.metalTexture() : null,
                    depthClear,
                    1.0, 1.0,
                    level
            );
            encoder.waitForFence(fence, MTLRenderStages.Vertex | MTLRenderStages.Fragment);
            currentEncoder = encoder;
        }
        texture.recordMaterializedClear(colorClear, depthClear);
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
}
