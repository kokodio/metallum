package com.metallum.render;

import com.metallum.Metallum;
import com.metallum.mtl.*;
import com.metallum.objc.AutoreleasePool;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.PolygonMode;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Environment(EnvType.CLIENT)
final class MetalCompiledRenderPipeline implements CompiledRenderPipeline, AutoCloseable {
    enum ResourceKind {
        UNIFORM_BUFFER,
        SAMPLED_IMAGE,
        TEXEL_BUFFER
    }

    static final int STAGE_VERTEX = 1;
    static final int STAGE_FRAGMENT = 2;
    static final int STAGE_ALL = STAGE_VERTEX | STAGE_FRAGMENT;

    record ResourceBinding(ResourceKind kind, String name, int bindingIndex, int stageMask,
                           @Nullable GpuFormat texelBufferFormat) {
    }

    private final List<ResourceBinding> resources;
    private final Map<String, ResourceBinding> resourcesByName;
    private final long allResourceMask;
    private final int firstAvailableVertexBufferSlot;
    private final MTLCullMode cullMode;
    private final MTLTriangleFillMode fillMode;
    private final float depthBiasScaleFactor;
    private final float depthBiasConstant;
    private final MTLPrimitiveType topology;
    private final int vertexBufferCount;

    private final MTLDepthStencilState depthStencilState;
    @Nullable
    private final MTLRenderPipelineState withDepthPipeline;
    @Nullable
    private final MTLRenderPipelineState withoutDepthPipeline;

    MetalCompiledRenderPipeline(
            final MetalDevice device,
            final RenderPipeline info,
            final String vertexMsl,
            final String fragmentMsl,
            final String vertexEntryPoint,
            final String fragmentEntryPoint,
            final List<ResourceBinding> resources
    ) {
        this.resources = resources;
        this.resourcesByName = resources.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(ResourceBinding::name, binding -> binding));

        int maxBindingIndex = -1;
        long resourceMask = 0L;
        for (ResourceBinding binding : resources) {
            maxBindingIndex = Math.max(maxBindingIndex, binding.bindingIndex());
            resourceMask |= 1L << binding.bindingIndex();
        }
        if (maxBindingIndex >= Long.SIZE) {
            throw new IllegalStateException("Pipeline " + info.getLocation() + " has binding index " + maxBindingIndex + ", limit is " + (Long.SIZE - 1));
        }
        this.allResourceMask = resourceMask;

        this.firstAvailableVertexBufferSlot = firstAvailableVertexBufferSlot(resources);
        this.cullMode = info.isCull() ? MTLCullMode.Back : MTLCullMode.None;
        this.fillMode = info.getPolygonMode() == PolygonMode.WIREFRAME ? MTLTriangleFillMode.Lines : MTLTriangleFillMode.Fill;
        this.topology = MTLPrimitiveType.from(info.getPrimitiveTopology());
        this.vertexBufferCount = info.getVertexFormatBindings().length;

        MTLCompareFunction depthCompareOp;
        int depthWrite;
        var depthStencilState = info.getDepthStencilState();
        if (depthStencilState == null) {
            depthCompareOp = MTLCompareFunction.Always;
            depthWrite = 0;
            this.depthBiasScaleFactor = 0.0f;
            this.depthBiasConstant = 0.0f;
        } else {
            depthCompareOp = MTLCompareFunction.from(depthStencilState.depthTest());
            depthWrite = depthStencilState.writeDepth() ? 1 : 0;
            this.depthBiasScaleFactor = depthStencilState.depthBiasScaleFactor();
            this.depthBiasConstant = depthStencilState.depthBiasConstant();
        }

        this.depthStencilState = device.depthStencilState(depthCompareOp, depthWrite != 0);

        var colorTarget = info.getColorTargetState();
        MTLPixelFormat colorFormat = colorTarget != null ? MTLPixelFormat.from(colorTarget.format()) : MTLPixelFormat.RGBA8Unorm;

        MTLFunction vertexFunction = device.getOrCompileFunction(vertexMsl, vertexEntryPoint, info.getVertexShader().toDebugFileName());
        MTLFunction fragmentFunction = device.getOrCompileFunction(fragmentMsl, fragmentEntryPoint, info.getFragmentShader().toDebugFileName());

