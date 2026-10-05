package com.metallum.mtl;

import com.metallum.objc.Msg;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;

@Environment(EnvType.CLIENT)
public record CAMetalDrawable(MemorySegment handle) {
    private static final Msg TEXTURE = Msg.of("texture", ADDRESS);
    private static final Msg PRESENTED_TIME = Msg.of("presentedTime", JAVA_DOUBLE);

    public MemorySegment texture() {
        return TEXTURE.sendPtr(handle);
    }

    public double presentedTime() {
        return PRESENTED_TIME.sendDouble(handle);
    }
}
