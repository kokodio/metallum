package com.metallum.mixin;

import com.metallum.client.metal.MetalTerrainFaceCulling;
import com.mojang.blaze3d.vertex.MeshData;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Environment(EnvType.CLIENT)
@Mixin(MeshData.class)
public abstract class MeshDataMixin implements MetalTerrainFaceCulling.MeshDataSegments {
	@Unique
	private MetalTerrainFaceCulling.FaceSegments metallum$terrainFaceSegments;

	@Override
	public MetalTerrainFaceCulling.FaceSegments metallum$getTerrainFaceSegments() {
		return this.metallum$terrainFaceSegments;
	}

	@Override
	public void metallum$setTerrainFaceSegments(final MetalTerrainFaceCulling.FaceSegments segments) {
		this.metallum$terrainFaceSegments = segments;
	}
}
