package com.metallum.mixin;

import com.metallum.client.metal.MetalTerrainFaceCulling;
import com.mojang.blaze3d.vertex.MeshData;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Environment(EnvType.CLIENT)
@Mixin(MeshData.class)
public abstract class MeshDataMixin implements MetalTerrainFaceCulling.MeshDataRanges {
	@Unique
	private MetalTerrainFaceCulling.FaceRanges metallum$terrainFaceRanges;

	@Override
	public MetalTerrainFaceCulling.FaceRanges metallum$getTerrainFaceRanges() {
		return this.metallum$terrainFaceRanges;
	}

	@Override
	public void metallum$setTerrainFaceRanges(final MetalTerrainFaceCulling.FaceRanges ranges) {
		this.metallum$terrainFaceRanges = ranges;
	}
}
