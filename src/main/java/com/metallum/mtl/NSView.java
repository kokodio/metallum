package com.metallum.mtl;

import com.metallum.objc.Msg;
import com.metallum.objc.NSObject;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BOOLEAN;

@Environment(EnvType.CLIENT)
public final class NSView extends NSObject {
    private static final Msg SET_WANTS_LAYER = Msg.ofVoid("setWantsLayer:", JAVA_BOOLEAN);
    private static final Msg SET_LAYER = Msg.ofVoid("setLayer:", ADDRESS);

    public NSView(final MemorySegment handle) {
        super(handle);
    }

    public void setWantsLayer(final boolean wantsLayer) {
        SET_WANTS_LAYER.send(this.handle, wantsLayer);
    }

    public void setLayer(@Nullable final CAMetalLayer layer) {
        SET_LAYER.send(this.handle, layer == null ? MemorySegment.NULL : layer.handle());
    }
}
