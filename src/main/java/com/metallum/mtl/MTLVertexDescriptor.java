package com.metallum.mtl;

import com.metallum.objc.Msg;
import com.metallum.objc.NSObject;
import com.metallum.objc.ObjC;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.ADDRESS;

@Environment(EnvType.CLIENT)
public final class MTLVertexDescriptor extends NSObject {
    private static final MemorySegment CLS = ObjC.clazz("MTLVertexDescriptor");
    private static final Msg ALLOC = Msg.of("alloc", ADDRESS);
    private static final Msg INIT = Msg.of("init", ADDRESS);
    private static final Msg ATTRIBUTES = Msg.of("attributes", ADDRESS);
    private static final Msg LAYOUTS = Msg.of("layouts", ADDRESS);

    private MTLVertexDescriptor(final MemorySegment handle) {
        super(handle);
    }

    public static MTLVertexDescriptor alloc() {
        return new MTLVertexDescriptor(ALLOC.sendPtr(CLS));
    }

    public MTLVertexDescriptor init() {
        this.handle = INIT.sendPtr(this.handle);
        return this;
    }


    public MTLVertexAttributeDescriptorArray attributes() {
        return new MTLVertexAttributeDescriptorArray(ATTRIBUTES.sendPtr(this.handle));
    }

    public MTLVertexBufferLayoutDescriptorArray layouts() {
        return new MTLVertexBufferLayoutDescriptorArray(LAYOUTS.sendPtr(this.handle));
    }
}
