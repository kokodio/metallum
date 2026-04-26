package com.metallum.mixin;

import com.metallum.client.metal.MetalTerrainVertexPacking;
import com.metallum.mixin.accessor.SectionCompilerResultsAccessor;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexSorting;
import java.util.Map;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
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
		if (!MetalTerrainVertexPacking.isEnabled()) {
			return;
		}

		Map<ChunkSectionLayer, MeshData> renderedLayers = ((SectionCompilerResultsAccessor)(Object)cir.getReturnValue()).metallum$getRenderedLayers();
		for (Map.Entry<ChunkSectionLayer, MeshData> entry : renderedLayers.entrySet()) {
			MeshData original = entry.getValue();
			MeshData packed = MetalTerrainVertexPacking.pack(entry.getKey(), original);
			if (packed != original) {
				entry.setValue(packed);
				original.close();
			}
		}
	}
}
