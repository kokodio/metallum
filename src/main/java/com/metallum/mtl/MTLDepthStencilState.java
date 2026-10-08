package com.metallum.mtl;

import com.metallum.objc.NSObject;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.lang.foreign.MemorySegment;

@Environment(EnvType.CLIENT)
public final class MTLDepthStencilState extends NSObject {
    MTLDepthStencilState(final MemorySegment handle) {
        super(handle);
    }
}
