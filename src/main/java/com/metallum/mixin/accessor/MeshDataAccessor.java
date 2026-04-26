package com.metallum.mixin.accessor;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MeshData.class)
public interface MeshDataAccessor {
	@Accessor("indexBuffer")
	ByteBufferBuilder.Result metallum$getIndexBuffer();

	@Accessor("indexBuffer")
	void metallum$setIndexBuffer(ByteBufferBuilder.Result indexBuffer);
}
