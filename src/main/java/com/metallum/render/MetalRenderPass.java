package com.metallum.render;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.blaze3d.systems.ScissorState;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.util.TextureViewAndSampler;
import io.github.kokodio.metaljvm.metal.*;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.SharedConstants;
import org.joml.Vector4fc;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.vulkan.VkDrawIndexedIndirectCommand;
import org.lwjgl.vulkan.VkDrawIndirectCommand;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.nio.ByteBuffer;
import java.util.function.Supplier;

@Environment(EnvType.CLIENT)
final class MetalRenderPass implements RenderPassBackend {
    static final boolean VALIDATION = SharedConstants.IS_RUNNING_IN_IDE;
    static final int MAX_VERTEX_BUFFERS = RenderPass.MAX_VERTEX_BUFFERS;
    private final MetalDevice device;
    private final MetalCommandEncoder commandEncoder;
    @Nullable
    private final String label;
    private final GpuTextureView targetView;
    private final MTLTexture[] colorAttachments;
    private final MTLPixelFormat[] colorFormats;
    private final int layout;
    @Nullable
    private final GpuTextureView depthTexture;
    private final RenderPass.RenderArea renderArea;
    private final boolean fullArea;
    @Nullable
    private Vector4fc[] clearColors;
    @Nullable
    private Double clearDepth;
    private final ScissorState scissorState = new ScissorState();
    private final GpuBufferSlice[] vertexBuffers = new GpuBufferSlice[MAX_VERTEX_BUFFERS];
    private final ResourceBindings bindings = new ResourceBindings();
    @Nullable
    private MemorySegment pushConstantData;
    private int pushConstantLength;
    @Nullable
    private MetalCompiledRenderPipeline compiledPipeline;
    @Nullable
    private GpuBuffer indexBuffer;
    private MTLIndexType indexType = MTLIndexType.UInt16;
    private int pushedDebugGroups = 0;
    private boolean scissorDirty = true;
    private boolean vertexBuffersDirty = true;
    private boolean pipelineDirty = true;
    private boolean pushConstantsDirty;

    MetalRenderPass(
            final MetalDevice device,
            final MetalCommandEncoder encoder,
            final Supplier<String> label,
            final GpuTextureView[] colorTextures,
            @Nullable final GpuTextureView depthTexture,
            final RenderPass.RenderArea renderArea,
            @Nullable final Vector4fc[] clearColors,
            @Nullable final Double clearDepth
    ) {
        this.device = device;
        this.commandEncoder = encoder;
        this.label = device.useLabels() ? label.get() : null;
        this.targetView = colorTextures.length != 0 ? colorTextures[0] : depthTexture;
        this.colorAttachments = new MTLTexture[colorTextures.length];
        this.colorFormats = new MTLPixelFormat[colorTextures.length];
        int layout = depthTexture == null ? 0 : MetalCompiledRenderPipeline.LAYOUT_DEPTH;
        for (int i = 0; i < colorTextures.length; i++) {
            GpuTextureView view = colorTextures[i];
            this.colorAttachments[i] = view == null ? null : ((MetalGpuTextureView) view).metalTexture();
            this.colorFormats[i] = view == null ? MTLPixelFormat.Invalid : ((MetalGpuTexture) view.texture()).mtlPixelFormat();
            if (view != null) {
                layout |= 1 << i;
            }
        }
        this.layout = layout;
        this.depthTexture = depthTexture;
        this.renderArea = renderArea;
        this.fullArea = renderArea.fillsTexture(this.targetView);
        this.clearColors = clearColors;
        this.clearDepth = clearDepth;
    }

    //todo
    @Override
    public void pushDebugGroup(final @NonNull Supplier<String> label) {
        pushedDebugGroups++;
        if (device.useLabels()) {
            commandEncoder.commandBuffer().pushDebugGroup(label.get());
        }
    }

    //todo
    @Override
    public void popDebugGroup() {
        if (pushedDebugGroups == 0) {
            throw new IllegalStateException("Can't pop more debug groups than was pushed!");
        }
        pushedDebugGroups--;
        if (device.useLabels()) {
            commandEncoder.commandBuffer().popDebugGroup();
        }
    }

