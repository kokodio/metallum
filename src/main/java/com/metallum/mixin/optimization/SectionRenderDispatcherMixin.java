package com.metallum.mixin.optimization;

import com.metallum.client.metal.optimization.MetalTerrainVertexPacking;
import com.metallum.client.metal.optimization.MetalTerrainFaceCulling;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SectionRenderDispatcher.class)
public abstract class SectionRenderDispatcherMixin {
	@Inject(method = "setCameraPosition", at = @At("HEAD"))
	private void metallum$updateTerrainFaceCullingCamera(final Vec3 cameraPosition, final CallbackInfo ci) {
		MetalTerrainFaceCulling.setCameraPosition(cameraPosition);
	}

	@Inject(method = "getRenderSectionSlice", at = @At("RETURN"))
	private void metallum$registerTerrainFaceCullingRanges(
		final SectionMesh sectionMesh,
		final ChunkSectionLayer layer,
		final CallbackInfoReturnable<SectionRenderDispatcher.RenderSectionBufferSlice> cir
	) {
		MetalTerrainFaceCulling.registerVisibleRanges(sectionMesh, layer, cir.getReturnValue());
	}

	@Redirect(
		method = "lambda$new$0",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/VertexFormat;getVertexSize()I")
	)
	private int metallum$packedTerrainVertexAlignment(final VertexFormat format) {
		return MetalTerrainVertexPacking.vertexSizeFor(format);
	}
}
