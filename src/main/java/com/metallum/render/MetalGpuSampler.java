package com.metallum.render;

import io.github.kokodio.metaljvm.metal.*;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.NonNull;

import java.util.OptionalDouble;

@Environment(EnvType.CLIENT)
final class MetalGpuSampler extends GpuSampler {
    private final MetalDevice device;
    private final MTLSamplerState metalSampler;
    private final AddressMode addressModeU;
    private final AddressMode addressModeV;
    private final FilterMode minFilter;
    private final FilterMode magFilter;
    private final int maxAnisotropy;
    private final OptionalDouble maxLod;
    private boolean closed;

    MetalGpuSampler(
            final MetalDevice device,
            final AddressMode addressModeU,
            final AddressMode addressModeV,
            final FilterMode minFilter,
            final FilterMode magFilter,
            final int maxAnisotropy,
            final OptionalDouble maxLod
    ) {
        this.device = device;
        MTLSamplerDescriptor descriptor = MTLSamplerDescriptor.alloc().init();
        descriptor.setMinFilter(MetalConversions.minMagFilter(minFilter));
        descriptor.setMagFilter(MetalConversions.minMagFilter(magFilter));
        descriptor.setMipFilter(toMtlMipFilter(maxLod));
        descriptor.setSAddressMode(MetalConversions.addressMode(addressModeU));
        descriptor.setTAddressMode(MetalConversions.addressMode(addressModeV));
        descriptor.setMaxAnisotropy(Math.max(1, maxAnisotropy));
        descriptor.setLodMinClamp(0.0f);
        double lodMaxClamp = toMtlMaxLodClamp(maxLod);
        descriptor.setLodMaxClamp(lodMaxClamp >= 0.0 && Double.isFinite(lodMaxClamp) ? (float) lodMaxClamp : Float.MAX_VALUE);
        this.metalSampler = MetalUtilities.nonNil(device.metalDevice().newSamplerState(descriptor), "newSamplerStateWithDescriptor:");
        descriptor.release();
        this.addressModeU = addressModeU;
        this.addressModeV = addressModeV;
        this.minFilter = minFilter;
        this.magFilter = magFilter;
        this.maxAnisotropy = maxAnisotropy;
        this.maxLod = maxLod;
    }

    @Override
    public @NonNull AddressMode getAddressModeU() {
        return this.addressModeU;
    }

    @Override
    public @NonNull AddressMode getAddressModeV() {
        return this.addressModeV;
    }

    @Override
    public @NonNull FilterMode getMinFilter() {
        return this.minFilter;
    }

    @Override
    public @NonNull FilterMode getMagFilter() {
        return this.magFilter;
    }

    @Override
    public int getMaxAnisotropy() {
        return this.maxAnisotropy;
    }

    @Override
    public @NonNull OptionalDouble getMaxLod() {
        return this.maxLod;
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.device.queueResourceRelease(this.metalSampler);
    }

    boolean isClosed() {
        return this.closed;
    }

    MTLSamplerState metalSampler() {
        return this.metalSampler;
    }

    private static MTLSamplerMipFilter toMtlMipFilter(final OptionalDouble maxLod) {
        return maxLod.orElse(1000.0) > 0.25 ? MTLSamplerMipFilter.Linear : MTLSamplerMipFilter.Nearest;
    }

    private static double toMtlMaxLodClamp(final OptionalDouble maxLod) {
        return Math.max(0.25, maxLod.orElse(1000.0));
    }
}
