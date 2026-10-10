package com.metallum.render;

import com.metallum.Metallum;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PolygonMode;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
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
final class MetalCompiledRenderPipeline implements BackendRenderPipeline {
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

    record PushConstants(int metalIndex, int stageMask) {
    }

    private final List<ResourceBinding> resources;
    @Nullable
    private final PushConstants pushConstants;
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

    private boolean closed;

    MetalCompiledRenderPipeline(
            final MetalDevice device,
            final BackendRenderPipeline.CreateInfo info,
            final MetalCrossShaderCompiler.Compiled compiled
    ) {
        this.device = device;
        this.resources = compiled.resources();
        this.resourcesByName = resources.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(ResourceBinding::name, binding -> binding));

        int maxBindingIndex = -1;
        long resourceMask = 0L;
        for (ResourceBinding binding : resources) {
            maxBindingIndex = Math.max(maxBindingIndex, binding.bindingIndex());
            resourceMask |= 1L << binding.bindingIndex();
        }
        if (maxBindingIndex >= ResourceBindings.MAX_BINDINGS) {
            throw new IllegalStateException("Pipeline " + info.name() + " has binding index " + maxBindingIndex + ", limit is " + (ResourceBindings.MAX_BINDINGS - 1));
        }
        this.allResourceMask = resourceMask;
        this.pushConstants = compiled.pushConstants();

        this.firstAvailableVertexBufferSlot = firstAvailableVertexBufferSlot(resources, this.pushConstants);
        this.cullMode = info.cull() ? MTLCullMode.Back : MTLCullMode.None;
        this.fillMode = info.polygonMode() == PolygonMode.WIREFRAME ? MTLTriangleFillMode.Lines : MTLTriangleFillMode.Fill;
        this.topology = MetalConversions.primitiveType(info.primitiveTopology());
        this.triangleFan = info.primitiveTopology() == PrimitiveTopology.TRIANGLE_FAN;
        int bufferCount = 0;
        for (BackendRenderPipeline.CreateInfo.VertexBuffer vertexBuffer : info.vertexBuffers()) {
            bufferCount = Math.max(bufferCount, vertexBuffer.bufferSlot() + 1);
        }
        this.vertexBufferCount = bufferCount;

        MTLCompareFunction depthCompareOp;
        DepthStencilState depthStencilState = info.depthStencilState();
        if (depthStencilState == null) {
            depthCompareOp = MTLCompareFunction.Always;
            this.depthBiasScaleFactor = 0.0f;
            this.depthBiasConstant = 0.0f;
            this.depthStencilState = device.depthStencilState(depthCompareOp, false);
        } else {
            depthCompareOp = MetalConversions.compareFunction(depthStencilState.depthTest());
            this.depthBiasScaleFactor = depthStencilState.depthBiasScaleFactor();
            this.depthBiasConstant = depthStencilState.depthBiasConstant();
            this.depthStencilState = device.depthStencilState(depthCompareOp, depthStencilState.writeDepth());
        }

        MTLFunction vertexFunction = device.getOrCompileFunction(compiled.vertexSource(), compiled.vertexEntryPoint(), info.name() + " (vertex)");
        MTLFunction fragmentFunction = device.getOrCompileFunction(compiled.fragmentSource(), compiled.fragmentEntryPoint(), info.name() + " (fragment)");

        List<ColorTargetState> colorTargets = info.colorTargetStates();
        int layout = LAYOUT_DEPTH | 1;
        for (int i = 1; i < colorTargets.size(); i++) {
            if (colorTargets.get(i) != null) {
                layout |= 1 << i;
            }
        }
        this.fullLayout = layout;

