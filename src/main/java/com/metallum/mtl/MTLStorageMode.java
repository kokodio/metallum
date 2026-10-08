package com.metallum.mtl;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public enum MTLStorageMode {
    Shared(0L),
    Managed(1L),
    Private(2L),
    Memoryless(3L);

    private static final MTLStorageMode[] VALUES = values();

    public final long value;

    MTLStorageMode(final long value) {
        this.value = value;
    }

    public static MTLStorageMode of(final long value) {
        return VALUES[(int) value];
    }
}
