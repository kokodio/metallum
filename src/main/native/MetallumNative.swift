import Foundation
import Metal
import QuartzCore
import simd

private let metallumMaxVertexBufferSlot = 30
private let metallumMaxSubmitsInFlight = 2
private let metallumSharedResourceOptions: MTLResourceOptions = .storageModeShared

private struct MetallumFullscreenUniforms {
    var viewportSize: SIMD2<Float>
    var z: Float
    var _padding0: Float
    var color: SIMD4<Float>
    var uvMin: SIMD2<Float>
    var uvMax: SIMD2<Float>
}

private struct DynamicPipelineKey: Hashable {
    let deviceAddress: UInt
    let vertexSource: String
    let fragmentSource: String
    let vertexEntry: String
    let fragmentEntry: String
    let colorFormat: UInt
    let depthFormat: UInt
    let stencilFormat: UInt
    let vertexAttributes: [UInt64]
    let vertexOffsets: [UInt64]
    let vertexAttributeBufferSlots: [UInt64]
    let vertexBindingBufferSlots: [UInt64]
    let vertexBindingStrides: [UInt64]
    let vertexBindingStepRates: [UInt64]
    let blendEnabled: Bool
    let blendSourceRgb: UInt64
    let blendDestRgb: UInt64
    let blendOpRgb: UInt64
    let blendSourceAlpha: UInt64
    let blendDestAlpha: UInt64
    let blendOpAlpha: UInt64
    let writeMask: UInt64
}

private struct DepthStencilKey: Hashable {
    let deviceAddress: UInt
    let compareOp: UInt64
    let writeDepth: Bool
}

private struct PipelineVariantKey: Hashable {
    let deviceAddress: UInt
    let colorFormat: MTLPixelFormat
    let depthFormat: MTLPixelFormat
}

private final class SubmitFence {
    let commandBuffer: MTLCommandBuffer
    let submitIndex: UInt64
    private var observedComplete = false

    init(commandBuffer: MTLCommandBuffer, submitIndex: UInt64) {
        self.commandBuffer = commandBuffer
        self.submitIndex = submitIndex
    }

    func wait(timeoutMs: UInt64) -> Bool {
        if refreshCompletion() {
            return true
        }
        if timeoutMs == 0 {
            return false
        }
        commandBuffer.waitUntilCompleted()
        return refreshCompletion()
    }

    private func refreshCompletion() -> Bool {
        if observedComplete {
            return true
        }
        if commandBuffer.status == .completed || commandBuffer.status == .error {
            observedComplete = true
            return true
        }
        return false
    }
}

private final class SubmitTracker {
    var inFlightFences: [SubmitFence] = []
    var completedSubmitIndex: UInt64 = 1
    var nextCommandBufferSerial: UInt64 = 1
    var pendingCommandBuffer: MTLCommandBuffer?
    var pendingRenderPass: RenderPassSession?
    var acquiredDrawable: CAMetalDrawable?
    var pendingDrawableForPresent: CAMetalDrawable?
}

private final class RenderPassSession {
    let commandBuffer: MTLCommandBuffer
    let encoder: MTLRenderCommandEncoder
    let device: MTLDevice
    let colorAttachmentAddress: UInt
    let depthAttachmentAddress: UInt
    let colorFormat: MTLPixelFormat
    let depthFormat: MTLPixelFormat
    let stencilFormat: MTLPixelFormat
    var viewportWidth: Double
    var viewportHeight: Double

    init(
        commandBuffer: MTLCommandBuffer,
        encoder: MTLRenderCommandEncoder,
        device: MTLDevice,
        colorAttachmentAddress: UInt,
        depthAttachmentAddress: UInt,
        colorFormat: MTLPixelFormat,
        depthFormat: MTLPixelFormat,
        stencilFormat: MTLPixelFormat,
        viewportWidth: Double,
        viewportHeight: Double
    ) {
        self.commandBuffer = commandBuffer
        self.encoder = encoder
        self.device = device
        self.colorAttachmentAddress = colorAttachmentAddress
        self.depthAttachmentAddress = depthAttachmentAddress
        self.colorFormat = colorFormat
        self.depthFormat = depthFormat
        self.stencilFormat = stencilFormat
        self.viewportWidth = viewportWidth
        self.viewportHeight = viewportHeight
    }
}

private enum NativeState {
    static var debugLabelsEnabled = false
    static var dynamicPipelines: [DynamicPipelineKey: MTLRenderPipelineState] = [:]
    static var depthStencilStates: [DepthStencilKey: MTLDepthStencilState] = [:]
    static var submitTrackers: [UInt: SubmitTracker] = [:]
    static var clearPipelines: [PipelineVariantKey: MTLRenderPipelineState] = [:]
    static var presentPipelines: [PipelineVariantKey: MTLRenderPipelineState] = [:]
    static var presentNearestSamplers: [UInt: MTLSamplerState] = [:]
    static var presentLinearSamplers: [UInt: MTLSamplerState] = [:]
}

@inline(__always)
private func retainedPointer(_ object: AnyObject?) -> UnsafeMutableRawPointer? {
    guard let object else {
        return nil
    }
    return UnsafeMutableRawPointer(Unmanaged.passRetained(object).toOpaque())
}

@inline(__always)
private func unretainedPointer(_ object: AnyObject?) -> UnsafeMutableRawPointer? {
    guard let object else {
        return nil
    }
    return UnsafeMutableRawPointer(Unmanaged.passUnretained(object).toOpaque())
}

@inline(__always)
private func object<T: AnyObject>(_ pointer: UnsafeMutableRawPointer?) -> T? {
    guard let pointer else {
        return nil
    }
    return Unmanaged<T>.fromOpaque(pointer).takeUnretainedValue()
}

@inline(__always)
private func object<T: AnyObject>(_ pointer: UnsafeRawPointer?) -> T? {
    guard let pointer else {
        return nil
    }
    return Unmanaged<T>.fromOpaque(UnsafeMutableRawPointer(mutating: pointer)).takeUnretainedValue()
}

@inline(__always)
private func renderPassSession(_ pointer: UnsafeMutableRawPointer?) -> RenderPassSession? {
    guard let pointer else {
        return nil
    }
    return Unmanaged<RenderPassSession>.fromOpaque(pointer).takeUnretainedValue()
}

@inline(__always)
private func takeRenderPassSession(_ pointer: UnsafeMutableRawPointer?) -> RenderPassSession? {
    guard let pointer else {
        return nil
    }
    return Unmanaged<RenderPassSession>.fromOpaque(pointer).takeRetainedValue()
}

@inline(__always)
private func objectAddress(_ object: AnyObject) -> UInt {
    UInt(bitPattern: Unmanaged.passUnretained(object).toOpaque())
}

@inline(__always)
private func maxUInt64(_ left: UInt64, _ right: UInt64) -> UInt64 {
    left >= right ? left : right
}

@inline(__always)
private func withMetalAutoreleasePool<T>(_ body: () -> T) -> T {
    autoreleasepool(invoking: body)
}

private func textureSliceCount(_ texture: MTLTexture) -> Int {
    switch texture.textureType {
    case .type2DArray:
        return max(texture.arrayLength, 1)
    case .typeCube:
        return 6
    case .typeCubeArray:
        return max(texture.arrayLength, 1) * 6
    default:
        return 1
    }
}

private func primitiveType(from code: UInt64) -> MTLPrimitiveType {
    switch code {
    case 0: return .triangle
    case 1: return .triangleStrip
    case 2: return .line
    case 3: return .lineStrip
    case 4: return .point
    default: return .triangle
    }
}

private func vertexFormat(from code: UInt64) -> MTLVertexFormat {
    switch code {
    case 1: return .float
    case 2: return .float2
    case 3: return .float3
    case 4: return .float4
    case 5: return .uchar4Normalized
    case 6: return .uchar4
    case 7: return .ushort2
    case 8: return .ushort2Normalized
    case 9: return .short2
    case 10: return .short2Normalized
    case 11: return .ushort4
    case 12: return .short4
    case 13: return .ushort4Normalized
    case 14: return .short4Normalized
    case 15: return .uint
    case 16: return .uint2
    case 17: return .uint3
    case 18: return .uint4
    case 19: return .int
    case 20: return .int2
    case 21: return .int3
    case 22: return .int4
    case 23: return .half
    case 24: return .half2
    case 25: return .half4
    case 26: return .char4Normalized
    case 27: return .char4
    case 28: return .uchar3Normalized
    case 29: return .char3Normalized
    case 30: return .uchar3
    case 31: return .char3
    case 32: return .ushort3
    case 33: return .short3
    case 34: return .ushort3Normalized
    case 35: return .short3Normalized
    case 36: return .half3
    case 37: return .uchar4Normalized_bgra
    case 38: return .uchar2
    default: return .invalid
    }
}

private func blendFactor(from code: UInt64) -> MTLBlendFactor {
    switch code {
    case 0: return .zero
    case 1: return .one
    case 2: return .sourceColor
    case 3: return .oneMinusSourceColor
    case 4: return .sourceAlpha
    case 5: return .oneMinusSourceAlpha
    case 6: return .destinationColor
    case 7: return .oneMinusDestinationColor
    case 8: return .destinationAlpha
    case 9: return .oneMinusDestinationAlpha
    case 10: return .sourceAlphaSaturated
    case 11: return .blendColor
    case 12: return .oneMinusBlendColor
    case 13: return .blendAlpha
    case 14: return .oneMinusBlendAlpha
    default: return .one
    }
}

private func blendOperation(from code: UInt64) -> MTLBlendOperation {
    switch code {
    case 0: return .add
    case 1: return .subtract
    case 2: return .reverseSubtract
    case 3: return .min
    case 4: return .max
    default: return .add
    }
}

private func compareFunction(from code: UInt64) -> MTLCompareFunction {
    switch code {
    case 1: return .always
    case 2: return .less
    case 3: return .lessEqual
    case 4: return .equal
    case 5: return .notEqual
    case 6: return .greaterEqual
    case 7: return .greater
    case 8: return .never
    default: return .always
    }
}

private func stencilPixelFormat(for depthFormat: MTLPixelFormat) -> MTLPixelFormat {
    switch depthFormat {
    case .depth24Unorm_stencil8, .depth32Float_stencil8:
        return depthFormat
    default:
        return .invalid
    }
}

private func samplerAddressMode(from code: UInt64) -> MTLSamplerAddressMode {
    switch code {
    case 2: return .repeat
    default: return .clampToEdge
    }
}

private func samplerMinMagFilter(from code: UInt64) -> MTLSamplerMinMagFilter {
    switch code {
    case 1: return .linear
    default: return .nearest
    }
}

