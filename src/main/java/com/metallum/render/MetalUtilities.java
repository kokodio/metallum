package com.metallum.render;

import com.metallum.Metallum;
import io.github.kokodio.metaljvm.metal.*;
import io.github.kokodio.metaljvm.quartzcore.*;
import io.github.kokodio.metaljvm.objc.AutoreleasePool;
import io.github.kokodio.metaljvm.foundation.NSErrorException;
import io.github.kokodio.metaljvm.foundation.NSObject;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.lang.foreign.ValueLayout.JAVA_FLOAT;

@Environment(EnvType.CLIENT)
final class MetalUtilities {
    private static final String PRESENT_MSL = """
            #include <metal_stdlib>
            using namespace metal;
            
            struct PresentVertexOut {
              float4 position [[position]];
              float2 uv;
            };
            
            vertex PresentVertexOut metallum_present_vs(uint vertexId [[vertex_id]]) {
              const float2 positions[3] = {
                float2(-1.0,  1.0),
                float2( 3.0,  1.0),
                float2(-1.0, -3.0)
              };
            
              // Y-flip version:
              // old equivalent was uvMin=(0,1), uvMax=(1,0)
              const float2 uvs[3] = {
                float2(0.0,  1.0),
                float2(2.0,  1.0),
                float2(0.0, -1.0)
              };
            
              PresentVertexOut out;
              out.position = float4(positions[vertexId], 0.0, 1.0);
              out.uv = uvs[vertexId];
              return out;
            }
            
            fragment float4 metallum_present_fs(
              PresentVertexOut in [[stage_in]],
              texture2d<float> tex [[texture(0)]],
              sampler smp [[sampler(0)]]
            ) {
              return tex.sample(smp, in.uv);
            }
            """;

    private static final String CLEAR_MSL = """
            #include <metal_stdlib>
            using namespace metal;
            
            struct ClearUniforms {
              float z;
              float3 _padding0;
              float4 color;
            };
            
            struct ClearVertexOut {
              float4 position [[position]];
              float4 color;
            };
            
            vertex ClearVertexOut metallum_clear_vs(
              uint vertexId [[vertex_id]],
              constant ClearUniforms& u [[buffer(1)]]
            ) {
              const float2 positions[3] = {
                float2(-1.0,  1.0),
                float2( 3.0,  1.0),
                float2(-1.0, -3.0)
              };
            
              ClearVertexOut out;
              out.position = float4(positions[vertexId], u.z, 1.0);
              out.color = u.color;
              return out;
            }
            
            fragment float4 metallum_clear_fs(ClearVertexOut in [[stage_in]]) {
              return in.color;
            }
            """;

    private static MTLDevice device;
    @Nullable
    private static MTLRenderPipelineState presentPipeline;
    @Nullable
    private static MTLSamplerState presentLinearSampler;
    @Nullable
    private static MTLSamplerState presentNearestSampler;
    private static final Map<Long, MTLRenderPipelineState> clearPipelines = new HashMap<>();
    private static final Map<Long, MTLDepthStencilState> depthStencilStates = new HashMap<>();

    private MetalUtilities() {
    }

    static void init(final MTLDevice mtlDevice) {
        device = mtlDevice;
        presentPipeline = buildPipeline(PRESENT_MSL, "metallum_present_vs", "metallum_present_fs",
                MTLPixelFormat.BGRA8Unorm, MTLPixelFormat.Invalid, MTLColorWriteMask.All);
        presentLinearSampler = buildPresentSampler(MTLSamplerMinMagFilter.Linear);
        presentNearestSampler = buildPresentSampler(MTLSamplerMinMagFilter.Nearest);
        ensureClearPipeline(MTLPixelFormat.BGRA8Unorm, MTLPixelFormat.Depth32Float, true);
        ensureClearPipeline(MTLPixelFormat.RGBA8Unorm, MTLPixelFormat.Depth32Float, true);
        ensureClearPipeline(MTLPixelFormat.BGRA8Unorm, MTLPixelFormat.Invalid, true);
    }