    @Override
    public void setPipeline(final @NonNull BackendRenderPipeline pipeline) {
        MetalCompiledRenderPipeline compiled = (MetalCompiledRenderPipeline) pipeline;
        if (this.compiledPipeline != compiled) {
            this.compiledPipeline = compiled;
            bindings.reset();
            vertexBuffersDirty = true;
            pipelineDirty = true;
        }
    }

    @Override
    public void setUniform(final int index, @Nullable final Object value) {
        bindings.set(index, value);
        if (value instanceof TextureViewAndSampler pair) {
            commandEncoder.flushPendingClear((MetalGpuTexture) pair.view().texture());
        }
    }

    @Override
    public void pushConstants(final @NonNull ByteBuffer value) {
        if (pushConstantData == null) {
            pushConstantData = Arena.ofAuto().allocate(128);
        }
        pushConstantLength = value.remaining();
        MemorySegment.copy(MemorySegment.ofBuffer(value), 0L, pushConstantData, 0L, pushConstantLength);
        pushConstantsDirty = true;
    }

    @Override
    public void enableScissor(final int x, final int y, final int width, final int height) {
        if (scissorState.enabled()
                && scissorState.x() == x
                && scissorState.y() == y
                && scissorState.width() == width
                && scissorState.height() == height) {
            return;
        }
        scissorState.enable(x, y, width, height);
        scissorDirty = true;
    }

    @Override
    public void disableScissor() {
        if (!scissorState.enabled()) {
            return;
        }
        scissorState.disable();
        scissorDirty = true;
    }

    @Override
    public void setVertexBuffer(final int slot, @Nullable final GpuBufferSlice vertexBuffer) {
        if (slot < 0 || slot >= MAX_VERTEX_BUFFERS) {
            throw new IllegalArgumentException("Unsupported Metal vertex buffer slot: " + slot);
        }

        if (!sameSlice(vertexBuffers[slot], vertexBuffer)) {
            vertexBuffers[slot] = vertexBuffer;
            vertexBuffersDirty = true;
        }
    }

    @Override
    public void setIndexBuffer(@Nullable final GpuBuffer indexBuffer, final @NonNull IndexType indexType) {
        setIndexBuffer(indexBuffer, MetalConversions.indexType(indexType));
    }

    private void setIndexBuffer(@Nullable final GpuBuffer indexBuffer, final MTLIndexType indexType) {
        if (this.indexBuffer != indexBuffer || this.indexType != indexType) {
            this.indexBuffer = indexBuffer;
            this.indexType = indexType;
        }
    }

    @Override
    public void drawIndexed(final int indexCount, final int instanceCount, final int firstIndex, final int vertexOffset, final int firstInstance) {
        MetalGpuBuffer nativeIndexBuffer = (MetalGpuBuffer) indexBuffer;
        MTLRenderCommandEncoder enc = renderEncoder();

        bindDrawState(enc);
        drawIndexedNative(enc, nativeIndexBuffer, firstIndex, indexCount, vertexOffset, instanceCount, indexType, firstInstance);
    }

    @Override
    public void multiDrawIndexed(@NonNull IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
        MetalGpuBuffer nativeIndexBuffer = (MetalGpuBuffer) indexBuffer;
        MTLRenderCommandEncoder enc = renderEncoder();
        bindDrawState(enc);

        int base = drawParameters.position();
        for (int i = 0; i < drawCount; i++) {
            int firstIndex = drawParameters.get(base + i * 3);
            int indexCount = drawParameters.get(base + i * 3 + 1);
            int baseVertex = drawParameters.get(base + i * 3 + 2);
            if (indexCount > 0) {
                drawIndexedNative(enc, nativeIndexBuffer, firstIndex, indexCount, baseVertex, instanceCount, indexType, firstInstance);
            }
        }
    }

