package com.metallum.mixin;

import com.metallum.client.metal.MetalTerrainVertexPacking;
import com.metallum.client.metal.MetalTerrainFaceCulling;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
	@Inject(method = "prepareChunkRenders", at = @At("HEAD"))
	private void metallum$beginTerrainFaceCullingPrepare(final Matrix4fc viewRotationMatrix, final CallbackInfoReturnable<ChunkSectionsToRender> cir) {
		MetalTerrainFaceCulling.beginPrepare();
	}

	@Redirect(
		method = "prepareChunkRenders",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/VertexFormat;getVertexSize()I")
	)
	private int metallum$packedTerrainBaseVertexStride(final VertexFormat format) {
		return MetalTerrainVertexPacking.vertexSizeFor(format);
	}
}
