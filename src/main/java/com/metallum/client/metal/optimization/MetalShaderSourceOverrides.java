package com.metallum.client.metal.optimization;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.vulkan.glsl.ShaderCompileException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.resources.Identifier;

@Environment(EnvType.CLIENT)
public final class MetalShaderSourceOverrides {
	private static final Identifier TERRAIN_VERTEX_SHADER = Identifier.withDefaultNamespace("core/terrain");
	private static final Identifier PACKED_TERRAIN_VERTEX_SHADER = Identifier.fromNamespaceAndPath("metallum", "shaders/core/terrain_packed.vsh");
	private static volatile String packedTerrainVertexSource;

	private MetalShaderSourceOverrides() {
	}

	public static String vertexSource(final RenderPipeline pipeline, final String source) throws ShaderCompileException {
		if (!MetalTerrainVertexPacking.isPackedTerrainPipeline(pipeline.getLocation().toString()) || !pipeline.getVertexShader().equals(TERRAIN_VERTEX_SHADER)) {
			return source;
		}
		return packedTerrainVertexSource();
	}

	private static String packedTerrainVertexSource() throws ShaderCompileException {
		String source = packedTerrainVertexSource;
		if (source == null) {
			synchronized (MetalShaderSourceOverrides.class) {
				source = packedTerrainVertexSource;
				if (source == null) {
					source = preprocess(PACKED_TERRAIN_VERTEX_SHADER, readResource(PACKED_TERRAIN_VERTEX_SHADER));
					packedTerrainVertexSource = source;
				}
			}
		}
		return source;
	}

	private static String preprocess(final Identifier sourceId, final String source) throws ShaderCompileException {
		GlslPreprocessor preprocessor = new GlslPreprocessor() {
			@Override
			public String applyImport(final boolean relative, final String name) {
				if (relative) {
					throw new ShaderOverrideException("Relative shader imports are not supported in " + sourceId + ": " + name);
				}
				try {
					return readResource(Identifier.parse(name).withPrefix("shaders/include/"));
				} catch (ShaderCompileException e) {
					throw new ShaderOverrideException("Failed to import " + name + " for " + sourceId, e);
				}
			}
		};
		try {
			return String.join("", preprocessor.process(source));
		} catch (ShaderOverrideException e) {
			if (e.getCause() instanceof ShaderCompileException compileException) {
				throw compileException;
			}
			throw new ShaderCompileException(e.getMessage());
		}
	}

	private static String readResource(final Identifier id) throws ShaderCompileException {
		String path = "assets/" + id.getNamespace() + "/" + id.getPath();
		try (InputStream input = MetalShaderSourceOverrides.class.getClassLoader().getResourceAsStream(path)) {
			if (input == null) {
				throw new ShaderCompileException("Missing shader override resource " + path);
			}
			return new String(input.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new ShaderCompileException("Failed to read shader override resource " + path + ": " + e.getMessage());
		}
	}

	private static final class ShaderOverrideException extends RuntimeException {
		ShaderOverrideException(final String message) {
			super(message);
		}

		ShaderOverrideException(final String message, final Throwable cause) {
			super(message, cause);
		}
	}
}
