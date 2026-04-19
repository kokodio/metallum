package com.metallum.client.metal;

import ca.weblite.objc.Client;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFWNativeCocoa;

@Environment(EnvType.CLIENT)
public final class MetalCocoaBootstrap {
	private static final String QUARTZ_CORE_FRAMEWORK = "/System/Library/Frameworks/QuartzCore.framework/QuartzCore";
	private static final Client COERCING_CLIENT = Client.getInstance();
	private static final Client RAW_CLIENT = Client.getRawClient();

	private MetalCocoaBootstrap() {
	}

	public static BootstrapContext bootstrap(final long windowHandle) {
		if (!MetalBackendConfig.isMacOs()) {
			throw new IllegalStateException("Metal bootstrap is only available on macOS");
		}

		NativeLibrary.getInstance(QUARTZ_CORE_FRAMEWORK);

		Pointer device = MetalProbe.createSystemDefaultDevice();
		if (MetalProbe.isNullPointer(device)) {
			throw new IllegalStateException("MTLCreateSystemDefaultDevice returned null during Cocoa bootstrap");
		}

		Pointer cocoaWindow = pointerFromHandle(GLFWNativeCocoa.glfwGetCocoaWindow(windowHandle));
		if (MetalProbe.isNullPointer(cocoaWindow)) {
			throw new IllegalStateException("glfwGetCocoaWindow returned null");
		}

		Pointer cocoaView = pointerFromHandle(GLFWNativeCocoa.glfwGetCocoaView(windowHandle));
		if (MetalProbe.isNullPointer(cocoaView)) {
			throw new IllegalStateException("glfwGetCocoaView returned null");
		}

		Pointer metalLayer = RAW_CLIENT.sendPointer("CAMetalLayer", "layer");
		if (MetalProbe.isNullPointer(metalLayer)) {
			throw new IllegalStateException("CAMetalLayer.layer returned null");
		}

		double scale = readBackingScaleFactor(cocoaWindow);
		COERCING_CLIENT.send(cocoaView, "setWantsLayer:", true);
		RAW_CLIENT.send(cocoaView, "setLayer:", metalLayer);
		RAW_CLIENT.send(metalLayer, "setDevice:", device);
		COERCING_CLIENT.send(metalLayer, "setFramebufferOnly:", true);
		COERCING_CLIENT.send(metalLayer, "setOpaque:", true);
		COERCING_CLIENT.send(metalLayer, "setContentsScale:", scale);

		return new BootstrapContext(device, MetalProbe.readDeviceName(device), cocoaWindow, cocoaView, metalLayer, scale);
	}

	private static Pointer pointerFromHandle(final long handle) {
		return handle == 0L ? null : new Pointer(handle);
	}

	private static double readBackingScaleFactor(final Pointer cocoaWindow) {
		Object value = COERCING_CLIENT.send(cocoaWindow, "backingScaleFactor");
		if (value instanceof Number number && number.doubleValue() > 0.0) {
			return number.doubleValue();
		}

		return 1.0;
	}

	@Environment(EnvType.CLIENT)
	public record BootstrapContext(
		Pointer device,
		String deviceName,
		Pointer cocoaWindow,
		Pointer cocoaView,
		Pointer metalLayer,
		double backingScaleFactor
	) {
		public String devicePointerHex() {
			return toHex(this.device);
		}

		public String cocoaWindowPointerHex() {
			return toHex(this.cocoaWindow);
		}

		public String cocoaViewPointerHex() {
			return toHex(this.cocoaView);
		}

		public String metalLayerPointerHex() {
			return toHex(this.metalLayer);
		}

		private static String toHex(@Nullable final Pointer pointer) {
			return MetalProbe.isNullPointer(pointer) ? "0x0" : "0x" + Long.toUnsignedString(Pointer.nativeValue(pointer), 16);
		}
	}
}
