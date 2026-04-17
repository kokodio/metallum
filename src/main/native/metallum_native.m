#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#import <QuartzCore/CAMetalLayer.h>
#import <simd/simd.h>
#import <float.h>
#import <math.h>
#import <stdint.h>
#import <stdlib.h>

typedef struct {
    vector_float2 viewportSize;
} MetallumGuiUniforms;

typedef struct {
    float x;
    float y;
    float z;
    uint32_t color;
    float u;
    float v;
} MetallumGuiVertex;

static id<MTLRenderPipelineState> g_texturedPipeline = nil;
static id<MTLRenderPipelineState> g_colorPipeline = nil;
static id<MTLRenderPipelineState> g_clearPipeline = nil;
static id<MTLRenderPipelineState> g_presentPipeline = nil;
static MTLPixelFormat g_texturedPipelineFormat = MTLPixelFormatInvalid;
static MTLPixelFormat g_colorPipelineFormat = MTLPixelFormatInvalid;
static MTLPixelFormat g_clearPipelineFormat = MTLPixelFormatInvalid;
static MTLPixelFormat g_presentPipelineFormat = MTLPixelFormatInvalid;
static NSMutableDictionary<NSString *, id<MTLRenderPipelineState>> *g_dynamicPipelines = nil;
static NSMutableDictionary<NSNumber *, id<MTLDepthStencilState>> *g_depthStencilStates = nil;
static NSMutableDictionary<NSValue *, id> *g_submitTrackers = nil;
static id<MTLSamplerState> g_presentLinearSampler = nil;
static id<MTLDevice> g_presentLinearSamplerDevice = nil;
static const NSUInteger METALLUM_VERTEX_BUFFER_SLOT = 30;
static const NSUInteger METALLUM_MAX_SUBMITS_IN_FLIGHT = 2;

@interface MetallumSubmitMarker : NSObject
@property(nonatomic, strong) id<MTLCommandBuffer> commandBuffer;
@property(nonatomic, assign) uint64_t submitIndex;
@end

@implementation MetallumSubmitMarker
@end

@interface MetallumSubmitTracker : NSObject
@property(nonatomic, strong) NSCondition *condition;
@property(nonatomic, strong) NSMutableArray<MetallumSubmitMarker *> *inFlightMarkers;
@property(nonatomic, assign) uint64_t completedSubmitIndex;
@property(nonatomic, assign) uint64_t submittedSubmitIndex;
@property(nonatomic, strong) id<MTLCommandBuffer> pendingCommandBuffer;
@property(nonatomic, strong) id<CAMetalDrawable> pendingPresentDrawable;
@end

@implementation MetallumSubmitTracker

- (instancetype)init {
    self = [super init];
    if (self != nil) {
        _condition = [[NSCondition alloc] init];
        _inFlightMarkers = [[NSMutableArray alloc] init];
        _completedSubmitIndex = 1;
        _submittedSubmitIndex = 1;
        _pendingCommandBuffer = nil;
        _pendingPresentDrawable = nil;
    }
    return self;
}

@end

typedef struct {
    void *commandBuffer;
    void *encoder;
    void *device;
    void *indexBuffer;
    uint64_t indexType;
    MTLPixelFormat colorFormat;
    MTLPixelFormat depthFormat;
    MTLPixelFormat stencilFormat;
    double viewportWidth;
    double viewportHeight;
    int flipVertexY;
} MetallumRenderPassSession;

void *metallum_acquire_next_drawable(void *layerPtr);
static void metallumCommitAsync(id<MTLCommandBuffer> commandBuffer);
static void metallumKeepObjectAliveUntilCompleted(id<MTLCommandBuffer> commandBuffer, id resource);

static MTLVertexFormat metallumVertexFormatFromCode(uint64_t code) {
    switch (code) {
        case 1: return MTLVertexFormatFloat;
        case 2: return MTLVertexFormatFloat2;
        case 3: return MTLVertexFormatFloat3;
        case 4: return MTLVertexFormatFloat4;
        case 5: return MTLVertexFormatUChar4Normalized;
        case 6: return MTLVertexFormatUChar4;
        case 7: return MTLVertexFormatUShort2;
        case 8: return MTLVertexFormatUShort2Normalized;
        case 9: return MTLVertexFormatShort2;
        case 10: return MTLVertexFormatShort2Normalized;
        case 11: return MTLVertexFormatUShort4;
        case 12: return MTLVertexFormatShort4;
        case 13: return MTLVertexFormatUShort4Normalized;
        case 14: return MTLVertexFormatShort4Normalized;
        case 15: return MTLVertexFormatUInt;
        case 16: return MTLVertexFormatUInt2;
        case 17: return MTLVertexFormatUInt3;
        case 18: return MTLVertexFormatUInt4;
        case 19: return MTLVertexFormatInt;
        case 20: return MTLVertexFormatInt2;
        case 21: return MTLVertexFormatInt3;
        case 22: return MTLVertexFormatInt4;
        case 23: return MTLVertexFormatHalf;
        case 24: return MTLVertexFormatHalf2;
        case 25: return MTLVertexFormatHalf4;
        case 26: return MTLVertexFormatChar4Normalized;
        case 27: return MTLVertexFormatChar4;
        case 28: return MTLVertexFormatUChar3Normalized;
        case 29: return MTLVertexFormatChar3Normalized;
        case 30: return MTLVertexFormatUChar3;
        case 31: return MTLVertexFormatChar3;
        case 32: return MTLVertexFormatUShort3;
        case 33: return MTLVertexFormatShort3;
        case 34: return MTLVertexFormatUShort3Normalized;
        case 35: return MTLVertexFormatShort3Normalized;
        case 36: return MTLVertexFormatHalf3;
        case 37: return MTLVertexFormatUChar4Normalized_BGRA;
        default: return MTLVertexFormatInvalid;
    }
}

static NSUInteger metallumTextureSliceCount(id<MTLTexture> texture) {
    switch (texture.textureType) {
        case MTLTextureType2DArray:
            return MAX(texture.arrayLength, (NSUInteger)1);
        case MTLTextureTypeCube:
            return 6;
        case MTLTextureTypeCubeArray:
            return MAX(texture.arrayLength, (NSUInteger)1) * 6;
        default:
            return 1;
    }
}

static MetallumSubmitTracker *metallumSubmitTrackerForQueue(id<MTLCommandQueue> queue) {
    if (queue == nil) {
        return nil;
    }

    if (g_submitTrackers == nil) {
        g_submitTrackers = [[NSMutableDictionary alloc] init];
    }

    NSValue *key = [NSValue valueWithPointer:(__bridge const void *)queue];
    MetallumSubmitTracker *tracker = g_submitTrackers[key];
    if (tracker == nil) {
        tracker = [[MetallumSubmitTracker alloc] init];
        g_submitTrackers[key] = tracker;
    }
    return tracker;
}

static id<MTLCommandBuffer> metallumSubmissionCommandBufferForEncoding(id<MTLCommandQueue> queue) {
    MetallumSubmitTracker *tracker = metallumSubmitTrackerForQueue(queue);
    if (tracker == nil) {
        return nil;
    }

    [tracker.condition lock];
    if (tracker.pendingCommandBuffer == nil) {
        tracker.pendingCommandBuffer = [queue commandBuffer];
    }
    id<MTLCommandBuffer> commandBuffer = tracker.pendingCommandBuffer;
    [tracker.condition unlock];
    return commandBuffer;
}

static id<MTLCommandBuffer> metallumTakeSubmissionCommandBufferForSubmit(id<MTLCommandQueue> queue) {
    MetallumSubmitTracker *tracker = metallumSubmitTrackerForQueue(queue);
    if (tracker == nil) {
        return nil;
    }

    [tracker.condition lock];
    if (tracker.pendingCommandBuffer == nil) {
        tracker.pendingCommandBuffer = [queue commandBuffer];
    }
    id<MTLCommandBuffer> commandBuffer = tracker.pendingCommandBuffer;
    tracker.pendingCommandBuffer = nil;
    [tracker.condition unlock];
    return commandBuffer;
}

static void metallumAttachPendingPresentDrawable(id<MTLCommandQueue> queue, id<MTLCommandBuffer> commandBuffer) {
    if (queue == nil || commandBuffer == nil) {
        return;
    }

    MetallumSubmitTracker *tracker = metallumSubmitTrackerForQueue(queue);
    if (tracker == nil) {
        return;
    }

    id<CAMetalDrawable> presentDrawable = nil;
    [tracker.condition lock];
    presentDrawable = tracker.pendingPresentDrawable;
    tracker.pendingPresentDrawable = nil;
    [tracker.condition unlock];

    if (presentDrawable != nil) {
        metallumKeepObjectAliveUntilCompleted(commandBuffer, presentDrawable);
        [commandBuffer presentDrawable:presentDrawable];
    }
}

static int metallumQueueDrawablePresent(id<MTLCommandQueue> queue, id<CAMetalDrawable> drawable) {
    if (queue == nil || drawable == nil) {
        return 1;
    }

    MetallumSubmitTracker *tracker = metallumSubmitTrackerForQueue(queue);
    if (tracker == nil) {
        return 1;
    }

    [tracker.condition lock];
    if (tracker.pendingCommandBuffer != nil) {
        tracker.pendingPresentDrawable = drawable;
        [tracker.condition unlock];
        return 0;
    }
    [tracker.condition unlock];

    id<MTLCommandBuffer> commandBuffer = [queue commandBuffer];
    if (commandBuffer == nil) {
        return 1;
    }

    metallumKeepObjectAliveUntilCompleted(commandBuffer, drawable);
    [commandBuffer presentDrawable:drawable];
    metallumCommitAsync(commandBuffer);
    return 0;
}

static MTLPrimitiveType metallumPrimitiveTypeFromCode(uint64_t code) {
    switch (code) {
        case 0: return MTLPrimitiveTypeTriangle;
        case 1: return MTLPrimitiveTypeTriangleStrip;
        case 2: return MTLPrimitiveTypeLine;
        case 3: return MTLPrimitiveTypeLineStrip;
        case 4: return MTLPrimitiveTypePoint;
        default: return MTLPrimitiveTypeTriangle;
    }
}

static MTLBlendFactor metallumBlendFactorFromCode(uint64_t code) {
    switch (code) {
        case 0: return MTLBlendFactorZero;
        case 1: return MTLBlendFactorOne;
        case 2: return MTLBlendFactorSourceColor;
        case 3: return MTLBlendFactorOneMinusSourceColor;
        case 4: return MTLBlendFactorSourceAlpha;
        case 5: return MTLBlendFactorOneMinusSourceAlpha;
        case 6: return MTLBlendFactorDestinationColor;
        case 7: return MTLBlendFactorOneMinusDestinationColor;
        case 8: return MTLBlendFactorDestinationAlpha;
        case 9: return MTLBlendFactorOneMinusDestinationAlpha;
        case 10: return MTLBlendFactorSourceAlphaSaturated;
        case 11: return MTLBlendFactorBlendColor;
        case 12: return MTLBlendFactorOneMinusBlendColor;
        case 13: return MTLBlendFactorBlendAlpha;
        case 14: return MTLBlendFactorOneMinusBlendAlpha;
        default: return MTLBlendFactorOne;
    }
}

static MTLBlendOperation metallumBlendOpFromCode(uint64_t code) {
    switch (code) {
        case 0: return MTLBlendOperationAdd;
        case 1: return MTLBlendOperationSubtract;
        case 2: return MTLBlendOperationReverseSubtract;
        case 3: return MTLBlendOperationMin;
        case 4: return MTLBlendOperationMax;
        default: return MTLBlendOperationAdd;
    }
}

static MTLCompareFunction metallumCompareFunctionFromCode(uint64_t code) {
    switch (code) {
        case 1: return MTLCompareFunctionAlways;
        case 2: return MTLCompareFunctionLess;
        case 3: return MTLCompareFunctionLessEqual;
        case 4: return MTLCompareFunctionEqual;
        case 5: return MTLCompareFunctionNotEqual;
        case 6: return MTLCompareFunctionGreaterEqual;
        case 7: return MTLCompareFunctionGreater;
        case 8: return MTLCompareFunctionNever;
        default: return MTLCompareFunctionAlways;
    }
}

static MTLPixelFormat metallumStencilPixelFormatForDepthFormat(MTLPixelFormat depthFormat) {
    switch (depthFormat) {
        case MTLPixelFormatDepth24Unorm_Stencil8:
        case MTLPixelFormatDepth32Float_Stencil8:
            return depthFormat;
        default:
            return MTLPixelFormatInvalid;
    }
}

