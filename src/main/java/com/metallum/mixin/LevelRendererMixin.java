package com.metallum.mixin;

import com.metallum.client.metal.MetalTerrainVertexPacking;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
	@Redirect(
		method = "prepareChunkRenders",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/VertexFormat;getVertexSize()I")
	)
	private int metallum$packedTerrainBaseVertexStride(final VertexFormat format) {
		return MetalTerrainVertexPacking.vertexSizeFor(format);
	}
}
