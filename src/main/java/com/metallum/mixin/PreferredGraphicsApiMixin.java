package com.metallum.mixin;

import com.metallum.client.metal.MetalBackend;
import com.metallum.client.metal.MetalBackendConfig;
import com.mojang.blaze3d.opengl.GlBackend;
import com.mojang.blaze3d.systems.GpuBackend;
import com.mojang.blaze3d.vulkan.VulkanBackend;
import net.minecraft.network.chat.Component;
import net.minecraft.client.PreferredGraphicsApi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PreferredGraphicsApi.class)
abstract class PreferredGraphicsApiMixin {
	@Inject(method = "getBackendsToTry", at = @At("HEAD"), cancellable = true)
	private void metallum$injectMetalBackend(final CallbackInfoReturnable<GpuBackend[]> cir) {
		PreferredGraphicsApi self = (PreferredGraphicsApi)(Object)this;
		if (self != PreferredGraphicsApi.DEFAULT || !MetalBackendConfig.isMacOs()) {
			return;
		}

		cir.setReturnValue(new GpuBackend[]{new MetalBackend(), new VulkanBackend(), new GlBackend()});
	}

	@Inject(method = "caption", at = @At("HEAD"), cancellable = true)
	private void metallum$renameDefaultApiToMetal(final CallbackInfoReturnable<Component> cir) {
		PreferredGraphicsApi self = (PreferredGraphicsApi)(Object)this;
		if (self == PreferredGraphicsApi.DEFAULT && MetalBackendConfig.isMacOs()) {
			cir.setReturnValue(Component.literal("Prefer Metal"));
		}
	}
}