static id<MTLDepthStencilState> metallumEnsureDepthStencilState(
    id<MTLDevice> device,
    uint64_t compareOp,
    int writeDepth
) {
    if (g_depthStencilStates == nil) {
        g_depthStencilStates = [[NSMutableDictionary alloc] init];
    }

    uint64_t packedKey = (compareOp << 1) | (uint64_t)(writeDepth != 0 ? 1 : 0);
    NSNumber *key = [NSNumber numberWithUnsignedLongLong:packedKey];
    id<MTLDepthStencilState> cached = g_depthStencilStates[key];
    if (cached != nil) {
        return cached;
    }

    MTLDepthStencilDescriptor *descriptor = [MTLDepthStencilDescriptor new];
    descriptor.depthCompareFunction = metallumCompareFunctionFromCode(compareOp);
    descriptor.depthWriteEnabled = writeDepth != 0;

    id<MTLDepthStencilState> state = [device newDepthStencilStateWithDescriptor:descriptor];
    if (state == nil) {
        return nil;
    }

    g_depthStencilStates[key] = state;
    return state;
}

static NSString *metallumDynamicPipelineKey(
    NSString *vertexSource,
    NSString *fragmentSource,
    NSString *vertexEntry,
    NSString *fragmentEntry,
    MTLPixelFormat colorFormat,
    MTLPixelFormat depthFormat,
    MTLPixelFormat stencilFormat,
    uint64_t vertexStride,
    const uint64_t *vertexAttributeFormats,
    const uint64_t *vertexAttributeOffsets,
    uint64_t vertexAttributeCount,
    int blendEnabled,
    uint64_t blendSourceRgb,
    uint64_t blendDestRgb,
    uint64_t blendOpRgb,
    uint64_t blendSourceAlpha,
    uint64_t blendDestAlpha,
    uint64_t blendOpAlpha,
    uint64_t writeMask
) {
    NSMutableString *key = [NSMutableString stringWithFormat:@"%lu|%lu|%@|%@|%u|%u|%u|%llu|%d|%llu|%llu|%llu|%llu|%llu|%llu|%llu",
                            (unsigned long)vertexSource.hash,
                            (unsigned long)fragmentSource.hash,
                            vertexEntry,
                            fragmentEntry,
                            (unsigned int)colorFormat,
                            (unsigned int)depthFormat,
                            (unsigned int)stencilFormat,
                            (unsigned long long)vertexStride,
                            blendEnabled,
                            (unsigned long long)blendSourceRgb,
                            (unsigned long long)blendDestRgb,
                            (unsigned long long)blendOpRgb,
                            (unsigned long long)blendSourceAlpha,
                            (unsigned long long)blendDestAlpha,
                            (unsigned long long)blendOpAlpha,
                            (unsigned long long)writeMask];
    for (uint64_t i = 0; i < vertexAttributeCount; i++) {
        [key appendFormat:@"|%llu:%llu", (unsigned long long)vertexAttributeFormats[i], (unsigned long long)vertexAttributeOffsets[i]];
    }
    return key;
}

static id<MTLRenderPipelineState> metallumEnsureDynamicPipeline(
    id<MTLDevice> device,
    NSString *vertexSource,
    NSString *fragmentSource,
    NSString *vertexEntry,
    NSString *fragmentEntry,
    MTLPixelFormat colorFormat,
    MTLPixelFormat depthFormat,
    MTLPixelFormat stencilFormat,
    uint64_t vertexStride,
    const uint64_t *vertexAttributeFormats,
    const uint64_t *vertexAttributeOffsets,
    uint64_t vertexAttributeCount,
    int blendEnabled,
    uint64_t blendSourceRgb,
    uint64_t blendDestRgb,
    uint64_t blendOpRgb,
    uint64_t blendSourceAlpha,
    uint64_t blendDestAlpha,
    uint64_t blendOpAlpha,
    uint64_t writeMask
) {
    if (g_dynamicPipelines == nil) {
        g_dynamicPipelines = [[NSMutableDictionary alloc] init];
    }

    NSString *key = metallumDynamicPipelineKey(
        vertexSource,
        fragmentSource,
        vertexEntry,
        fragmentEntry,
        colorFormat,
        depthFormat,
        stencilFormat,
        vertexStride,
        vertexAttributeFormats,
        vertexAttributeOffsets,
        vertexAttributeCount,
        blendEnabled,
        blendSourceRgb,
        blendDestRgb,
        blendOpRgb,
        blendSourceAlpha,
        blendDestAlpha,
        blendOpAlpha,
        writeMask
    );

    id<MTLRenderPipelineState> cached = g_dynamicPipelines[key];
    if (cached != nil) {
        return cached;
    }

    NSError *error = nil;
    id<MTLLibrary> vertexLibrary = [device newLibraryWithSource:vertexSource options:nil error:&error];
    if (vertexLibrary == nil) {
        NSLog(@"[metallum] Failed to compile vertex MSL for pipeline: %@", error);
        return nil;
    }
    id<MTLLibrary> fragmentLibrary = [device newLibraryWithSource:fragmentSource options:nil error:&error];
    if (fragmentLibrary == nil) {
        NSLog(@"[metallum] Failed to compile fragment MSL for pipeline: %@", error);
        return nil;
    }

    id<MTLFunction> vertexFunction = [vertexLibrary newFunctionWithName:vertexEntry];
    id<MTLFunction> fragmentFunction = [fragmentLibrary newFunctionWithName:fragmentEntry];
    if (vertexFunction == nil || fragmentFunction == nil) {
        NSLog(@"[metallum] Failed to resolve MSL entry points v='%@' f='%@'", vertexEntry, fragmentEntry);
        return nil;
    }

    MTLRenderPipelineDescriptor *descriptor = [MTLRenderPipelineDescriptor new];
    descriptor.vertexFunction = vertexFunction;
    descriptor.fragmentFunction = fragmentFunction;
    descriptor.colorAttachments[0].pixelFormat = colorFormat;
    descriptor.depthAttachmentPixelFormat = depthFormat;
    descriptor.stencilAttachmentPixelFormat = stencilFormat;
    descriptor.colorAttachments[0].writeMask = (MTLColorWriteMask)writeMask;
    if (blendEnabled != 0) {
        descriptor.colorAttachments[0].blendingEnabled = YES;
        descriptor.colorAttachments[0].sourceRGBBlendFactor = metallumBlendFactorFromCode(blendSourceRgb);
        descriptor.colorAttachments[0].destinationRGBBlendFactor = metallumBlendFactorFromCode(blendDestRgb);
        descriptor.colorAttachments[0].rgbBlendOperation = metallumBlendOpFromCode(blendOpRgb);
        descriptor.colorAttachments[0].sourceAlphaBlendFactor = metallumBlendFactorFromCode(blendSourceAlpha);
        descriptor.colorAttachments[0].destinationAlphaBlendFactor = metallumBlendFactorFromCode(blendDestAlpha);
        descriptor.colorAttachments[0].alphaBlendOperation = metallumBlendOpFromCode(blendOpAlpha);
    } else {
        descriptor.colorAttachments[0].blendingEnabled = NO;
    }

    if (vertexAttributeCount > 0) {
        MTLVertexDescriptor *vertexDescriptor = [MTLVertexDescriptor vertexDescriptor];
        for (uint64_t i = 0; i < vertexAttributeCount; i++) {
            MTLVertexFormat format = metallumVertexFormatFromCode(vertexAttributeFormats[i]);
            if (format == MTLVertexFormatInvalid) {
                NSLog(@"[metallum] Unsupported vertex attribute format code: %llu", (unsigned long long)vertexAttributeFormats[i]);
                return nil;
            }
            vertexDescriptor.attributes[(NSUInteger)i].format = format;
            vertexDescriptor.attributes[(NSUInteger)i].offset = (NSUInteger)vertexAttributeOffsets[i];
            vertexDescriptor.attributes[(NSUInteger)i].bufferIndex = METALLUM_VERTEX_BUFFER_SLOT;
        }
        vertexDescriptor.layouts[METALLUM_VERTEX_BUFFER_SLOT].stride = (NSUInteger)vertexStride;
        vertexDescriptor.layouts[METALLUM_VERTEX_BUFFER_SLOT].stepFunction = MTLVertexStepFunctionPerVertex;
        descriptor.vertexDescriptor = vertexDescriptor;
    }

    id<MTLRenderPipelineState> pipeline = [device newRenderPipelineStateWithDescriptor:descriptor error:&error];
    if (pipeline == nil) {
        NSLog(@"[metallum] Failed to create dynamic render pipeline: %@", error);
        return nil;
    }

    g_dynamicPipelines[key] = pipeline;
    return pipeline;
}

static void metallumDestroyRenderPassSession(MetallumRenderPassSession *session) {
    if (session == NULL) {
        return;
    }

    if (session->encoder != NULL) {
        CFRelease(session->encoder);
    }
    if (session->commandBuffer != NULL) {
        CFRelease(session->commandBuffer);
    }
    free(session);
}

static void metallumCommitAsync(id<MTLCommandBuffer> commandBuffer) {
    if (commandBuffer == nil) {
        return;
    }

    [commandBuffer commit];
}

static void metallumKeepObjectAliveUntilCompleted(id<MTLCommandBuffer> commandBuffer, id resource) {
    if (commandBuffer == nil || resource == nil) {
        return;
    }

    [commandBuffer addCompletedHandler:^(id<MTLCommandBuffer> completedBuffer) {
        (void)completedBuffer;
        (void)resource;
    }];
}

static int metallumSignalSubmit(id<MTLCommandQueue> queue, uint64_t submitIndex) {
    @autoreleasepool {
        if (queue == nil) {
            return 1;
        }

        MetallumSubmitTracker *tracker = metallumSubmitTrackerForQueue(queue);
        if (tracker == nil) {
            return 1;
        }

        while (true) {
            id<MTLCommandBuffer> oldestCommandBuffer = nil;
            [tracker.condition lock];
            if (tracker.inFlightMarkers.count < METALLUM_MAX_SUBMITS_IN_FLIGHT) {
                [tracker.condition unlock];
                break;
            }

            oldestCommandBuffer = tracker.inFlightMarkers.firstObject.commandBuffer;
            [tracker.condition unlock];
            [oldestCommandBuffer waitUntilCompleted];
        }

        id<MTLCommandBuffer> commandBuffer = metallumTakeSubmissionCommandBufferForSubmit(queue);
        if (commandBuffer == nil) {
            return 1;
        }

        metallumAttachPendingPresentDrawable(queue, commandBuffer);

        MetallumSubmitMarker *marker = [[MetallumSubmitMarker alloc] init];
        marker.commandBuffer = commandBuffer;
        marker.submitIndex = submitIndex;

        [tracker.condition lock];
        tracker.submittedSubmitIndex = MAX(tracker.submittedSubmitIndex, submitIndex);
        [tracker.inFlightMarkers addObject:marker];
        [tracker.condition unlock];

        [commandBuffer addCompletedHandler:^(id<MTLCommandBuffer> completedBuffer) {
            (void)completedBuffer;
            [tracker.condition lock];
            tracker.completedSubmitIndex = MAX(tracker.completedSubmitIndex, submitIndex);
            [tracker.inFlightMarkers removeObjectIdenticalTo:marker];
            [tracker.condition broadcast];
            [tracker.condition unlock];
        }];

        [commandBuffer commit];
        return 0;
    }
}

static int metallumWaitForSubmitCompletion(id<MTLCommandQueue> queue, uint64_t submitIndex, uint64_t timeoutMs) {
    @autoreleasepool {
        if (queue == nil) {
            return 2;
        }

        MetallumSubmitTracker *tracker = metallumSubmitTrackerForQueue(queue);
        if (tracker == nil) {
            return 2;
        }

        if (submitIndex <= 1) {
            return 0;
        }

        [tracker.condition lock];
        if (tracker.completedSubmitIndex >= submitIndex) {
            [tracker.condition unlock];
            return 0;
        }

        if (timeoutMs == 0) {
            [tracker.condition unlock];
            return 1;
        }

        NSDate *deadline = [NSDate dateWithTimeIntervalSinceNow:(NSTimeInterval)timeoutMs / 1000.0];
        while (tracker.completedSubmitIndex < submitIndex) {
            if (![tracker.condition waitUntilDate:deadline]) {
                int result = tracker.completedSubmitIndex >= submitIndex ? 0 : 1;
                [tracker.condition unlock];
                return result;
            }
        }

        [tracker.condition unlock];
        return 0;
    }
}

