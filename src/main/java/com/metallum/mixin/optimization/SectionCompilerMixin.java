package com.metallum.mixin.optimization;

import com.metallum.client.metal.optimization.MetalTerrainVertexPacking;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SectionCompiler.class)
public abstract class SectionCompilerMixin {
	@Inject(method = "compile", at = @At("RETURN"))
	private void metallum$packTerrainVertices(
		final SectionPos sectionPos,
		final RenderSectionRegion region,
		final VertexSorting vertexSorting,
		final SectionBufferBuilderPack sectionBufferBuilderPack,
		final CallbackInfoReturnable<SectionCompiler.Results> cir
	) {
		MetalTerrainVertexPacking.optimize(cir.getReturnValue(), sectionBufferBuilderPack);
	}
}
