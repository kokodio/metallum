package com.metallum.render;

import com.mojang.renderpearl.api.commands.GpuFence;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
final class MetalFence implements GpuFence {
    private final MetalCommandEncoder encoder;
    private final long submitIndex;
    private boolean closed;

    MetalFence(final MetalCommandEncoder encoder, final long submitIndex) {
        this.encoder = encoder;
        this.submitIndex = submitIndex;
    }

    @Override
    public void close() {
        this.closed = true;
    }

    @Override
    public boolean awaitCompletion(final long timeoutNS) {
        return this.closed || this.encoder.awaitSubmitCompletion(this.submitIndex, timeoutNS < 0L ? Long.MAX_VALUE : timeoutNS / 1_000_000);
    }
}
