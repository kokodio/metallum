package com.metallum.mtl;

import com.metallum.objc.Msg;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.JAVA_INT;

@Environment(EnvType.CLIENT)
public final class MTLRenderPassStencilAttachmentDescriptor extends MTLRenderPassAttachmentDescriptor {
    private static final Msg SET_CLEAR_STENCIL = Msg.ofVoid("setClearStencil:", JAVA_INT);

    MTLRenderPassStencilAttachmentDescriptor(final MemorySegment handle) {
        super(handle);
    }

    public void setClearStencil(final int clearStencil) {
        SET_CLEAR_STENCIL.send(this.handle, clearStencil);
    }
}
