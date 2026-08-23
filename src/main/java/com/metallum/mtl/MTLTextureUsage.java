package com.metallum.mtl;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public enum MTLTextureUsage {
    Unknown(0L),
    ShaderRead(1L),
    ShaderWrite(2L),
    RenderTarget(4L),
    PixelFormatView(16L),
    ShaderAtomic(32L);

    public final long value;

    MTLTextureUsage(final long value) {
        this.value = value;
    }
}