private func samplerMipFilter(from code: UInt64) -> MTLSamplerMipFilter {
    switch code {
    case 1: return .nearest
    case 2: return .linear
    default: return .notMipmapped
    }
}

private func makeClearColor(red: Float, green: Float, blue: Float, alpha: Float) -> MTLClearColor {
    MTLClearColor(red: Double(red), green: Double(green), blue: Double(blue), alpha: Double(alpha))
}

private func stringFromOptionalCString(_ pointer: UnsafePointer<CChar>?) -> String? {
    guard let pointer else {
        return nil
    }
    let value = String(cString: pointer)
    return value.isEmpty ? nil : value
}

private func textureLabel(_ texture: MTLTexture?) -> String {
    guard let texture else {
        return "nil"
    }
    if let label = texture.label, !label.isEmpty {
        return label
    }
    return "\(texture.width)x\(texture.height) \(texture.pixelFormat)"
}

private func labelCommandBuffer(_ commandBuffer: MTLCommandBuffer, tracker: SubmitTracker, purpose: @autoclosure () -> String) {
    guard NativeState.debugLabelsEnabled else {
        return
    }
    let serial = tracker.nextCommandBufferSerial
    tracker.nextCommandBufferSerial += 1
    commandBuffer.label = "Metallum \(purpose()) CB #\(serial)"
}

private func setBlitEncoderLabel(_ encoder: MTLBlitCommandEncoder, _ label: @autoclosure () -> String) {
    guard NativeState.debugLabelsEnabled else {
        return
    }
    encoder.label = "Metallum \(label())"
}

private func setRenderEncoderLabel(_ encoder: MTLRenderCommandEncoder, _ label: @autoclosure () -> String) {
    guard NativeState.debugLabelsEnabled else {
        return
    }
    encoder.label = "Metallum \(label())"
}

private func guiMslSource() -> String {
    """
    #include <metal_stdlib>
    using namespace metal;
    struct FullscreenUniforms {
      float2 viewportSize;
      float z;
      float _padding0;
      float4 color;
      float2 uvMin;
      float2 uvMax;
    };
    struct VertexOut {
      float4 position [[position]];
      float4 color;
      float2 uv;
    };
    vertex VertexOut metallum_fullscreen_vs(uint vertexId [[vertex_id]], constant FullscreenUniforms& u [[buffer(1)]]) {
      const float2 positions[4] = {
        float2(0.0, 0.0),
        float2(u.viewportSize.x, 0.0),
        float2(0.0, u.viewportSize.y),
        float2(u.viewportSize.x, u.viewportSize.y)
      };
      const float2 uvs[4] = {
        float2(u.uvMin.x, u.uvMin.y),
        float2(u.uvMax.x, u.uvMin.y),
        float2(u.uvMin.x, u.uvMax.y),
        float2(u.uvMax.x, u.uvMax.y)
      };
      VertexOut out;
      float2 position = positions[vertexId & 3];
      float x = (position.x / max(u.viewportSize.x, 1.0)) * 2.0 - 1.0;
      float y = 1.0 - (position.y / max(u.viewportSize.y, 1.0)) * 2.0;
      out.position = float4(x, y, u.z, 1.0);
      out.color = u.color;
      out.uv = uvs[vertexId & 3];
      return out;
    }
    fragment float4 metallum_gui_fs_textured(VertexOut in [[stage_in]], texture2d<float> tex [[texture(0)]], sampler smp [[sampler(0)]]) {
      return tex.sample(smp, in.uv) * in.color;
    }
    fragment float4 metallum_gui_fs_color(VertexOut in [[stage_in]]) {
      return in.color;
    }
    """
}

private func submitTracker(for queue: MTLCommandQueue) -> SubmitTracker {
    let key = objectAddress(queue)
    if let existing = NativeState.submitTrackers[key] {
        return existing
    }
    let created = SubmitTracker()
    NativeState.submitTrackers[key] = created
    return created
}

private func ensureSubmissionCommandBuffer(_ queue: MTLCommandQueue, _ tracker: SubmitTracker) -> MTLCommandBuffer? {
    if tracker.pendingCommandBuffer == nil {
        tracker.pendingCommandBuffer = queue.makeCommandBuffer()
        if let commandBuffer = tracker.pendingCommandBuffer {
            labelCommandBuffer(commandBuffer, tracker: tracker, purpose: "Frame")
        }
    }
    return tracker.pendingCommandBuffer
}

private func finishPendingRenderPass(_ tracker: SubmitTracker) {
    guard let session = tracker.pendingRenderPass else {
        return
    }
    session.encoder.endEncoding()
    tracker.pendingRenderPass = nil
}

private func submissionCommandBufferForStandaloneEncoding(_ queue: MTLCommandQueue) -> MTLCommandBuffer? {
    let tracker = submitTracker(for: queue)
    let commandBuffer = ensureSubmissionCommandBuffer(queue, tracker)
    finishPendingRenderPass(tracker)
    return commandBuffer
}

private func canReuseRenderPass(
    _ session: RenderPassSession,
    colorTexture: MTLTexture,
    depthTexture: MTLTexture?
) -> Bool {
    session.colorAttachmentAddress == objectAddress(colorTexture)
        && session.depthAttachmentAddress == (depthTexture.map(objectAddress) ?? 0)
}

private func reusePendingRenderPassIfCompatible(
    _ queue: MTLCommandQueue,
    colorTexture: MTLTexture,
    depthTexture: MTLTexture?,
    viewportWidth: Double,
    viewportHeight: Double
) -> RenderPassSession? {
    let tracker = submitTracker(for: queue)
    guard let session = tracker.pendingRenderPass,
          canReuseRenderPass(
            session,
            colorTexture: colorTexture,
            depthTexture: depthTexture
          ) else {
        return nil
    }

    prepareRenderPassSessionForReuse(session, viewportWidth: viewportWidth, viewportHeight: viewportHeight)
    return session
}

private func prepareRenderPassSessionForReuse(_ session: RenderPassSession, viewportWidth: Double, viewportHeight: Double) {
    session.viewportWidth = viewportWidth
    session.viewportHeight = viewportHeight
    session.encoder.setViewport(MTLViewport(originX: 0.0, originY: 0.0, width: viewportWidth, height: viewportHeight, znear: 0.0, zfar: 1.0))
}

private func encodeClearDraw(
    encoder: MTLRenderCommandEncoder,
    pipeline: MTLRenderPipelineState,
    textureWidth: Int,
    textureHeight: Int,
    clearColor: SIMD4<Float>,
    scissorRect: MTLScissorRect,
    depthState: MTLDepthStencilState? = nil,
    clearDepth: Double = 0.0
) {
    encoder.setViewport(MTLViewport(originX: 0.0, originY: 0.0, width: Double(textureWidth), height: Double(textureHeight), znear: 0.0, zfar: 1.0))
    encoder.setScissorRect(scissorRect)

    var uniforms = MetallumFullscreenUniforms(
        viewportSize: SIMD2<Float>(Float(textureWidth), Float(textureHeight)),
        z: depthState == nil ? 0.0 : Float(max(0.0, min(clearDepth, 1.0))),
        _padding0: 0.0,
        color: clearColor,
        uvMin: SIMD2<Float>(0.0, 0.0),
        uvMax: SIMD2<Float>(0.0, 0.0)
    )

    encoder.setRenderPipelineState(pipeline)
    if let depthState {
        encoder.setDepthStencilState(depthState)
    }
    withUnsafeBytes(of: &uniforms) { bytes in
        encoder.setVertexBytes(bytes.baseAddress!, length: bytes.count, index: 1)
    }
    encoder.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 4)
}

private func takeSubmissionCommandBufferForSubmit(_ queue: MTLCommandQueue) -> MTLCommandBuffer? {
    let tracker = submitTracker(for: queue)
    _ = ensureSubmissionCommandBuffer(queue, tracker)
    finishPendingRenderPass(tracker)
    let commandBuffer = tracker.pendingCommandBuffer
    tracker.pendingCommandBuffer = nil
    return commandBuffer
}

private func keepObjectAliveUntilCompleted(_ commandBuffer: MTLCommandBuffer?, _ resource: AnyObject?) {
    guard let commandBuffer, let resource else {
        return
    }
    commandBuffer.addCompletedHandler { _ in
        _ = resource
    }
}

private func keepResourceAliveUntilCompleted(_ session: RenderPassSession?, _ resource: AnyObject?) {
    guard let session else {
        return
    }
    keepObjectAliveUntilCompleted(session.commandBuffer, resource)
}

private func commandBufferForFramePresentEncoding(_ queue: MTLCommandQueue) -> MTLCommandBuffer? {
    let tracker = submitTracker(for: queue)
    finishPendingRenderPass(tracker)
    return ensureSubmissionCommandBuffer(queue, tracker)
}

private func encodeTextureToDrawable(
    commandBuffer: MTLCommandBuffer,
    device: MTLDevice,
    drawable: CAMetalDrawable,
    sourceTexture: MTLTexture
) -> Bool {
    guard let pipeline = ensurePresentPipeline(device, drawable.texture.pixelFormat) else {
        return false
    }

    let renderPass = MTLRenderPassDescriptor()
    renderPass.colorAttachments[0].texture = drawable.texture
    renderPass.colorAttachments[0].loadAction = .dontCare
    renderPass.colorAttachments[0].storeAction = .store
    guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: renderPass) else {
        return false
    }
    setRenderEncoderLabel(encoder, "present \(textureLabel(sourceTexture)) -> drawable")

    let w = Float(drawable.texture.width)
    let h = Float(drawable.texture.height)
    var uniforms = MetallumFullscreenUniforms(
        viewportSize: SIMD2<Float>(w, h),
        z: 0.0,
        _padding0: 0.0,
        color: SIMD4<Float>(1.0, 1.0, 1.0, 1.0),
        uvMin: SIMD2<Float>(0.0, 1.0),
        uvMax: SIMD2<Float>(1.0, 0.0)
    )

    encoder.setRenderPipelineState(pipeline)
    withUnsafeBytes(of: &uniforms) { bytes in
        encoder.setVertexBytes(bytes.baseAddress!, length: bytes.count, index: 1)
    }
    encoder.setFragmentTexture(sourceTexture, index: 0)
    let requiresScaling = sourceTexture.width != drawable.texture.width || sourceTexture.height != drawable.texture.height
    let sampler = requiresScaling ? ensurePresentLinearSampler(device) : ensurePresentNearestSampler(device)
    if let sampler {
        encoder.setFragmentSamplerState(sampler, index: 0)
    }
    encoder.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 4)
    encoder.endEncoding()
    return true
}

