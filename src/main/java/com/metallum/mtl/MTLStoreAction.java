package com.metallum.mtl;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public enum MTLStoreAction {
    DontCare(0L),
    Store(1L),
    MultisampleResolve(2L),
    StoreAndMultisampleResolve(3L),
    Unknown(4L),
    CustomSampleDepthStore(5L);

    public final long value;

    MTLStoreAction(final long value) {
        this.value = value;
    }
}
