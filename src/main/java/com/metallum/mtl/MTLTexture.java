package com.metallum.mtl;

import com.metallum.objc.Msg;
import com.metallum.objc.NSObject;
import com.metallum.objc.ObjC;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

@Environment(EnvType.CLIENT)
public final class MTLTexture extends NSObject {
    private static final Msg SET_LABEL = Msg.ofVoid("setLabel:", ADDRESS);
    private static final Msg WIDTH = Msg.of("width", JAVA_LONG);
    private static final Msg HEIGHT = Msg.of("height", JAVA_LONG);
    private static final Msg NEW_TEXTURE_VIEW = Msg.of(
            "newTextureViewWithPixelFormat:textureType:levels:slices:",
            ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG);

    MTLTexture(final MemorySegment handle) {
        super(handle);
    }

    public void setLabel(final String label) {
        MemorySegment nsLabel = ObjC.nsString(label);
        SET_LABEL.send(this.handle, nsLabel);
        ObjC.release(nsLabel);
    }

    public long width() {
        return WIDTH.sendLong(this.handle);
    }

    public long height() {
        return HEIGHT.sendLong(this.handle);
    }

    @Nullable
    public MTLTexture newTextureView(final MTLPixelFormat pixelFormat, final MTLTextureType textureType, final NSRange levelRange, final NSRange sliceRange) {
        MemorySegment view = NEW_TEXTURE_VIEW.sendPtr(
                this.handle,
                pixelFormat.value,
                textureType.value,
                levelRange.location(), levelRange.length(),
                sliceRange.location(), sliceRange.length()
        );
        return ObjC.isNil(view) ? null : new MTLTexture(view);
    }
}