private func commitPendingDrawablePresent(_ queue: MTLCommandQueue, waitUntilCompleted: Bool) -> Int32 {
    let tracker = submitTracker(for: queue)
    guard tracker.pendingCommandBuffer == nil else {
        return 2
    }
    guard let drawable = tracker.pendingDrawableForPresent else {
        return 1
    }
    tracker.pendingDrawableForPresent = nil

    guard let commandBuffer = queue.makeCommandBuffer() else {
        if tracker.pendingDrawableForPresent == nil {
            tracker.pendingDrawableForPresent = drawable
        }
        return 1
    }

    labelCommandBuffer(commandBuffer, tracker: tracker, purpose: "Present")

    keepObjectAliveUntilCompleted(commandBuffer, drawable)
    commandBuffer.present(drawable)
    commandBuffer.commit()
    if waitUntilCompleted {
        commandBuffer.waitUntilCompleted()
    }
    return 0
}

private func acquireNextDrawable(_ queue: MTLCommandQueue, _ layer: CAMetalLayer) -> Int32 {
    let tracker = submitTracker(for: queue)
    guard tracker.acquiredDrawable == nil, tracker.pendingDrawableForPresent == nil else {
        return 2
    }
    guard let drawable = layer.nextDrawable() else {
        return 1
    }
    tracker.acquiredDrawable = drawable
    return 0
}

private func signalSubmit(_ queue: MTLCommandQueue, submitIndex: UInt64) -> Int32 {
    let tracker = submitTracker(for: queue)

    guard let commandBuffer = takeSubmissionCommandBufferForSubmit(queue) else {
        return 1
    }

    let fence = SubmitFence(commandBuffer: commandBuffer, submitIndex: submitIndex)
    tracker.inFlightFences.append(fence)

    commandBuffer.commit()
    return 0
}

private func waitForSubmitCompletion(_ queue: MTLCommandQueue, submitIndex: UInt64, timeoutMs: UInt64) -> Int32 {
    if submitIndex <= 1 {
        return 0
    }

    let tracker = submitTracker(for: queue)
    if tracker.completedSubmitIndex >= submitIndex {
        return 0
    }
    guard let fence = tracker.inFlightFences.first(where: { $0.submitIndex == submitIndex }) else {
        return 1
    }

    guard fence.wait(timeoutMs: timeoutMs) else {
        return 1
    }

    tracker.completedSubmitIndex = maxUInt64(tracker.completedSubmitIndex, submitIndex)
    let completedSubmitIndex = tracker.completedSubmitIndex
    tracker.inFlightFences.removeAll { $0.submitIndex <= completedSubmitIndex }
    return 0
}

private func buildGuiPipeline(device: MTLDevice, textured: Bool, colorFormat: MTLPixelFormat, depthFormat: MTLPixelFormat = .invalid) -> MTLRenderPipelineState? {
    do {
        let library = try device.makeLibrary(source: guiMslSource(), options: nil)
        guard
            let vertexFunction = library.makeFunction(name: "metallum_fullscreen_vs"),
            let fragmentFunction = library.makeFunction(name: textured ? "metallum_gui_fs_textured" : "metallum_gui_fs_color")
        else {
            NSLog("[metallum] Failed to create GUI shader functions")
            return nil
        }

        let descriptor = MTLRenderPipelineDescriptor()
        if NativeState.debugLabelsEnabled {
            descriptor.label = "Metallum \(textured ? "textured" : "color") helper pipeline color=\(colorFormat) depth=\(depthFormat)"
        }
        descriptor.vertexFunction = vertexFunction
        descriptor.fragmentFunction = fragmentFunction
        descriptor.colorAttachments[0].pixelFormat = colorFormat
        descriptor.depthAttachmentPixelFormat = depthFormat
        descriptor.colorAttachments[0].isBlendingEnabled = textured
        if textured {
            descriptor.colorAttachments[0].rgbBlendOperation = .add
            descriptor.colorAttachments[0].alphaBlendOperation = .add
            descriptor.colorAttachments[0].sourceRGBBlendFactor = .sourceAlpha
            descriptor.colorAttachments[0].sourceAlphaBlendFactor = .sourceAlpha
            descriptor.colorAttachments[0].destinationRGBBlendFactor = .oneMinusSourceAlpha
            descriptor.colorAttachments[0].destinationAlphaBlendFactor = .oneMinusSourceAlpha
        }

        return try device.makeRenderPipelineState(descriptor: descriptor)
    } catch {
        NSLog("[metallum] Failed to create GUI render pipeline: %@", String(describing: error))
        return nil
    }
}

private func buildClearPipeline(device: MTLDevice, colorFormat: MTLPixelFormat, depthFormat: MTLPixelFormat = .invalid) -> MTLRenderPipelineState? {
    buildGuiPipeline(device: device, textured: false, colorFormat: colorFormat, depthFormat: depthFormat)
}

private func buildPresentPipeline(device: MTLDevice, colorFormat: MTLPixelFormat) -> MTLRenderPipelineState? {
    do {
        let library = try device.makeLibrary(source: guiMslSource(), options: nil)
        guard
            let vertexFunction = library.makeFunction(name: "metallum_fullscreen_vs"),
            let fragmentFunction = library.makeFunction(name: "metallum_gui_fs_textured")
        else {
            NSLog("[metallum] Failed to create present shader functions")
            return nil
        }

        let descriptor = MTLRenderPipelineDescriptor()
        if NativeState.debugLabelsEnabled {
            descriptor.label = "Metallum present pipeline color=\(colorFormat)"
        }
        descriptor.vertexFunction = vertexFunction
        descriptor.fragmentFunction = fragmentFunction
        descriptor.colorAttachments[0].pixelFormat = colorFormat
        descriptor.colorAttachments[0].isBlendingEnabled = false

        return try device.makeRenderPipelineState(descriptor: descriptor)
    } catch {
        NSLog("[metallum] Failed to create present render pipeline: %@", String(describing: error))
        return nil
    }
}

private func ensurePresentLinearSampler(_ device: MTLDevice) -> MTLSamplerState? {
    let key = objectAddress(device)
    if let cached = NativeState.presentLinearSamplers[key] {
        return cached
    }
    let descriptor = MTLSamplerDescriptor()
    if NativeState.debugLabelsEnabled {
        descriptor.label = "Metallum present linear sampler"
    }
    descriptor.minFilter = .linear
    descriptor.magFilter = .linear
    descriptor.mipFilter = .notMipmapped
    descriptor.sAddressMode = .clampToEdge
    descriptor.tAddressMode = .clampToEdge
    let sampler = device.makeSamplerState(descriptor: descriptor)
    if let sampler {
        NativeState.presentLinearSamplers[key] = sampler
    }
    return sampler
}

private func ensurePresentNearestSampler(_ device: MTLDevice) -> MTLSamplerState? {
    let key = objectAddress(device)
    if let cached = NativeState.presentNearestSamplers[key] {
        return cached
    }
    let descriptor = MTLSamplerDescriptor()
    if NativeState.debugLabelsEnabled {
        descriptor.label = "Metallum present nearest sampler"
    }
    descriptor.minFilter = .nearest
    descriptor.magFilter = .nearest
    descriptor.mipFilter = .notMipmapped
    descriptor.sAddressMode = .clampToEdge
    descriptor.tAddressMode = .clampToEdge
    let sampler = device.makeSamplerState(descriptor: descriptor)
    if let sampler {
        NativeState.presentNearestSamplers[key] = sampler
    }
    return sampler
}

private func ensurePresentPipeline(_ device: MTLDevice, _ colorFormat: MTLPixelFormat) -> MTLRenderPipelineState? {
    let key = PipelineVariantKey(deviceAddress: objectAddress(device), colorFormat: colorFormat, depthFormat: .invalid)
    if let cached = NativeState.presentPipelines[key] {
        return cached
    }
    let pipeline = buildPresentPipeline(device: device, colorFormat: colorFormat)
    if let pipeline {
        NativeState.presentPipelines[key] = pipeline
    }
    return pipeline
}

private func ensureClearColorDepthPipeline(_ device: MTLDevice, _ colorFormat: MTLPixelFormat, _ depthFormat: MTLPixelFormat) -> MTLRenderPipelineState? {
    let key = PipelineVariantKey(deviceAddress: objectAddress(device), colorFormat: colorFormat, depthFormat: depthFormat)
    if let cached = NativeState.clearPipelines[key] {
        return cached
    }
    let pipeline = buildClearPipeline(device: device, colorFormat: colorFormat, depthFormat: depthFormat)
    if let pipeline {
        NativeState.clearPipelines[key] = pipeline
    }
    return pipeline
}

private func ensureDepthStencilState(device: MTLDevice, compareOp: UInt64, writeDepth: Bool) -> MTLDepthStencilState? {
    let key = DepthStencilKey(deviceAddress: objectAddress(device), compareOp: compareOp, writeDepth: writeDepth)
    if let cached = NativeState.depthStencilStates[key] {
        return cached
    }
    let descriptor = MTLDepthStencilDescriptor()
    descriptor.depthCompareFunction = compareFunction(from: compareOp)
    descriptor.isDepthWriteEnabled = writeDepth
    let state = device.makeDepthStencilState(descriptor: descriptor)
    if let state {
        NativeState.depthStencilStates[key] = state
    }
    return state
}

private func copiedArray(_ pointer: UnsafePointer<UInt64>?, count: UInt64) -> [UInt64] {
    guard let pointer, count > 0 else {
        return []
    }
    return Array(UnsafeBufferPointer(start: pointer, count: Int(count)))
}