    static void close() {
        if (presentPipeline != null) {
            presentPipeline.release();
            presentPipeline = null;
        }
        if (presentLinearSampler != null) {
            presentLinearSampler.release();
            presentLinearSampler = null;
        }
        if (presentNearestSampler != null) {
            presentNearestSampler.release();
            presentNearestSampler = null;
        }
        clearPipelines.values().forEach(MTLRenderPipelineState::release);
        clearPipelines.clear();
        depthStencilStates.values().forEach(MTLDepthStencilState::release);
        depthStencilStates.clear();
        device = null;
    }

    static void clearDraw(
            final MTLRenderCommandEncoder encoder,
            final MTLPixelFormat colorFormat,
            final MTLPixelFormat depthFormat,
            final int targetWidth,
            final int targetHeight,
            @Nullable final Vector4fc clearColor,
            @Nullable final Double clearDepth,
            final RenderPass.RenderArea area
    ) {
        int left = Math.max(area.x(), 0);
        int top = Math.max(area.y(), 0);
        int right = Math.min(area.x() + area.width(), targetWidth);
        int bottom = Math.min(area.y() + area.height(), targetHeight);
        if (left >= right || top >= bottom) {
            return;
        }

        MTLRenderPipelineState pipeline = ensureClearPipeline(colorFormat, depthFormat, clearColor != null);
        if (pipeline == null) {
            return;
        }
        boolean hasDepth = depthFormat != MTLPixelFormat.Invalid;

        encoder.setViewport(new MTLViewport(0.0, 0.0, targetWidth, targetHeight, 0.0, 1.0));
        encoder.setScissorRect(new MTLScissorRect(left, top, right - left, bottom - top));
        encoder.setRenderPipelineState(pipeline);
        if (hasDepth) {
            encoder.setDepthStencilState(ensureDepthStencilState(MTLCompareFunction.Always, clearDepth != null));
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            MemorySegment uniforms = MemorySegment.ofAddress(stack.nmalloc(16, 48)).reinterpret(48);
            uniforms.set(JAVA_FLOAT, 0, hasDepth && clearDepth != null ? (float) Math.clamp(clearDepth, 0.0, 1.0) : 0.0f);
            uniforms.set(JAVA_FLOAT, 32, clearColor == null ? 0.0f : clearColor.x());
            uniforms.set(JAVA_FLOAT, 36, clearColor == null ? 0.0f : clearColor.y());
            uniforms.set(JAVA_FLOAT, 40, clearColor == null ? 0.0f : clearColor.z());
            uniforms.set(JAVA_FLOAT, 44, clearColor == null ? 0.0f : clearColor.w());
            encoder.setVertexBytes(uniforms, 48L, 1L);
        }

        encoder.drawPrimitives(MTLPrimitiveType.Triangle, 0, 3, 1, 0);
    }

    static void encodePresentTextureToDrawable(
            final MTLCommandBuffer commandBuffer,
            final CAMetalLayer layer,
            final MTLTexture sourceTexture,
            final MTLFence globalFence
    ) {
        try (AutoreleasePool _ = AutoreleasePool.push()) {
            CAMetalDrawable drawable = layer.nextDrawable();
            if (drawable == null) {
                return;
            }
            MTLTexture drawableTexture = drawable.texture();

            MTLRenderPassDescriptor renderPass = MTLRenderPassDescriptor.alloc().init();
            MTLRenderPassColorAttachmentDescriptor attachment = renderPass.colorAttachments().objectAtIndexedSubscript(0);
            attachment.setTexture(drawableTexture);
            attachment.setLoadAction(MTLLoadAction.DontCare);
            attachment.setStoreAction(MTLStoreAction.Store);
            MTLRenderCommandEncoder encoder = nonNil(commandBuffer.renderCommandEncoder(renderPass), "renderCommandEncoderWithDescriptor:");
            renderPass.release();

            if (globalFence != null) {
                encoder.waitForFence(globalFence, MTLRenderStages.Fragment);
            }

            long drawableWidth = drawableTexture.width();
            long drawableHeight = drawableTexture.height();
            encoder.setViewport(new MTLViewport(0.0, 0.0, drawableWidth, drawableHeight, 0.0, 1.0));
            encoder.setRenderPipelineState(presentPipeline);
            encoder.setFragmentTexture(sourceTexture, 0L);

            boolean requiresScaling = sourceTexture.width() != drawableWidth
                    || sourceTexture.height() != drawableHeight;
            encoder.setFragmentSamplerState(requiresScaling ? presentLinearSampler : presentNearestSampler, 0L);

            encoder.drawPrimitives(MTLPrimitiveType.Triangle, 0, 3, 1, 0);

            if (globalFence != null) {
                encoder.updateFence(globalFence, MTLRenderStages.Fragment);
            }

            encoder.endEncoding();
            commandBuffer.presentDrawable(drawable);
        }
    }

