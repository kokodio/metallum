package com.metallum.mtl;

import com.metallum.objc.Msg;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;

@Environment(EnvType.CLIENT)
public final class MTLRenderPassDepthAttachmentDescriptor extends MTLRenderPassAttachmentDescriptor {
    private static final Msg SET_CLEAR_DEPTH = Msg.ofVoid("setClearDepth:", JAVA_DOUBLE);

    MTLRenderPassDepthAttachmentDescriptor(final MemorySegment handle) {
        super(handle);
    }

    public void setClearDepth(final double clearDepth) {
        SET_CLEAR_DEPTH.send(this.handle, clearDepth);
    }
}
