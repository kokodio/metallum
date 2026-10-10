package com.metallum.render;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout.UniformDescription;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.SpvModule;
import com.mojang.renderpearl.util.ShaderCompileException;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.spvc.Spv;
import org.lwjgl.util.spvc.Spvc;
import org.lwjgl.util.spvc.SpvcMslResourceBinding;
import org.lwjgl.util.spvc.SpvcMslShaderInterfaceVar2;
import org.lwjgl.util.spvc.SpvcReflectedResource;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Environment(EnvType.CLIENT)
final class MetalCrossShaderCompiler {
    private static final int MSL_VERSION_4_0 = 0x040000;
    private static final int[] RESOURCE_TYPES = {
            Spvc.SPVC_RESOURCE_TYPE_UNIFORM_BUFFER,
            Spvc.SPVC_RESOURCE_TYPE_SAMPLED_IMAGE,
            Spvc.SPVC_RESOURCE_TYPE_SEPARATE_IMAGE,
            Spvc.SPVC_RESOURCE_TYPE_SEPARATE_SAMPLERS
    };
    private static final Pattern VERTEX_ENTRY_PATTERN = Pattern.compile("\\bvertex\\s+\\w+\\s+(\\w+)\\s*\\(");
    private static final Pattern FRAGMENT_ENTRY_PATTERN = Pattern.compile("\\bfragment\\s+\\w+\\s+(\\w+)\\s*\\(");

    private MetalCrossShaderCompiler() {
    }

    record Compiled(
            String vertexSource,
            String fragmentSource,
            String vertexEntryPoint,
            String fragmentEntryPoint,
            List<MetalCompiledRenderPipeline.ResourceBinding> resources,
            MetalCompiledRenderPipeline.@Nullable PushConstants pushConstants
    ) {
    }

    static Compiled compile(final BackendRenderPipeline.CreateInfo info) throws ShaderCompileException {
        SpvModule vertexSpirv = null;
        SpvModule fragmentSpirv = null;
        for (BackendRenderPipeline.CreateInfo.Shader shader : info.shaders()) {
            if (shader.module().type() == ShaderType.VERTEX) {
                vertexSpirv = shader.module();
            } else if (shader.module().type() == ShaderType.FRAGMENT) {
                fragmentSpirv = shader.module();
            }
        }
        if (vertexSpirv == null || fragmentSpirv == null) {
            throw new ShaderCompileException("Pipeline " + info.name() + " requires both a vertex and a fragment shader");
        }

        List<UniformDescription> uniforms = info.uniforms();
        MetalIndices metalIndices = MetalIndices.of(uniforms);

        MslShader vertexMsl = spirvToMsl(vertexSpirv.spv(), metalIndices, vertexAttributeFormats(info), true);
        boolean enableFragDepth = info.depthStencilState() != null;
        MslShader fragmentMsl = spirvToMsl(fragmentSpirv.spv(), metalIndices, Map.of(), enableFragDepth);

        String vertexEntryPoint = extractEntryPoint(vertexMsl.source(), VERTEX_ENTRY_PATTERN, "main0");
        String fragmentEntryPoint = extractEntryPoint(fragmentMsl.source(), FRAGMENT_ENTRY_PATTERN, "main0");
        List<MetalCompiledRenderPipeline.ResourceBinding> resources = buildResourceBindings(uniforms, metalIndices, vertexMsl, fragmentMsl);
        MetalCompiledRenderPipeline.PushConstants pushConstants = buildPushConstants(metalIndices, vertexMsl, fragmentMsl);
        return new Compiled(vertexMsl.source(), fragmentMsl.source(), vertexEntryPoint, fragmentEntryPoint, resources, pushConstants);
    }

    private static String extractEntryPoint(final String msl, final Pattern pattern, final String fallback) {
        Matcher matcher = pattern.matcher(msl);
        return matcher.find() ? matcher.group(1) : fallback;
    }