    @Nullable
    private static MTLRenderPipelineState ensureClearPipeline(final MTLPixelFormat colorFormat, final MTLPixelFormat depthFormat, final boolean writeColor) {
        long key = (colorFormat.value << 32) | (depthFormat.value << 1) | (writeColor ? 1L : 0L);
        MTLRenderPipelineState cached = clearPipelines.get(key);
        if (cached != null) {
            return cached;
        }
        MTLRenderPipelineState pipeline = buildPipeline(CLEAR_MSL, "metallum_clear_vs", "metallum_clear_fs",
                colorFormat, depthFormat, writeColor ? MTLColorWriteMask.All : MTLColorWriteMask.None);
        if (pipeline != null) {
            clearPipelines.put(key, pipeline);
        }
        return pipeline;
    }

    private static MTLDepthStencilState ensureDepthStencilState(final MTLCompareFunction compareOp, final boolean writeDepth) {
        long key = (compareOp.value << 1) | (writeDepth ? 1L : 0L);
        MTLDepthStencilState cached = depthStencilStates.get(key);
        if (cached != null) {
            return cached;
        }
        MTLDepthStencilDescriptor descriptor = MTLDepthStencilDescriptor.alloc().init();
        descriptor.setDepthCompareFunction(compareOp);
        descriptor.setDepthWriteEnabled(writeDepth);
        MTLDepthStencilState state = MetalUtilities.nonNil(device.newDepthStencilState(descriptor), "newDepthStencilStateWithDescriptor:");
        depthStencilStates.put(key, state);
        descriptor.release();
        return state;
    }

    @Nullable
    private static MTLRenderPipelineState buildPipeline(
            final String mslSource,
            final String vertexEntry,
            final String fragmentEntry,
            final MTLPixelFormat colorFormat,
            final MTLPixelFormat depthFormat,
            final long writeMask
    ) {
        try (AutoreleasePool _ = AutoreleasePool.push()) {
            MTLLibrary library = newLibrary(device, mslSource);
            if (library == null) {
                return null;
            }
            MTLFunction vertexFunction = library.newFunctionWithName(vertexEntry);
            MTLFunction fragmentFunction = library.newFunctionWithName(fragmentEntry);
            library.release();

            MTLRenderPipelineState pipeline = null;
            if (vertexFunction != null && fragmentFunction != null) {
                MTLRenderPipelineDescriptor descriptor = MTLRenderPipelineDescriptor.alloc().init();
                descriptor.setVertexFunction(vertexFunction);
                descriptor.setFragmentFunction(fragmentFunction);
                descriptor.setDepthAttachmentPixelFormat(depthFormat);
                MTLRenderPipelineColorAttachmentDescriptor attachment = descriptor.colorAttachments().objectAtIndexedSubscript(0);
                attachment.setPixelFormat(colorFormat);
                attachment.setBlendingEnabled(false);
                attachment.setWriteMask(writeMask);
                pipeline = newRenderPipelineState(device, descriptor);
                descriptor.release();
            }
            if (vertexFunction != null) {
                vertexFunction.release();
            }
            if (fragmentFunction != null) {
                fragmentFunction.release();
            }
            return pipeline;
        }
    }