static NSString *metallumGuiMslSource(void) {
    return @"#include <metal_stdlib>\n"
           "using namespace metal;\n"
           "struct VertexIn {\n"
           "  float3 position [[attribute(0)]];\n"
           "  float4 color [[attribute(1)]];\n"
           "  float2 uv [[attribute(2)]];\n"
           "};\n"
           "struct GuiUniforms {\n"
           "  float2 viewportSize;\n"
           "};\n"
           "struct VertexOut {\n"
           "  float4 position [[position]];\n"
           "  float4 color;\n"
           "  float2 uv;\n"
           "};\n"
           "vertex VertexOut metallum_gui_vs(VertexIn in [[stage_in]], constant GuiUniforms& u [[buffer(1)]]) {\n"
           "  VertexOut out;\n"
           "  float x = (in.position.x / max(u.viewportSize.x, 1.0)) * 2.0 - 1.0;\n"
           "  float y = 1.0 - (in.position.y / max(u.viewportSize.y, 1.0)) * 2.0;\n"
           "  out.position = float4(x, y, in.position.z, 1.0);\n"
           "  out.color = in.color;\n"
           "  out.uv = in.uv;\n"
           "  return out;\n"
           "}\n"
           "fragment float4 metallum_gui_fs_textured(VertexOut in [[stage_in]], texture2d<float> tex [[texture(0)]], sampler smp [[sampler(0)]]) {\n"
           "  return tex.sample(smp, in.uv) * in.color;\n"
           "}\n"
           "fragment float4 metallum_gui_fs_color(VertexOut in [[stage_in]]) {\n"
           "  return in.color;\n"
           "}\n";
}

static inline MetallumGuiVertex metallum_vertex(float x, float y, float z, float u, float v) {
	MetallumGuiVertex out;
	out.x = x;
    out.y = y;
    out.z = z;
    out.color = 0xFFFFFFFFu;
    out.u = u;
	out.v = v;
	return out;
}

static inline uint32_t metallum_color_from_argb(int argb) {
    uint32_t red = (uint32_t)((argb >> 16) & 0xFF);
    uint32_t green = (uint32_t)((argb >> 8) & 0xFF);
    uint32_t blue = (uint32_t)(argb & 0xFF);
    uint32_t alpha = (uint32_t)((argb >> 24) & 0xFF);
    return red | (green << 8) | (blue << 16) | (alpha << 24);
}

static id<MTLSamplerState> metallumEnsurePresentLinearSampler(id<MTLDevice> device) {
    if (g_presentLinearSampler != nil && g_presentLinearSamplerDevice == device) {
        return g_presentLinearSampler;
    }

    MTLSamplerDescriptor *descriptor = [MTLSamplerDescriptor new];
    descriptor.minFilter = MTLSamplerMinMagFilterLinear;
    descriptor.magFilter = MTLSamplerMinMagFilterLinear;
    descriptor.mipFilter = MTLSamplerMipFilterNotMipmapped;
    descriptor.sAddressMode = MTLSamplerAddressModeClampToEdge;
    descriptor.tAddressMode = MTLSamplerAddressModeClampToEdge;

    g_presentLinearSampler = [device newSamplerStateWithDescriptor:descriptor];
    g_presentLinearSamplerDevice = device;
    return g_presentLinearSampler;
}

static id<MTLRenderPipelineState> metallumBuildGuiPipeline(id<MTLDevice> device, BOOL textured, MTLPixelFormat colorFormat) {
    NSError *error = nil;
    id<MTLLibrary> library = [device newLibraryWithSource:metallumGuiMslSource() options:nil error:&error];
    if (library == nil) {
        NSLog(@"[metallum] Failed to compile GUI MSL: %@", error);
        return nil;
    }

    id<MTLFunction> vertexFunction = [library newFunctionWithName:@"metallum_gui_vs"];
    id<MTLFunction> fragmentFunction = [library newFunctionWithName:(textured ? @"metallum_gui_fs_textured" : @"metallum_gui_fs_color")];
    if (vertexFunction == nil || fragmentFunction == nil) {
        NSLog(@"[metallum] Failed to create GUI shader functions");
        return nil;
    }

    MTLRenderPipelineDescriptor *descriptor = [MTLRenderPipelineDescriptor new];
    descriptor.vertexFunction = vertexFunction;
    descriptor.fragmentFunction = fragmentFunction;
    descriptor.colorAttachments[0].pixelFormat = colorFormat;
    descriptor.colorAttachments[0].blendingEnabled = YES;
    descriptor.colorAttachments[0].rgbBlendOperation = MTLBlendOperationAdd;
    descriptor.colorAttachments[0].alphaBlendOperation = MTLBlendOperationAdd;
    descriptor.colorAttachments[0].sourceRGBBlendFactor = MTLBlendFactorSourceAlpha;
    descriptor.colorAttachments[0].sourceAlphaBlendFactor = MTLBlendFactorSourceAlpha;
    descriptor.colorAttachments[0].destinationRGBBlendFactor = MTLBlendFactorOneMinusSourceAlpha;
    descriptor.colorAttachments[0].destinationAlphaBlendFactor = MTLBlendFactorOneMinusSourceAlpha;

    MTLVertexDescriptor *vertexDescriptor = [MTLVertexDescriptor vertexDescriptor];
    vertexDescriptor.attributes[0].format = MTLVertexFormatFloat3;
    vertexDescriptor.attributes[0].offset = 0;
    vertexDescriptor.attributes[0].bufferIndex = 0;
    vertexDescriptor.attributes[1].format = MTLVertexFormatUChar4Normalized;
    vertexDescriptor.attributes[1].offset = 12;
    vertexDescriptor.attributes[1].bufferIndex = 0;
    vertexDescriptor.attributes[2].format = MTLVertexFormatFloat2;
    vertexDescriptor.attributes[2].offset = 16;
    vertexDescriptor.attributes[2].bufferIndex = 0;
    vertexDescriptor.layouts[0].stride = 24;
    vertexDescriptor.layouts[0].stepFunction = MTLVertexStepFunctionPerVertex;
    descriptor.vertexDescriptor = vertexDescriptor;

    id<MTLRenderPipelineState> pipeline = [device newRenderPipelineStateWithDescriptor:descriptor error:&error];
    if (pipeline == nil) {
        NSLog(@"[metallum] Failed to create GUI render pipeline: %@", error);
        return nil;
    }

    return pipeline;
}

static id<MTLRenderPipelineState> metallumBuildClearPipeline(id<MTLDevice> device, MTLPixelFormat colorFormat) {
    NSError *error = nil;
    id<MTLLibrary> library = [device newLibraryWithSource:metallumGuiMslSource() options:nil error:&error];
    if (library == nil) {
        NSLog(@"[metallum] Failed to compile clear MSL: %@", error);
        return nil;
    }

    id<MTLFunction> vertexFunction = [library newFunctionWithName:@"metallum_gui_vs"];
    id<MTLFunction> fragmentFunction = [library newFunctionWithName:@"metallum_gui_fs_color"];
    if (vertexFunction == nil || fragmentFunction == nil) {
        NSLog(@"[metallum] Failed to create clear shader functions");
        return nil;
    }

    MTLRenderPipelineDescriptor *descriptor = [MTLRenderPipelineDescriptor new];
    descriptor.vertexFunction = vertexFunction;
    descriptor.fragmentFunction = fragmentFunction;
    descriptor.colorAttachments[0].pixelFormat = colorFormat;
    descriptor.colorAttachments[0].blendingEnabled = NO;

    MTLVertexDescriptor *vertexDescriptor = [MTLVertexDescriptor vertexDescriptor];
    vertexDescriptor.attributes[0].format = MTLVertexFormatFloat3;
    vertexDescriptor.attributes[0].offset = 0;
    vertexDescriptor.attributes[0].bufferIndex = 0;
    vertexDescriptor.attributes[1].format = MTLVertexFormatUChar4Normalized;
    vertexDescriptor.attributes[1].offset = 12;
    vertexDescriptor.attributes[1].bufferIndex = 0;
    vertexDescriptor.attributes[2].format = MTLVertexFormatFloat2;
    vertexDescriptor.attributes[2].offset = 16;
    vertexDescriptor.attributes[2].bufferIndex = 0;
    vertexDescriptor.layouts[0].stride = 24;
    vertexDescriptor.layouts[0].stepFunction = MTLVertexStepFunctionPerVertex;
    descriptor.vertexDescriptor = vertexDescriptor;

    id<MTLRenderPipelineState> pipeline = [device newRenderPipelineStateWithDescriptor:descriptor error:&error];
    if (pipeline == nil) {
        NSLog(@"[metallum] Failed to create clear render pipeline: %@", error);
        return nil;
    }

    return pipeline;
}

static id<MTLRenderPipelineState> metallumBuildPresentPipeline(id<MTLDevice> device, MTLPixelFormat colorFormat) {
    NSError *error = nil;
    id<MTLLibrary> library = [device newLibraryWithSource:metallumGuiMslSource() options:nil error:&error];
    if (library == nil) {
        NSLog(@"[metallum] Failed to compile present MSL: %@", error);
        return nil;
    }

    id<MTLFunction> vertexFunction = [library newFunctionWithName:@"metallum_gui_vs"];
    id<MTLFunction> fragmentFunction = [library newFunctionWithName:@"metallum_gui_fs_textured"];
    if (vertexFunction == nil || fragmentFunction == nil) {
        NSLog(@"[metallum] Failed to create present shader functions");
        return nil;
    }

    MTLRenderPipelineDescriptor *descriptor = [MTLRenderPipelineDescriptor new];
    descriptor.vertexFunction = vertexFunction;
    descriptor.fragmentFunction = fragmentFunction;
    descriptor.colorAttachments[0].pixelFormat = colorFormat;
    descriptor.colorAttachments[0].blendingEnabled = NO;

    MTLVertexDescriptor *vertexDescriptor = [MTLVertexDescriptor vertexDescriptor];
    vertexDescriptor.attributes[0].format = MTLVertexFormatFloat3;
    vertexDescriptor.attributes[0].offset = 0;
    vertexDescriptor.attributes[0].bufferIndex = 0;
    vertexDescriptor.attributes[1].format = MTLVertexFormatUChar4Normalized;
    vertexDescriptor.attributes[1].offset = 12;
    vertexDescriptor.attributes[1].bufferIndex = 0;
    vertexDescriptor.attributes[2].format = MTLVertexFormatFloat2;
    vertexDescriptor.attributes[2].offset = 16;
    vertexDescriptor.attributes[2].bufferIndex = 0;
    vertexDescriptor.layouts[0].stride = 24;
    vertexDescriptor.layouts[0].stepFunction = MTLVertexStepFunctionPerVertex;
    descriptor.vertexDescriptor = vertexDescriptor;

    id<MTLRenderPipelineState> pipeline = [device newRenderPipelineStateWithDescriptor:descriptor error:&error];
    if (pipeline == nil) {
        NSLog(@"[metallum] Failed to create present render pipeline: %@", error);
        return nil;
    }

    return pipeline;
}

static id<MTLRenderPipelineState> metallumEnsureClearPipeline(id<MTLDevice> device, MTLPixelFormat colorFormat) {
    if (g_clearPipeline == nil || g_clearPipeline.device != device || g_clearPipelineFormat != colorFormat) {
        g_clearPipeline = metallumBuildClearPipeline(device, colorFormat);
        g_clearPipelineFormat = colorFormat;
    }
    return g_clearPipeline;
}

static id<MTLRenderPipelineState> metallumEnsurePresentPipeline(id<MTLDevice> device, MTLPixelFormat colorFormat) {
    if (g_presentPipeline == nil || g_presentPipeline.device != device || g_presentPipelineFormat != colorFormat) {
        g_presentPipeline = metallumBuildPresentPipeline(device, colorFormat);
        g_presentPipelineFormat = colorFormat;
    }
    return g_presentPipeline;
}

static id<MTLRenderPipelineState> metallumEnsureGuiPipeline(id<MTLDevice> device, BOOL textured, MTLPixelFormat colorFormat) {
    if (textured) {
        if (g_texturedPipeline == nil || g_texturedPipeline.device != device || g_texturedPipelineFormat != colorFormat) {
            g_texturedPipeline = metallumBuildGuiPipeline(device, YES, colorFormat);
            g_texturedPipelineFormat = colorFormat;
        }
        return g_texturedPipeline;
    }

    if (g_colorPipeline == nil || g_colorPipeline.device != device || g_colorPipelineFormat != colorFormat) {
        g_colorPipeline = metallumBuildGuiPipeline(device, NO, colorFormat);
        g_colorPipelineFormat = colorFormat;
    }
    return g_colorPipeline;
}

