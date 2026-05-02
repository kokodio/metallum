package com.metallum.client.metal.render;

import ca.weblite.objc.Client;
import com.sun.jna.Pointer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

@Environment(EnvType.CLIENT)
public final class MetalProbe {
	private static final Client COERCING_CLIENT = Client.getInstance();
	private MetalProbe() {
	}

	public static ProbeResult probe() {
		if (!MetalBackendConfig.isMacOs()) {
			return ProbeResult.unsupported("Metal probe is only available on macOS");
		}

		Pointer device = createSystemDefaultDevice();
		if (isNullPointer(device)) {
			return ProbeResult.unsupported("MTLCreateSystemDefaultDevice returned null");
		}

		String deviceName = readDeviceName(device);

		return ProbeResult.supported(deviceName);
	}

	static Pointer createSystemDefaultDevice() {
		return MetalNativeBridge.INSTANCE.metallum_create_system_default_device();
	}

	static String readDeviceName(final Pointer device) {
		Object value = COERCING_CLIENT.send(device, "name");
		if (value instanceof String string && !string.isBlank()) {
			return string;
		}

		return "<unknown Metal device>";
	}

	static boolean isNullPointer(@Nullable final Pointer pointer) {
		return pointer == null || Pointer.nativeValue(pointer) == 0L;
	}

	@Environment(EnvType.CLIENT)
	public record ProbeResult(boolean supported, String deviceName, String message, @Nullable Throwable failure) {
		public static ProbeResult supported(final String deviceName) {
			return new ProbeResult(true, deviceName, "Metal probe succeeded on " + deviceName, null);
		}

		public static ProbeResult unsupported(final String message) {
			return new ProbeResult(false, "<none>", message, null);
		}

		public static ProbeResult failed(final Throwable failure) {
			String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
			return new ProbeResult(false, "<none>", message, failure);
		}
	}
}
