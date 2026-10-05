package com.metallum.mtl;

import com.metallum.objc.Msg;
import com.metallum.objc.ObjC;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.*;

@Environment(EnvType.CLIENT)
public final class CAMetalLayer {
    private static final MemorySegment CLS = ObjC.clazz("CAMetalLayer");
    private static final Msg NEW = Msg.of("new", ADDRESS);
    private static final Msg SET_DEVICE = Msg.ofVoid("setDevice:", ADDRESS);
    private static final Msg SET_FRAMEBUFFER_ONLY = Msg.ofVoid("setFramebufferOnly:", JAVA_BOOLEAN);
    private static final Msg SET_OPAQUE = Msg.ofVoid("setOpaque:", JAVA_BOOLEAN);
    private static final Msg SET_CONTENTS_SCALE = Msg.ofVoid("setContentsScale:", JAVA_DOUBLE);
    private static final Msg SET_PIXEL_FORMAT = Msg.ofVoid("setPixelFormat:", JAVA_LONG);
    private static final Msg SET_DRAWABLE_SIZE = Msg.ofVoid("setDrawableSize:", JAVA_DOUBLE, JAVA_DOUBLE);
    private static final Msg SET_ALLOWS_NEXT_DRAWABLE_TIMEOUT = Msg.ofVoid("setAllowsNextDrawableTimeout:", JAVA_BOOLEAN);
    private static final Msg SET_PRESENTS_WITH_TRANSACTION = Msg.ofVoid("setPresentsWithTransaction:", JAVA_BOOLEAN);
    private static final Msg SET_DISPLAY_SYNC_ENABLED = Msg.ofVoid("setDisplaySyncEnabled:", JAVA_BOOLEAN);
    private static final Msg NEXT_DRAWABLE = Msg.of("nextDrawable", true, ADDRESS);
    private static final MemorySegment NSSCREEN = ObjC.clazz("NSScreen");
    private static final Msg MAIN_SCREEN = Msg.of("mainScreen", ADDRESS);
    private static final Msg MAXIMUM_FRAMES_PER_SECOND = Msg.of("maximumFramesPerSecond", JAVA_LONG);
    private static final MethodHandle CA_CURRENT_MEDIA_TIME = ObjC.LINKER.downcallHandle(
            ObjC.QUARTZ_CORE.findOrThrow("CACurrentMediaTime"), FunctionDescriptor.of(JAVA_DOUBLE));

    private final MemorySegment handle;
    private boolean mailbox;
    private long refreshIntervalNanos = 1_000_000_000L / 60;
    private long lastScreenQueryNanos = Long.MIN_VALUE / 2;
    private long mediaTimeOffsetNanos;
    private long refreshAnchorNanos;
    private long lastAnchorNanos = Long.MIN_VALUE / 2;
    private long lastPresentNanos = Long.MIN_VALUE / 2;
    private long lastPresentedRefreshNanos = Long.MIN_VALUE / 2;
    @Nullable
    private CAMetalDrawable probe;
    private long probeNanos = Long.MIN_VALUE / 2;

    public CAMetalLayer(final MTLDevice device, final double contentsScale) {
        this.handle = NEW.sendPtr(CLS);
        if (ObjC.isNil(this.handle)) {
            throw new IllegalStateException("Failed to create CAMetalLayer");
        }
        SET_DEVICE.send(this.handle, device.handle());
        SET_FRAMEBUFFER_ONLY.send(this.handle, true);
        SET_OPAQUE.send(this.handle, true);
        SET_CONTENTS_SCALE.send(this.handle, contentsScale);
    }

    public MemorySegment handle() {
        return this.handle;
    }

    public void configure(final double width, final double height, final boolean immediatePresentMode) {
        SET_PIXEL_FORMAT.send(this.handle, MTLPixelFormat.BGRA8Unorm.value);
        SET_DRAWABLE_SIZE.send(this.handle, width, height);
        SET_ALLOWS_NEXT_DRAWABLE_TIMEOUT.send(this.handle, false);
        SET_PRESENTS_WITH_TRANSACTION.send(this.handle, false);
        SET_DISPLAY_SYNC_ENABLED.send(this.handle, !immediatePresentMode);
        this.mailbox = immediatePresentMode;
    }

    public boolean isMailbox() {
        return this.mailbox;
    }

    @Nullable
    CAMetalDrawable nextDrawable() {
        MemorySegment drawable = NEXT_DRAWABLE.sendPtr(this.handle);
        return ObjC.isNil(drawable) ? null : new CAMetalDrawable(drawable);
    }

    /**
     * With VSync off every frame is rendered, but only the first one after each display refresh is presented. Presenting
     * all of them made nextDrawable block as soon as the three drawables were queued, which capped the frame rate (#11).
     * The refresh phase comes from the presentedTime of one drawable sampled every 250 ms.
     */
    public boolean shouldPresent() {
        if (!this.mailbox) {
            return true;
        }
        long now = System.nanoTime();
        if (now - this.lastScreenQueryNanos > 1_000_000_000L) {
            this.lastScreenQueryNanos = now;
            MemorySegment screen = MAIN_SCREEN.sendPtr(NSSCREEN);
            long fps = ObjC.isNil(screen) ? 0L : MAXIMUM_FRAMES_PER_SECOND.sendLong(screen);
            this.refreshIntervalNanos = 1_000_000_000L / Math.clamp(fps > 0L ? fps : 60L, 30L, 1000L);
            this.mediaTimeOffsetNanos = now - (long) (currentMediaTime() * 1e9);
        }
        updateRefreshAnchor(now);

        if (now - this.lastAnchorNanos > 2_000_000_000L) {
            // no recent presentedTime (starting up, window hidden): roughly one present per refresh
            if (now - this.lastPresentNanos < this.refreshIntervalNanos * 9 / 10) {
                return false;
            }
            this.lastPresentNanos = now;
            return true;
        }
        long refreshNanos = now - Math.floorMod(now - this.refreshAnchorNanos, this.refreshIntervalNanos);
        if (refreshNanos - this.lastPresentedRefreshNanos < this.refreshIntervalNanos / 2) {
            return false;
        }
        this.lastPresentedRefreshNanos = refreshNanos;
        this.lastPresentNanos = now;
        return true;
    }

    void presented(final CAMetalDrawable drawable) {
        long now = System.nanoTime();
        if (this.mailbox && this.probe == null && now - this.probeNanos > 250_000_000L) {
            this.probe = new CAMetalDrawable(ObjC.retain(drawable.handle()));
            this.probeNanos = now;
        }
    }

    private void updateRefreshAnchor(final long now) {
        if (this.probe == null) {
            return;
        }
        double presentedTime = this.probe.presentedTime();
        if (presentedTime > 0.0) {
            this.refreshAnchorNanos = (long) (presentedTime * 1e9) + this.mediaTimeOffsetNanos;
            this.lastAnchorNanos = now;
        } else if (now - this.probeNanos < 100_000_000L) {
            return;
        }
        // release it right away: a drawable we hold can't go back to the layer's pool of three
        ObjC.release(this.probe.handle());
        this.probe = null;
    }

    private static double currentMediaTime() {
        try {
            return (double) CA_CURRENT_MEDIA_TIME.invokeExact();
        } catch (Throwable t) {
            throw new IllegalStateException("CACurrentMediaTime failed", t);
        }
    }
}
