package com.metallum.client.metal;

import com.sun.jna.Pointer;
import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
final class MetalNativeBridge {
	private static final String RESOURCE_PATH = "/natives/macos/libmetallum.dylib";
	private static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT;
	private static final ValueLayout.OfLong LONG = ValueLayout.JAVA_LONG;
	private static final ValueLayout.OfDouble DOUBLE = ValueLayout.JAVA_DOUBLE;
	private static final Linker LINKER = Linker.nativeLinker();

	static final MetalNativeBridge INSTANCE = loadNative();

	private final Arena libraryArena;
	private final MethodHandle createSystemDefaultDevice;
	private final MethodHandle setDebugLabelsEnabled;
	private final MethodHandle createCommandQueue;
	private final MethodHandle acquireNextDrawable;
	private final MethodHandle enqueuePresentTextureToLayer;
	private final MethodHandle presentPendingDrawable;
	private final MethodHandle createBuffer;
	private final MethodHandle uploadBufferRegionAsync;
	private final MethodHandle copyBufferToBuffer;
	private final MethodHandle createTexture2d;
	private final MethodHandle createTextureView;
	private final MethodHandle createBufferTextureView;
	private final MethodHandle copyBufferToTexture;
	private final MethodHandle copyTextureToTexture;
	private final MethodHandle copyTextureToBuffer;
	private final MethodHandle createSampler;
	private final MethodHandle beginRenderPass;
	private final MethodHandle createRenderPipeline;
	private final MethodHandle renderPassSetPipeline;
	private final MethodHandle renderPassSetDepthStencilState;
	private final MethodHandle renderPassSetRasterState;
	private final MethodHandle renderPassSetVertexBuffer;
	private final MethodHandle renderPassSetBufferBinding;
	private final MethodHandle renderPassSetTextureBinding;
	private final MethodHandle renderPassSetScissor;
	private final MethodHandle renderPassDrawIndexed;
	private final MethodHandle renderPassDrawIndexedTriangleFan;
	private final MethodHandle renderPassDraw;
	private final MethodHandle renderPassDrawTriangleFan;
	private final MethodHandle endRenderPass;
	private final MethodHandle configureLayer;
	private final MethodHandle signalSubmit;
	private final MethodHandle waitForSubmitCompletion;
	private final MethodHandle releaseObject;
	private final MethodHandle waitForCommandQueueIdle;
	private final MethodHandle clearTexture;
	private final MethodHandle clearColorTextureRegion;
	private final MethodHandle clearColorDepthTextures;
	private final MethodHandle clearColorDepthTexturesRegion;
	private final MethodHandle getBufferContents;

