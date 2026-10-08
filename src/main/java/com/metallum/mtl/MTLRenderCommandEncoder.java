package com.metallum.mtl;

import com.metallum.objc.Msg;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.lwjgl.system.MemoryStack;

import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.*;

@Environment(EnvType.CLIENT)
public final class MTLRenderCommandEncoder extends MTLCommandEncoder {
    private static final Msg SET_RENDER_PIPELINE_STATE = Msg.ofVoid("setRenderPipelineState:", ADDRESS);
    private static final Msg SET_DEPTH_STENCIL_STATE = Msg.ofVoid("setDepthStencilState:", ADDRESS);
    private static final Msg SET_DEPTH_BIAS = Msg.ofVoid("setDepthBias:slopeScale:clamp:", JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT);
    private static final Msg SET_FRONT_FACING_WINDING = Msg.ofVoid("setFrontFacingWinding:", JAVA_LONG);
    private static final Msg SET_CULL_MODE = Msg.ofVoid("setCullMode:", JAVA_LONG);
    private static final Msg SET_TRIANGLE_FILL_MODE = Msg.ofVoid("setTriangleFillMode:", JAVA_LONG);
    private static final Msg SET_VERTEX_BUFFER = Msg.ofVoid("setVertexBuffer:offset:atIndex:", ADDRESS, JAVA_LONG, JAVA_LONG);
    private static final Msg SET_FRAGMENT_BUFFER = Msg.ofVoid("setFragmentBuffer:offset:atIndex:", ADDRESS, JAVA_LONG, JAVA_LONG);
    private static final Msg SET_VERTEX_BUFFER_OFFSET = Msg.ofVoid("setVertexBufferOffset:atIndex:", JAVA_LONG, JAVA_LONG);
    private static final Msg SET_FRAGMENT_BUFFER_OFFSET = Msg.ofVoid("setFragmentBufferOffset:atIndex:", JAVA_LONG, JAVA_LONG);
    private static final Msg SET_VERTEX_TEXTURE = Msg.ofVoid("setVertexTexture:atIndex:", ADDRESS, JAVA_LONG);
    private static final Msg SET_FRAGMENT_TEXTURE = Msg.ofVoid("setFragmentTexture:atIndex:", ADDRESS, JAVA_LONG);
    private static final Msg SET_VERTEX_SAMPLER = Msg.ofVoid("setVertexSamplerState:atIndex:", ADDRESS, JAVA_LONG);
    private static final Msg SET_FRAGMENT_SAMPLER = Msg.ofVoid("setFragmentSamplerState:atIndex:", ADDRESS, JAVA_LONG);
    private static final Msg SET_SCISSOR_RECT = Msg.ofVoid("setScissorRect:", ADDRESS);
    private static final Msg SET_VIEWPORT = Msg.ofVoid("setViewport:", ADDRESS);
    private static final Msg SET_VERTEX_BYTES = Msg.ofVoid("setVertexBytes:length:atIndex:", ADDRESS, JAVA_LONG, JAVA_LONG);
    private static final Msg DRAW_PRIMITIVES = Msg.ofVoid("drawPrimitives:vertexStart:vertexCount:instanceCount:baseInstance:",
            JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG);
    private static final Msg DRAW_INDEXED = Msg.ofVoid("drawIndexedPrimitives:indexCount:indexType:indexBuffer:indexBufferOffset:instanceCount:baseVertex:baseInstance:",
            JAVA_LONG, JAVA_LONG, JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG);
    private static final Msg DRAW_INDEXED_INDIRECT = Msg.ofVoid("drawIndexedPrimitives:indexType:indexBuffer:indexBufferOffset:indirectBuffer:indirectBufferOffset:",
            JAVA_LONG, JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG);
    private static final Msg DRAW_INDIRECT = Msg.ofVoid("drawPrimitives:indirectBuffer:indirectBufferOffset:", JAVA_LONG, ADDRESS, JAVA_LONG);
    private static final Msg UPDATE_FENCE = Msg.ofVoid("updateFence:afterStages:", ADDRESS, JAVA_LONG);
    private static final Msg WAIT_FOR_FENCE = Msg.ofVoid("waitForFence:beforeStages:", ADDRESS, JAVA_LONG);

    MTLRenderCommandEncoder(final MemorySegment handle) {
        super(handle);
    }

    public void setRenderPipelineState(final MTLRenderPipelineState pipelineState) {
        SET_RENDER_PIPELINE_STATE.send(handle(), pipelineState.handle());
    }

    public void setDepthStencilState(final MTLDepthStencilState depthStencilState) {
        SET_DEPTH_STENCIL_STATE.send(handle(), depthStencilState.handle());
    }

    public void setDepthBias(final float depthBias, final float slopeScale, final float clamp) {
        SET_DEPTH_BIAS.send(handle(), depthBias, slopeScale, clamp);
    }

