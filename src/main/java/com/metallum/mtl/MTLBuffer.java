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
public final class MTLBuffer extends NSObject {
    private static final Msg CONTENTS = Msg.of("contents", ADDRESS);
    private static final Msg LENGTH = Msg.of("length", JAVA_LONG);
    private static final Msg SET_LABEL = Msg.ofVoid("setLabel:", ADDRESS);
    private static final Msg STORAGE_MODE = Msg.of("storageMode", JAVA_LONG);
    private static final Msg NEW_TEXTURE = Msg.of("newTextureWithDescriptor:offset:bytesPerRow:", ADDRESS, ADDRESS, JAVA_LONG, JAVA_LONG);

    MTLBuffer(final MemorySegment handle) {
        super(handle);
        if (handle == null || handle.address() == 0L) {
            throw new IllegalArgumentException("MTLBuffer handle is null");
        }
    }

    public MemorySegment contents() {
        return CONTENTS.sendPtr(handle);
    }

    public long length() {
        return LENGTH.sendLong(handle);
    }

    public MTLStorageMode storageMode() {
        return MTLStorageMode.of(STORAGE_MODE.sendLong(this.handle));
    }

    @Nullable
    public MTLTexture newTexture(final MTLTextureDescriptor descriptor, final long offset, final long bytesPerRow) {
        MemorySegment texture = NEW_TEXTURE.sendPtr(this.handle, descriptor.handle(), offset, bytesPerRow);
        return ObjC.isNil(texture) ? null : new MTLTexture(texture);
    }

    public void setLabel(final String label) {
        MemorySegment nsLabel = ObjC.nsString(label);
        SET_LABEL.send(handle, nsLabel);
        ObjC.release(nsLabel);
    }
}