    @Override
    public void multiDrawIndexed(@NonNull PointerBuffer firstIndexOffsets, @NonNull IntBuffer indexCounts, @NonNull IntBuffer vertexOffsets, int drawCount) {
        MTLPrimitiveType primitiveType = primitiveTopology();
        if (isTriangleFan()) {
            throw new UnsupportedOperationException("Metal backend does not support triangle fan multiDrawIndexed");
        }

        MetalGpuBuffer nativeIndexBuffer = (MetalGpuBuffer) indexBuffer;
        MTLRenderCommandEncoder enc = renderEncoder();
        bindDrawState(enc);

        MTLBuffer indexBufferHandle = nativeIndexBuffer.metalBuffer();
        MemorySegment offsets = MemorySegment.ofAddress(org.lwjgl.system.MemoryUtil.memAddress(firstIndexOffsets)).reinterpret(drawCount * 8L);
        MemorySegment counts = MemorySegment.ofAddress(org.lwjgl.system.MemoryUtil.memAddress(indexCounts)).reinterpret(drawCount * 4L);
        MemorySegment vertices = MemorySegment.ofAddress(org.lwjgl.system.MemoryUtil.memAddress(vertexOffsets)).reinterpret(drawCount * 4L);
        for (int i = 0; i < drawCount; i++) {
            int indexCount = counts.get(ValueLayout.JAVA_INT, i * 4L);
            if (indexCount <= 0) {
                continue;
            }
            long firstIndexOffset = offsets.get(ValueLayout.JAVA_LONG, i * 8L);
            int baseVertex = vertices.get(ValueLayout.JAVA_INT, i * 4L);
            enc.drawIndexedPrimitives(primitiveType, indexCount, indexType, indexBufferHandle, firstIndexOffset, 1, baseVertex, 0);
        }
    }

    @Override
    public void drawIndexedIndirect(final @NonNull GpuBufferSlice commands, final int drawCount) {
        MTLPrimitiveType primitiveType = primitiveTopology();
        if (isTriangleFan()) {
            throw new UnsupportedOperationException("Metal backend does not support triangle fan indirect draws");
        }

        MetalGpuBuffer nativeIndexBuffer = (MetalGpuBuffer) indexBuffer;
        MTLRenderCommandEncoder enc = renderEncoder();
        bindDrawState(enc);

        MTLBuffer indexBufferHandle = nativeIndexBuffer.metalBuffer();
        MTLBuffer indirectBuffer = ((MetalGpuBuffer) commands.buffer()).metalBuffer();
        long indirectOffset = commands.offset();
        for (int i = 0; i < drawCount; i++) {
            enc.drawIndexedPrimitives(primitiveType, indexType, indexBufferHandle, 0L, indirectBuffer, indirectOffset);
            indirectOffset += VkDrawIndexedIndirectCommand.SIZEOF;
        }
    }

    @Override
    public void draw(final int vertexCount, final int instanceCount, final int firstVertex, final int firstInstance) {
        MTLRenderCommandEncoder enc = renderEncoder();

        bindDrawState(enc);
        drawNative(enc, firstVertex, vertexCount, instanceCount, firstInstance);
    }

    @Override
    public void multiDraw(@NonNull IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
        MTLRenderCommandEncoder enc = renderEncoder();
        bindDrawState(enc);

        int base = drawParameters.position();
        for (int i = 0; i < drawCount; i++) {
            drawNative(enc, drawParameters.get(base + i * 2), drawParameters.get(base + i * 2 + 1), instanceCount, firstInstance);
        }
    }

    @Override
    public void multiDraw(@NonNull IntBuffer firstVertices, @NonNull IntBuffer vertexCounts, int drawCount) {
        MTLRenderCommandEncoder enc = renderEncoder();
        bindDrawState(enc);

        int firstBase = firstVertices.position();
        int countBase = vertexCounts.position();
        for (int i = 0; i < drawCount; i++) {
            drawNative(enc, firstVertices.get(firstBase + i), vertexCounts.get(countBase + i), 1, 0);
        }
    }

