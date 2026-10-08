package com.metallum.mtl;

import com.metallum.objc.Msg;
import com.metallum.objc.NSObject;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.JAVA_LONG;

@Environment(EnvType.CLIENT)
public final class MTLVertexAttributeDescriptor extends NSObject {
    private static final Msg SET_FORMAT = Msg.ofVoid("setFormat:", JAVA_LONG);
    private static final Msg SET_OFFSET = Msg.ofVoid("setOffset:", JAVA_LONG);
    private static final Msg SET_BUFFER_INDEX = Msg.ofVoid("setBufferIndex:", JAVA_LONG);

    public MTLVertexAttributeDescriptor(final MemorySegment handle) {
        super(handle);
    }

    public void setFormat(final MTLVertexFormat format) {
        SET_FORMAT.send(this.handle, format.value);
    }

    public void setOffset(final long offset) {
        SET_OFFSET.send(this.handle, offset);
    }

    public void setBufferIndex(final long bufferIndex) {
        SET_BUFFER_INDEX.send(this.handle, bufferIndex);
    }
}
