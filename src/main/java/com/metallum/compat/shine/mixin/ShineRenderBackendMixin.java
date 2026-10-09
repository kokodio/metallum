package com.metallum.compat.shine.mixin;

import com.mojang.blaze3d.systems.DeviceInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Pseudo
@Mixin(targets = "com.bloom.client.render.ShineRenderBackend", remap = false)
public class ShineRenderBackendMixin {
    @Redirect(
            method = "identify",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/DeviceInfo;backendName()Ljava/lang/String;"
            )
    )
    private static String metallum$treatMetalLikeVulkan(final DeviceInfo deviceInfo) {
        String backendName = deviceInfo.backendName();
        return "Metal".equalsIgnoreCase(backendName) ? "Vulkan" : backendName;
    }
}
