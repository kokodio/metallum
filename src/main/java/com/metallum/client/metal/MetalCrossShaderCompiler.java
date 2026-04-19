package com.metallum.client.metal;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.vulkan.VulkanBindGroupLayout;
import com.mojang.blaze3d.vulkan.glsl.GlslCompiler;
import com.mojang.blaze3d.vulkan.glsl.IntermediaryShaderModule;
import com.mojang.blaze3d.vulkan.glsl.ShaderCompileException;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.spvc.Spvc;
import org.slf4j.Logger;

@Environment(EnvType.CLIENT)
final class MetalCrossShaderCompiler {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final boolean SHADER_DUMPS_ENABLED = Boolean.getBoolean("metallum.debug.shaders");
	private static final int MSL_VERSION_4_0 = 0x040000;
	private static final Pattern VERTEX_ENTRY_PATTERN = Pattern.compile("\\bvertex\\s+\\w+\\s+(\\w+)\\s*\\(");
	private static final Pattern FRAGMENT_ENTRY_PATTERN = Pattern.compile("\\bfragment\\s+\\w+\\s+(\\w+)\\s*\\(");
	private static final Method ADD_TO_BIND_GROUP_METHOD = lookupAddToBindGroupMethod();
	private static final Set<String> LOGGED_MISSING_SHADER_SOURCE = ConcurrentHashMap.newKeySet();
	private static final Set<String> LOGGED_COMPILE_FAILURE = ConcurrentHashMap.newKeySet();
	private static final Path SHADER_DUMP_ROOT = Path.of("build", "metallum-shaders");
	private static final Set<String> LOGGED_SHADER_DUMP_ROOT = ConcurrentHashMap.newKeySet();

	private MetalCrossShaderCompiler() {
	}