void *metallum_create_system_default_device(void) {
    @autoreleasepool {
        return (__bridge_retained void *)MTLCreateSystemDefaultDevice();
    }
}

void *metallum_create_command_queue(void *devicePtr) {
    @autoreleasepool {
        id<MTLDevice> device = (__bridge id<MTLDevice>)devicePtr;
        if (device == nil) {
            return NULL;
        }
        return (__bridge_retained void *)[device newCommandQueue];
    }
}

void *metallum_create_buffer(void *devicePtr, uint64_t length, uint64_t options) {
    @autoreleasepool {
        id<MTLDevice> device = (__bridge id<MTLDevice>)devicePtr;
        if (device == nil) {
            return NULL;
        }
        return (__bridge_retained void *)[device newBufferWithLength:length options:(MTLResourceOptions)options];
    }
}

void *metallum_create_texture_2d(
    void *devicePtr,
    uint64_t pixelFormat,
    uint64_t width,
    uint64_t height,
    uint64_t depthOrLayers,
    uint64_t mipLevels,
    uint64_t cubeCompatible,
    uint64_t usage,
    uint64_t storageMode
) {
    @autoreleasepool {
        id<MTLDevice> device = (__bridge id<MTLDevice>)devicePtr;
        if (device == nil) {
            return NULL;
        }
        BOOL mipmapped = mipLevels > 1;
        MTLTextureDescriptor *descriptor = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:(MTLPixelFormat)pixelFormat
                                                                                               width:width
                                                                                              height:height
                                                                                           mipmapped:mipmapped];
        if (cubeCompatible != 0) {
            if (depthOrLayers > 6) {
                descriptor.textureType = MTLTextureTypeCubeArray;
                descriptor.arrayLength = depthOrLayers;
            } else {
                descriptor.textureType = MTLTextureTypeCube;
                descriptor.arrayLength = 1;
            }
        } else if (depthOrLayers > 1) {
            descriptor.textureType = MTLTextureType2DArray;
            descriptor.arrayLength = depthOrLayers;
        }
        descriptor.mipmapLevelCount = MAX((NSUInteger)1, (NSUInteger)mipLevels);
        descriptor.usage = (MTLTextureUsage)usage;
        descriptor.storageMode = (MTLStorageMode)storageMode;
        return (__bridge_retained void *)[device newTextureWithDescriptor:descriptor];
    }
}

void *metallum_create_texture_view(void *texturePtr, uint64_t baseMipLevel, uint64_t mipLevelCount) {
    @autoreleasepool {
        id<MTLTexture> texture = (__bridge id<MTLTexture>)texturePtr;
        if (texture == nil || mipLevelCount == 0) {
            return NULL;
        }

        NSUInteger baseLevel = (NSUInteger)baseMipLevel;
        NSUInteger levelCount = (NSUInteger)mipLevelCount;
        if (baseLevel >= texture.mipmapLevelCount || baseLevel + levelCount > texture.mipmapLevelCount) {
            return NULL;
        }

        NSRange levels = NSMakeRange(baseLevel, levelCount);
        NSRange slices = NSMakeRange(0, metallumTextureSliceCount(texture));
        id<MTLTexture> view = [texture newTextureViewWithPixelFormat:texture.pixelFormat
                                                         textureType:texture.textureType
                                                              levels:levels
                                                              slices:slices];
        return view == nil ? NULL : (__bridge_retained void *)view;
	}
}

void *metallum_create_buffer_texture_view(
    void *bufferPtr,
    uint64_t pixelFormat,
    uint64_t offset,
    uint64_t width,
    uint64_t height,
    uint64_t bytesPerRow
) {
    @autoreleasepool {
        id<MTLBuffer> buffer = (__bridge id<MTLBuffer>)bufferPtr;
        if (buffer == nil || width == 0 || height == 0 || bytesPerRow == 0) {
            return NULL;
        }

        MTLTextureDescriptor *descriptor = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:(MTLPixelFormat)pixelFormat
                                                                                               width:(NSUInteger)width
                                                                                              height:(NSUInteger)height
                                                                                           mipmapped:NO];
        descriptor.usage = MTLTextureUsageShaderRead;
        descriptor.storageMode = buffer.storageMode;

        NSUInteger nativeOffset = (NSUInteger)offset;
        NSUInteger nativeBytesPerRow = (NSUInteger)bytesPerRow;
        if (nativeOffset + nativeBytesPerRow * (NSUInteger)height > buffer.length) {
            return NULL;
        }

        id<MTLTexture> texture = [buffer newTextureWithDescriptor:descriptor
                                                           offset:nativeOffset
                                                      bytesPerRow:nativeBytesPerRow];
        return texture == nil ? NULL : (__bridge_retained void *)texture;
    }
}

int metallum_upload_buffer_region_async(
    void *commandQueuePtr,
    void *destinationBufferPtr,
    uint64_t destinationOffset,
    const void *bytes,
    uint64_t length
) {
    @autoreleasepool {
        id<MTLCommandQueue> queue = (__bridge id<MTLCommandQueue>)commandQueuePtr;
        id<MTLBuffer> destinationBuffer = (__bridge id<MTLBuffer>)destinationBufferPtr;
        if (queue == nil || destinationBuffer == nil || bytes == NULL || length == 0) {
            return 1;
        }

        id<MTLBuffer> stagingBuffer = [queue.device newBufferWithBytes:bytes
                                                                length:(NSUInteger)length
                                                               options:MTLResourceStorageModeShared];
        if (stagingBuffer == nil) {
            return 1;
        }

        id<MTLCommandBuffer> commandBuffer = metallumSubmissionCommandBufferForEncoding(queue);
        if (commandBuffer == nil) {
            return 1;
        }

        id<MTLBlitCommandEncoder> blit = [commandBuffer blitCommandEncoder];
        if (blit == nil) {
            return 1;
        }

        [blit copyFromBuffer:stagingBuffer
                sourceOffset:0
                    toBuffer:destinationBuffer
           destinationOffset:(NSUInteger)destinationOffset
                        size:(NSUInteger)length];
        [blit endEncoding];
        metallumKeepObjectAliveUntilCompleted(commandBuffer, stagingBuffer);
        metallumKeepObjectAliveUntilCompleted(commandBuffer, destinationBuffer);
        return 0;
    }
}

int metallum_copy_buffer_to_buffer(
    void *commandQueuePtr,
    void *sourceBufferPtr,
    uint64_t sourceOffset,
    void *destinationBufferPtr,
    uint64_t destinationOffset,
    uint64_t length
) {
    @autoreleasepool {
        id<MTLCommandQueue> queue = (__bridge id<MTLCommandQueue>)commandQueuePtr;
        id<MTLBuffer> sourceBuffer = (__bridge id<MTLBuffer>)sourceBufferPtr;
        id<MTLBuffer> destinationBuffer = (__bridge id<MTLBuffer>)destinationBufferPtr;
        if (queue == nil || sourceBuffer == nil || destinationBuffer == nil || length == 0) {
            return 1;
        }

        id<MTLCommandBuffer> commandBuffer = metallumSubmissionCommandBufferForEncoding(queue);
        if (commandBuffer == nil) {
            return 1;
        }

        id<MTLBlitCommandEncoder> blit = [commandBuffer blitCommandEncoder];
        if (blit == nil) {
            return 1;
        }

        [blit copyFromBuffer:sourceBuffer
                sourceOffset:(NSUInteger)sourceOffset
                    toBuffer:destinationBuffer
           destinationOffset:(NSUInteger)destinationOffset
                        size:(NSUInteger)length];
        [blit endEncoding];
        metallumKeepObjectAliveUntilCompleted(commandBuffer, sourceBuffer);
        metallumKeepObjectAliveUntilCompleted(commandBuffer, destinationBuffer);
        return 0;
    }
}

int metallum_copy_buffer_to_texture(
    void *commandQueuePtr,
    void *sourceBufferPtr,
    uint64_t sourceOffset,
    void *texturePtr,
    uint64_t mipLevel,
    uint64_t slice,
    uint64_t x,
    uint64_t y,
    uint64_t width,
    uint64_t height,
    uint64_t bytesPerRow,
    uint64_t bytesPerImage
) {
    @autoreleasepool {
        id<MTLCommandQueue> queue = (__bridge id<MTLCommandQueue>)commandQueuePtr;
        id<MTLBuffer> sourceBuffer = (__bridge id<MTLBuffer>)sourceBufferPtr;
        id<MTLTexture> texture = (__bridge id<MTLTexture>)texturePtr;
        if (queue == nil || sourceBuffer == nil || texture == nil || width == 0 || height == 0) {
            return 1;
        }

        id<MTLCommandBuffer> commandBuffer = metallumSubmissionCommandBufferForEncoding(queue);
        if (commandBuffer == nil) {
            return 1;
        }

        id<MTLBlitCommandEncoder> blit = [commandBuffer blitCommandEncoder];
        if (blit == nil) {
            return 1;
        }

        [blit copyFromBuffer:sourceBuffer
                sourceOffset:(NSUInteger)sourceOffset
           sourceBytesPerRow:(NSUInteger)bytesPerRow
         sourceBytesPerImage:(NSUInteger)bytesPerImage
                  sourceSize:MTLSizeMake((NSUInteger)width, (NSUInteger)height, 1)
                   toTexture:texture
            destinationSlice:(NSUInteger)slice
            destinationLevel:(NSUInteger)mipLevel
           destinationOrigin:MTLOriginMake((NSUInteger)x, (NSUInteger)y, 0)];
        [blit endEncoding];
        metallumKeepObjectAliveUntilCompleted(commandBuffer, sourceBuffer);
        metallumKeepObjectAliveUntilCompleted(commandBuffer, texture);
        return 0;
    }
}

int metallum_copy_texture_to_texture(
    void *commandQueuePtr,
    void *sourceTexturePtr,
    void *destinationTexturePtr,
    uint64_t mipLevel,
    uint64_t sourceX,
    uint64_t sourceY,
    uint64_t destX,
    uint64_t destY,
    uint64_t width,
    uint64_t height
) {
    @autoreleasepool {
        id<MTLCommandQueue> queue = (__bridge id<MTLCommandQueue>)commandQueuePtr;
        id<MTLTexture> sourceTexture = (__bridge id<MTLTexture>)sourceTexturePtr;
        id<MTLTexture> destinationTexture = (__bridge id<MTLTexture>)destinationTexturePtr;
        if (queue == nil || sourceTexture == nil || destinationTexture == nil || width == 0 || height == 0) {
            return 1;
        }

        id<MTLCommandBuffer> commandBuffer = metallumSubmissionCommandBufferForEncoding(queue);
        if (commandBuffer == nil) {
            return 1;
        }

        id<MTLBlitCommandEncoder> blit = [commandBuffer blitCommandEncoder];
        if (blit == nil) {
            return 1;
        }

        [blit copyFromTexture:sourceTexture
                  sourceSlice:0
                  sourceLevel:(NSUInteger)mipLevel
                 sourceOrigin:MTLOriginMake((NSUInteger)sourceX, (NSUInteger)sourceY, 0)
                   sourceSize:MTLSizeMake((NSUInteger)width, (NSUInteger)height, 1)
                    toTexture:destinationTexture
             destinationSlice:0
             destinationLevel:(NSUInteger)mipLevel
            destinationOrigin:MTLOriginMake((NSUInteger)destX, (NSUInteger)destY, 0)];
        [blit endEncoding];
        return 0;
    }
}

