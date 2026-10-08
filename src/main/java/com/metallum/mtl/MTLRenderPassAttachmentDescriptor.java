package com.metallum.mtl;

import com.metallum.objc.Msg;
import com.metallum.objc.NSObject;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

@Environment(EnvType.CLIENT)
public abstract class MTLRenderPassAttachmentDescriptor extends NSObject {
    private static final Msg SET_TEXTURE = Msg.ofVoid("setTexture:", ADDRESS);
    private static final Msg SET_LOAD_ACTION = Msg.ofVoid("setLoadAction:", JAVA_LONG);
    private static final Msg SET_STORE_ACTION = Msg.ofVoid("setStoreAction:", JAVA_LONG);

    MTLRenderPassAttachmentDescriptor(final MemorySegment handle) {
        super(handle);
    }


    public void setTexture(final MTLTexture texture) {
        SET_TEXTURE.send(this.handle, texture.handle());
    }

    public void setLoadAction(final MTLLoadAction loadAction) {
        SET_LOAD_ACTION.send(this.handle, loadAction.value);
    }

    public void setStoreAction(final MTLStoreAction storeAction) {
        SET_STORE_ACTION.send(this.handle, storeAction.value);
    }
}