	static MetalCompiledRenderPipeline compile(final RenderPipeline pipeline, final ShaderSource shaderSource) {
		String pipelineId = pipeline.getLocation().toString();
		Path dumpDir = SHADER_DUMPS_ENABLED ? shaderDumpDir(pipelineId) : null;
		try (GlslCompiler glslCompiler = new GlslCompiler()) {
			String vertexSource = shaderSource.get(pipeline.getVertexShader(), ShaderType.VERTEX);
			String fragmentSource = shaderSource.get(pipeline.getFragmentShader(), ShaderType.FRAGMENT);
			if (vertexSource == null || fragmentSource == null) {
				dumpMissingSource(pipeline, dumpDir, vertexSource, fragmentSource);
				if (LOGGED_MISSING_SHADER_SOURCE.add(pipelineId)) {
					LOGGER.error(
						"Couldn't find source for pipeline {} (vertex shader {} missing: {}, fragment shader {} missing: {})",
						pipeline.getLocation(),
						pipeline.getVertexShader(),
						vertexSource == null,
						pipeline.getFragmentShader(),
						fragmentSource == null
					);
				}
				return MetalCompiledRenderPipeline.invalid(pipeline);
			}

			String vertexGlsl = GlslPreprocessor.injectDefines(vertexSource, pipeline.getShaderDefines());
			String fragmentGlsl = GlslPreprocessor.injectDefines(fragmentSource, pipeline.getShaderDefines());
			dumpText(resolveDumpPath(dumpDir, "00-vertex-source.glsl"), vertexSource);
			dumpText(resolveDumpPath(dumpDir, "01-fragment-source.glsl"), fragmentSource);
			dumpText(resolveDumpPath(dumpDir, "10-vertex-preprocessed.glsl"), vertexGlsl);
			dumpText(resolveDumpPath(dumpDir, "11-fragment-preprocessed.glsl"), fragmentGlsl);

			try (
				IntermediaryShaderModule vertexSpirv = glslCompiler.createIntermediary(pipeline.getLocation() + ".vert", vertexGlsl, ShaderType.VERTEX);
				IntermediaryShaderModule fragmentSpirv = glslCompiler.createIntermediary(pipeline.getLocation() + ".frag", fragmentGlsl, ShaderType.FRAGMENT)
			) {
				List<VulkanBindGroupLayout.Entry> layoutEntries = new ArrayList<>();
				dumpBinary(resolveDumpPath(dumpDir, "20-vertex-initial.spv"), vertexSpirv.spirv());
				dumpBinary(resolveDumpPath(dumpDir, "21-fragment-initial.spv"), fragmentSpirv.spirv());
				addToBindGroup(layoutEntries, vertexSpirv, pipeline);
				addToBindGroup(layoutEntries, fragmentSpirv, pipeline);
				List<String> vertexOutputs = extractVariableNames(vertexSpirv.outputs());
				vertexSpirv.rebind(pipeline.getVertexFormat().getElementAttributeNames(), layoutEntries);
				fragmentSpirv.rebind(vertexOutputs, layoutEntries);
				dumpBinary(resolveDumpPath(dumpDir, "22-vertex-rebound.spv"), vertexSpirv.spirv());
				dumpBinary(resolveDumpPath(dumpDir, "23-fragment-rebound.spv"), fragmentSpirv.spirv());

				boolean flipVertexY = shouldFlipVertexY(pipeline);
				String vertexMsl = spirvToMsl(vertexSpirv.spirv(), flipVertexY);
				String fragmentMsl = spirvToMsl(fragmentSpirv.spirv());
				String vertexEntryPoint = extractEntryPoint(vertexMsl, VERTEX_ENTRY_PATTERN, "main0");
				String fragmentEntryPoint = extractEntryPoint(fragmentMsl, FRAGMENT_ENTRY_PATTERN, "main0");
				List<MetalCompiledRenderPipeline.ResourceBinding> resources = buildResourceBindings(layoutEntries, vertexMsl, fragmentMsl);
				dumpText(resolveDumpPath(dumpDir, "30-vertex.msl"), vertexMsl);
				dumpText(resolveDumpPath(dumpDir, "31-fragment.msl"), fragmentMsl);
				dumpMetadata(resolveDumpPath(dumpDir, "metadata.txt"), pipeline, layoutEntries, resources, vertexOutputs, vertexEntryPoint, fragmentEntryPoint);
				LOGGED_MISSING_SHADER_SOURCE.remove(pipelineId);
				LOGGED_COMPILE_FAILURE.remove(pipelineId);
				return new MetalCompiledRenderPipeline(
					pipeline,
					true,
					vertexMsl,
					fragmentMsl,
					vertexEntryPoint,
					fragmentEntryPoint,
					flipVertexY,
					resources
				);
			}
		} catch (Throwable throwable) {
			dumpFailure(pipeline, dumpDir, throwable);
			if (LOGGED_COMPILE_FAILURE.add(pipelineId)) {
				LOGGER.error("Failed to compile Metal cross shader for pipeline {}", pipeline.getLocation(), throwable);
			}
			return MetalCompiledRenderPipeline.invalid(pipeline);
		}
	}

