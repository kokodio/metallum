package com.metallum.mtl;

import com.metallum.objc.Msg;
import com.metallum.objc.NSObject;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;

@Environment(EnvType.CLIENT)
public final class NSWindow extends NSObject {
    private static final Msg BACKING_SCALE_FACTOR = Msg.of("backingScaleFactor", JAVA_DOUBLE);

    public NSWindow(final MemorySegment handle) {
        super(handle);
    }

    public double backingScaleFactor() {
        return BACKING_SCALE_FACTOR.sendDouble(this.handle);
    }
}
