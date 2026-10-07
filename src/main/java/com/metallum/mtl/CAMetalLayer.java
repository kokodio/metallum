package com.metallum.mtl;

import com.metallum.objc.Msg;
import com.metallum.objc.ObjC;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;

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

    private final MemorySegment handle;

    public CAMetalLayer(final MTLDevice device, final double contentsScale) {
        this.handle = NEW.sendPtr(CLS);
        if (ObjC.isNil(this.handle)) {
            throw new IllegalStateException("Failed to create CAMetalLayer");
        }
        SET_DEVICE.send(this.handle, device.handle());
        SET_FRAMEBUFFER_ONLY.send(this.handle, false);
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
    }

    @Nullable
    CAMetalDrawable nextDrawable() {
        MemorySegment drawable = NEXT_DRAWABLE.sendPtr(this.handle);
        return ObjC.isNil(drawable) ? null : new CAMetalDrawable(drawable);
    }
}