private func ensureDynamicPipeline(
    device: MTLDevice,
    vertexSource: String,
    fragmentSource: String,
    vertexEntry: String,
    fragmentEntry: String,
    colorFormat: MTLPixelFormat,
    depthFormat: MTLPixelFormat,
    stencilFormat: MTLPixelFormat,
    vertexAttributeFormats: UnsafePointer<UInt64>?,
    vertexAttributeOffsets: UnsafePointer<UInt64>?,
    vertexAttributeBufferSlots: UnsafePointer<UInt64>?,
    vertexAttributeCount: UInt64,
    vertexBindingBufferSlots: UnsafePointer<UInt64>?,
    vertexBindingStrides: UnsafePointer<UInt64>?,
    vertexBindingStepRates: UnsafePointer<UInt64>?,
    vertexBindingCount: UInt64,
    blendEnabled: Bool,
    blendSourceRgb: UInt64,
    blendDestRgb: UInt64,
    blendOpRgb: UInt64,
    blendSourceAlpha: UInt64,
    blendDestAlpha: UInt64,
    blendOpAlpha: UInt64,
    writeMask: UInt64
) -> MTLRenderPipelineState? {
    let formats = copiedArray(vertexAttributeFormats, count: vertexAttributeCount)
    let offsets = copiedArray(vertexAttributeOffsets, count: vertexAttributeCount)
    let attributeBufferSlots = copiedArray(vertexAttributeBufferSlots, count: vertexAttributeCount)
    let bindingBufferSlots = copiedArray(vertexBindingBufferSlots, count: vertexBindingCount)
    let bindingStrides = copiedArray(vertexBindingStrides, count: vertexBindingCount)
    let bindingStepRates = copiedArray(vertexBindingStepRates, count: vertexBindingCount)
    guard formats.count == offsets.count,
          formats.count == attributeBufferSlots.count,
          bindingBufferSlots.count == bindingStrides.count,
          bindingBufferSlots.count == bindingStepRates.count
    else {
        return nil
    }

    let key = DynamicPipelineKey(
        deviceAddress: objectAddress(device),
        vertexSource: vertexSource,
        fragmentSource: fragmentSource,
        vertexEntry: vertexEntry,
        fragmentEntry: fragmentEntry,
        colorFormat: colorFormat.rawValue,
        depthFormat: depthFormat.rawValue,
        stencilFormat: stencilFormat.rawValue,
        vertexAttributes: formats,
        vertexOffsets: offsets,
        vertexAttributeBufferSlots: attributeBufferSlots,
        vertexBindingBufferSlots: bindingBufferSlots,
        vertexBindingStrides: bindingStrides,
        vertexBindingStepRates: bindingStepRates,
        blendEnabled: blendEnabled,
        blendSourceRgb: blendSourceRgb,
        blendDestRgb: blendDestRgb,
        blendOpRgb: blendOpRgb,
        blendSourceAlpha: blendSourceAlpha,
        blendDestAlpha: blendDestAlpha,
        blendOpAlpha: blendOpAlpha,
        writeMask: writeMask
    )

    if let cached = NativeState.dynamicPipelines[key] {
        return cached
    }

    do {
        let vertexLibrary = try device.makeLibrary(source: vertexSource, options: nil)
        let fragmentLibrary = try device.makeLibrary(source: fragmentSource, options: nil)
        guard
            let vertexFunction = vertexLibrary.makeFunction(name: vertexEntry),
            let fragmentFunction = fragmentLibrary.makeFunction(name: fragmentEntry)
        else {
            NSLog("[metallum] Failed to resolve MSL entry points v='%@' f='%@'", vertexEntry, fragmentEntry)
            return nil
        }

        let descriptor = MTLRenderPipelineDescriptor()
        if NativeState.debugLabelsEnabled {
            descriptor.label = "Metallum pipeline \(vertexEntry)->\(fragmentEntry) color=\(colorFormat) depth=\(depthFormat)"
        }
        descriptor.vertexFunction = vertexFunction
        descriptor.fragmentFunction = fragmentFunction
        descriptor.colorAttachments[0].pixelFormat = colorFormat
        descriptor.depthAttachmentPixelFormat = depthFormat
        descriptor.stencilAttachmentPixelFormat = stencilFormat
        descriptor.colorAttachments[0].writeMask = MTLColorWriteMask(rawValue: UInt(writeMask))

        if blendEnabled {
            descriptor.colorAttachments[0].isBlendingEnabled = true
            descriptor.colorAttachments[0].sourceRGBBlendFactor = blendFactor(from: blendSourceRgb)
            descriptor.colorAttachments[0].destinationRGBBlendFactor = blendFactor(from: blendDestRgb)
            descriptor.colorAttachments[0].rgbBlendOperation = blendOperation(from: blendOpRgb)
            descriptor.colorAttachments[0].sourceAlphaBlendFactor = blendFactor(from: blendSourceAlpha)
            descriptor.colorAttachments[0].destinationAlphaBlendFactor = blendFactor(from: blendDestAlpha)
            descriptor.colorAttachments[0].alphaBlendOperation = blendOperation(from: blendOpAlpha)
        } else {
            descriptor.colorAttachments[0].isBlendingEnabled = false
        }

        if vertexAttributeCount > 0 {
            let vertexDescriptor = MTLVertexDescriptor()
            for index in 0..<Int(vertexAttributeCount) {
                let format = vertexFormat(from: formats[index])
                if format == .invalid {
                    NSLog("[metallum] Unsupported vertex attribute format code: %llu", formats[index])
                    return nil
                }
                vertexDescriptor.attributes[index].format = format
                vertexDescriptor.attributes[index].offset = Int(offsets[index])
                vertexDescriptor.attributes[index].bufferIndex = Int(attributeBufferSlots[index])
            }
            for index in 0..<Int(vertexBindingCount) {
                let bufferSlot = Int(bindingBufferSlots[index])
                if bufferSlot < 0 || bufferSlot > metallumMaxVertexBufferSlot {
                    NSLog("[metallum] Unsupported vertex buffer slot: %d", bufferSlot)
                    return nil
                }
                vertexDescriptor.layouts[bufferSlot].stride = Int(bindingStrides[index])
                if bindingStepRates[index] > 0 {
                    vertexDescriptor.layouts[bufferSlot].stepFunction = .perInstance
                    vertexDescriptor.layouts[bufferSlot].stepRate = Int(bindingStepRates[index])
                } else {
                    vertexDescriptor.layouts[bufferSlot].stepFunction = .perVertex
                    vertexDescriptor.layouts[bufferSlot].stepRate = 1
                }
            }
            descriptor.vertexDescriptor = vertexDescriptor
        }

        let pipeline = try device.makeRenderPipelineState(descriptor: descriptor)
        NativeState.dynamicPipelines[key] = pipeline
        return pipeline
    } catch {
        NSLog("[metallum] Failed to create dynamic render pipeline: %@", String(describing: error))
        return nil
    }
}

private func triangleFanOutputIndexCount(sourceCount: UInt64, buffer: MTLBuffer) -> Int? {
    let triangleCount = sourceCount - 2
    guard triangleCount <= UInt64.max / 3 else {
        return nil
    }

    let indexCount = triangleCount * 3
    let bufferIndexCapacity = UInt64(buffer.length / MemoryLayout<UInt32>.stride)
    guard indexCount <= UInt64(Int.max), indexCount <= bufferIndexCapacity else {
        return nil
    }
    return Int(indexCount)
}

private func writeSequentialTriangleFanIndices(_ indexBuffer: MTLBuffer, vertexCount: UInt64) -> Int? {
    guard vertexCount >= 3, vertexCount - 1 <= UInt64(UInt32.max), let generatedIndexCount = triangleFanOutputIndexCount(sourceCount: vertexCount, buffer: indexBuffer) else {
        return nil
    }
    let triangleCount = vertexCount - 2
    let indices = indexBuffer.contents().assumingMemoryBound(to: UInt32.self)
    var writeIndex = 0
    for triangle in 0..<triangleCount {
        indices[writeIndex] = 0
        indices[writeIndex + 1] = UInt32(triangle + 1)
        indices[writeIndex + 2] = UInt32(triangle + 2)
        writeIndex += 3
    }
    return generatedIndexCount
}

private func readIndex(_ indexBuffer: MTLBuffer, byteOffset: UInt64, index: UInt64, indexType: UInt64) -> UInt32 {
    let base = indexBuffer.contents().advanced(by: Int(byteOffset))
    if indexType == 0 {
        return UInt32(base.assumingMemoryBound(to: UInt16.self)[Int(index)])
    }
    return base.assumingMemoryBound(to: UInt32.self)[Int(index)]
}

private func writeIndexedTriangleFanIndices(
    sourceIndexBuffer: MTLBuffer,
    destinationIndexBuffer: MTLBuffer,
    indexType: UInt64,
    indexOffsetBytes: UInt64,
    indexCount: UInt64
) -> Int? {
    guard indexCount >= 3, let generatedIndexCount = triangleFanOutputIndexCount(sourceCount: indexCount, buffer: destinationIndexBuffer) else {
        return nil
    }
    let triangleCount = indexCount - 2
    let center = readIndex(sourceIndexBuffer, byteOffset: indexOffsetBytes, index: 0, indexType: indexType)
    let indices = destinationIndexBuffer.contents().assumingMemoryBound(to: UInt32.self)
    var writeIndex = 0
    for triangle in 0..<triangleCount {
        indices[writeIndex] = center
        indices[writeIndex + 1] = readIndex(sourceIndexBuffer, byteOffset: indexOffsetBytes, index: triangle + 1, indexType: indexType)
        indices[writeIndex + 2] = readIndex(sourceIndexBuffer, byteOffset: indexOffsetBytes, index: triangle + 2, indexType: indexType)
        writeIndex += 3
    }
    return generatedIndexCount
}

@_cdecl("metallum_create_system_default_device")
public func metallum_create_system_default_device() -> UnsafeMutableRawPointer? {
    return withMetalAutoreleasePool {
        retainedPointer(MTLCreateSystemDefaultDevice())
    }
}

@_cdecl("metallum_set_debug_labels_enabled")
public func metallum_set_debug_labels_enabled(_ enabled: Int32) {
    NativeState.debugLabelsEnabled = enabled != 0
}

@_cdecl("metallum_create_command_queue")
public func metallum_create_command_queue(_ devicePtr: UnsafeMutableRawPointer?) -> UnsafeMutableRawPointer? {
    return withMetalAutoreleasePool {
    guard let device: MTLDevice = object(devicePtr) else {
        return nil
    }
    return retainedPointer(device.makeCommandQueue())
    }
}

@_cdecl("metallum_create_buffer")
public func metallum_create_buffer(
    _ devicePtr: UnsafeMutableRawPointer?,
    _ length: UInt64,
    _ options: UInt64,
    _ labelPtr: UnsafePointer<CChar>?
) -> UnsafeMutableRawPointer? {
    return withMetalAutoreleasePool {
    guard let device: MTLDevice = object(devicePtr) else {
        return nil
    }
    guard let buffer = device.makeBuffer(length: Int(length), options: MTLResourceOptions(rawValue: UInt(options))) else {
        return nil
    }
    buffer.label = stringFromOptionalCString(labelPtr)
    return retainedPointer(buffer)
    }
}