	private static Method lookupAddToBindGroupMethod() {
		try {
			Method method = GlslCompiler.class.getDeclaredMethod("addToBindGroup", List.class, IntermediaryShaderModule.class, RenderPipeline.class);
			method.setAccessible(true);
			return method;
		} catch (ReflectiveOperationException | RuntimeException e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	private static void addToBindGroup(
		final List<VulkanBindGroupLayout.Entry> entries,
		final IntermediaryShaderModule shader,
		final RenderPipeline pipeline
	) throws ShaderCompileException {
		try {
			ADD_TO_BIND_GROUP_METHOD.invoke(null, entries, shader, pipeline);
		} catch (InvocationTargetException e) {
			Throwable cause = e.getCause();
			if (cause instanceof ShaderCompileException compileException) {
				throw compileException;
			}

			throw new ShaderCompileException("Failed to build bind group entries: " + cause);
		} catch (ReflectiveOperationException e) {
			throw new ShaderCompileException("Failed to access GlslCompiler.bindGroup builder: " + e.getMessage());
		}
	}

	private static List<String> extractVariableNames(final List<?> variables) throws ShaderCompileException {
		List<String> names = new ArrayList<>(variables.size());
		for (Object variable : variables) {
			names.add(readName(variable));
		}
		return names;
	}

	private static String readName(final Object object) throws ShaderCompileException {
		try {
			Method nameMethod = object.getClass().getDeclaredMethod("name");
			nameMethod.setAccessible(true);
			Object name = nameMethod.invoke(object);
			if (name instanceof String string) {
				return string;
			}
			throw new ShaderCompileException("Unexpected reflected variable name type: " + name);
		} catch (ReflectiveOperationException | RuntimeException e) {
			String fallback = tryParseNameFromRecordString(object);
			if (fallback != null) {
				return fallback;
			}
			throw new ShaderCompileException("Failed to read reflected variable name: " + e.getMessage());
		}
	}

	private static String tryParseNameFromRecordString(final Object object) {
		String text = String.valueOf(object);
		int nameStart = text.indexOf("name=");
		if (nameStart < 0) {
			return null;
		}
		int valueStart = nameStart + "name=".length();
		int valueEnd = text.indexOf(',', valueStart);
		if (valueEnd < 0) {
			valueEnd = text.indexOf(']', valueStart);
		}
		if (valueEnd <= valueStart) {
			return null;
		}
		String value = text.substring(valueStart, valueEnd).trim();
		return value.isEmpty() ? null : value;
	}

	private static String extractEntryPoint(final String msl, final Pattern pattern, final String fallback) {
		Matcher matcher = pattern.matcher(msl);
		return matcher.find() ? matcher.group(1) : fallback;
	}

	private static List<MetalCompiledRenderPipeline.ResourceBinding> buildResourceBindings(
		final List<VulkanBindGroupLayout.Entry> entries,
		final String vertexMsl,
		final String fragmentMsl
	) {
		List<MetalCompiledRenderPipeline.ResourceBinding> resources = new ArrayList<>(entries.size());
		for (int index = 0; index < entries.size(); index++) {
			VulkanBindGroupLayout.Entry entry = entries.get(index);
			MetalCompiledRenderPipeline.ResourceKind kind = switch (entry.type()) {
				case UNIFORM_BUFFER -> MetalCompiledRenderPipeline.ResourceKind.UNIFORM_BUFFER;
				case SAMPLED_IMAGE -> MetalCompiledRenderPipeline.ResourceKind.SAMPLED_IMAGE;
				case TEXEL_BUFFER -> MetalCompiledRenderPipeline.ResourceKind.TEXEL_BUFFER;
			};
			GpuFormat texelFormat = entry.type() == VulkanBindGroupLayout.VulkanBindGroupEntryType.TEXEL_BUFFER ? entry.texelBufferFormat() : null;
			resources.add(new MetalCompiledRenderPipeline.ResourceBinding(kind, entry.name(), index, stageMask(kind, index, vertexMsl, fragmentMsl), texelFormat));
		}
		return resources;
	}

	private static int stageMask(
		final MetalCompiledRenderPipeline.ResourceKind kind,
		final int bindingIndex,
		final String vertexMsl,
		final String fragmentMsl
	) {
		String resourceAttribute = switch (kind) {
			case UNIFORM_BUFFER -> "buffer";
			case TEXEL_BUFFER, SAMPLED_IMAGE -> "texture";
		};
		String marker = "[[" + resourceAttribute + "(" + bindingIndex + ")]]";
		int mask = 0;
		if (vertexMsl.contains(marker)) {
			mask |= MetalCompiledRenderPipeline.STAGE_VERTEX;
		}
		if (fragmentMsl.contains(marker)) {
			mask |= MetalCompiledRenderPipeline.STAGE_FRAGMENT;
		}
		return mask == 0 ? MetalCompiledRenderPipeline.STAGE_ALL : mask;
	}

	private static String spirvToMsl(final ByteBuffer spirvBytes) throws ShaderCompileException {
		return spirvToMsl(spirvBytes, false);
	}

	private static String spirvToMsl(final ByteBuffer spirvBytes, final boolean flipVertexY) throws ShaderCompileException {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			ByteBuffer copy = MemoryUtil.memAlloc(spirvBytes.remaining());
			copy.put(spirvBytes.duplicate());
			copy.flip();
			IntBuffer spirvWords = copy.asIntBuffer();

			PointerBuffer pContext = stack.mallocPointer(1);
			checkSpvc(Spvc.spvc_context_create(pContext), "spvc_context_create");
			long context = pContext.get(0);
			try {
				PointerBuffer pIr = stack.mallocPointer(1);
				checkSpvc(Spvc.spvc_context_parse_spirv(context, spirvWords, spirvWords.remaining(), pIr), "spvc_context_parse_spirv");

				PointerBuffer pCompiler = stack.mallocPointer(1);
				checkSpvc(
					Spvc.spvc_context_create_compiler(context, Spvc.SPVC_BACKEND_MSL, pIr.get(0), Spvc.SPVC_CAPTURE_MODE_COPY, pCompiler),
					"spvc_context_create_compiler"
				);
				long compiler = pCompiler.get(0);

				PointerBuffer pOptions = stack.mallocPointer(1);
				checkSpvc(Spvc.spvc_compiler_create_compiler_options(compiler, pOptions), "spvc_compiler_create_compiler_options");
				long options = pOptions.get(0);
				checkSpvc(
					Spvc.spvc_compiler_options_set_uint(options, Spvc.SPVC_COMPILER_OPTION_MSL_PLATFORM, Spvc.SPVC_MSL_PLATFORM_MACOS),
					"spvc_compiler_options_set_uint(MSL_PLATFORM)"
				);
				checkSpvc(
					Spvc.spvc_compiler_options_set_uint(options, Spvc.SPVC_COMPILER_OPTION_MSL_VERSION, MSL_VERSION_4_0),
					"spvc_compiler_options_set_uint(MSL_VERSION)"
				);
				checkSpvc(
					Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_MSL_ENABLE_DECORATION_BINDING, true),
					"spvc_compiler_options_set_bool(MSL_ENABLE_DECORATION_BINDING)"
				);
				if (flipVertexY) {
					checkSpvc(
						Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_FLIP_VERTEX_Y, true),
						"spvc_compiler_options_set_bool(FLIP_VERTEX_Y)"
					);
				}
				checkSpvc(Spvc.spvc_compiler_install_compiler_options(compiler, options), "spvc_compiler_install_compiler_options");

				PointerBuffer pSource = stack.mallocPointer(1);
				checkSpvc(Spvc.spvc_compiler_compile(compiler, pSource), "spvc_compiler_compile");
				return MemoryUtil.memUTF8(pSource.get(0));
			} finally {
				Spvc.spvc_context_destroy(context);
				MemoryUtil.memFree(copy);
			}
		}
	}

	private static boolean shouldFlipVertexY(final RenderPipeline pipeline) {
		return true;
	}

	private static void checkSpvc(final int result, final String stage) throws ShaderCompileException {
		if (result != Spvc.SPVC_SUCCESS) {
			throw new ShaderCompileException("SPIRV-Cross error at " + stage + ": " + result);
		}
	}

	@Nullable
	private static Path shaderDumpDir(final String pipelineId) {
		if (!SHADER_DUMPS_ENABLED) {
			return null;
		}
		Path dir = SHADER_DUMP_ROOT.resolve(sanitizePathSegment(pipelineId));
		try {
			Files.createDirectories(dir);
			if (LOGGED_SHADER_DUMP_ROOT.add("root")) {
				LOGGER.info("Metal shader dumps enabled at {}", SHADER_DUMP_ROOT.toAbsolutePath());
			}
		} catch (IOException e) {
			LOGGER.warn("Failed to create Metal shader dump directory {}", dir.toAbsolutePath(), e);
		}
		return dir;
	}

	private static String sanitizePathSegment(final String value) {
		String sanitized = value.replaceAll("[^a-zA-Z0-9._-]+", "_");
		return sanitized.isEmpty() ? "unnamed_pipeline" : sanitized;
	}

	@Nullable
	private static Path resolveDumpPath(@Nullable final Path dumpDir, final String fileName) {
		return dumpDir == null ? null : dumpDir.resolve(fileName);
	}

	private static void dumpMissingSource(
		final RenderPipeline pipeline,
		@Nullable final Path dumpDir,
		final String vertexSource,
		final String fragmentSource
	) {
		StringBuilder builder = new StringBuilder();
		builder.append("Pipeline: ").append(pipeline.getLocation()).append('\n');
		builder.append("Vertex shader id: ").append(pipeline.getVertexShader()).append('\n');
		builder.append("Fragment shader id: ").append(pipeline.getFragmentShader()).append('\n');
		builder.append("Vertex source missing: ").append(vertexSource == null).append('\n');
		builder.append("Fragment source missing: ").append(fragmentSource == null).append('\n');
		dumpText(resolveDumpPath(dumpDir, "failure.txt"), builder.toString());
	}

	private static void dumpFailure(final RenderPipeline pipeline, final Path dumpDir, final Throwable throwable) {
		if (dumpDir == null) {
			return;
		}
		StringWriter stringWriter = new StringWriter();
		try (PrintWriter printWriter = new PrintWriter(stringWriter)) {
			printWriter.println("Pipeline: " + pipeline.getLocation());
			printWriter.println("Vertex shader id: " + pipeline.getVertexShader());
			printWriter.println("Fragment shader id: " + pipeline.getFragmentShader());
			printWriter.println();
			throwable.printStackTrace(printWriter);
		}
		dumpText(dumpDir.resolve("failure.txt"), stringWriter.toString());
	}

	private static void dumpMetadata(
		@Nullable final Path path,
		final RenderPipeline pipeline,
		final List<VulkanBindGroupLayout.Entry> layoutEntries,
		final List<MetalCompiledRenderPipeline.ResourceBinding> resources,
		final List<String> vertexOutputs,
		final String vertexEntryPoint,
		final String fragmentEntryPoint
	) {
		if (path == null) {
			return;
		}
		StringBuilder builder = new StringBuilder();
		builder.append("Pipeline: ").append(pipeline.getLocation()).append('\n');
		builder.append("Vertex shader: ").append(pipeline.getVertexShader()).append('\n');
		builder.append("Fragment shader: ").append(pipeline.getFragmentShader()).append('\n');
		builder.append("Vertex entry point: ").append(vertexEntryPoint).append('\n');
		builder.append("Fragment entry point: ").append(fragmentEntryPoint).append('\n');
		builder.append("Vertex format: ").append(pipeline.getVertexFormat()).append('\n');
		builder.append("Vertex mode: ").append(pipeline.getVertexFormatMode()).append('\n');
		builder.append("Shader defines: ").append(pipeline.getShaderDefines()).append('\n');
		builder.append("Vertex outputs: ").append(vertexOutputs).append('\n');
		builder.append('\n');
		builder.append("Bind Group Layout Entries:\n");
		for (int index = 0; index < layoutEntries.size(); index++) {
			VulkanBindGroupLayout.Entry entry = layoutEntries.get(index);
			builder.append("  [").append(index).append("] ")
				.append(entry.type()).append(' ')
				.append(entry.name());
			if (entry.texelBufferFormat() != null) {
				builder.append(" texelFormat=").append(entry.texelBufferFormat());
			}
			builder.append('\n');
		}
		builder.append('\n');
		builder.append("Metal Resource Bindings:\n");
		for (MetalCompiledRenderPipeline.ResourceBinding resource : resources) {
			builder.append("  [").append(resource.bindingIndex()).append("] ")
				.append(resource.kind()).append(' ')
				.append(resource.name())
				.append(" stages=").append(resource.stageMask());
			if (resource.texelBufferFormat() != null) {
				builder.append(" texelFormat=").append(resource.texelBufferFormat());
			}
			builder.append('\n');
		}
		dumpText(path, builder.toString());
	}

	private static void dumpText(@Nullable final Path path, final String text) {
		if (path == null) {
			return;
		}
		try {
			Path parent = path.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			Files.writeString(path, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
		} catch (IOException e) {
			LOGGER.warn("Failed to write shader dump {}", path.toAbsolutePath(), e);
		}
	}

	private static void dumpBinary(@Nullable final Path path, final ByteBuffer source) {
		if (path == null) {
			return;
		}
		try {
			Path parent = path.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			ByteBuffer copy = source.duplicate();
			byte[] bytes = new byte[copy.remaining()];
			copy.get(bytes);
			Files.write(path, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
		} catch (IOException e) {
			LOGGER.warn("Failed to write shader dump {}", path.toAbsolutePath(), e);
		}
	}
}