    private static MTLSamplerState buildPresentSampler(final MTLSamplerMinMagFilter filter) {
        MTLSamplerDescriptor descriptor = MTLSamplerDescriptor.alloc().init();
        descriptor.setMinFilter(filter);
        descriptor.setMagFilter(filter);
        descriptor.setMipFilter(MTLSamplerMipFilter.NotMipmapped);
        descriptor.setSAddressMode(MTLSamplerAddressMode.ClampToEdge);
        descriptor.setTAddressMode(MTLSamplerAddressMode.ClampToEdge);
        MTLSamplerState result = MetalUtilities.nonNil(device.newSamplerState(descriptor), "newSamplerStateWithDescriptor:");
        descriptor.release();
        return result;
    }

    @Nullable
    static MTLTexture newBufferTextureView(
            final MTLDevice device,
            final MTLBuffer buffer,
            final MTLStorageMode storageMode,
            final MTLPixelFormat pixelFormat,
            final long offset,
            final long width,
            final long bytesPerRow
    ) {
        if (pixelFormat == MTLPixelFormat.Invalid || width <= 0 || bytesPerRow <= 0 || offset < 0) {
            return null;
        }
        long bufferLength = buffer.length();
        if (offset > bufferLength || bytesPerRow > bufferLength - offset) {
            return null;
        }
        long alignment = device.minimumTextureBufferAlignmentForPixelFormat(pixelFormat);
        if (alignment <= 0 || offset % alignment != 0) {
            return null;
        }
        long remainder = bytesPerRow % alignment;
        long alignedBytesPerRow = remainder == 0 ? bytesPerRow : bytesPerRow + alignment - remainder;

        try (AutoreleasePool _ = AutoreleasePool.push()) {
            MTLTextureDescriptor descriptor = MTLTextureDescriptor.textureBufferDescriptor(pixelFormat, width, 0L, MTLTextureUsage.ShaderRead);
            descriptor.setStorageMode(storageMode);
            descriptor.setHazardTrackingMode(MTLHazardTrackingMode.Untracked);
            return buffer.newTexture(descriptor, offset, alignedBytesPerRow);
        }
    }

    static boolean sameHandle(@Nullable final NSObject left, @Nullable final NSObject right) {
        long leftValue = left == null ? 0L : left.handle();
        long rightValue = right == null ? 0L : right.handle();
        return leftValue == rightValue;
    }

    static List<String> vertexAttributeNames(final RenderPipeline pipeline) {
        List<String> names = new ArrayList<>();
        for (VertexFormat binding : pipeline.getVertexFormatBindings()) {
            if (binding != null) {
                for (VertexFormatElement element : binding.getElements()) {
                    names.add(element.name());
                }
            }
        }
        return names;
    }

    static <T> T nonNil(@Nullable final T object, final String selector) {
        if (object == null) {
            throw new IllegalStateException(selector + " returned nil");
        }
        return object;
    }

    @Nullable
    static MTLLibrary newLibrary(final MTLDevice device, final String source) {
        try (AutoreleasePool _ = AutoreleasePool.push()) {
            return device.newLibraryWithSource(source, null);
        } catch (NSErrorException exception) {
            Metallum.LOGGER.error("[metallum] Failed to compile MSL: {}", exception.getMessage());
            return null;
        }
    }

    @Nullable
    static MTLRenderPipelineState newRenderPipelineState(final MTLDevice device, final MTLRenderPipelineDescriptor descriptor) {
        try (AutoreleasePool _ = AutoreleasePool.push()) {
            return device.newRenderPipelineState(descriptor);
        } catch (NSErrorException exception) {
            Metallum.LOGGER.error("[metallum] Failed to create render pipeline state: {}", exception.getMessage());
            return null;
        }
    }
}
