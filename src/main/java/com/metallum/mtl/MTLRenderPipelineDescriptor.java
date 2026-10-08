package com.metallum.mtl;

import com.metallum.objc.Msg;
import com.metallum.objc.NSObject;
import com.metallum.objc.ObjC;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

@Environment(EnvType.CLIENT)
public final class MTLRenderPipelineDescriptor extends NSObject {
    private static final MemorySegment CLS = ObjC.clazz("MTLRenderPipelineDescriptor");
    private static final Msg ALLOC = Msg.of("alloc", ADDRESS);
    private static final Msg INIT = Msg.of("init", ADDRESS);
    private static final Msg SET_LABEL = Msg.ofVoid("setLabel:", ADDRESS);
    private static final Msg SET_VERTEX_FUNCTION = Msg.ofVoid("setVertexFunction:", ADDRESS);
    private static final Msg SET_FRAGMENT_FUNCTION = Msg.ofVoid("setFragmentFunction:", ADDRESS);
    private static final Msg SET_VERTEX_DESCRIPTOR = Msg.ofVoid("setVertexDescriptor:", ADDRESS);
    private static final Msg COLOR_ATTACHMENTS = Msg.of("colorAttachments", ADDRESS);
    private static final Msg SET_DEPTH_ATTACHMENT_PIXEL_FORMAT = Msg.ofVoid("setDepthAttachmentPixelFormat:", JAVA_LONG);
    private static final Msg SET_STENCIL_ATTACHMENT_PIXEL_FORMAT = Msg.ofVoid("setStencilAttachmentPixelFormat:", JAVA_LONG);

    private MTLRenderPipelineDescriptor(final MemorySegment handle) {
        super(handle);
    }

    public static MTLRenderPipelineDescriptor alloc() {
        return new MTLRenderPipelineDescriptor(ALLOC.sendPtr(CLS));
    }

    public MTLRenderPipelineDescriptor init() {
        this.handle = INIT.sendPtr(this.handle);
        return this;
    }


    public void setLabel(final String label) {
        MemorySegment nsLabel = ObjC.nsString(label);
        SET_LABEL.send(this.handle, nsLabel);
        ObjC.release(nsLabel);
    }

    public void setVertexFunction(final MTLFunction vertexFunction) {
        SET_VERTEX_FUNCTION.send(this.handle, vertexFunction.handle());
    }

    public void setFragmentFunction(final MTLFunction fragmentFunction) {
        SET_FRAGMENT_FUNCTION.send(this.handle, fragmentFunction.handle());
    }

    public void setVertexDescriptor(final MTLVertexDescriptor vertexDescriptor) {
        SET_VERTEX_DESCRIPTOR.send(this.handle, vertexDescriptor.handle());
    }

    public MTLRenderPipelineColorAttachmentDescriptorArray colorAttachments() {
        return new MTLRenderPipelineColorAttachmentDescriptorArray(COLOR_ATTACHMENTS.sendPtr(this.handle));
    }

    public void setDepthAttachmentPixelFormat(final MTLPixelFormat depthAttachmentPixelFormat) {
        SET_DEPTH_ATTACHMENT_PIXEL_FORMAT.send(this.handle, depthAttachmentPixelFormat.value);
    }

    public void setStencilAttachmentPixelFormat(final MTLPixelFormat stencilAttachmentPixelFormat) {
        SET_STENCIL_ATTACHMENT_PIXEL_FORMAT.send(this.handle, stencilAttachmentPixelFormat.value);
    }
}