int metallum_copy_texture_to_buffer(
    void *commandQueuePtr,
    void *sourceTexturePtr,
    void *destinationBufferPtr,
    uint64_t destinationOffset,
    uint64_t mipLevel,
    uint64_t slice,
    uint64_t x,
    uint64_t y,
    uint64_t width,
    uint64_t height,
    uint64_t bytesPerRow,
    uint64_t bytesPerImage
) {
    @autoreleasepool {
        id<MTLCommandQueue> queue = (__bridge id<MTLCommandQueue>)commandQueuePtr;
        id<MTLTexture> sourceTexture = (__bridge id<MTLTexture>)sourceTexturePtr;
        id<MTLBuffer> destinationBuffer = (__bridge id<MTLBuffer>)destinationBufferPtr;
        if (queue == nil || sourceTexture == nil || destinationBuffer == nil || width == 0 || height == 0) {
            return 1;
        }

        id<MTLCommandBuffer> commandBuffer = metallumSubmissionCommandBufferForEncoding(queue);
        if (commandBuffer == nil) {
            return 1;
        }

        id<MTLBlitCommandEncoder> blit = [commandBuffer blitCommandEncoder];
        if (blit == nil) {
            return 1;
        }

        [blit copyFromTexture:sourceTexture
                  sourceSlice:(NSUInteger)slice
                  sourceLevel:(NSUInteger)mipLevel
                 sourceOrigin:MTLOriginMake((NSUInteger)x, (NSUInteger)y, 0)
                   sourceSize:MTLSizeMake((NSUInteger)width, (NSUInteger)height, 1)
                     toBuffer:destinationBuffer
            destinationOffset:(NSUInteger)destinationOffset
       destinationBytesPerRow:(NSUInteger)bytesPerRow
     destinationBytesPerImage:(NSUInteger)bytesPerImage];
        [blit endEncoding];
        metallumKeepObjectAliveUntilCompleted(commandBuffer, sourceTexture);
        metallumKeepObjectAliveUntilCompleted(commandBuffer, destinationBuffer);
        return 0;
    }
}

static MTLSamplerAddressMode metallumSamplerAddressModeFromCode(uint64_t code) {
    switch (code) {
        case 2: return MTLSamplerAddressModeRepeat;
        case 1:
        default: return MTLSamplerAddressModeClampToEdge;
    }
}

static MTLSamplerMinMagFilter metallumSamplerMinMagFilterFromCode(uint64_t code) {
    switch (code) {
        case 1: return MTLSamplerMinMagFilterLinear;
        case 0:
        default: return MTLSamplerMinMagFilterNearest;
    }
}

static MTLSamplerMipFilter metallumSamplerMipFilterFromCode(uint64_t code) {
    switch (code) {
        case 1: return MTLSamplerMipFilterNearest;
        case 2: return MTLSamplerMipFilterLinear;
        case 0:
        default: return MTLSamplerMipFilterNotMipmapped;
    }
}

void *metallum_create_sampler(
    void *devicePtr,
    uint64_t addressModeU,
    uint64_t addressModeV,
    uint64_t minFilter,
    uint64_t magFilter,
    uint64_t mipFilter,
    int maxAnisotropy,
    double lodMaxClamp
) {
    @autoreleasepool {
        id<MTLDevice> device = (__bridge id<MTLDevice>)devicePtr;
        if (device == nil) {
            return NULL;
        }
        MTLSamplerDescriptor *descriptor = [MTLSamplerDescriptor new];
        descriptor.minFilter = metallumSamplerMinMagFilterFromCode(minFilter);
        descriptor.magFilter = metallumSamplerMinMagFilterFromCode(magFilter);
        descriptor.mipFilter = metallumSamplerMipFilterFromCode(mipFilter);
        descriptor.sAddressMode = metallumSamplerAddressModeFromCode(addressModeU);
        descriptor.tAddressMode = metallumSamplerAddressModeFromCode(addressModeV);
        descriptor.maxAnisotropy = (NSUInteger)MAX(maxAnisotropy, 1);
        descriptor.lodMinClamp = 0.0;
        descriptor.lodMaxClamp = lodMaxClamp >= 0.0 && isfinite(lodMaxClamp) ? lodMaxClamp : FLT_MAX;
        return (__bridge_retained void *)[device newSamplerStateWithDescriptor:descriptor];
    }
}

void *metallum_create_sampler_linear(void *devicePtr) {
    return metallum_create_sampler(devicePtr, 1, 1, 1, 1, 0, 1, 0.0);
}

int metallum_draw_indexed(
    void *commandQueuePtr,
    void *colorTexturePtr,
    void *vertexBufferPtr,
    uint64_t vertexOffset,
    void *indexBufferPtr,
    uint64_t indexOffsetBytes,
    uint64_t indexType,
    uint64_t indexCount,
    int64_t baseVertex,
    uint64_t instanceCount,
    void *sourceTexturePtr,
    void *samplerPtr,
    double viewportWidth,
    double viewportHeight,
    int textured
) {
    @autoreleasepool {
        id<MTLCommandQueue> queue = (__bridge id<MTLCommandQueue>)commandQueuePtr;
        id<MTLTexture> colorTexture = (__bridge id<MTLTexture>)colorTexturePtr;
        id<MTLBuffer> vertexBuffer = (__bridge id<MTLBuffer>)vertexBufferPtr;
        id<MTLBuffer> indexBuffer = (__bridge id<MTLBuffer>)indexBufferPtr;
        if (queue == nil || colorTexture == nil || vertexBuffer == nil || indexBuffer == nil) {
            return 1;
        }

        id<MTLRenderPipelineState> pipeline = metallumEnsureGuiPipeline(queue.device, textured != 0, colorTexture.pixelFormat);
        if (pipeline == nil) {
            return 1;
        }

        id<MTLCommandBuffer> commandBuffer = metallumSubmissionCommandBufferForEncoding(queue);
        if (commandBuffer == nil) {
            return 1;
        }

        MTLRenderPassDescriptor *renderPass = [MTLRenderPassDescriptor renderPassDescriptor];
        renderPass.colorAttachments[0].texture = colorTexture;
        renderPass.colorAttachments[0].loadAction = MTLLoadActionLoad;
        renderPass.colorAttachments[0].storeAction = MTLStoreActionStore;

        id<MTLRenderCommandEncoder> encoder = [commandBuffer renderCommandEncoderWithDescriptor:renderPass];
        if (encoder == nil) {
            return 1;
        }

        [encoder setRenderPipelineState:pipeline];
        [encoder setVertexBuffer:vertexBuffer offset:vertexOffset atIndex:0];

        MetallumGuiUniforms uniforms;
        uniforms.viewportSize = (vector_float2){(float)viewportWidth, (float)viewportHeight};
        [encoder setVertexBytes:&uniforms length:sizeof(uniforms) atIndex:1];

        if (textured != 0) {
            id<MTLTexture> sourceTexture = (__bridge id<MTLTexture>)sourceTexturePtr;
            id<MTLSamplerState> sampler = (__bridge id<MTLSamplerState>)samplerPtr;
            if (sourceTexture != nil) {
                [encoder setFragmentTexture:sourceTexture atIndex:0];
            }
            if (sampler != nil) {
                [encoder setFragmentSamplerState:sampler atIndex:0];
            }
        }

        MTLIndexType metalIndexType = indexType == 0 ? MTLIndexTypeUInt16 : MTLIndexTypeUInt32;
        [encoder drawIndexedPrimitives:MTLPrimitiveTypeTriangle
                            indexCount:indexCount
                             indexType:metalIndexType
                           indexBuffer:indexBuffer
                     indexBufferOffset:indexOffsetBytes
                         instanceCount:MAX((NSUInteger)1, (NSUInteger)instanceCount)
                            baseVertex:baseVertex
                          baseInstance:0];
        [encoder endEncoding];
        return 0;
    }
}

int metallum_draw_nonindexed(
    void *commandQueuePtr,
    void *colorTexturePtr,
    void *vertexBufferPtr,
    uint64_t vertexOffset,
    uint64_t vertexStart,
    uint64_t vertexCount,
    uint64_t instanceCount,
    void *sourceTexturePtr,
    void *samplerPtr,
    double viewportWidth,
    double viewportHeight,
    int textured
) {
    @autoreleasepool {
        id<MTLCommandQueue> queue = (__bridge id<MTLCommandQueue>)commandQueuePtr;
        id<MTLTexture> colorTexture = (__bridge id<MTLTexture>)colorTexturePtr;
        id<MTLBuffer> vertexBuffer = (__bridge id<MTLBuffer>)vertexBufferPtr;
        if (queue == nil || colorTexture == nil || vertexBuffer == nil) {
            return 1;
        }

        id<MTLRenderPipelineState> pipeline = metallumEnsureGuiPipeline(queue.device, textured != 0, colorTexture.pixelFormat);
        if (pipeline == nil) {
            return 1;
        }

        id<MTLCommandBuffer> commandBuffer = metallumSubmissionCommandBufferForEncoding(queue);
        if (commandBuffer == nil) {
            return 1;
        }

        MTLRenderPassDescriptor *renderPass = [MTLRenderPassDescriptor renderPassDescriptor];
        renderPass.colorAttachments[0].texture = colorTexture;
        renderPass.colorAttachments[0].loadAction = MTLLoadActionLoad;
        renderPass.colorAttachments[0].storeAction = MTLStoreActionStore;

        id<MTLRenderCommandEncoder> encoder = [commandBuffer renderCommandEncoderWithDescriptor:renderPass];
        if (encoder == nil) {
            return 1;
        }

        [encoder setRenderPipelineState:pipeline];
        [encoder setVertexBuffer:vertexBuffer offset:vertexOffset atIndex:0];

        MetallumGuiUniforms uniforms;
        uniforms.viewportSize = (vector_float2){(float)viewportWidth, (float)viewportHeight};
        [encoder setVertexBytes:&uniforms length:sizeof(uniforms) atIndex:1];

        if (textured != 0) {
            id<MTLTexture> sourceTexture = (__bridge id<MTLTexture>)sourceTexturePtr;
            id<MTLSamplerState> sampler = (__bridge id<MTLSamplerState>)samplerPtr;
            if (sourceTexture != nil) {
                [encoder setFragmentTexture:sourceTexture atIndex:0];
            }
            if (sampler != nil) {
                [encoder setFragmentSamplerState:sampler atIndex:0];
            }
        }

        if (instanceCount > 1) {
            [encoder drawPrimitives:MTLPrimitiveTypeTriangle
                        vertexStart:vertexStart
                        vertexCount:vertexCount
                      instanceCount:instanceCount];
        } else {
            [encoder drawPrimitives:MTLPrimitiveTypeTriangle
                        vertexStart:vertexStart
                        vertexCount:vertexCount];
        }
        [encoder endEncoding];
        return 0;
    }
}

void *metallum_begin_render_pass(
    void *commandQueuePtr,
    void *colorTexturePtr,
    void *depthTexturePtr,
    double viewportWidth,
    double viewportHeight,
    int clearColorEnabled,
    int clearColor,
    int clearDepthEnabled,
    double clearDepth
) {
    @autoreleasepool {
        id<MTLCommandQueue> queue = (__bridge id<MTLCommandQueue>)commandQueuePtr;
        id<MTLTexture> colorTexture = (__bridge id<MTLTexture>)colorTexturePtr;
        id<MTLTexture> depthTexture = (__bridge id<MTLTexture>)depthTexturePtr;
        if (queue == nil || colorTexture == nil) {
            return NULL;
        }

        id<MTLCommandBuffer> commandBuffer = metallumSubmissionCommandBufferForEncoding(queue);
        if (commandBuffer == nil) {
            return NULL;
        }

        MTLRenderPassDescriptor *renderPass = [MTLRenderPassDescriptor renderPassDescriptor];
        renderPass.colorAttachments[0].texture = colorTexture;
        if (clearColorEnabled != 0) {
            double red = ((clearColor >> 16) & 0xFF) / 255.0;
            double green = ((clearColor >> 8) & 0xFF) / 255.0;
            double blue = (clearColor & 0xFF) / 255.0;
            double alpha = ((clearColor >> 24) & 0xFF) / 255.0;
            renderPass.colorAttachments[0].loadAction = MTLLoadActionClear;
            renderPass.colorAttachments[0].clearColor = MTLClearColorMake(red, green, blue, alpha);
        } else {
            renderPass.colorAttachments[0].loadAction = MTLLoadActionLoad;
        }
        renderPass.colorAttachments[0].storeAction = MTLStoreActionStore;

        MTLPixelFormat depthFormat = depthTexture != nil ? depthTexture.pixelFormat : MTLPixelFormatInvalid;
        MTLPixelFormat stencilFormat = metallumStencilPixelFormatForDepthFormat(depthFormat);
        if (depthTexture != nil) {
            renderPass.depthAttachment.texture = depthTexture;
            renderPass.depthAttachment.loadAction = clearDepthEnabled != 0 ? MTLLoadActionClear : MTLLoadActionLoad;
            renderPass.depthAttachment.clearDepth = clearDepth;
            renderPass.depthAttachment.storeAction = MTLStoreActionStore;
            if (stencilFormat != MTLPixelFormatInvalid) {
                renderPass.stencilAttachment.texture = depthTexture;
                renderPass.stencilAttachment.loadAction = MTLLoadActionLoad;
                renderPass.stencilAttachment.storeAction = MTLStoreActionStore;
            }
        }

        id<MTLRenderCommandEncoder> encoder = [commandBuffer renderCommandEncoderWithDescriptor:renderPass];
        if (encoder == nil) {
            return NULL;
        }

        MTLViewport viewport;
        viewport.originX = 0.0;
        viewport.originY = 0.0;
        viewport.width = viewportWidth;
        viewport.height = viewportHeight;
        viewport.znear = 0.0;
        viewport.zfar = 1.0;
        [encoder setViewport:viewport];

        MetallumRenderPassSession *session = calloc(1, sizeof(MetallumRenderPassSession));
        if (session == NULL) {
            [encoder endEncoding];
            return NULL;
        }

        session->commandBuffer = (__bridge_retained void *)commandBuffer;
        session->encoder = (__bridge_retained void *)encoder;
        session->device = (__bridge void *)queue.device;
        session->indexBuffer = NULL;
        session->indexType = 0;
        session->colorFormat = colorTexture.pixelFormat;
        session->depthFormat = depthFormat;
        session->stencilFormat = stencilFormat;
        session->viewportWidth = viewportWidth;
        session->viewportHeight = viewportHeight;
        session->flipVertexY = 0;
        metallumKeepObjectAliveUntilCompleted(commandBuffer, colorTexture);
        metallumKeepObjectAliveUntilCompleted(commandBuffer, depthTexture);
        return session;
    }
}

