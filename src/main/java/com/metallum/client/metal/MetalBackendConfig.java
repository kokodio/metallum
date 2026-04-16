package com.metallum.client.metal;

import java.util.Locale;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public final class MetalBackendConfig {
	private MetalBackendConfig() {
	}

	public static boolean isMacOs() {
		String osName = System.getProperty("os.name", "");
		return osName.toLowerCase(Locale.ROOT).contains("mac");
	}
}
