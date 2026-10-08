package com.metallum.mtl;

import com.metallum.objc.Msg;
import com.metallum.objc.NSObject;
import com.metallum.objc.ObjC;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.ADDRESS;

@Environment(EnvType.CLIENT)
public final class MTLFunction extends NSObject {
    private static final Msg SET_LABEL = Msg.ofVoid("setLabel:", ADDRESS);

    MTLFunction(final MemorySegment handle) {
        super(handle);
    }

    public void setLabel(final String label) {
        MemorySegment nsLabel = ObjC.nsString(label);
        SET_LABEL.send(this.handle, nsLabel);
        ObjC.release(nsLabel);
    }
}
