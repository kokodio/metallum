package com.metallum.mtl;

import com.metallum.objc.Msg;
import com.metallum.objc.NSObject;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

@Environment(EnvType.CLIENT)
public final class MTLRenderPassColorAttachmentDescriptorArray extends NSObject {
    private static final Msg OBJECT_AT_INDEXED_SUBSCRIPT = Msg.of("objectAtIndexedSubscript:", ADDRESS, JAVA_LONG);

    public MTLRenderPassColorAttachmentDescriptorArray(final MemorySegment handle) {
        super(handle);
    }

    public MTLRenderPassColorAttachmentDescriptor object(final long index) {
        return new MTLRenderPassColorAttachmentDescriptor(OBJECT_AT_INDEXED_SUBSCRIPT.sendPtr(this.handle, index));
    }
}