    @Override
    public void drawIndirect(final @NonNull GpuBufferSlice commands, final int drawCount) {
        MTLPrimitiveType primitiveType = primitiveTopology();
        if (isTriangleFan()) {
            throw new UnsupportedOperationException("Metal backend does not support triangle fan indirect draws");
        }

        MTLRenderCommandEncoder enc = renderEncoder();
        bindDrawState(enc);

        MTLBuffer indirectBuffer = ((MetalGpuBuffer) commands.buffer()).metalBuffer();
        long indirectOffset = commands.offset();
        for (int i = 0; i < drawCount; i++) {
            enc.drawPrimitives(primitiveType, indirectBuffer, indirectOffset);
            indirectOffset += VkDrawIndirectCommand.SIZEOF;
        }
    }

    private void drawNative(final MTLRenderCommandEncoder enc, final int firstVertex, final int vertexCount, final int instanceCount, final int firstInstance) {
        if (vertexCount <= 0) {
            return;
        }
        if (isTriangleFan()) {
            drawTriangleFan(enc, firstVertex, vertexCount, instanceCount, firstInstance);
        } else {
            enc.drawPrimitives(primitiveTopology(), firstVertex, vertexCount, instanceCount, firstInstance);
        }
    }

    @Override
    public void writeTimestamp(final @NonNull GpuQueryPool pool, final int index) {
        if (pool instanceof MetalGpuQueryPool metalPool && index >= 0 && index < pool.size()) {
            metalPool.setValue(index, device.getTimestampNow());
        }
    }

    MTLPixelFormat depthAttachmentFormat() {
        if (depthTexture == null) {
            return MTLPixelFormat.Invalid;
        }
        return ((MetalGpuTexture) depthTexture.texture()).mtlPixelFormat();
    }

    void materializePendingClear() {
        if (clearColors != null || clearDepth != null) {
            renderEncoder();
        }
    }

    private MTLRenderCommandEncoder renderEncoder() {
        MTLRenderCommandEncoder encoder = commandEncoder.renderCommandEncoder(
                colorAttachments,
                colorFormats,
                depthTexture == null ? null : ((MetalGpuTextureView) depthTexture).metalTexture(),
                depthAttachmentFormat(),
                targetView.getWidth(0),
                targetView.getHeight(0),
                clearColors,
                clearDepth,
                renderArea,
                fullArea
        );
        clearColors = null;
        clearDepth = null;
        return encoder;
    }

    void invalidateEncoderState() {
        pipelineDirty = true;
        scissorDirty = true;
        vertexBuffersDirty = true;
        pushConstantsDirty = pushConstantData != null;
    }

    private void pushVertexBuffers(final MTLRenderCommandEncoder enc) {
        int firstSlot = compiledPipeline.firstAvailableVertexBufferSlot();
        int count = compiledPipeline.vertexBufferCount();
        for (int slot = 0; slot < count; slot++) {
            GpuBufferSlice vertexBuffer = vertexBuffers[slot];
            if (vertexBuffer == null) {
                continue;
            }
            if (VALIDATION && vertexBuffer.buffer().isClosed()) {
                throw new IllegalStateException("Vertex buffer at slot " + slot + " has been closed");
            }

            MetalGpuBuffer nativeVertexBuffer = (MetalGpuBuffer) vertexBuffer.buffer();
            int metalSlot = firstSlot + slot;
            enc.setVertexBuffer(nativeVertexBuffer.metalBuffer(), vertexBuffer.offset(), metalSlot);
        }
    }

    private void drawTriangleFan(MTLRenderCommandEncoder encoder, final int firstVertex, final int vertexCount, final int instanceCount, final int baseInstance) {
        int triangleCount = vertexCount - 2;
        int indexCount = triangleCount * 3;
        MTLIndexType fanIndexType = vertexCount - 1 <= 0xFFFF ? MTLIndexType.UInt16 : MTLIndexType.UInt32;

        try (GpuBufferSlice.MappedView mapped = commandEncoder.transientMemory().allocateGpuMapped((long) indexCount * MetalConversions.bytes(fanIndexType), MetalConversions.bytes(fanIndexType), GpuBuffer.USAGE_INDEX)) {
            if (fanIndexType == MTLIndexType.UInt16) {
                ShortBuffer indices = mapped.data().asShortBuffer();
                for (int i = 0; i < triangleCount; i++) {
                    indices.put((short) 0);
                    indices.put((short) (i + 1));
                    indices.put((short) (i + 2));
                }
            } else {
                IntBuffer indices = mapped.data().asIntBuffer();
                for (int i = 0; i < triangleCount; i++) {
                    indices.put(0);
                    indices.put(i + 1);
                    indices.put(i + 2);
                }
            }
            GpuBufferSlice slice = mapped.slice();
            encoder.drawIndexedPrimitives(MTLPrimitiveType.Triangle, indexCount, fanIndexType, ((MetalGpuBuffer) slice.buffer()).metalBuffer(), slice.offset(), instanceCount, firstVertex, baseInstance);
        }
    }

