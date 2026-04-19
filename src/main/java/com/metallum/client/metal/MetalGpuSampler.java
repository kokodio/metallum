package com.metallum.client.metal;

import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.sun.jna.Pointer;
import java.util.OptionalDouble;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
final class MetalGpuSampler extends GpuSampler {
	private final MetalDevice device;
	private final Pointer nativeHandle;
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
		this.nativeHandle = MetalNativeBridge.INSTANCE.metallum_create_sampler(
			device.metalDevicePointer(),
			toMtlAddressMode(addressModeU),
			toMtlAddressMode(addressModeV),
			toMtlMinMagFilter(minFilter),
			toMtlMinMagFilter(magFilter),
			toMtlMipFilter(maxLod),
			Math.max(1, maxAnisotropy),
			toMtlMaxLodClamp(maxLod)
		);
		this.addressModeU = addressModeU;
		this.addressModeV = addressModeV;
		this.minFilter = minFilter;
		this.magFilter = magFilter;
		this.maxAnisotropy = maxAnisotropy;
		this.maxLod = maxLod;
	}

	@Override
	public AddressMode getAddressModeU() {
		return this.addressModeU;
	}

	@Override
	public AddressMode getAddressModeV() {
		return this.addressModeV;
	}

	@Override
	public FilterMode getMinFilter() {
		return this.minFilter;
	}

	@Override
	public FilterMode getMagFilter() {
		return this.magFilter;
	}

	@Override
	public int getMaxAnisotropy() {
		return this.maxAnisotropy;
	}

	@Override
	public OptionalDouble getMaxLod() {
		return this.maxLod;
	}

	@Override
	public void close() {
		if (this.closed) {
			return;
		}
		this.closed = true;
		this.device.queueResourceRelease(this.nativeHandle);
	}

	boolean isClosed() {
		return this.closed;
	}

	Pointer nativeHandle() {
		return this.nativeHandle;
	}

	private static long toMtlAddressMode(final AddressMode addressMode) {
		return switch (addressMode) {
			case REPEAT -> 2L;
			case CLAMP_TO_EDGE -> 1L;
		};
	}

	private static long toMtlMinMagFilter(final FilterMode filterMode) {
		return switch (filterMode) {
			case NEAREST -> 0L;
			case LINEAR -> 1L;
		};
	}

	private static long toMtlMipFilter(final OptionalDouble maxLod) {
		return maxLod.orElse(1000.0) > 0.25 ? 2L : 1L;
	}

	private static double toMtlMaxLodClamp(final OptionalDouble maxLod) {
		return Math.max(0.25, maxLod.orElse(1000.0));
	}
}
