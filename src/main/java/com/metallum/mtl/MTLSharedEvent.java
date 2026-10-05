package com.metallum.mtl;

import com.metallum.objc.Msg;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.JAVA_LONG;

@Environment(EnvType.CLIENT)
public record MTLSharedEvent(MemorySegment handle) {
    private static final Msg SIGNALED_VALUE = Msg.of("signaledValue", JAVA_LONG);

    public long signaledValue() {
        return SIGNALED_VALUE.sendLong(handle);
    }
}