        MTLVertexDescriptor vertexDescriptor = buildVertexDescriptor(info, this.firstAvailableVertexBufferSlot);
        this.withDepthPipeline = createPipeline(device, info, vertexFunction, fragmentFunction, vertexDescriptor, colorFormat, MTLPixelFormat.Depth32Float);
        this.withoutDepthPipeline = createPipeline(device, info, vertexFunction, fragmentFunction, vertexDescriptor, colorFormat, MTLPixelFormat.Invalid);
        vertexDescriptor.release();
    }

    @Nullable
    private static MTLRenderPipelineState createPipeline(
            final MetalDevice device,
            final RenderPipeline info,
            @Nullable final MTLFunction vertexFunction,
            @Nullable final MTLFunction fragmentFunction,
            final MTLVertexDescriptor vertexDescriptor,
            final MTLPixelFormat colorFormat,
            final MTLPixelFormat depthFormat
    ) {
        if (vertexFunction == null || fragmentFunction == null) {
            return null;
        }

        ColorTargetState colorTarget = info.getColorTargetState();
        Optional<BlendFunction> blendFunction = colorTarget == null ? Optional.empty() : colorTarget.blendFunction();
        long writeMask = colorTarget == null ? MTLColorWriteMask.All.value : MTLColorWriteMask.from(colorTarget.writeMask());

        try (AutoreleasePool _ = AutoreleasePool.push()) {
            MTLRenderPipelineDescriptor pipelineDesc = MTLRenderPipelineDescriptor.alloc().init();
            if (device.useLabels()) {
                pipelineDesc.setLabel("Pipeline " + info.getLocation());
            }
            pipelineDesc.setVertexFunction(vertexFunction);
            pipelineDesc.setFragmentFunction(fragmentFunction);
            pipelineDesc.setVertexDescriptor(vertexDescriptor);
            pipelineDesc.setDepthAttachmentPixelFormat(depthFormat);

            MTLRenderPipelineColorAttachmentDescriptor colorAttachment = pipelineDesc.colorAttachments().object(0);
            colorAttachment.setPixelFormat(colorFormat);
            colorAttachment.setWriteMask(writeMask);
            colorAttachment.setBlendingEnabled(blendFunction.isPresent());
            if (blendFunction.isPresent()) {
                var function = blendFunction.get();
                colorAttachment.setSourceRGBBlendFactor(MTLBlendFactor.from(function.color().sourceFactor()));
                colorAttachment.setDestinationRGBBlendFactor(MTLBlendFactor.from(function.color().destFactor()));
                colorAttachment.setRgbBlendOperation(MTLBlendOperation.from(function.color().op()));
                colorAttachment.setSourceAlphaBlendFactor(MTLBlendFactor.from(function.alpha().sourceFactor()));
                colorAttachment.setDestinationAlphaBlendFactor(MTLBlendFactor.from(function.alpha().destFactor()));
                colorAttachment.setAlphaBlendOperation(MTLBlendOperation.from(function.alpha().op()));
            }

            MTLRenderPipelineState pipeline = device.metalDevice().newRenderPipelineState(pipelineDesc);
            if (pipeline == null) {
                Metallum.LOGGER.error("[metallum] Pipeline {} failed to build with depth format {}", info.getLocation(), depthFormat);
            }
            pipelineDesc.release();
            return pipeline;
        }
    }

    @Override
    public boolean isValid() {
        return this.withDepthPipeline != null;
    }

    List<ResourceBinding> resources() {
        return this.resources;
    }

    long allResourceMask() {
        return this.allResourceMask;
    }

    @Nullable
    ResourceBinding resource(final String name) {
        return this.resourcesByName.get(name);
    }

    int firstAvailableVertexBufferSlot() {
        return this.firstAvailableVertexBufferSlot;
    }

    float depthBiasScaleFactor() {
        return this.depthBiasScaleFactor;
    }

    float depthBiasConstant() {
        return this.depthBiasConstant;
    }

    MTLDepthStencilState getDepthStencilState() {
        return this.depthStencilState;
    }

    @Nullable
    MTLRenderPipelineState getNativePipeline(final boolean useDepth) {
        return useDepth ? this.withDepthPipeline : this.withoutDepthPipeline;
    }

    MTLCullMode cullMode() {
        return this.cullMode;
    }

    MTLTriangleFillMode fillMode() {
        return this.fillMode;
    }

    MTLPrimitiveType topology() {
        return this.topology;
    }

    int vertexBufferCount() {
        return this.vertexBufferCount;
    }

    private static MTLVertexDescriptor buildVertexDescriptor(
            final RenderPipeline pipeline,
            final int firstMetalVertexBufferSlot
    ) {
        VertexFormat[] bindings = pipeline.getVertexFormatBindings();
        MTLVertexDescriptor vertexDesc = MTLVertexDescriptor.alloc().init();
        long attrIndex = 0;

        try (AutoreleasePool _ = AutoreleasePool.push()) {
            for (int i = 0; i < bindings.length; i++) {
                VertexFormat binding = bindings[i];
                if (binding == null || binding.getElements().isEmpty()) {
                    continue;
                }

                int metalSlot = firstMetalVertexBufferSlot + i;

                long stride = binding.getVertexSize();
                long stepRate = binding.getStepRate();
                MTLVertexStepFunction stepFunction = stepRate > 0 ? MTLVertexStepFunction.PerInstance : MTLVertexStepFunction.PerVertex;
                MTLVertexBufferLayoutDescriptor layout = vertexDesc.layouts().object(metalSlot);
                layout.setStride(stride);
                layout.setStepFunction(stepFunction);
                layout.setStepRate(stepRate > 0 ? stepRate : 1);

                for (VertexFormatElement element : binding.getElements()) {
                    MTLVertexFormat format = MTLVertexFormat.from(element.format());
                    if (format == MTLVertexFormat.Invalid) {
                        throw new IllegalStateException("Unsupported vertex attribute format: " + element.format());
                    }
                    MTLVertexAttributeDescriptor attribute = vertexDesc.attributes().object(attrIndex);
                    attribute.setFormat(format);
                    attribute.setOffset(element.offset());
                    attribute.setBufferIndex(metalSlot);
                    attrIndex++;
                }
            }
        }

        return vertexDesc;
    }

    private static int firstAvailableVertexBufferSlot(final List<ResourceBinding> resources) {
        int maxVertexBufferBinding = -1;
        for (ResourceBinding resource : resources) {
            if (resource.kind() == ResourceKind.UNIFORM_BUFFER && (resource.stageMask() & STAGE_VERTEX) != 0) {
                maxVertexBufferBinding = Math.max(maxVertexBufferBinding, resource.bindingIndex());
            }
        }
        return maxVertexBufferBinding + 1;
    }

    @Override
    public void close() {
        if (this.withDepthPipeline != null) {
            this.withDepthPipeline.release();
        }
        if (this.withoutDepthPipeline != null) {
            this.withoutDepthPipeline.release();
        }
    }
}
