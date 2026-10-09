package com.metallum.compat.sodium.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import net.caffeinemc.mods.sodium.client.gpu.device.backend.DrawBackend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.gpu.device.backend.DrawBackend", remap = false)
public class DrawBackendMixin {
    @Inject(method = "chooseBackend", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metallum$chooseMetalBackend(CallbackInfoReturnable<DrawBackend> cir) {
        if (RenderSystem.getDevice().getDeviceInfo().backendName().equals("Metal")) {
            cir.setReturnValue(DrawBackend.VK_INDIRECT);
        }
    }
}
