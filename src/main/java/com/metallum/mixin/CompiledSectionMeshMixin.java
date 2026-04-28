package com.metallum.mixin;

import com.metallum.client.metal.MetalTerrainFaceCulling;
import com.metallum.mixin.accessor.SectionCompilerResultsAccessor;
import com.mojang.blaze3d.vertex.MeshData;
import java.util.Map;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.TranslucencyPointOfView;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(EnvType.CLIENT)
@Mixin(CompiledSectionMesh.class)
public abstract class CompiledSectionMeshMixin implements MetalTerrainFaceCulling.SectionMeshSegments {
	@Unique
	private MetalTerrainFaceCulling.FaceSegments metallum$terrainFaceSegments;
	@Unique
	@Nullable
	private BlockPos metallum$terrainSectionOrigin;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void metallum$copyTerrainFaceSegments(
		final TranslucencyPointOfView translucencyPointOfView,
		final SectionCompiler.Results results,
		final CallbackInfo ci
	) {
		Map<ChunkSectionLayer, MeshData> renderedLayers = ((SectionCompilerResultsAccessor)(Object)results).metallum$getRenderedLayers();
		MeshData solidMesh = renderedLayers.get(ChunkSectionLayer.SOLID);
		if (solidMesh instanceof MetalTerrainFaceCulling.MeshDataSegments segmentsHolder) {
			this.metallum$terrainFaceSegments = segmentsHolder.metallum$getTerrainFaceSegments();
		}
	}

	@Override
	public MetalTerrainFaceCulling.FaceSegments metallum$getTerrainFaceSegments() {
		return this.metallum$terrainFaceSegments;
	}

	@Override
	public void metallum$setTerrainFaceSegments(final MetalTerrainFaceCulling.FaceSegments segments) {
		this.metallum$terrainFaceSegments = segments;
	}

	@Override
	public BlockPos metallum$getTerrainSectionOrigin() {
		return this.metallum$terrainSectionOrigin;
	}

	@Override
	public void metallum$setTerrainSectionOrigin(final BlockPos origin) {
		this.metallum$terrainSectionOrigin = origin;
	}
}
