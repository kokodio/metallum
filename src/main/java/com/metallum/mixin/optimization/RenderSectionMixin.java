package com.metallum.mixin.optimization;

import com.metallum.client.metal.optimization.MetalTerrainFaceCulling;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Environment(EnvType.CLIENT)
@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class RenderSectionMixin {
	@Shadow
	public abstract BlockPos getRenderOrigin();

	@Inject(method = "getSectionMesh", at = @At("RETURN"))
	private void metallum$rememberTerrainFaceCullingOrigin(final CallbackInfoReturnable<SectionMesh> cir) {
		MetalTerrainFaceCulling.rememberSectionOrigin(cir.getReturnValue(), this.getRenderOrigin());
	}
}
