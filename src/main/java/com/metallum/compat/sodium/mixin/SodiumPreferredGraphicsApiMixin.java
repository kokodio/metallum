package com.metallum.compat.sodium.mixin;

import net.caffeinemc.mods.sodium.client.config.structure.EnumOption;
import net.minecraft.client.PreferredGraphicsApi;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.gui.options.control.CyclingControl$CyclingControlElement")
public class SodiumPreferredGraphicsApiMixin {
    @Redirect(
            method = "extractRenderState",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/caffeinemc/mods/sodium/client/config/structure/EnumOption;getElementName(Ljava/lang/Enum;)Lnet/minecraft/network/chat/Component;"
            ),
            remap = false
    )
    private <T extends Enum<T>> Component metallum$renameDefaultApiToMetal(final EnumOption<T> option, final T element) {
        if (element instanceof PreferredGraphicsApi) {
            return PreferredGraphicsApi.DEFAULT.caption();
        }

        return option.getElementName(element);
    }
}
