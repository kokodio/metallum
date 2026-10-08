package com.metallum.mtl;

import com.metallum.objc.Msg;
import com.metallum.objc.NSObject;
import com.metallum.objc.ObjC;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.ADDRESS;

@Environment(EnvType.CLIENT)
public final class MTLLibrary extends NSObject {
    private static final Msg NEW_FUNCTION_WITH_NAME = Msg.of("newFunctionWithName:", true, ADDRESS, ADDRESS);

    MTLLibrary(final MemorySegment handle) {
        super(handle);
    }

    @Nullable
    public MTLFunction newFunction(final String name) {
        MemorySegment nsName = ObjC.nsString(name);
        MemorySegment function = NEW_FUNCTION_WITH_NAME.sendPtr(this.handle, nsName);
        ObjC.release(nsName);
        return ObjC.isNil(function) ? null : new MTLFunction(function);
    }
}
