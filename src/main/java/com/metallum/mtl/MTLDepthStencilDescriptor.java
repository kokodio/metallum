package com.metallum.mtl;

import com.metallum.objc.Msg;
import com.metallum.objc.NSObject;
import com.metallum.objc.ObjC;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.*;

@Environment(EnvType.CLIENT)
public final class MTLDepthStencilDescriptor extends NSObject {
    private static final MemorySegment CLS = ObjC.clazz("MTLDepthStencilDescriptor");
    private static final Msg ALLOC = Msg.of("alloc", ADDRESS);
    private static final Msg INIT = Msg.of("init", ADDRESS);
    private static final Msg SET_DEPTH_COMPARE_FUNCTION = Msg.ofVoid("setDepthCompareFunction:", JAVA_LONG);
    private static final Msg SET_DEPTH_WRITE_ENABLED = Msg.ofVoid("setDepthWriteEnabled:", JAVA_BOOLEAN);

    private MTLDepthStencilDescriptor(final MemorySegment handle) {
        super(handle);
    }

    public static MTLDepthStencilDescriptor alloc() {
        return new MTLDepthStencilDescriptor(ALLOC.sendPtr(CLS));
    }

    public MTLDepthStencilDescriptor init() {
        this.handle = INIT.sendPtr(this.handle);
        return this;
    }


    public void setDepthCompareFunction(final MTLCompareFunction depthCompareFunction) {
        SET_DEPTH_COMPARE_FUNCTION.send(this.handle, depthCompareFunction.value);
    }

    public void setDepthWriteEnabled(final boolean depthWriteEnabled) {
        SET_DEPTH_WRITE_ENABLED.send(this.handle, depthWriteEnabled);
    }
}
