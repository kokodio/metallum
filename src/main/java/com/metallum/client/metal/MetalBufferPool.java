package com.metallum.client.metal;

import com.sun.jna.Pointer;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

@Environment(EnvType.CLIENT)
final class MetalBufferPool {
	private final Map<Key, ArrayDeque<Pointer>> available = new HashMap<>();

	@Nullable
	Pointer acquire(final long size, final long resourceOptions) {
		Key key = new Key(normalizeSize(size), resourceOptions);
		ArrayDeque<Pointer> queue = this.available.get(key);
		if (queue == null) {
			return null;
		}
		Pointer handle = queue.pollFirst();
		if (queue.isEmpty()) {
			this.available.remove(key);
		}
		return handle;
	}

	void recycle(final Pointer handle, final long size, final long resourceOptions) {
		if (MetalProbe.isNullPointer(handle)) {
			return;
		}
		this.available.computeIfAbsent(new Key(normalizeSize(size), resourceOptions), ignored -> new ArrayDeque<>()).addFirst(handle);
	}

	long allocationSize(final long requestedSize) {
		return normalizeSize(requestedSize);
	}

	void close(final MetalNativeBridge nativeApi) {
		for (ArrayDeque<Pointer> queue : this.available.values()) {
			for (Pointer handle : queue) {
				if (!MetalProbe.isNullPointer(handle)) {
					nativeApi.metallum_release_object(handle);
				}
			}
		}
		this.available.clear();
	}

	private record Key(long size, long resourceOptions) {
	}

	private static long normalizeSize(final long size) {
		if (size <= 0L) {
			return 0L;
		}
		if (size <= 64L * 1024L) {
			return nextPowerOfTwo(Math.max(size, 4L * 1024L));
		}
		if (size <= 1024L * 1024L) {
			return roundUp(size, 64L * 1024L);
		}
		return roundUp(size, 1024L * 1024L);
	}

	private static long nextPowerOfTwo(final long value) {
		long result = 1L;
		while (result < value) {
			result <<= 1;
		}
		return result;
	}

	private static long roundUp(final long value, final long alignment) {
		return ((value + alignment - 1L) / alignment) * alignment;
	}
}
