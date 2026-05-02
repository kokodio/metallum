package com.metallum.client.metal.render;

import com.mojang.blaze3d.systems.GpuQueryPool;
import java.util.OptionalLong;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
final class MetalGpuQueryPool implements GpuQueryPool {
	private final OptionalLong[] values;

	MetalGpuQueryPool(final int size) {
		this.values = new OptionalLong[size];

		for (int i = 0; i < size; i++) {
			this.values[i] = OptionalLong.empty();
		}
	}

	void setValue(final int index, final long value) {
		this.values[index] = OptionalLong.of(value);
	}

	@Override
	public int size() {
		return this.values.length;
	}

	@Override
	public OptionalLong getValue(final int index) {
		return this.values[index];
	}

	@Override
	public OptionalLong[] getValues(final int index, final int count) {
		OptionalLong[] result = new OptionalLong[count];
		System.arraycopy(this.values, index, result, 0, count);
		return result;
	}

	@Override
	public void close() {
		for (int i = 0; i < this.values.length; i++) {
			this.values[i] = OptionalLong.empty();
		}
	}
}
