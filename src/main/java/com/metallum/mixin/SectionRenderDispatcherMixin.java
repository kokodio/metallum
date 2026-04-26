package com.metallum.mixin;

import com.metallum.client.metal.MetalTerrainVertexPacking;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(SectionRenderDispatcher.class)
public abstract class SectionRenderDispatcherMixin {
	@Redirect(
		method = "lambda$new$0",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/VertexFormat;getVertexSize()I")
	)
	private int metallum$packedTerrainVertexAlignment(final VertexFormat format) {
		return MetalTerrainVertexPacking.vertexSizeFor(format);
	}
}
