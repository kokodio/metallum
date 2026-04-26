package com.metallum.mixin;

import com.metallum.client.metal.MetalTerrainFaceCulling;
import com.metallum.mixin.accessor.SectionCompilerResultsAccessor;
import com.mojang.blaze3d.vertex.MeshData;
import java.util.EnumMap;
import java.util.Map;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.TranslucencyPointOfView;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(EnvType.CLIENT)
@Mixin(CompiledSectionMesh.class)
public abstract class CompiledSectionMeshMixin implements MetalTerrainFaceCulling.SectionMeshRanges {
	@Unique
	@Nullable
	private EnumMap<ChunkSectionLayer, MetalTerrainFaceCulling.FaceRanges> metallum$terrainFaceRangesByLayer;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void metallum$copyTerrainFaceRanges(
		final TranslucencyPointOfView translucencyPointOfView,
		final SectionCompiler.Results results,
		final CallbackInfo ci
	) {
		Map<ChunkSectionLayer, MeshData> renderedLayers = ((SectionCompilerResultsAccessor)(Object)results).metallum$getRenderedLayers();
		for (Map.Entry<ChunkSectionLayer, MeshData> entry : renderedLayers.entrySet()) {
			if (!(entry.getValue() instanceof MetalTerrainFaceCulling.MeshDataRanges rangesHolder)) {
				continue;
			}

			MetalTerrainFaceCulling.FaceRanges ranges = rangesHolder.metallum$getTerrainFaceRanges();
			if (ranges != null) {
				this.metallum$setTerrainFaceRanges(entry.getKey(), ranges);
			}
		}
	}

	@Override
	public MetalTerrainFaceCulling.FaceRanges metallum$getTerrainFaceRanges(final ChunkSectionLayer layer) {
		return this.metallum$terrainFaceRangesByLayer == null ? null : this.metallum$terrainFaceRangesByLayer.get(layer);
	}

	@Override
	public void metallum$setTerrainFaceRanges(final ChunkSectionLayer layer, final MetalTerrainFaceCulling.FaceRanges ranges) {
		if (this.metallum$terrainFaceRangesByLayer == null) {
			this.metallum$terrainFaceRangesByLayer = new EnumMap<>(ChunkSectionLayer.class);
		}
		this.metallum$terrainFaceRangesByLayer.put(layer, ranges);
	}
}
