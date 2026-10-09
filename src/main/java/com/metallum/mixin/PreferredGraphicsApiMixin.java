package com.metallum.mixin;

import com.metallum.render.MetalBackend;
import com.mojang.blaze3d.systems.GpuBackend;
import net.minecraft.client.PreferredGraphicsApi;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PreferredGraphicsApi.class)
abstract class PreferredGraphicsApiMixin {
    @Inject(method = "getBackendsToTry", at = @At("HEAD"), cancellable = true)
    private void metallum$injectMetalBackend(final CallbackInfoReturnable<GpuBackend[]> cir) {
        cir.setReturnValue(new GpuBackend[]{new MetalBackend()});
    }

    @Inject(method = "caption", at = @At("HEAD"), cancellable = true)
    private void metallum$renameDefaultApiToMetal(final CallbackInfoReturnable<Component> cir) {
        cir.setReturnValue(Component.literal("Prefer Metal"));
    }
}