        this.descriptor = vertexFunction == null || fragmentFunction == null ? null : buildDescriptor(device, info, vertexFunction, fragmentFunction, this.firstAvailableVertexBufferSlot);
        if (this.descriptor != null && getNativePipeline(layout) == null) {
            Metallum.LOGGER.error("[metallum] Pipeline {} failed to build", info.name());
        }
    }

    private static MTLRenderPipelineDescriptor buildDescriptor(
            final MetalDevice device,
            final BackendRenderPipeline.CreateInfo info,
            final MTLFunction vertexFunction,
            final MTLFunction fragmentFunction,
            final int firstVertexBufferSlot
    ) {
        List<ColorTargetState> colorTargets = info.colorTargetStates();
        MTLVertexDescriptor vertexDescriptor = buildVertexDescriptor(info, firstVertexBufferSlot);

        try (AutoreleasePool _ = AutoreleasePool.push()) {
            MTLRenderPipelineDescriptor pipelineDesc = MTLRenderPipelineDescriptor.alloc().init();
            if (device.useLabels()) {
                pipelineDesc.setLabel("Pipeline " + info.name());
            }
            pipelineDesc.setVertexFunction(vertexFunction);
            pipelineDesc.setFragmentFunction(fragmentFunction);
            pipelineDesc.setVertexDescriptor(vertexDescriptor);
            pipelineDesc.setDepthAttachmentPixelFormat(MTLPixelFormat.Depth32Float);
            vertexDescriptor.release();

            for (int i = 0; i < Math.max(colorTargets.size(), 1); i++) {
                ColorTargetState colorTarget = i < colorTargets.size() ? colorTargets.get(i) : null;
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

    boolean isValid() {
        return this.pipelinesByLayout.get(this.fullLayout) != null;
    }

    @Override
    public boolean isClosed() {
        return this.closed;
    }

    List<ResourceBinding> resources() {
        return this.resources;
    }

    long allResourceMask() {
        return this.allResourceMask;
    }

    @Nullable
    PushConstants pushConstants() {
        return this.pushConstants;
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
            final BackendRenderPipeline.CreateInfo info,
            final int firstMetalVertexBufferSlot
    ) {
        MTLVertexDescriptor vertexDesc = MTLVertexDescriptor.alloc().init();

        try (AutoreleasePool _ = AutoreleasePool.push()) {
            for (BackendRenderPipeline.CreateInfo.VertexBuffer vertexBuffer : info.vertexBuffers()) {
                int metalSlot = firstMetalVertexBufferSlot + vertexBuffer.bufferSlot();
                long stepRate = vertexBuffer.stepRate();
                MTLVertexBufferLayoutDescriptor layout = vertexDesc.layouts().objectAtIndexedSubscript(metalSlot);
                layout.setStride(vertexBuffer.stride());
                layout.setStepFunction(stepRate > 0 ? MTLVertexStepFunction.PerInstance : MTLVertexStepFunction.PerVertex);
                layout.setStepRate(stepRate > 0 ? stepRate : 1);
            }

            for (BackendRenderPipeline.CreateInfo.AttribBinding binding : info.attribBindings()) {
                MTLVertexFormat format = MetalConversions.vertexFormat(binding.format());
                if (format == MTLVertexFormat.Invalid) {
                    throw new IllegalStateException("Unsupported vertex attribute format: " + binding.format());
                }
                MTLVertexAttributeDescriptor attribute = vertexDesc.attributes().objectAtIndexedSubscript(binding.location());
                attribute.setFormat(format);
                attribute.setOffset(binding.offset());
                attribute.setBufferIndex(firstMetalVertexBufferSlot + binding.bufferSlot());
            }
        }

        return vertexDesc;
    }

    private static int firstAvailableVertexBufferSlot(final List<ResourceBinding> resources, @Nullable final PushConstants pushConstants) {
        int maxVertexBufferBinding = -1;
        if (pushConstants != null && (pushConstants.stageMask() & STAGE_VERTEX) != 0) {
            maxVertexBufferBinding = pushConstants.metalIndex();
        }
        for (ResourceBinding resource : resources) {
            if (resource.kind() == ResourceKind.UNIFORM_BUFFER && (resource.stageMask() & STAGE_VERTEX) != 0) {
                maxVertexBufferBinding = Math.max(maxVertexBufferBinding, resource.metalIndex());
            }
        }
        return maxVertexBufferBinding + 1;
    }

    @Override
    public void close() {
        this.closed = true;
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