@_cdecl("metallum_create_texture_2d")
public func metallum_create_texture_2d(
    _ devicePtr: UnsafeMutableRawPointer?,
    _ pixelFormat: UInt64,
    _ width: UInt64,
    _ height: UInt64,
    _ depthOrLayers: UInt64,
    _ mipLevels: UInt64,
    _ cubeCompatible: UInt64,
    _ usage: UInt64,
    _ storageMode: UInt64,
    _ labelPtr: UnsafePointer<CChar>?
) -> UnsafeMutableRawPointer? {
    return withMetalAutoreleasePool {
    guard let device: MTLDevice = object(devicePtr) else {
        return nil
    }

    let descriptor = MTLTextureDescriptor.texture2DDescriptor(
        pixelFormat: MTLPixelFormat(rawValue: UInt(pixelFormat)) ?? .invalid,
        width: Int(width),
        height: Int(height),
        mipmapped: mipLevels > 1
    )

    if cubeCompatible != 0 {
        if depthOrLayers > 6 {
            descriptor.textureType = MTLTextureType.typeCubeArray
            descriptor.arrayLength = Int(depthOrLayers)
        } else {
            descriptor.textureType = MTLTextureType.typeCube
            descriptor.arrayLength = 1
        }
    } else if depthOrLayers > 1 {
        descriptor.textureType = MTLTextureType.type2DArray
        descriptor.arrayLength = Int(depthOrLayers)
    }

    descriptor.mipmapLevelCount = max(Int(mipLevels), 1)
    descriptor.usage = MTLTextureUsage(rawValue: UInt(usage))
    descriptor.storageMode = MTLStorageMode(rawValue: UInt(storageMode)) ?? .shared
    descriptor.hazardTrackingMode = .tracked
    guard let texture = device.makeTexture(descriptor: descriptor) else {
        return nil
    }
    texture.label = stringFromOptionalCString(labelPtr)
    return retainedPointer(texture)
    }
}

@_cdecl("metallum_create_texture_view")
public func metallum_create_texture_view(_ texturePtr: UnsafeMutableRawPointer?, _ baseMipLevel: UInt64, _ mipLevelCount: UInt64) -> UnsafeMutableRawPointer? {
    return withMetalAutoreleasePool {
    guard let texture: MTLTexture = object(texturePtr), mipLevelCount > 0 else {
        return nil
    }

    let baseLevel = Int(baseMipLevel)
    let levelCount = Int(mipLevelCount)
    guard baseLevel < texture.mipmapLevelCount, baseLevel + levelCount <= texture.mipmapLevelCount else {
        return nil
    }

    let view = texture.__newTextureView(
        with: texture.pixelFormat,
        textureType: texture.textureType,
        levels: NSRange(location: baseLevel, length: levelCount),
        slices: NSRange(location: 0, length: textureSliceCount(texture))
    )
    if NativeState.debugLabelsEnabled {
        view?.label = "\(textureLabel(texture)) view mip=\(baseLevel)..<\(baseLevel + levelCount)"
    }
    return retainedPointer(view)
    }
}

@_cdecl("metallum_create_buffer_texture_view")
public func metallum_create_buffer_texture_view(
    _ bufferPtr: UnsafeMutableRawPointer?,
    _ pixelFormat: UInt64,
    _ offset: UInt64,
    _ width: UInt64,
    _ height: UInt64,
    _ bytesPerRow: UInt64
) -> UnsafeMutableRawPointer? {
    return withMetalAutoreleasePool {
    guard let buffer: MTLBuffer = object(bufferPtr), width > 0, height > 0, bytesPerRow > 0 else {
        return nil
    }

    let descriptor = MTLTextureDescriptor.texture2DDescriptor(
        pixelFormat: MTLPixelFormat(rawValue: UInt(pixelFormat)) ?? .invalid,
        width: Int(width),
        height: Int(height),
        mipmapped: false
    )
    descriptor.usage = MTLTextureUsage.shaderRead
    descriptor.storageMode = buffer.storageMode
    descriptor.hazardTrackingMode = .tracked

    let nativeOffset = Int(offset)
    let nativeBytesPerRow = Int(bytesPerRow)
    let requiredLength = nativeOffset + nativeBytesPerRow * Int(height)
    guard requiredLength <= buffer.length else {
        return nil
    }

    guard let textureView = buffer.makeTexture(descriptor: descriptor, offset: nativeOffset, bytesPerRow: nativeBytesPerRow) else {
        return nil
    }
    if NativeState.debugLabelsEnabled {
        textureView.label = "\(buffer.label ?? "buffer") texel view"
    }
    return retainedPointer(textureView)
    }
}

@_cdecl("metallum_copy_buffer_to_buffer")
public func metallum_copy_buffer_to_buffer(
    _ commandQueuePtr: UnsafeMutableRawPointer?,
    _ sourceBufferPtr: UnsafeMutableRawPointer?,
    _ sourceOffset: UInt64,
    _ destinationBufferPtr: UnsafeMutableRawPointer?,
    _ destinationOffset: UInt64,
    _ length: UInt64
) -> Int32 {
    return withMetalAutoreleasePool {
    guard
        let queue: MTLCommandQueue = object(commandQueuePtr),
        let sourceBuffer: MTLBuffer = object(sourceBufferPtr),
        let destinationBuffer: MTLBuffer = object(destinationBufferPtr),
        length > 0
    else {
        return 1
    }

    guard let commandBuffer = submissionCommandBufferForStandaloneEncoding(queue), let blit = commandBuffer.makeBlitCommandEncoder() else {
        return 1
    }
    setBlitEncoderLabel(blit, "copy buffer \(sourceBuffer.label ?? "buffer") -> \(destinationBuffer.label ?? "buffer")")
    blit.copy(from: sourceBuffer, sourceOffset: Int(sourceOffset), to: destinationBuffer, destinationOffset: Int(destinationOffset), size: Int(length))
    blit.endEncoding()
    return 0
    }
}

@_cdecl("metallum_copy_buffer_to_texture")
public func metallum_copy_buffer_to_texture(
    _ commandQueuePtr: UnsafeMutableRawPointer?,
    _ sourceBufferPtr: UnsafeMutableRawPointer?,
    _ sourceOffset: UInt64,
    _ texturePtr: UnsafeMutableRawPointer?,
    _ mipLevel: UInt64,
    _ slice: UInt64,
    _ x: UInt64,
    _ y: UInt64,
    _ width: UInt64,
    _ height: UInt64,
    _ bytesPerRow: UInt64,
    _ bytesPerImage: UInt64
) -> Int32 {
    return withMetalAutoreleasePool {
    guard
        let queue: MTLCommandQueue = object(commandQueuePtr),
        let sourceBuffer: MTLBuffer = object(sourceBufferPtr),
        let texture: MTLTexture = object(texturePtr),
        width > 0,
        height > 0
    else {
        return 1
    }
    guard let commandBuffer = submissionCommandBufferForStandaloneEncoding(queue), let blit = commandBuffer.makeBlitCommandEncoder() else {
        return 1
    }
    setBlitEncoderLabel(blit, "copy buffer \(sourceBuffer.label ?? "buffer") -> texture \(textureLabel(texture))")
    blit.copy(
        from: sourceBuffer,
        sourceOffset: Int(sourceOffset),
        sourceBytesPerRow: Int(bytesPerRow),
        sourceBytesPerImage: Int(bytesPerImage),
        sourceSize: MTLSize(width: Int(width), height: Int(height), depth: 1),
        to: texture,
        destinationSlice: Int(slice),
        destinationLevel: Int(mipLevel),
        destinationOrigin: MTLOrigin(x: Int(x), y: Int(y), z: 0)
    )
    blit.endEncoding()
    return 0
    }
}

@_cdecl("metallum_copy_texture_to_texture")
public func metallum_copy_texture_to_texture(
    _ commandQueuePtr: UnsafeMutableRawPointer?,
    _ sourceTexturePtr: UnsafeMutableRawPointer?,
    _ destinationTexturePtr: UnsafeMutableRawPointer?,
    _ mipLevel: UInt64,
    _ sourceX: UInt64,
    _ sourceY: UInt64,
    _ destX: UInt64,
    _ destY: UInt64,
    _ width: UInt64,
    _ height: UInt64
) -> Int32 {
    return withMetalAutoreleasePool {
    guard
        let queue: MTLCommandQueue = object(commandQueuePtr),
        let sourceTexture: MTLTexture = object(sourceTexturePtr),
        let destinationTexture: MTLTexture = object(destinationTexturePtr),
        width > 0,
        height > 0
    else {
        return 1
    }
    guard let commandBuffer = submissionCommandBufferForStandaloneEncoding(queue), let blit = commandBuffer.makeBlitCommandEncoder() else {
        return 1
    }
    setBlitEncoderLabel(blit, "copy texture \(textureLabel(sourceTexture)) -> \(textureLabel(destinationTexture))")
    blit.copy(
        from: sourceTexture,
        sourceSlice: 0,
        sourceLevel: Int(mipLevel),
        sourceOrigin: MTLOrigin(x: Int(sourceX), y: Int(sourceY), z: 0),
        sourceSize: MTLSize(width: Int(width), height: Int(height), depth: 1),
        to: destinationTexture,
        destinationSlice: 0,
        destinationLevel: Int(mipLevel),
        destinationOrigin: MTLOrigin(x: Int(destX), y: Int(destY), z: 0)
    )
    blit.endEncoding()
    return 0
    }
}

@_cdecl("metallum_copy_texture_to_buffer")
public func metallum_copy_texture_to_buffer(
    _ commandQueuePtr: UnsafeMutableRawPointer?,
    _ sourceTexturePtr: UnsafeMutableRawPointer?,
    _ destinationBufferPtr: UnsafeMutableRawPointer?,
    _ destinationOffset: UInt64,
    _ mipLevel: UInt64,
    _ slice: UInt64,
    _ x: UInt64,
    _ y: UInt64,
    _ width: UInt64,
    _ height: UInt64,
    _ bytesPerRow: UInt64,
    _ bytesPerImage: UInt64
) -> Int32 {
    return withMetalAutoreleasePool {
    guard
        let queue: MTLCommandQueue = object(commandQueuePtr),
        let sourceTexture: MTLTexture = object(sourceTexturePtr),
        let destinationBuffer: MTLBuffer = object(destinationBufferPtr),
        width > 0,
        height > 0
    else {
        return 1
    }
    guard let commandBuffer = submissionCommandBufferForStandaloneEncoding(queue), let blit = commandBuffer.makeBlitCommandEncoder() else {
        return 1
    }
    setBlitEncoderLabel(blit, "copy texture \(textureLabel(sourceTexture)) -> buffer \(destinationBuffer.label ?? "buffer")")
    blit.copy(
        from: sourceTexture,
        sourceSlice: Int(slice),
        sourceLevel: Int(mipLevel),
        sourceOrigin: MTLOrigin(x: Int(x), y: Int(y), z: 0),
        sourceSize: MTLSize(width: Int(width), height: Int(height), depth: 1),
        to: destinationBuffer,
        destinationOffset: Int(destinationOffset),
        destinationBytesPerRow: Int(bytesPerRow),
        destinationBytesPerImage: Int(bytesPerImage)
    )
    blit.endEncoding()
    return 0
    }
}