    private static List<MetalCompiledRenderPipeline.ResourceBinding> buildResourceBindings(
            final List<UniformDescription> uniforms,
            final MetalIndices metalIndices,
            final MslShader vertexMsl,
            final MslShader fragmentMsl
    ) {
        List<MetalCompiledRenderPipeline.ResourceBinding> resources = new ArrayList<>(uniforms.size());
        for (int index = 0; index < uniforms.size(); index++) {
            UniformDescription uniform = uniforms.get(index);
            MetalCompiledRenderPipeline.ResourceKind kind = switch (uniform.type()) {
                case UNIFORM_BUFFER -> MetalCompiledRenderPipeline.ResourceKind.UNIFORM_BUFFER;
                case COMBINED_IMAGE_SAMPLER -> MetalCompiledRenderPipeline.ResourceKind.SAMPLED_IMAGE;
                case TEXEL_BUFFER -> MetalCompiledRenderPipeline.ResourceKind.TEXEL_BUFFER;
            };
            GpuFormat texelFormat = uniform.type() == UniformType.TEXEL_BUFFER ? uniform.gpuFormat() : null;
            resources.add(new MetalCompiledRenderPipeline.ResourceBinding(kind, uniform.name(), index, metalIndices.byBinding()[index], stageMask(uniform.name(), vertexMsl, fragmentMsl), texelFormat));
        }
        return resources;
    }

    private static MetalCompiledRenderPipeline.@Nullable PushConstants buildPushConstants(
            final MetalIndices metalIndices,
            final MslShader vertexMsl,
            final MslShader fragmentMsl
    ) {
        int stageMask = (vertexMsl.hasPushConstants() ? MetalCompiledRenderPipeline.STAGE_VERTEX : 0)
                | (fragmentMsl.hasPushConstants() ? MetalCompiledRenderPipeline.STAGE_FRAGMENT : 0);
        return stageMask == 0 ? null : new MetalCompiledRenderPipeline.PushConstants(metalIndices.pushConstants(), stageMask);
    }

    private static int stageMask(
            final String name,
            final MslShader vertexMsl,
            final MslShader fragmentMsl
    ) {
        int mask = 0;
        if (vertexMsl.activeResources().contains(name)) {
            mask |= MetalCompiledRenderPipeline.STAGE_VERTEX;
        }
        if (fragmentMsl.activeResources().contains(name)) {
            mask |= MetalCompiledRenderPipeline.STAGE_FRAGMENT;
        }
        if (mask == 0) {
            mask = MetalCompiledRenderPipeline.STAGE_ALL;
        }

        return mask;
    }

    private static Map<Integer, GpuFormat> vertexAttributeFormats(final BackendRenderPipeline.CreateInfo info) {
        Map<Integer, GpuFormat> formats = new HashMap<>();
        for (BackendRenderPipeline.CreateInfo.AttribBinding attribute : info.attribBindings()) {
            formats.putIfAbsent(attribute.location(), attribute.format());
        }
        return formats;
    }

    private static void registerIntegerInputConversions(
            final MemoryStack stack,
            final long compiler,
            final Map<Integer, GpuFormat> attributeFormats
    ) throws ShaderCompileException {
        if (attributeFormats.isEmpty()) {
            return;
        }

        PointerBuffer pResources = stack.mallocPointer(1);
        checkSpvc(Spvc.spvc_compiler_create_shader_resources(compiler, pResources), "spvc_compiler_create_shader_resources");

        PointerBuffer pList = stack.mallocPointer(1);
        PointerBuffer pCount = stack.mallocPointer(1);
        checkSpvc(Spvc.spvc_resources_get_resource_list_for_type(pResources.get(0), Spvc.SPVC_RESOURCE_TYPE_STAGE_INPUT, pList, pCount), "spvc_resources_get_resource_list_for_type(STAGE_INPUT)");
        int count = (int) pCount.get(0);
        if (count == 0) {
            return;
        }

        SpvcReflectedResource.Buffer list = SpvcReflectedResource.create(pList.get(0), count);
        for (int i = 0; i < count; i++) {
            SpvcReflectedResource input = list.get(i);
            int location = Spvc.spvc_compiler_get_decoration(compiler, input.id(), Spv.SpvDecorationLocation);
            GpuFormat format = attributeFormats.get(location);
            if (format == null || !format.name().endsWith("_UINT")) {
                continue;
            }
            int width = format.name().contains("8") ? Spvc.SPVC_MSL_SHADER_VARIABLE_FORMAT_UINT8
                    : format.name().contains("16") ? Spvc.SPVC_MSL_SHADER_VARIABLE_FORMAT_UINT16
                    : Spvc.SPVC_MSL_SHADER_VARIABLE_FORMAT_OTHER;
            if (width == Spvc.SPVC_MSL_SHADER_VARIABLE_FORMAT_OTHER) {
                continue;
            }

            long typeHandle = Spvc.spvc_compiler_get_type_handle(compiler, input.type_id());
            int baseType = Spvc.spvc_type_get_basetype(typeHandle);
            if (baseType != Spvc.SPVC_BASETYPE_INT8 && baseType != Spvc.SPVC_BASETYPE_INT16
                    && baseType != Spvc.SPVC_BASETYPE_INT32 && baseType != Spvc.SPVC_BASETYPE_INT64) {
                continue;
            }

            SpvcMslShaderInterfaceVar2 var = SpvcMslShaderInterfaceVar2.malloc(stack);
            Spvc.spvc_msl_shader_interface_var_init_2(var);
            var.location(location);
            var.vecsize(Spvc.spvc_type_get_vector_size(typeHandle));
            var.format(width);
            var.rate(Spvc.SPVC_MSL_SHADER_VARIABLE_RATE_PER_VERTEX);
            checkSpvc(Spvc.spvc_compiler_msl_add_shader_input_2(compiler, var), "spvc_compiler_msl_add_shader_input_2");
        }
    }

