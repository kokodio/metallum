package com.metallum.render;

import com.metallum.Metallum;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.PolygonMode;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import io.github.kokodio.metaljvm.metal.*;
import io.github.kokodio.metaljvm.objc.AutoreleasePool;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
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
    static final int LAYOUT_DEPTH = 1 << ColorTargetState.MAX_COLOR_TARGETS;

    record ResourceBinding(ResourceKind kind, String name, int bindingIndex, int metalIndex, int stageMask,
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
    private final boolean triangleFan;
    private final int vertexBufferCount;

    private final MTLDepthStencilState depthStencilState;
    private final MetalDevice device;
    @Nullable
    private final MTLRenderPipelineDescriptor descriptor;
    private final int fullLayout;
    private final Int2ObjectOpenHashMap<MTLRenderPipelineState> pipelinesByLayout = new Int2ObjectOpenHashMap<>();

    MetalCompiledRenderPipeline(
            final MetalDevice device,
            final RenderPipeline info,
            final String vertexMsl,
            final String fragmentMsl,
            final String vertexEntryPoint,
            final String fragmentEntryPoint,
            final List<ResourceBinding> resources
    ) {
        this.device = device;
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
        this.topology = MetalConversions.primitiveType(info.getPrimitiveTopology());
        this.triangleFan = info.getPrimitiveTopology() == com.mojang.blaze3d.PrimitiveTopology.TRIANGLE_FAN;
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
            depthCompareOp = MetalConversions.compareFunction(depthStencilState.depthTest());
            depthWrite = depthStencilState.writeDepth() ? 1 : 0;
            this.depthBiasScaleFactor = depthStencilState.depthBiasScaleFactor();
            this.depthBiasConstant = depthStencilState.depthBiasConstant();
        }

        this.depthStencilState = device.depthStencilState(depthCompareOp, depthWrite != 0);

        MTLFunction vertexFunction = device.getOrCompileFunction(vertexMsl, vertexEntryPoint, info.getVertexShader().toDebugFileName());
        MTLFunction fragmentFunction = device.getOrCompileFunction(fragmentMsl, fragmentEntryPoint, info.getFragmentShader().toDebugFileName());

        ColorTargetState[] colorTargets = info.getColorTargetStates();
        int layout = LAYOUT_DEPTH | 1;
        for (int i = 1; i < colorTargets.length; i++) {
            if (colorTargets[i] != null) {
                layout |= 1 << i;
            }
        }
        this.fullLayout = layout;

        this.descriptor = vertexFunction == null || fragmentFunction == null ? null : buildDescriptor(device, info, vertexFunction, fragmentFunction, this.firstAvailableVertexBufferSlot);
        if (this.descriptor != null && getNativePipeline(layout) == null) {
            Metallum.LOGGER.error("[metallum] Pipeline {} failed to build", info.getLocation());
        }
    }

    private static MTLRenderPipelineDescriptor buildDescriptor(
            final MetalDevice device,
            final RenderPipeline info,
            final MTLFunction vertexFunction,
            final MTLFunction fragmentFunction,
            final int firstVertexBufferSlot
    ) {
        ColorTargetState[] colorTargets = info.getColorTargetStates();
        MTLVertexDescriptor vertexDescriptor = buildVertexDescriptor(info, firstVertexBufferSlot);

        try (AutoreleasePool _ = AutoreleasePool.push()) {
            MTLRenderPipelineDescriptor pipelineDesc = MTLRenderPipelineDescriptor.alloc().init();
            if (device.useLabels()) {
                pipelineDesc.setLabel("Pipeline " + info.getLocation());
            }
            pipelineDesc.setVertexFunction(vertexFunction);
            pipelineDesc.setFragmentFunction(fragmentFunction);
            pipelineDesc.setVertexDescriptor(vertexDescriptor);
            pipelineDesc.setDepthAttachmentPixelFormat(MTLPixelFormat.Depth32Float);
            vertexDescriptor.release();

            for (int i = 0; i < Math.max(colorTargets.length, 1); i++) {
                ColorTargetState colorTarget = i < colorTargets.length ? colorTargets[i] : null;
                if (colorTarget == null && i > 0) {
                    continue;
                }
                Optional<BlendFunction> blendFunction = colorTarget == null ? Optional.empty() : colorTarget.blendFunction();

                MTLRenderPipelineColorAttachmentDescriptor colorAttachment = pipelineDesc.colorAttachments().objectAtIndexedSubscript(i);
                colorAttachment.setPixelFormat(colorTarget != null ? MetalConversions.pixelFormat(colorTarget.format()) : MTLPixelFormat.RGBA8Unorm);
                colorAttachment.setWriteMask(colorTarget == null ? MTLColorWriteMask.All : MetalConversions.colorWriteMask(colorTarget.writeMask()));
                colorAttachment.setBlendingEnabled(blendFunction.isPresent());
                if (blendFunction.isPresent()) {
                    var function = blendFunction.get();
                    colorAttachment.setSourceRGBBlendFactor(MetalConversions.blendFactor(function.color().sourceFactor()));
                    colorAttachment.setDestinationRGBBlendFactor(MetalConversions.blendFactor(function.color().destFactor()));
                    colorAttachment.setRgbBlendOperation(MetalConversions.blendOperation(function.color().op()));
                    colorAttachment.setSourceAlphaBlendFactor(MetalConversions.blendFactor(function.alpha().sourceFactor()));
                    colorAttachment.setDestinationAlphaBlendFactor(MetalConversions.blendFactor(function.alpha().destFactor()));
                    colorAttachment.setAlphaBlendOperation(MetalConversions.blendOperation(function.alpha().op()));
                }
            }
            return pipelineDesc;
        }
    }

    @Nullable
    private MTLRenderPipelineState createPipeline(final int layout) {
        if (descriptor == null) {
            return null;
        }

        try (AutoreleasePool _ = AutoreleasePool.push()) {
            MTLRenderPipelineDescriptor variant = new MTLRenderPipelineDescriptor(descriptor.copy().handle());
            for (int missing = fullLayout & ~layout; missing != 0; missing &= missing - 1) {
                int slot = Integer.numberOfTrailingZeros(missing);
                if (slot == ColorTargetState.MAX_COLOR_TARGETS) {
                    variant.setDepthAttachmentPixelFormat(MTLPixelFormat.Invalid);
                } else {
                    variant.colorAttachments().objectAtIndexedSubscript(slot).setPixelFormat(MTLPixelFormat.Invalid);
                }
            }
            MTLRenderPipelineState pipeline = MetalUtilities.newRenderPipelineState(device.metalDevice(), variant);
            variant.release();
            return pipeline;
        }
    }

    @Override
    public boolean isValid() {
        return this.pipelinesByLayout.get(this.fullLayout) != null;
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
    MTLRenderPipelineState getNativePipeline(final int passLayout) {
        int layout = passLayout & this.fullLayout;
        MTLRenderPipelineState pipeline = this.pipelinesByLayout.get(layout);
        if (pipeline == null && !this.pipelinesByLayout.containsKey(layout)) {
            pipeline = createPipeline(layout);
            this.pipelinesByLayout.put(layout, pipeline);
        }
        return pipeline;
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

    boolean triangleFan() {
        return this.triangleFan;
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
                MTLVertexBufferLayoutDescriptor layout = vertexDesc.layouts().objectAtIndexedSubscript(metalSlot);
                layout.setStride(stride);
                layout.setStepFunction(stepFunction);
                layout.setStepRate(stepRate > 0 ? stepRate : 1);

                for (VertexFormatElement element : binding.getElements()) {
                    MTLVertexFormat format = MetalConversions.vertexFormat(element.format());
                    if (format == MTLVertexFormat.Invalid) {
                        throw new IllegalStateException("Unsupported vertex attribute format: " + element.format());
                    }
                    MTLVertexAttributeDescriptor attribute = vertexDesc.attributes().objectAtIndexedSubscript(attrIndex);
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
                maxVertexBufferBinding = Math.max(maxVertexBufferBinding, resource.metalIndex());
            }
        }
        return maxVertexBufferBinding + 1;
    }

    @Override
    public void close() {
        for (MTLRenderPipelineState pipeline : this.pipelinesByLayout.values()) {
            if (pipeline != null) {
                pipeline.release();
            }
        }
        this.pipelinesByLayout.clear();
        if (this.descriptor != null) {
            this.descriptor.release();
        }
    }
}
