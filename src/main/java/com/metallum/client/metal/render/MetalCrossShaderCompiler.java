package com.metallum.client.metal.render;

import com.metallum.client.metal.optimization.MetalShaderSourceOverrides;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BindGroupLayout.UniformDescription;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.vulkan.VulkanBindGroupLayout;
import com.mojang.blaze3d.vulkan.VulkanBindGroupLayout.VulkanBindGroupEntryType;
import com.mojang.blaze3d.vulkan.glsl.GlslCompiler;
import com.mojang.blaze3d.vulkan.glsl.IntermediaryShaderModule;
import com.mojang.blaze3d.vulkan.glsl.ShaderCompileException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.spvc.Spvc;

@Environment(EnvType.CLIENT)
final class MetalCrossShaderCompiler {
	private static final int MSL_VERSION_4_0 = 0x040000;
	private static final Pattern VERTEX_ENTRY_PATTERN = Pattern.compile("\\bvertex\\s+\\w+\\s+(\\w+)\\s*\\(");
	private static final Pattern FRAGMENT_ENTRY_PATTERN = Pattern.compile("\\bfragment\\s+\\w+\\s+(\\w+)\\s*\\(");

	private MetalCrossShaderCompiler() {
	}

	static MetalCompiledRenderPipeline compile(final RenderPipeline pipeline, final ShaderSource shaderSource) {
		try (GlslCompiler glslCompiler = new GlslCompiler()) {
			String vertexSource = shaderSource.get(pipeline.getVertexShader(), ShaderType.VERTEX);
			String fragmentSource = shaderSource.get(pipeline.getFragmentShader(), ShaderType.FRAGMENT);
			if (vertexSource == null || fragmentSource == null) {
				throw new IllegalStateException(
					"Couldn't find source for pipeline " + pipeline.getLocation()
						+ " (vertex shader " + pipeline.getVertexShader() + " missing: " + (vertexSource == null)
						+ ", fragment shader " + pipeline.getFragmentShader() + " missing: " + (fragmentSource == null) + ")"
				);
			}
			vertexSource = MetalShaderSourceOverrides.vertexSource(pipeline, vertexSource);

			String vertexGlsl = GlslPreprocessor.injectDefines(vertexSource, pipeline.getShaderDefines());
			String fragmentGlsl = GlslPreprocessor.injectDefines(fragmentSource, pipeline.getShaderDefines());

			try (
				IntermediaryShaderModule vertexSpirv = glslCompiler.createIntermediary(pipeline.getLocation() + ".vert", vertexGlsl, ShaderType.VERTEX);
				IntermediaryShaderModule fragmentSpirv = glslCompiler.createIntermediary(pipeline.getLocation() + ".frag", fragmentGlsl, ShaderType.FRAGMENT)
			) {
				List<VulkanBindGroupLayout.Entry> layoutEntries = new ArrayList<>();
				addToBindGroup(layoutEntries, vertexSpirv, pipeline);
				addToBindGroup(layoutEntries, fragmentSpirv, pipeline);
				List<String> vertexOutputs = extractVariableNames(vertexSpirv.outputs());
				vertexSpirv.rebind(MetalPipelineSupport.vertexAttributeNames(pipeline), layoutEntries);
				fragmentSpirv.rebind(vertexOutputs, layoutEntries);

				String vertexMsl = spirvToMsl(vertexSpirv.spirv());
				String fragmentMsl = spirvToMsl(fragmentSpirv.spirv());
				String vertexEntryPoint = extractEntryPoint(vertexMsl, VERTEX_ENTRY_PATTERN, "main0");
				String fragmentEntryPoint = extractEntryPoint(fragmentMsl, FRAGMENT_ENTRY_PATTERN, "main0");
				List<MetalCompiledRenderPipeline.ResourceBinding> resources = buildResourceBindings(layoutEntries, vertexMsl, fragmentMsl);
				return new MetalCompiledRenderPipeline(
					pipeline,
					vertexMsl,
					fragmentMsl,
					vertexEntryPoint,
					fragmentEntryPoint,
					resources
				);
			}
		} catch (ShaderCompileException e) {
			throw new IllegalStateException("Failed to compile Metal cross shader for pipeline " + pipeline.getLocation(), e);
		}
	}