void *metallum_create_render_pipeline(
    void *devicePtr,
    const char *vertexMsl,
    const char *fragmentMsl,
    const char *vertexEntryPoint,
    const char *fragmentEntryPoint,
    uint64_t colorFormat,
    uint64_t depthFormat,
    uint64_t stencilFormat,
    uint64_t vertexStride,
    const uint64_t *vertexAttributeFormats,
    const uint64_t *vertexAttributeOffsets,
    uint64_t vertexAttributeCount,
    int blendEnabled,
    uint64_t blendSourceRgb,
    uint64_t blendDestRgb,
    uint64_t blendOpRgb,
    uint64_t blendSourceAlpha,
    uint64_t blendDestAlpha,
    uint64_t blendOpAlpha,
    uint64_t writeMask
) {
    @autoreleasepool {
        id<MTLDevice> device = (__bridge id<MTLDevice>)devicePtr;
        if (device == nil || vertexMsl == NULL || fragmentMsl == NULL || vertexEntryPoint == NULL || fragmentEntryPoint == NULL) {
            return NULL;
        }

        NSString *vertexSource = [NSString stringWithUTF8String:vertexMsl];
        NSString *fragmentSource = [NSString stringWithUTF8String:fragmentMsl];
        NSString *vertexEntry = [NSString stringWithUTF8String:vertexEntryPoint];
        NSString *fragmentEntry = [NSString stringWithUTF8String:fragmentEntryPoint];
        if (vertexSource == nil || fragmentSource == nil || vertexEntry == nil || fragmentEntry == nil) {
            return NULL;
        }

        id<MTLRenderPipelineState> pipeline = metallumEnsureDynamicPipeline(
            device,
            vertexSource,
            fragmentSource,
            vertexEntry,
            fragmentEntry,
            (MTLPixelFormat)colorFormat,
            (MTLPixelFormat)depthFormat,
            (MTLPixelFormat)stencilFormat,
            vertexStride,
            vertexAttributeFormats,
            vertexAttributeOffsets,
            vertexAttributeCount,
            blendEnabled,
            blendSourceRgb,
            blendDestRgb,
            blendOpRgb,
            blendSourceAlpha,
            blendDestAlpha,
            blendOpAlpha,
            writeMask
        );
        return pipeline == nil ? NULL : (__bridge void *)pipeline;
    }
}

int metallum_render_pass_set_pipeline(void *renderPassPtr, void *pipelinePtr) {
    MetallumRenderPassSession *session = (MetallumRenderPassSession *)renderPassPtr;
    id<MTLRenderCommandEncoder> encoder = session == NULL ? nil : (__bridge id<MTLRenderCommandEncoder>)session->encoder;
    id<MTLRenderPipelineState> pipeline = (__bridge id<MTLRenderPipelineState>)pipelinePtr;
    if (encoder == nil || pipeline == nil) {
        return 1;
    }

    [encoder setRenderPipelineState:pipeline];
    return 0;
}

int metallum_render_pass_set_depth_stencil_state(
    void *renderPassPtr,
    uint64_t depthCompareOp,
    int writeDepth,
    double depthBiasScaleFactor,
    double depthBiasConstant
) {
    MetallumRenderPassSession *session = (MetallumRenderPassSession *)renderPassPtr;
    id<MTLRenderCommandEncoder> encoder = session == NULL ? nil : (__bridge id<MTLRenderCommandEncoder>)session->encoder;
    id<MTLDevice> device = session == NULL ? nil : (__bridge id<MTLDevice>)session->device;
    if (encoder == nil || device == nil) {
        return 1;
    }

    if (session->depthFormat != MTLPixelFormatInvalid) {
        id<MTLDepthStencilState> depthState = metallumEnsureDepthStencilState(device, depthCompareOp, writeDepth);
        if (depthState != nil) {
            [encoder setDepthStencilState:depthState];
        }
        [encoder setDepthBias:(float)depthBiasConstant slopeScale:(float)depthBiasScaleFactor clamp:0.0f];
    }
    return 0;
}

int metallum_render_pass_set_vertex_buffer(void *renderPassPtr, uint64_t slot, void *bufferPtr, uint64_t offset) {
    MetallumRenderPassSession *session = (MetallumRenderPassSession *)renderPassPtr;
    id<MTLRenderCommandEncoder> encoder = session == NULL ? nil : (__bridge id<MTLRenderCommandEncoder>)session->encoder;
    if (encoder == nil) {
        return 1;
    }

    id<MTLBuffer> buffer = (__bridge id<MTLBuffer>)bufferPtr;
    [encoder setVertexBuffer:buffer offset:(NSUInteger)offset atIndex:METALLUM_VERTEX_BUFFER_SLOT + (NSUInteger)slot];
    return 0;
}

int metallum_render_pass_set_index_buffer(void *renderPassPtr, void *bufferPtr, uint64_t indexType) {
    MetallumRenderPassSession *session = (MetallumRenderPassSession *)renderPassPtr;
    if (session == NULL) {
        return 1;
    }

    session->indexBuffer = bufferPtr;
    session->indexType = indexType;
    return 0;
}

int metallum_render_pass_set_buffer_binding(void *renderPassPtr, uint64_t binding, void *bufferPtr, uint64_t offset, int stageMask) {
    MetallumRenderPassSession *session = (MetallumRenderPassSession *)renderPassPtr;
    id<MTLRenderCommandEncoder> encoder = session == NULL ? nil : (__bridge id<MTLRenderCommandEncoder>)session->encoder;
    if (encoder == nil) {
        return 1;
    }

    id<MTLBuffer> buffer = (__bridge id<MTLBuffer>)bufferPtr;
    NSUInteger index = (NSUInteger)binding;
    if ((stageMask & 1) != 0) {
        [encoder setVertexBuffer:buffer offset:(NSUInteger)offset atIndex:index];
    }
    if ((stageMask & 2) != 0) {
        [encoder setFragmentBuffer:buffer offset:(NSUInteger)offset atIndex:index];
    }
    return 0;
}

int metallum_render_pass_set_texture_binding(void *renderPassPtr, uint64_t binding, void *texturePtr, void *samplerPtr, int stageMask) {
    MetallumRenderPassSession *session = (MetallumRenderPassSession *)renderPassPtr;
    id<MTLRenderCommandEncoder> encoder = session == NULL ? nil : (__bridge id<MTLRenderCommandEncoder>)session->encoder;
    if (encoder == nil) {
        return 1;
    }

    NSUInteger index = (NSUInteger)binding;
    id<MTLTexture> texture = (__bridge id<MTLTexture>)texturePtr;
    id<MTLSamplerState> sampler = (__bridge id<MTLSamplerState>)samplerPtr;
    if ((stageMask & 1) != 0) {
        [encoder setVertexTexture:texture atIndex:index];
        [encoder setVertexSamplerState:sampler atIndex:index];
    }
    if ((stageMask & 2) != 0) {
        [encoder setFragmentTexture:texture atIndex:index];
        [encoder setFragmentSamplerState:sampler atIndex:index];
    }
    return 0;
}

int metallum_render_pass_set_scissor(
    void *renderPassPtr,
    int scissorEnabled,
    int scissorX,
    int scissorY,
    int scissorWidth,
    int scissorHeight
) {
    MetallumRenderPassSession *session = (MetallumRenderPassSession *)renderPassPtr;
    id<MTLRenderCommandEncoder> encoder = session == NULL ? nil : (__bridge id<MTLRenderCommandEncoder>)session->encoder;
    if (encoder == nil) {
        return 1;
    }

    if (scissorEnabled != 0 && scissorWidth > 0 && scissorHeight > 0) {
        MTLScissorRect scissorRect;
        scissorRect.x = (NSUInteger)MAX(scissorX, 0);
        scissorRect.y = (NSUInteger)MAX(scissorY, 0);
        scissorRect.width = (NSUInteger)scissorWidth;
        scissorRect.height = (NSUInteger)scissorHeight;
        [encoder setScissorRect:scissorRect];
    } else {
        MTLScissorRect scissorRect;
        scissorRect.x = 0;
        scissorRect.y = 0;
        scissorRect.width = (NSUInteger)MAX(session->viewportWidth, 0.0);
        scissorRect.height = (NSUInteger)MAX(session->viewportHeight, 0.0);
        [encoder setScissorRect:scissorRect];
    }
    return 0;
}

int metallum_render_pass_set_raster_state(void *renderPassPtr, int cullBackFaces, int wireframe, int flipVertexY) {
    MetallumRenderPassSession *session = (MetallumRenderPassSession *)renderPassPtr;
    id<MTLRenderCommandEncoder> encoder = session == NULL ? nil : (__bridge id<MTLRenderCommandEncoder>)session->encoder;
    if (encoder == nil) {
        return 1;
    }

    session->flipVertexY = flipVertexY != 0 ? 1 : 0;
    [encoder setFrontFacingWinding:flipVertexY != 0 ? MTLWindingClockwise : MTLWindingCounterClockwise];
    [encoder setCullMode:cullBackFaces != 0 ? MTLCullModeBack : MTLCullModeNone];
    [encoder setTriangleFillMode:wireframe != 0 ? MTLTriangleFillModeLines : MTLTriangleFillModeFill];
    return 0;
}

static void metallumKeepResourceAliveUntilCompleted(MetallumRenderPassSession *session, id resource) {
    if (session == NULL || resource == nil || session->commandBuffer == NULL) {
        return;
    }

    id<MTLCommandBuffer> commandBuffer = (__bridge id<MTLCommandBuffer>)session->commandBuffer;
    metallumKeepObjectAliveUntilCompleted(commandBuffer, resource);
}

static id<MTLBuffer> metallumCreateSequentialTriangleFanIndexBuffer(
    MetallumRenderPassSession *session,
    uint64_t vertexCount,
    NSUInteger *generatedIndexCount
) {
    if (generatedIndexCount != NULL) {
        *generatedIndexCount = 0;
    }
    if (session == NULL || vertexCount < 3) {
        return nil;
    }

    id<MTLDevice> device = (__bridge id<MTLDevice>)session->device;
    if (device == nil) {
        return nil;
    }

    uint64_t triangleCount = vertexCount - 2;
    uint64_t indexCount = triangleCount * 3;
    if (triangleCount > (UINT64_MAX / 3) || indexCount > (SIZE_MAX / sizeof(uint32_t))) {
        return nil;
    }

    size_t byteLength = (size_t)indexCount * sizeof(uint32_t);
    uint32_t *indices = malloc(byteLength);
    if (indices == NULL) {
        return nil;
    }

    for (uint64_t triangle = 0; triangle < triangleCount; triangle++) {
        uint64_t offset = triangle * 3;
        indices[offset] = 0;
        indices[offset + 1] = (uint32_t)(triangle + 1);
        indices[offset + 2] = (uint32_t)(triangle + 2);
    }

    id<MTLBuffer> buffer = [device newBufferWithBytes:indices length:byteLength options:MTLResourceStorageModeShared];
    free(indices);
    if (generatedIndexCount != NULL) {
        *generatedIndexCount = buffer == nil ? 0 : (NSUInteger)indexCount;
    }
    return buffer;
}