    private static MslShader spirvToMsl(
            final ByteBuffer spirvBytes,
            final MetalIndices metalIndices,
            final Map<Integer, GpuFormat> attributeFormats,
            final boolean enableFragDepth
    ) throws ShaderCompileException {
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
                        Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_MSL_TEXTURE_BUFFER_NATIVE, true),
                        "spvc_compiler_options_set_bool(MSL_TEXTURE_BUFFER_NATIVE)"
                );
                checkSpvc(
                        Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_FLIP_VERTEX_Y, true),
                        "spvc_compiler_options_set_bool(FLIP_VERTEX_Y)"
                );
                if (!enableFragDepth) {
                    checkSpvc(
                            Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_MSL_ENABLE_FRAG_DEPTH_BUILTIN, false),
                            "spvc_compiler_options_set_bool(MSL_ENABLE_FRAG_DEPTH_BUILTIN)"
                    );
                }
                checkSpvc(Spvc.spvc_compiler_install_compiler_options(compiler, options), "spvc_compiler_install_compiler_options");

                registerIntegerInputConversions(stack, compiler, attributeFormats);
                remapResourceBindings(stack, compiler, metalIndices.byBinding());

                PointerBuffer pActiveSet = stack.mallocPointer(1);
                checkSpvc(Spvc.spvc_compiler_get_active_interface_variables(compiler, pActiveSet), "spvc_compiler_get_active_interface_variables");
                long activeSet = pActiveSet.get(0);
                checkSpvc(Spvc.spvc_compiler_set_enabled_interface_variables(compiler, activeSet), "spvc_compiler_set_enabled_interface_variables");

                Set<String> activeResources = collectActiveResourceNames(stack, compiler, activeSet);

                PointerBuffer pResources = stack.mallocPointer(1);
                checkSpvc(Spvc.spvc_compiler_create_shader_resources(compiler, pResources), "spvc_compiler_create_shader_resources");
                long resources = pResources.get(0);

                PointerBuffer pList = stack.mallocPointer(1);
                PointerBuffer pCount = stack.mallocPointer(1);
                checkSpvc(Spvc.spvc_resources_get_resource_list_for_type(resources, Spvc.SPVC_RESOURCE_TYPE_PUSH_CONSTANT, pList, pCount), "spvc_resources_get_resource_list_for_type");
                boolean hasPushConstants = pCount.get(0) > 0;
                if (hasPushConstants) {
                    SpvcReflectedResource.Buffer list = SpvcReflectedResource.create(pList.get(0), 1);
                    Spvc.spvc_compiler_set_decoration(compiler, list.get(0).id(), Spv.SpvDecorationBinding, metalIndices.pushConstants());
                }

                PointerBuffer pSource = stack.mallocPointer(1);
                checkSpvc(Spvc.spvc_compiler_compile(compiler, pSource), "spvc_compiler_compile");
                return new MslShader(MemoryUtil.memUTF8(pSource.get(0)), hasPushConstants, activeResources);
            } finally {
                Spvc.spvc_context_destroy(context);
            }
        }
    }

    private static void remapResourceBindings(final MemoryStack stack, final long compiler, final int[] metalIndices) throws ShaderCompileException {
        PointerBuffer pResources = stack.mallocPointer(1);
        checkSpvc(Spvc.spvc_compiler_create_shader_resources(compiler, pResources), "spvc_compiler_create_shader_resources");
        int stage = Spvc.spvc_compiler_get_execution_model(compiler);

        PointerBuffer pList = stack.mallocPointer(1);
        PointerBuffer pCount = stack.mallocPointer(1);
        for (int type : RESOURCE_TYPES) {
            checkSpvc(Spvc.spvc_resources_get_resource_list_for_type(pResources.get(0), type, pList, pCount), "spvc_resources_get_resource_list_for_type");
            int count = (int) pCount.get(0);
            if (count == 0) {
                continue;
            }
            SpvcReflectedResource.Buffer list = SpvcReflectedResource.create(pList.get(0), count);
            for (int i = 0; i < count; i++) {
                int id = list.get(i).id();
                int binding = Spvc.spvc_compiler_get_decoration(compiler, id, Spv.SpvDecorationBinding);
                if (binding >= metalIndices.length) {
                    continue;
                }
                int metalIndex = metalIndices[binding];
                SpvcMslResourceBinding resourceBinding = SpvcMslResourceBinding.malloc(stack);
                Spvc.spvc_msl_resource_binding_init(resourceBinding);
                resourceBinding.stage(stage)
                        .desc_set(Spvc.spvc_compiler_get_decoration(compiler, id, Spv.SpvDecorationDescriptorSet))
                        .binding(binding)
                        .msl_buffer(metalIndex)
                        .msl_texture(metalIndex)
                        .msl_sampler(metalIndex);
                checkSpvc(Spvc.spvc_compiler_msl_add_resource_binding(compiler, resourceBinding), "spvc_compiler_msl_add_resource_binding");
            }
        }
    }

    private record MetalIndices(int[] byBinding, int pushConstants) {
        static MetalIndices of(final List<UniformDescription> uniforms) {
            int[] byBinding = new int[uniforms.size()];
            int buffers = 0;
            int textures = 0;
            for (int i = 0; i < byBinding.length; i++) {
                byBinding[i] = uniforms.get(i).type() == UniformType.UNIFORM_BUFFER ? buffers++ : textures++;
            }
            return new MetalIndices(byBinding, buffers);
        }
    }

    record MslShader(String source, boolean hasPushConstants, Set<String> activeResources) {
    }

    private static Set<String> collectActiveResourceNames(final MemoryStack stack, final long compiler, final long activeSet) throws ShaderCompileException {
        PointerBuffer pResources = stack.mallocPointer(1);
        checkSpvc(
                Spvc.spvc_compiler_create_shader_resources_for_active_variables(compiler, pResources, activeSet),
                "spvc_compiler_create_shader_resources_for_active_variables"
        );
        long resources = pResources.get(0);

        Set<String> names = new HashSet<>();
        collectResourceNames(stack, resources, Spvc.SPVC_RESOURCE_TYPE_UNIFORM_BUFFER, names);
        collectResourceNames(stack, resources, Spvc.SPVC_RESOURCE_TYPE_SAMPLED_IMAGE, names);
        collectResourceNames(stack, resources, Spvc.SPVC_RESOURCE_TYPE_SEPARATE_IMAGE, names);
        collectResourceNames(stack, resources, Spvc.SPVC_RESOURCE_TYPE_SEPARATE_SAMPLERS, names);
        return names;
    }

    private static void collectResourceNames(
            final MemoryStack stack,
            final long resources,
            final int resourceType,
            final Set<String> out
    ) throws ShaderCompileException {
        PointerBuffer pList = stack.mallocPointer(1);
        PointerBuffer pCount = stack.mallocPointer(1);
        checkSpvc(Spvc.spvc_resources_get_resource_list_for_type(resources, resourceType, pList, pCount), "spvc_resources_get_resource_list_for_type");
        int count = (int) pCount.get(0);
        if (count == 0) {
            return;
        }
        SpvcReflectedResource.Buffer list = SpvcReflectedResource.create(pList.get(0), count);
        for (int i = 0; i < count; i++) {
            out.add(list.get(i).nameString());
        }
    }

    private static void checkSpvc(final int result, final String stage) throws ShaderCompileException {
        if (result != Spvc.SPVC_SUCCESS) {
            throw new ShaderCompileException("SPIRV-Cross error at " + stage + ": " + result);
        }
    }
}
