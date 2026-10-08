package com.metallum.mtl;

import com.metallum.objc.Msg;
import com.metallum.objc.NSObject;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.ADDRESS;

@Environment(EnvType.CLIENT)
public final class CAMetalDrawable extends NSObject {
    private static final Msg TEXTURE = Msg.of("texture", ADDRESS);

    public CAMetalDrawable(final MemorySegment handle) {
        super(handle);
    }

    public MTLTexture texture() {
        return new MTLTexture(TEXTURE.sendPtr(this.handle));
    }
}