static uint32_t metallumReadIndex(id<MTLBuffer> indexBuffer, uint64_t byteOffset, uint64_t index, uint64_t indexType) {
    const uint8_t *base = (const uint8_t *)indexBuffer.contents + byteOffset;
    if (indexType == 0) {
        const uint16_t *indices = (const uint16_t *)base;
        return (uint32_t)indices[index];
    }

    const uint32_t *indices = (const uint32_t *)base;
    return indices[index];
}

static id<MTLBuffer> metallumCreateIndexedTriangleFanIndexBuffer(
    MetallumRenderPassSession *session,
    uint64_t indexOffsetBytes,
    uint64_t indexCount,
    NSUInteger *generatedIndexCount
) {
    if (generatedIndexCount != NULL) {
        *generatedIndexCount = 0;
    }
    if (session == NULL || indexCount < 3) {
        return nil;
    }

    id<MTLDevice> device = (__bridge id<MTLDevice>)session->device;
    id<MTLBuffer> sourceIndexBuffer = (__bridge id<MTLBuffer>)session->indexBuffer;
    if (device == nil || sourceIndexBuffer == nil || sourceIndexBuffer.contents == NULL) {
        return nil;
    }

    uint64_t triangleCount = indexCount - 2;
    uint64_t generatedCount = triangleCount * 3;
    if (triangleCount > (UINT64_MAX / 3) || generatedCount > (SIZE_MAX / sizeof(uint32_t))) {
        return nil;
    }

    size_t byteLength = (size_t)generatedCount * sizeof(uint32_t);
    uint32_t *indices = malloc(byteLength);
    if (indices == NULL) {
        return nil;
    }

    uint32_t center = metallumReadIndex(sourceIndexBuffer, indexOffsetBytes, 0, session->indexType);
    for (uint64_t triangle = 0; triangle < triangleCount; triangle++) {
        uint64_t offset = triangle * 3;
        indices[offset] = center;
        indices[offset + 1] = metallumReadIndex(sourceIndexBuffer, indexOffsetBytes, triangle + 1, session->indexType);
        indices[offset + 2] = metallumReadIndex(sourceIndexBuffer, indexOffsetBytes, triangle + 2, session->indexType);
    }

    id<MTLBuffer> buffer = [device newBufferWithBytes:indices length:byteLength options:MTLResourceStorageModeShared];
    free(indices);
    if (generatedIndexCount != NULL) {
        *generatedIndexCount = buffer == nil ? 0 : (NSUInteger)generatedCount;
    }
    return buffer;
}

int metallum_render_pass_draw_indexed(
    void *renderPassPtr,
    uint64_t primitiveType,
    uint64_t indexOffsetBytes,
    uint64_t indexCount,
    int64_t baseVertex,
    uint64_t instanceCount
) {
    MetallumRenderPassSession *session = (MetallumRenderPassSession *)renderPassPtr;
    id<MTLRenderCommandEncoder> encoder = session == NULL ? nil : (__bridge id<MTLRenderCommandEncoder>)session->encoder;
    id<MTLBuffer> indexBuffer = session == NULL ? nil : (__bridge id<MTLBuffer>)session->indexBuffer;
    if (encoder == nil || indexBuffer == nil) {
        return 2;
    }

    MTLPrimitiveType metalPrimitive = metallumPrimitiveTypeFromCode(primitiveType);
    MTLIndexType metalIndexType = session->indexType == 0 ? MTLIndexTypeUInt16 : MTLIndexTypeUInt32;
    NSUInteger safeInstanceCount = (NSUInteger)MAX((uint64_t)1, instanceCount);
    [encoder drawIndexedPrimitives:metalPrimitive
                        indexCount:(NSUInteger)indexCount
                         indexType:metalIndexType
                       indexBuffer:indexBuffer
                 indexBufferOffset:(NSUInteger)indexOffsetBytes
                     instanceCount:safeInstanceCount
                        baseVertex:baseVertex
                      baseInstance:0];
    return 0;
}

int metallum_render_pass_draw_indexed_triangle_fan(
    void *renderPassPtr,
    uint64_t indexOffsetBytes,
    uint64_t indexCount,
    int64_t baseVertex,
    uint64_t instanceCount
) {
    MetallumRenderPassSession *session = (MetallumRenderPassSession *)renderPassPtr;
    id<MTLRenderCommandEncoder> encoder = session == NULL ? nil : (__bridge id<MTLRenderCommandEncoder>)session->encoder;
    if (encoder == nil) {
        return 1;
    }
    if (indexCount < 3) {
        return 0;
    }

    NSUInteger generatedIndexCount = 0;
    id<MTLBuffer> fanIndexBuffer = metallumCreateIndexedTriangleFanIndexBuffer(session, indexOffsetBytes, indexCount, &generatedIndexCount);
    if (fanIndexBuffer == nil || generatedIndexCount == 0) {
        return 3;
    }

    metallumKeepResourceAliveUntilCompleted(session, fanIndexBuffer);
    NSUInteger safeInstanceCount = (NSUInteger)MAX((uint64_t)1, instanceCount);
    [encoder drawIndexedPrimitives:MTLPrimitiveTypeTriangle
                        indexCount:generatedIndexCount
                         indexType:MTLIndexTypeUInt32
                       indexBuffer:fanIndexBuffer
                 indexBufferOffset:0
                     instanceCount:safeInstanceCount
                        baseVertex:baseVertex
                      baseInstance:0];
    return 0;
}

int metallum_render_pass_draw(
    void *renderPassPtr,
    uint64_t primitiveType,
    uint64_t firstVertex,
    uint64_t vertexCount,
    uint64_t instanceCount
) {
    MetallumRenderPassSession *session = (MetallumRenderPassSession *)renderPassPtr;
    id<MTLRenderCommandEncoder> encoder = session == NULL ? nil : (__bridge id<MTLRenderCommandEncoder>)session->encoder;
    if (encoder == nil) {
        return 1;
    }

    MTLPrimitiveType metalPrimitive = metallumPrimitiveTypeFromCode(primitiveType);
    NSUInteger safeInstanceCount = (NSUInteger)MAX((uint64_t)1, instanceCount);
    if (safeInstanceCount > 1) {
        [encoder drawPrimitives:metalPrimitive
                    vertexStart:(NSUInteger)firstVertex
                    vertexCount:(NSUInteger)vertexCount
                  instanceCount:safeInstanceCount];
    } else {
        [encoder drawPrimitives:metalPrimitive
                    vertexStart:(NSUInteger)firstVertex
                    vertexCount:(NSUInteger)vertexCount];
    }
    return 0;
}

int metallum_render_pass_draw_triangle_fan(
    void *renderPassPtr,
    uint64_t firstVertex,
    uint64_t vertexCount,
    uint64_t instanceCount
) {
    MetallumRenderPassSession *session = (MetallumRenderPassSession *)renderPassPtr;
    id<MTLRenderCommandEncoder> encoder = session == NULL ? nil : (__bridge id<MTLRenderCommandEncoder>)session->encoder;
    if (encoder == nil) {
        return 1;
    }
    if (vertexCount < 3) {
        return 0;
    }

    NSUInteger generatedIndexCount = 0;
    id<MTLBuffer> fanIndexBuffer = metallumCreateSequentialTriangleFanIndexBuffer(session, vertexCount, &generatedIndexCount);
    if (fanIndexBuffer == nil || generatedIndexCount == 0) {
        return 3;
    }

    metallumKeepResourceAliveUntilCompleted(session, fanIndexBuffer);
    NSUInteger safeInstanceCount = (NSUInteger)MAX((uint64_t)1, instanceCount);
    [encoder drawIndexedPrimitives:MTLPrimitiveTypeTriangle
                        indexCount:generatedIndexCount
                         indexType:MTLIndexTypeUInt32
                       indexBuffer:fanIndexBuffer
                 indexBufferOffset:0
                     instanceCount:safeInstanceCount
                        baseVertex:(int64_t)firstVertex
                      baseInstance:0];
    return 0;
}

int metallum_end_render_pass(void *renderPassPtr) {
    @autoreleasepool {
        MetallumRenderPassSession *session = (MetallumRenderPassSession *)renderPassPtr;
        if (session == NULL) {
            return 0;
        }

        id<MTLRenderCommandEncoder> encoder = (__bridge id<MTLRenderCommandEncoder>)session->encoder;
        if (encoder != nil) {
            [encoder endEncoding];
        }
        metallumDestroyRenderPassSession(session);
        return 0;
    }
}

int metallum_configure_layer(void *layerPtr, double width, double height, int immediatePresentMode) {
    @autoreleasepool {
        CAMetalLayer *layer = (__bridge CAMetalLayer *)layerPtr;
        if (layer == nil || width <= 0.0 || height <= 0.0) {
            return 1;
        }

        layer.pixelFormat = MTLPixelFormatBGRA8Unorm;
        layer.drawableSize = CGSizeMake(width, height);
        if ([layer respondsToSelector:@selector(setDisplaySyncEnabled:)]) {
            layer.displaySyncEnabled = immediatePresentMode == 0;
        }
        return 0;
    }
}

int metallum_draw_indexed_gui(
    void *commandQueuePtr,
    void *colorTexturePtr,
    void *vertexBufferPtr,
    uint64_t vertexOffset,
    void *indexBufferPtr,
    uint64_t indexOffsetBytes,
    uint64_t indexType,
    uint64_t indexCount,
    int64_t baseVertex,
    uint64_t instanceCount,
    void *sourceTexturePtr,
    void *samplerPtr,
    double viewportWidth,
    double viewportHeight,
    int textured
) {
    return metallum_draw_indexed(
        commandQueuePtr,
        colorTexturePtr,
        vertexBufferPtr,
        vertexOffset,
        indexBufferPtr,
        indexOffsetBytes,
        indexType,
        indexCount,
        baseVertex,
        instanceCount,
        sourceTexturePtr,
        samplerPtr,
        viewportWidth,
        viewportHeight,
        textured
    );
}

void *metallum_acquire_next_drawable(void *layerPtr) {
    @autoreleasepool {
        CAMetalLayer *layer = (__bridge CAMetalLayer *)layerPtr;
        if (layer == nil) {
            return NULL;
        }

        id<CAMetalDrawable> drawable = [layer nextDrawable];
        if (drawable == nil) {
            return NULL;
        }

        return (__bridge_retained void *)drawable;
    }
}

int metallum_copy_texture_to_drawable(void *commandQueuePtr, void *drawablePtr, void *sourceTexturePtr) {
    @autoreleasepool {
        id<MTLCommandQueue> queue = (__bridge id<MTLCommandQueue>)commandQueuePtr;
        id<CAMetalDrawable> drawable = (__bridge id<CAMetalDrawable>)drawablePtr;
        id<MTLTexture> sourceTexture = (__bridge id<MTLTexture>)sourceTexturePtr;
        if (queue == nil || drawable == nil || sourceTexture == nil) {
            return 1;
        }

        id<MTLCommandBuffer> commandBuffer = metallumSubmissionCommandBufferForEncoding(queue);
        if (commandBuffer == nil) {
            return 1;
        }

        id<MTLRenderPipelineState> pipeline = metallumEnsurePresentPipeline(queue.device, drawable.texture.pixelFormat);
        if (pipeline == nil) {
            return 1;
        }

        MTLRenderPassDescriptor *renderPass = [MTLRenderPassDescriptor renderPassDescriptor];
        renderPass.colorAttachments[0].texture = drawable.texture;
        renderPass.colorAttachments[0].loadAction = MTLLoadActionDontCare;
        renderPass.colorAttachments[0].storeAction = MTLStoreActionStore;
        id<MTLRenderCommandEncoder> encoder = [commandBuffer renderCommandEncoderWithDescriptor:renderPass];
        if (encoder == nil) {
            return 1;
        }

        float w = (float)drawable.texture.width;
        float h = (float)drawable.texture.height;
        MetallumGuiVertex fullscreenVertices[] = {
            metallum_vertex(0.0f, 0.0f, 0.0f, 0.0f, 1.0f),
            metallum_vertex(w, 0.0f, 0.0f, 1.0f, 1.0f),
            metallum_vertex(0.0f, h, 0.0f, 0.0f, 0.0f),
            metallum_vertex(w, h, 0.0f, 1.0f, 0.0f)
        };
        id<MTLSamplerState> sampler = metallumEnsurePresentLinearSampler(queue.device);

        [encoder setRenderPipelineState:pipeline];
        [encoder setVertexBytes:fullscreenVertices length:sizeof(fullscreenVertices) atIndex:0];
        MetallumGuiUniforms uniforms;
        uniforms.viewportSize = (vector_float2){w, h};
        [encoder setVertexBytes:&uniforms length:sizeof(uniforms) atIndex:1];
        [encoder setFragmentTexture:sourceTexture atIndex:0];
        [encoder setFragmentSamplerState:sampler atIndex:0];
        [encoder drawPrimitives:MTLPrimitiveTypeTriangleStrip vertexStart:0 vertexCount:4];
        [encoder endEncoding];

        metallumKeepObjectAliveUntilCompleted(commandBuffer, drawable);
        metallumKeepObjectAliveUntilCompleted(commandBuffer, sourceTexture);
        return 0;
    }
}