    private void drawIndexedNative(
            final MTLRenderCommandEncoder enc,
            final MetalGpuBuffer nativeIndexBuffer,
            final int firstIndex,
            final int indexCount,
            final int baseVertex,
            final int instanceCount,
            final MTLIndexType indexType,
            final int baseInstance
    ) {
        MTLPrimitiveType primitiveType = primitiveTopology();

        long indexOffsetBytes = (long) firstIndex * MetalConversions.bytes(indexType);
        if (isTriangleFan()) {
            if (indexCount < 3) {
                return;
            }
            int generatedIndexCount = (indexCount - 2) * 3;
            try (GpuBufferSlice.MappedView mapped = commandEncoder.transientMemory().allocateGpuMapped((long) generatedIndexCount * Integer.BYTES, Integer.BYTES, GpuBuffer.USAGE_INDEX)) {
                expandTriangleFan(mapped.data().asIntBuffer(), nativeIndexBuffer.metalBuffer(), indexOffsetBytes, indexCount, indexType);
                GpuBufferSlice slice = mapped.slice();
                enc.drawIndexedPrimitives(MTLPrimitiveType.Triangle, generatedIndexCount, MTLIndexType.UInt32, ((MetalGpuBuffer) slice.buffer()).metalBuffer(), slice.offset(), instanceCount, baseVertex, baseInstance);
            }
        } else {
            enc.drawIndexedPrimitives(primitiveType, indexCount, indexType, nativeIndexBuffer.metalBuffer(), indexOffsetBytes, instanceCount, baseVertex, baseInstance);
        }
    }

    private static void expandTriangleFan(final IntBuffer out, final MTLBuffer indexBuffer, final long indexOffsetBytes, final int indexCount, final MTLIndexType indexType) {
        MemorySegment indices = indexBuffer.contents()
                .reinterpret(indexOffsetBytes + (long) indexCount * MetalConversions.bytes(indexType))
                .asSlice(indexOffsetBytes);
        int center = readIndex(indices, 0, indexType);
        for (int i = 1; i < indexCount - 1; i++) {
            out.put(center).put(readIndex(indices, i, indexType)).put(readIndex(indices, i + 1, indexType));
        }
    }

    private static void bindBuffer(final MTLRenderCommandEncoder enc, final MTLBuffer buffer, final long offset, final long index, final int stageMask) {
        if ((stageMask & MetalCompiledRenderPipeline.STAGE_VERTEX) != 0) {
            enc.setVertexBuffer(buffer, offset, index);
        }
        if ((stageMask & MetalCompiledRenderPipeline.STAGE_FRAGMENT) != 0) {
            enc.setFragmentBuffer(buffer, offset, index);
        }
    }

    private static void bindTexture(final MTLRenderCommandEncoder enc, final MTLTexture texture, final long index, final int stageMask) {
        if ((stageMask & MetalCompiledRenderPipeline.STAGE_VERTEX) != 0) {
            enc.setVertexTexture(texture, index);
        }
        if ((stageMask & MetalCompiledRenderPipeline.STAGE_FRAGMENT) != 0) {
            enc.setFragmentTexture(texture, index);
        }
    }

