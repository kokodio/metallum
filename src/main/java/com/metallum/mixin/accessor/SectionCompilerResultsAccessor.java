package com.metallum.mixin.accessor;

import com.mojang.blaze3d.vertex.MeshData;
import java.util.Map;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SectionCompiler.Results.class)
public interface SectionCompilerResultsAccessor {
	@Accessor("renderedLayers")
	Map<ChunkSectionLayer, MeshData> metallum$getRenderedLayers();
}
