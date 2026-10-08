package com.metallum.mtl;

import com.metallum.objc.Msg;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;

@Environment(EnvType.CLIENT)
public final class MTLRenderPassColorAttachmentDescriptor extends MTLRenderPassAttachmentDescriptor {
    private static final Msg SET_CLEAR_COLOR = Msg.ofVoid("setClearColor:", JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE);

    MTLRenderPassColorAttachmentDescriptor(final MemorySegment handle) {
        super(handle);
    }

    public void setClearColor(final MTLClearColor clearColor) {
        SET_CLEAR_COLOR.send(this.handle, clearColor.red(), clearColor.green(), clearColor.blue(), clearColor.alpha());
    }
}