@_cdecl("metallum_create_sampler")
public func metallum_create_sampler(
    _ devicePtr: UnsafeMutableRawPointer?,
    _ addressModeU: UInt64,
    _ addressModeV: UInt64,
    _ minFilter: UInt64,
    _ magFilter: UInt64,
    _ mipFilter: UInt64,
    _ maxAnisotropy: Int32,
    _ lodMaxClamp: Double
) -> UnsafeMutableRawPointer? {
    return withMetalAutoreleasePool {
    guard let device: MTLDevice = object(devicePtr) else {
        return nil
    }
    let descriptor = MTLSamplerDescriptor()
    descriptor.minFilter = samplerMinMagFilter(from: minFilter)
    descriptor.magFilter = samplerMinMagFilter(from: magFilter)
    descriptor.mipFilter = samplerMipFilter(from: mipFilter)
    descriptor.sAddressMode = samplerAddressMode(from: addressModeU)
    descriptor.tAddressMode = samplerAddressMode(from: addressModeV)
    descriptor.maxAnisotropy = max(Int(maxAnisotropy), 1)
    descriptor.lodMinClamp = 0.0
    descriptor.lodMaxClamp = lodMaxClamp >= 0.0 && lodMaxClamp.isFinite ? Float(lodMaxClamp) : Float.greatestFiniteMagnitude
    return retainedPointer(device.makeSamplerState(descriptor: descriptor))
    }
}

@_cdecl("metallum_begin_render_pass")
public func metallum_begin_render_pass(
    _ commandQueuePtr: UnsafeMutableRawPointer?,
    _ colorTexturePtr: UnsafeMutableRawPointer?,
    _ depthTexturePtr: UnsafeMutableRawPointer?,
    _ labelPtr: UnsafePointer<CChar>?,
    _ viewportWidth: Double,
    _ viewportHeight: Double,
    _ clearColorEnabled: Int32,
    _ clearColorRed: Float,
    _ clearColorGreen: Float,
    _ clearColorBlue: Float,
    _ clearColorAlpha: Float,
    _ clearDepthEnabled: Int32,
    _ clearDepth: Double
) -> UnsafeMutableRawPointer? {
    return withMetalAutoreleasePool {
    guard
        let queue: MTLCommandQueue = object(commandQueuePtr),
        let colorTexture: MTLTexture = object(colorTexturePtr)
    else {
        return nil
    }
    let depthTexture: MTLTexture? = object(depthTexturePtr)
    let passLabel = stringFromOptionalCString(labelPtr)
    let depthFormat = depthTexture?.pixelFormat ?? .invalid
    let stencilFormat = stencilPixelFormat(for: depthFormat)
    if clearColorEnabled == 0, clearDepthEnabled == 0, let session = reusePendingRenderPassIfCompatible(
        queue,
        colorTexture: colorTexture,
        depthTexture: depthTexture,
        viewportWidth: viewportWidth,
        viewportHeight: viewportHeight
    ) {
        setRenderEncoderLabel(session.encoder, passLabel ?? "render pass \(textureLabel(colorTexture))")
        return UnsafeMutableRawPointer(Unmanaged.passRetained(session).toOpaque())
    }

    let tracker = submitTracker(for: queue)
    finishPendingRenderPass(tracker)
    guard let commandBuffer = ensureSubmissionCommandBuffer(queue, tracker) else {
        return nil
    }

    let renderPass = MTLRenderPassDescriptor()
    renderPass.colorAttachments[0].texture = colorTexture
    if clearColorEnabled != 0 {
        renderPass.colorAttachments[0].loadAction = .clear
        renderPass.colorAttachments[0].clearColor = makeClearColor(red: clearColorRed, green: clearColorGreen, blue: clearColorBlue, alpha: clearColorAlpha)
    } else {
        renderPass.colorAttachments[0].loadAction = .load
    }
    renderPass.colorAttachments[0].storeAction = .store

    if let depthTexture {
        renderPass.depthAttachment.texture = depthTexture
        renderPass.depthAttachment.loadAction = clearDepthEnabled != 0 ? .clear : .load
        renderPass.depthAttachment.clearDepth = clearDepth
        renderPass.depthAttachment.storeAction = .store
        if stencilFormat != .invalid {
            renderPass.stencilAttachment.texture = depthTexture
            renderPass.stencilAttachment.loadAction = .dontCare
            renderPass.stencilAttachment.storeAction = .dontCare
        }
    }

    guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: renderPass) else {
        return nil
    }
    setRenderEncoderLabel(encoder, passLabel ?? "render pass \(textureLabel(colorTexture))")
    encoder.setViewport(MTLViewport(originX: 0.0, originY: 0.0, width: viewportWidth, height: viewportHeight, znear: 0.0, zfar: 1.0))

    let session = RenderPassSession(
        commandBuffer: commandBuffer,
        encoder: encoder,
        device: queue.device,
        colorAttachmentAddress: objectAddress(colorTexture),
        depthAttachmentAddress: depthTexture.map(objectAddress) ?? 0,
        colorFormat: colorTexture.pixelFormat,
        depthFormat: depthFormat,
        stencilFormat: stencilFormat,
        viewportWidth: viewportWidth,
        viewportHeight: viewportHeight
    )
    tracker.pendingRenderPass = session
    return UnsafeMutableRawPointer(Unmanaged.passRetained(session).toOpaque())
    }
}

@_cdecl("metallum_create_render_pipeline")
public func metallum_create_render_pipeline(
    _ devicePtr: UnsafeMutableRawPointer?,
    _ vertexMsl: UnsafePointer<CChar>?,
    _ fragmentMsl: UnsafePointer<CChar>?,
    _ vertexEntryPoint: UnsafePointer<CChar>?,
    _ fragmentEntryPoint: UnsafePointer<CChar>?,
    _ colorFormat: UInt64,
    _ depthFormat: UInt64,
    _ stencilFormat: UInt64,
    _ vertexAttributeFormats: UnsafePointer<UInt64>?,
    _ vertexAttributeOffsets: UnsafePointer<UInt64>?,
    _ vertexAttributeBufferSlots: UnsafePointer<UInt64>?,
    _ vertexAttributeCount: UInt64,
    _ vertexBindingBufferSlots: UnsafePointer<UInt64>?,
    _ vertexBindingStrides: UnsafePointer<UInt64>?,
    _ vertexBindingStepRates: UnsafePointer<UInt64>?,
    _ vertexBindingCount: UInt64,
    _ blendEnabled: Int32,
    _ blendSourceRgb: UInt64,
    _ blendDestRgb: UInt64,
    _ blendOpRgb: UInt64,
    _ blendSourceAlpha: UInt64,
    _ blendDestAlpha: UInt64,
    _ blendOpAlpha: UInt64,
    _ writeMask: UInt64
) -> UnsafeMutableRawPointer? {
    return withMetalAutoreleasePool {
    guard
        let device: MTLDevice = object(devicePtr),
        let vertexMsl,
        let fragmentMsl,
        let vertexEntryPoint,
        let fragmentEntryPoint
    else {
        return nil
    }

    let pipeline = ensureDynamicPipeline(
        device: device,
        vertexSource: String(cString: vertexMsl),
        fragmentSource: String(cString: fragmentMsl),
        vertexEntry: String(cString: vertexEntryPoint),
        fragmentEntry: String(cString: fragmentEntryPoint),
        colorFormat: MTLPixelFormat(rawValue: UInt(colorFormat)) ?? .invalid,
        depthFormat: MTLPixelFormat(rawValue: UInt(depthFormat)) ?? .invalid,
        stencilFormat: MTLPixelFormat(rawValue: UInt(stencilFormat)) ?? .invalid,
        vertexAttributeFormats: vertexAttributeFormats,
        vertexAttributeOffsets: vertexAttributeOffsets,
        vertexAttributeBufferSlots: vertexAttributeBufferSlots,
        vertexAttributeCount: vertexAttributeCount,
        vertexBindingBufferSlots: vertexBindingBufferSlots,
        vertexBindingStrides: vertexBindingStrides,
        vertexBindingStepRates: vertexBindingStepRates,
        vertexBindingCount: vertexBindingCount,
        blendEnabled: blendEnabled != 0,
        blendSourceRgb: blendSourceRgb,
        blendDestRgb: blendDestRgb,
        blendOpRgb: blendOpRgb,
        blendSourceAlpha: blendSourceAlpha,
        blendDestAlpha: blendDestAlpha,
        blendOpAlpha: blendOpAlpha,
        writeMask: writeMask
    )
    return unretainedPointer(pipeline)
    }
}

@_cdecl("metallum_render_pass_set_pipeline")
public func metallum_render_pass_set_pipeline(_ renderPassPtr: UnsafeMutableRawPointer?, _ pipelinePtr: UnsafeMutableRawPointer?) -> Int32 {
    guard let session = renderPassSession(renderPassPtr), let pipeline: MTLRenderPipelineState = object(pipelinePtr) else {
        return 1
    }
    session.encoder.setRenderPipelineState(pipeline)
    return 0
}

@_cdecl("metallum_render_pass_set_depth_stencil_state")
public func metallum_render_pass_set_depth_stencil_state(
    _ renderPassPtr: UnsafeMutableRawPointer?,
    _ depthCompareOp: UInt64,
    _ writeDepth: Int32,
    _ depthBiasScaleFactor: Double,
    _ depthBiasConstant: Double
) -> Int32 {
    guard let session = renderPassSession(renderPassPtr) else {
        return 1
    }
    if session.depthFormat != .invalid, let depthState = ensureDepthStencilState(device: session.device, compareOp: depthCompareOp, writeDepth: writeDepth != 0) {
        session.encoder.setDepthStencilState(depthState)
        session.encoder.setDepthBias(Float(depthBiasConstant), slopeScale: Float(depthBiasScaleFactor), clamp: 0.0)
    }
    return 0
}

@_cdecl("metallum_render_pass_set_raster_state")
public func metallum_render_pass_set_raster_state(_ renderPassPtr: UnsafeMutableRawPointer?, _ cullBackFaces: Int32, _ wireframe: Int32) -> Int32 {
    guard let session = renderPassSession(renderPassPtr) else {
        return 1
    }
    session.encoder.setFrontFacing(.clockwise)
    session.encoder.setCullMode(cullBackFaces != 0 ? .back : .none)
    session.encoder.setTriangleFillMode(wireframe != 0 ? .lines : .fill)
    return 0
}

