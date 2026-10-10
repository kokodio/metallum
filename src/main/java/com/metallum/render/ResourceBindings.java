package com.metallum.render;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.util.TextureViewAndSampler;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;

@Environment(EnvType.CLIENT)
final class ResourceBindings {
    static final int MAX_BINDINGS = Long.SIZE;

    private final GpuBufferSlice[] slices = new GpuBufferSlice[MAX_BINDINGS];
    private final TextureViewAndSampler[] textures = new TextureViewAndSampler[MAX_BINDINGS];
    private long dirtyMask;

    void set(final int index, @Nullable final Object value) {
        if (index < 0 || index >= MAX_BINDINGS) {
            throw new IllegalArgumentException("Unsupported Metal uniform index: " + index);
        }
        this.slices[index] = null;
        this.textures[index] = null;
        switch (value) {
            case null -> {
            }
            case GpuBufferSlice slice -> this.slices[index] = slice;
            case TextureViewAndSampler texture -> this.textures[index] = texture;
            default -> throw new IllegalArgumentException("Unsupported uniform value: " + value.getClass().getSimpleName());
        }
        this.dirtyMask |= 1L << index;
    }

    void reset() {
        Arrays.fill(this.slices, null);
        Arrays.fill(this.textures, null);
    }

    void markDirty(final long mask) {
        this.dirtyMask |= mask;
    }

    boolean hasDirty() {
        return this.dirtyMask != 0L;
    }

    boolean isDirty(final int index) {
        return (this.dirtyMask & (1L << index)) != 0L;
    }

    void clearDirty() {
        this.dirtyMask = 0L;
    }

    GpuBufferSlice slice(final String name, final int index) {
        GpuBufferSlice slice = this.slices[index];
        if (slice == null) {
            throw new IllegalStateException("Missing uniform " + name);
        }
        return slice;
    }

    TextureViewAndSampler texture(final String name, final int index) {
        TextureViewAndSampler texture = this.textures[index];
        if (texture == null) {
            throw new IllegalStateException("Missing sampler " + name);
        }
        return texture;
    }
}
