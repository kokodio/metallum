package com.metallum.mtl;

import com.metallum.objc.AutoreleasePool;
import com.metallum.objc.Msg;
import com.metallum.objc.NSObject;
import com.metallum.objc.ObjC;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.ADDRESS;

@Environment(EnvType.CLIENT)
public final class MTLCommandQueue extends NSObject {
    private static final Msg COMMAND_BUFFER = Msg.of("commandBuffer", ADDRESS);

    MTLCommandQueue(final MemorySegment handle) {
        super(handle);
    }

    @Override
    public MemorySegment handle() {
        if (ObjC.isNil(handle)) {
            throw new IllegalStateException("MTLCommandQueue is closed");
        }
        return handle;
    }

    public MTLCommandBuffer commandBuffer() {
        try (AutoreleasePool _ = AutoreleasePool.push()) {
            MemorySegment commandBuffer = COMMAND_BUFFER.sendPtr(handle);
            if (ObjC.isNil(commandBuffer)) {
                throw new IllegalStateException("Failed to create MTLCommandBuffer");
            }
            return new MTLCommandBuffer(ObjC.retain(commandBuffer));
        }
    }

    public void close() {
        if (ObjC.isNil(handle)) {
            return;
        }
        ObjC.release(handle);
        handle = MemorySegment.NULL;
    }
}
