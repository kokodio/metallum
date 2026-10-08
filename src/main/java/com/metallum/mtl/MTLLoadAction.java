package com.metallum.mtl;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public enum MTLLoadAction {
    DontCare(0L),
    Load(1L),
    Clear(2L);

    public final long value;

    MTLLoadAction(final long value) {
        this.value = value;
    }
}