@_cdecl("metallum_render_pass_set_vertex_buffer")
public func metallum_render_pass_set_vertex_buffer(_ renderPassPtr: UnsafeMutableRawPointer?, _ slot: UInt64, _ bufferPtr: UnsafeMutableRawPointer?, _ offset: UInt64) -> Int32 {
    guard let session = renderPassSession(renderPassPtr) else {
        return 1
    }
    if slot > UInt64(metallumMaxVertexBufferSlot) {
        return 2
    }
    let buffer: MTLBuffer? = object(bufferPtr)
    session.encoder.setVertexBuffer(buffer, offset: Int(offset), index: Int(slot))
    return 0
}

@_cdecl("metallum_render_pass_set_buffer_binding")
public func metallum_render_pass_set_buffer_binding(
    _ renderPassPtr: UnsafeMutableRawPointer?,
    _ binding: UInt64,
    _ bufferPtr: UnsafeMutableRawPointer?,
    _ offset: UInt64,
    _ stageMask: Int32
) -> Int32 {
    guard let session = renderPassSession(renderPassPtr) else {
        return 1
    }
    let buffer: MTLBuffer? = object(bufferPtr)
    let index = Int(binding)
    if (stageMask & 1) != 0 {
        session.encoder.setVertexBuffer(buffer, offset: Int(offset), index: index)
    }
    if (stageMask & 2) != 0 {
        session.encoder.setFragmentBuffer(buffer, offset: Int(offset), index: index)
    }
    return 0
}

@_cdecl("metallum_render_pass_set_texture_binding")
public func metallum_render_pass_set_texture_binding(
    _ renderPassPtr: UnsafeMutableRawPointer?,
    _ binding: UInt64,
    _ texturePtr: UnsafeMutableRawPointer?,
    _ samplerPtr: UnsafeMutableRawPointer?,
    _ stageMask: Int32
) -> Int32 {
    guard let session = renderPassSession(renderPassPtr) else {
        return 1
    }
    let texture: MTLTexture? = object(texturePtr)
    let sampler: MTLSamplerState? = object(samplerPtr)
    let index = Int(binding)
    if (stageMask & 1) != 0 {
        session.encoder.setVertexTexture(texture, index: index)
        session.encoder.setVertexSamplerState(sampler, index: index)
    }
    if (stageMask & 2) != 0 {
        session.encoder.setFragmentTexture(texture, index: index)
        session.encoder.setFragmentSamplerState(sampler, index: index)
    }
    return 0
}

@_cdecl("metallum_render_pass_set_scissor")
public func metallum_render_pass_set_scissor(
    _ renderPassPtr: UnsafeMutableRawPointer?,
    _ scissorEnabled: Int32,
    _ scissorX: Int32,
    _ scissorY: Int32,
    _ scissorWidth: Int32,
    _ scissorHeight: Int32
) -> Int32 {
    guard let session = renderPassSession(renderPassPtr) else {
        return 1
    }
    if scissorEnabled != 0 && scissorWidth > 0 && scissorHeight > 0 {
        session.encoder.setScissorRect(
            MTLScissorRect(
                x: max(Int(scissorX), 0),
                y: max(Int(scissorY), 0),
                width: Int(scissorWidth),
                height: Int(scissorHeight)
            )
        )
    } else {
        session.encoder.setScissorRect(
            MTLScissorRect(
                x: 0,
                y: 0,
                width: Int(max(session.viewportWidth, 0.0)),
                height: Int(max(session.viewportHeight, 0.0))
            )
        )
    }
    return 0
}

@_cdecl("metallum_render_pass_draw_indexed")
public func metallum_render_pass_draw_indexed(
    _ renderPassPtr: UnsafeMutableRawPointer?,
    _ indexBufferPtr: UnsafeMutableRawPointer?,
    _ indexType: UInt64,
    _ primitiveTypeCode: UInt64,
    _ indexOffsetBytes: UInt64,
    _ indexCount: UInt64,
    _ baseVertex: Int64,
    _ instanceCount: UInt64
) -> Int32 {
    guard let session = renderPassSession(renderPassPtr), let indexBuffer: MTLBuffer = object(indexBufferPtr) else {
        return 2
    }
    session.encoder.drawIndexedPrimitives(
        type: primitiveType(from: primitiveTypeCode),
        indexCount: Int(indexCount),
        indexType: indexType == 0 ? .uint16 : .uint32,
        indexBuffer: indexBuffer,
        indexBufferOffset: Int(indexOffsetBytes),
        instanceCount: max(Int(instanceCount), 1),
        baseVertex: Int(baseVertex),
        baseInstance: 0
    )
    return 0
}

@_cdecl("metallum_render_pass_draw_indexed_triangle_fan")
public func metallum_render_pass_draw_indexed_triangle_fan(
    _ renderPassPtr: UnsafeMutableRawPointer?,
    _ indexBufferPtr: UnsafeMutableRawPointer?,
    _ fanIndexBufferPtr: UnsafeMutableRawPointer?,
    _ indexType: UInt64,
    _ indexOffsetBytes: UInt64,
    _ indexCount: UInt64,
    _ baseVertex: Int64,
    _ instanceCount: UInt64
) -> Int32 {
    return withMetalAutoreleasePool {
    guard
        let session = renderPassSession(renderPassPtr),
        let indexBuffer: MTLBuffer = object(indexBufferPtr),
        let fanIndexBuffer: MTLBuffer = object(fanIndexBufferPtr)
    else {
        return 1
    }
    if indexCount < 3 {
        return 0
    }
    guard let generatedIndexCount = writeIndexedTriangleFanIndices(
        sourceIndexBuffer: indexBuffer,
        destinationIndexBuffer: fanIndexBuffer,
        indexType: indexType,
        indexOffsetBytes: indexOffsetBytes,
        indexCount: indexCount
    ) else {
        return 3
    }
    keepResourceAliveUntilCompleted(session, fanIndexBuffer)
    session.encoder.drawIndexedPrimitives(
        type: .triangle,
        indexCount: generatedIndexCount,
        indexType: .uint32,
        indexBuffer: fanIndexBuffer,
        indexBufferOffset: 0,
        instanceCount: max(Int(instanceCount), 1),
        baseVertex: Int(baseVertex),
        baseInstance: 0
    )
    return 0
    }
}

@_cdecl("metallum_render_pass_draw")
public func metallum_render_pass_draw(
    _ renderPassPtr: UnsafeMutableRawPointer?,
    _ primitiveTypeCode: UInt64,
    _ firstVertex: UInt64,
    _ vertexCount: UInt64,
    _ instanceCount: UInt64
) -> Int32 {
    guard let session = renderPassSession(renderPassPtr) else {
        return 1
    }
    let safeInstanceCount = max(Int(instanceCount), 1)
    if safeInstanceCount > 1 {
        session.encoder.drawPrimitives(type: primitiveType(from: primitiveTypeCode), vertexStart: Int(firstVertex), vertexCount: Int(vertexCount), instanceCount: safeInstanceCount)
    } else {
        session.encoder.drawPrimitives(type: primitiveType(from: primitiveTypeCode), vertexStart: Int(firstVertex), vertexCount: Int(vertexCount))
    }
    return 0
}

@_cdecl("metallum_render_pass_draw_triangle_fan")
public func metallum_render_pass_draw_triangle_fan(
    _ renderPassPtr: UnsafeMutableRawPointer?,
    _ fanIndexBufferPtr: UnsafeMutableRawPointer?,
    _ firstVertex: UInt64,
    _ vertexCount: UInt64,
    _ instanceCount: UInt64
) -> Int32 {
    return withMetalAutoreleasePool {
    guard let session = renderPassSession(renderPassPtr), let fanIndexBuffer: MTLBuffer = object(fanIndexBufferPtr) else {
        return 1
    }
    if vertexCount < 3 {
        return 0
    }
    guard let generatedIndexCount = writeSequentialTriangleFanIndices(fanIndexBuffer, vertexCount: vertexCount) else {
        return 3
    }
    keepResourceAliveUntilCompleted(session, fanIndexBuffer)
    session.encoder.drawIndexedPrimitives(
        type: .triangle,
        indexCount: generatedIndexCount,
        indexType: .uint32,
        indexBuffer: fanIndexBuffer,
        indexBufferOffset: 0,
        instanceCount: max(Int(instanceCount), 1),
        baseVertex: Int(firstVertex),
        baseInstance: 0
    )
    return 0
    }
}

@_cdecl("metallum_end_render_pass")
public func metallum_end_render_pass(_ renderPassPtr: UnsafeMutableRawPointer?) -> Int32 {
    guard takeRenderPassSession(renderPassPtr) != nil else {
        return 0
    }
    return 0
}

@_cdecl("metallum_configure_layer")
public func metallum_configure_layer(_ layerPtr: UnsafeMutableRawPointer?, _ width: Double, _ height: Double, _ immediatePresentMode: Int32) -> Int32 {
    return withMetalAutoreleasePool {
    guard let layer: CAMetalLayer = object(layerPtr), width > 0.0, height > 0.0 else {
        return 1
    }
    layer.pixelFormat = .bgra8Unorm
    layer.drawableSize = CGSize(width: width, height: height)
    layer.allowsNextDrawableTimeout = false
    layer.presentsWithTransaction = false
    layer.displaySyncEnabled = immediatePresentMode == 0
    return 0
    }
}

@_cdecl("metallum_acquire_next_drawable")
public func metallum_acquire_next_drawable(
    _ commandQueuePtr: UnsafeMutableRawPointer?,
    _ layerPtr: UnsafeMutableRawPointer?
) -> Int32 {
    return withMetalAutoreleasePool {
    guard
        let queue: MTLCommandQueue = object(commandQueuePtr),
        let layer: CAMetalLayer = object(layerPtr)
    else {
        return 1
    }
    return acquireNextDrawable(queue, layer)
    }
}

@_cdecl("metallum_enqueue_present_texture_to_layer")
public func metallum_enqueue_present_texture_to_layer(
    _ commandQueuePtr: UnsafeMutableRawPointer?,
    _ layerPtr: UnsafeMutableRawPointer?,
    _ sourceTexturePtr: UnsafeMutableRawPointer?
) -> Int32 {
    return withMetalAutoreleasePool {
    guard
        let queue: MTLCommandQueue = object(commandQueuePtr),
        let _: CAMetalLayer = object(layerPtr),
        let sourceTexture: MTLTexture = object(sourceTexturePtr)
    else {
        return 1
    }
    let tracker = submitTracker(for: queue)
    guard tracker.pendingDrawableForPresent == nil else {
        return 2
    }
    guard let drawable = tracker.acquiredDrawable else {
        return 1
    }

    guard
        let commandBuffer = commandBufferForFramePresentEncoding(queue)
    else {
        return 1
    }
    guard encodeTextureToDrawable(commandBuffer: commandBuffer, device: queue.device, drawable: drawable, sourceTexture: sourceTexture) else {
        return 1
    }
    keepObjectAliveUntilCompleted(commandBuffer, drawable)
    keepObjectAliveUntilCompleted(commandBuffer, sourceTexture)
    guard tracker.pendingDrawableForPresent == nil else {
        return 2
    }
    tracker.acquiredDrawable = nil
    tracker.pendingDrawableForPresent = drawable
    return 0
    }
}

