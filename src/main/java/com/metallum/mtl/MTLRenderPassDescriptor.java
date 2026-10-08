package com.metallum.mtl;

import com.metallum.objc.Msg;
import com.metallum.objc.NSObject;
import com.metallum.objc.ObjC;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.ADDRESS;

@Environment(EnvType.CLIENT)
public final class MTLRenderPassDescriptor extends NSObject {
    private static final MemorySegment CLS = ObjC.clazz("MTLRenderPassDescriptor");
    private static final Msg ALLOC = Msg.of("alloc", ADDRESS);
    private static final Msg INIT = Msg.of("init", ADDRESS);
    private static final Msg COLOR_ATTACHMENTS = Msg.of("colorAttachments", ADDRESS);
    private static final Msg DEPTH_ATTACHMENT = Msg.of("depthAttachment", ADDRESS);
    private static final Msg STENCIL_ATTACHMENT = Msg.of("stencilAttachment", ADDRESS);

    private MTLRenderPassDescriptor(final MemorySegment handle) {
        super(handle);
    }

    public static MTLRenderPassDescriptor alloc() {
        return new MTLRenderPassDescriptor(ALLOC.sendPtr(CLS));
    }

    public MTLRenderPassDescriptor init() {
        this.handle = INIT.sendPtr(this.handle);
        return this;
    }


    public MTLRenderPassColorAttachmentDescriptorArray colorAttachments() {
        return new MTLRenderPassColorAttachmentDescriptorArray(COLOR_ATTACHMENTS.sendPtr(this.handle));
    }

    public MTLRenderPassDepthAttachmentDescriptor depthAttachment() {
        return new MTLRenderPassDepthAttachmentDescriptor(DEPTH_ATTACHMENT.sendPtr(this.handle));
    }

    public MTLRenderPassStencilAttachmentDescriptor stencilAttachment() {
        return new MTLRenderPassStencilAttachmentDescriptor(STENCIL_ATTACHMENT.sendPtr(this.handle));
    }
}
