package com.metallum.compat.sodium;

import com.metallum.Metallum;
import com.metallum.MetallumConfig;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.option.OptionFlag;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public final class MetallumSodiumConfig implements ConfigEntryPoint {
    @Override
    public void registerConfigLate(final ConfigBuilder builder) {
        builder.registerOwnModOptions().addPage(builder.createOptionPage()
                .setName(Component.literal("Display"))
                .addOption(builder.createBooleanOption(Identifier.fromNamespaceAndPath(Metallum.MOD_ID, "display_p3"))
                        .setName(Component.literal("Display P3"))
                        .setTooltip(Component.literal(
                                "On: saturated Display P3 colors, like before 26.2.\nOff: sRGB colors, 26.3 and later."
                        ))
                        .setDefaultValue(false)
                        .setFlags(OptionFlag.REQUIRES_GAME_RESTART)
                        .setBinding(value -> MetallumConfig.INSTANCE.displayP3 = value, () -> MetallumConfig.INSTANCE.displayP3)
                        .setStorageHandler(MetallumConfig.INSTANCE::save)));
    }
}