@_cdecl("metallum_present_pending_drawable")
public func metallum_present_pending_drawable(_ commandQueuePtr: UnsafeMutableRawPointer?) -> Int32 {
    return withMetalAutoreleasePool {
    guard let queue: MTLCommandQueue = object(commandQueuePtr) else {
        return 1
    }
    return commitPendingDrawablePresent(queue, waitUntilCompleted: false)
    }
}

@_cdecl("metallum_release_object")
public func metallum_release_object(_ obj: UnsafeMutableRawPointer?) {
    guard let obj else {
        return
    }
    Unmanaged<AnyObject>.fromOpaque(obj).release()
}

@_cdecl("metallum_signal_submit")
public func metallum_signal_submit(_ commandQueuePtr: UnsafeMutableRawPointer?, _ submitIndex: UInt64) -> Int32 {
    return withMetalAutoreleasePool {
    guard let queue: MTLCommandQueue = object(commandQueuePtr) else {
        return 1
    }
    return signalSubmit(queue, submitIndex: submitIndex)
    }
}

@_cdecl("metallum_wait_for_submit_completion")
public func metallum_wait_for_submit_completion(_ commandQueuePtr: UnsafeMutableRawPointer?, _ submitIndex: UInt64, _ timeoutMs: UInt64) -> Int32 {
    guard let queue: MTLCommandQueue = object(commandQueuePtr) else {
        return 2
    }
    return waitForSubmitCompletion(queue, submitIndex: submitIndex, timeoutMs: timeoutMs)
}

@_cdecl("metallum_wait_for_command_queue_idle")
public func metallum_wait_for_command_queue_idle(_ commandQueuePtr: UnsafeMutableRawPointer?) -> Int32 {
    guard let queue: MTLCommandQueue = object(commandQueuePtr) else {
        return 1
    }
    guard let commandBuffer = takeSubmissionCommandBufferForSubmit(queue) else {
        return commitPendingDrawablePresent(queue, waitUntilCompleted: true) == 0 ? 0 : 1
    }
    commandBuffer.commit()
    if commitPendingDrawablePresent(queue, waitUntilCompleted: true) != 0 {
        commandBuffer.waitUntilCompleted()
    }
    return 0
}

@_cdecl("metallum_clear_texture")
public func metallum_clear_texture(
    _ commandQueuePtr: UnsafeMutableRawPointer?,
    _ texturePtr: UnsafeMutableRawPointer?,
    _ clearColorEnabled: Int32,
    _ clearColorRed: Float,
    _ clearColorGreen: Float,
    _ clearColorBlue: Float,
    _ clearColorAlpha: Float,
    _ clearDepthEnabled: Int32,
    _ clearDepth: Double
) -> Int32 {
    return withMetalAutoreleasePool {
    guard let queue: MTLCommandQueue = object(commandQueuePtr), let texture: MTLTexture = object(texturePtr) else {
        return 1
    }
    let format = texture.pixelFormat
    let isDepthLike = format == .depth16Unorm || format == .depth32Float || format == .depth24Unorm_stencil8 || format == .depth32Float_stencil8
    guard let commandBuffer = submissionCommandBufferForStandaloneEncoding(queue) else {
        return 1
    }

    let renderPass = MTLRenderPassDescriptor()

    if isDepthLike {
        renderPass.depthAttachment.texture = texture
        renderPass.depthAttachment.loadAction = clearDepthEnabled != 0 ? .clear : .load
        renderPass.depthAttachment.storeAction = .store
        renderPass.depthAttachment.clearDepth = clearDepth
        if format == .depth24Unorm_stencil8 || format == .depth32Float_stencil8 {
            renderPass.stencilAttachment.texture = texture
            renderPass.stencilAttachment.loadAction = .dontCare
            renderPass.stencilAttachment.storeAction = .dontCare
        }
    } else {
        renderPass.colorAttachments[0].texture = texture
        if clearColorEnabled != 0 {
            renderPass.colorAttachments[0].loadAction = .clear
            renderPass.colorAttachments[0].clearColor = makeClearColor(red: clearColorRed, green: clearColorGreen, blue: clearColorBlue, alpha: clearColorAlpha)
        } else {
            renderPass.colorAttachments[0].loadAction = .load
        }
        renderPass.colorAttachments[0].storeAction = .store
    }

    guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: renderPass) else {
        return 1
    }
    setRenderEncoderLabel(encoder, "clear \(textureLabel(texture))")
    encoder.endEncoding()
    return 0
    }
}

private func clearColorDepthTextures(
    _ commandQueuePtr: UnsafeMutableRawPointer?,
    _ colorTexturePtr: UnsafeMutableRawPointer?,
    _ clearColorRed: Float,
    _ clearColorGreen: Float,
    _ clearColorBlue: Float,
    _ clearColorAlpha: Float,
    _ depthTexturePtr: UnsafeMutableRawPointer?,
    _ clearDepth: Double
) -> Int32 {
    return withMetalAutoreleasePool {
    guard
        let queue: MTLCommandQueue = object(commandQueuePtr),
        let colorTexture: MTLTexture = object(colorTexturePtr),
        let depthTexture: MTLTexture = object(depthTexturePtr)
    else {
        return 1
    }
    guard let commandBuffer = submissionCommandBufferForStandaloneEncoding(queue) else {
        return 1
    }

    let renderPass = MTLRenderPassDescriptor()
    renderPass.colorAttachments[0].texture = colorTexture
    renderPass.colorAttachments[0].loadAction = .clear
    renderPass.colorAttachments[0].clearColor = makeClearColor(red: clearColorRed, green: clearColorGreen, blue: clearColorBlue, alpha: clearColorAlpha)
    renderPass.colorAttachments[0].storeAction = .store

    renderPass.depthAttachment.texture = depthTexture
    renderPass.depthAttachment.loadAction = .clear
    renderPass.depthAttachment.clearDepth = clearDepth
    renderPass.depthAttachment.storeAction = .store

    let depthFormat = depthTexture.pixelFormat
    if depthFormat == .depth24Unorm_stencil8 || depthFormat == .depth32Float_stencil8 {
        renderPass.stencilAttachment.texture = depthTexture
        renderPass.stencilAttachment.loadAction = .dontCare
        renderPass.stencilAttachment.storeAction = .dontCare
    }

    guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: renderPass) else {
        return 1
    }
    setRenderEncoderLabel(encoder, "clear color+depth \(textureLabel(colorTexture)) + \(textureLabel(depthTexture))")
    encoder.endEncoding()
    return 0
    }
}

@_cdecl("metallum_clear_color_depth_textures_region")
public func metallum_clear_color_depth_textures_region(
    _ commandQueuePtr: UnsafeMutableRawPointer?,
    _ colorTexturePtr: UnsafeMutableRawPointer?,
    _ clearColorRed: Float,
    _ clearColorGreen: Float,
    _ clearColorBlue: Float,
    _ clearColorAlpha: Float,
    _ depthTexturePtr: UnsafeMutableRawPointer?,
    _ clearDepth: Double,
    _ x: Int32,
    _ y: Int32,
    _ width: Int32,
    _ height: Int32
) -> Int32 {
    return withMetalAutoreleasePool {
    guard
        let queue: MTLCommandQueue = object(commandQueuePtr),
        let colorTexture: MTLTexture = object(colorTexturePtr),
        let depthTexture: MTLTexture = object(depthTexturePtr),
        width > 0,
        height > 0
    else {
        return 1
    }

    let textureWidth = min(colorTexture.width, depthTexture.width)
    let textureHeight = min(colorTexture.height, depthTexture.height)
    let clampedX = max(Int(x), 0)
    let clampedY = max(Int(y), 0)
    let clampedMaxX = min(Int(x) + Int(width), textureWidth)
    let clampedMaxY = min(Int(y) + Int(height), textureHeight)
    if clampedX >= clampedMaxX || clampedY >= clampedMaxY {
        return 0
    }
    let scissorRect = MTLScissorRect(x: clampedX, y: clampedY, width: clampedMaxX - clampedX, height: clampedMaxY - clampedY)
    if clampedX == 0 && clampedY == 0 && clampedMaxX == textureWidth && clampedMaxY == textureHeight {
        return clearColorDepthTextures(commandQueuePtr, colorTexturePtr, clearColorRed, clearColorGreen, clearColorBlue, clearColorAlpha, depthTexturePtr, clearDepth)
    }

    guard
        let commandBuffer = submissionCommandBufferForStandaloneEncoding(queue),
        let pipeline = ensureClearColorDepthPipeline(queue.device, colorTexture.pixelFormat, depthTexture.pixelFormat),
        let depthState = ensureDepthStencilState(device: queue.device, compareOp: 1, writeDepth: true)
    else {
        return 1
    }

    let renderPass = MTLRenderPassDescriptor()
    renderPass.colorAttachments[0].texture = colorTexture
    renderPass.colorAttachments[0].loadAction = .load
    renderPass.colorAttachments[0].storeAction = .store

    renderPass.depthAttachment.texture = depthTexture
    renderPass.depthAttachment.loadAction = .load
    renderPass.depthAttachment.storeAction = .store

    let depthFormat = depthTexture.pixelFormat
    if depthFormat == .depth24Unorm_stencil8 || depthFormat == .depth32Float_stencil8 {
        renderPass.stencilAttachment.texture = depthTexture
        renderPass.stencilAttachment.loadAction = .dontCare
        renderPass.stencilAttachment.storeAction = .dontCare
    }

    guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: renderPass) else {
        return 1
    }
    setRenderEncoderLabel(encoder, "clear color+depth region \(textureLabel(colorTexture)) + \(textureLabel(depthTexture))")
    encodeClearDraw(
        encoder: encoder,
        pipeline: pipeline,
        textureWidth: textureWidth,
        textureHeight: textureHeight,
        clearColor: SIMD4<Float>(clearColorRed, clearColorGreen, clearColorBlue, clearColorAlpha),
        scissorRect: scissorRect,
        depthState: depthState,
        clearDepth: clearDepth
    )
    encoder.endEncoding()
    return 0
    }
}

@_cdecl("metallum_get_buffer_contents")
public func metallum_get_buffer_contents(_ bufferPtr: UnsafeMutableRawPointer?) -> UnsafeMutableRawPointer? {
    guard let buffer: MTLBuffer = object(bufferPtr) else {
        return nil
    }
    return buffer.contents()
}