	private MetalNativeBridge(final Arena libraryArena, final SymbolLookup lookup) {
		this.libraryArena = libraryArena;
		this.createSystemDefaultDevice = downcall(lookup, "metallum_create_system_default_device", FunctionDescriptor.of(ValueLayout.ADDRESS));
		this.setDebugLabelsEnabled = downcall(lookup, "metallum_set_debug_labels_enabled", FunctionDescriptor.ofVoid(INT));
		this.createCommandQueue = downcall(lookup, "metallum_create_command_queue", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
		this.acquireNextDrawable = downcall(lookup, "metallum_acquire_next_drawable", FunctionDescriptor.of(INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
		this.enqueuePresentTextureToLayer = downcall(lookup, "metallum_enqueue_present_texture_to_layer", FunctionDescriptor.of(INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
		this.presentPendingDrawable = downcall(lookup, "metallum_present_pending_drawable", FunctionDescriptor.of(INT, ValueLayout.ADDRESS));
		this.createBuffer = downcall(lookup, "metallum_create_buffer", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG, ValueLayout.ADDRESS));
		this.uploadBufferRegionAsync = downcall(lookup, "metallum_upload_buffer_region_async", FunctionDescriptor.of(INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, ValueLayout.ADDRESS, LONG));
		this.copyBufferToBuffer = downcall(lookup, "metallum_copy_buffer_to_buffer", FunctionDescriptor.of(INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, ValueLayout.ADDRESS, LONG, LONG));
		this.createTexture2d = downcall(
			lookup,
			"metallum_create_texture_2d",
			FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG, LONG, LONG, LONG, LONG, LONG, LONG, ValueLayout.ADDRESS)
		);
		this.createTextureView = downcall(lookup, "metallum_create_texture_view", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG));
		this.createBufferTextureView = downcall(
			lookup,
			"metallum_create_buffer_texture_view",
			FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG, LONG, LONG, LONG)
		);
		this.copyBufferToTexture = downcall(
			lookup,
			"metallum_copy_buffer_to_texture",
			FunctionDescriptor.of(INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, ValueLayout.ADDRESS, LONG, LONG, LONG, LONG, LONG, LONG, LONG, LONG)
		);
		this.copyTextureToTexture = downcall(
			lookup,
			"metallum_copy_texture_to_texture",
			FunctionDescriptor.of(INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG, LONG, LONG, LONG, LONG, LONG)
		);
		this.copyTextureToBuffer = downcall(
			lookup,
			"metallum_copy_texture_to_buffer",
			FunctionDescriptor.of(INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG, LONG, LONG, LONG, LONG, LONG, LONG, LONG)
		);
		this.createSampler = downcall(
			lookup,
			"metallum_create_sampler",
			FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG, LONG, LONG, LONG, INT, DOUBLE)
		);
		this.beginRenderPass = downcall(
			lookup,
			"metallum_begin_render_pass",
			FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, DOUBLE, DOUBLE, INT, INT, INT, DOUBLE)
		);
		this.createRenderPipeline = downcall(
			lookup,
			"metallum_create_render_pipeline",
			FunctionDescriptor.of(
				ValueLayout.ADDRESS,
				ValueLayout.ADDRESS,
				ValueLayout.ADDRESS,
				ValueLayout.ADDRESS,
				ValueLayout.ADDRESS,
				ValueLayout.ADDRESS,
				LONG,
				LONG,
				LONG,
				LONG,
				ValueLayout.ADDRESS,
				ValueLayout.ADDRESS,
				LONG,
				INT,
				LONG,
				LONG,
				LONG,
				LONG,
				LONG,
				LONG,
				LONG
			)
		);
		this.renderPassSetPipeline = downcall(lookup, "metallum_render_pass_set_pipeline", FunctionDescriptor.of(INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
		this.renderPassSetDepthStencilState = downcall(
			lookup,
			"metallum_render_pass_set_depth_stencil_state",
			FunctionDescriptor.of(INT, ValueLayout.ADDRESS, LONG, INT, DOUBLE, DOUBLE)
		);
		this.renderPassSetRasterState = downcall(lookup, "metallum_render_pass_set_raster_state", FunctionDescriptor.of(INT, ValueLayout.ADDRESS, INT, INT, INT));
		this.renderPassSetVertexBuffer = downcall(lookup, "metallum_render_pass_set_vertex_buffer", FunctionDescriptor.of(INT, ValueLayout.ADDRESS, LONG, ValueLayout.ADDRESS, LONG));
		this.renderPassSetBufferBinding = downcall(lookup, "metallum_render_pass_set_buffer_binding", FunctionDescriptor.of(INT, ValueLayout.ADDRESS, LONG, ValueLayout.ADDRESS, LONG, INT));
		this.renderPassSetTextureBinding = downcall(lookup, "metallum_render_pass_set_texture_binding", FunctionDescriptor.of(INT, ValueLayout.ADDRESS, LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS, INT));
		this.renderPassSetScissor = downcall(lookup, "metallum_render_pass_set_scissor", FunctionDescriptor.of(INT, ValueLayout.ADDRESS, INT, INT, INT, INT, INT));
		this.renderPassDrawIndexed = downcall(lookup, "metallum_render_pass_draw_indexed", FunctionDescriptor.of(INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG, LONG, LONG, LONG, LONG));
		this.renderPassDrawIndexedTriangleFan = downcall(lookup, "metallum_render_pass_draw_indexed_triangle_fan", FunctionDescriptor.of(INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG, LONG, LONG, LONG));
		this.renderPassDraw = downcall(lookup, "metallum_render_pass_draw", FunctionDescriptor.of(INT, ValueLayout.ADDRESS, LONG, LONG, LONG, LONG));
		this.renderPassDrawTriangleFan = downcall(lookup, "metallum_render_pass_draw_triangle_fan", FunctionDescriptor.of(INT, ValueLayout.ADDRESS, LONG, LONG, LONG));
		this.endRenderPass = downcall(lookup, "metallum_end_render_pass", FunctionDescriptor.of(INT, ValueLayout.ADDRESS));
		this.configureLayer = downcall(lookup, "metallum_configure_layer", FunctionDescriptor.of(INT, ValueLayout.ADDRESS, DOUBLE, DOUBLE, INT));
		this.signalSubmit = downcall(lookup, "metallum_signal_submit", FunctionDescriptor.of(INT, ValueLayout.ADDRESS, LONG));
		this.waitForSubmitCompletion = downcall(lookup, "metallum_wait_for_submit_completion", FunctionDescriptor.of(INT, ValueLayout.ADDRESS, LONG, LONG));
		this.releaseObject = downcall(lookup, "metallum_release_object", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
		this.waitForCommandQueueIdle = downcall(lookup, "metallum_wait_for_command_queue_idle", FunctionDescriptor.of(INT, ValueLayout.ADDRESS));
		this.clearTexture = downcall(lookup, "metallum_clear_texture", FunctionDescriptor.of(INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, INT, INT, INT, DOUBLE));
		this.clearColorTextureRegion = downcall(
			lookup,
			"metallum_clear_color_texture_region",
			FunctionDescriptor.of(INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, INT, INT, INT, INT, INT)
		);
		this.clearColorDepthTextures = downcall(
			lookup,
			"metallum_clear_color_depth_textures",
			FunctionDescriptor.of(INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, INT, ValueLayout.ADDRESS, DOUBLE)
		);
		this.clearColorDepthTexturesRegion = downcall(
			lookup,
			"metallum_clear_color_depth_textures_region",
			FunctionDescriptor.of(INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, INT, ValueLayout.ADDRESS, DOUBLE, INT, INT, INT, INT)
		);
		this.getBufferContents = downcall(lookup, "metallum_get_buffer_contents", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
	}

	private static MetalNativeBridge loadNative() {
		try {
			Path tempLib = Files.createTempFile("metallum-native-", ".dylib");
			tempLib.toFile().deleteOnExit();
			try (InputStream stream = MetalNativeBridge.class.getResourceAsStream(RESOURCE_PATH)) {
				if (stream == null) {
					throw new IllegalStateException("Missing native library resource: " + RESOURCE_PATH);
				}
				Files.copy(stream, tempLib, StandardCopyOption.REPLACE_EXISTING);
			}

			Arena arena = Arena.ofShared();
			SymbolLookup lookup = SymbolLookup.libraryLookup(tempLib, arena);
			return new MetalNativeBridge(arena, lookup);
		} catch (IOException e) {
			throw new IllegalStateException("Failed to load Metal native bridge", e);
		}
	}

	private static MethodHandle downcall(final SymbolLookup lookup, final String symbol, final FunctionDescriptor descriptor) {
		return LINKER.downcallHandle(lookup.findOrThrow(symbol), descriptor);
	}

	Pointer metallum_create_system_default_device() {
		try {
			return toPointer((MemorySegment)this.createSystemDefaultDevice.invokeExact());
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_create_system_default_device", throwable);
		}
	}

	void metallum_set_debug_labels_enabled(final boolean enabled) {
		try {
			this.setDebugLabelsEnabled.invokeExact(enabled ? 1 : 0);
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_set_debug_labels_enabled", throwable);
		}
	}

	Pointer metallum_create_command_queue(final Pointer device) {
		try {
			return toPointer((MemorySegment)this.createCommandQueue.invokeExact(toSegment(device)));
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_create_command_queue", throwable);
		}
	}

	Pointer metallum_create_buffer(final Pointer device, final long length, final long options, final String label) {
		try (Arena arena = Arena.ofConfined()) {
			return toPointer((MemorySegment)this.createBuffer.invokeExact(toSegment(device), length, options, toCString(arena, label)));
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_create_buffer", throwable);
		}
	}

	int metallum_upload_buffer_region_async(
		final Pointer commandQueue,
		final Pointer destinationBuffer,
		final long destinationOffset,
		final ByteBuffer bytes,
		final long length
	) {
		try (Arena arena = Arena.ofConfined()) {
			return (int)this.uploadBufferRegionAsync.invokeExact(
				toSegment(commandQueue),
				toSegment(destinationBuffer),
				destinationOffset,
				toByteSegment(arena, bytes, length),
				length
			);
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_upload_buffer_region_async", throwable);
		}
	}

	int metallum_copy_buffer_to_buffer(
		final Pointer commandQueue,
		final Pointer sourceBuffer,
		final long sourceOffset,
		final Pointer destinationBuffer,
		final long destinationOffset,
		final long length
	) {
		try {
			return (int)this.copyBufferToBuffer.invokeExact(
				toSegment(commandQueue),
				toSegment(sourceBuffer),
				sourceOffset,
				toSegment(destinationBuffer),
				destinationOffset,
				length
			);
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_copy_buffer_to_buffer", throwable);
		}
	}

	Pointer metallum_create_texture_2d(
		final Pointer device,
		final long pixelFormat,
		final long width,
		final long height,
		final long depthOrLayers,
		final long mipLevels,
		final long cubeCompatible,
		final long usage,
		final long storageMode,
		final String label
	) {
		try (Arena arena = Arena.ofConfined()) {
			return toPointer((MemorySegment)this.createTexture2d.invokeExact(
				toSegment(device),
				pixelFormat,
				width,
				height,
				depthOrLayers,
				mipLevels,
				cubeCompatible,
				usage,
				storageMode,
				toCString(arena, label)
			));
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_create_texture_2d", throwable);
		}
	}

	Pointer metallum_create_texture_view(final Pointer texture, final long baseMipLevel, final long mipLevelCount) {
		try {
			return toPointer((MemorySegment)this.createTextureView.invokeExact(toSegment(texture), baseMipLevel, mipLevelCount));
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_create_texture_view", throwable);
		}
	}

	Pointer metallum_create_buffer_texture_view(
		final Pointer buffer,
		final long pixelFormat,
		final long offset,
		final long width,
		final long height,
		final long bytesPerRow
	) {
		try {
			return toPointer((MemorySegment)this.createBufferTextureView.invokeExact(toSegment(buffer), pixelFormat, offset, width, height, bytesPerRow));
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_create_buffer_texture_view", throwable);
		}
	}

	int metallum_copy_buffer_to_texture(
		final Pointer commandQueue,
		final Pointer sourceBuffer,
		final long sourceOffset,
		final Pointer texture,
		final long mipLevel,
		final long slice,
		final long x,
		final long y,
		final long width,
		final long height,
		final long bytesPerRow,
		final long bytesPerImage
	) {
		try {
			return (int)this.copyBufferToTexture.invokeExact(
				toSegment(commandQueue),
				toSegment(sourceBuffer),
				sourceOffset,
				toSegment(texture),
				mipLevel,
				slice,
				x,
				y,
				width,
				height,
				bytesPerRow,
				bytesPerImage
			);
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_copy_buffer_to_texture", throwable);
		}
	}

	int metallum_copy_texture_to_texture(
		final Pointer commandQueue,
		final Pointer sourceTexture,
		final Pointer destinationTexture,
		final long mipLevel,
		final long sourceX,
		final long sourceY,
		final long destX,
		final long destY,
		final long width,
		final long height
	) {
		try {
			return (int)this.copyTextureToTexture.invokeExact(
				toSegment(commandQueue),
				toSegment(sourceTexture),
				toSegment(destinationTexture),
				mipLevel,
				sourceX,
				sourceY,
				destX,
				destY,
				width,
				height
			);
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_copy_texture_to_texture", throwable);
		}
	}

	int metallum_copy_texture_to_buffer(
		final Pointer commandQueue,
		final Pointer sourceTexture,
		final Pointer destinationBuffer,
		final long destinationOffset,
		final long mipLevel,
		final long slice,
		final long x,
		final long y,
		final long width,
		final long height,
		final long bytesPerRow,
		final long bytesPerImage
	) {
		try {
			return (int)this.copyTextureToBuffer.invokeExact(
				toSegment(commandQueue),
				toSegment(sourceTexture),
				toSegment(destinationBuffer),
				destinationOffset,
				mipLevel,
				slice,
				x,
				y,
				width,
				height,
				bytesPerRow,
				bytesPerImage
			);
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_copy_texture_to_buffer", throwable);
		}
	}

	Pointer metallum_create_sampler(
		final Pointer device,
		final long addressModeU,
		final long addressModeV,
		final long minFilter,
		final long magFilter,
		final long mipFilter,
		final int maxAnisotropy,
		final double lodMaxClamp
	) {
		try {
			return toPointer((MemorySegment)this.createSampler.invokeExact(
				toSegment(device),
				addressModeU,
				addressModeV,
				minFilter,
				magFilter,
				mipFilter,
				maxAnisotropy,
				lodMaxClamp
			));
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_create_sampler", throwable);
		}
	}

	Pointer metallum_begin_render_pass(
		final Pointer commandQueue,
		final Pointer colorTexture,
		final Pointer depthTexture,
		final double viewportWidth,
		final double viewportHeight,
		final int clearColorEnabled,
		final int clearColor,
		final int clearDepthEnabled,
		final double clearDepth,
		final String label
	) {
		try (Arena arena = Arena.ofConfined()) {
			return toPointer((MemorySegment)this.beginRenderPass.invokeExact(
				toSegment(commandQueue),
				toSegment(colorTexture),
				toSegment(depthTexture),
				toCString(arena, label),
				viewportWidth,
				viewportHeight,
				clearColorEnabled,
				clearColor,
				clearDepthEnabled,
				clearDepth
			));
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_begin_render_pass", throwable);
		}
	}

	Pointer metallum_create_render_pipeline(
		final Pointer device,
		final String vertexMsl,
		final String fragmentMsl,
		final String vertexEntryPoint,
		final String fragmentEntryPoint,
		final long colorFormat,
		final long depthFormat,
		final long stencilFormat,
		final long vertexStride,
		final long[] vertexAttributeFormats,
		final long[] vertexAttributeOffsets,
		final long vertexAttributeCount,
		final int blendEnabled,
		final long blendSourceRgb,
		final long blendDestRgb,
		final long blendOpRgb,
		final long blendSourceAlpha,
		final long blendDestAlpha,
		final long blendOpAlpha,
		final long writeMask
	) {
		try (Arena arena = Arena.ofConfined()) {
			return toPointer((MemorySegment)this.createRenderPipeline.invokeExact(
				toSegment(device),
				toCString(arena, vertexMsl),
				toCString(arena, fragmentMsl),
				toCString(arena, vertexEntryPoint),
				toCString(arena, fragmentEntryPoint),
				colorFormat,
				depthFormat,
				stencilFormat,
				vertexStride,
				toLongArray(arena, vertexAttributeFormats),
				toLongArray(arena, vertexAttributeOffsets),
				vertexAttributeCount,
				blendEnabled,
				blendSourceRgb,
				blendDestRgb,
				blendOpRgb,
				blendSourceAlpha,
				blendDestAlpha,
				blendOpAlpha,
				writeMask
			));
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_create_render_pipeline", throwable);
		}
	}

	int metallum_render_pass_set_pipeline(final Pointer renderPass, final Pointer pipeline) {
		try {
			return (int)this.renderPassSetPipeline.invokeExact(toSegment(renderPass), toSegment(pipeline));
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_render_pass_set_pipeline", throwable);
		}
	}

	int metallum_render_pass_set_depth_stencil_state(
		final Pointer renderPass,
		final long depthCompareOp,
		final int writeDepth,
		final double depthBiasScaleFactor,
		final double depthBiasConstant
	) {
		try {
			return (int)this.renderPassSetDepthStencilState.invokeExact(
				toSegment(renderPass),
				depthCompareOp,
				writeDepth,
				depthBiasScaleFactor,
				depthBiasConstant
			);
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_render_pass_set_depth_stencil_state", throwable);
		}
	}

	int metallum_render_pass_set_raster_state(final Pointer renderPass, final int cullBackFaces, final int wireframe, final int flipVertexY) {
		try {
			return (int)this.renderPassSetRasterState.invokeExact(toSegment(renderPass), cullBackFaces, wireframe, flipVertexY);
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_render_pass_set_raster_state", throwable);
		}
	}

	int metallum_render_pass_set_vertex_buffer(final Pointer renderPass, final long slot, final Pointer buffer, final long offset) {
		try {
			return (int)this.renderPassSetVertexBuffer.invokeExact(toSegment(renderPass), slot, toSegment(buffer), offset);
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_render_pass_set_vertex_buffer", throwable);
		}
	}

	int metallum_render_pass_set_buffer_binding(final Pointer renderPass, final long binding, final Pointer buffer, final long offset, final int stageMask) {
		try {
			return (int)this.renderPassSetBufferBinding.invokeExact(toSegment(renderPass), binding, toSegment(buffer), offset, stageMask);
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_render_pass_set_buffer_binding", throwable);
		}
	}

	int metallum_render_pass_set_texture_binding(
		final Pointer renderPass,
		final long binding,
		final Pointer texture,
		final Pointer sampler,
		final int stageMask
	) {
		try {
			return (int)this.renderPassSetTextureBinding.invokeExact(
				toSegment(renderPass),
				binding,
				toSegment(texture),
				toSegment(sampler),
				stageMask
			);
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_render_pass_set_texture_binding", throwable);
		}
	}

	int metallum_render_pass_set_scissor(
		final Pointer renderPass,
		final int scissorEnabled,
		final int scissorX,
		final int scissorY,
		final int scissorWidth,
		final int scissorHeight
	) {
		try {
			return (int)this.renderPassSetScissor.invokeExact(
				toSegment(renderPass),
				scissorEnabled,
				scissorX,
				scissorY,
				scissorWidth,
				scissorHeight
			);
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_render_pass_set_scissor", throwable);
		}
	}

	int metallum_render_pass_draw_indexed(
		final Pointer renderPass,
		final Pointer indexBuffer,
		final long indexType,
		final long primitiveType,
		final long indexOffsetBytes,
		final long indexCount,
		final long baseVertex,
		final long instanceCount
	) {
		try {
			return (int)this.renderPassDrawIndexed.invokeExact(
				renderPassSegment(renderPass),
				toSegment(indexBuffer),
				indexType,
				primitiveType,
				indexOffsetBytes,
				indexCount,
				baseVertex,
				instanceCount
			);
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_render_pass_draw_indexed", throwable);
		}
	}

	int metallum_render_pass_draw_indexed_triangle_fan(
		final Pointer renderPass,
		final Pointer indexBuffer,
		final long indexType,
		final long indexOffsetBytes,
		final long indexCount,
		final long baseVertex,
		final long instanceCount
	) {
		try {
			return (int)this.renderPassDrawIndexedTriangleFan.invokeExact(
				renderPassSegment(renderPass),
				toSegment(indexBuffer),
				indexType,
				indexOffsetBytes,
				indexCount,
				baseVertex,
				instanceCount
			);
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_render_pass_draw_indexed_triangle_fan", throwable);
		}
	}

	int metallum_render_pass_draw(
		final Pointer renderPass,
		final long primitiveType,
		final long firstVertex,
		final long vertexCount,
		final long instanceCount
	) {
		try {
			return (int)this.renderPassDraw.invokeExact(renderPassSegment(renderPass), primitiveType, firstVertex, vertexCount, instanceCount);
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_render_pass_draw", throwable);
		}
	}

	int metallum_render_pass_draw_triangle_fan(final Pointer renderPass, final long firstVertex, final long vertexCount, final long instanceCount) {
		try {
			return (int)this.renderPassDrawTriangleFan.invokeExact(renderPassSegment(renderPass), firstVertex, vertexCount, instanceCount);
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_render_pass_draw_triangle_fan", throwable);
		}
	}

	int metallum_end_render_pass(final Pointer renderPass) {
		try {
			return (int)this.endRenderPass.invokeExact(renderPassSegment(renderPass));
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_end_render_pass", throwable);
		}
	}

	int metallum_configure_layer(final Pointer layer, final double width, final double height, final int immediatePresentMode) {
		try {
			return (int)this.configureLayer.invokeExact(toSegment(layer), width, height, immediatePresentMode);
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_configure_layer", throwable);
		}
	}

	int metallum_acquire_next_drawable(final Pointer commandQueue, final Pointer layer) {
		try {
			return (int)this.acquireNextDrawable.invokeExact(toSegment(commandQueue), toSegment(layer));
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_acquire_next_drawable", throwable);
		}
	}

	int metallum_enqueue_present_texture_to_layer(final Pointer commandQueue, final Pointer layer, final Pointer sourceTexture) {
		try {
			return (int)this.enqueuePresentTextureToLayer.invokeExact(toSegment(commandQueue), toSegment(layer), toSegment(sourceTexture));
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_enqueue_present_texture_to_layer", throwable);
		}
	}

	int metallum_present_pending_drawable(final Pointer commandQueue) {
		try {
			return (int)this.presentPendingDrawable.invokeExact(toSegment(commandQueue));
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_present_pending_drawable", throwable);
		}
	}

	int metallum_signal_submit(final Pointer commandQueue, final long submitIndex) {
		try {
			return (int)this.signalSubmit.invokeExact(toSegment(commandQueue), submitIndex);
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_signal_submit", throwable);
		}
	}

	int metallum_wait_for_submit_completion(final Pointer commandQueue, final long submitIndex, final long timeoutMs) {
		try {
			return (int)this.waitForSubmitCompletion.invokeExact(toSegment(commandQueue), submitIndex, timeoutMs);
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_wait_for_submit_completion", throwable);
		}
	}

	void metallum_release_object(final Pointer object) {
		try {
			this.releaseObject.invokeExact(toSegment(object));
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_release_object", throwable);
		}
	}

	int metallum_wait_for_command_queue_idle(final Pointer commandQueue) {
		try {
			return (int)this.waitForCommandQueueIdle.invokeExact(toSegment(commandQueue));
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_wait_for_command_queue_idle", throwable);
		}
	}

	int metallum_clear_texture(
		final Pointer commandQueue,
		final Pointer texture,
		final int clearColorEnabled,
		final int clearColor,
		final int clearDepthEnabled,
		final double clearDepth
	) {
		try {
			return (int)this.clearTexture.invokeExact(
				toSegment(commandQueue),
				toSegment(texture),
				clearColorEnabled,
				clearColor,
				clearDepthEnabled,
				clearDepth
			);
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_clear_texture", throwable);
		}
	}

	int metallum_clear_color_depth_textures(
		final Pointer commandQueue,
		final Pointer colorTexture,
		final int clearColor,
		final Pointer depthTexture,
		final double clearDepth
	) {
		try {
			return (int)this.clearColorDepthTextures.invokeExact(
				toSegment(commandQueue),
				toSegment(colorTexture),
				clearColor,
				toSegment(depthTexture),
				clearDepth
			);
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_clear_color_depth_textures", throwable);
		}
	}

	int metallum_clear_color_depth_textures_region(
		final Pointer commandQueue,
		final Pointer colorTexture,
		final int clearColor,
		final Pointer depthTexture,
		final double clearDepth,
		final int x,
		final int y,
		final int width,
		final int height
	) {
		try {
			return (int)this.clearColorDepthTexturesRegion.invokeExact(
				toSegment(commandQueue),
				toSegment(colorTexture),
				clearColor,
				toSegment(depthTexture),
				clearDepth,
				x,
				y,
				width,
				height
			);
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_clear_color_depth_textures_region", throwable);
		}
	}

	int metallum_clear_color_texture_region(
		final Pointer commandQueue,
		final Pointer texture,
		final int clearColor,
		final int x,
		final int y,
		final int width,
		final int height
	) {
		try {
			return (int)this.clearColorTextureRegion.invokeExact(
				toSegment(commandQueue),
				toSegment(texture),
				clearColor,
				x,
				y,
				width,
				height
			);
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_clear_color_texture_region", throwable);
		}
	}


	Pointer metallum_get_buffer_contents(final Pointer buffer) {
		try {
			return toPointer((MemorySegment)this.getBufferContents.invokeExact(toSegment(buffer)));
		} catch (Throwable throwable) {
			throw bridgeFailure("metallum_get_buffer_contents", throwable);
		}
	}

	ByteBuffer nativeByteBufferView(final Pointer pointer, final long byteSize) {
		if (pointer == null || Pointer.nativeValue(pointer) == 0L) {
			throw new IllegalArgumentException("Cannot create a ByteBuffer view for a null native pointer");
		}
		if (byteSize < 0L) {
			throw new IllegalArgumentException("Byte size must be non-negative");
		}
		return MemorySegment.ofAddress(Pointer.nativeValue(pointer)).reinterpret(byteSize).asByteBuffer();
	}

	private static MemorySegment renderPassSegment(final Pointer renderPass) {
		return toSegment(renderPass);
	}

	private static MemorySegment toSegment(final Pointer pointer) {
		if (pointer == null) {
			return MemorySegment.NULL;
		}
		long address = Pointer.nativeValue(pointer);
		return address == 0L ? MemorySegment.NULL : MemorySegment.ofAddress(address);
	}

	private static Pointer toPointer(final MemorySegment segment) {
		long address = segment.address();
		return address == 0L ? null : new Pointer(address);
	}

	private static MemorySegment toCString(final Arena arena, final String value) {
		return value == null ? MemorySegment.NULL : arena.allocateFrom(value);
	}

	private static MemorySegment toByteSegment(final Arena arena, final ByteBuffer buffer, final long length) {
		if (length == 0L) {
			return MemorySegment.NULL;
		}
		if (length < 0L) {
			throw new IllegalArgumentException("Length must be non-negative");
		}
		if (buffer.remaining() < length) {
			throw new IllegalArgumentException("ByteBuffer does not contain " + length + " readable bytes");
		}

		ByteBuffer duplicate = buffer.duplicate();
		duplicate.limit(duplicate.position() + Math.toIntExact(length));
		ByteBuffer window = duplicate.slice();
		if (window.isDirect()) {
			return MemorySegment.ofBuffer(window);
		}

		MemorySegment copy = arena.allocate(length);
		copy.copyFrom(MemorySegment.ofBuffer(window));
		return copy;
	}

	private static MemorySegment toLongArray(final Arena arena, final long[] values) {
		if (values == null || values.length == 0) {
			return MemorySegment.NULL;
		}
		MemorySegment segment = arena.allocate(LONG, values.length);
		for (int i = 0; i < values.length; i++) {
			segment.setAtIndex(LONG, i, values[i]);
		}
		return segment;
	}

	private static MemorySegment toPointerArray(final Arena arena, final Pointer[] values) {
		if (values == null || values.length == 0) {
			return MemorySegment.NULL;
		}
		MemorySegment segment = arena.allocate(ValueLayout.ADDRESS, values.length);
		for (int i = 0; i < values.length; i++) {
			segment.setAtIndex(ValueLayout.ADDRESS, i, toSegment(values[i]));
		}
		return segment;
	}

	private static RuntimeException bridgeFailure(final String symbol, final Throwable throwable) {
		return new IllegalStateException("Native bridge call failed: " + symbol, throwable);
	}
}