    public void setFrontFacingWinding(final MTLWinding winding) {
        SET_FRONT_FACING_WINDING.send(handle(), winding.value);
    }

    public void setCullMode(final MTLCullMode cullMode) {
        SET_CULL_MODE.send(handle(), cullMode.value);
    }

    public void setTriangleFillMode(final MTLTriangleFillMode fillMode) {
        SET_TRIANGLE_FILL_MODE.send(handle(), fillMode.value);
    }

    public void setVertexBuffer(final MTLBuffer buffer, final long offset, final long index) {
        SET_VERTEX_BUFFER.send(handle(), seg(buffer), offset, index);
    }

    public void setFragmentBuffer(final MTLBuffer buffer, final long offset, final long index) {
        SET_FRAGMENT_BUFFER.send(handle(), seg(buffer), offset, index);
    }

    public void setVertexBufferOffset(final long offset, final long index) {
        SET_VERTEX_BUFFER_OFFSET.send(handle(), offset, index);
    }

    public void setFragmentBufferOffset(final long offset, final long index) {
        SET_FRAGMENT_BUFFER_OFFSET.send(handle(), offset, index);
    }

    public void setVertexTexture(final MTLTexture texture, final long index) {
        SET_VERTEX_TEXTURE.send(handle(), texture.handle(), index);
    }

    public void setFragmentTexture(final MTLTexture texture, final long index) {
        SET_FRAGMENT_TEXTURE.send(handle(), texture.handle(), index);
    }

    public void setVertexSamplerState(final MTLSamplerState sampler, final long index) {
        SET_VERTEX_SAMPLER.send(handle(), sampler.handle(), index);
    }

    public void setFragmentSamplerState(final MTLSamplerState sampler, final long index) {
        SET_FRAGMENT_SAMPLER.send(handle(), sampler.handle(), index);
    }

    public void setScissorRect(final long x, final long y, final long width, final long height) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            SET_SCISSOR_RECT.send(handle(), MTLScissorRect.on(stack, x, y, width, height));
        }
    }

    public void setViewport(final double originX, final double originY, final double width, final double height, final double znear, final double zfar) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            MemorySegment viewport = MemorySegment.ofAddress(stack.nmalloc(8, 48)).reinterpret(48);
            viewport.set(JAVA_DOUBLE, 0, originX);
            viewport.set(JAVA_DOUBLE, 8, originY);
            viewport.set(JAVA_DOUBLE, 16, width);
            viewport.set(JAVA_DOUBLE, 24, height);
            viewport.set(JAVA_DOUBLE, 32, znear);
            viewport.set(JAVA_DOUBLE, 40, zfar);
            SET_VIEWPORT.send(handle(), viewport);
        }
    }

    public void setVertexBytes(final MemorySegment bytes, final long length, final long index) {
        SET_VERTEX_BYTES.send(handle(), bytes, length, index);
    }

    public void drawPrimitives(final MTLPrimitiveType primitiveType, final int firstVertex, final int vertexCount, final int instanceCount, final int baseInstance) {
        DRAW_PRIMITIVES.send(handle(), primitiveType.value, firstVertex, vertexCount, instanceCount, baseInstance);
    }

    public void drawIndexedPrimitives(final MTLPrimitiveType primitiveType, final int indexCount, final MTLIndexType indexType, final MTLBuffer indexBuffer, final long offset, final int instanceCount, final int baseVertex, final int baseInstance) {
        DRAW_INDEXED.send(handle(), primitiveType.value, indexCount, indexType.value, indexBuffer.handle(), offset, instanceCount, baseVertex, baseInstance);
    }

    public void drawIndexedPrimitives(final MTLPrimitiveType primitiveType, final MTLIndexType indexType, final MTLBuffer indexBuffer, final long indexBufferOffset, final MTLBuffer indirectBuffer, final long indirectBufferOffset) {
        DRAW_INDEXED_INDIRECT.send(handle(), primitiveType.value, indexType.value, indexBuffer.handle(), indexBufferOffset, indirectBuffer.handle(), indirectBufferOffset);
    }

    public void drawPrimitives(final MTLPrimitiveType primitiveType, final MTLBuffer indirectBuffer, final long indirectBufferOffset) {
        DRAW_INDIRECT.send(handle(), primitiveType.value, indirectBuffer.handle(), indirectBufferOffset);
    }

    public void updateFence(final MTLFence fence, final MTLRenderStages stages) {
        UPDATE_FENCE.send(handle(), fence.handle(), stages.value);
    }

    public void waitForFence(final MTLFence fence, final MTLRenderStages stages) {
        WAIT_FOR_FENCE.send(handle(), fence.handle(), stages.value);
    }

    private static MemorySegment seg(final MTLBuffer buffer) {
        return buffer == null ? MemorySegment.NULL : buffer.handle();
    }
}