    private static void bindTextureAndSampler(final MTLRenderCommandEncoder enc, final MTLTexture texture, final MTLSamplerState sampler, final long index, final int stageMask) {
        if ((stageMask & MetalCompiledRenderPipeline.STAGE_VERTEX) != 0) {
            enc.setVertexTexture(texture, index);
            enc.setVertexSamplerState(sampler, index);
        }
        if ((stageMask & MetalCompiledRenderPipeline.STAGE_FRAGMENT) != 0) {
            enc.setFragmentTexture(texture, index);
            enc.setFragmentSamplerState(sampler, index);
        }
    }

    private static int readIndex(final MemorySegment indices, final int index, final MTLIndexType indexType) {
        if (indexType == MTLIndexType.UInt16) {
            return Short.toUnsignedInt(indices.get(ValueLayout.JAVA_SHORT_UNALIGNED, index * 2L));
        }
        return indices.get(ValueLayout.JAVA_INT_UNALIGNED, index * 4L);
    }

    private void bindDrawState(final MTLRenderCommandEncoder enc) {
        if (compiledPipeline == null) {
            throw new IllegalStateException("Pipeline is missing");
        }

        if (pipelineDirty) {
            MTLRenderPipelineState pipelineState = compiledPipeline.getNativePipeline(layout);
            if (pipelineState == null) {
                throw new IllegalStateException("Native pipeline is unavailable");
            }
            enc.setRenderPipelineState(pipelineState);
            pipelineDirty = false;

            if (depthTexture != null) {
                enc.setDepthStencilState(compiledPipeline.getDepthStencilState());
                enc.setDepthBias(
                        compiledPipeline.depthBiasConstant(),
                        compiledPipeline.depthBiasScaleFactor(),
                        0.0f
                );
            }

            enc.setFrontFacingWinding(MTLWinding.Clockwise);
            enc.setCullMode(compiledPipeline.cullMode());
            enc.setTriangleFillMode(compiledPipeline.fillMode());

            bindings.markDirty(compiledPipeline.allResourceMask());
            pushConstantsDirty = pushConstantData != null;
        }

        if (scissorDirty) {
            pushEffectiveScissor(enc);
            scissorDirty = false;
        }

        if (vertexBuffersDirty) {
            pushVertexBuffers(enc);
            vertexBuffersDirty = false;
        }

        if (bindings.hasDirty()) {
            for (MetalCompiledRenderPipeline.ResourceBinding binding : compiledPipeline.resources()) {
                if (bindings.isDirty(binding.bindingIndex())) {
                    pushDescriptor(enc, binding);
                }
            }
        }

        if (pushConstantsDirty) {
            pushPushConstants(enc);
            pushConstantsDirty = false;
        }

        bindings.clearDirty();
    }

    private MTLPrimitiveType primitiveTopology() {
        if (compiledPipeline == null) {
            throw new IllegalStateException("Pipeline is missing");
        }
        return compiledPipeline.topology();
    }

    private boolean isTriangleFan() {
        return compiledPipeline != null && compiledPipeline.triangleFan();
    }

    private void pushEffectiveScissor(final MTLRenderCommandEncoder enc) {
        int areaLeft = renderArea.x();
        int areaTop = renderArea.y();
        if (!scissorState.enabled()) {
            if (fullArea) {
                enc.setScissorRect(new MTLScissorRect(0L, 0L, targetView.getWidth(0), targetView.getHeight(0)));
                return;
            }
            enc.setScissorRect(new MTLScissorRect(areaLeft, areaTop, renderArea.width(), renderArea.height()));
            return;
        }

        int areaRight = areaLeft + renderArea.width();
        int areaBottom = areaTop + renderArea.height();
        int left = Math.max(areaLeft, scissorState.x());
        int top = Math.max(areaTop, scissorState.y());
        int right = Math.min(areaRight, scissorState.x() + scissorState.width());
        int bottom = Math.min(areaBottom, scissorState.y() + scissorState.height());
        if (right <= left || bottom <= top) {
            enc.setScissorRect(new MTLScissorRect(0, 0, 0, 0));
        } else {
            enc.setScissorRect(new MTLScissorRect(left, top, right - left, bottom - top));
        }
    }