int metallum_present_drawable(void *commandQueuePtr, void *drawablePtr) {
    @autoreleasepool {
        id<MTLCommandQueue> queue = (__bridge id<MTLCommandQueue>)commandQueuePtr;
        id<CAMetalDrawable> drawable = (__bridge id<CAMetalDrawable>)drawablePtr;
        if (queue == nil || drawable == nil) {
            return 1;
        }
        return metallumQueueDrawablePresent(queue, drawable);
    }
}

void metallum_release_object(void *obj) {
    if (obj != NULL) {
        CFRelease(obj);
    }
}

int metallum_signal_submit(void *commandQueuePtr, uint64_t submitIndex) {
    id<MTLCommandQueue> queue = (__bridge id<MTLCommandQueue>)commandQueuePtr;
    return metallumSignalSubmit(queue, submitIndex);
}

int metallum_wait_for_submit_completion(void *commandQueuePtr, uint64_t submitIndex, uint64_t timeoutMs) {
    id<MTLCommandQueue> queue = (__bridge id<MTLCommandQueue>)commandQueuePtr;
    return metallumWaitForSubmitCompletion(queue, submitIndex, timeoutMs);
}

int metallum_wait_for_command_queue_idle(void *commandQueuePtr) {
    @autoreleasepool {
        id<MTLCommandQueue> queue = (__bridge id<MTLCommandQueue>)commandQueuePtr;
        if (queue == nil) {
            return 1;
        }

        id<MTLCommandBuffer> commandBuffer = metallumTakeSubmissionCommandBufferForSubmit(queue);
        if (commandBuffer == nil) {
            return 1;
        }

        metallumAttachPendingPresentDrawable(queue, commandBuffer);
        [commandBuffer commit];
        [commandBuffer waitUntilCompleted];
        return 0;
    }
}

int metallum_clear_texture(
    void *commandQueuePtr,
    void *texturePtr,
    int clearColorEnabled,
    int clearColor,
    int clearDepthEnabled,
    double clearDepth
) {
    @autoreleasepool {
        id<MTLCommandQueue> queue = (__bridge id<MTLCommandQueue>)commandQueuePtr;
        id<MTLTexture> texture = (__bridge id<MTLTexture>)texturePtr;
        if (queue == nil || texture == nil) {
            return 1;
        }

        id<MTLCommandBuffer> commandBuffer = metallumSubmissionCommandBufferForEncoding(queue);
        if (commandBuffer == nil) {
            return 1;
        }

        MTLRenderPassDescriptor *renderPass = [MTLRenderPassDescriptor renderPassDescriptor];

        MTLPixelFormat format = texture.pixelFormat;
        BOOL isDepthLike =
            format == MTLPixelFormatDepth16Unorm ||
            format == MTLPixelFormatDepth32Float ||
            format == MTLPixelFormatDepth24Unorm_Stencil8 ||
            format == MTLPixelFormatDepth32Float_Stencil8;

        if (isDepthLike) {
            renderPass.depthAttachment.texture = texture;
            renderPass.depthAttachment.loadAction = clearDepthEnabled != 0 ? MTLLoadActionClear : MTLLoadActionLoad;
            renderPass.depthAttachment.storeAction = MTLStoreActionStore;
            renderPass.depthAttachment.clearDepth = clearDepth;

            if (format == MTLPixelFormatDepth24Unorm_Stencil8 || format == MTLPixelFormatDepth32Float_Stencil8) {
                renderPass.stencilAttachment.texture = texture;
                renderPass.stencilAttachment.loadAction = MTLLoadActionLoad;
                renderPass.stencilAttachment.storeAction = MTLStoreActionStore;
            }
        } else {
            renderPass.colorAttachments[0].texture = texture;
            if (clearColorEnabled != 0) {
                double red = ((clearColor >> 16) & 0xFF) / 255.0;
                double green = ((clearColor >> 8) & 0xFF) / 255.0;
                double blue = (clearColor & 0xFF) / 255.0;
                double alpha = ((clearColor >> 24) & 0xFF) / 255.0;
                renderPass.colorAttachments[0].loadAction = MTLLoadActionClear;
                renderPass.colorAttachments[0].clearColor = MTLClearColorMake(red, green, blue, alpha);
            } else {
                renderPass.colorAttachments[0].loadAction = MTLLoadActionLoad;
            }
            renderPass.colorAttachments[0].storeAction = MTLStoreActionStore;
        }

        id<MTLRenderCommandEncoder> encoder = [commandBuffer renderCommandEncoderWithDescriptor:renderPass];
        if (encoder == nil) {
            return 1;
        }

        [encoder endEncoding];
        return 0;
    }
}

int metallum_clear_color_texture_region(
    void *commandQueuePtr,
    void *texturePtr,
    int clearColor,
    int x,
    int y,
    int width,
    int height
) {
    @autoreleasepool {
        id<MTLCommandQueue> queue = (__bridge id<MTLCommandQueue>)commandQueuePtr;
        id<MTLTexture> texture = (__bridge id<MTLTexture>)texturePtr;
        if (queue == nil || texture == nil || width <= 0 || height <= 0) {
            return 1;
        }

        NSUInteger textureWidth = texture.width;
        NSUInteger textureHeight = texture.height;
        NSInteger clampedX = MAX((NSInteger)x, 0);
        NSInteger clampedY = MAX((NSInteger)y, 0);
        NSInteger clampedMaxX = MIN((NSInteger)x + (NSInteger)width, (NSInteger)textureWidth);
        NSInteger clampedMaxY = MIN((NSInteger)y + (NSInteger)height, (NSInteger)textureHeight);
        if (clampedX >= clampedMaxX || clampedY >= clampedMaxY) {
            return 0;
        }

        if (clampedX == 0 && clampedY == 0 && clampedMaxX == (NSInteger)textureWidth && clampedMaxY == (NSInteger)textureHeight) {
            return metallum_clear_texture(commandQueuePtr, texturePtr, 1, clearColor, 0, 1.0);
        }

        id<MTLCommandBuffer> commandBuffer = metallumSubmissionCommandBufferForEncoding(queue);
        if (commandBuffer == nil) {
            return 1;
        }

        id<MTLRenderPipelineState> pipeline = metallumEnsureClearPipeline(queue.device, texture.pixelFormat);
        if (pipeline == nil) {
            return 1;
        }

        MTLRenderPassDescriptor *renderPass = [MTLRenderPassDescriptor renderPassDescriptor];
        renderPass.colorAttachments[0].texture = texture;
        renderPass.colorAttachments[0].loadAction = MTLLoadActionLoad;
        renderPass.colorAttachments[0].storeAction = MTLStoreActionStore;

        id<MTLRenderCommandEncoder> encoder = [commandBuffer renderCommandEncoderWithDescriptor:renderPass];
        if (encoder == nil) {
            return 1;
        }

        MTLViewport viewport;
        viewport.originX = 0.0;
        viewport.originY = 0.0;
        viewport.width = (double)textureWidth;
        viewport.height = (double)textureHeight;
        viewport.znear = 0.0;
        viewport.zfar = 1.0;

        MTLScissorRect scissorRect;
        scissorRect.x = (NSUInteger)clampedX;
        scissorRect.y = (NSUInteger)clampedY;
        scissorRect.width = (NSUInteger)(clampedMaxX - clampedX);
        scissorRect.height = (NSUInteger)(clampedMaxY - clampedY);

        uint32_t packedColor = metallum_color_from_argb(clearColor);
        MetallumGuiVertex fullscreenVertices[] = {
            metallum_vertex(0.0f, 0.0f, 0.0f, 0.0f, 0.0f),
            metallum_vertex((float)textureWidth, 0.0f, 0.0f, 0.0f, 0.0f),
            metallum_vertex(0.0f, (float)textureHeight, 0.0f, 0.0f, 0.0f),
            metallum_vertex((float)textureWidth, (float)textureHeight, 0.0f, 0.0f, 0.0f)
        };
        for (NSUInteger i = 0; i < 4; i++) {
            fullscreenVertices[i].color = packedColor;
        }

        MetallumGuiUniforms uniforms;
        uniforms.viewportSize = (vector_float2){(float)textureWidth, (float)textureHeight};

        [encoder setViewport:viewport];
        [encoder setScissorRect:scissorRect];
        [encoder setRenderPipelineState:pipeline];
        [encoder setVertexBytes:fullscreenVertices length:sizeof(fullscreenVertices) atIndex:0];
        [encoder setVertexBytes:&uniforms length:sizeof(uniforms) atIndex:1];
        [encoder drawPrimitives:MTLPrimitiveTypeTriangleStrip vertexStart:0 vertexCount:4];
        [encoder endEncoding];

        metallumKeepObjectAliveUntilCompleted(commandBuffer, texture);
        return 0;
    }
}

int metallum_clear_color_depth_textures(
    void *commandQueuePtr,
    void *colorTexturePtr,
    int clearColor,
    void *depthTexturePtr,
    double clearDepth
) {
    @autoreleasepool {
        id<MTLCommandQueue> queue = (__bridge id<MTLCommandQueue>)commandQueuePtr;
        id<MTLTexture> colorTexture = (__bridge id<MTLTexture>)colorTexturePtr;
        id<MTLTexture> depthTexture = (__bridge id<MTLTexture>)depthTexturePtr;
        if (queue == nil || colorTexture == nil || depthTexture == nil) {
            return 1;
        }

        id<MTLCommandBuffer> commandBuffer = metallumSubmissionCommandBufferForEncoding(queue);
        if (commandBuffer == nil) {
            return 1;
        }

        MTLRenderPassDescriptor *renderPass = [MTLRenderPassDescriptor renderPassDescriptor];
        double red = ((clearColor >> 16) & 0xFF) / 255.0;
        double green = ((clearColor >> 8) & 0xFF) / 255.0;
        double blue = (clearColor & 0xFF) / 255.0;
        double alpha = ((clearColor >> 24) & 0xFF) / 255.0;

        renderPass.colorAttachments[0].texture = colorTexture;
        renderPass.colorAttachments[0].loadAction = MTLLoadActionClear;
        renderPass.colorAttachments[0].clearColor = MTLClearColorMake(red, green, blue, alpha);
        renderPass.colorAttachments[0].storeAction = MTLStoreActionStore;

        renderPass.depthAttachment.texture = depthTexture;
        renderPass.depthAttachment.loadAction = MTLLoadActionClear;
        renderPass.depthAttachment.clearDepth = clearDepth;
        renderPass.depthAttachment.storeAction = MTLStoreActionStore;

        MTLPixelFormat depthFormat = depthTexture.pixelFormat;
        if (depthFormat == MTLPixelFormatDepth24Unorm_Stencil8 || depthFormat == MTLPixelFormatDepth32Float_Stencil8) {
            renderPass.stencilAttachment.texture = depthTexture;
            renderPass.stencilAttachment.loadAction = MTLLoadActionLoad;
            renderPass.stencilAttachment.storeAction = MTLStoreActionStore;
        }

        id<MTLRenderCommandEncoder> encoder = [commandBuffer renderCommandEncoderWithDescriptor:renderPass];
        if (encoder == nil) {
            return 1;
        }

        [encoder endEncoding];
        return 0;
    }
}

void *metallum_get_buffer_contents(void *bufferPtr) {
    id<MTLBuffer> buffer = (__bridge id<MTLBuffer>)bufferPtr;
    if (buffer == nil) {
        return NULL;
    }
    return buffer.contents;
}
