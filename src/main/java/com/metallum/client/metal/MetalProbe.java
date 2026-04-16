package com.metallum.client.metal;

import static ca.weblite.objc.RuntimeUtils.str;

import ca.weblite.objc.Client;
import com.sun.jna.Pointer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

@Environment(EnvType.CLIENT)
public final class MetalProbe {
	private static final String SANITY_SHADER = """
		#include <metal_stdlib>
		using namespace metal;

		vertex float4 metallum_probe_vertex(uint vertexId [[vertex_id]]) {
			const float x = vertexId == 0 ? -1.0 : 1.0;
			return float4(x, 0.0, 0.0, 1.0);
		}

		fragment half4 metallum_probe_fragment() {
			return half4(1.0);
		}
		""";
	private static final Client COERCING_CLIENT = Client.getInstance();
	private static final Client RAW_CLIENT = Client.getRawClient();
	private MetalProbe() {
	}

	public static ProbeResult probe() {
		if (!MetalBackendConfig.isMacOs()) {
			return ProbeResult.unsupported("Metal probe is only available on macOS");
		}

		try {
			Pointer device = createSystemDefaultDevice();
			if (isNullPointer(device)) {
				return ProbeResult.unsupported("MTLCreateSystemDefaultDevice returned null");
			}

			String deviceName = readDeviceName(device);
			if (!compileSanityShader(device)) {
				return ProbeResult.unsupported("Metal device '" + deviceName + "' failed runtime MSL sanity compilation");
			}

			return ProbeResult.supported(deviceName);
		} catch (Throwable throwable) {
			return ProbeResult.failed(throwable);
		}
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

	private static boolean compileSanityShader(final Pointer device) {
		Pointer source = str(SANITY_SHADER);
		if (isNullPointer(source)) {
			return false;
		}

		Pointer library = RAW_CLIENT.sendPointer(device, "newLibraryWithSource:options:error:", source, Pointer.NULL, Pointer.NULL);
		return !isNullPointer(library);
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