    private void pushPushConstants(final MTLRenderCommandEncoder enc) {
        MetalCompiledRenderPipeline.PushConstants constants = compiledPipeline.pushConstants();
        if (constants == null || pushConstantData == null) {
            return;
        }

        if ((constants.stageMask() & MetalCompiledRenderPipeline.STAGE_VERTEX) != 0) {
            enc.setVertexBytes(pushConstantData, pushConstantLength, constants.metalIndex());
        }
        if ((constants.stageMask() & MetalCompiledRenderPipeline.STAGE_FRAGMENT) != 0) {
            enc.setFragmentBytes(pushConstantData, pushConstantLength, constants.metalIndex());
        }
    }

    private void pushDescriptor(
            final MTLRenderCommandEncoder enc,
            final MetalCompiledRenderPipeline.ResourceBinding binding
    ) {
        if (binding.kind() == MetalCompiledRenderPipeline.ResourceKind.SAMPLED_IMAGE) {
            TextureViewAndSampler textureBinding = bindings.texture(binding.name(), binding.bindingIndex());

            MetalGpuTextureView textureView = (MetalGpuTextureView) textureBinding.view();
            MetalGpuSampler sampler = (MetalGpuSampler) textureBinding.sampler();
            bindTextureAndSampler(enc, textureView.metalTexture(), sampler.metalSampler(), binding.metalIndex(), binding.stageMask());
            return;
        }

        if (binding.kind() == MetalCompiledRenderPipeline.ResourceKind.TEXEL_BUFFER) {
            pushTexelBufferDescriptor(enc, binding);
            return;
        }

        GpuBufferSlice uniformSlice = bindings.slice(binding.name(), binding.bindingIndex());
        if (VALIDATION && uniformSlice.buffer().isClosed()) {
            throw new IllegalStateException("Uniform " + binding.name() + " buffer has been closed");
        }

        MetalGpuBuffer uniformBuffer = (MetalGpuBuffer) uniformSlice.buffer();
        bindBuffer(enc, uniformBuffer.metalBuffer(), uniformSlice.offset(), binding.metalIndex(), binding.stageMask());
    }

    private void pushTexelBufferDescriptor(final MTLRenderCommandEncoder enc, final MetalCompiledRenderPipeline.ResourceBinding binding) {
        GpuBufferSlice texelSlice = bindings.slice(binding.name(), binding.bindingIndex());
        if (VALIDATION && texelSlice.buffer().isClosed()) {
            throw new IllegalStateException("Texel buffer " + binding.name() + " has been closed");
        }

        GpuFormat texelFormat = binding.texelBufferFormat();
        if (texelFormat == null) {
            throw new IllegalStateException("Texel buffer " + binding.name() + " is missing a format");
        }

        MetalGpuBuffer texelBuffer = (MetalGpuBuffer) texelSlice.buffer();
        MTLPixelFormat pixelFormat = MetalConversions.pixelFormat(texelFormat);
        int pixelSize = texelFormat.blockSize();
        long texelByteLength = texelSlice.length();
        if (texelByteLength <= 0L || texelByteLength % pixelSize != 0L) {
            throw new IllegalStateException("Texel buffer " + binding.name() + " length " + texelByteLength + " is not a valid " + texelFormat + " range");
        }
        long texelCount = texelByteLength / pixelSize;
        MTLTexture texelTexture = MetalUtilities.newBufferTextureView(
                device.metalDevice(),
                texelBuffer.metalBuffer(),
                texelBuffer.storageMode(),
                pixelFormat,
                texelSlice.offset(),
                texelCount,
                texelByteLength
        );
        if (texelTexture == null) {
            throw new IllegalStateException("Failed to create Metal texel buffer texture for " + binding.name());
        }

        bindTexture(enc, texelTexture, binding.metalIndex(), binding.stageMask());
        commandEncoder.queueForDestroy(texelTexture::release);
    }

    private static boolean sameSlice(@Nullable final GpuBufferSlice left, @Nullable final GpuBufferSlice right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.buffer() == right.buffer()
                && left.offset() == right.offset()
                && left.length() == right.length();
    }
}