	private static void addToBindGroup(
		final List<VulkanBindGroupLayout.Entry> entries,
		final IntermediaryShaderModule shader,
		final RenderPipeline pipeline
	) throws ShaderCompileException {
		List<UniformDescription> uniforms = BindGroupLayout.flattenUniforms(pipeline.getBindGroupLayouts());
		List<String> samplers = BindGroupLayout.flattenSamplers(pipeline.getBindGroupLayouts());
		for (Object buffer : shader.uniformBuffers()) {
			String name = readName(buffer);
			if (findUniform(uniforms, name) == null) {
				throw new ShaderCompileException("Unable to find shader defined uniform (" + name + ")");
			}
			addBindingIfAbsent(entries, VulkanBindGroupEntryType.UNIFORM_BUFFER, name, null);
		}

		for (Object sampler : shader.samplers()) {
			String name = readName(sampler);
			UniformDescription uniform = findUniform(uniforms, name);
			int dimensions = readInt(sampler, "dimensions");
			if (uniform != null) {
				if (dimensions != 5) {
					throw new ShaderCompileException("UTB (" + name + ") must have type of SpvDimBuffer");
				}
				addBindingIfAbsent(entries, VulkanBindGroupEntryType.TEXEL_BUFFER, name, uniform.gpuFormat());
			} else {
				if (!samplers.contains(name)) {
					throw new ShaderCompileException("Unable to find shader defined uniform (" + name + ")");
				}
				if (dimensions != 1 && dimensions != 3) {
					throw new ShaderCompileException("Sampled texture (" + name + ") must have type of SpvDim2D or SpvDimCube");
				}
				addBindingIfAbsent(entries, VulkanBindGroupEntryType.SAMPLED_IMAGE, name, null);
			}
		}
	}

	@Nullable
	private static UniformDescription findUniform(final List<UniformDescription> uniforms, final String name) {
		for (UniformDescription uniform : uniforms) {
			if (uniform.name().equals(name)) {
				return uniform;
			}
		}
		return null;
	}

	private static void addBindingIfAbsent(
		final List<VulkanBindGroupLayout.Entry> entries,
		final VulkanBindGroupEntryType type,
		final String name,
		@Nullable final GpuFormat texelBufferFormat
	) {
		for (VulkanBindGroupLayout.Entry entry : entries) {
			if (entry.type() == type && entry.name().equals(name)) {
				return;
			}
		}
		entries.add(new VulkanBindGroupLayout.Entry(type, name, texelBufferFormat));
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

	private static int readInt(final Object object, final String accessorName) throws ShaderCompileException {
		try {
			Method method = object.getClass().getDeclaredMethod(accessorName);
			method.setAccessible(true);
			Object value = method.invoke(object);
			if (value instanceof Integer integer) {
				return integer;
			}
			throw new ShaderCompileException("Unexpected reflected " + accessorName + " type: " + value);
		} catch (ReflectiveOperationException | RuntimeException e) {
			throw new ShaderCompileException("Failed to read reflected " + accessorName + ": " + e.getMessage());
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
		try (MemoryStack stack = MemoryStack.stackPush()) {
			IntBuffer spirvWords = spirvBytes.asIntBuffer();

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
				checkSpvc(
					Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_FLIP_VERTEX_Y, true),
					"spvc_compiler_options_set_bool(FLIP_VERTEX_Y)"
				);
				checkSpvc(Spvc.spvc_compiler_install_compiler_options(compiler, options), "spvc_compiler_install_compiler_options");

				PointerBuffer pSource = stack.mallocPointer(1);
				checkSpvc(Spvc.spvc_compiler_compile(compiler, pSource), "spvc_compiler_compile");
				return MemoryUtil.memUTF8(pSource.get(0));
			} finally {
				Spvc.spvc_context_destroy(context);
			}
		}
	}

	private static void checkSpvc(final int result, final String stage) throws ShaderCompileException {
		if (result != Spvc.SPVC_SUCCESS) {
			throw new ShaderCompileException("SPIRV-Cross error at " + stage + ": " + result);
		}
	}
}
